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
 * List fetching stays so Couchmoney-style slugs still work if OAuth is not set up. The tokens come built into the app
 * (tools/trakt_login.py) or from signing in on the TV (Home Settings → Trakt), which wins until a build brings others.
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
    private static String user = "";        // the signed-in person's name on Trakt, for the settings ("" = not known)
    private static String session = "";     // one sign-in: a new one fetches the picks again (Recs)
    private static boolean signedOut;       // signed out on the TV: the tokens built into the app stay unused

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

    /** Who the picks come from: "" when nobody is signed in; changes with each sign-in (Recs fetches again). */
    static String account() {
        synchronized (LOCK) {
            loadTokens();
            return hasKey() && (!accessToken.isEmpty() || !refreshToken.isEmpty()) ? session : "";
        }
    }

    /** The signed-in person's name on Trakt, "" if not known yet (see {@link #refreshUser}). */
    static String user() {
        synchronized (LOCK) {
            loadTokens();
            return user;
        }
    }

    /** Asks Trakt who is signed in (network: never on the main thread). */
    static void refreshUser() {
        if (!hasOAuth()) return;
        try {
            String name = fetchUser();
            synchronized (LOCK) {
                user = name;
                saveTokens();
            }
        } catch (Exception e) {
            AppLog.w("Trakt user: " + e.getMessage());
        }
    }

    private static String fetchUser() throws IOException, org.json.JSONException {
        JSONObject u = new JSONObject(get("/users/settings", true)).optJSONObject("user");
        if (u == null) return "";
        String name = u.isNull("name") ? "" : u.optString("name").trim();
        return name.isEmpty() ? u.optString("username") : name;
    }

    /** Trakt's sign-in for TVs: a short code that the person enters at Trakt's address, on a phone or a computer. */
    static final class Device {
        final String code, userCode, url;
        final long intervalMs, expiresAt;   // expiresAt: SystemClock.elapsedRealtime()

        Device(String code, String userCode, String url, long intervalMs, long expiresAt) {
            this.code = code;
            this.userCode = userCode;
            this.url = url;
            this.intervalMs = intervalMs;
            this.expiresAt = expiresAt;
        }
    }

    static final int WAITING = 0, SIGNED_IN = 1, EXPIRED = 2, SLOW_DOWN = 3;

    /** Asks Trakt for a code to show (network: never on the main thread). */
    static Device startSignIn() throws IOException {
        String clientId = Config.TRAKT_CLIENT_ID.trim();
        if (clientId.isEmpty()) throw new IOException("no Trakt client ID");
        int[] status = new int[1];
        JSONObject o = post(AUTH + "/oauth/device/code", body("client_id", clientId), clientId, status);
        if (status[0] != 200 || o.optString("device_code").isEmpty()) throw new IOException("HTTP " + status[0]);
        String url = o.optString("verification_url");
        return new Device(o.optString("device_code"), o.optString("user_code"),
                url.isEmpty() ? "https://trakt.tv/activate" : url, Math.max(1, o.optInt("interval", 5)) * 1000L,
                android.os.SystemClock.elapsedRealtime() + Math.max(60, o.optInt("expires_in", 600)) * 1000L);
    }

    /**
     * One check, every `intervalMs`: has the person entered the code? Signed in, the tokens are saved here (over the
     * ones built into the app) and the picks come from this account. Network: never on the main thread.
     */
    static int checkSignIn(Device d) throws IOException {
        if (android.os.SystemClock.elapsedRealtime() > d.expiresAt) return EXPIRED;
        String clientId = Config.TRAKT_CLIENT_ID.trim();
        int[] status = new int[1];
        JSONObject o = post(AUTH + "/oauth/device/token", body("code", d.code, "client_id", clientId), clientId, status);
        switch (status[0]) {
            case 200:
                synchronized (LOCK) {
                    loadTokens();
                    applyToken(o);
                    signedOut = false;
                    user = "";
                    session = "device-" + o.optLong("created_at", System.currentTimeMillis() / 1000L);
                    saveTokens();
                }
                refreshUser();
                return SIGNED_IN;
            case 400: return WAITING;       // not entered yet
            case 429: return SLOW_DOWN;     // asked too often
            case 404: case 409: case 410: case 418: return EXPIRED;   // unknown, already used, expired or refused
            default: throw new IOException("HTTP " + status[0]);
        }
    }

    /** Signs out on the TV (and tells Trakt, if it answers): the picks no longer come from the account. */
    static void signOut() {
        String token;
        synchronized (LOCK) {
            loadTokens();
            token = accessToken;
            accessToken = refreshToken = user = session = "";
            expiresAt = 0;
            signedOut = true;
            try {
                saveTokens();
            } catch (IOException e) {
                AppLog.w("Trakt sign-out: " + e.getMessage());
            }
        }
        String clientId = Config.TRAKT_CLIENT_ID.trim();
        if (token.isEmpty() || clientId.isEmpty()) return;
        try {
            post(AUTH + "/oauth/revoke", body("token", token, "client_id", clientId), clientId, new int[1]);
        } catch (IOException e) {
            AppLog.w("Trakt revoke: " + e.getMessage());
        }
    }

    /**
     * Marks a title (`kind` "movie" or "tv", by its TMDB id) watched now in the signed-in person's history; a show, every
     * episode aired so far. Network: never on the main thread.
     */
    static void markWatched(String kind, int tmdbId) throws IOException {
        sync("/sync/history", kind, tmdbId, 0);
    }

    /** Rates a title on Trakt's 1 to 10 scale (a star is 2). Network: never on the main thread. */
    static void rate(String kind, int tmdbId, int rating) throws IOException {
        sync("/sync/ratings", kind, tmdbId, Math.max(1, Math.min(10, rating)));
    }

    private static void sync(String path, String kind, int tmdbId, int rating) throws IOException {
        String clientId = Config.TRAKT_CLIENT_ID.trim();
        if (clientId.isEmpty() || !hasOAuth()) throw new IOException("not signed in to Trakt");
        String list = kind.equals("movie") ? "movies" : "shows";
        JSONObject body;
        try {
            JSONObject item = new JSONObject().put("ids", new JSONObject().put("tmdb", tmdbId));
            if (rating > 0) item.put("rating", rating);
            body = new JSONObject().put(list, new JSONArray().put(item));
        } catch (org.json.JSONException e) {
            throw new IOException("Trakt request body");
        }
        int[] status = new int[1];
        JSONObject answer = post(API + path, body, clientId, access(false), status);
        if (status[0] == 401) answer = post(API + path, body, clientId, access(true), status);
        if (status[0] != 200 && status[0] != 201) throw new IOException("Trakt returned HTTP " + status[0]);
        JSONObject missing = answer.optJSONObject("not_found");
        JSONArray lost = missing == null ? null : missing.optJSONArray(list);
        if (lost != null && lost.length() > 0) throw new IOException("Trakt doesn't know " + kind + " " + tmdbId);
    }

    private static JSONObject body(String... pairs) throws IOException {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2) o.put(pairs[i], pairs[i + 1]);
        } catch (org.json.JSONException e) {
            throw new IOException("Trakt request body");
        }
        return o;
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
        for (int i = 0; i < array.length(); i++) {
            Item item = parseItem(array.optJSONObject(i), type);   // bare movie/show objects, no wrapper
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
        return new JSONArray(get(path, oauth));
    }

    /** The answer's text; with `oauth`, a refused token is refreshed once and the request made again. */
    private static String get(String path, boolean oauth) throws IOException {
        String clientId = Config.TRAKT_CLIENT_ID.trim();
        if (clientId.isEmpty()) throw new IOException("no Trakt client ID");
        String token = oauth ? access(false) : "";
        try {
            return getOnce(path, clientId, token);
        } catch (IOException e) {
            if (!oauth || e.getMessage() == null || !e.getMessage().contains("HTTP 401")) throw e;
            return getOnce(path, clientId, access(true));
        }
    }

    private static String getOnce(String path, String clientId, String token) throws IOException {
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
            return read(c.getInputStream());
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
        int[] status = new int[1];
        JSONObject o = post(url, body, clientId, status);
        if (status[0] != 200) throw new IOException("Trakt auth HTTP " + status[0]);
        return o;
    }

    private static JSONObject post(String url, JSONObject body, String clientId, int[] status) throws IOException {
        return post(url, body, clientId, null, status);
    }

    /**
     * POSTs JSON (as the signed-in person with a `token`); `status` gets the HTTP code, and the answer is its JSON (empty
     * when it has none).
     */
    private static JSONObject post(String url, JSONObject body, String clientId, String token, int[] status) throws IOException {
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
        if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        try (OutputStream out = c.getOutputStream()) {
            out.write(payload);
        }
        try {
            int code = c.getResponseCode();
            status[0] = code;
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : read(in);
            try {
                return text.trim().startsWith("{") ? new JSONObject(text) : new JSONObject();
            } catch (org.json.JSONException e) {
                throw new IOException("Trakt returned invalid JSON");
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
                    user = o.optString("user");
                    session = o.optString("session", nowBaked);
                    signedOut = o.optBoolean("signed_out");
                    baked = nowBaked;
                    if (signedOut || !accessToken.isEmpty() || !refreshToken.isEmpty()) return;
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
        user = "";
        session = baked;
        signedOut = false;
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
            o.put("user", user);
            o.put("session", session);
            o.put("signed_out", signedOut);
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
