package io.freemytv;

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Uses Filmix's exported Android TV search provider, with the same intent as a search suggestion. */
final class Filmix {
    static final String PKG = "net.filmix.filmix";
    static final String UNAVAILABLE = "Filmix search is unavailable; open Filmix once and return to the home screen";
    private static final Uri SEARCH = Uri.parse("content://net.filmix.providers.SuggestionsProvider/search_suggest_query?limit=30");
    private static final Uri RECORDS = Uri.parse("content://net.filmix.providers.SuggestionsProvider/records");
    // A provider call can outlive its timeout. No queue means blocked calls cannot accumulate.
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, "filmix-search");
                thread.setDaemon(true);
                return thread;
            });

    private Filmix() {}

    static String find(Context context, String title, String original, int year, boolean series) throws IOException {
        Set<String> queries = new LinkedHashSet<>();
        if (title != null && !title.trim().isEmpty()) queries.add(title.trim());
        if (original != null && !original.trim().isEmpty()
                && !FilmixMatch.normalize(original).equals(FilmixMatch.normalize(title))) queries.add(original.trim());
        for (String query : queries) {
            String match = search(context, query, title, original, year, series);
            if (match != null) return match;
        }
        return null;
    }

    private static String search(Context context, String query, String title, String original,
                                 int year, boolean series) throws IOException {
        Future<String> future;
        try {
            future = IO.submit(() -> query(context, query, title, original, year, series));
        } catch (RuntimeException e) {
            throw new IOException(UNAVAILABLE);
        }
        try {
            return future.get(8, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new IOException(UNAVAILABLE);
        } catch (ExecutionException | TimeoutException e) {
            future.cancel(true);
            // Provider exceptions can contain its account token and URL. Never propagate their messages.
            Throwable cause = e.getCause() == null ? e : e.getCause();
            AppLog.w("Filmix search failed: " + cause.getClass().getSimpleName());
            throw new IOException(UNAVAILABLE);
        }
    }

    private static String query(Context context, String query, String title, String original,
                                int year, boolean series) throws Exception {
        try (Cursor cursor = context.getContentResolver().query(SEARCH, null, " ?", new String[] {query}, null)) {
            if (cursor == null) throw new IOException(UNAVAILABLE);
            int caption = cursor.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_TEXT_1);
            int production = cursor.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_PRODUCTION_YEAR);
            int extra = cursor.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA);
            String match = null;
            long matchId = 0;
            for (int count = 0; count < 30 && cursor.moveToNext(); count++) {
                try {
                    JSONObject payload = new JSONObject(cursor.getString(extra));
                    if (!(payload.opt("hasSeries") instanceof Boolean)) continue;
                    int offeredYear = Integer.parseInt(cursor.getString(production));
                    if (!FilmixMatch.matches(title, original, year, series, cursor.getString(caption),
                            offeredYear, payload.getBoolean("hasSeries"))) continue;
                    String safe = payload(payload);
                    if (safe == null) continue;
                    long id = payload.getLong("id");
                    if (match != null && matchId != id) return null;   // ambiguous remakes: omit rather than guess
                    match = safe;
                    matchId = id;
                } catch (org.json.JSONException | NumberFormatException | NullPointerException ignored) {
                    // A malformed suggestion does not invalidate the other search results.
                }
            }
            return match;
        }
    }

    private static String payload(JSONObject source) throws org.json.JSONException {
        long id = source.optLong("id");
        String slug = source.optString("alt_name");
        if (id <= 0 || id > Integer.MAX_VALUE || slug.isEmpty() || slug.length() > 512
                || !slug.matches("[A-Za-z0-9_-]+") || !(source.opt("hasSeries") instanceof Boolean)) return null;
        return new JSONObject().put("id", id).put("alt_name", slug).put("hasSeries", source.getBoolean("hasSeries")).toString();
    }

    static Intent intent(String data) {
        try {
            String safe = payload(new JSONObject(data));
            if (safe == null) return null;
            return new Intent(Intent.ACTION_VIEW, RECORDS)
                    .setComponent(new ComponentName(PKG, PKG + ".SearchActivity"))
                    .putExtra(SearchManager.EXTRA_DATA_KEY, safe);
        } catch (org.json.JSONException | NullPointerException e) {
            return null;
        }
    }
}
