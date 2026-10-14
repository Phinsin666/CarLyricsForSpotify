package cmccwm.mobilemusic;

import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Multi-source synchronized lyric lookup. Kept under the original class name so updates stay small. */
final class LrcLibClient {
    static final class Result {
        final String lrc, translatedLrc, source;
        Result(String lrc, String translatedLrc, String source) {
            this.lrc = lrc == null ? "" : lrc;
            this.translatedLrc = translatedLrc == null ? "" : translatedLrc;
            this.source = source;
        }
    }

    private static final class SourceLyrics {
        final String lrc, translatedLrc;
        SourceLyrics(String lrc, String translatedLrc) {
            this.lrc = lrc == null ? "" : lrc.trim();
            this.translatedLrc = translatedLrc == null ? "" : translatedLrc.trim();
        }
    }

    private static final class SongCandidate {
        final JSONObject song;
        final long score;
        SongCandidate(JSONObject song, long score) {
            this.song = song;
            this.score = score;
        }
    }

    static Result fetch(String title, String artist, String album, long durationMs) throws Exception {
        SourceLyrics netease = null;
        SourceLyrics qq = null;
        try {
            netease = fetchNetEase(title, artist, durationMs);
        } catch (Exception ignored) { }
        try {
            qq = fetchQqMusic(title, artist, durationMs);
        } catch (Exception ignored) { }

        // Do not stop at the first source that merely has original lyrics. Some NetEase
        // tracks have an empty or partial tlyric while QQ has a complete translation (and
        // vice versa). Prefer the source with the most usable Chinese translation lines.
        SourceLyrics preferred = betterTranslatedSource(netease, qq);
        if (preferred != null && !preferred.lrc.isEmpty()) {
            String source = preferred == qq ? "QQ音乐" : "网易云音乐";
            return new Result(normalizeTimestamp(preferred.lrc),
                    normalizeTimestamp(preferred.translatedLrc), source);
        }
        String lrclib = fetchLrcLib(title, artist, album, durationMs);
        return new Result(normalizeTimestamp(lrclib), "", "LRCLIB");
    }

    private static SourceLyrics betterTranslatedSource(SourceLyrics first, SourceLyrics second) {
        boolean firstHasLyrics = first != null && !first.lrc.isEmpty();
        boolean secondHasLyrics = second != null && !second.lrc.isEmpty();
        if (!firstHasLyrics) return secondHasLyrics ? second : null;
        if (!secondHasLyrics) return first;
        int firstScore = translationLineCount(first.translatedLrc);
        int secondScore = translationLineCount(second.translatedLrc);
        return secondScore > firstScore ? second : first;
    }

    private static int translationLineCount(String lrc) {
        if (lrc == null || lrc.isEmpty()) return 0;
        int count = 0;
        for (String line : lrc.split("\\n")) {
            if (line.matches(".*\\[\\d{1,3}:\\d{2}(?:\\.\\d{1,3})?\\].*") && containsCjk(line)) count++;
        }
        return count;
    }

    private static SourceLyrics fetchNetEase(String title, String artist, long durationMs) throws Exception {
        for (String words : searchQueries(title, artist)) {
            Uri search = Uri.parse("https://music.163.com/api/search/get/web").buildUpon()
                    .appendQueryParameter("s", words)
                    .appendQueryParameter("type", "1").appendQueryParameter("offset", "0")
                    .appendQueryParameter("total", "true").appendQueryParameter("limit", "10").build();
            JSONObject root;
            try {
                root = getObject(search.toString(), false, "https://music.163.com/");
            } catch (Exception ignored) { continue; }
            JSONObject result = root == null ? null : root.optJSONObject("result");
            JSONArray songs = result == null ? null : result.optJSONArray("songs");
            List<JSONObject> candidates = chooseSongs(songs, title, artist, durationMs, true);
            for (int i = 0; i < Math.min(5, candidates.size()); i++) {
                long id = candidates.get(i).optLong("id", 0);
                if (id == 0) continue;
                Uri lyricUrl = Uri.parse("https://music.163.com/api/song/lyric").buildUpon()
                        .appendQueryParameter("id", Long.toString(id)).appendQueryParameter("lv", "-1")
                        .appendQueryParameter("kv", "-1").appendQueryParameter("tv", "-1").build();
                JSONObject lyricRoot;
                try {
                    lyricRoot = getObject(lyricUrl.toString(), false, "https://music.163.com/");
                } catch (Exception ignored) { continue; }
                JSONObject lrc = lyricRoot == null ? null : lyricRoot.optJSONObject("lrc");
                JSONObject translated = lyricRoot == null ? null : lyricRoot.optJSONObject("tlyric");
                SourceLyrics lyrics = new SourceLyrics(lrc == null ? "" : lrc.optString("lyric", ""),
                        translated == null ? "" : translated.optString("lyric", ""));
                if (hasTimedLyrics(lyrics.lrc)) return lyrics;
            }
        }
        return new SourceLyrics("", "");
    }

