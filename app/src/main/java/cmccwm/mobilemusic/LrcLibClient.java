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

    static Result fetch(String title, String artist, String album, long durationMs) throws Exception {
        try {
            SourceLyrics netease = fetchNetEase(title, artist, durationMs);
            if (!netease.lrc.isEmpty()) return new Result(normalizeTimestamp(netease.lrc),
                    normalizeTimestamp(netease.translatedLrc), "网易云音乐");
        } catch (Exception ignored) { }
        try {
            SourceLyrics qq = fetchQqMusic(title, artist, durationMs);
            if (!qq.lrc.isEmpty()) return new Result(normalizeTimestamp(qq.lrc),
                    normalizeTimestamp(qq.translatedLrc), "QQ音乐");
        } catch (Exception ignored) { }
        String lrclib = fetchLrcLib(title, artist, album, durationMs);
        return new Result(normalizeTimestamp(lrclib), "", "LRCLIB");
    }

    private static SourceLyrics fetchNetEase(String title, String artist, long durationMs) throws Exception {
        Uri search = Uri.parse("https://music.163.com/api/search/get/web").buildUpon()
                .appendQueryParameter("s", searchWords(title, artist))
                .appendQueryParameter("type", "1").appendQueryParameter("offset", "0")
                .appendQueryParameter("total", "true").appendQueryParameter("limit", "10").build();
        JSONObject root = getObject(search.toString(), false, "https://music.163.com/");
        JSONObject result = root == null ? null : root.optJSONObject("result");
        JSONArray songs = result == null ? null : result.optJSONArray("songs");
        JSONObject best = chooseSong(songs, title, artist, durationMs, true);
        if (best == null) return new SourceLyrics("", "");
        long id = best.optLong("id", 0);
        if (id == 0) return new SourceLyrics("", "");
        Uri lyricUrl = Uri.parse("https://music.163.com/api/song/lyric").buildUpon()
                .appendQueryParameter("id", Long.toString(id)).appendQueryParameter("lv", "-1")
                .appendQueryParameter("kv", "-1").appendQueryParameter("tv", "-1").build();
        JSONObject lyricRoot = getObject(lyricUrl.toString(), false, "https://music.163.com/");
        JSONObject lrc = lyricRoot == null ? null : lyricRoot.optJSONObject("lrc");
        JSONObject translated = lyricRoot == null ? null : lyricRoot.optJSONObject("tlyric");
        return new SourceLyrics(lrc == null ? "" : lrc.optString("lyric", ""),
                translated == null ? "" : translated.optString("lyric", ""));
    }

    private static SourceLyrics fetchQqMusic(String title, String artist, long durationMs) throws Exception {
        Uri search = Uri.parse("https://c.y.qq.com/soso/fcgi-bin/client_search_cp").buildUpon()
                .appendQueryParameter("w", searchWords(title, artist)).appendQueryParameter("p", "1")
                .appendQueryParameter("n", "10").appendQueryParameter("format", "json").build();
        JSONObject root = getObject(search.toString(), false, "https://y.qq.com/");
        JSONObject data = root == null ? null : root.optJSONObject("data");
        JSONObject song = data == null ? null : data.optJSONObject("song");
        JSONArray songs = song == null ? null : song.optJSONArray("list");
        JSONObject best = chooseSong(songs, title, artist, durationMs, false);
        if (best == null) return new SourceLyrics("", "");
        String mid = best.optString("songmid", "");
        if (mid.isEmpty()) return new SourceLyrics("", "");
        Uri lyricUrl = Uri.parse("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg").buildUpon()
                .appendQueryParameter("songmid", mid).appendQueryParameter("format", "json")
                .appendQueryParameter("nobase64", "1").build();
        JSONObject lyricRoot = getObject(lyricUrl.toString(), false, "https://y.qq.com/");
        return new SourceLyrics(lyricRoot == null ? "" : decodeEntities(lyricRoot.optString("lyric", "")),
                lyricRoot == null ? "" : decodeEntities(lyricRoot.optString("trans", "")));
    }

    private static JSONObject chooseSong(JSONArray songs, String wantedTitle, String wantedArtist, long durationMs, boolean netease) {
        if (songs == null) return null;
        JSONObject best = null;
        long bestScore = Long.MAX_VALUE;
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
            if (score < bestScore) { best = item; bestScore = score; }
        }
        return best;
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
