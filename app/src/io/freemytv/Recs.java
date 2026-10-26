package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen picks: what's trending this week plus recent releases (or your Trakt recommendations, or one of your Trakt
 * lists for Movies and another for Shows, picked in Home Settings), only from the
 * streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Apple TV, Okko, Hulu, Paramount+, Peacock,
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). Each row (Explore,
 * Movies, Shows) shows four (the fourth's edge past the other three); the focus on the last card asks for three more
 * ({@link #more}). With Filmix, a title is looked up in Filmix only when its row gets to it: the rest wait in a queue.
 * Explore can be filtered as a Trakt smart list is (where from: recommendations, trending, popular or anticipated;
 * movies or shows, genres in and out, years, statuses, ratings, the parental guide): Trakt applies it all.
 * The search field's row shows Trakt's answers for what is typed, each looked up the same way ({@link #search}).
 * "Watch" opens the service's app.
 *
 * Each person uses their own free TMDB key (config.properties "tmdb_key", built into the app). It never goes to the log.
 */
final class Recs {
    static final class Pick {
        int id;
        String kind;            // "movie" or "tv"
        String title, backdrop, logo, meta = "", app, pkg;   // logo: TMDB's path of the title's logo (a PNG)
        boolean rec;            // one of the recommendations (Explore, Movies, Shows), not only a filter's or the watchlist's
        String link;            // Watchmode URL or Filmix search-suggestion payload; null opens the app's home
        String originalTitle;
        int year;
        float rating;           // Trakt's average for its picks, else TMDB's (0 to 10); 0 = too few votes to say
        boolean detailed;
        boolean searched;       // only a search's answer: its photo goes to a folder of its own, not the rows'
        boolean nowhere;        // none of the TV's apps has it (with Filmix: Filmix said so): only the watchlist shows it
        java.io.File photo;     // one of your own photos (Photos): no title, no caption
    }

    /**
     * Cards a row shows at first (three fill the screen, the fourth's edge shows past them), and how many more each step
     * toward its end asks for.
     */
    static final int FIRST = 4, PAGE = 3;

    interface Listener { void onRecs(); }

    /** The Watchlist row's kind (its titles are movies and shows). */
    static final String WATCHLIST = "watchlist";
    /** The search field's row (Trakt's answers: movies and shows). */
    static final String SEARCH = "search";
    /** Where a search is: nothing typed; asking Trakt; its answers coming in; all in; Trakt didn't answer. */
    static final int SEARCH_IDLE = 0, SEARCH_ASKING = 1, SEARCH_FILLING = 2, SEARCH_DONE = 3, SEARCH_FAILED = 4;

    /**
     * Explore's filters (hold OK on its tab), as Trakt's smart lists have them: where the titles come from (`source`,
     * SOURCES: the person's recommendations or one of Trakt's lists of the moment), the type, genres wanted and genres
     * left out, a year or a span of years, statuses, the least each rating source may give, and how much of each kind
     * of content IMDb's parental guide may report. Trakt applies them all (Trakt.discover). Saved by value, so the lists
     * can change. Mutable for the filters' panel, which edits a copy; the one a row has is never changed.
     */
    static final class Filter {
        /** Movies and shows, movies only ("movie"), or shows only ("tv"). */
        static final String[] TYPES = {null, "movie", "tv"};
        static final int MOVIES = 1, SHOWS = 2;
        /** Trakt's sources: the person's recommendations, then its trending, popular and anticipated titles. */
        static final String[] SOURCES = {"recommendations", "trending", "popular", "anticipated"};
        /** Trakt's genre slugs, its movies' and its shows' (not "none"): bits of `genres` and `without`. */
        static final String[] GENRES = {"action", "adventure", "animation", "anime", "biography", "children", "comedy",
            "crime", "documentary", "donghua", "drama", "family", "fantasy", "game-show", "history", "holiday",
            "home-and-garden", "horror", "mini-series", "music", "musical", "mystery", "news", "reality", "romance",
            "science-fiction", "short", "soap", "special-interest", "sporting-event", "superhero", "suspense", "talk-show",
            "thriller", "war", "western"};
        /** Trakt's statuses, a movie's and a show's (bits of `statuses`): MOVIE_STATUSES and SHOW_STATUSES say whose. */
        static final String[] STATUSES = {"released", "returning series", "continuing", "in production", "post production",
            "planned", "upcoming", "pilot", "rumored", "canceled", "ended"};
        static final int MOVIE_STATUSES = bits(0, 3, 4, 5, 8, 9), SHOW_STATUSES = bits(1, 2, 3, 5, 6, 7, 9, 10);
        /**
         * On the air: Trakt gives ongoing shows "returning series" and almost never "continuing" (none of its popular
         * shows, none of the person's recommendations), so the two always go together.
         */
        static final int ONGOING = bits(1, 2);
        /** The rating sources, by Trakt's parameter; the least each may give (RATING_MIN, in its own scale). */
        static final String[] RATINGS = {"ratings", "imdb_ratings", "tmdb_ratings", "rt_meters", "rt_user_meters",
            "metascores", "letterboxd_ratings"};
        static final float[][] RATING_MIN = {{50, 60, 70, 80, 90}, {5, 6, 7, 8, 9}, {5, 6, 7, 8, 9}, {50, 60, 70, 80, 90},
            {50, 60, 70, 80, 90}, {50, 60, 70, 80, 90}, {2.5f, 3, 3.5f, 4, 4.5f}};
        static final float[] RATING_TOP = {100, 10, 10, 100, 100, 100, 5};
        /** IMDb's parental guide through Trakt: its kinds of content. Each allows up to a level: 1 moderate, 2 mild, 3 none. */
        static final String[] PARENTAL = {"nudity", "violence", "profanity", "alcohol", "frightening"};

        int type, source;
        long genres, without;           // GENRES wanted (any of them, or all with allGenres) and left out
        boolean allGenres;
        int yearFrom, yearTo;           // 0: any year
        int statuses;                   // bits of STATUSES: any of them
        final int[] ratings = new int[RATINGS.length];     // 0: any, else 1 + an index into RATING_MIN
        final int[] parental = new int[PARENTAL.length];   // 0: any
        boolean unrated;                // with a parental filter, titles the guide hasn't rated stay

        Filter copy() {
            Filter f = new Filter();
            f.type = type;
            f.source = source;
            f.genres = genres;
            f.without = without;
            f.allGenres = allGenres;
            f.yearFrom = yearFrom;
            f.yearTo = yearTo;
            f.statuses = statuses;
            System.arraycopy(ratings, 0, f.ratings, 0, ratings.length);
            System.arraycopy(parental, 0, f.parental, 0, parental.length);
            f.unrated = unrated;
            return f;
        }

        boolean none() {
            if (type != 0 || source != 0 || genres != 0 || without != 0 || yearFrom != 0 || statuses != 0) return false;
            for (int r : ratings) if (r != 0) return false;
            return !restricted();
        }

        /** A kind of content is limited. */
        boolean restricted() {
            for (int p : parental) if (p != 0) return true;
            return false;
        }

        /** The statuses a type has: a movie's ("movie"), a show's ("tv"), or both (null). */
        static int statusesOf(String kind) {
            return kind == null ? MOVIE_STATUSES | SHOW_STATUSES : kind.equals("movie") ? MOVIE_STATUSES : SHOW_STATUSES;
        }

        /** Another type: the statuses it can't have go. */
        void setType(int t) {
            type = t;
            statuses &= statusesOf(TYPES[t]);
        }

        /** Has `kind`'s titles ("movie" or "tv"): its type, and one of its statuses if statuses are chosen. */
        boolean takes(String kind) {
            if (type != 0 && !TYPES[type].equals(kind)) return false;
            return statuses == 0 || (statuses & statusesOf(kind)) != 0;
        }

        String source() {
            return SOURCES[source];
        }

        /** Trakt's query parameters for `kind`'s titles ("movie" or "tv"), each starting with "&". */
        String query(String kind) {
            StringBuilder q = new StringBuilder();
            if (genres != 0 || without != 0) {
                q.append("&genres=").append(genreList());
                if (allGenres && Long.bitCount(genres) > 1) q.append("&genres_operator=and");
            }
            if (yearFrom > 0) q.append("&years=").append(yearFrom).append('-').append(yearTo);   // one year too: Y-Y
            int s = statuses & statusesOf(kind);
            if (s != 0) {
                StringBuilder v = new StringBuilder();
                for (int i = 0; i < STATUSES.length; i++) {
                    if ((s & 1 << i) != 0) v.append(v.length() > 0 ? "," : "").append(STATUSES[i].replace(" ", "%20"));
                }
                q.append("&statuses=").append(v);
            }
            for (int i = 0; i < RATINGS.length; i++) {
                if (ratings[i] == 0) continue;
                q.append('&').append(RATINGS[i]).append('=').append(number(RATING_MIN[i][ratings[i] - 1])).append('-')
                        .append(number(RATING_TOP[i]));
            }
            for (int i = 0; i < PARENTAL.length; i++) {
                if (parental[i] != 0) q.append("&parental_").append(PARENTAL[i]).append("=0-").append(3 - parental[i]);
            }
            if (restricted()) q.append("&parental_include_unrated=").append(unrated);
            return q.toString();
        }

        /** The genres as Trakt takes them: "comedy,drama,-horror" (the ones left out with a minus). */
        private String genreList() {
            StringBuilder g = new StringBuilder();
            for (int i = 0; i < GENRES.length; i++) {
                if ((genres & 1L << i) != 0) g.append(g.length() > 0 ? "," : "").append(GENRES[i]);
                else if ((without & 1L << i) != 0) g.append(g.length() > 0 ? ",-" : "-").append(GENRES[i]);
            }
            return g.toString();
        }

        private static String number(float v) {
            return v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(v);
        }

        private static int bits(int... at) {
            int b = 0;
            for (int i : at) b |= 1 << i;
            return b;
        }

        /** By value: "v2;type=tv;source=trending;genres=comedy,-horror;all=1;years=2010-2015;…", only what isn't the default. */
        String saved() {
            StringBuilder s = new StringBuilder("v2");
            if (type != 0) s.append(";type=").append(TYPES[type]);
            if (source != 0) s.append(";source=").append(SOURCES[source]);
            if (genres != 0 || without != 0) s.append(";genres=").append(genreList());
            if (allGenres) s.append(";all=1");
            if (yearFrom > 0) s.append(";years=").append(yearFrom).append('-').append(yearTo);
            if (statuses != 0) {
                s.append(";statuses=");
                for (int i = 0, n = 0; i < STATUSES.length; i++) if ((statuses & 1 << i) != 0) s.append(n++ > 0 ? "," : "").append(STATUSES[i]);
            }
            StringBuilder r = new StringBuilder();
            for (int i = 0; i < RATINGS.length; i++) {
                if (ratings[i] != 0) r.append(r.length() > 0 ? "," : "").append(RATINGS[i]).append(':').append(number(RATING_MIN[i][ratings[i] - 1]));
            }
            if (r.length() > 0) s.append(";ratings=").append(r);
            StringBuilder p = new StringBuilder();
            for (int i = 0; i < PARENTAL.length; i++) {
                if (parental[i] != 0) p.append(p.length() > 0 ? "," : "").append(PARENTAL[i]).append(':').append(3 - parental[i]);
            }
            if (p.length() > 0) s.append(";parental=").append(p);
            if (unrated) s.append(";unrated=1");
            return s.toString();
        }

        /** A saved filter; one saved before (type|decade|rating|genre|status) becomes the same filter. Unknown values go. */
        static Filter parse(String saved) {
            Filter f = new Filter();
            if (saved == null) return f;
            try {
                if (saved.startsWith("v2")) {
                    for (String part : saved.split(";")) {
                        int eq = part.indexOf('=');
                        if (eq < 0) continue;
                        String key = part.substring(0, eq), value = part.substring(eq + 1);
                        switch (key) {
                            case "type": f.type = Math.max(0, Arrays.asList(TYPES).indexOf(value)); break;
                            case "source": f.source = Math.max(0, Arrays.asList(SOURCES).indexOf(value)); break;
                            case "genres":
                                for (String g : value.split(",")) {
                                    int i = Arrays.asList(GENRES).indexOf(g.startsWith("-") ? g.substring(1) : g);
                                    if (i < 0) continue;
                                    if (g.startsWith("-")) f.without |= 1L << i; else f.genres |= 1L << i;
                                }
                                break;
                            case "all": f.allGenres = value.equals("1"); break;
                            case "years":
                                String[] y = value.split("-");
                                f.yearFrom = Integer.parseInt(y[0]);
                                f.yearTo = Integer.parseInt(y[y.length - 1]);
                                break;
                            case "statuses":
                                for (String st : value.split(",")) {
                                    int i = Arrays.asList(STATUSES).indexOf(st);
                                    if (i >= 0) f.statuses |= 1 << i;
                                }
                                break;
                            case "ratings":
                                for (String rv : value.split(",")) {
                                    String[] kv = rv.split(":");
                                    int i = Arrays.asList(RATINGS).indexOf(kv[0]);
                                    if (i >= 0 && kv.length == 2) f.ratings[i] = minIndex(i, Float.parseFloat(kv[1]));
                                }
                                break;
                            case "parental":
                                for (String pv : value.split(",")) {
                                    String[] kv = pv.split(":");
                                    int i = Arrays.asList(PARENTAL).indexOf(kv[0]);
                                    if (i >= 0 && kv.length == 2) f.parental[i] = Math.max(0, Math.min(3, 3 - Integer.parseInt(kv[1])));
                                }
                                break;
                            case "unrated": f.unrated = value.equals("1"); break;
                            default: break;
                        }
                    }
                } else {
                    String[] v = saved.split("\\|", -1);
                    if (v.length != 5) return f;
                    f.type = Math.max(0, Arrays.asList(TYPES).indexOf(v[0].isEmpty() ? null : v[0]));
                    int decade = Integer.parseInt(v[1]);
                    if (decade == 1900) {
                        f.yearFrom = 1900;
                        f.yearTo = 1989;
                    } else if (decade > 0) {
                        f.yearFrom = decade;
                        f.yearTo = decade + 9;
                    }
                    int rating = Integer.parseInt(v[2]);
                    if (rating > 0) f.ratings[0] = minIndex(0, rating * 10);
                    int g = Arrays.asList(GENRES).indexOf(v[3]);
                    if (g >= 0) f.genres = 1L << g;
                    switch (Integer.parseInt(v[4])) {
                        case 1: f.statuses = bits(1, 2); break;
                        case 2: f.statuses = bits(3, 5, 6, 7); break;
                        case 3: f.statuses = bits(10); break;
                        case 4: f.statuses = bits(9); break;
                        default: break;
                    }
                    if (f.type != SHOWS) f.statuses = 0;
                }
            } catch (RuntimeException e) {
                return new Filter();
            }
            if (f.yearFrom <= 0 || f.yearTo < f.yearFrom) f.yearFrom = f.yearTo = 0;
            if ((f.statuses & ONGOING) != 0) f.statuses |= ONGOING;
            f.statuses &= statusesOf(TYPES[f.type]);
            return f;
        }

        /** 1 + the index of `value` among the source's minimums (the nearest one at or below it), 0 below them all. */
        private static int minIndex(int source, float value) {
            int at = 0;
            for (int i = 0; i < RATING_MIN[source].length; i++) if (RATING_MIN[source][i] <= value + 0.001f) at = i + 1;
            return at;
        }

        @Override public boolean equals(Object o) {
            return o instanceof Filter && ((Filter) o).saved().equals(saved());
        }

        @Override public int hashCode() {
            return saved().hashCode();
        }
    }

    private static final String API = "https://api.themoviedb.org/3";
    /** The backdrops: on a 720p UI, TMDB's 1280-wide copy shows the same and is lighter to download and decode. */
    private static final String IMAGES = "https://image.tmdb.org/t/p/" + (Backdrop.W <= 1280 ? "w1280" : "original");
    /** Title logos: 500 wide is more than the wide card shows. */
    private static final String LOGOS = "https://image.tmdb.org/t/p/w500";
    /** TMDB's Animation genre (movies and shows: cartoons and anime), left out with hide_animation=true. */
    private static final int ANIMATION = 16;
    private static final long LIST_EVERY = 6 * 3600_000L;
    /** Titles a new list takes at most (movies and shows together): all of Trakt's recommendations, or of a list. */
    private static final int LIST_MAX = 400;
    /** Titles of each type a filter asks Trakt for (its most at once). */
    private static final int DISCOVER_MAX = 250;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 16;
    private static final boolean FILMIX_ONLY = "filmix".equals(Config.RECOMMENDATIONS_APP);
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"prime video", "prime", "com.amazon.firebat"},
        {"prime video", "prime", "com.amazon.avod"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"hulu", "hulu", "com.hulu.plus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"paramount", "paramount", "com.cbs.ca"},      // Paramount+ outside the US (a Sony in the UK; Canada)
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"peacock", "peacock", "com.peacock.peacockfiretv"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"max", "max", "com.hbo.hbonow"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
        {"apple tv", "appletv", "com.apple.atve.sony.appletv"},   // Sony's own build
        {"apple tv", "appletv", "com.apple.atve.amazon.appletv"},
        {"hotstar", "hotstar", "in.startv.hotstar"},   // TMDB: "JioHotstar" (earlier "Disney Plus Hotstar")
        {"crunchyroll", "crunchyroll", "com.crunchyroll.crunchyroid"},
        {"now tv", "now", "com.bskyb.nowtv.beta"},     // UK: Sky's NOW (TMDB: "Now TV", "Now TV Cinema")
        {"oneplay", "oneplay", "net.cme.voyo.cz.tvapp"},    // Czechia: Voyo and O2 TV became Oneplay, same app
        // Russia (TMDB's watch providers for RU)
        {"okko", "okko", "ru.more.play"},
        {"kinopoisk", "kinopoisk", "ru.kinopoisk.tv"},
        {"premier", "premier", "gpm.tnt_premier"},
        {"amediateka", "amediateka", "ru.amediateka"},
        {"more tv", "wink", "ru.rt.video.app.tv"},   // TMDB: "More TV" (more.tv is part of Rostelecom's Wink now)
    };
    private static final Map<String, String> SERVICE_NAME = new HashMap<>();
    static {
        SERVICE_NAME.put("filmix", "Filmix");
        SERVICE_NAME.put("netflix", "Netflix");
        SERVICE_NAME.put("prime", "Prime Video");
        SERVICE_NAME.put("disney", "Disney+");
        SERVICE_NAME.put("hulu", "Hulu");
        SERVICE_NAME.put("paramount", "Paramount+");
        SERVICE_NAME.put("peacock", "Peacock");
        SERVICE_NAME.put("globoplay", "Globoplay");
        SERVICE_NAME.put("max", "Max");
        SERVICE_NAME.put("appletv", "Apple TV");
        SERVICE_NAME.put("hotstar", "JioHotstar");
        SERVICE_NAME.put("okko", "Okko");
        SERVICE_NAME.put("crunchyroll", "Crunchyroll");
        SERVICE_NAME.put("now", "NOW");
        SERVICE_NAME.put("oneplay", "Oneplay");
        SERVICE_NAME.put("kinopoisk", "Кинопоиск");
        SERVICE_NAME.put("premier", "Premier");
        SERVICE_NAME.put("amediateka", "Амедиатека");
        SERVICE_NAME.put("wink", "Wink");
    }
    /** Country for "what streams where" (config.properties "tmdb_region", e.g. US, GB, BR). */
    static final String REGION = Place.region();
    /** The errors the settings say in words: TMDB lists none of the TV's apps in REGION, or the TV has none we know. */
    static final String NO_APPS = "none of the TV's streaming services are on TMDB";
    static final String NO_KNOWN_APPS = "none of the TV's streaming apps can give picks";

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    // Built and ready to draw, least recently drawn first: a card on screen never loses its photo.
    private final Map<String, Backdrop> images = new LinkedHashMap<>(16, 0.75f, true);
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private volatile List<Pick> picks = new ArrayList<>();
    /** Filmix: the titles not looked up yet, in Trakt's (or TMDB's) order; picks takes them as the rows need them. */
    private final List<Pick> queue = new ArrayList<>();
    /** How many cards each row (Explore, Movies, Shows) shows; reaching the last one adds PAGE. Main thread. */
    private final int[] shown = {FIRST, FIRST, FIRST, FIRST};
    private final boolean[] loadingMore = new boolean[4];
    private volatile int period = -1;
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)
    private volatile long failedAt, keyStamp, linkRetryAt;
    private volatile String listLanguage = "";   // language used for the saved titles, genres and texts
    /**
     * Titles hidden from the rows (their menu's "Don't recommend"), by key: Trakt leaves them out of its recommendations
     * from then on, and these keep a saved list or another source from bringing them back. Kept apart from the cache,
     * which a new CACHE_VERSION clears.
     */
    private final Set<String> hidden = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final SharedPreferences hiddenPrefs;
    /** Each row's filter (only Explore has one), kept apart from the cache like `hidden`. */
    private final Filter[] filters = {new Filter(), new Filter(), new Filter(), new Filter()};
    private final SharedPreferences filterPrefs;
    /**
     * A row's own titles by key, in their order: filtered Explore's (under EXPLORE: Trakt's order for the filter, or
     * the lists'), and the Watchlist row's. Its cards are these, as they're confirmed (the titles not looked up yet wait
     * in the queue with the others). Empty while Trakt's answer is on its way; absent for a row without a filter
     * (Explore, Movies and Shows show the picks: Pick.rec) and for the Watchlist until Trakt answers.
     */
    private final Map<String, List<String>> rowTitles = new java.util.concurrent.ConcurrentHashMap<>();
    /** Filtered Explore's key in rowTitles (a map without null keys; Explore's kind is null) and in the saved filters. */
    private static final String EXPLORE = "explore";
    /**
     * The person's Trakt watchlist (keys: a pick's menu says Add or Remove), and the shows they watch with episodes out
     * that they haven't seen (key → how many: the card says so). Replaced whole, never changed in place.
     */
    private volatile Set<String> watchlist = new HashSet<>();
    private volatile Map<String, Integer> newEpisodes = new HashMap<>();
    private volatile boolean watchLoading;
    private volatile long watchAt;
    /** Trakt's watchlist and progress, apart from `io` (~20 requests): the cards' images don't wait for them. */
    private final ExecutorService watchIo = Executors.newSingleThreadExecutor();
    private static final long WATCH_EVERY = 30 * 60_000L;
    /** Titles Filmix doesn't have (or with no app on the TV), so a filter that brings them back doesn't ask again. */
    private final Set<String> missing = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Per row: a new filter starts a new generation, and the lookups and answers of the one before are dropped. */
    private final int[] generation = new int[4];
    private final long[] filterTriedAt = new long[4];
    /** Filmix answers one question at a time (a second one at once is refused): the rows and the search take turns. */
    private static final Object FILMIX = new Object();
    // The search: Trakt asked on a thread of its own, TMDB's details for its answers a few at a time, then Filmix for
    // each in order; a newer text (generation) drops what an older one was doing.
    private static final int SEARCH_MAX = 12;
    private final ExecutorService searchIo = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ThreadPoolExecutor searchDetails = new java.util.concurrent.ThreadPoolExecutor(4, 4,
            30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>());
    private volatile int searchGen, searchState = SEARCH_IDLE, searchExpected;
    private volatile String searchText = "";
    private volatile List<Pick> found = new ArrayList<>();
    /** Answers already looked up, by key, least recently used first: typing on (or again) doesn't ask again. */
    private final Map<String, Pick> searchKnown = new LinkedHashMap<>(16, 0.75f, true);
    private String searchLanguage = "";

    private Recs(Context c) {
        context = c;
        searchDetails.allowCoreThreadTimeOut(true);
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        hiddenPrefs = c.getSharedPreferences("recs-hidden", Context.MODE_PRIVATE);
        hidden.addAll(hiddenPrefs.getStringSet("keys", new HashSet<>()));
        filterPrefs = c.getSharedPreferences("recs-filters", Context.MODE_PRIVATE);
        filters[0] = Filter.parse(filterPrefs.getString(EXPLORE, null));
        if (filterPrefs.contains("movie") || filterPrefs.contains("tv")) {
            filterPrefs.edit().remove("movie").remove("tv").apply();   // Movies' and Shows' own filters, from before Explore's
        }
        if (cache.getInt("version", 0) != CACHE_VERSION
                || !Config.RECOMMENDATIONS_APP.equals(cache.getString("recommendationsApp", ""))) {
            cache.edit().clear().putInt("version", CACHE_VERSION)
                    .putString("recommendationsApp", Config.RECOMMENDATIONS_APP).apply();
        }
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            String waiting = cache.getString("queue", null);
            if (waiting != null) queue.addAll(parse(new JSONArray(waiting)));
            period = cache.getInt("period", -1);
            listAt = cache.getLong("listAt", 0);
            listLanguage = cache.getString("language", cache.getBoolean("english", false) ? "en" : "pt");
            keyStamp = cache.getLong("keyStamp", 0);
            JSONObject watch = new JSONObject(cache.getString("watch", "{}"));
            Set<String> listed = new HashSet<>();
            JSONArray l = watch.optJSONArray("listed");
            for (int i = 0; l != null && i < l.length(); i++) listed.add(l.optString(i));
            watchlist = listed;
            Map<String, Integer> fresh = new HashMap<>();
            JSONObject n = watch.optJSONObject("new");
            for (java.util.Iterator<String> it = n == null ? null : n.keys(); it != null && it.hasNext(); ) {
                String key = it.next();
                fresh.put(key, n.optInt(key));
            }
            newEpisodes = fresh;
            watchAt = watch.optLong("at");
            // Filtered Explore's titles, if they were fetched for the filter it has now; the watchlist's.
            JSONObject rows = new JSONObject(cache.getString("rows", "{}"));
            for (String kind : new String[] {EXPLORE, WATCHLIST}) {
                JSONObject o = rows.optJSONObject(kind);
                if (o == null || (kind.equals(EXPLORE) && (filters[0].none()
                        || !o.optString("filter").equals(filters[0].saved())))) continue;
                List<String> keys = new ArrayList<>();
                JSONArray a = o.optJSONArray("keys");
                for (int i = 0; a != null && i < a.length(); i++) keys.add(a.optString(i));
                rowTitles.put(kind, keys);
            }
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    static boolean hasKey(Context c) {
        return !Config.TMDB_KEY.trim().isEmpty();
    }

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** For the settings: the titles ready to show and, with Filmix, how many still wait to be looked up. */
    private void writeStatus() {
        int waiting, ready = 0;
        synchronized (queue) {
            waiting = queue.size();
        }
        for (Pick p : picks) if (!p.nowhere) ready++;
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", ready).put("waiting", waiting).put("error", error)
                    .put("at", System.currentTimeMillis()).toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    String error() { return error; }

    static String periodLabel() {
        int p = period(System.currentTimeMillis());
        return p == MANHA ? Ui.tr("THIS MORNING") : p == TARDE ? Ui.tr("THIS AFTERNOON") : Ui.tr("TONIGHT");
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    /** The Explore row's cards. */
    List<Pick> now() {
        return now(null);
    }

    /** A row's cards in order, as many as it shows now: movies only ("movie"), shows only ("tv") or both (null). */
    List<Pick> now(String kind) {
        if (SEARCH.equals(kind)) return new ArrayList<>(found);
        List<Pick> list = new ArrayList<>();
        if (!hasKey(context)) return list;    // no TMDB key: no recommendations
        if (FILMIX_ONLY && !filmixCurrent()) return list;
        int max = shown[row(kind)];
        Map<String, Boolean> have = new HashMap<>();   // one look per app, not per title (this runs every 15 s)
        for (Pick p : order(kind)) {
            if (list.size() >= max) break;
            if (visible(p, kind, have)) list.add(p);
        }
        return list;
    }

    /**
     * The confirmed titles a row draws from: the recommendations in order, or the row's own (a filtered row's, the
     * watchlist's) in their order.
     */
    private List<Pick> order(String kind) {
        List<Pick> all = picks;
        List<String> keys = rowTitles.get(titlesKey(kind));
        if (keys == null) {
            List<Pick> out = new ArrayList<>();
            if (ownTitles(kind)) return out;   // its titles aren't in yet
            for (Pick p : all) if (p.rec) out.add(p);
            return out;
        }
        Map<String, Pick> byKey = new HashMap<>();
        for (Pick p : all) byKey.put(key(p), p);
        List<Pick> out = new ArrayList<>();
        for (String k : keys) {
            Pick p = byKey.get(k);
            if (p != null) out.add(p);
        }
        return out;
    }

    /** The row shows titles of its own (filtered Explore, or the watchlist), not the picks. */
    private boolean ownTitles(String kind) {
        return WATCHLIST.equals(kind) || (kind == null && !filters[0].none());
    }

    /** The row's key in rowTitles: Explore's (kind null) is EXPLORE. */
    private static String titlesKey(String kind) {
        return kind == null ? EXPLORE : kind;
    }

    /** In the row: its own titles, or a recommendation of its kind. */
    private static boolean inRow(Pick p, String kind, List<String> keys) {
        return keys != null ? keys.contains(key(p)) : p.rec && (kind == null || kind.equals(p.kind));
    }

    /** The row's filter (only Explore has one). */
    Filter filter(String kind) {
        return filters[row(kind)];
    }

    /** On the person's Trakt watchlist (as far as this TV knows). */
    boolean watchlisted(Pick p) {
        return watchlist.contains(key(p));
    }

    /** A show's aired episodes the person hasn't seen, when some came out since they last watched it; else 0. */
    int newEpisodes(Pick p) {
        Integer n = p == null ? null : newEpisodes.get(key(p));
        return n == null ? 0 : n;
    }

    /**
     * Main thread: on the watchlist or off it, here at once (Trakt is asked apart): the Watchlist row gets it after its
     * shows with new episodes, or loses it.
     */
    void setWatchlisted(Pick p, boolean on) {
        String k = key(p);
        Set<String> listed = new HashSet<>(watchlist);
        if (on ? !listed.add(k) : !listed.remove(k)) return;
        watchlist = listed;
        List<String> keys = rowTitles.get(WATCHLIST);
        List<String> row = keys == null ? new ArrayList<>() : new ArrayList<>(keys);
        row.remove(k);
        if (on) {
            int at = 0;
            while (at < row.size() && newEpisodes.containsKey(row.get(at))) at++;
            row.add(at, k);
        } else if (newEpisodes.containsKey(k)) {
            row.add(0, k);   // its new episodes keep it there
        }
        rowTitles.put(WATCHLIST, row);
        if (!on || !p.searched) {
            notifyListeners();
            io.execute(this::save);
            return;
        }
        io.execute(() -> {
            adopt(p);
            notifyListeners();
            save();
        });
    }

    /**
     * io thread: a search's answer put on the watchlist joins the rows' titles, so the Watchlist row shows it now, not
     * at Trakt's next answer: once Filmix (or the TV's apps) is known to have it or not.
     */
    private void adopt(Pick p) {
        boolean streams = p.pkg != null && (!FILMIX_ONLY || filmixPick(p));
        if (!p.detailed || !(streams || away(p))) return;
        for (Pick have : picks) if (key(have).equals(key(p))) return;
        p.searched = false;   // its photo from now on in the rows' folder
        p.nowhere = !streams;
        List<Pick> list = new ArrayList<>(picks);
        list.add(p);
        picks = list;
    }

    /** Main thread: marked watched from here, a show has no new episodes left (until Trakt says otherwise). */
    void seen(Pick p) {
        if (!newEpisodes.containsKey(key(p))) return;
        Map<String, Integer> fresh = new HashMap<>(newEpisodes);
        fresh.remove(key(p));
        newEpisodes = fresh;
        notifyListeners();
        io.execute(this::save);
    }

    /** Main thread: the Watchlist row from Trakt again (its shows with new episodes, then the watchlist), apart from io. */
    private void loadWatchlist() {
        if (watchLoading || !Trakt.hasOAuth()) return;
        watchLoading = true;
        watchAt = System.currentTimeMillis();
        notifyListeners();
        watchIo.execute(() -> {
            watchRow(null);
            main.post(() -> {
                watchLoading = false;
                refill(WATCHLIST);
                notifyListeners();
                io.execute(this::save);
            });
        });
    }

    /**
     * The Watchlist row's titles from Trakt: the shows with episodes out since the person last watched (newest first),
     * then their watchlist (latest added first). The ones not known yet go to the queue (or, while the list is being
     * made, into `candidates`). If Trakt can't be asked, the row keeps what it had.
     */
    private void watchRow(List<Pick> candidates) {
        if (!Trakt.hasOAuth()) return;
        try {
            List<Trakt.Unwatched> fresh = Trakt.newEpisodes(System.currentTimeMillis());
            List<Trakt.Item> listed = Trakt.watchlist();
            List<Pick> found = new ArrayList<>();
            Map<String, Integer> counts = new HashMap<>();
            Set<String> keys = new HashSet<>();
            for (Trakt.Unwatched u : fresh) {
                Pick pick = pickFromTrakt(u.show, "tv");
                counts.put(key(pick), u.episodes);
                found.add(pick);
            }
            for (Trakt.Item item : listed) {
                Pick pick = pickFromTrakt(item, item.type.equals("show") ? "tv" : "movie");
                keys.add(key(pick));
                found.add(pick);
            }
            List<String> row = enqueue(found, candidates, true);
            watchlist = keys;
            newEpisodes = counts;
            rowTitles.put(WATCHLIST, row);
            AppLog.w("Trakt watchlist: " + listed.size() + " titles, " + fresh.size() + " shows with new episodes");
        } catch (IOException e) {
            AppLog.w("Trakt watchlist: " + e.getMessage());
        }
    }

    /** Filters only come with Trakt's own recommendations (signed in on the TV, or built in). */
    static boolean canFilter() {
        return Trakt.hasOAuth();
    }

    /**
     * Main thread: Explore's new filter (kind null; the other rows have none; a copy is kept). Its titles come again,
     * from Trakt with the filter applied there (it reaches past the unfiltered list's end); meanwhile the row is empty,
     * its cards pulsing. `failed` runs if Trakt can't be asked: the row goes back to what it showed.
     */
    void setFilter(String kind, Filter f, Runnable failed) {
        int r = row(kind);
        Filter before = filters[r];
        if (kind != null || f.equals(before)) return;
        f = f.copy();
        List<String> keysBefore = rowTitles.get(EXPLORE);
        filters[r] = f;
        filterPrefs.edit().putString(EXPLORE, f.saved()).apply();
        shown[r] = FIRST;
        if (f.none()) {
            generation[r]++;
            loadingMore[r] = false;
            rowTitles.remove(EXPLORE);
            refill(kind);
            notifyListeners();
            io.execute(this::save);
            return;
        }
        loadFilter(() -> {
            filters[r] = before;
            filterPrefs.edit().putString(EXPLORE, before.saved()).apply();
            if (keysBefore != null) rowTitles.put(EXPLORE, keysBefore);
            failed.run();
        });
    }

    /**
     * Main thread: filtered Explore's titles, the row empty and pulsing meanwhile. `failed` runs (on the main thread) if
     * Trakt can't be asked; the row then has no titles until it's asked again.
     */
    private void loadFilter(Runnable failed) {
        int r = row(null), gen = ++generation[r];
        Filter f = filters[r];
        rowTitles.put(EXPLORE, new ArrayList<>());
        loadingMore[r] = true;
        filterTriedAt[r] = System.currentTimeMillis();
        notifyListeners();
        io.execute(() -> {
            List<String> keys;
            try {
                keys = filteredKeys(f, null);
            } catch (IOException e) {
                AppLog.w("Trakt filtered: " + e.getMessage());
                keys = null;
            }
            List<String> found = keys;
            main.post(() -> {
                if (gen != generation[r]) return;
                loadingMore[r] = false;
                if (found == null) {
                    generation[r]++;
                    rowTitles.remove(EXPLORE);
                    failed.run();
                } else {
                    rowTitles.put(EXPLORE, found);
                }
                refill(null);
                notifyListeners();
                io.execute(this::save);
            });
        });
    }

    /** Main thread: a row with fewer cards than it shows looks its next titles up, if it has any left. */
    private void refill(String kind) {
        if (count(kind) < shown[row(kind)] && queued(kind)) lookUp(kind);
    }

    /**
     * io thread: filtered Explore's titles by key, movies and shows taking turns (or the one type the filter takes),
     * from the filter's source on Trakt (the person's recommendations, or Trakt's trending, popular or anticipated
     * titles), filtered there; the ones they've watched left out. The ones not known yet go to the queue (or, while the
     * list is being made, into `candidates`), to be looked up when the row gets to them.
     */
    private List<String> filteredKeys(Filter f, List<Pick> candidates) throws IOException {
        List<Pick> movies = new ArrayList<>(), shows = new ArrayList<>();
        for (String kind : new String[] {"movie", "tv"}) {
            if (!f.takes(kind)) continue;
            List<Trakt.Item> items = Trakt.discover(f.source(), kind.equals("movie") ? "movies" : "shows", f.query(kind), DISCOVER_MAX);
            for (Trakt.Item item : items) (kind.equals("movie") ? movies : shows).add(pickFromTrakt(item, kind));
        }
        List<Pick> found = new ArrayList<>();
        alternate(found, new HashSet<>(), movies, shows, LIST_MAX);
        List<String> keys = enqueue(found, candidates, false);
        AppLog.w("Trakt filtered " + f.saved() + ": " + keys.size() + " titles");
        return keys;
    }

    /**
     * A row's own titles: their keys in order, without repeats or (but on the watchlist, `all`) the ones Filmix doesn't
     * have; the ones not known yet go to the queue, or into `candidates` while the list is being made (then only the new
     * list counts as known: the titles of the one it replaces are looked up again with the others).
     */
    private List<String> enqueue(List<Pick> found, List<Pick> candidates, boolean all) {
        Set<String> known = new HashSet<>();
        if (candidates == null) for (Pick p : picks) known.add(key(p));
        List<String> keys = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        synchronized (queue) {
            if (candidates == null) for (Pick p : queue) known.add(key(p));
            else for (Pick p : candidates) known.add(key(p));
            for (Pick p : found) {
                String k = key(p);
                if ((!all && missing.contains(k)) || !seen.add(k)) continue;
                keys.add(k);
                if (!known.add(k)) continue;
                if (candidates != null) candidates.add(p);
                else queue.add(p);
            }
        }
        return keys;
    }

    private boolean visible(Pick p, String kind, Map<String, Boolean> have) {
        if (WATCHLIST.equals(kind) && away(p)) return true;   // the watchlist has it all, marked when no app has it
        return (!FILMIX_ONLY || filmixPick(p)) && (kind == null || kind.equals(WATCHLIST) || kind.equals(p.kind))
                && (WATCHLIST.equals(kind) || !hidden.contains(key(p)))
                && (!p.detailed || (p.pkg != null && have.computeIfAbsent(p.pkg, this::installed)));   // app missing: skip
    }

    private static int row(String kind) {
        return kind == null ? 0 : kind.equals("movie") ? 1 : kind.equals("tv") ? 2 : 3;
    }

    /**
     * The focus reached a row's last card (main thread): PAGE more. With Filmix, the queue's next titles are looked up
     * now, one at a time, and each card shows as soon as its title is confirmed. False when the row has nothing more.
     */
    boolean more(String kind) {
        if (SEARCH.equals(kind) || !hasMore(kind)) return false;   // a search's answers come by themselves
        int r = row(kind);
        shown[r] += PAGE;
        if (count(kind) < shown[r]) lookUp(kind);
        notifyListeners();
        return true;
    }

    /**
     * Main thread: the title leaves every row now and for good (its menu's "Don't recommend"); a row left short takes
     * its next title. False if it was already hidden.
     */
    boolean hide(Pick p) {
        if (!hidden.add(key(p))) return false;
        saveHidden();
        synchronized (queue) {
            for (int i = queue.size() - 1; i >= 0; i--) if (key(queue.get(i)).equals(key(p))) queue.remove(i);
        }
        for (String kind : new String[] {null, "movie", "tv", WATCHLIST}) refill(kind);
        notifyListeners();
        return true;
    }

    /** Main thread: back in its place (Trakt refused to hide it). */
    void unhide(Pick p) {
        if (!hidden.remove(key(p))) return;
        saveHidden();
        notifyListeners();
    }

    private void saveHidden() {
        hiddenPrefs.edit().putStringSet("keys", new HashSet<>(hidden)).apply();
    }

    /** The row has more to show: cards already confirmed, or titles still waiting in the queue. */
    boolean hasMore(String kind) {
        if (!hasKey(context) || (FILMIX_ONLY && !filmixCurrent())) return false;
        return count(kind) > shown[row(kind)] || queued(kind);
    }

    /** The row's next titles are being looked up (its last cards are still empty). */
    boolean loading(String kind) {
        if (SEARCH.equals(kind)) return searchState == SEARCH_ASKING || searchState == SEARCH_FILLING;
        return loadingMore[row(kind)] || (WATCHLIST.equals(kind) && watchLoading && !rowTitles.containsKey(WATCHLIST));
    }

    private int count(String kind) {
        int n = 0;
        Map<String, Boolean> have = new HashMap<>();
        for (Pick p : order(kind)) if (visible(p, kind, have)) n++;
        return n;
    }

    private boolean queued(String kind) {
        synchronized (queue) {
            return next(queue, kind, false) != null;
        }
    }

    /**
     * The row's next title in `list` (taken out of it with `take`): the first of its kind, or a filtered row's next one
     * in Trakt's order for it.
     */
    private Pick next(List<Pick> list, String kind, boolean take) {
        List<String> keys = rowTitles.get(titlesKey(kind));
        if (keys == null && ownTitles(kind)) return null;   // its titles aren't in yet
        int at = -1;
        if (keys == null) {
            for (int i = 0; i < list.size() && at < 0; i++) if (inRow(list.get(i), kind, null)) at = i;
        } else {
            Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < list.size(); i++) index.put(key(list.get(i)), i);
            for (int i = 0; i < keys.size() && at < 0; i++) {
                Integer found = index.get(keys.get(i));
                if (found != null) at = found;
            }
        }
        if (at < 0) return null;
        return take ? list.remove(at) : list.get(at);
    }

    /** Main thread: looks up the row's next titles in the background until it has as many cards as it shows. */
    private void lookUp(String kind) {
        int r = row(kind);
        if (loadingMore[r]) return;
        loadingMore[r] = true;
        int gen = generation[r];
        io.execute(() -> lookUpNext(kind, gen));
    }

    /** One title from the queue (io thread), then the next one as a new task, so the cards' images load in between. */
    private void lookUpNext(String kind, int gen) {
        boolean failed = false;
        Pick next;
        synchronized (queue) {
            next = next(queue, kind, true);
        }
        if (next != null) {
            try {
                boolean found = confirm(next);
                if (!found) missing.add(key(next));
                if (found || keepAway(next)) {
                    List<Pick> list = new ArrayList<>(picks);
                    list.add(next);
                    picks = list;
                    notifyListeners();
                }
            } catch (IOException e) {   // Filmix can't be asked now: the title goes back for the next try
                synchronized (queue) {
                    queue.add(0, next);
                }
                failed = true;
                AppLog.w("Filmix: " + e.getMessage());
            }
            save();
            writeStatus();   // the settings count what is ready and what still waits
        }
        boolean stop = failed || next == null;
        main.post(() -> {
            int r = row(kind);
            if (gen != generation[r]) return;   // another filter since: its own lookups run
            if (!stop && count(kind) < shown[r] && queued(kind)) {
                io.execute(() -> lookUpNext(kind, gen));
                return;
            }
            loadingMore[r] = false;
            shown[r] = Math.min(shown[r], Math.max(count(kind), FIRST));   // what didn't come isn't waited for
            notifyListeners();
        });
    }

    /** How many of `list` the row would show (a filtered row, the watchlist: its own titles). */
    private int count(List<Pick> list, String kind) {
        List<String> keys = rowTitles.get(titlesKey(kind));
        if (keys == null && ownTitles(kind)) return 0;
        int n = 0;
        for (Pick p : list) if (inRow(p, kind, keys)) n++;
        return n;
    }

    /**
     * io thread, while the list is made again: filtered Explore's titles, the new ones added to `candidates`. If Trakt
     * can't be asked, the row keeps the ones it had.
     */
    private void filterRows(List<Pick> candidates) {
        Filter f = filters[0];
        if (f.none()) return;
        try {
            rowTitles.put(EXPLORE, filteredKeys(f, candidates));
        } catch (IOException e) {
            AppLog.w("Trakt filtered: " + e.getMessage());
        }
    }

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
    private static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /** The one app every pick opens in ("Filmix"), or null when each opens in the service that streams it. */
    static String onlyApp() {
        return FILMIX_ONLY ? SERVICE_NAME.get("filmix") : null;
    }

    /** Name of the service the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        return p == null || p.app == null ? "" : SERVICE_NAME.get(p.app);
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        io.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    /** The pick's backdrop if it's already built, else null (and nothing starts loading). */
    Backdrop ready(Pick p) {
        if (p == null || p.backdrop == null) return null;
        synchronized (images) {
            return images.get(p.backdrop);
        }
    }

    /** How many cards the row shows now, counting the ones still being looked up. Main thread. */
    int shown(String kind) {
        if (SEARCH.equals(kind)) {
            // Until Trakt answers, the answers there are (or four empty cards); then as many as Trakt gave.
            List<Pick> list = found;
            return searchState == SEARCH_FILLING ? searchExpected
                    : searchState == SEARCH_ASKING && list.isEmpty() ? FIRST : list.size();
        }
        return shown[row(kind)];
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).exists();
    }

    /** A search's answers keep their photos apart: the rows' (and the screensaver's) stay on disk. */
    private File imageFile(Pick p) {
        return new File(new File(context.getCacheDir(), p.searched ? "recs-search" : "recs"), p.backdrop.replace("/", ""));
    }

    /** With a key: fetches the list if stale or the time of day (morning/afternoon/night) changed; then the details. */
    void refreshIfStale() {
        if (!hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        long sinceWatch = now - watchAt;
        if (!picks.isEmpty() && (sinceWatch < 0 || sinceWatch > (rowTitles.containsKey(WATCHLIST) ? WATCH_EVERY : 60_000L))) {
            loadWatchlist();
        }
        long sinceFilter = now - filterTriedAt[0];
        if (!filters[0].none() && !rowTitles.containsKey(EXPLORE) && !picks.isEmpty() && (sinceFilter < 0 || sinceFilter > 60_000L)) {
            loadFilter(() -> {});
        }
        long stamp = sourceStamp();   // a new key, country, animation choice or installed streaming-app set refetches
        boolean newKey = stamp != keyStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0 && sinceFail >= 0 && sinceFail < (FILMIX_ONLY ? 60_000L : 10 * 60_000L)) return;
        int p = period(now);
        boolean stale = newKey || p != period || now - listAt > LIST_EVERY || now < listAt || picks.isEmpty()
                || !listLanguage.equals(Ui.language);
        boolean missing = false;
        for (Pick pick : wanted()) missing |= !pick.detailed || !hasImage(pick) || needsLink(pick);
        if (!stale && !missing) return;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            try {
                if (stale) fetchList(p);
                // One failing title doesn't stop the others; whatever has no app on the TV is not downloaded.
                int failures = 0, tried = 0;
                String last = "";
                for (Pick pick : wanted()) {
                    tried++;
                    try {
                        if (!pick.detailed) fetchDetails(pick);
                        if (pick.pkg != null) download(pick);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                    findLink(pick);
                }
                save();
                if (tried > 0 && failures == tried) throw new IOException(last);
                error = "";
                failedAt = 0;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
            } finally {
                fetching = false;
                writeStatus();
                notifyListeners();
            }
        });
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            images.clear();
        }
    }

    /**
     * Screensaver: the picks with an app on the TV and an image already on disk, starting with this hour's (the
     * featured one first), never two from the same franchise.
     */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        if (FILMIX_ONLY && !filmixCurrent()) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : wanted()) if (hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        Map<String, Boolean> have = new HashMap<>();
        for (Pick p : picks) {
            if ((!FILMIX_ONLY || filmixPick(p)) && p.detailed && p.pkg != null && have.computeIfAbsent(p.pkg, this::installed) && hasImage(p)
                    && seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p) : null;
    }

    /**
     * While the screensaver is showing: details and image for every pick in the list (not just this hour's), one at a
     * time in the background, so it has more photos. Not used otherwise.
     */
    void prefetchAll() {
        if (!hasKey(context) || fetching) return;
        long sinceFail = System.currentTimeMillis() - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        List<Pick> todo = new ArrayList<>();
        for (Pick p : picks) if (!p.detailed || (p.pkg != null && !hasImage(p))) todo.add(p);
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            int failures = 0;
            String last = "";
            try {
                // One failing title (gone from TMDB, an image that won't download) doesn't stop the others.
                for (Pick p : todo) {
                    try {
                        if (!p.detailed) fetchDetails(p);
                        if (p.pkg != null) download(p);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (failures == todo.size()) {
                    error = last;
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations (screensaver): " + last);
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    /** The cards the three rows (Explore, Movies, Shows) show now, without repeats: their images are wanted. */
    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String kind : new String[] {null, "movie", "tv", WATCHLIST}) {
            for (Pick p : now(kind)) if (seen.add(key(p))) out.add(p);
        }
        return out;
    }

    // MARK: - Search (the search field's row)

    /** Where the search is (SEARCH_IDLE…). */
    int searchState() {
        return searchState;
    }

    /**
     * A search's answer or a watchlist's title Filmix doesn't have (without Filmix: none of the TV's apps streams it).
     * False while that isn't known: Filmix couldn't be asked, and is asked again at the next search for it.
     */
    boolean away(Pick p) {
        return p.pkg == null && p.detailed && (!FILMIX_ONLY || p.nowhere || missing.contains(key(p)));
    }

    /** What the search's answers are for. */
    String searchText() {
        return searchText;
    }

    /**
     * Main thread: the search row's answers for `text` (empty: none). Until Trakt answers, the row keeps the ones it has;
     * then the new ones come in order, each as soon as it's looked up, and a newer text drops them. A failed search is
     * asked again for the same text.
     */
    void search(String text) {
        String q = text == null ? "" : text.trim();
        if (q.equals(searchText) && searchState != SEARCH_FAILED) return;
        searchText = q;
        int gen = ++searchGen;
        if (q.isEmpty() || !Trakt.hasKey() || !hasKey(context)) {
            found = new ArrayList<>();
            searchState = SEARCH_IDLE;
        } else {
            searchState = SEARCH_ASKING;
            searchIo.execute(() -> runSearch(q, gen));
        }
        notifyListeners();
    }

    /**
     * searchIo: Trakt's answers (SEARCH_MAX at most, most watched first), the ones known already as they are; TMDB's
     * details for the others four at a time; then, in order, Filmix for each (the rows' lookups wait their turn), the row
     * taking each answer once it's done. Every answer shows, Filmix's or not: a search is for finding, and the menu
     * works on any of them (the card says where Filmix hasn't it).
     */
    private void runSearch(String q, int gen) {
        List<Trakt.Item> items;
        try {
            items = Trakt.search(q, 20);
        } catch (IOException e) {
            AppLog.w("Trakt search: " + e.getMessage());
            main.post(() -> {
                if (gen != searchGen) return;
                found = new ArrayList<>();
                searchState = SEARCH_FAILED;
                notifyListeners();
            });
            return;
        }
        Map<String, Pick> known = new HashMap<>();
        for (Pick p : picks) if (p.detailed) known.put(key(p), p);
        List<Pick> answers = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        synchronized (searchKnown) {
            if (!searchLanguage.equals(Ui.language)) {   // their titles and details are in another language
                searchKnown.clear();
                searchLanguage = Ui.language;
            }
            for (Trakt.Item item : items) {
                if (answers.size() >= SEARCH_MAX) break;
                Pick p = pickFromTrakt(item, item.type.equals("show") ? "tv" : "movie");
                String k = key(p);
                if (!seen.add(k)) continue;
                Pick had = known.get(k);
                if (had == null) had = searchKnown.get(k);
                if (had == null) p.searched = true;
                answers.add(had != null ? had : p);
            }
        }
        List<java.util.concurrent.Future<Boolean>> asked = new ArrayList<>();
        for (Pick p : answers) asked.add(searchDetails.submit(() -> gen == searchGen && (p.detailed || details(p))));
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < answers.size(); i++) {
            if (gen != searchGen) return;
            Pick p = answers.get(i);
            boolean ok;
            try {
                ok = asked.get(i).get();
            } catch (Exception e) {
                ok = false;
            }
            if (!ok) continue;
            if (FILMIX_ONLY && p.pkg == null && p.year >= 1888 && !missing.contains(key(p))) {
                try {
                    String link;
                    synchronized (FILMIX) {
                        link = Filmix.find(context, p.title, p.originalTitle, p.year, p.kind.equals("tv"));
                    }
                    if (link == null) {
                        missing.add(key(p));
                    } else {
                        p.app = "filmix";
                        p.pkg = Filmix.PKG;
                        p.link = link;
                        p.nowhere = false;   // a watchlist's title Filmix has now
                    }
                } catch (IOException e) {   // asked again at the next search for it
                    AppLog.w("Filmix (search): " + e.getMessage());
                }
            }
            if (p.searched) {
                synchronized (searchKnown) {
                    searchKnown.put(key(p), p);
                    while (searchKnown.size() > 60) searchKnown.remove(searchKnown.keySet().iterator().next());
                }
            }
            out.add(p);
            List<Pick> shown = new ArrayList<>(out);
            int expected = out.size() + answers.size() - 1 - i;   // the ones still to come, if they all do
            main.post(() -> {
                if (gen != searchGen) return;
                found = shown;
                searchExpected = expected;
                searchState = SEARCH_FILLING;
                notifyListeners();
            });
        }
        main.post(() -> {
            if (gen != searchGen) return;
            found = new ArrayList<>(out);
            searchState = SEARCH_DONE;
            notifyListeners();
        });
        AppLog.w("Trakt search “" + q + "”: " + items.size() + " answers, " + out.size() + " shown");
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
    static String key(Pick p) {
        return p.kind + ":" + p.id;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - TMDB

    private void fetchList(int p) throws Exception {
        if (FILMIX_ONLY) {
            fetchFilmixList(p);
            return;
        }
        // None of the services we know is on the TV (or hide_services hides them all): no region would change that.
        boolean any = false;
        for (String[] s : SERVICES) any |= installed(s[2]);
        if (!any) throw new IOException(NO_KNOWN_APPS);
        // The services the TV has, by their TMDB ids in your region.
        Map<Integer, String[]> services = new HashMap<>();
        JSONArray providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + REGION).getJSONArray("results");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = service(o.optString("provider_name"), o.optInt("provider_id"));
            if (s != null && installed(s[2])) services.put(o.getInt("provider_id"), s);
        }
        if (services.isEmpty()) throw new IOException(NO_APPS);
        StringBuilder ids = new StringBuilder();
        for (int id : services.keySet()) ids.append(ids.length() == 0 ? "" : "|").append(id);
        String common = "&language=" + lang() + "&watch_region=" + REGION + "&with_watch_monetization_types=flatrate&include_adult=false"
                + "&sort_by=popularity.desc&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8")
                + (Config.HIDE_ANIMATION ? "&without_genres=" + ANIMATION : "");
        // 1. Trending this week (or Trakt personal recommendations if configured), only what is
        //    on one of the TV's services: the details say where (and are then ready).
        List<Pick> hotMovies = new ArrayList<>(), hotShows = new ArrayList<>();
        // A tab's own list (Home Settings) comes first, and alone: no trending or new releases join it.
        List<Trakt.Item> movieList = ownList("movie"), showList = ownList("tv");
        for (Trakt.Item item : movieList) {
            Pick pick = pickFromTrakt(item, "movie");
            try {
                fetchDetails(pick);
                if (pick.pkg != null) hotMovies.add(pick);
            } catch (Exception ignored) {}
        }
        for (Trakt.Item item : showList) {
            Pick pick = pickFromTrakt(item, "tv");
            try {
                fetchDetails(pick);
                if (pick.pkg != null) hotShows.add(pick);
            } catch (Exception ignored) {}
        }
        if ((movieList.isEmpty() || showList.isEmpty()) && (Trakt.hasOAuth() || (Trakt.hasKey() && !Config.TRAKT_USER.isEmpty()))) {
            try {
                for (Trakt.Item item : movieList.isEmpty() ? traktItems("movies", Config.TRAKT_LIST_MOVIES) : new ArrayList<Trakt.Item>()) {
                    Pick pick = pickFromTrakt(item, "movie");
                    try {
                        fetchDetails(pick);
                        if (pick.pkg != null) hotMovies.add(pick);
                    } catch (Exception ignored) {}
                }
                for (Trakt.Item item : showList.isEmpty() ? traktItems("shows", Config.TRAKT_LIST_SHOWS) : new ArrayList<Trakt.Item>()) {
                    Pick pick = pickFromTrakt(item, "tv");
                    try {
                        fetchDetails(pick);
                        if (pick.pkg != null) hotShows.add(pick);
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                AppLog.w("Trakt recommendations: " + e.getMessage());
            }
        }
        if (hotMovies.isEmpty() || hotShows.isEmpty()) {
            for (String kind : new String[] {"movie", "tv"}) {
                if (kind.equals("movie") && !hotMovies.isEmpty()) continue;
                if (kind.equals("tv") && !hotShows.isEmpty()) continue;
                JSONArray hot = get("/trending/" + kind + "/week?language=" + lang()).getJSONArray("results");
                List<Pick> candidates = new ArrayList<>();
                for (int i = 0; i < hot.length() && i < 20; i++) add(candidates, hot.getJSONObject(i), kind);
                for (Pick pick : candidates) {
                    try {
                        fetchDetails(pick);
                    } catch (Exception e) {
                        continue;
                    }
                    if (pick.pkg != null) (kind.equals("movie") ? hotMovies : hotShows).add(pick);
                }
            }
        }
        // 2. Recent releases on the TV's services: movies from the past year and shows with a new episode in the
        //    past 3 months.
        JSONArray movies = get("/discover/movie?vote_count.gte=30&primary_release_date.gte=" + day(-365)
                + "&primary_release_date.lte=" + day(0) + common).getJSONArray("results");
        JSONArray shows = get("/discover/tv?vote_count.gte=20&air_date.gte=" + day(-90) + "&air_date.lte=" + day(0)
                + common).getJSONArray("results");
        List<Pick> newMovies = new ArrayList<>(), newShows = new ArrayList<>();
        if (movieList.isEmpty()) for (int i = 0; i < movies.length(); i++) add(newMovies, movies.getJSONObject(i), "movie");
        if (showList.isEmpty()) for (int i = 0; i < shows.length(); i++) add(newShows, shows.getJSONObject(i), "tv");
        // Trending first, then the new releases; movies and shows alternating, without repeats, up to 24 (a list: all).
        int max = movieList.isEmpty() && showList.isEmpty() ? 24 : LIST_MAX;
        List<Pick> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        alternate(list, seen, hotMovies, hotShows, max);
        alternate(list, seen, newMovies, newShows, max);
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
        for (Pick pick : list) pick.rec = true;
        picks = list;
        synchronized (queue) {
            queue.clear();   // every title here is already checked: → past a row's end only shows more of them
        }
        List<Pick> extra = new ArrayList<>();
        filterRows(extra);   // a filtered row's and the watchlist's own titles: looked up (details, app) when they're due
        watchAt = System.currentTimeMillis();
        watchRow(extra);
        synchronized (queue) {
            queue.addAll(extra);
        }
        main.post(() -> Arrays.fill(shown, FIRST));
        period = p;
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
    }

    private static boolean filmixPick(Pick p) {
        return p.detailed && "filmix".equals(p.app) && Filmix.PKG.equals(p.pkg) && p.link != null;
    }

    private boolean filmixCurrent() {
        long age = System.currentTimeMillis() - listAt;
        return listAt > 0 && age >= 0 && age <= LIST_EVERY;
    }

    /** TMDB supplies discovery and artwork; only exact matches confirmed by Filmix reach the screen. */
    private void fetchFilmixList(int period) throws Exception {
        if (!installed(Filmix.PKG)) throw new IOException(Filmix.UNAVAILABLE);
        List<Pick> movies = new ArrayList<>(), shows = new ArrayList<>();
        // The person's own list for a tab, else Trakt's recommendations (or the lists built in), else TMDB's trending.
        List<Trakt.Item> movieList = ownList("movie"), showList = ownList("tv");
        for (Trakt.Item item : movieList) movies.add(pickFromTrakt(item, "movie"));
        for (Trakt.Item item : showList) shows.add(pickFromTrakt(item, "tv"));

        if (Trakt.hasOAuth() || (Trakt.hasKey() && !Config.TRAKT_USER.isEmpty())) {
            try {
                if (movies.isEmpty()) {
                    for (Trakt.Item item : traktItems("movies", Config.TRAKT_LIST_MOVIES)) {
                        movies.add(pickFromTrakt(item, "movie"));
                    }
                }
            } catch (Exception e) {
                AppLog.w("Trakt movies: " + e.getMessage());
            }

            try {
                if (shows.isEmpty()) {
                    for (Trakt.Item item : traktItems("shows", Config.TRAKT_LIST_SHOWS)) {
                        shows.add(pickFromTrakt(item, "tv"));
                    }
                }
            } catch (Exception e) {
                AppLog.w("Trakt shows: " + e.getMessage());
            }
        }

        if (movies.isEmpty()) {
            JSONArray hot = get("/trending/movie/week?language=" + lang()).getJSONArray("results");
            for (int i = 0; i < hot.length() && i < 20; i++) add(movies, hot.getJSONObject(i), "movie");
        }
        if (shows.isEmpty()) {
            JSONArray hot = get("/trending/tv/week?language=" + lang()).getJSONArray("results");
            for (int i = 0; i < hot.length() && i < 20; i++) add(shows, hot.getJSONObject(i), "tv");
        }

        List<Pick> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // All of Trakt's recommendations (50 movies, 50 shows), or of a list, are looked up in Filmix: none is cut before.
        alternate(candidates, seen, movies, shows, LIST_MAX);
        missing.clear();

        if (candidates.size() < 40) {
            String common = "&language=" + lang() + "&include_adult=false&sort_by=popularity.desc"
                    + (Config.HIDE_ANIMATION ? "&without_genres=" + ANIMATION : "");
            JSONArray recentMovies = get("/discover/movie?vote_count.gte=30&primary_release_date.gte=" + day(-365)
                    + "&primary_release_date.lte=" + day(0) + common).getJSONArray("results");
            JSONArray recentShows = get("/discover/tv?vote_count.gte=20&air_date.gte=" + day(-90)
                    + "&air_date.lte=" + day(0) + common).getJSONArray("results");
            List<Pick> discMovies = new ArrayList<>(), discShows = new ArrayList<>();
            // A tab with a list of its own shows only that list
            if (movieList.isEmpty()) for (int i = 0; i < recentMovies.length(); i++) add(discMovies, recentMovies.getJSONObject(i), "movie");
            if (showList.isEmpty()) for (int i = 0; i < recentShows.length(); i++) add(discShows, recentShows.getJSONObject(i), "tv");
            alternate(candidates, seen, discMovies, discShows, 60);
        }
        for (Pick pick : candidates) pick.rec = true;
        filterRows(candidates);
        watchAt = System.currentTimeMillis();
        watchRow(candidates);

        // Only what the rows show at first is looked up now (Explore's four, then Movies' and Shows'); the rest waits in
        // the queue until the focus on a row's last card asks for it.
        List<Pick> rest = new ArrayList<>(candidates), confirmed = new ArrayList<>();
        int checked = 0;
        for (String kind : new String[] {null, "movie", "tv", WATCHLIST}) {
            while (count(confirmed, kind) < FIRST) {
                Pick next = next(rest, kind, true);
                if (next == null) break;
                checked++;
                if (confirm(next)) {
                    confirmed.add(next);
                } else {
                    missing.add(key(next));
                    if (keepAway(next)) confirmed.add(next);
                }
            }
        }
        int on = 0;
        for (Pick p : confirmed) if (!p.nowhere) on++;
        AppLog.w("Filmix: " + on + " of " + checked + " titles found, " + rest.size() + " waiting");
        if (on == 0) throw new IOException("No matching TMDB titles were found in Filmix");
        picks = confirmed;
        synchronized (queue) {
            queue.clear();
            queue.addAll(rest);
        }
        main.post(() -> Arrays.fill(shown, FIRST));   // a new list: each row starts again with four
        this.period = period;
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
    }

    /**
     * Looks the title up in Filmix, with its TMDB details first (the Russian and original titles, the year). False when
     * Filmix doesn't have it; IOException when Filmix can't be asked.
     */
    private boolean confirm(Pick pick) throws IOException {
        if (!FILMIX_ONLY) return (pick.detailed || details(pick)) && pick.pkg != null;
        if (pick.year < 1888) return false;
        if (pick.backdrop == null && !details(pick)) return false;
        String link;
        synchronized (FILMIX) {
            link = Filmix.find(context, pick.title, pick.originalTitle, pick.year, pick.kind.equals("tv"));
        }
        if (link == null) {
            AppLog.w("Filmix: not found “" + pick.title + "” / “" + pick.originalTitle + "” (" + pick.year + ")");
            return false;
        }
        pick.app = "filmix";
        pick.pkg = Filmix.PKG;
        pick.link = link;
        return pick.detailed || details(pick);   // a missing TMDB detail must not make an incomplete card visible
    }

    /**
     * A title no app of the TV has (Filmix said so) that is on the watchlist: its card shows there all the same, marked
     * so, and nowhere else. False for the others, and without its TMDB details.
     */
    private boolean keepAway(Pick pick) {
        List<String> keys = rowTitles.get(WATCHLIST);
        if (pick.pkg != null || keys == null || !keys.contains(key(pick)) || !(pick.detailed || details(pick))) return false;
        pick.nowhere = true;
        return true;
    }

    private boolean details(Pick pick) {
        try {
            fetchDetails(pick);
            return true;
        } catch (Exception e) {
            AppLog.w("No TMDB details for " + key(pick) + " “" + pick.title + "”");
            return false;
        }
    }

    /**
     * The person's own Trakt list picked for the Movies ("movie") or Shows ("tv") tab (Home Settings), its titles of that
     * kind in its order; empty without one, or when Trakt can't give it (then the recommendations stand in).
     */
    private static List<Trakt.Item> ownList(String kind) {
        String list = Prefs.recsListRef(kind);
        if (list.isEmpty() || !Trakt.hasOAuth()) return new ArrayList<>();
        try {
            List<Trakt.Item> items = Trakt.listItems(list, kind.equals("movie") ? "movie" : "show");
            AppLog.w("Trakt list " + list + ": " + items.size() + " " + kind);
            return items;
        } catch (IOException e) {
            AppLog.w("Trakt list " + list + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Trakt's personal recommendation API first; the configured public lists remain as a fallback
     * when OAuth is missing or the recommendation endpoints return nothing.
     */
    private List<Trakt.Item> traktItems(String recKind, String listSlug) {
        if (Trakt.hasOAuth()) {
            try {
                List<Trakt.Item> items = Trakt.fetchRecommendations(recKind);
                if (!items.isEmpty()) return items;
            } catch (Exception e) {
                AppLog.w("Trakt " + recKind + " recommendations: " + e.getMessage());
            }
        }
        try {
            return Trakt.fetchListItems(Config.TRAKT_USER, listSlug);
        } catch (Exception e) {
            AppLog.w("Trakt " + recKind + " list: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static Pick pickFromTrakt(Trakt.Item item, String kind) {
        Pick p = new Pick();
        p.id = item.tmdbId;
        p.kind = kind;
        p.title = item.title;
        p.originalTitle = item.title;
        p.year = item.year;
        p.rating = item.votes >= 20 ? item.rating : 0;   // Trakt's, as its filters see it (the details keep it)
        return p;
    }

    /** Language of titles and genres (TMDB's "fr-FR", "zh-CN"…); the services come from the region. */
    private static String lang() {
        String lang = Ui.language;
        if (lang.equals("en")) return "en-US";
        if (lang.equals("pt")) return "pt-BR";
        if (lang.equals("es")) return "ES".equals(java.util.Locale.getDefault().getCountry()) ? "es-ES" : "es-MX";
        if (lang.equals("de")) return "de-DE";   // TMDB's de-AT and de-CH have few translations of their own
        if (lang.indexOf('-') > 0) return lang;
        return lang + "-" + Ui.locale().getCountry();
    }

    private static void alternate(List<Pick> out, java.util.Set<String> seen, List<Pick> a, List<Pick> b, int max) {
        for (int i = 0; i < Math.max(a.size(), b.size()) && out.size() < max; i++) {
            if (i < a.size() && seen.add(key(a.get(i)))) out.add(a.get(i));
            if (i < b.size() && out.size() < max && seen.add(key(b.get(i)))) out.add(b.get(i));
        }
    }

    /** The date `offset` days from today, as "2026-10-19" (negative = in the past). */
    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    /** Noon of a TMDB date ("2025-03-12") here, so no time zone moves it a day; 0 when there is none. */
    private static long dayAt(String date) {
        if (date == null || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return 0;
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(Integer.parseInt(date.substring(0, 4)), Integer.parseInt(date.substring(5, 7)) - 1,
                Integer.parseInt(date.substring(8, 10)), 12, 0);
        return cal.getTimeInMillis();
    }

    private static void add(List<Pick> list, JSONObject o, String kind) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return;
        if (Config.HIDE_ANIMATION && animated(o)) return;
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.originalTitle = o.optString(kind.equals("movie") ? "original_title" : "original_name");
        String date = o.optString(kind.equals("movie") ? "release_date" : "first_air_date");
        if (date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) p.year = Integer.parseInt(date.substring(0, 4));
        p.backdrop = backdrop;
        list.add(p);
    }

    private static boolean animated(JSONObject o) {
        JSONArray genres = o.optJSONArray("genre_ids");
        for (int i = 0; genres != null && i < genres.length(); i++) if (genres.optInt(i) == ANIMATION) return true;
        return false;
    }

    /**
     * A movie's year, genre and runtime; a show's last episode's day (where a movie's year is), genre and seasons; and the
     * service it's on, among the ones the TV has.
     */
    private void fetchDetails(Pick p) throws Exception {
        JSONObject o = get("/" + p.kind + "/" + p.id
                + "?language=" + lang() + "&append_to_response=" + (FILMIX_ONLY ? "images" : "watch/providers,images")
                + "&include_image_language=null," + Ui.language.replaceAll("-.*", "") + ",en");
        p.title = title(p, o);
        String origField = p.kind.equals("movie") ? "original_title" : "original_name";
        String orig = o.optString(origField, "");
        if (!orig.isEmpty()) p.originalTitle = orig;
        String date = o.optString(p.kind.equals("movie") ? "release_date" : "first_air_date");
        if (date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) p.year = Integer.parseInt(date.substring(0, 4));
        // A handful of votes says little: no rating then, rather than a misleading 10. Trakt's picks keep Trakt's.
        if (p.rating <= 0) p.rating = o.optInt("vote_count") >= 20 ? (float) o.optDouble("vote_average", 0) : 0;
        String best = bestBackdrop(o.optJSONObject("images"));
        if (best != null) p.backdrop = best;
        p.logo = bestLogo(o.optJSONObject("images"));
        List<String> parts = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        if (p.kind.equals("movie") && p.year > 0) parts.add(String.valueOf(p.year));
        if (p.kind.equals("tv")) {
            JSONObject last = o.optJSONObject("last_episode_to_air");
            long aired = dayAt(last != null ? last.optString("air_date") : o.optString("last_air_date"));
            if (aired > 0) parts.add(Ui.datePattern("format|MMM d, yyyy").format(aired));
            parts.add(Ui.tr("Series"));
        }
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(genre(name));
        }
        if (p.kind.equals("movie")) {
            int minutes = o.optInt("runtime");
            if (minutes > 0) parts.add(minutes >= 60 ? Ui.trf("{h}h {m}m", "h", minutes / 60, "m", minutes % 60) : Ui.trf("{m}m", "m", minutes));
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0) parts.add(Ui.plural("plural|one:{n} season|other:{n} seasons", seasons));
        }
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject br = wp == null ? null : wp.optJSONObject("results") == null ? null : wp.optJSONObject("results").optJSONObject(REGION);
        JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
        for (int i = 0; !FILMIX_ONLY && flat != null && i < flat.length() && p.app == null; i++) {
            String[] s = service(flat.getJSONObject(i).optString("provider_name"), flat.getJSONObject(i).optInt("provider_id"));
            if (s != null && installed(s[2])) {
                p.app = s[1];
                p.pkg = s[2];
            }
        }
        if (p.app != null && !FILMIX_ONLY) parts.add(SERVICE_NAME.get(p.app));   // with Filmix, every card is Filmix's
        p.meta = String.join(" · ", parts);
        p.detailed = true;
    }

    /**
     * Without a title in the TV's language TMDB answers with the original one, so a Korean series on a German TV reads
     * "닥터X: 하얀 마피아의 시대". Then the English title, when TMDB has one (one more request, only for those).
     */
    private String title(Pick p, JSONObject o) {
        String field = p.kind.equals("movie") ? "title" : "name";
        String own = o.optString(field, p.title);
        String original = o.optString("original_language");
        String lang = Ui.language.replaceAll("-.*", "");   // TMDB's original_language has no country: "zh", not "zh-CN"
        if (lang.equals("en") || own.isEmpty() || !own.equals(o.optString("original_" + field))
                || lang.equals(original) || "en".equals(original)) return own;
        try {
            String english = get("/" + p.kind + "/" + p.id + "?language=en-US").optString(field);
            return english.isEmpty() ? own : english;
        } catch (Exception e) {
            return own;
        }
    }

    /** TV genres that TMDB returns in English even in other languages (pt-BR, es, de…). */
    private static String genre(String name) {
        switch (name) {
            case "Action & Adventure": return Ui.tr("genre|Action & Adventure");
            case "Sci-Fi & Fantasy": return Ui.tr("genre|Sci-Fi & Fantasy");
            case "War & Politics": return Ui.tr("genre|War & Politics");
            case "Kids": return Ui.tr("genre|Kids");
            case "News": return Ui.tr("genre|News");
            case "Soap": return Ui.tr("genre|Soap");
            case "Talk": return Ui.tr("genre|Talk");
            default: return name;
        }
    }

    /**
     * The title's best backdrop: no text written over it (our own text goes on top of it), the closest to 1920 wide
     * (larger only costs download, disk and decoding) and, on a tie, the highest rated.
     */
    private static String bestBackdrop(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty()) continue;
            boolean textless = b.isNull("iso_639_1");
            int width = b.optInt("width");
            double score = (textless ? 1e7 : 0) + Math.min(width, 1920) * 100 - Math.max(0, width - 1920) + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = b.optString("file_path");
            }
        }
        return best;
    }

    /**
     * The title's logo for its card: in the TV's language, else in English, else one in no language; of those, the best
     * rated. An SVG one by its PNG copy (TMDB serves both).
     */
    private static String bestLogo(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("logos");
        if (list == null) return null;
        for (String want : new String[] {Ui.language.replaceAll("-.*", ""), "en", null}) {
            String best = null;
            double bestScore = -1;
            for (int i = 0; i < list.length(); i++) {
                JSONObject l = list.optJSONObject(i);
                String path = l == null ? "" : l.optString("file_path", "");
                if (path.isEmpty() || !java.util.Objects.equals(l.isNull("iso_639_1") ? null : l.optString("iso_639_1"), want)) continue;
                double score = l.optDouble("vote_average", 0);
                if (score > bestScore) {
                    bestScore = score;
                    best = path;
                }
            }
            if (best != null) return best.endsWith(".svg") ? best.substring(0, best.length() - 4) + ".png" : best;
        }
        return null;
    }

    /** The SERVICES row for a TMDB name: the installed one first, when a service has more than one package. */
    private String[] service(String name, int providerId) {
        String n = name.toLowerCase(Locale.ROOT);
        String[] first = null;
        // "HBO Max Amazon Channel", "Apple TV Amazon Channel"...: a subscription sold inside another app, not this one.
        if (n.contains(" channel")) return null;
        for (String[] s : SERVICES) {
            // Apple TV: only the streaming service (TMDB provider 350); provider 2 is the Apple TV Store (rent or buy).
            if (s[1].equals("appletv") && providerId != 350) continue;
            if (!(s[0].equals("max") ? n.matches(".*\\bmax\\b.*") : n.contains(s[0]))) continue;
            if (installed(s[2])) return s;
            if (first == null) first = s;
        }
        return first;
    }

    /** Services named in hide_services (config.properties), trimmed and lowercased: never a source of picks. */
    private static final Set<String> HIDDEN = new HashSet<>();
    static {
        for (String s : Config.HIDE_SERVICES.split(",")) {
            String key = s.trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty()) HIDDEN.add(key);
        }
    }

    /** The service's app is on the TV, and the service isn't hidden (an app the TV ships without a subscription, say). */
    private boolean installed(String pkg) {
        if (!HIDDEN.isEmpty()) for (String[] s : SERVICES) if (s[2].equals(pkg) && HIDDEN.contains(s[1])) return false;
        return HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
    }

    private long sourceStamp() {
        long stamp = (Config.TMDB_KEY.hashCode() * 31L + REGION.hashCode()) * 31L + (Config.HIDE_ANIMATION ? 1 : 0);
        stamp = stamp * 31L + Config.RECOMMENDATIONS_APP.hashCode();
        stamp = stamp * 31L + Config.TRAKT_CLIENT_ID.hashCode();
        stamp = stamp * 31L + Config.TRAKT_USER.hashCode();
        stamp = stamp * 31L + Trakt.account().hashCode();   // signing in or out of Trakt fetches the list again
        stamp = stamp * 31L + Prefs.recsListRef("movie").hashCode();   // and so does another list for Movies or Shows
        stamp = stamp * 31L + Prefs.recsListRef("tv").hashCode();
        if (FILMIX_ONLY) return stamp * 31L + (installed(Filmix.PKG) ? Filmix.PKG.hashCode() : 0);
        for (String[] service : SERVICES) stamp = stamp * 31L + (installed(service[2]) ? service[2].hashCode() : 0);
        return stamp;
    }

    // MARK: - Watchmode (the title's own page in its app; the free plan's web addresses are what the apps open)

    private static String linkKey(Pick p) {
        return "wm2:" + p.kind + p.id + p.app;   // wm2: links found before the channel check are looked up again
    }

    private boolean needsLink(Pick p) {
        return !FILMIX_ONLY && linksOn() && p.pkg != null && p.link == null
                && cache.getString(linkKey(p), null) == null && System.currentTimeMillis() >= linkRetryAt;
    }

    /** A key is set and Watchmode hasn't refused it (a refused key isn't tried again until it changes). */
    private boolean linksOn() {
        String key = Config.WATCHMODE_KEY.trim();
        return !key.isEmpty() && cache.getInt("wm:refused", 0) != key.hashCode();
    }

    /** Sets p.link from what was looked up before, or looks it up (2 requests of the free plan's 2,500 a month). */
    private void findLink(Pick p) {
        if (!linksOn() || p.pkg == null || p.link != null) return;
        String saved = cache.getString(linkKey(p), null);
        if (saved != null) {
            p.link = saved.isEmpty() ? null : saved;
            return;
        }
        if (System.currentTimeMillis() < linkRetryAt) return;
        try {
            String link = "";
            JSONArray found = new JSONObject(watchmode("/search/?search_field=tmdb_" + p.kind + "_id&search_value=" + p.id))
                    .optJSONArray("title_results");
            if (found != null && found.length() > 0) {
                JSONArray sources = new JSONArray(watchmode("/title/" + found.getJSONObject(0).getInt("id") + "/sources/?regions=" + REGION));
                String[] service = null;
                for (String[] s : SERVICES) if (s[1].equals(p.app)) service = s;
                for (int i = 0; service != null && i < sources.length() && link.isEmpty(); i++) {
                    JSONObject s = sources.getJSONObject(i);
                    String name = s.optString("name").toLowerCase(Locale.ROOT).replace(" ", "");
                    String want = service[0].replace(" ", "");
                    // Not a channel sold inside another app ("AppleTV+ Amazon Channel", "Paramount+ (Via Amazon Prime)"):
                    // that link opens Amazon or Apple's app, not the one the pick is for.
                    boolean resold = name.contains("channel") || name.contains("(via");
                    boolean same = !resold && (want.equals("max") ? name.equals("max") : name.contains(want));
                    if (same && s.optString("type").equals("sub") && s.optString("web_url").startsWith("https://")) link = s.optString("web_url");
                }
            }
            cache.edit().putString(linkKey(p), link).apply();
            p.link = link.isEmpty() ? null : link;
        } catch (Exception e) {
            if ("Watchmode returned HTTP 401".equals(e.getMessage())) {
                cache.edit().putInt("wm:refused", Config.WATCHMODE_KEY.trim().hashCode()).apply();
            }
            linkRetryAt = System.currentTimeMillis() + 10 * 60_000L;   // quota or network: not again right away
            AppLog.w("watchmode: " + e.getMessage());
        }
    }

    private static String watchmode(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.watchmode.com/v1" + path + (path.contains("?") ? "&" : "?")
                + "apiKey=" + URLEncoder.encode(Config.WATCHMODE_KEY.trim(), "UTF-8")).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Watchmode returned HTTP " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private JSONObject get(String path) throws IOException, org.json.JSONException {
        String key = readKey();
        if (key == null) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("TMDB rejected the key");
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private String readKey() {
        String key = Config.TMDB_KEY.trim();
        return key.isEmpty() ? null : key;
    }

    // MARK: - Images (TMDB originals on disk, up to 24 so the whole list fits; this hour's kept ready in memory)

    private void download(Pick p) throws IOException {
        if (p.backdrop == null) return;   // TMDB has no image for it: the card shows without one
        fetch(IMAGES + p.backdrop, imageFile(p), p.searched ? 16 : 24);
    }

    /** Saves `url` as `f` unless it's there already; its folder keeps its newest `keep` files. */
    private static void fetch(String url, File f, int keep) throws IOException {
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile(), keep);
    }

    /** Reads the photo (≥ the UI's width) and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return;
        }
        File f = imageFile(p);
        try {
            download(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight + " → " + Backdrop.W + "×" + Backdrop.H);
            boolean forLight = light;
            Bitmap logo = forLight ? loadLogo(p) : null;   // only the cards show it
            Backdrop backdrop = Backdrop.make(b, forLight, false, logo != null);
            backdrop.logo = logo;
            if (forLight != light) return;   // the design changed midway: this one is stale
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                // The row's cards around the focus and the other tabs' first ones; the least recently drawn goes.
                while (images.size() > (forLight ? 12 : 4)) images.remove(images.keySet().iterator().next());
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("recommendation image: " + e.getMessage());
        }
    }

    /** The title's logo, ready for its card (Backdrop.logo); null without one, or if it can't be had now. */
    private Bitmap loadLogo(Pick p) {
        if (p.logo == null) return null;
        File f = new File(new File(context.getCacheDir(), "recs-logos"), p.logo.replace("/", ""));
        try {
            fetch(LOGOS + p.logo, f, 60);
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b == null) throw new IOException("unreadable logo");
            return Backdrop.logo(b);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("logo \u201c" + p.title + "\u201d: " + e.getMessage());
            return null;
        }
    }

    void trim() {
        // Explore's picks stay: the home screen opens on that tab, so its cards are never gray when you come back.
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Pick p : now(null)) if (p.backdrop != null) keep.add(p.backdrop);
        synchronized (images) {
            images.keySet().retainAll(keep);
        }
    }

    private static void prune(File dir, int keep) {
        // A download cut off when the system stopped the app leaves its .part behind.
        long stale = System.currentTimeMillis() - 10 * 60_000L;
        File[] parts = dir.listFiles((d, name) -> name.endsWith(".part"));
        for (int i = 0; parts != null && i < parts.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            if (parts[i].lastModified() < stale) parts[i].delete();
        }
        File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
        if (files == null || files.length <= keep) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - keep; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - List cache

    private void save() {
        try {
            JSONArray a = new JSONArray(), q = new JSONArray();
            for (Pick p : picks) a.put(json(p));
            synchronized (queue) {
                for (Pick p : queue) q.put(json(p));
            }
            SharedPreferences.Editor e = cache.edit().putString("picks", a.toString()).putString("queue", q.toString())
                    .putInt("period", period).putLong("listAt", listAt)
                    .putString("language", listLanguage)
                    .putString("recommendationsApp", Config.RECOMMENDATIONS_APP)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION);
            JSONObject rows = new JSONObject();
            for (String kind : new String[] {EXPLORE, WATCHLIST}) {
                List<String> keys = rowTitles.get(kind);
                if (keys != null && (kind.equals(WATCHLIST) || !keys.isEmpty())) {
                    rows.put(kind, new JSONObject().put("filter", filters[0].saved()).put("keys", new JSONArray(keys)));
                }
            }
            e.putString("rows", rows.toString());
            e.putString("watch", new JSONObject().put("listed", new JSONArray(watchlist))
                    .put("new", new JSONObject(newEpisodes)).put("at", watchAt).toString());
            // Watchmode links of titles no longer in the list go (the refused-key mark stays).
            Set<String> keep = new HashSet<>();
            for (Pick p : picks) keep.add(linkKey(p));
            for (String k : cache.getAll().keySet()) if ((k.startsWith("wm:") || k.startsWith("wm2:")) && !k.equals("wm:refused") && !keep.contains(k)) e.remove(k);
            e.apply();
        } catch (Exception ignored) {
        }
    }

    private static JSONObject json(Pick p) throws org.json.JSONException {
        return new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title)
                .put("original", p.originalTitle == null ? "" : p.originalTitle).put("year", p.year)
                .put("rating", (double) p.rating).put("backdrop", p.backdrop == null ? "" : p.backdrop)
                .put("logo", p.logo == null ? "" : p.logo)
                .put("link", p.link == null ? "" : p.link).put("meta", p.meta)
                .put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg).put("detailed", p.detailed)
                .put("rec", p.rec).put("nowhere", p.nowhere);
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.originalTitle = o.optString("original").isEmpty() ? null : o.optString("original");
            p.year = o.optInt("year");
            p.rating = (float) o.optDouble("rating", 0);
            p.backdrop = o.optString("backdrop").isEmpty() ? null : o.optString("backdrop");
            p.logo = o.optString("logo").isEmpty() ? null : o.optString("logo");
            p.link = o.optString("link").isEmpty() ? null : o.optString("link");
            p.meta = o.optString("meta");
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.detailed = o.optBoolean("detailed");
            p.rec = o.optBoolean("rec");
            p.nowhere = o.optBoolean("nowhere");
            out.add(p);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