    private static SourceLyrics fetchQqMusic(String title, String artist, long durationMs) throws Exception {
        for (String words : searchQueries(title, artist)) {
            Uri search = Uri.parse("https://c.y.qq.com/soso/fcgi-bin/client_search_cp").buildUpon()
                    .appendQueryParameter("w", words).appendQueryParameter("p", "1")
                    .appendQueryParameter("n", "10").appendQueryParameter("format", "json").build();
            JSONObject root;
            try {
                root = getObject(search.toString(), false, "https://y.qq.com/");
            } catch (Exception ignored) { continue; }
            JSONObject data = root == null ? null : root.optJSONObject("data");
            JSONObject song = data == null ? null : data.optJSONObject("song");
            JSONArray songs = song == null ? null : song.optJSONArray("list");
            List<JSONObject> candidates = chooseSongs(songs, title, artist, durationMs, false);
            for (int i = 0; i < Math.min(5, candidates.size()); i++) {
                String mid = candidates.get(i).optString("songmid", "");
                if (mid.isEmpty()) continue;
                Uri lyricUrl = Uri.parse("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg").buildUpon()
                        .appendQueryParameter("songmid", mid).appendQueryParameter("format", "json")
                        .appendQueryParameter("nobase64", "1").appendQueryParameter("g_tk", "5381").build();
                JSONObject lyricRoot;
                try {
                    lyricRoot = getObject(lyricUrl.toString(), false, "https://y.qq.com/");
                } catch (Exception ignored) { continue; }
                SourceLyrics lyrics = new SourceLyrics(
                        lyricRoot == null ? "" : decodeEntities(lyricRoot.optString("lyric", "")),
                        lyricRoot == null ? "" : decodeEntities(lyricRoot.optString("trans", "")));
                if (hasTimedLyrics(lyrics.lrc)) return lyrics;
            }
        }
        return new SourceLyrics("", "");
    }

    private static List<JSONObject> chooseSongs(JSONArray songs, String wantedTitle, String wantedArtist,
                                                long durationMs, boolean netease) {
        ArrayList<SongCandidate> matches = new ArrayList<>();
        ArrayList<JSONObject> ordered = new ArrayList<>();
        if (songs == null) return ordered;
        String targetTitle = normalizeMatch(wantedTitle);
        String targetArtist = normalizeMatch(wantedArtist);
        for (int i = 0; i < songs.length(); i++) {
            JSONObject item = songs.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString(netease ? "name" : "songname", "");
            String candidateTitle = normalizeMatch(name);
            String candidateArtist = artistNames(item.optJSONArray(netease ? "artists" : "singer"));
            long sourceDuration = netease ? item.optLong("duration", 0) : item.optLong("interval", 0) * 1000L;
            long delta = durationMs > 0 && sourceDuration > 0 ? Math.abs(durationMs - sourceDuration) : i * 1000L;
            boolean exactTitle = candidateTitle.equals(targetTitle);
            boolean relatedTitle = !candidateTitle.isEmpty() && !targetTitle.isEmpty()
                    && (candidateTitle.contains(targetTitle) || targetTitle.contains(candidateTitle));
            if (!exactTitle && !relatedTitle) continue;
            if (durationMs > 0 && sourceDuration > 0 && delta > 35000L) continue;
            long score = delta;
            if (exactTitle) score -= 30000L; else score -= 12000L;
            String normalizedCandidateArtist = normalizeMatch(candidateArtist);
            if (!targetArtist.isEmpty() && !normalizedCandidateArtist.isEmpty()
                    && (targetArtist.contains(normalizedCandidateArtist) || normalizedCandidateArtist.contains(targetArtist))) score -= 15000L;
            matches.add(new SongCandidate(item, score));
        }
        matches.sort((left, right) -> Long.compare(left.score, right.score));
        for (SongCandidate match : matches) ordered.add(match.song);
        return ordered;
    }

    private static boolean hasTimedLyrics(String lrc) {
        return lrc != null && lrc.matches("(?s).*\\[\\d{1,3}:\\d{2}(?:\\.\\d{1,3})?\\].*");
    }

