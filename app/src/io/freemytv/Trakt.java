package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Trakt API client: personal recommendations (OAuth) and public user lists (client ID only).
 * List fetching stays so Couchmoney-style slugs still work if OAuth is not set up.
 */
final class Trakt {
    static final class Item {
        final String type; // "movie" or "show"
        final String title;
        final int year;
        final int tmdbId;
        final int traktId;

        Item(String type, String title, int year, int tmdbId, int traktId) {
            this.type = type;
            this.title = title;
            this.year = year;
            this.tmdbId = tmdbId;
            this.traktId = traktId;
        }
    }

    private static final String API = "https://api.trakt.tv";
    private static final String AUTH = "https://auth.trakt.tv";
    private static final Object LOCK = new Object();

    private static Context app;
    private static String accessToken = "";
    private static String refreshToken = "";
    private static long expiresAt;
    private static String baked = "";

    private Trakt() {}

    static void init(Context c) {
        app = c.getApplicationContext();
        synchronized (LOCK) {
            loadTokens();
        }
    }

    static boolean hasKey() {
        return !Config.TRAKT_CLIENT_ID.trim().isEmpty();
    }

    /** True when a user access token is available (baked in or saved after a refresh). */
    static boolean hasOAuth() {
        synchronized (LOCK) {
            loadTokens();
            return hasKey() && (!accessToken.isEmpty() || !refreshToken.isEmpty());
        }
    }

    /**
     * Personalized recommendations for the signed-in user.
     * {@code kind} is {@code "movies"} or {@code "shows"}.
     */
    static List<Item> fetchRecommendations(String kind) throws IOException {
        List<Item> items = new ArrayList<>();
        if (!hasOAuth()) return items;
        String type = "shows".equals(kind) ? "show" : "movie";
        String path = "/recommendations/" + kind + "?limit=50&ignore_collected=true&ignore_watchlisted=true";
        JSONArray array;
        try {
            array = getArray(path, true);
        } catch (Exception e) {
            AppLog.w("Trakt fetchRecommendations failed for " + kind + ": " + e.getMessage());
            return items;
        }
        String expected = type;
        for (int i = 0; i < array.length(); i++) {
            Item item = parseItem(array.optJSONObject(i), expected);
            if (item != null) items.add(item);
        }
        return items;
    }

    /**
     * Fetches items from a public user list (e.g. Couchmoney recommendations).
     * Returns an empty list on HTTP errors (e.g. 404 if list not yet synced).
     */
    static List<Item> fetchListItems(String user, String listSlug) throws IOException {
        List<Item> items = new ArrayList<>();
        if (!hasKey() || user == null || user.trim().isEmpty() || listSlug == null || listSlug.trim().isEmpty()) {
            return items;
        }
        String path = "/users/" + URLEncoder.encode(user.trim(), "UTF-8")
                + "/lists/" + URLEncoder.encode(listSlug.trim(), "UTF-8") + "/items";
        JSONArray array;
        try {
            array = getArray(path, false);
        } catch (Exception e) {
            AppLog.w("Trakt fetchListItems failed for " + listSlug + ": " + e.getMessage());
            return items;
        }
        for (int i = 0; i < array.length(); i++) {
            Item item = parseItem(array.optJSONObject(i), null);
            if (item != null) items.add(item);
        }
        return items;
    }

    private static Item parseItem(JSONObject obj, String defaultType) {
        if (obj == null) return null;
        String type = obj.optString("type");
        JSONObject media = type.isEmpty() ? null : obj.optJSONObject(type);
        if (media == null) {
            if (obj.has("movie")) {
                type = "movie";
                media = obj.optJSONObject("movie");
            } else if (obj.has("show")) {
                type = "show";
                media = obj.optJSONObject("show");
            } else if (obj.has("ids")) {
                type = defaultType != null ? defaultType : "";
                media = obj;
            }
        }
        if (media == null || type.isEmpty()) return null;
        String title = media.optString("title");
        int year = media.optInt("year");
        JSONObject ids = media.optJSONObject("ids");
        int tmdbId = ids != null ? ids.optInt("tmdb") : 0;
        int traktId = ids != null ? ids.optInt("trakt") : 0;
        if (tmdbId <= 0 || title.isEmpty()) return null;
        return new Item(type, title, year, tmdbId, traktId);
    }

    private static JSONArray getArray(String path, boolean oauth) throws IOException, org.json.JSONException {
        String clientId = Config.TRAKT_CLIENT_ID.trim();
        if (clientId.isEmpty()) throw new IOException("no Trakt client ID");
        String token = oauth ? access(false) : "";
        try {
            return getArrayOnce(path, clientId, token);
        } catch (IOException e) {
            if (!oauth || e.getMessage() == null || !e.getMessage().contains("HTTP 401")) throw e;
            return getArrayOnce(path, clientId, access(true));
        }
    }

