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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Trakt API client: personal recommendations and the person's own lists (OAuth), public user lists and search (client
 * ID only).
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
        final float rating;     // Trakt's average (0 to 10)
        final int votes;

        Item(String type, String title, int year, int tmdbId, int traktId, float rating, int votes) {
            this.type = type;
            this.title = title;
            this.year = year;
            this.tmdbId = tmdbId;
            this.traktId = traktId;
            this.rating = rating;
            this.votes = votes;
        }
    }

    /** One of the signed-in person's own lists: a smart one (Trakt fills it from filters) or a personal one. */
    static final class UserList {
        final String ref;       // a personal list's Trakt id, or "s" and a smart list's
        final String name;
        final String media;     // a smart list's "movies" or "shows"; "" for a personal list (it can hold both)

        UserList(String ref, String name, String media) {
            this.ref = ref;
            this.name = name;
            this.media = media;
        }
    }

    private static final String API = "https://api.trakt.tv";
    private static final String AUTH = "https://auth.trakt.tv";
    private static final int PAGE = 250;    // items a page of a paginated list: the most Trakt gives
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

    /**
     * Keeps a title out of the signed-in person's recommendations from now on (their hidden list, which trakt.tv can
     * undo). By TMDB id, which /recommendations/{id} doesn't take. Network: never on the main thread.
     */
    static void hideRecommendation(String kind, int tmdbId) throws IOException {
        sync("/users/hidden/recommendations", kind, tmdbId, 0);
    }

    /**
     * Puts the title on the signed-in person's watchlist, or takes it off. A full one (Trakt's limit for free accounts)
     * answers HTTP 420, which the exception's message says. Network: never on the main thread.
     */
    static void watchlist(String kind, int tmdbId, boolean add) throws IOException {
        sync(add ? "/sync/watchlist" : "/sync/watchlist/remove", kind, tmdbId, 0);
    }

    /** The signed-in person's watchlist (movies and shows), the latest added first. Throws when Trakt can't be asked. */
    static List<Item> watchlist() throws IOException {
        JSONArray array;
        try {
            array = getArray("/sync/watchlist?extended=full", true);
        } catch (org.json.JSONException e) {
            throw new IOException("Trakt watchlist: " + e.getMessage());
        }
        List<JSONObject> entries = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) if (array.optJSONObject(i) != null) entries.add(array.optJSONObject(i));
        java.util.Collections.sort(entries, (a, b) -> b.optString("listed_at").compareTo(a.optString("listed_at")));
        List<Item> items = new ArrayList<>();
        for (JSONObject o : entries) {
            Item item = parseItem(o, null);
            if (item != null && (item.type.equals("movie") || item.type.equals("show"))) items.add(item);   // not seasons, episodes
        }
        return items;
    }

    /** A show the person watches, with `episodes` aired that they haven't seen, the first of them out on `since`. */
    static final class Unwatched {
        final Item show;
        final int episodes;
        final long since;

        Unwatched(Item show, int episodes, long since) {
            this.show = show;
            this.episodes = episodes;
            this.since = since;
        }
    }

    /**
     * What the signed-in person has watched, as Trakt has it: the movies and the shows they saw every aired episode of
     * (TMDB ids; a new episode out, the show is no longer among them), and the shows with new episodes for them.
     */
    static final class Watched {
        final Set<Integer> movies = new HashSet<>(), shows = new HashSet<>();
        final List<Unwatched> fresh = new ArrayList<>();
    }

    /**
     * What the person has watched: their movies, and their shows, one seen through when its episodes' plays reach the
     * ones aired and it was never started over (Trakt's answer no longer lists the episodes; one watched twice counts
     * twice). Then the progress of the 20 shows watched last (one request each), which says exactly whether those are
     * seen through, and which have new episodes: the ones (within 3 years) whose next unwatched episode came out after
     * they last watched, within the past year (an older one is a show left behind, not news), the newest first. Throws
     * when Trakt can't be asked.
     */
    static Watched watched(long now) throws IOException {
        Watched w = new Watched();
        List<JSONObject> shows;
        try {
            for (JSONObject o : pages("/sync/watched/movies")) {
                Item item = parseItem(o, "movie");
                if (item != null) w.movies.add(item.tmdbId);
            }
            shows = pages("/sync/watched/shows?extended=noseasons");
        } catch (org.json.JSONException e) {
            throw new IOException("Trakt watched: " + e.getMessage());
        }
        for (JSONObject o : shows) {
            Item item = parseItem(o, "show");
            JSONObject show = o.optJSONObject("show");
            int aired = show == null ? 0 : show.optInt("aired_episodes");
            if (item != null && aired > 0 && o.optInt("plays") >= aired && instant(o.optString("reset_at")) == 0) {
                w.shows.add(item.tmdbId);
            }
        }
        java.util.Collections.sort(shows, (a, b) -> b.optString("last_watched_at").compareTo(a.optString("last_watched_at")));
        long year = 365L * 24 * 3600_000L;
        for (int i = 0; i < Math.min(20, shows.size()); i++) {
            JSONObject o = shows.get(i);
            if (now - instant(o.optString("last_watched_at")) > 3 * year) break;
            Item item = parseItem(o, "show");
            if (item == null || item.traktId <= 0) continue;
            try {
                JSONObject p = new JSONObject(get("/shows/" + item.traktId + "/progress/watched", true));
                int unseen = p.optInt("aired") - p.optInt("completed");
                if (p.optInt("aired") > 0 && unseen > 0) w.shows.remove(item.tmdbId);
                else if (p.optInt("aired") > 0) w.shows.add(item.tmdbId);
                JSONObject next = p.optJSONObject("next_episode");
                long aired = next == null ? 0 : instant(next.optString("first_aired")), seen = instant(p.optString("last_watched_at"));
                if (p.optInt("completed") > 0 && unseen > 0 && aired > seen && aired <= now && now - aired < year) {
                    w.fresh.add(new Unwatched(item, unseen, aired));
                }
            } catch (IOException | org.json.JSONException e) {
                AppLog.w("Trakt progress " + item.traktId + ": " + e.getMessage());
            }
        }
        java.util.Collections.sort(w.fresh, (a, b) -> Long.compare(b.since, a.since));
        return w;
    }

    /** Every item of a list Trakt answers in pages (its sync lists too: 100 at a time unless asked for more); 50 at most. */
    private static List<JSONObject> pages(String path) throws IOException, org.json.JSONException {
        List<JSONObject> out = new ArrayList<>();
        int[] count = {1};
        for (int page = 1; page <= count[0] && page <= 50; page++) {
            JSONArray a = new JSONArray(get(path + (path.contains("?") ? "&" : "?") + "page=" + page + "&limit=" + PAGE, true, count));
            for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) out.add(a.optJSONObject(i));
        }
        return out;
    }

    /** Trakt's time ("2026-09-04T01:00:00.000Z") in ms; 0 if there is none. */
    private static long instant(String at) {
        try {
            return at == null || at.isEmpty() ? 0 : java.time.Instant.parse(at).toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
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
        // What Trakt did (added, existing, not found: counts and ids, no token), as a write's only trace
        AppLog.w("Trakt " + path + " " + kind + " " + tmdbId + ": HTTP " + status[0] + " " + answer);
        if (status[0] != 200 && status[0] != 201) throw new IOException("Trakt returned HTTP " + status[0]);   // 420: a limit
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
        if (!hasOAuth()) return new ArrayList<>();
        try {
            return recommendations(kind, "", 50);
        } catch (Exception e) {
            AppLog.w("Trakt fetchRecommendations failed for " + kind + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Up to `limit` recommendations, with Trakt's filters in `query` ("&genres=horror&years=2010-2019": Trakt applies them
     * before the limit, so a filter reaches past the unfiltered list's end); each with its rating (extended=full).
     * Throws when Trakt can't be asked.
     */
    static List<Item> recommendations(String kind, String query, int limit) throws IOException {
        List<Item> items = new ArrayList<>();
        String type = "shows".equals(kind) ? "show" : "movie";
        String path = "/recommendations/" + kind + "?limit=" + limit + "&ignore_collected=true&ignore_watchlisted=true"
                + "&extended=full" + query;
        JSONArray array;
        try {
            array = getArray(path, true);
        } catch (org.json.JSONException e) {
            throw new IOException("Trakt recommendations: " + e.getMessage());
        }
        for (int i = 0; i < array.length(); i++) {
            Item item = parseItem(array.optJSONObject(i), type);   // bare movie/show objects, no wrapper
            if (item != null) items.add(item);
        }
        return items;
    }

    /**
     * Up to `limit` movies or shows (`type`: "movies" or "shows") from `source` (the person's "recommendations", or
     * Trakt's "trending", "popular" or "anticipated"), with Trakt's filters in `query` ("&genres=comedy,-horror&…") and
     * the ones the person watched left out: as a smart list of theirs would have them, through the preview the Trakt
     * app shows while one is being made (newer than the API's documentation: it applies every filter, the parental
     * guide's and genres that must all match too). If that's gone (HTTP 400, 404 or 405), the documented lists, which
     * take most of them. Ranked by Trakt; each with its rating (extended=full). Throws when Trakt can't be asked.
     */
    static List<Item> discover(String source, String type, String query, int limit) throws IOException {
        String common = "extended=full&ignore_watched=true&ignore_watchlisted=true" + query;
        JSONArray array;
        try {
            try {
                array = getArray("/smart-lists/preview/items?source=" + source + "&media_type=" + type + "&limit=" + limit
                        + "&" + common, true);
            } catch (IOException e) {
                String m = String.valueOf(e.getMessage());
                if (!m.contains("HTTP 400") && !m.contains("HTTP 404") && !m.contains("HTTP 405")) throw e;
                AppLog.w("Trakt preview: " + m);
                String path = source.equals("recommendations") ? "/recommendations/" + type + "?ignore_collected=true&"
                        : "/" + type + "/" + source + "?";
                array = getArray(path + "limit=" + limit + "&" + common, true);
            }
        } catch (org.json.JSONException e) {
            throw new IOException("Trakt discover: " + e.getMessage());
        }
        String one = "shows".equals(type) ? "show" : "movie";
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Item item = parseItem(array.optJSONObject(i), one);   // {type, movie|show, rank}, {watchers, movie} or bare
            if (item != null && item.type.equals(one)) items.add(item);
        }
        return items;
    }

    /**
     * Trakt's search for movies and shows (the client ID is enough), in any language Trakt has their titles in. Its
     * matching minds the case of Cyrillic letters ("дюна" finds nothing, "Дюна" finds Dune), so the query starts with a
     * capital, as titles do. Trakt scores many answers alike: the most watched go first (by votes), its own order
     * breaking near ties ("Друзья": Friends, 5th in Trakt's order, comes first). Throws when Trakt can't be asked.
     */
    static List<Item> search(String query, int limit) throws IOException {
        List<Item> items = new ArrayList<>();
        String q = query.trim();
        if (q.isEmpty()) return items;
        int first = q.offsetByCodePoints(0, 1);
        q = new String(Character.toChars(Character.toTitleCase(q.codePointAt(0)))) + q.substring(first);
        String path = "/search/movie,show?query=" + URLEncoder.encode(q, "UTF-8").replace("+", "%20")
                + "&extended=full&limit=" + limit;
        JSONArray array;
        try {
            array = getArray(path, false);
        } catch (org.json.JSONException e) {
            throw new IOException("Trakt search: " + e.getMessage());
        }
        List<float[]> ranked = new ArrayList<>();   // {score, index}
        for (int i = 0; i < array.length(); i++) {
            Item item = parseItem(array.optJSONObject(i), null);
            if (item == null) continue;
            ranked.add(new float[] {(float) Math.log10(item.votes + 1) - 0.15f * items.size(), items.size()});
            items.add(item);
        }
        java.util.Collections.sort(ranked, (a, b) -> Float.compare(b[0], a[0]));
        List<Item> out = new ArrayList<>();
        for (float[] r : ranked) out.add(items.get((int) r[1]));
        return out;
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
                + "/lists/" + URLEncoder.encode(listSlug.trim(), "UTF-8") + "/items?extended=full";
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

    /**
     * The signed-in person's own lists, as the Trakt app has them: their smart lists first, then their personal ones
     * (not the watchlist, nor the lists they like). Throws when Trakt can't be asked.
     */
    static List<UserList> lists() throws IOException {
        List<UserList> out = new ArrayList<>();
        try {   // newer than the API's documentation: without them, the personal lists still come
            JSONArray smart = getArray("/users/me/smart-lists", true);
            for (int i = 0; i < smart.length(); i++) {
                JSONObject o = smart.optJSONObject(i);
                JSONObject ids = o == null ? null : o.optJSONObject("ids");
                int id = ids == null ? 0 : ids.optInt("trakt");
                if (id > 0) out.add(new UserList("s" + id, o.optString("name"), o.optString("media_type")));
            }
        } catch (IOException | org.json.JSONException e) {
            AppLog.w("Trakt smart lists: " + e.getMessage());
        }
        JSONArray array;
        try {
            array = getArray("/users/me/lists", true);
        } catch (org.json.JSONException e) {
            throw new IOException("Trakt lists: " + e.getMessage());
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            JSONObject ids = o == null ? null : o.optJSONObject("ids");
            int id = ids == null ? 0 : ids.optInt("trakt");
            if (id > 0) out.add(new UserList(String.valueOf(id), o.optString("name"), ""));
        }
        return out;
    }

    /**
     * The movies ("movie") or shows ("show") on one of the person's lists (`ref`, as UserList has it), in its order on
     * Trakt. A smart list's come ranked by Trakt (its first 250); a personal list's come by rank, and the list says how
     * it's sorted (by date added, title, rating…), which is done here. Throws when Trakt can't be asked, or the list is
     * gone (HTTP 404).
     */
    static List<Item> listItems(String ref, String type) throws IOException {
        boolean smart = ref.startsWith("s");
        int listId;
        try {
            listId = Integer.parseInt(smart ? ref.substring(1) : ref);
        } catch (NumberFormatException e) {
            throw new IOException("Trakt list " + ref + "?");
        }
        if (smart) {
            List<Item> items = new ArrayList<>();
            try {
                JSONArray array = getArray("/smart-lists/" + listId + "/items?extended=full&limit=250", true);
                for (int i = 0; i < array.length(); i++) {
                    Item item = parseItem(array.optJSONObject(i), null);
                    if (item != null && item.type.equals(type)) items.add(item);
                }
            } catch (org.json.JSONException e) {
                throw new IOException("Trakt smart list: " + e.getMessage());
            }
            return items;
        }
        JSONObject list;
        JSONArray array;
        try {
            list = new JSONObject(get("/users/me/lists/" + listId, true));
            array = getArray("/users/me/lists/" + listId + "/items/" + type + "?extended=full", true);
        } catch (org.json.JSONException e) {
            throw new IOException("Trakt list: " + e.getMessage());
        }
        List<JSONObject> entries = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) if (array.optJSONObject(i) != null) entries.add(array.optJSONObject(i));
        String by = list.optString("sort_by", "rank");
        if (by.equals("random")) {
            java.util.Collections.shuffle(entries);
        } else {
            java.util.Collections.sort(entries, (a, b) -> compareListed(a, b, by));
            if (list.optString("sort_how").equals("desc")) java.util.Collections.reverse(entries);
        }
        List<Item> items = new ArrayList<>();
        for (JSONObject entry : entries) {
            Item item = parseItem(entry, null);
            if (item != null) items.add(item);
        }
        return items;
    }

    /** Two list items in the list's sort, ascending; what an item doesn't carry (popularity, my rating…) goes by rank. */
    private static int compareListed(JSONObject a, JSONObject b, String by) {
        JSONObject ma = media(a), mb = media(b);
        switch (by) {
            case "added": return a.optString("listed_at").compareTo(b.optString("listed_at"));
            case "title": return ma.optString("title").compareToIgnoreCase(mb.optString("title"));
            case "released": return released(ma).compareTo(released(mb));
            case "runtime": return Integer.compare(ma.optInt("runtime"), mb.optInt("runtime"));
            case "percentage": return Double.compare(ma.optDouble("rating", 0), mb.optDouble("rating", 0));
            case "votes": return Integer.compare(ma.optInt("votes"), mb.optInt("votes"));
            default: return Integer.compare(a.optInt("rank"), b.optInt("rank"));
        }
    }

    private static JSONObject media(JSONObject entry) {
        JSONObject m = entry.optJSONObject(entry.optString("type"));
        return m != null ? m : new JSONObject();
    }

    /** A movie's release date or a show's first air date, as text that sorts by date. */
    private static String released(JSONObject media) {
        String at = media.optString("released");
        return at.isEmpty() ? media.optString("first_aired") : at;
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
        return new Item(type, title, year, tmdbId, traktId, (float) media.optDouble("rating", 0), media.optInt("votes"));
    }

    private static JSONArray getArray(String path, boolean oauth) throws IOException, org.json.JSONException {
        return new JSONArray(get(path, oauth));
    }

    /** The answer's text; with `oauth`, a refused token is refreshed once and the request made again. */
    private static String get(String path, boolean oauth) throws IOException {
        return get(path, oauth, null);
    }

    /** The same, with how many pages the list has (Trakt's X-Pagination-Page-Count; 1 if it doesn't say) into `pages`. */
    private static String get(String path, boolean oauth, int[] pages) throws IOException {
        String clientId = Config.TRAKT_CLIENT_ID.trim();
        if (clientId.isEmpty()) throw new IOException("no Trakt client ID");
        String token = oauth ? access(false) : "";
        try {
            return getOnce(path, clientId, token, pages);
        } catch (IOException e) {
            if (!oauth || e.getMessage() == null || !e.getMessage().contains("HTTP 401")) throw e;
            return getOnce(path, clientId, access(true), pages);
        }
    }

    private static String getOnce(String path, String clientId, String token, int[] pages) throws IOException {
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
            if (pages != null) pages[0] = c.getHeaderFieldInt("X-Pagination-Page-Count", 1);
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