    private static String fetchLrcLib(String title, String artist, String album, long durationMs) throws Exception {
        boolean preferOriginalCjk = containsCjk(title) || containsCjk(artist);
        Uri exactUri = Uri.parse("https://lrclib.net/api/get").buildUpon()
                .appendQueryParameter("track_name", clean(title)).appendQueryParameter("artist_name", clean(artist))
                .appendQueryParameter("album_name", clean(album))
                .appendQueryParameter("duration", Long.toString(Math.max(0, Math.round(durationMs / 1000.0)))).build();
        JSONObject exact = getObject(exactUri.toString(), true, null);
        String exactLrc = lyrics(exact);
        if (!exactLrc.isEmpty() && (!preferOriginalCjk || containsCjk(exactLrc))) return exactLrc;

        Uri search = Uri.parse("https://lrclib.net/api/search").buildUpon()
                .appendQueryParameter("track_name", clean(title)).appendQueryParameter("artist_name", clean(artist)).build();
        JSONArray results = getArray(search.toString());
        JSONObject best = null;
        long bestDelta = Long.MAX_VALUE;
        int bestCjkCount = -1;
        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            String candidate = lyrics(item);
            if (item == null || candidate.isEmpty()) continue;
            long d = Math.round(item.optDouble("duration", 0) * 1000.0);
            long delta = durationMs > 0 && d > 0 ? Math.abs(durationMs - d) : i;
            int cjkCount = countCjk(candidate);
            if (preferOriginalCjk) {
                if (cjkCount > bestCjkCount || (cjkCount == bestCjkCount && delta < bestDelta)) {
                    best = item; bestDelta = delta; bestCjkCount = cjkCount;
                }
            } else if (delta < bestDelta) { best = item; bestDelta = delta; }
        }
        if (best != null) return lyrics(best);
        return exactLrc;
    }

    private static String searchWords(String title, String artist) {
        String cleanedTitle = clean(title).replaceAll("\\s*[-–—]\\s*", " ");
        String firstArtist = artist == null ? "" : artist.split("[,，/&、]")[0].trim();
        return (cleanedTitle + " " + firstArtist).trim();
    }

    private static List<String> searchQueries(String title, String artist) {
        ArrayList<String> queries = new ArrayList<>();
        addQuery(queries, searchWords(title, artist));
        String cleanedTitle = clean(title);
        String[] titleParts = cleanedTitle.split("\\s*[-–—]\\s*", 2);
        if (titleParts.length > 1) {
            addQuery(queries, searchWords(titleParts[0], artist));
            addQuery(queries, titleParts[0].trim());
        }
        return queries;
    }

    private static void addQuery(List<String> queries, String value) {
        String query = value == null ? "" : value.trim();
        if (!query.isEmpty() && !queries.contains(query)) queries.add(query);
    }

    private static String artistNames(JSONArray artists) {
        if (artists == null) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist != null) result.append(' ').append(artist.optString("name", ""));
        }
        return result.toString();
    }

    private static String normalizeMatch(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[\\p{P}\\p{Z}\\s]+", "");
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("(?i)\\s*[-–—]?\\s*(remaster(ed)?|live|radio edit|explicit).*?$", "").trim();
    }

    private static boolean containsCjk(String value) { return countCjk(value) > 0; }
    private static int countCjk(String value) {
        if (value == null) return 0;
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= '\u3400' && c <= '\u4dbf') || (c >= '\u4e00' && c <= '\u9fff') || (c >= '\uf900' && c <= '\ufaff')) count++;
        }
        return count;
    }

    private static String decodeEntities(String value) {
        return value.replace("&#58;", ":").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
    }

    private static String lyrics(JSONObject object) {
        if (object == null) return "";
        String value = object.optString("syncedLyrics", "");
        return "null".equals(value) ? "" : value.trim();
    }

    private static String normalizeTimestamp(String lrc) {
        return lrc == null ? "" : lrc.replaceAll("(?m)(\\[\\d+:\\d{2}\\.)(\\d{2})(\\])", "$1$20$3");
    }

    private static JSONObject getObject(String url, boolean allow404, String referer) throws Exception {
        String raw = request(url, allow404, referer);
        return raw.isEmpty() ? null : new JSONObject(raw);
    }

    private static JSONArray getArray(String url) throws Exception {
        String raw = request(url, false, null);
        return raw.isEmpty() ? new JSONArray() : new JSONArray(raw);
    }

    private static String request(String url, boolean allow404, String referer) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 SpotifyOverlayLyrics/2.3");
        if (referer != null) connection.setRequestProperty("Referer", referer);
        int code = connection.getResponseCode();
        if (allow404 && code == 404) return "";
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        try (InputStream input = connection.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
            return result.toString();
        } finally { connection.disconnect(); }
    }
}
