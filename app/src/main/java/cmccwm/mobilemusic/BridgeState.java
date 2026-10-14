package cmccwm.mobilemusic;

import android.content.Context;
import android.icu.text.Transliterator;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BridgeState {
    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?\\]");
    private static final Transliterator TO_TRADITIONAL = Transliterator.getInstance("Simplified-Traditional");
    private static final Transliterator TO_SIMPLIFIED = Transliterator.getInstance("Traditional-Simplified");
    private static volatile BridgeState INSTANCE;
    static BridgeState get() { return INSTANCE; }
    static synchronized BridgeState ensure(Context context) {
        if (INSTANCE == null) INSTANCE = new BridgeState(context.getApplicationContext());
        return INSTANCE;
    }

    static final class Snapshot {
        final String title, artist, previous, current, next, status;
        final boolean playing;
        Snapshot(String title, String artist, String previous, String current, String next, String status, boolean playing) {
            this.title = title; this.artist = artist; this.previous = previous; this.current = current;
            this.next = next; this.status = status; this.playing = playing;
        }
    }

    private static final class LyricLine {
        final long timeMs;
        final String simplified, traditional, translatedSimplified, translatedTraditional;
        LyricLine(long timeMs, String simplified, String traditional,
                  String translatedSimplified, String translatedTraditional) {
            this.timeMs = timeMs;
            this.simplified = simplified;
            this.traditional = traditional;
            this.translatedSimplified = translatedSimplified;
            this.translatedTraditional = translatedTraditional;
        }
    }

    private static final class TimedText {
        final long timeMs;
        final String text;
        TimedText(long timeMs, String text) { this.timeMs = timeMs; this.text = text; }
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private MediaController spotify;
    private String mediaId = "", title = "等待 Spotify", artist = "", album = "", status = "等待 Spotify 媒体通知";
    private long duration;
    private List<LyricLine> lines = Collections.emptyList();

    private BridgeState(Context context) { this.context = context; }

    synchronized String getStatusText() { return status; }
    synchronized boolean hasSpotifySession() { return spotify != null; }

    synchronized void attachSpotify(MediaController controller) {
        spotify = controller;
        if (controller == null) {
            status = "未找到 Spotify 媒体会话";
            DiagnosticLog.add(context, "BridgeState 收到空 MediaController");
            return;
        }
        MediaMetadata metadata = controller.getMetadata();
        if (metadata == null) {
            status = "已连接 Spotify，等待歌曲信息";
            DiagnosticLog.add(context, "已连接 Spotify，但 metadata 为空");
            return;
        }
        String newTitle = value(metadata, MediaMetadata.METADATA_KEY_TITLE);
        String newArtist = value(metadata, MediaMetadata.METADATA_KEY_ARTIST);
        String newAlbum = value(metadata, MediaMetadata.METADATA_KEY_ALBUM);
        long newDuration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        String newId = newTitle + "|" + newArtist + "|" + newDuration;
        title = TextUtils.isEmpty(newTitle) ? "Spotify" : newTitle;
        artist = TextUtils.isEmpty(newArtist) ? "未知歌手" : newArtist;
        album = newAlbum;
        duration = newDuration;
        if (!newId.equals(mediaId)) {
            DiagnosticLog.add(context, "检测到新媒体：标题=" + !TextUtils.isEmpty(newTitle)
                    + "，歌手=" + !TextUtils.isEmpty(newArtist) + "，时长毫秒=" + newDuration);
            mediaId = newId;
            lines = Collections.emptyList();
            status = "正在匹配歌词：" + title;
            // Keep display fallbacks (for example "未知歌手") out of lyric queries.
            // Some Spotify releases genuinely expose an empty artist/album; searching
            // the provider for the UI fallback text prevents otherwise exact matches.
            fetchLyrics(newId, newTitle, newArtist, newAlbum, newDuration);
        }
    }

    synchronized void detachSpotify() {
        spotify = null;
        status = "Spotify 媒体会话已结束";
        DiagnosticLog.add(context, "Spotify 媒体会话已结束");
    }

    synchronized Snapshot snapshot() {
        PlaybackState playback = spotify == null ? null : spotify.getPlaybackState();
        boolean playing = playback != null && (playback.getState() == PlaybackState.STATE_PLAYING || playback.getState() == PlaybackState.STATE_BUFFERING);
        long position = playback == null ? 0L : playback.getPosition();
        if (playing && playback.getLastPositionUpdateTime() > 0) {
            position += (long) ((SystemClock.elapsedRealtime() - playback.getLastPositionUpdateTime()) * playback.getPlaybackSpeed());
        }
        int index = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).timeMs <= position + 120L) index = i; else break;
        }
        boolean simplified = context.getSharedPreferences(LyricsOverlayService.PREFS, Context.MODE_PRIVATE)
                .getInt(LyricsOverlayService.KEY_CHINESE_SCRIPT, LyricsOverlayService.SCRIPT_TRADITIONAL)
                == LyricsOverlayService.SCRIPT_SIMPLIFIED;
        String previous = index > 0 ? display(lines.get(index - 1), simplified, false) : "";
        String current = index >= 0 ? display(lines.get(index), simplified, true) : (lines.isEmpty() ? status : "♪");
        String next = index + 1 < lines.size() ? display(lines.get(index + 1), simplified, false) : "";
        return new Snapshot(title, artist, previous, current, next, status, playing);
    }

    private static String display(LyricLine line, boolean simplified, boolean includeTranslation) {
        String original = simplified ? line.simplified : line.traditional;
        String translated = simplified ? line.translatedSimplified : line.translatedTraditional;
        return includeTranslation && !TextUtils.isEmpty(translated) ? original + "\n" + translated : original;
    }

    private void fetchLyrics(String requestedId, String t, String a, String al, long d) {
        network.execute(() -> {
            String found = "", translated = "", error = null;
            String source = "";
            try {
                LrcLibClient.Result result = LrcLibClient.fetch(t, a, al, d);
                found = result.lrc; translated = result.translatedLrc; source = result.source;
            } catch (Exception e) { error = e.getMessage(); }
            final List<LyricLine> parsed = parse(found, translated);
            final String failure = error;
            final String lyricSource = source;
            main.post(() -> {
                synchronized (BridgeState.this) {
                    if (!requestedId.equals(mediaId)) return;
                    lines = parsed;
                    status = !parsed.isEmpty() ? "✓ 已找到同步歌词（" + lyricSource + "）：" + title
                            : (failure == null ? "未找到同步歌词：" + title : "歌词请求失败：" + failure);
                    boolean hasTranslation = hasTranslation(parsed);
                    DiagnosticLog.add(context, !parsed.isEmpty()
                            ? "歌词匹配成功，来源=" + lyricSource + "，行数=" + parsed.size()
                            + "，含翻译=" + hasTranslation
                            : "歌词匹配失败，异常=" + (failure == null ? "无" : failure));
                }
            });
        });
    }

    private static List<LyricLine> parse(String lrc, String translatedLrc) {
        ArrayList<LyricLine> result = new ArrayList<>();
        List<TimedText> originals = parseTimedText(lrc);
        List<TimedText> translations = parseTimedText(translatedLrc);
        String[] matchedTranslations = matchTranslations(originals, translations);
        for (int originalIndex = 0; originalIndex < originals.size(); originalIndex++) {
            TimedText original = originals.get(originalIndex);
            String translated = matchedTranslations[originalIndex];
            if (!containsCjk(translated) || normalizeText(translated).equals(normalizeText(original.text))) translated = "";
            String traditional = TO_TRADITIONAL.transliterate(original.text);
            if (isCreditLine(traditional)) continue;
            String simplified = TO_SIMPLIFIED.transliterate(original.text);
            String translatedTraditional = TextUtils.isEmpty(translated) ? "" : TO_TRADITIONAL.transliterate(translated);
            String translatedSimplified = TextUtils.isEmpty(translated) ? "" : TO_SIMPLIFIED.transliterate(translated);
            result.add(new LyricLine(original.timeMs, simplified, traditional,
                    translatedSimplified, translatedTraditional));
        }
        result.sort(Comparator.comparingLong(line -> line.timeMs));
        return result;
    }

    private static List<TimedText> parseTimedText(String lrc) {
        ArrayList<TimedText> result = new ArrayList<>();
        if (lrc == null || lrc.isEmpty()) return result;
        for (String raw : lrc.split("\\n")) {
            Matcher matcher = TIME.matcher(raw);
            int end = 0;
            ArrayList<Long> times = new ArrayList<>();
            while (matcher.find()) {
                long minutes = Long.parseLong(matcher.group(1));
                long seconds = Long.parseLong(matcher.group(2));
                String fraction = matcher.group(3);
                long millis = 0;
                if (fraction != null) {
                    millis = Long.parseLong(fraction);
                    if (fraction.length() == 1) millis *= 100;
                    else if (fraction.length() == 2) millis *= 10;
                }
                times.add(minutes * 60000L + seconds * 1000L + millis);
                end = matcher.end();
            }
            String text = raw.substring(Math.min(end, raw.length())).trim();
            if (text.isEmpty()) continue;
            for (Long time : times) result.add(new TimedText(time, text));
        }
        result.sort(Comparator.comparingLong(line -> line.timeMs));
        return result;
    }

    private static String[] matchTranslations(List<TimedText> originals, List<TimedText> translations) {
        String[] matched = new String[originals.size()];
        boolean[] used = new boolean[translations.size()];
        // Reserve exact/near-exact lines first so a sparse translation cannot be stolen
        // by the preceding original line. Then fill timestamp drift up to 3.5 seconds.
        matchTranslationPass(originals, translations, matched, used, 500L);
        matchTranslationPass(originals, translations, matched, used, 3500L);
        for (int i = 0; i < matched.length; i++) if (matched[i] == null) matched[i] = "";
        return matched;
    }

    private static void matchTranslationPass(List<TimedText> originals, List<TimedText> translations,
                                             String[] matched, boolean[] used, long toleranceMs) {
        for (int originalIndex = 0; originalIndex < originals.size(); originalIndex++) {
            if (matched[originalIndex] != null) continue;
            int bestIndex = -1;
            long bestDelta = toleranceMs + 1L;
            for (int translationIndex = 0; translationIndex < translations.size(); translationIndex++) {
                TimedText candidate = translations.get(translationIndex);
                if (used[translationIndex] || !containsCjk(candidate.text)) continue;
                long delta = Math.abs(candidate.timeMs - originals.get(originalIndex).timeMs);
                if (delta < bestDelta) { bestDelta = delta; bestIndex = translationIndex; }
            }
            if (bestIndex >= 0) {
                used[bestIndex] = true;
                matched[originalIndex] = translations.get(bestIndex).text;
            }
        }
    }

    private static boolean hasTranslation(List<LyricLine> lines) {
        for (LyricLine line : lines) if (!TextUtils.isEmpty(line.translatedSimplified)) return true;
        return false;
    }

    private static boolean containsCjk(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= '\u3400' && c <= '\u4dbf') || (c >= '\u4e00' && c <= '\u9fff')
                    || (c >= '\uf900' && c <= '\ufaff')) return true;
        }
        return false;
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.replaceAll("[\\p{P}\\p{Z}\\s]+", "").toLowerCase();
    }

    private static boolean isCreditLine(String text) {
        String compact = text.replace(" ", "").toLowerCase();
        return compact.startsWith("作詞:") || compact.startsWith("作詞：")
                || compact.startsWith("作曲:") || compact.startsWith("作曲：")
                || compact.startsWith("詞lyricist") || compact.startsWith("曲composer")
                || compact.startsWith("編曲") || compact.startsWith("製作人")
                || compact.startsWith("和聲") || compact.startsWith("混音")
                || compact.startsWith("母帶") || compact.startsWith("企劃")
                || compact.startsWith("監製") || compact.startsWith("出品人")
                || compact.contains("版權所有未經許可請勿使用");
    }

    private static String value(MediaMetadata metadata, String key) {
        CharSequence value = metadata.getText(key);
        return value == null ? "" : value.toString();
    }
}