    private static JSONArray getArrayOnce(String path, String clientId, String token)
            throws IOException, org.json.JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("trakt-api-version", "2");
        c.setRequestProperty("trakt-api-key", clientId);
        c.setRequestProperty("User-Agent", "FreeMyTV/1.0");
        if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        try {
            int code = c.getResponseCode();
            if (code == 401 || code == 403) throw new IOException("Trakt rejected credentials (HTTP " + code + ")");
            if (code == 404) throw new IOException("Trakt not found (HTTP 404): " + path);
            if (code != 200) throw new IOException("Trakt returned HTTP " + code);
            return new JSONArray(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private static String access(boolean forceRefresh) throws IOException {
        synchronized (LOCK) {
            loadTokens();
            long now = System.currentTimeMillis();
            if (!forceRefresh && !accessToken.isEmpty() && (expiresAt == 0 || now < expiresAt)) return accessToken;
            if (refreshToken.isEmpty()) {
                if (accessToken.isEmpty()) throw new IOException("no Trakt access token");
                return accessToken;
            }
            refresh();
            return accessToken;
        }
    }

    private static void refresh() throws IOException {
        String clientId = Config.TRAKT_CLIENT_ID.trim();
        String redirect = Config.TRAKT_REDIRECT_URI.trim();
        if (redirect.isEmpty()) redirect = "urn:ietf:wg:oauth:2.0:oob";
        JSONObject body = new JSONObject();
        try {
            body.put("refresh_token", refreshToken);
            body.put("client_id", clientId);
            body.put("grant_type", "refresh_token");
            body.put("redirect_uri", redirect);
        } catch (org.json.JSONException e) {
            throw new IOException("Trakt refresh body");
        }
        JSONObject token = postJson(AUTH + "/oauth/token", body, clientId);
        applyToken(token);
        saveTokens();
    }

    private static void applyToken(JSONObject token) throws IOException {
        String access = token.optString("access_token");
        String refresh = token.optString("refresh_token");
        if (access.isEmpty()) throw new IOException("Trakt refresh returned no access token");
        accessToken = access;
        if (!refresh.isEmpty()) refreshToken = refresh;
        long expiresIn = token.optLong("expires_in", 7 * 24 * 3600L);
        long createdAt = token.optLong("created_at", System.currentTimeMillis() / 1000L);
        expiresAt = Math.max(0, createdAt + expiresIn - 120) * 1000L;
    }

    private static JSONObject postJson(String url, JSONObject body, String clientId) throws IOException {
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("trakt-api-version", "2");
        c.setRequestProperty("trakt-api-key", clientId);
        c.setRequestProperty("User-Agent", "FreeMyTV/1.0");
        try (OutputStream out = c.getOutputStream()) {
            out.write(payload);
        }
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : read(in);
            if (code != 200) throw new IOException("Trakt auth HTTP " + code);
            try {
                return new JSONObject(text);
            } catch (org.json.JSONException e) {
                throw new IOException("Trakt auth returned invalid JSON");
            }
        } finally {
            c.disconnect();
        }
    }

    private static void loadTokens() {
        if (app == null) {
            takeBaked();
            return;
        }
        String nowBaked = bakedStamp();
        File file = tokenFile();
        if (file.isFile()) {
            try (FileInputStream in = new FileInputStream(file)) {
                JSONObject o = new JSONObject(read(in));
                if (nowBaked.equals(o.optString("baked"))) {
                    accessToken = o.optString("access_token");
                    refreshToken = o.optString("refresh_token");
                    expiresAt = o.optLong("expires_at");
                    baked = nowBaked;
                    if (!accessToken.isEmpty() || !refreshToken.isEmpty()) return;
                }
            } catch (Exception ignored) {
            }
        }
        takeBaked();
        baked = nowBaked;
        if (!accessToken.isEmpty() || !refreshToken.isEmpty()) {
            try {
                saveTokens();
            } catch (IOException ignored) {
            }
        }
    }

    private static void takeBaked() {
        accessToken = Config.TRAKT_ACCESS_TOKEN.trim();
        refreshToken = Config.TRAKT_REFRESH_TOKEN.trim();
        expiresAt = 0;
        baked = bakedStamp();
    }

    private static String bakedStamp() {
        return Integer.toHexString(Config.TRAKT_ACCESS_TOKEN.hashCode())
                + "-" + Integer.toHexString(Config.TRAKT_REFRESH_TOKEN.hashCode());
    }

    private static void saveTokens() throws IOException {
        if (app == null) return;
        JSONObject o = new JSONObject();
        try {
            o.put("access_token", accessToken);
            o.put("refresh_token", refreshToken);
            o.put("expires_at", expiresAt);
            o.put("baked", bakedStamp());
        } catch (org.json.JSONException e) {
            throw new IOException("Trakt token save");
        }
        File file = tokenFile();
        File tmp = new File(file.getParentFile(), file.getName() + "." + android.os.Process.myPid() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
        if (!tmp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Trakt token save");
        }
    }

    private static File tokenFile() {
        return new File(app.getFilesDir(), "trakt-oauth.json");
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
