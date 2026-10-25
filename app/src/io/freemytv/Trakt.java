package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Trakt API client for fetching personal lists and Couchmoney recommendations.
 * Uses public API key (client ID) and HTTP version 2 headers.
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

    private Trakt() {}

    static boolean hasKey() {
        return !Config.TRAKT_CLIENT_ID.trim().isEmpty();
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
            array = getArray(path);
        } catch (Exception e) {
            AppLog.w("Trakt fetchListItems failed for " + listSlug + ": " + e.getMessage());
            return items;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            String type = obj.optString("type");
            JSONObject media = obj.optJSONObject(type);
            if (media == null) {
                if (obj.has("movie")) {
                    type = "movie";
                    media = obj.optJSONObject("movie");
                } else if (obj.has("show")) {
                    type = "show";
                    media = obj.optJSONObject("show");
                }
            }
            if (media == null) continue;
            String title = media.optString("title");
            int year = media.optInt("year");
            JSONObject ids = media.optJSONObject("ids");
            int tmdbId = ids != null ? ids.optInt("tmdb") : 0;
            int traktId = ids != null ? ids.optInt("trakt") : 0;
            if (tmdbId > 0 && !title.isEmpty()) {
                items.add(new Item(type, title, year, tmdbId, traktId));
            }
        }
        return items;
    }

    private static JSONArray getArray(String path) throws IOException, org.json.JSONException {
        String clientId = Config.TRAKT_CLIENT_ID.trim();
        if (clientId.isEmpty()) throw new IOException("no Trakt client ID");
        HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("trakt-api-version", "2");
        c.setRequestProperty("trakt-api-key", clientId);
        c.setRequestProperty("User-Agent", "FreeMyTV/1.0");
        try {
            int code = c.getResponseCode();
            if (code == 401 || code == 403) throw new IOException("Trakt rejected client ID (HTTP " + code + ")");
            if (code == 404) throw new IOException("Trakt list not found (HTTP 404): " + path);
            if (code != 200) throw new IOException("Trakt returned HTTP " + code);
            return new JSONArray(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
