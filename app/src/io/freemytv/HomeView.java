package io.freemytv;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass:
 *  - at the top left, the search's magnifier (OK: a glass field in the tabs' place, Trakt's answers in the row) and the
 *    tabs (Explore, Movies, Shows, Watchlist); on their line at the top right, icon-only glass buttons
 *    (Live TV, HDMI Inputs, Settings), with the focused one's name below it, and before them the small crypto quotes
 *    (logo + price);
 *  - a row of cards for the selected tab: the titles recommended for right now (Recs, from Trakt or TMDB), or the
 *    Trakt watchlist with the shows that have new episodes (a badge says how many); the background is
 *    the first card's photo, heavily blurred and almost white (Backdrop); without picks, one of your own photos
 *    (Photos) the same way, or else a light gradient;
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, weather for the next hours or days
 *    and quotes with the 24 h change); the background lightens (darkens at night). ↑ or Back closes it;
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener, Wallpaper.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf like the tvOS 27 Dock: icons in each brand's color, 48 apart, and the glass bar hugging them with 40 of
    // padding (the focused icon grows 20% without touching its edge). The icons are 16:9, the shape of the apps' TV
    // banners, so those are never stretched: up to 224 × 126, 178 × 100 with eight apps, 133 × 75 with ten
    // (HomeApps.shelfVisible; past that the shelf scrolls).
    private static final float SHELF_PAD = 40, TILE_GAP = 48;
    private static final float TILE_RADIUS = 22;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.2f;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // Bottom drawer (measured with it open; everything moves up by SHIFT).
    private static final float SHIFT = 560, ROW_TITLE = 518, ROW_TOP = 540, ROW_H = 126, CARDS_TOP = 708, CARDS_H = 300;
    // "All Apps" sheet.
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final int UP_COLOR = 0xFF30D158, DOWN_COLOR = 0xFFFF453A;
    private static final long TOAST_MS = 6000, FADE_MS = 800;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7, MENU = 8, MENU_GLASS = 9, MENU_ITEMS = 10, MENU_SHARP = 11, SKY_IMAGES = 12;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    private static final ExecutorService SHOTS = Executors.newSingleThreadExecutor();   // the menu's copy of the screen
    // Light design (the L_ constants): tab bar, recommendation cards and the shelf.
    // The tabs' focus is a 68 px capsule, on the round buttons' line (as on tvOS, the tab bar at the top), and so are the
    // quotes, ending before the buttons; the cards sit on the 5-column grid (the wide one spans 3), as tall as the room
    // under that line allows (504: the focused one stays 42 px clear of the tabs, a button's name 13 px of the cards).
    private static final float L_TABBAR_TOP = 69, L_TABBAR_H = 68, L_CARDS_TOP = 200, L_CARD_H = 504;
    private static final float L_TALL_W = 320, L_CARD_GAP = 40, L_BUTTON = 68, L_BUTTON_GAP = 20, L_BUTTON_CY = 103;
    private static final float L_PLATTER_TOP = 814, L_TILE_H = 126;
    // The photo of the day's caption: on the buttons' line, ending before them, on a dark chip. Measured on 15 of
    // Bing's photos with the scrims: at least 4.6:1 even over a white sky (without the chip, down to 1.4:1).
    private static final float L_CAPTION_BASE = 110, L_CAPTION_R = 1520, CAPTION_PAD = 16, CAPTION_H = 40;
    private static final int CAPTION_INK = 0xEBFFFFFF, CAPTION_CHIP = 0x80000000;
    private static final float L_DATE_BASE = 330, L_CLOCK_BASE = 540, L_WEATHER_BASE = 626, L_QUOTES_BASE = 694;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static String[] tabs;
    private static int tabsGeneration = -1;
    private static final String[] TAB_KIND = {null, "movie", "tv", Recs.WATCHLIST};
    private static final int TABS = TAB_KIND.length;
    private static final int Z_TOP = 4, Z_CARDS = 5, Z_SEARCH = 6;

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final Recs recs;
    private final NowPlaying.Watch playing;
    private final Wallpaper wallpaper;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { activate(); }
        @Override public boolean clickable(int id) { return (id - 1) / 1000 != WIDGETS; }
        @Override public boolean holdable(int id) { return (id - 1) / 1000 == GRID && canToggleShelf((id - 1) % 1000); }
        @Override public void hold(int id) { a11yHold(); }
    });
    private final RectF a11yRect = new RectF();
    private final Layer sky, skyImages, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, menuSharpLayer, menuLayer, menuGlass,
            menuItemsLayer, toastLayer;
    private int zone = Z_SHELF;

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to TABS - 1) and icon-only buttons (TABS on); the row's cards
    private int tab, topIndex, cardIndex;
    // The tab whose row shows. Switching tabs, the row fades out (rowOut) drifting away from the side the focus went to
    // (rowDir), and the new tab's fades in (rowIn) from that side: quick, and only alpha and a shift (no layer).
    private int rowTab, rowDir;
    private final Spring rowOut = new Spring(0.16f, 1, 1), rowIn = new Spring(0.3f, 1, 1);
    private float rowA = 1;     // the row's alpha this frame
    private int widgetIndex;    // with TalkBack on, the drawer's weather (0) or quotes (1) card can take the focus
    private List<Recs.Pick> cards = new ArrayList<>();
    // The tabs, then the round buttons at the top right (up to HomeApps.MAX_TOP).
    private final Spring[] topFocus = new Spring[TABS + HomeApps.MAX_TOP];
    // The tabs' focus pill: its left edge and width, gliding from tab to tab (as the filters' focus does).
    private final Spring tabPillX = new Spring(0.3f, 0.8f, 0), tabPillW = new Spring(0.3f, 0.8f, 0);
    private final RectF[] topRects = new RectF[TABS + HomeApps.MAX_TOP];
    // Search (Trakt's), from the magnifier before the tabs (zone Z_SEARCH: the magnifier, or the field once open). OK
    // on it turns the tabs into a glass field and brings up the TV's keyboard; a moment after the typing stops the row
    // shows Trakt's answers, each as it's looked up (Recs.search). ↓ goes to the answers, ↑ or Back from them to the
    // field, Back from the field to the tabs; the text stays until Home. The keyboard types into an invisible EditText:
    // the field is drawn here, with the tabs.
    private static final long SEARCH_AFTER_MS = 400, CARET_MS = 530, KEYBOARD_AFTER_MS = 320;
    // The magnifier at 30 pt, the round buttons' icons at 27: an outline drawn smaller (136 px against the gear's 150), it
    // reads as large and as heavy as the filled gear only that much larger.
    private static final float SEARCH_FIELD_W = 1040, SEARCH_ICON = 30;
    private final RectF searchRect = new RectF(), fieldRect = new RectF();
    private final Spring searchFocus = new Spring(0.26f, 0.85f, 0), searchMorph = new Spring(0.34f, 0.86f, 0);
    private boolean searchOpen, caretOn;
    private final EditText field;
    private String query = "", queryLine = "", searchEmpty;
    private float queryLineW;
    private Paint lSearch, lSearchHint;
    private final Runnable searchNow = this::searchQuery;
    private final Runnable keyboardSoon = () -> {
        if (this.running && this.searchOpen && this.zone == Z_SEARCH && !this.onMic) showKeyboard();
    };
    // Voice, where the TV has a voice input (Google's on Google TV): the microphone at the field's right end (→ from the
    // text), or the remote's search key with the field open. What's said becomes the text, searched at once.
    private static final String GOOGLE_VOICE = "com.google.android.katniss";
    private final Intent voice;
    private boolean onMic;      // the field's focus is on its microphone, not on the text
    private final Spring micFocus = new Spring(0.26f, 0.85f, 0);
    private final Runnable caretBlink = new Runnable() {
        @Override public void run() {
            boolean on = running && searchOpen && zone == Z_SEARCH && !onMic;
            caretOn = on && !caretOn;
            heroDirty = true;
            wake();
            if (on) handler.postDelayed(this, CARET_MS);
        }
    };
    // The row of picks, as on Apple TV: the focused card is wide and the others tall, each width a spring, and the row
    // scrolls only when the wide card wouldn't fit. The focus on the last card asks Recs for more; empty cards (slots) hold
    // their places, softly pulsing, until their titles are confirmed. The focused card stretches past its size once and
    // comes back softly (a spring with its own `back`), a little thinner meanwhile. The next card stays put until only
    // their final gap is left between them; from there it moves with the stretch and comes back with it, squeezing the
    // gap after it.
    private final List<Spring> cardFocus = new ArrayList<>(), cardWide = new ArrayList<>();
    // The focused card's light in its photo's colors (drawAmbient): slower than its lift and without a bounce, so it
    // blooms and fades softly.
    private final List<Spring> cardGlow = new ArrayList<>();
    private static final float CARD_SQUASH = 0.25f;   // height lost per width stretched past its size: ~1% at a move
    private final Spring rowScroll = new Spring(0.42f, 0.92f, 0), rowNudge = new Spring(0.24f, 0.42f, 0);
    private RectF[] cardRects = new RectF[0];   // each slot at rest (the focused one wide): moving up and down, TalkBack
    private CardText[] cardWords = new CardText[0];
    private int slots;          // the cards, plus the empty ones of a page still loading
    private boolean rowReset;   // the next update puts the row at rest at once (another tab)
    private float tallW, wideW, cardGap;
    private float[] cardX = new float[0], cardW = new float[0];   // where each slot is drawn this frame
    private long[] imageAt = new long[0];     // when each card's photo arrived (0 = not yet): it fades in
    private long fadingUntil;
    private static final long IMAGE_FADE_MS = 260, BG_FADE_MS = 600;
    private final Path star = new Path();   // the rating's star, 1 unit across, drawn scaled
    private final Runnable focusBackground = this::backgroundFromFocus;
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private Paint lTab, lTabOn, lCardMeta, lQuote, lTip, lDate, lClock, lWeather, lClockQuote;
    private TextPaint lCaption;
    private Shader lightGradient;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private final Rect layerClip = new Rect();
    private int shelfIndex;
    /** The first app the shelf shows: with more apps than fit, it scrolls with the focus. */
    private int shelfFirst;
    private final int shelfVisible;

    // Moving apps on the shelf (as on Apple TV): hold OK on a tile, then ← → move it, OK drops it, Back cancels. The
    // lifted tile wiggles; the others slide to their new places (each slot's x offset is a spring that returns to 0).
    private static final long HOLD_MS = 500, WIGGLE_MS = 300;
    private static final float LIFT_GROW = 0.08f, WIGGLE_DEG = 1.5f;
    private boolean reordering, reorderAnim, okHeld, holdArmed;
    private int liftIndex = -1;
    private long reorderAt;
    private float wiggle;
    private Spring[] tileSlide = new Spring[0];
    private final Spring moveLift = new Spring(0.3f, 0.72f, 0);
    private List<HomeApps.Item> reorderOrigin;
    private final Runnable holdStart = () -> { if (this.okDown && this.holdArmed) hold(); };

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background
    private List<Recs.Pick> picks = new ArrayList<>();
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private int bgFrames;
    private final Spring heroFocus = new Spring(0.28f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF(), quotesCard = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen, heroOnDock = true;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen, shelfEdited;
    private int gridIndex;
    private long toggledAt;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean idle;
    private long lastNanos, resumedAt, lastStep;
    private static final long BURST_MS = 1500;
    private int w, h;
    private float k = 1;
    private String clockText = "", dateText = "", weatherLine = "";
    /** "Off in 42 min" next to the round buttons while the sleep timer runs (Settings > Sleep Timer); "" otherwise. */
    private String sleepText = "", sleepMeasured = "";
    private float sleepWidth;
    private long weatherLineAt = -1;
    private int weatherLinePrefs = -1;
    private String weatherLineLanguage = "";
    private String captionLine = "", captionFrom;   // the photo of the day's caption, shortened to fit (and its source)
    private float captionW;
    private long clockMinute = -1;
    private boolean okDown, backDown;

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint, quotePaint, staleQuote;
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp, dayLow;
    private Paint pricePaint, changePaint, sheetTitle;
    private TextPaint titlePaint, gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final RectF skyRect = new RectF(0, 0, Backdrop.GW, Backdrop.GH);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint logoPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();
    private final TextPaint fitPaint = new TextPaint();
    private final RectF gridCell = new RectF();
    private LinearGradient[] tileShaders = new LinearGradient[0];
    private LinearGradient tileSheen, cardSheen;
    private float tileShaderH, cardSheenH;
    // The dock icons' glass (drawTileGlass): the rim's light by angle and the glare (tileSheen), made again only when
    // the icons' size changes.
    private final Paint tileRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private android.graphics.SweepGradient tileRimShader;
    private float tileRimW, tileRimH;
    private LinearGradient rowSheen;
    private android.graphics.SweepGradient rowRim;
    private final Paint ambientPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);   // drawAmbient
    private final RectF logoRect = new RectF();
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private android.graphics.ColorFilter veilFilter;
    private float veilAmount = -1;
    private int veilColor;
    private HomeData.Weather textWeather;
    private long textPricesAt = -1, textMinute = -1;
    private int textPrefs = -1;
    private boolean clock24;
    private String textLanguage;
    private String weatherTitle, nowTemp, highLow, sunriseText, sunsetText, staleText;
    private final String[] hourText = new String[6], hourTempText = new String[6];
    private final String[] dayText = new String[5], dayMaxText = new String[5], dayMinText = new String[5];
    private boolean textDays;   // the weather card shows the next days (Home Settings → Forecast) and has them
    private final String[] priceText = new String[HomeData.COINS.length], changeText = new String[HomeData.COINS.length];

    /** A card's words, cut to fit once (never while drawing): the wide card's details and the new episodes' badge. */
    private static final class CardText {
        String key = "", meta = "", rating = "", badge = "";
        int generation = -1;
        float width, ratingW;   // ratingW: the star, the number and the space after them (0 without a rating)
        float badgeW;           // the new episodes' words, at most a tall card's room
    }

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout;

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            shadows.dither = dark() > 0.5f;
            switch (kind) {
                case SKY: drawSky(c); break;
                case SKY_IMAGES: drawSkyImages(c); break;
                case HERO: drawLightTop(c); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                case MENU_SHARP: if (menuSharp != null) c.drawBitmap(menuSharp, null, screen, null); break;
                case MENU: drawMenuBack(c); break;
                case MENU_GLASS: drawMenuGlass(c); break;
                case MENU_ITEMS: drawMenuItems(c); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final Runnable reloadSoon = this::reload;
    private static final long APPS_MAX_AGE_MS = 15 * 60_000;
    private final LauncherApps launcherApps;
    private boolean appsStale = true;
    private int appsVersion, appsGeneration;
    private long appsAt;

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reloadSoon(); }
        @Override public void onInputAdded(String inputId) { reloadSoon(); }
        @Override public void onInputRemoved(String inputId) { reloadSoon(); }
    };

    private void reloadSoon() {
        handler.removeCallbacks(reloadSoon);
        handler.postDelayed(reloadSoon, 500);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            wake();
            playing.start();   // the listener allowed after the screen showed (a no-op once watching)
            data.refreshIfStale();
            recs.refreshIfStale();
            checkNotices();
            updatePalette();
            checkNight(true);
            updatePicks();      // three new ones every hour
            if (photoTheme()) wallpaper.refreshIfStale();
            updateSleep();
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        playing = new NowPlaying.Watch(a, () -> {
            fitPlaying();
            heroDirty = true;
            wake();
            bounceBars();
        });
        wallpaper = Wallpaper.get(a);
        click = new Click(a);
        shelfVisible = HomeApps.shelfVisible(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        launcherApps = (LauncherApps) a.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        if (launcherApps != null) launcherApps.registerCallback(packages, handler);
        setFocusable(true);
        sky = new Layer(a, SKY);
        skyImages = new Layer(a, SKY_IMAGES);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        menuSharpLayer = new Layer(a, MENU_SHARP);
        menuLayer = new Layer(a, MENU);
        menuGlass = new Layer(a, MENU_GLASS);
        menuItemsLayer = new Layer(a, MENU_ITEMS);
        toastLayer = new Layer(a, TOAST);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 8 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms). The tabs and the row keep theirs only part of the time (cacheHero).
        for (View v : new View[] {skyImages, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, menuSharpLayer, menuLayer, menuGlass, menuItemsLayer}) v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        for (View v : new View[] {sky, skyImages, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, menuSharpLayer, menuLayer,
                menuGlass, menuItemsLayer, toastLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        setContentDescription(Ui.NAME);
        field = searchField(a);
        addView(field);
        voice = voiceInput(a);
        drawerLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < topFocus.length; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        setLightButtons(HomeApps.topButtons());
        recs.setLight(true);
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        for (View v : new View[] {menuSharpLayer, menuLayer, menuGlass, menuItemsLayer}) v.setVisibility(INVISIBLE);
        for (int i = 0; i < menuFocus.length; i++) menuFocus[i] = new Spring(0.26f, 0.85f, 0);
        for (int i = 0; i < starFocus.length; i++) starFocus[i] = new Spring(0.26f, 0.8f, 0);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setStatusItems(statusList(a));
        setDrawerItems(drawerList(a));
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        traktIn = Recs.canFilter();
        checkNight(false);
        boolean h24 = Prefs.clock24();
        if (h24 != clock24) {   // 12/24 hours changed in the TV's settings: the clock and the drawer's times again
            clock24 = h24;
            clockMinute = -1;
            textPrefs = -1;
        }
        long now = SystemClock.uptimeMillis();
        // Some remotes (Xiaomi's) send one Home press as a burst of 5 to 8, and each one pauses and resumes the home
        // screen: those after the first change nothing, so the lists are read only once.
        boolean again = resumedAt != 0 && now - resumedAt < BURST_MS;
        resumedAt = now;
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        wallpaper.setListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        recs.fill();
        onWallpaper();   // the theme or your photos may have changed in Home Settings
        Photos.importInbox(getContext(), ownReady);   // photos a computer just sent
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        playing.start();
        checkNotices();
        if (!again) reload();
        updatePalette();
        updatePicks();
        updateSleep();      // a timer just set in Settings shows at once, not at the next tick
        restartCaret();     // back from a title opened from the search: the field may have the focus
        if (allAppsOnResume) {
            allAppsOnResume = false;
            reset(false);
            openSheet();
        } else if (sheetOpen) {
            closeSheet();
        }
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
    }

    void pause() {
        if (!running) return;
        running = false;
        cancelReorder(true);
        closePickMenu(true);
        handler.removeCallbacks(holdStart);
        handler.removeCallbacks(caretBlink);
        handler.removeCallbacks(keyboardSoon);
        okHeld = holdArmed = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(reloadSoon);
        handler.removeCallbacks(bars);
        handler.removeCallbacks(focusBackground);
        playing.stop();
        data.removeListener(this);
        recs.removeListener(this);
        wallpaper.setListener(null);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        pause();
        if (launcherApps != null) launcherApps.unregisterCallback(packages);
        click.release();
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        wake();
        cancelReorder(!animated);
        closePickMenu(!animated);
        closeSearch(!animated);
        if (field.length() > 0) field.setText("");   // the next search starts afresh
        zone = Z_SHELF;
        shelfIndex = 0;
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
        }
        if (pickIndex != 0 && !picks.isEmpty()) {
            pickIndex = 0;
            updatePicks();
        }
        if (tab != 0) selectTab(0);
        if (rowTab != tab) swapRow(false);
        cardIndex = 0;      // Home: the row slides back to its first card, and the background follows it
        handler.removeCallbacks(focusBackground);
        handler.post(focusBackground);
        retarget(!animated);
        motionDirty = true;
    }

    /** The round buttons at the top right (Settings > Top Buttons), with their names in the current language. */
    private void setLightButtons(List<HomeApps.Item> list) {
        lightButtons.clear();
        lightButtons.addAll(list);
        if (zone == Z_TOP && topIndex >= TABS + lightButtons.size()) {
            topIndex = TABS - 1 + lightButtons.size();
            if (topIndex < TABS && !picksOn()) zone = Z_SHELF;   // without the picks the tabs can't hold focus
            else if (topIndex < TABS) selectTab(topIndex);
            retarget(true);
        }
        layoutLight();
        statusDirty = true;
    }

    /** The tab names, made again only when the language changes (they are drawn on every frame). */
    private static String[] tabs() {
        if (tabsGeneration != Ui.generation) {
            tabs = new String[] {Ui.tr("Explore"), Ui.tr("Movies"), Ui.tr("Shows"), Ui.tr("tab|Watchlist")};
            tabsGeneration = Ui.generation;
        }
        return tabs;
    }

    @Override public void onData() {
        wake();
        traktIn = Recs.canFilter();
        updatePalette();
        checkNight(true);
        layoutStatus();
        heroDirty = statusDirty = drawerDirty = true;
        platter.invalidate();
    }

    /**
     * The photo of the day arrived, or its theme (or your photos) changed: fetch it when wanted, let it go when not (it
     * holds a full-screen texture, a lot on a 1 GB TV), and redraw the background and the glass.
     */
    @Override public void onWallpaper() {
        if (photoTheme()) wallpaper.refreshIfStale();
        else wallpaper.release();
        photoShown = photoTheme() ? wallpaper.backdrop() : null;
        if (bgTo == null && bgFadeAt == 0) neutralGlass();
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        invalidateAll();
    }

    /** The "Photo of the day" theme, where it applies: no picks, and your own photos off (no Bing request then). */
    private boolean photoTheme() {
        return Prefs.theme().equals("photo") && !picksOn() && !Photos.on(getContext());
    }

    /**
     * The photo of the day behind the clock, once downloaded; null otherwise. Kept by onWallpaper (on resume, when the
     * photo arrives, when your photos change): dark() asks for it inside every tone(), many times a frame.
     */
    private Backdrop photoShown;

    private Backdrop photoBg() {
        return photoShown;
    }

    @Override public void onRecs() {
        wake();
        updatePicks();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, skyImages, heroLayer, status, platter, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(skyImages, Backdrop.GW, Backdrop.GH);
        // Down to where the focused card's light ends (drawAmbient, ~832): shorter, it was cut there in a straight line.
        measure(heroLayer, width, Math.round(850 * kk));
        measure(status, Math.round(1160 * kk), Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        measure(platter, width, Math.round(320 * kk));
        measure(tiles, width, Math.round(270 * kk));
        measure(drawerLayer, width, Math.round(600 * kk));
        measure(sheetLayer, width, height);
        measure(menuSharpLayer, width, height);
        measure(menuLayer, width, height);
        for (View v : new View[] {menuGlass, menuItemsLayer}) measure(v, Math.max(1, menuBounds.width()), Math.max(1, menuBounds.height()));
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk));
        measure(field, Math.round(SEARCH_FIELD_W * kk), Math.round(L_TABBAR_H * kk));
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        skyImages.layout(0, 0, Backdrop.GW, Backdrop.GH);
        skyImages.setPivotX(0);
        skyImages.setPivotY(0);
        skyImages.setScaleX(width / (float) Backdrop.GW);
        skyImages.setScaleY(height / (float) Backdrop.GH);
        place(heroLayer, 0, 0);
        place(status, width - status.getMeasuredWidth(), 0);
        place(platter, 0, Math.round(750 * kk));
        place(tiles, 0, Math.round(790 * kk));
        place(drawerLayer, 0, Math.round(470 * kk));
        sheetLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        menuSharpLayer.layout(0, 0, width, height);
        menuLayer.layout(0, 0, width, height);
        for (View v : new View[] {menuGlass, menuItemsLayer}) v.layout(menuBounds.left, menuBounds.top, menuBounds.right, menuBounds.bottom);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
        place(field, Math.round((MARGIN - 26) * kk), Math.round(L_TABBAR_TOP * kk));   // where the field is drawn
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        makePaints();
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        Symbols.warm(getContext(), "bell", 26 * k);
        Symbols.warm(getContext(), "bedtime", 21 * k);   // the sleep timer's "Off in", at lQuote's size
        for (String s : new String[] {"play", "check", "hide", "bookmark_add", "bookmark_remove", "chevron_left", "chevron_right"}) {
            Symbols.warm(getContext(), s, 30 * k);   // a pick's menu, the filters
        }
        Symbols.warm(getContext(), "search", SEARCH_ICON * k);   // the magnifier, at rest and focused
        Symbols.warm(getContext(), "search", SEARCH_ICON * k * 1.1f);
        Symbols.warm(getContext(), "chevron_down", 40 * k);   // over Explore's tab, upside down: ↑ opens its filters
        Symbols.warm(getContext(), "mic", 26 * k);   // the field's microphone, at rest and focused
        Symbols.warm(getContext(), "mic", 26 * k * 1.08f);
        layoutShelf();
        layoutStatus();
        layoutDrawer();
        layoutLight();
        hero = null;
        heroFrom = null;
        updatePicks();
        motionDirty = true;
        wake();
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint, clockPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        lTab = Ui.text(Ui.medium, 30 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 30 * k, L_INK);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        starPaint.setPathEffect(new android.graphics.CornerPathEffect(0.08f));   // rounded points, like SF Symbols' star
        starLine.setStyle(Paint.Style.STROKE);
        starLine.setStrokeWidth(0.07f);         // in the star's own units (it's drawn 1 unit across, scaled)
        starLine.setStrokeJoin(Paint.Join.ROUND);
        starLine.setPathEffect(new android.graphics.CornerPathEffect(0.08f));
        menuText = Ui.text(Ui.medium, 30 * k, L_INK);
        menuHint = Ui.text(Ui.medium, 26 * k, L_INK);
        menuTitle = Ui.text(Ui.semibold, 34 * k, L_INK);
        menuValue = Ui.text(Ui.semibold, 30 * k, L_INK);
        aboutTitle = new TextPaint(Ui.text(Ui.semibold, 33 * k, L_INK));
        aboutText = new TextPaint(Ui.text(Ui.regular, 25 * k, L_INK));
        ageText = Ui.text(Ui.semibold, 18 * k, L_INK);
        ageText.setLetterSpacing(0.04f);
        ageRim.setStyle(Paint.Style.STROKE);
        if (filterPanel == null) {
            filterPanel = new FilterPanel(getContext(), new FilterPanel.Host() {
                @Override public void dirty() {
                    menuDirty = true;
                    wake();
                }
                @Override public void moved() { click.move(); }
                @Override public void picked() { click.open(); }
                @Override public void announce(String text) { announceForAccessibility(text); }
                @Override public void close() { closePickMenu(false); }
            }, shadows);
        }
        filterPanel.setScale(k);
        lEmpty = Ui.text(Ui.medium, 30 * k, L_DIM);
        lEmpty.setTextAlign(Paint.Align.CENTER);
        lBadge = Ui.text(Ui.semibold, 21 * k, L_INK);
        lSearch = Ui.text(Ui.medium, 30 * k, L_INK);
        lSearchHint = Ui.text(Ui.medium, 30 * k, L_DIM);
        lQuote = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lDate = Ui.text(Ui.semibold, 34 * k, L_DIM);
        lClock = Ui.numbers(Ui.light, 200 * k, L_INK);
        lClock.setLetterSpacing(-0.01f);
        lWeather = Ui.text(Ui.medium, 34 * k, L_DIM);
        lClockQuote = Ui.numbers(Ui.medium, 28 * k, L_DIM);
        lCaption = new TextPaint(Ui.text(Ui.medium, 22 * k, CAPTION_INK));
        captionFrom = null;
        lightGradient = null;
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setShadowLayer(16 * k, 0, 1 * k, 0x99000000);
        // Quotes: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF);
        quotePaint.setShadowLayer(14 * k, 0, 1 * k, 0x99000000);
        staleQuote = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        staleQuote.setTextAlign(Paint.Align.RIGHT);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        dayLow = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        dayLow.setTextAlign(Paint.Align.CENTER);
        pricePaint = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        pricePaint.setTextAlign(Paint.Align.RIGHT);
        changePaint = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF);
        changePaint.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
        fitPlaying();
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean tilesMoving = step(tileFocus, dt);
        if (reorderAnim) tilesMoving |= stepReorder(dt, up);
        boolean statusMoving = step(statusFocus, dt);
        boolean heroMoving = heroFocus.step(dt);
        boolean topMoving = step(topFocus, dt) | tabPillX.step(dt) | tabPillW.step(dt);
        // The menu has faded: a title hidden from it leaves the row, and the row slides back.
        if (!menuOpen && menuWarm == 0 && menu.value <= 0.002f) {
            if (hideAfter != null) recs.hide(hideAfter);
            if (unlistAfter != null) recs.setWatchlisted(unlistAfter, false);
            hideAfter = unlistAfter = null;
            menuShift.target = 0;
        }
        // The row: lift, widths, scroll, its shift for the menu and the bump at its end; empty cards pulse while their
        // titles load.
        float shiftShown = menuShift.value;
        boolean cardsMoving = step(cardFocus, dt) | step(cardGlow, dt) | step(cardWide, dt) | rowScroll.step(dt) | rowNudge.step(dt)
                | menuShift.step(dt) | rowOut.step(dt) | rowIn.step(dt) | slots > cards.size() | up < fadingUntil;
        if (rowTab != tab && rowOut.value < 0.04f) {
            swapRow(true);   // the last tab's row has faded: the selected one's comes in
            cardsMoving = true;
        }
        if (menuWaitsRow && Math.abs(shiftShown - menuShift.target) < 3 * k) {
            // A few px short of its place (a critically damped spring takes another ~0.1 s to cover them), the row stops
            // where the frame on screen has it, and that frame is copied for the menu.
            menuWaitsRow = false;
            menuShift.snap(shiftShown);
            placeMenu();
            captureBehind(++menuShotId);
        }
        if (topMoving) status.invalidate();
        boolean searchMoving = searchFocus.step(dt) | searchMorph.step(dt) | micFocus.step(dt);
        heroMoving |= topMoving | cardsMoving | searchMoving;
        boolean drawerItemsMoving = step(drawerFocus, dt);
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        // The menu opens and closes by fading and scaling its cached layers (redrawing the glass on every frame costs
        // this TV ~50 ms a frame); only the items are redrawn, while their focus moves. Meanwhile a still copy of the
        // screen stands in for the home screen's layers, which aren't drawn: composing them all under the fading menu
        // cost ~50 ms of GPU a frame, the copy and the menu alone ~17.
        boolean itemsMoving = step(menuFocus, dt) | step(starFocus, dt) | (filterPanel != null && filterPanel.step(dt));
        boolean menuMoving = menu.step(dt) | itemsMoving;
        if (itemsMoving || menuDirty) menuItemsLayer.invalidate();
        if (menuBackDirty) {
            menuSharpLayer.invalidate();
            menuLayer.invalidate();
            menuGlass.invalidate();
        }
        menuDirty = menuBackDirty = false;
        // First a warm-up frame: the copy alone shows (it looks just like the screen, so a long frame goes unseen) while
        // the menu's layers are drawn almost transparent, so the GPU has them ready; the fade starts on the next frame.
        if (menuWarm > 0 && --menuWarm == 0) menu.target = 1;
        float ma = Ui.clamp(menu.value), msc = 0.9f + 0.1f * ma;
        boolean menuShown = menuWarm > 0 || ma > 0.002f, covers = menuShown && menuSharp != null;
        boolean opaque = menuOpen && ma >= 0.999f && menuShot != null;   // the blurred copy hides the sharp one too
        if (menuWarm > 0) ma = 0.01f;
        if (covers != menuCovers) {
            menuCovers = covers;
            motionDirty = true;
        }
        for (View v : new View[] {menuLayer, menuGlass, menuItemsLayer}) {
            v.setAlpha(ma);
            if (menuShown != (v.getVisibility() == VISIBLE)) v.setVisibility(menuShown ? VISIBLE : INVISIBLE);
        }
        for (View v : new View[] {menuGlass, menuItemsLayer}) {
            v.setScaleX(msc);
            v.setScaleY(msc);
        }
        if (covers != (menuSharpLayer.getVisibility() == VISIBLE)) menuSharpLayer.setVisibility(covers ? VISIBLE : INVISIBLE);
        menuSharpLayer.setAlpha(opaque ? 0 : 1);   // transparent, not invisible: kept ready for the fade out
        if (!menuShown && !menuOpen) menuShot = menuSharp = null;
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        // The shelf's and the buttons' glass is clear: the background fades under it. The open drawer's glass shows a copy
        // of the background, so it follows on every fourth frame (it changes slowly; the last frame of the fade too):
        // redrawing the glass during the fade cost this TV 57 frames a second instead of 59.
        boolean drawerGlass = bgFading && (bgFadeAt == 0 || ++bgFrames % 4 == 0);
        boolean nightMoving = tickNight(up);

        if (drawerMoving || sheetMoving || motionDirty) updateMotion();
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the shelf's glass is clear (nothing to redraw as it moves).
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusMoving || statusDirty) status.invalidate();
        boolean heroRedrawn = heroMoving || heroDirty || heroFading;
        if (heroRedrawn) heroLayer.invalidate();
        cacheHero(heroRedrawn);
        if (drawerItemsMoving || drawerDirty || drawerGlass) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if (drawerMoving || bgFading) {
            sky.invalidate();
            skyImages.invalidate();
        }
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = false;
        if (updateClock()) {
            layoutStatus();
            status.invalidate();
            if (hero != null && hero.clock) {
                refreshHero(false);
                heroLayer.invalidate();
            }
            if (!picksOn()) heroLayer.invalidate();
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving
                || sheetMoving || menuMoving || menuWarm > 0 || toastFading || heroFading || bgFading || nightMoving || sheetOpen || sheet.value > 0.001f
                || drawerOpen || drawer.value > 0.001f || toast != null || !toasts.isEmpty();
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else Choreographer.getInstance().postFrameCallbackDelayed(this, 1000);
    }

    private void wake() {
        if (!running || !idle) return;
        idle = false;
        lastNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    private static boolean step(List<Spring> springs, float dt) {
        boolean moving = false;
        for (int i = 0; i < springs.size(); i++) moving |= springs.get(i).step(dt);   // no iterator: runs every frame
        return moving;
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = Ui.clamp(sheet.value);
        float home = 1 - s, top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = SHIFT * k;
        float lift = -d * shift;
        show(heroLayer, top, lift);
        show(status, top, lift);
        show(platter, home, lift);
        show(tiles, home, lift);
        // Under the menu's opaque blurred copy nothing needs drawing: transparent, not invisible, so the GPU keeps their
        // layers and the screen comes back without redrawing them.
        sky.setAlpha(menuCovers ? 0 : 1);
        skyImages.setAlpha(menuCovers ? 0 : 1);
        if (menuCovers) for (View v : new View[] {heroLayer, status, platter, tiles}) v.setAlpha(0);
        float da = home * smoothstep(0.1f, 0.6f, dc);
        drawerLayer.setTranslationY((1 - d) * shift);
        drawerLayer.setAlpha(da);
        drawerLayer.setVisibility(da > 0.002f ? VISIBLE : INVISIBLE);
        sheetLayer.setVisibility(s > 0.001f || sheetOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(s);
        float scale = 1.04f - 0.04f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    /**
     * The tabs and the row in a layer (cached pixels) only where that pays: while the drawer or the sheet moves and fades
     * them, and while the focus rests on the dock, where those open. Elsewhere the row redraws on every frame its focus
     * moves, and a layer redrawn on every frame cost this TV's GPU a pass of its own on top (the arrow held: 56 → 58
     * frames a second, late frames 5% → 3%): there they are drawn straight to the screen.
     */
    private void cacheHero(boolean redrawn) {
        if (Ui.LEGACY) return;
        if (zone != Z_SHELF) heroOnDock = false;
        else if (!redrawn) heroOnDock = true;   // once the row is still (a card's focus fading as it left), until it leaves
        boolean layer = heroOnDock || drawerOpen || sheetOpen || drawer.value > 0.001f || sheet.value > 0.001f;
        int type = layer ? LAYER_TYPE_HARDWARE : LAYER_TYPE_NONE;
        if (heroLayer.getLayerType() != type) heroLayer.setLayerType(type, null);
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private void updateSleep() {
        boolean here = Prefs.sleepShown() && !android.provider.Settings.canDrawOverlays(getContext());   // else SleepOverlay
        int left = here ? SleepTimer.minutesLeft(getContext()) : 0;
        String text = left == 0 ? "" : Ui.trf("Off in {time}", "time", SleepTimer.duration(left));
        if (text.equals(sleepText)) return;
        sleepText = text;
        statusDirty = heroDirty = true;   // the quotes end before it
        clipLayers();
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        dateText = Ui.date(now);
        return true;
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    private Recs.Pick currentPick() {
        return picks.isEmpty() ? null : picks.get(Math.min(pickIndex, picks.size() - 1));
    }

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    /** Where the one card missing from `now` was in `before` (the rest in the same order, maybe more after); else -1. */
    private static int goneAt(List<Recs.Pick> before, List<Recs.Pick> now) {
        int gone = -1;
        for (int i = 0, j = 0; i < before.size(); i++) {
            if (j < now.size() && Recs.key(before.get(i)).equals(Recs.key(now.get(j)))) {
                j++;
            } else if (gone < 0) {
                gone = i;
            } else {
                return -1;
            }
        }
        return gone;
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (hero.clock != next.clock || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        boolean layoutChanged = hero == null || hero.clock != next.clock;
        hero = next;
        heroDirty = true;
        if (layoutChanged) layoutStatus();
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.locale());
            return h;
        }
        h.label = Recs.periodLabel();
        h.title = p.title == null ? "" : p.title;
        h.meta = p.meta == null ? "" : p.meta;
        h.button = p.pkg != null;
        h.dots = picks.size();
        h.dot = pickIndex;
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private boolean browse(int dir) {
        if (picks.size() < 2) return false;
        int next = pickIndex;
        for (int i = 0; i < picks.size(); i++) {
            next = (next + dir + picks.size()) % picks.size();
            if (zone != Z_HERO || picks.get(next).pkg != null) break;
        }
        if (next == pickIndex) return false;
        pickIndex = next;
        heroSlide = dir;
        refreshHero(true);
        Backdrop b = recs.whole(currentPick(), true);
        if (b != null) showBackdrop(b);
        spoken.focusMoved();
        return true;
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        wake();     // idle, the frame loop runs once a second: the fade would be over before its first frame
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up, BG_FADE_MS) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    /** No photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on screen). */
    private void neutralGlass() {
        Backdrop photo = photoBg();
        if (photo != null && scene.shaded()) {
            // The photo of the day, dimmed at night: day and night glass blended by the GPU, as for the picks.
            scene.setFrost(night < 0.5f ? photo.frost : photo.nightFrost);
            if (nightAt != 0) {
                if (!nightPair) {
                    scene.setGlassImages(photo.glass, photo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(night < 0.5f ? photo.glass : photo.nightGlass);
                nightPair = false;
            }
            return;
        }
        if (!scene.shaded()) {   // Android 8 to 12: a gray image would be one more full-area pass for the flat look
            scene.clearFrost();
            scene.clearGlass();
            nightPair = false;
            return;
        }
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = data.isNight(System.currentTimeMillis());
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        skyImages.invalidate();
        platter.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        if (photoBg() != null) return 1;     // white text over the photo of the day, by day too
        return night;
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        lEmpty.setColor(tone(L_DIM, 0xFF8E8E93));
        lTabOn.setColor(tone(L_INK, WHITE));
        // The details under a card: white at night, over the focused card's light (gray, or translucent, looked dull).
        lCardMeta.setColor(tone(L_INK, 0xFFFFFFFF));
        lQuote.setColor(tone(L_FAINT, 0xB8FFFFFF));
        lTip.setColor(tone(L_INK, WHITE));
        lDate.setColor(tone(L_DIM, 0xFF98989D));
        lClock.setColor(tone(L_INK, WHITE));
        lWeather.setColor(tone(L_DIM, 0xFF98989D));
        lClockQuote.setColor(tone(L_DIM, 0xFF98989D));
        staleQuote.setColor(tone(L_FAINT, 0x99FFFFFF));
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up, BG_FADE_MS);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            if (drawerLayer.getVisibility() == VISIBLE || toast != null) {
                int[] from = frostOf(bgFrom), to = frostOf(bgTo);
                for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
                scene.setFrost(frostMix);
            }
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            // Two photos in the glass, now all the second one: it stays so (the next change sets the glass anew), with no
            // image to upload on the frame the fade ends.
            boolean pair = bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (!pair && glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        amount = Math.round(amount * 255) / 255f;
        if (amount == veilAmount && color == veilColor) return veilFilter;
        veilAmount = amount;
        veilColor = color;
        float keep = 1 - amount;
        return veilFilter = new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        drawImage(c, image, alpha, screen);
    }

    private void drawImage(Canvas c, Bitmap image, float alpha, RectF to) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, to, imagePaint);
    }

    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and Settings. */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.settings());
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.26f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && Prefs.weather()) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    // MARK: - Quotes and shelf

    private void setShelf(List<HomeApps.Item> list) {
        wake();
        cancelReorder(true);    // the list changed under the move (an app installed or removed)
        tileShaders = new LinearGradient[0];
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileSlide = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileSlide[i] = new Spring(0.32f, 0.86f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
    }

    private void layoutShelf() {
        int n = Math.min(shelf.size(), shelfVisible);
        float pad = SHELF_PAD * k, gap = TILE_GAP * k;
        float full = (CONTENT_R - MARGIN) * k;
        // Tiles in the TV banners' own 16:9, so an app's art is never stretched; never taller than L_TILE_H. The glass
        // keeps its bottom edge, so with more apps it only gets lower.
        float tw = Math.min(L_TILE_H * 16f / 9 * k, (full - Math.max(0, n - 1) * gap) / Math.max(1, n));
        float th = tw * 9f / 16;
        float width = n * tw + Math.max(0, n - 1) * gap, left = (w - width) / 2f;
        float top = (L_PLATTER_TOP + L_TILE_H) * k - th;
        platterRect.set(left - pad, top, left + width + pad, top + th + 2 * pad);
        snap(platterRect);
        shelfFirst = Math.max(0, Math.min(shelfFirst, shelf.size() - n));
        float x = left - shelfFirst * (tw + gap), y = top + pad;
        for (int i = 0; i < shelf.size(); i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            snap(tileRects[i]);
            x += tw + gap;
        }
        crispLogos.clear();
        clipLayers();
    }

    /**
     * The dock's and the round buttons' layers span the screen, what they draw only part of it: each is cut to that (with
     * room for shadows and focus). This TV's driver lacks EGL_EXT_buffer_age, so every frame composes the whole screen,
     * every layer to its edges: on a dock of three apps, the clear rest was half a screen of GPU fill on each frame.
     */
    private void clipLayers() {
        if (w == 0 || lTip == null) return;
        // The dock's glass with its shadow, and its tiles: the edge ones grown in focus (lifted to move, a little more).
        float bw = tileRects.length > 0 ? tileRects[0].width() : 0;
        float m = Math.max(2 * SHADOW_BLUR * k, (FOCUS_GROW + LIFT_GROW) / 2 * bw + 2.5f * DEEP_BLUR * k);
        layerClip.set((int) Math.max(0, platterRect.left - m), 0, (int) Math.min(w, Math.ceil(platterRect.right + m)), h);
        platter.setClipBounds(layerClip);
        tiles.setClipBounds(layerClip);
        // The round buttons, grown in focus with their shadows and names below, and the sleep timer before them.
        float left = w;
        for (int i = 0; i < lightButtons.size(); i++) {
            RectF r = topRects[TABS + i];
            float half = lTip.measureText(lightButtons.get(i).label) / 2;
            left = Math.min(left, r.left - 0.05f * r.width() - 2 * SHADOW_BLUR * k);
            left = Math.min(left, Math.min(r.centerX(), CONTENT_R * k - half) - half - 4 * k);
        }
        if (!sleepText.isEmpty()) {
            float right = (lightButtons.isEmpty() ? CONTENT_R * k : topRects[TABS].left) - 28 * k;
            left = Math.min(left, right - lQuote.measureText(sleepText) - 1.5f * lQuote.getTextSize());
        }
        int x = w - Math.round(1160 * k);   // where the layer starts (onLayout)
        layerClip.set((int) Math.max(0, left - x), 0, w - x, h);
        status.setClipBounds(layerClip);
    }

    /** Scrolls the shelf, one app at a time, so the focused app is shown: the tiles slide there (unless `snap`). */
    private void revealShelf(boolean snap) {
        int n = Math.min(shelf.size(), shelfVisible);
        int first = Math.max(0, Math.min(Math.max(shelfFirst, shelfIndex - n + 1), Math.min(shelfIndex, shelf.size() - n)));
        if (first == shelfFirst || tileRects.length == 0) return;
        float dx = (shelfFirst - first) * (tileRects[0].width() + TILE_GAP * k);
        for (int i = 0; i < tileRects.length; i++) {
            tileRects[i].offset(dx, 0);
            if (!snap && i < tileSlide.length) tileSlide[i].value -= dx;
        }
        shelfFirst = first;
        tilesDirty = true;
        if (snap) return;
        reorderAnim = true;   // the same slides as moving an app
        wake();
    }

    /** Shelf layer: the glass bar and its shadow (it only changes with the background). */
    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty()) return;
        float radius = (TILE_RADIUS + SHELF_PAD) * k;
        tmp.set(platterRect);
        tmp.offset(0, 18 * k);
        shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f)));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        // The magnifier's glass and shadow (drawSearch); clear, so the background changing under it doesn't redraw it.
        buttonGlass(c, platterRect, radius, 1);
    }

    /**
     * The round buttons', the magnifier's and the dock's glass: nearly clear, a hint of white by day; at night, as Liquid
     * Glass over dark content, a little lighter than what's behind it, not darker. Without shaders (its tint at least
     * 35% there), smoked at night.
     */
    private void buttonGlass(Canvas c, RectF r, float radius, float alpha) {
        int tint = scene.shaded() ? 0xFFFFFFFF : tone(0xFFFFFFFF, SMOKE);
        scene.clear(c, r, radius, tint, tone(0.18f, 0.06f), tone(0.02f, 0.025f), tone(0.6f, 0.55f), alpha);
    }

    /** The quotes row's text and sizes, for one set of coins, prices and text size. */
    private final class Quotes {
        final int[] coins;
        final double[] prices;
        final float size, cap, halfCap;
        final String[] values;
        final float[] glyphs, widths;

        Quotes(int[] coins, double[] all, Paint text) {
            this.coins = coins;
            size = text.getTextSize();
            cap = Ui.cap(text);
            halfCap = Ui.halfCap(text);
            int n = coins.length;
            prices = new double[n];
            values = new String[n];
            glyphs = new float[n];
            widths = new float[n];
            for (int i = 0; i < n; i++) {
                prices[i] = all[coins[i]];
                values[i] = HomeData.compact(prices[i]);
                glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
                widths[i] = text.measureText(values[i]);
            }
        }

        boolean matches(int[] now, double[] all, float textSize) {
            if (now != coins || textSize != size) return false;
            for (int i = 0; i < coins.length; i++) if (Double.compare(all[coins[i]], prices[i]) != 0) return false;
            return true;
        }
    }

    private Quotes quotes;
    // What's playing, cut to fit the tab row and the clock's row: once per title, never while drawing.
    private String playingTab, playingClock;
    private float playingTabWidth, playingTabCap, playingClockCap;
    private final RectF barRect = new RectF();
    // The bars bounce for a few seconds when the screen shows or the title changes, then stand still: bouncing all
    // along redrew the top layer 10 times a second (~8% of a core on a Skyworth's Cortex-A55), the still screen nothing.
    private static final long BAR_FRAME_MS = 100, BARS_BOUNCE_MS = 3000;
    private long barsUntil;
    private final Runnable bars = new Runnable() {
        @Override public void run() {
            if (!running || playingTab == null) return;
            heroDirty = true;
            wake();
            if (SystemClock.uptimeMillis() < barsUntil) handler.postDelayed(this, BAR_FRAME_MS);
        }
    };

    private void bounceBars() {
        handler.removeCallbacks(bars);
        if (playingTab == null) return;
        barsUntil = SystemClock.uptimeMillis() + BARS_BOUNCE_MS;
        handler.post(bars);
    }

    private void fitPlaying() {
        String now = playing.text;
        if (now == null || lQuote == null) {
            playingTab = playingClock = null;
            return;
        }
        fitPaint.set(lQuote);
        playingTab = TextUtils.ellipsize(now, fitPaint, 640 * k, TextUtils.TruncateAt.END).toString();
        playingTabWidth = lQuote.measureText(playingTab);
        playingTabCap = Ui.cap(lQuote);
        playingClockCap = Ui.cap(lClockQuote);
        fitPaint.set(lClockQuote);
        playingClock = TextUtils.ellipsize(now, fitPaint, (CONTENT_R - MARGIN - 60) * k, TextUtils.TruncateAt.END).toString();
    }

    /** Coin logo + price in the configured currency, in a row on the round buttons' line, ending before them. */
    private void drawQuotes(Canvas c) {
        boolean clock = !picksOn();
        // Before the round buttons and the sleep timer's "Off in" (measured once per text: this row is redrawn on every
        // frame of the cards' focus animation).
        if (!sleepText.equals(sleepMeasured)) {
            sleepMeasured = sleepText;
            sleepWidth = sleepText.isEmpty() ? 0 : lQuote.measureText(sleepText) + lQuote.getTextSize() * 1.4f + 28 * k;
        }
        float right = (lightButtons.isEmpty() ? CONTENT_R * k : topRects[TABS].left - 28 * k) - sleepWidth;
        applyTone();
        Paint text = clock ? lClockQuote : lQuote;
        // What an app is playing takes the quotes' place while it plays.
        String line = clock ? playingClock : playingTab;
        if (line != null) {
            float cap = clock ? playingClockCap : playingTabCap, gap = cap * 0.6f;
            float base = clock ? L_QUOTES_BASE * k : L_BUTTON_CY * k + cap / 2;
            float x = clock ? MARGIN * k : right - playingTabWidth - gap - NowPlaying.barsWidth(cap);
            NowPlaying.drawBars(c, x, base, cap, text, SystemClock.uptimeMillis() < barsUntil, barRect);
            c.drawText(line, x + NowPlaying.barsWidth(cap) + gap, base, text);
            return;
        }
        // One read of the list, so n always matches the coins drawn below.
        int[] coins = HomeData.active();
        int n = coins.length;
        if (n == 0) return;
        // Measured once per set of prices: this row is redrawn on every frame of the cards' focus animation, and
        // measuring text there cost ~3 ms a frame on a Mi Box 3.
        Quotes q = quotes;
        if (q == null || !q.matches(coins, data.prices, text.getTextSize())) {
            q = quotes = new Quotes(coins, data.prices, text);
        }
        // On the round buttons' line, before them.
        float base = clock ? L_QUOTES_BASE * k : L_BUTTON_CY * k + q.halfCap;
        float cap = q.cap, gap = (clock ? 8 : 6) * k, spacing = (clock ? 32 : 24) * k;
        float total = spacing * (n - 1);
        for (int i = 0; i < n; i++) total += q.glyphs[i] + gap + q.widths[i];
        float x = clock ? MARGIN * k : right - total;
        int coinColor = clock ? text.getColor() : tone(0xB38E8E93, 0x8CFFFFFF);
        for (int i = 0; i < n; i++) {
            Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], x + q.glyphs[i] / 2, base - cap / 2, cap, coinColor);
            x += q.glyphs[i] + gap;
            c.drawText(q.values[i], x, base, text);
            x += q.widths[i] + spacing;
        }
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        if (stale.isEmpty()) return;
        if (clock) c.drawText(stale, x - spacing + 24 * k + staleQuote.measureText(stale), base, staleQuote);
        else c.drawText(stale, right - total - 24 * k, base, staleQuote);
    }

    private void drawTiles(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        if (liftIndex >= 0 && liftIndex < shelf.size() && moveLift.value > 0.02f) top = liftIndex;
        int last = Math.min(shelf.size(), shelfFirst + shelfVisible);
        // Scrolling, tiles slide in and out through the glass's sides (only those that show any part are drawn). Only
        // then: on a shelf that fits, the clip would cut the end tiles' shadows in a straight line.
        boolean scrolls = shelf.size() > shelfVisible;
        if (scrolls) {
            c.save();
            c.clipRect(platterRect.left, 0, platterRect.right, h);
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                float slid = i < tileSlide.length ? tileSlide[i].value : 0;
                if (tileRects[i].right + slid <= platterRect.left || tileRects[i].left + slid >= platterRect.right) continue;
                // Moving apps: the tile slides from where it was and the lifted one wiggles (only then: no extra work
                // otherwise).
                float dx = i < tileSlide.length ? tileSlide[i].value : 0;
                float angle = i == liftIndex ? wiggle * moveLift.value : 0;
                if (dx == 0 && angle == 0) {
                    drawShelfTile(c, i);
                    continue;
                }
                c.save();
                c.translate(dx, 0);
                c.rotate(angle, tileRects[i].centerX(), tileRects[i].centerY());
                drawShelfTile(c, i);
                c.restore();
            }
        }
        if (scrolls) c.restore();
        // More apps than fit: a small arrow inside the glass's edge on each side that has more, where no tile reaches
        // even focused (inside the screen's safe area).
        float cy = platterRect.centerY(), size = 28 * k, inset = 14 * k;
        int ink = tone(0x99000000, 0xCCFFFFFF);
        if (last < shelf.size()) Symbols.draw(getContext(), c, "chevron_right", platterRect.right - inset, cy, size, ink);
        if (shelfFirst > 0) {
            c.save();
            c.scale(-1, 1, platterRect.left + inset, cy);
            Symbols.draw(getContext(), c, "chevron_right", platterRect.left + inset, cy, size, ink);
            c.restore();
        }
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and the focused card's
     * glare. Its edge catches the light as the dock's glass does (drawTileGlass).
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        // Lifted to be moved: a little bigger than focused, with a deeper shadow.
        float l = i == liftIndex ? Math.max(0, moveLift.value) : 0;
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f + LIFT_GROW * l;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (4 + 14 * f + 6 * l) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc + 0.14f * Ui.clamp(l)));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        int[] style = TILE_STYLE.get(it.key);
        if (it.logo != null && style != null) {
            if (tileShaders.length != shelf.size() || tileShaderH != bh) {
                tileShaders = new LinearGradient[shelf.size()];
                tileShaderH = bh;
            }
            if (tileShaders[i] == null) {
                tileShaders[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, style[0], style[1], Shader.TileMode.CLAMP);
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tileFill.setShader(tileShaders[i]);
            tileFill.setAlpha(255);
            c.drawRoundRect(tmp, r0, r0, tileFill);
            tileFill.setShader(null);
            c.restore();
            drawLogo(c, it, cell, s, style[2]);
        } else if (it.logo != null) {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r, r, tileFill);
            drawLogo(c, it, cell, s, 0);
        } else {
            if (it.tile != null) roundImage(c, it.tile, it.tileShader, cell, r);
        }
        drawTileGlass(c, cx, cy, bw, bh, s, r0, fc);
    }

    /**
     * The icon as under glass, as the focused card is: focused, the card's glare from its top-left corner over it; and
     * always its rim, as Liquid Glass's edge: the light catches it on the top-left corner and, through the glass, more
     * softly on the bottom-right one; between them, along the sides, it nearly fades. Brighter focused. In the icon's
     * own transform (it grows with it).
     */
    private void drawTileGlass(Canvas c, float cx, float cy, float bw, float bh, float s, float r0, float focus) {
        if (tileRimShader == null || tileRimW != bw || tileRimH != bh) {
            tileRimShader = glassLight(bw, bh);
            tileSheen = glassSheen(bw, bh);
            tileRimW = bw;
            tileRimH = bh;
            tileRim.setStyle(Paint.Style.STROKE);
        }
        float sw = 2.5f * k;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        if (focus > 0.01f) {
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tileFill.setShader(tileSheen);
            tileFill.setAlpha(Math.round(255 * focus));
            c.drawRoundRect(tmp, r0, r0, tileFill);
            tileFill.setShader(null);
            tileFill.setAlpha(255);
        }
        tmp.set(-bw / 2 + sw / 2, -bh / 2 + sw / 2, bw / 2 - sw / 2, bh / 2 - sw / 2);
        tileRim.setShader(tileRimShader);
        tileRim.setStrokeWidth(sw);
        tileRim.setAlpha(Math.round(255 * (0.75f + 0.25f * focus)));
        c.drawRoundRect(tmp, r0 - sw / 2, r0 - sw / 2, tileRim);
        c.restore();
    }

    /**
     * A glass edge's light for a w × h shape centered on 0, 0 (the dock's icons, the focused card): by angle from
     * 3 o'clock, clockwise, squeezed to the shape so its corners fall on 45° and 225°: brightest top-left, softer
     * bottom-right, nearly nothing along the sides between.
     */
    /**
     * The glare over a focused w × h shape centered on 0, 0 (a card, a dock icon), as if under glass: from its top-left
     * corner, gone by its bottom's middle.
     */
    private static LinearGradient glassSheen(float w, float h) {
        return new LinearGradient(-w / 2, -h / 2, 0, h / 2, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
    }

    private static android.graphics.SweepGradient glassLight(float w, float h) {
        android.graphics.SweepGradient g = new android.graphics.SweepGradient(0, 0,
                new int[] {0x1FFFFFFF, 0x8CFFFFFF, 0x1FFFFFFF, 0x26FFFFFF, 0xF2FFFFFF, 0x4DFFFFFF, 0x1FFFFFFF},
                new float[] {0, 0.125f, 0.25f, 0.5f, 0.625f, 0.75f, 1});
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(1, h / w);
        g.setLocalMatrix(m);
        return g;
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`: the shader of an anti-aliased rounded rectangle. A
     * clipPath there isn't anti-aliased on some GPUs (stair-stepped corners on a MediaTek TV), and on Android 8 to 11 it
     * cost a mask on every frame of a focus animation (measured on a Mi Box 3: most of a 49 ms frame). `shader` is the
     * image's own, made with it (HomeApps.Item, Backdrop) and dropped with it; the canvas does the scaling, so drawing
     * changes nothing in it and allocates nothing.
     */
    private void roundImage(Canvas c, Bitmap image, BitmapShader shader, RectF cell, float r) {
        float sx = cell.width() / image.getWidth(), sy = cell.height() / image.getHeight();
        c.save();
        c.translate(cell.left, cell.top);
        c.scale(sx, sy);
        imageFill.setShader(shader);
        c.drawRoundRect(0, 0, image.getWidth(), image.getHeight(), r / sx, r / sy, imageFill);
        imageFill.setShader(null);
        c.restore();
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size and to the resting one: focused or at
     * rest it is drawn pixel for pixel, without resampling (on a 720p UI, shrinking at draw time made it look jagged).
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        boolean rest = s < 1 + FOCUS_GROW / 2;
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null, rest);
        if (logo == null) return;
        float scale = rest ? s : s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (rest ? Math.abs(scale - 1) < 0.002f : scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark, boolean rest) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width()) + (rest ? "@rest" : "");
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        if (rest) scale /= 1 + FOCUS_GROW;
        Bitmap out = Ui.downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = HomeApps.allInputs(c);
        list.addAll(HomeApps.drawerShortcuts(c));
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    private void layoutDrawer() {
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = drawerInputs > 0 && drawerInputs < n ? 2 : 1;
        float tw = Math.min(230 * k, (right - left - Math.max(0, n - groups) * gap - (groups - 1) * groupGap) / Math.max(1, n));
        float x = left, top = ROW_TOP * k;
        for (int i = 0; i < n; i++) {
            if (i == drawerInputs && i > 0) x += groupGap - gap;
            drawerRects[i].set(x, top, x + tw, top + ROW_H * k);
            snap(drawerRects[i]);
            x += tw + gap;
        }
        float cardsTop = CARDS_TOP * k, split = left + (right - left) * 0.585f;
        boolean wx = Prefs.weather(), qt = Prefs.quotes() && HomeData.active().length > 0;
        // A card that is off gives its room to the other one.
        weatherCard.set(left, cardsTop, qt ? split - 16 * k : right, cardsTop + CARDS_H * k);
        quotesCard.set(wx ? split + 16 * k : left, cardsTop, right, cardsTop + CARDS_H * k);
        snap(weatherCard);
        snap(quotesCard);
        // The symbols at their sizes at rest and focused: drawing them never scales one.
        for (HomeApps.Item it : drawerItems) {
            Symbols.warm(getContext(), it.symbol, 34 * k);
            Symbols.warm(getContext(), it.symbol, 34 * k * 1.07f);
        }
        Symbols.warmWeather(getContext(), 52 * k);
        Symbols.warmWeather(getContext(), 30 * k);
        Symbols.warm(getContext(), "sunrise", 22 * k);
        Symbols.warm(getContext(), "sunset", 22 * k);
        drawerDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        drawerOpen = true;
        drawer.target = 1;
        zone = Z_DRAWER;
        drawerIndex = shelf.isEmpty() ? 0 : nearest(drawerRects, tileRects[shelfIndex].centerX());
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        drawerDirty = true;
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;
        for (Spring f : drawerFocus) f.snap(0);   // the drawer goes away: its focus doesn't need to animate
        if (!shelf.isEmpty() && drawerIndex < drawerRects.length) shelfIndex = nearest(tileRects, drawerRects[drawerIndex].centerX());
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        float dk = dark();
        if (dk < 0.999f) {
            tmp.set(r);
            tmp.offset(0, drop);
            shadow.setColor(Ui.alpha(shadowColor, 1 - dk));
            shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
            scene.glass(c, r, radius, 0xFFFFFFFF, lightTint, 0, 0, 0.02f, 1, 1 - dk);
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk);
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        cardDimRight.setColor(tone(L_FAINT, 0x99FFFFFF));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        hourTemp.setColor(drawerInk(0.95f));
        dayLow.setColor(tone(L_DIM, 0xADFFFFFF));
        pricePaint.setColor(drawerInk(0.95f));
        float titleBase = ROW_TITLE * k;
        if (drawerInputs > 0) c.drawText(Ui.tr("Inputs"), drawerRects[0].left + 4 * k, titleBase, rowTitle);
        if (drawerInputs < drawerItems.size()) c.drawText(Ui.tr("Shortcuts"), drawerRects[drawerInputs].left + 4 * k, titleBase, rowTitle);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        if (Prefs.weather()) drawWeatherCard(c, weatherCard);
        if (Prefs.quotes() && HomeData.active().length > 0) drawQuotesCard(c, quotesCard);
    }

    /**
     * Square drawer button: symbol and name; an input with no device is dimmed. The magnifier's glass and shadow (as the
     * dock's above it); focused: white, with dark text.
     */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * k * s;
        tmp.set(cell);
        tmp.offset(0, (6 + 8 * f) * k);
        shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
        shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        buttonGlass(c, cell, r, 1 - f);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.4f, 34 * k * s, ink);
        drawerLabel.setColor(ink);
        drawerLabel.setTextSize(23 * k * s);
        float room = cell.width() - 16 * k, wide = drawerLabel.measureText(it.label);
        // A long name ("Launcher Settings", an input you renamed) shrinks a little before it gets cut.
        if (wide > room) drawerLabel.setTextSize(Math.max(18 * k * s, 23 * k * s * room / wide));
        fitPaint.set(drawerLabel);
        String label = TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel);
        drawerLabel.setTextSize(23 * k);
    }

    /** Weather for your location: now (large), the next 6 hours or 5 days, and sunrise and sunset. */
    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) weatherSnapshot.draw(c, r, drawerCardsKey(), cc -> drawWeather(cc, r));
        else drawWeather(c, r);
    }

    private void drawWeather(Canvas c, RectF r) {
        refreshDrawerText();
        float x = r.left + 34 * k;
        c.drawText(weatherTitle, x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = textWeather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 52 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, drawerInk(0.95f));
        c.drawText(nowTemp, x + sw + 18 * k - Ui.leftBearing(bigTemp, nowTemp), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(highLow, x, r.top + 258 * k, cardDim);
        float split = r.left + 392 * k;
        fill.setShader(null);
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 30 * k;
        if (textDays) {
            float hw = (r.right - 30 * k - hx) / 5;
            for (int i = 0; i < wx.days.size() && i < 5; i++) {
                float hc = hx + hw * (i + 0.5f);
                c.drawText(dayText[i], hc, r.top + 96 * k, hourPaint);
                Symbols.draw(getContext(), c, Symbols.weather(wx.dayCode.get(i), true), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
                // The high, then the low dimmed, centered together (closer than the next day, so the pairs read apart).
                float maxW = hourTemp.measureText(dayMaxText[i]), minW = dayLow.measureText(dayMinText[i]), gap = 6 * k;
                float left = hc - (maxW + gap + minW) / 2;
                c.drawText(dayMaxText[i], left + maxW / 2, r.top + 196 * k, hourTemp);
                c.drawText(dayMinText[i], left + maxW + gap + minW / 2, r.top + 196 * k, dayLow);
            }
        } else {
            float hw = (r.right - 30 * k - hx) / 6;
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                float hc = hx + hw * (i + 0.5f);
                c.drawText(hourText[i], hc, r.top + 96 * k, hourPaint);
                Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
                c.drawText(hourTempText[i], hc, r.top + 196 * k, hourTemp);
            }
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", sunriseText, sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", sunsetText, sx, base);
    }

    private void refreshDrawerText() {
        HomeData.Weather wx = data.weather;
        boolean settings = Prefs.version != textPrefs || !Ui.language.equals(textLanguage);
        textPrefs = Prefs.version;
        textLanguage = Ui.language;
        if (wx != textWeather || settings) {
            textWeather = wx;
            weatherTitle = Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city();
            nowTemp = HomeData.degrees(wx.temp);
            highLow = HomeData.highLow(wx, "   ");
            textDays = Prefs.forecast().equals("days") && !wx.days.isEmpty();   // no days yet (an older forecast, offline): the hours
            for (int i = 0; textDays && i < wx.days.size() && i < 5; i++) {
                dayText[i] = HomeData.weekday(wx.days.get(i));
                dayMaxText[i] = HomeData.degrees(wx.dayMax.get(i));
                dayMinText[i] = HomeData.degrees(wx.dayMin.get(i));
            }
            for (int i = 0; !textDays && i < wx.hours.size() && i < 6; i++) {
                hourText[i] = HomeData.hour(wx.hours.get(i));
                hourTempText[i] = HomeData.degrees(wx.hourTemp.get(i));
            }
            sunriseText = Ui.trf("Sunrise {time}", "time", Ui.time(wx.sunrise));
            sunsetText = Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset));
        }
        long minute = System.currentTimeMillis() / 60_000;
        if (data.pricesAt != textPricesAt || minute != textMinute || settings) {
            textPricesAt = data.pricesAt;
            textMinute = minute;
            String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
            staleText = stale.isEmpty() ? Ui.tr("24h change") : Ui.trf("quotes|{ago}", "ago", stale);
            for (int i = 0; i < HomeData.COINS.length; i++) {
                priceText[i] = HomeData.brl(data.prices[i]);
                changeText[i] = HomeData.change(data.changes[i]);
            }
        }
    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF));
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    /** Prices in the configured currency with the 24 h change (green/red, like Apple's Stocks app). */
    private void drawQuotesCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) quotesSnapshot.draw(c, r, drawerCardsKey(), cc -> drawPrices(cc, r));
        else drawPrices(c, r);
    }

    /**
     * What the drawer cards show depends on: the data, the settings, the language, day or night, and the minute (the
     * sunrise and sunset, "12 min ago").
     */
    private String drawerCardsKey() {
        return data.weatherAt + "|" + data.pricesAt + "|" + Prefs.version + "|" + Ui.language + "|"
                + Float.floatToIntBits(dark()) + "|" + System.currentTimeMillis() / 60_000;
    }

    /**
     * Android 8 to 11: a drawer card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again took ~15 ms a frame on a Mi Box 3.
     */
    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void draw(Canvas c, RectF r, String key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot weatherSnapshot = new Snapshot(), quotesSnapshot = new Snapshot();

    private void drawPrices(Canvas c, RectF r) {
        float x = r.left + 34 * k, right = r.right - 34 * k;
        refreshDrawerText();
        c.drawText(Ui.tr("Crypto"), x, r.top + 54 * k, cardTitle);
        c.drawText(staleText, right, r.top + 54 * k, cardDimRight);
        float cap = Ui.cap(cardText);
        int[] coins = HomeData.active();
        for (int j = 0; j < coins.length; j++) {
            int i = coins[j];
            float base = r.top + (110 + 52 * j) * k, mid = base - cap / 2;
            float gw = Glyphs.width(getContext(), HomeData.COINS[i], cap);
            Glyphs.coin(getContext(), c, HomeData.COINS[i], x + 14 * k, mid, cap, tone(L_DIM, 0xCCFFFFFF));
            c.drawText(HomeData.coinName(i), x + Math.max(gw, 28 * k) / 2 + 30 * k, base, cardText);
            c.drawText(priceText[i], right - 128 * k, base, pricePaint);
            double change = data.changes[i];
            if (Double.isNaN(change)) continue;
            int color = change >= 0 ? tone(0xFF248A3D, UP_COLOR) : tone(0xFFD70015, DOWN_COLOR);
            tmp.set(right - 108 * k, mid - 17 * k, right, mid + 17 * k);
            fill.setShader(null);
            fill.setColor(Ui.alpha(color, 0.2f));
            c.drawRoundRect(tmp, 17 * k, 17 * k, fill);
            changePaint.setColor(color);
            c.drawText(changeText[i], tmp.centerX(), mid + Ui.halfCap(changePaint), changePaint);
        }
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        revealShelf(snap);
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean statusChanged = aim(statusFocus, !sheetOpen && zone == Z_STATUS ? statusIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        boolean heroChanged = heroFocus.target != (!sheetOpen && zone == Z_HERO ? 1 : 0);
        heroFocus.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        boolean topChanged = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
        // The pill goes to the selected tab: gliding from the last one while the tabs have the focus, else at once (it
        // grows in there).
        RectF selected = topRects[tab];
        if (snap || tabsFocus() < 0.01f) {
            tabPillX.snap(selected.left);
            tabPillW.snap(selected.width());
        } else if (tabPillX.target != selected.left || tabPillW.target != selected.width()) {
            tabPillX.target = selected.left;
            tabPillW.target = selected.width();
            topChanged = true;
        }
        boolean mic = searchOpen && onMic;
        float searchTarget = !sheetOpen && zone == Z_SEARCH && !mic ? 1 : 0, micTarget = !sheetOpen && zone == Z_SEARCH && mic ? 1 : 0;
        heroChanged |= searchFocus.target != searchTarget || micFocus.target != micTarget;
        searchFocus.target = searchTarget;
        micFocus.target = micTarget;
        if (snap) {
            searchFocus.snap(searchTarget);
            micFocus.snap(micTarget);
        }
        restartCaret();
        heroChanged |= aimCards(snap) | aimRow(snap) | topChanged;
        statusChanged |= topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
        spoken.focusMoved();
    }

    /** The row's focus: the focused card's lift and its light (none with the sheet open or the focus elsewhere). */
    private boolean aimCards(boolean snap) {
        int focused = !sheetOpen && zone == Z_CARDS ? cardIndex : -1;
        return aim(cardFocus, focused, snap) | aim(cardGlow, focused, snap);
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    /**
     * The row's targets: the focused card wide, the others tall, and the scroll that keeps the wide card inside the
     * margins while moving the row as little as possible (three cards fit exactly: the row moves from the fourth on).
     * Also each slot's place at rest. True if something is to move.
     */
    private boolean aimRow(boolean snap) {
        if (slots == 0 || tallW == 0) return false;
        int focus = Math.min(cardIndex, slots - 1);
        boolean changed = aim(cardWide, focus, snap);
        float margin = MARGIN * k, left = margin + focus * (tallW + cardGap), right = left + wideW;
        float s = rowScroll.target;
        if (left - s < margin) s = left - margin;
        if (right - s > CONTENT_R * k) s = right - CONTENT_R * k;
        s = Math.max(0, s);
        changed |= s != rowScroll.target;
        rowScroll.target = s;
        if (snap) rowScroll.snap(s);
        float x = margin - s, top = L_CARDS_TOP * k, bottom = top + L_CARD_H * k;
        for (int i = 0; i < slots; i++) {
            float w = i == focus ? wideW : tallW;
            cardRects[i].set(x, top, x + w, bottom);
            snap(cardRects[i]);
            x += w + cardGap;
        }
        return changed;
    }

    private static boolean aim(List<Spring> springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.size(); i++) {
            Spring s = springs.get(i);
            float target = i == focused ? 1 : 0;
            changed |= s.target != target;
            s.target = target;
            if (snap) s.snap(target);
        }
        return changed;
    }

    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = true;
    }

    /** All Apps from the remote's button. Coming back from another app, resume() opens it (it closes the sheet). */
    void showAllApps() {
        if (!running) {
            allAppsOnResume = true;
            return;
        }
        reset(false);
        openSheet();
    }

    private boolean allAppsOnResume;

    private void openSheet() {
        sheetOpen = true;
        gridIndex = 0;
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
        if (shelfEdited) {     // the grid was kept as it was while open: apps that went to the shelf leave it now
            shelfEdited = false;
            reload();
        }
    }

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    private void drawSheet(Canvas c) {
        c.drawColor(0xA8000000);
        float shift = scroll.value * GRID_ROW * k;
        c.drawText(Ui.tr("All Apps"), w / 2f, 124 * k - shift, sheetTitle);
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = GRID_TOP * k;
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * k - shift;
            if (y > h || y + GRID_ROW * k < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k);
            float f = gridFocus[i].value;
            drawCard(c, others.get(i), gridCell, GRID_RADIUS * k, f, 0.08f);
            if (sheetOpen && i == gridIndex) focusedCell.set(cell);
            gridLabel.setColor(blend(0x99FFFFFF, 0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(gridLabels[i], gridCell.centerX(), gridCell.bottom + (40 + 6 * f) * k, gridLabel);
        }
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, HomeApps.Item it, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (it.tile != null) roundImage(c, it.tile, it.tileShader, cell, r);
        if (f > 0.01f) {
            float bh = base.height();
            if (cardSheen == null || cardSheenH != bh) {
                cardSheen = new LinearGradient(0, -bh / 2, 0, 0, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                cardSheenH = bh;
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-base.width() / 2, -bh / 2, base.width() / 2, bh / 2);
            sheen.setShader(cardSheen);
            sheen.setAlpha(Math.round(255 * fc));
            c.drawRoundRect(tmp, radius, radius, sheen);
            sheen.setAlpha(255);
            c.restore();
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
        wake();
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        // The shelf and All Apps (100 to 500 ms of package queries on a TV) only when they can have changed.
        long up = SystemClock.uptimeMillis();
        final boolean apps = appsStale || Prefs.version != appsVersion || Ui.generation != appsGeneration
                || up - appsAt > APPS_MAX_AGE_MS;
        if (apps) {
            appsStale = false;
            appsVersion = Prefs.version;
            appsGeneration = Ui.generation;
            appsAt = up;
        }
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = apps ? HomeApps.shelf(c, true) : null;
            final List<HomeApps.Item> st = statusList(c);
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = apps ? HomeApps.others(c) : null;
            final List<HomeApps.Item> top = apps ? HomeApps.topButtons() : null;   // they change with Prefs too
            handler.post(() -> {
                if (top != null) setLightButtons(top);
                if (s != null) setShelf(s);
                setStatusItems(st);
                setDrawerItems(d);
                if (o != null && !sheetOpen) setOthers(o);
                else if (o != null) appsStale = true;   // All Apps is open: its list comes next time
            });
        });
    }

    /** Apps installed, removed or updated (also while another app is open): the next reload reads them again. */
    private final LauncherApps.Callback packages = new LauncherApps.Callback() {
        @Override public void onPackageAdded(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageRemoved(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackageChanged(String pkg, UserHandle user) { appsChanged(); }
        @Override public void onPackagesAvailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
        @Override public void onPackagesUnavailable(String[] pkgs, UserHandle user, boolean replacing) { appsChanged(); }
    };

    private void appsChanged() {
        appsStale = true;
        if (running) reloadSoon();
    }

    // MARK: - Keys: only the arrows, OK, Back and Settings (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        wake();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (reordering) return reorderKey(e, down);
        if (menuOpen) return menuKey(e, down);
        int code = e.getKeyCode();
        if (down && code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
                && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            holdArmed = false;      // another key while OK is held: no move mode
            handler.removeCallbacks(holdStart);
        }
        if (down && rowTab != tab && code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) swapRow(false);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                // Held (~0.5 s) on a shelf app: move mode; on an app in All Apps: on or off the shelf. A timer, since
                // not every remote repeats a held key, or the long-press repeat, whichever comes first; the release
                // after a hold opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                        handler.removeCallbacks(holdStart);
                        holdArmed = sheetOpen ? canToggleShelf(gridIndex) : canReorder() || canPickMenu() || canFilter();
                        if (holdArmed) handler.postDelayed(holdStart, HOLD_MS);
                    } else if (okDown && holdArmed && e.isLongPress()) {
                        hold();
                    }
                } else {
                    holdArmed = false;
                    handler.removeCallbacks(holdStart);
                    if (okDown && !okHeld && !e.isCanceled() && spoken.keyClick()) activate();
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                // The remote's menu key (≡) opens what holding OK opens: a pick's menu, or Explore's filters.
                if (!canPickMenu() && !canFilter()) return false;
                if (down && e.getRepeatCount() == 0) {
                    if (canPickMenu()) openPickMenu(); else openFilters(false);
                }
                return true;
            case KeyEvent.KEYCODE_SEARCH:
                // With the search's field open, the remote's search key speaks into it; elsewhere it's the TV's own.
                if (!searchOpen || voice == null) return false;
                if (down && e.getRepeatCount() == 0) startVoice();
                return true;
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_NOTIFICATION:
                // The remote's Settings button (TCL's sends NOTIFICATION) opens TV Settings, as Hisense's gear does by
                // default; ours are Home Settings in the drawer. Hisense's gear is a Remote Buttons choice (RemoteKeys);
                // where the system takes the key first (Android 14's settings key behavior, JMGO's), it never gets here:
                // SettingsKey sees it in SystemUI's log.
                if (RemoteKeys.HISENSE) return false;
                if (down && e.getRepeatCount() == 0) openTvSettings();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** TV Settings, from the middle of the screen: the remote's Settings button (here, or SettingsKey's). */
    void openTvSettings() {
        HomeApps.open(activity, HomeApps.tvSettings(), this, w / 2, h / 2, 1, 1);
    }

    private void back() {
        if (sheetOpen) {
            closeSheet();
        } else if (zone == Z_DRAWER || zone == WIDGETS) {
            closeDrawer();
            retarget(false);
        } else if (searchOpen && zone == Z_SEARCH) {
            closeSearch(false);
        } else if (searchOpen && zone == Z_CARDS) {
            zone = Z_SEARCH;    // from the answers, back to the field
            onMic = false;
            retarget(false);
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            retarget(false);
        } else if (searchOpen) {
            closeSearch(false);
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!statusItems.isEmpty()) {
                        zone = Z_STATUS;
                        statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[shelfIndex].centerX());
                    } else {
                        return;
                    }
                } else {
                    if (drawerItems.isEmpty()) return;
                    openDrawer();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return;
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) return;
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            case WIDGETS: {
                List<Integer> on = widgets();
                int at = on.indexOf(widgetIndex);
                if (dy < 0 || at < 0) {
                    zone = Z_DRAWER;
                } else if (dx != 0 && at + dx >= 0 && at + dx < on.size()) {
                    widgetIndex = on.get(at + dx);
                } else {
                    return;
                }
                break;
            }
            default:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0) {
                    closeDrawer();
                } else if (talkBack() && !widgets().isEmpty()) {
                    // Only with TalkBack: the widgets are there to be read, and this is how it gets to them.
                    zone = WIDGETS;
                    widgetIndex = widgets().get(0);
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void activate() {
        wake();
        if (reordering) {   // TalkBack's click while moving an app: drop it
            dropReorder();
            return;
        }
        HomeApps.Item it;
        RectF r;
        if (!sheetOpen && zone == WIDGETS) return;   // a widget is only read
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_HERO) {
            Recs.Pick p = currentPick();
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = heroButton;
        } else if (zone == Z_STATUS) {
            if (statusItems.isEmpty()) return;
            it = statusItems.get(statusIndex);
            r = statusRects[statusIndex];
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
        } else if (zone == Z_SEARCH) {
            if (!searchOpen) openSearch();
            else if (onMic) startVoice();
            else showKeyboard();
            return;
        } else if (zone == Z_TOP) {
            if (topIndex < TABS) {
                selectTab(topIndex);
                return;
            }
            it = lightButtons.get(topIndex - TABS);
            r = topRects[topIndex];
        } else if (zone == Z_CARDS) {
            Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
            if (p != null && p.pkg == null) {   // an answer or a watchlist's title Filmix (or the TV's apps) hasn't: its menu works
                String app = Recs.onlyApp();
                if (app != null && !recs.away(p)) showToast(Ui.trf("Couldn't open {app}.", "app", app));   // Filmix didn't answer
                else if (app != null) showToast(Ui.trf("“{title}” isn't on {app}.", "title", p.title, "app", app));
                else showToast(Ui.trf("“{title}” isn't on the TV's apps.", "title", p.title));
            }
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p), p.link);
            r = cardRects[cardIndex];
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
        click.open();
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            openDrawer();
            for (int i = 0; i < drawerItems.size(); i++) {
                if (drawerItems.get(i).kind == HomeApps.INPUT) {
                    drawerIndex = i;
                    break;
                }
            }
            retarget(false);
            return;
        }
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.trf("Couldn't open {app}.", "app", it.label));
    }

    // MARK: - Holding OK: All Apps puts the app on the shelf or takes it off; on the shelf, the app is lifted to move;
    // on a pick, its menu opens; on the Explore tab, its filters

    private void hold() {
        if (sheetOpen) toggleShelf(); else if (canPickMenu()) openPickMenu(); else if (canFilter()) openFilters(true); else startReorder();
    }

    /** TalkBack's long click on an app in All Apps (once per press, if the remote's hold also arrives). */
    private void a11yHold() {
        if (SystemClock.uptimeMillis() - toggledAt < 1000) return;
        if (okDown) okHeld = true;     // the remote's release must not open the app
        toggleShelf();
    }

    private boolean canToggleShelf(int i) {
        if (!sheetOpen || i >= others.size()) return false;
        HomeApps.Item it = others.get(i);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    /** Puts the focused app at the end of the shelf, or takes it off if it's there; a toast says which. */
    private void toggleShelf() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (!canToggleShelf(gridIndex)) return;
        okHeld = true;
        toggledAt = SystemClock.uptimeMillis();
        final HomeApps.Item it = others.get(gridIndex);
        final Context c = getContext().getApplicationContext();
        click.open();
        LOADER.execute(() -> {
            // On the shelf means shown there: installed and within the limit, as in Settings > Shelf.
            List<String> list = new ArrayList<>(HomeApps.shelfPackages(c));
            PackageManager pm = c.getPackageManager();
            int max = HomeApps.maxShelf(c), shown = 0;
            boolean on = false;
            for (String p : list) {
                if (shown >= max) break;
                if (HomeApps.launchIntent(pm, p) == null) continue;
                shown++;
                on |= p.equals(it.pkg);
            }
            final String text;
            list.remove(it.pkg);
            if (on) {
                text = Ui.trf("Removed from the shelf: {app}", "app", it.label);
            } else {
                if (shown >= max) {
                    handler.post(() -> showToast(Ui.tr("The shelf is full. Make room in Free My TV › Shelf.")));
                    return;
                }
                list.add(it.pkg);
                text = Ui.trf("Added to the shelf: {app}", "app", it.label);
            }
            Prefs.setShelf(list);   // here, so the next hold reads the new list
            handler.post(() -> {
                shelfEdited = true;
                showToast(text);
                reload();
            });
        });
    }

    // MARK: - A pick's menu (OK held on its card): its trailer on YouTube; watched, rated or not recommended on Trakt

    // The items (menuList holds the ones this menu has, in order: on the Watchlist tab, no Don't recommend).
    private static final int M_TRAILER = 0, M_WATCHED = 1, M_WATCHLIST = 2, M_HIDE = 3;
    private final int[] menuList = new int[4];
    private int menuCount;
    // 658 wide: beside the wide card at the row's left end, it ends at the content's right edge (CONTENT_R).
    private static final float MENU_W = 658, MENU_ITEM_H = 88, MENU_PAD = 14, MENU_GAP = 6;
    /** The cards' corners: the menu panel's (its pills' half height and its padding), as Liquid Glass rounds them. */
    private static final float CARD_R = MENU_ITEM_H / 2 + MENU_PAD;
    /** A rating goes to Trakt this long after the last star pressed (or as the menu closes): one request, not one a press. */
    private static final long RATE_AFTER_MS = 1200;
    private static final String YOUTUBE = "com.google.android.youtube.tv";
    private final Spring menu = new Spring(0.34f, 0.86f, 0);
    private final Spring[] menuFocus = new Spring[4];   // a pick's four items
    private final Spring[] starFocus = new Spring[10];   // Trakt's own 1 to 10 (five stars with halves)
    /** While the menu is open, its card goes to the row's left end and the menu takes its right: the row's shift. */
    private final Spring menuShift = new Spring(0.16f, 1, 0);
    private boolean menuWaitsRow;   // the screen is copied, and the menu shown, once the row stops there
    private Recs.Pick hideAfter;    // "Don't recommend": it leaves the row once the menu has faded
    private Recs.Pick unlistAfter;  // off the watchlist from the Watchlist tab: it leaves that row once the menu has faded
    // The same menu as Explore's filters (OK held on its tab): FilterPanel's panel in the middle of the screen, nothing
    // moving behind it; the row takes its filters as it closes.
    private boolean menuFilters;
    private FilterPanel filterPanel;
    private boolean menuOpen, menuDirty, menuBackDirty, menuRight, menuCovers, menuSignedIn;   // signed in to Trakt: read once as it opens (a file)
    /** Signed in to Trakt (a file): read as the screen comes back and as data comes in, never while drawing. */
    private boolean traktIn;
    private Recs.Pick menuPick;
    private int menuCard, menuIndex, starIndex, menuShotId, menuWarm;
    private Bitmap menuShot;    // the screen behind the menu: a small copy, blurred and dimmed
    private Bitmap menuSharp;   // and as it was, full size: it stands in for the home screen while the menu fades
    private Bitmap shotNext, sharpNext;   // the two copies of the screen as they come in (the menu shows once both have)
    private int[] shotPx;
    // How dark the picture under the panel is (0 or 1, the night's dark() aside), as Liquid Glass adapts: by day over a
    // dark photo the panel is drawn as at night, its text white. Without the copy, over the plain dim: dark.
    private float menuUnder = 1, shotUnder;
    private boolean cardOverMenu;   // drawLightCard is drawing the menu's card (drawMenuBack)
    private Scene menuScene;    // the panel's glass, over that copy
    private final Paint shotPaint = new Paint(Paint.FILTER_BITMAP_FLAG), starLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint menuText, menuHint, menuTitle, menuValue, lEmpty, lBadge;
    // Under the menu's card (drawAbout): its title in words, the start of its story, its age rating after its details.
    private TextPaint aboutTitle, aboutText;
    private Paint ageText;
    private final Paint ageRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String aboutLine = "";
    private StaticLayout aboutLayout;
    private float aboutTitleBase, aboutTop;   // the title's baseline and the story's top (aboutMenu)
    private final RectF menuRect = new RectF();   // the panel at rest, on screen
    private final Rect menuBounds = new Rect();   // its two layers: the panel and room for its shadow
    // Trakt, per title (Recs.key): marked watched or hidden from here; the stars chosen; the stars sent (or on their way)
    private final Set<String> watched = new HashSet<>(), hiding = new HashSet<>(), listing = new HashSet<>();
    private final Map<String, Integer> rated = new HashMap<>(), ratedSent = new HashMap<>();
    private Recs.Pick ratePick;
    private final Runnable rateNow = this::sendRating;
    private final Runnable menuFallback = () -> showMenu(menuShotId);

    private boolean canPickMenu() {
        if (sheetOpen || zone != Z_CARDS || cardIndex >= cards.size()) return false;
        Recs.Pick p = cards.get(cardIndex);
        return p.photo == null && p.id > 0 && p.title != null;
    }

    private int menuItems() {
        return menuCount;
    }

    /** The pick menu's item at `i` (M_TRAILER…). */
    private int menuItem(int i) {
        return menuList[Math.max(0, Math.min(menuCount - 1, i))];
    }

    /** The second item shows the stars once the title is marked watched (or rated) from here. */
    private boolean stars(Recs.Pick p) {
        String key = Recs.key(p);
        return watched.contains(key) || rated.containsKey(key);
    }

    private void openPickMenu() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (menuOpen || !canPickMenu()) return;
        sendRating();               // one still waiting, for another title, goes now
        okHeld = true;              // the release of this press opens nothing
        menuOpen = true;
        menuFilters = false;
        menuPick = cards.get(cardIndex);
        menuCard = cardIndex;
        menuIndex = 0;
        menuCount = 0;
        menuList[menuCount++] = M_TRAILER;
        if (Trakt.hasKey()) {
            menuList[menuCount++] = M_WATCHED;
            menuList[menuCount++] = M_WATCHLIST;
            String kind = rowKind();   // not recommended from the Watchlist or a search: nothing to hide there
            if (!Recs.WATCHLIST.equals(kind) && !Recs.SEARCH.equals(kind)) menuList[menuCount++] = M_HIDE;
        }
        menuSignedIn = Trakt.hasOAuth();
        aboutMenu();
        Recs.Pick asked = menuPick;   // saved before the overview was kept: it's asked now, and shows when it comes
        recs.about(asked, () -> {
            if (menuPick != asked) return;
            aboutMenu();
            menuBackDirty = true;
            wake();
        });
        Integer r = rated.get(Recs.key(menuPick));
        starIndex = r == null ? 5 : r - 1;
        aimMenu(true);
        menuShot = menuSharp = null;
        handler.removeCallbacks(focusBackground);   // the screen's copy would go stale
        // The card goes to the row's left end, the menu on its right: where it already is, the menu opens at once;
        // elsewhere the row slides there first, and the screen is copied once it stops (doFrame).
        menuShift.target = menuCard < slots ? Math.max(0, cardRects[menuCard].left - MARGIN * k) : 0;
        menuWaitsRow = Math.abs(menuShift.value - menuShift.target) >= 3 * k;
        menuBackDirty = true;
        click.open();
        if (!menuWaitsRow) {
            placeMenu();
            captureBehind(++menuShotId);
        }
        wake();
    }

    /** OK held on Explore's tab (the only one with filters: Movies and Shows show their source whole). */
    private boolean canFilter() {
        return !sheetOpen && zone == Z_TOP && topIndex < TABS && TAB_KIND[topIndex] == null && picksOn();
    }

    /** Explore's filters, over the screen blurred as behind a pick's menu: ↑ on its tab, or OK `held` there. */
    private void openFilters(boolean held) {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (menuOpen || !canFilter()) return;
        if (held) okHeld = true;   // the release of this press opens nothing
        if (!Recs.canFilter()) {
            showToast(Ui.tr("Sign in to Trakt first: Free My TV › Home Settings › Trakt."));
            return;
        }
        menuOpen = menuFilters = true;
        menuPick = null;
        filterPanel.open(recs.filter(null), new String[] {Ui.tr("filter|All"), tabs()[1], tabs()[2]}, tabs()[0]);
        menuIndex = 0;
        aimMenu(true);
        menuShot = menuSharp = null;
        menuWaitsRow = false;
        handler.removeCallbacks(focusBackground);
        menuBackDirty = true;
        click.open();
        placeMenu();
        captureBehind(++menuShotId);
        wake();
    }

    /** As the filters close: Explore takes them, if they changed (Recs asks Trakt for its titles again). */
    private void applyFilters() {
        Recs.Filter f = filterPanel.draft();
        if (f.equals(recs.filter(null))) return;
        if (TAB_KIND[rowTab] == null) {
            cardIndex = 0;
            rowReset = true;
        }
        recs.setFilter(null, f, () -> showToast(Ui.tr("Trakt didn't answer. Try again in a moment.")));
        heroDirty = true;
    }

    /**
     * The screen, copied twice: first small (half the glass's image size, scaled by the GPU as it copies), blurred like
     * Backdrop's photos (~35 px at full size) and dimmed a little on another thread, then scaled up to the glass's image
     * size; then full size (it stands in for the home screen while the menu fades). The menu fades in over the blurred
     * copy, and the panel's glass shows the same copy, so it bends what is really behind it (its own Scene). On this TV
     * the small copy takes ~40 ms and its blur ~40 more, while the full one is coming (~60 ms): the menu shows ~0.1 s
     * after the row stops. Without the copies within 250 ms (PixelCopy refused or slow), over a plain dim and the
     * shelf's kind of glass.
     */
    private void captureBehind(int id) {
        handler.postDelayed(menuFallback, 250);
        shotNext = sharpNext = null;
        shotPx = null;
        int sw = Backdrop.GW / 2, sh = Backdrop.GH / 2, radius = Math.round(8 * sw / (w / 4f));
        float darkNow = dark();
        // The panel's place on the small copy (placeMenu ran just before).
        int x0 = clampInt(Math.round(menuRect.left * sw / w), 0, sw - 1), x1 = clampInt(Math.round(menuRect.right * sw / w), x0 + 1, sw);
        int y0 = clampInt(Math.round(menuRect.top * sh / h), 0, sh - 1), y1 = clampInt(Math.round(menuRect.bottom * sh / h), y0 + 1, sh);
        Bitmap small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Bitmap full = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(activity.getWindow(), small, result -> {
                if (result != PixelCopy.SUCCESS || id != menuShotId || !menuOpen) {
                    showMenu(id);
                    return;
                }
                SHOTS.execute(() -> {
                    int[] px = Backdrop.reduce(small, sw, sh, radius, 3);
                    float under = Math.max(darkNow, luma(px, sw, x0, y0, x1, y1) < DARK_UNDER ? 1 : 0);
                    int keep = Math.round(256 * (1 - (0.1f + 0.2f * under)));   // dimmed a tenth by day, 30% at night
                    for (int i = 0; i < px.length; i++) {
                        int c = px[i];
                        px[i] = 0xFF000000 | (((c >> 16) & 255) * keep >> 8) << 16 | (((c >> 8) & 255) * keep >> 8) << 8 | (c & 255) * keep >> 8;
                    }
                    Bitmap shot = Bitmap.createBitmap(px, sw, sh, Bitmap.Config.ARGB_8888);
                    Bitmap glass = Bitmap.createScaledBitmap(shot, Backdrop.GW, Backdrop.GH, true);
                    int[] glassPx = new int[Backdrop.GW * Backdrop.GH];
                    glass.getPixels(glassPx, 0, Backdrop.GW, 0, 0, Backdrop.GW, Backdrop.GH);
                    glass.recycle();
                    handler.post(() -> {
                        if (id != menuShotId || !menuOpen) return;
                        shotNext = shot;
                        shotPx = glassPx;
                        shotUnder = under;
                        copiesIn(id);
                    });
                });
                PixelCopy.request(activity.getWindow(), full, done -> {
                    if (done != PixelCopy.SUCCESS || id != menuShotId || !menuOpen) {
                        showMenu(id);
                        return;
                    }
                    sharpNext = full;
                    copiesIn(id);
                }, handler);
            }, handler);
        } catch (IllegalArgumentException e) {   // no surface yet
            showMenu(id);
        }
    }

    /** Both copies of the screen are in: the menu's glass reads the blurred one, and the fade starts. */
    private void copiesIn(int id) {
        if (shotNext == null || sharpNext == null || id != menuShotId || !menuOpen) return;
        if (menuScene == null) menuScene = new Scene(false);
        menuScene.setSize(w, h);
        menuScene.setGlassImage(shotPx);
        menuUnder = shotUnder;
        menuShot = shotNext;
        menuSharp = sharpNext;
        shotNext = sharpNext = null;
        shotPx = null;
        menuBackDirty = true;
        showMenu(id);
    }

    /** Below it, the panel's text is dark on light glass; above it, white on smoked glass. */
    private static final float DARK_UNDER = 0.45f;

    /** Mean luma (0…1, of the gamma-coded values) of the pixels in [x0, x1) × [y0, y1) of `px` (`stride` wide). */
    private static float luma(int[] px, int stride, int x0, int y0, int x1, int y1) {
        long sum = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int c = px[y * stride + x];
                sum += 54 * ((c >> 16) & 255) + 183 * ((c >> 8) & 255) + 19 * (c & 255);   // Rec. 709, out of 256
            }
        }
        return sum / (256f * 255 * (x1 - x0) * (y1 - y0));
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** How dark the menu and the filters are drawn: the night's, or the picture's under them. */
    private float menuDark() {
        return menuShot == null ? 1 : Math.max(dark(), menuUnder);
    }

    private int menuTone(int onLight, int onDark) {
        return blend(onLight, onDark, menuDark());
    }

    private float menuTone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * menuDark();
    }

    /** Starts the fade-in, once per opening (the copy or the fallback, whichever comes first). */
    private void showMenu(int id) {
        handler.removeCallbacks(menuFallback);
        if (!menuOpen || id != menuShotId || menu.target == 1 || menuWarm > 0) return;
        if (menuSharp != null) menuWarm = 2;    // see the frame: a warm-up frame first
        else menu.target = 1;
        menuDirty = true;
        wake();
        announceForAccessibility(menuLabel(menuIndex));
    }

    private void closePickMenu(boolean snap) {
        if (!menuOpen && menu.value <= 0) return;
        if (menuOpen && menuFilters) applyFilters();
        menuOpen = false;
        menuWarm = 0;
        menuWaitsRow = false;
        handler.removeCallbacks(menuFallback);
        if (running && zone == Z_CARDS) handler.post(focusBackground);
        sendRating();
        menu.target = 0;
        if (snap) {
            menu.snap(0);
            menuShift.snap(0);
            if (hideAfter != null) recs.hide(hideAfter);
            if (unlistAfter != null) recs.setWatchlisted(unlistAfter, false);
            hideAfter = unlistAfter = null;
        }
        menuDirty = true;
        wake();
    }

    private void aimMenu(boolean snap) {
        aim(menuFocus, menuIndex, snap);
        aim(starFocus, !menuFilters && menuItem(menuIndex) == M_WATCHED && menuPick != null && stars(menuPick) ? starIndex : -1, snap);
        menuDirty = true;
    }

    private String menuLabel(int i) {
        if (menuFilters) return filterPanel.label();
        int item = menuItem(i);
        if (item == M_TRAILER) return Ui.tr("Watch trailer");
        if (item == M_HIDE) return Ui.tr("Don't recommend");
        if (item == M_WATCHLIST) {
            return menuPick != null && recs.watchlisted(menuPick) ? Ui.tr("Remove from Watchlist") : Ui.tr("Add to Watchlist");
        }
        return menuPick != null && stars(menuPick) ? Ui.tr("Rate it on Trakt") : Ui.tr("Watched");
    }

    /** Keys while the menu is open: ↑ ↓ between its items, ← → along the stars, OK, and Back closes it. */
    private boolean menuKey(KeyEvent e, boolean down) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            // ≡ again closes what it opened (the filters as Back does: their titles show).
            if (down && e.getRepeatCount() == 0) {
                closePickMenu(false);
                click.move();
            }
            return true;
        }
        if (menuFilters) return filterPanel.key(e, down);
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    int next = Math.max(0, Math.min(menuItems() - 1, menuIndex + (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)));
                    if (next != menuIndex) {
                        menuIndex = next;
                        aimMenu(false);
                        click.move();
                        announceForAccessibility(menuLabel(menuIndex));
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && menuItem(menuIndex) == M_WATCHED && stars(menuPick)) {
                    int next = Math.max(0, Math.min(starFocus.length - 1, starIndex + (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)));
                    if (next != starIndex) {
                        starIndex = next;
                        aimMenu(false);
                        click.move();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // On release, as elsewhere; the release of the press that opened the menu (okHeld) chooses nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        okHeld = false;
                    }
                } else {
                    if (okDown && !okHeld && !e.isCanceled()) menuSelect();
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        closePickMenu(false);
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void menuSelect() {
        Recs.Pick p = menuPick;
        if (p == null) return;
        int item = menuItem(menuIndex);
        if (item == M_TRAILER) {
            click.open();
            closePickMenu(true);
            openTrailer(p);
            return;
        }
        if (!menuSignedIn) {
            showToast(Ui.tr("Sign in to Trakt first: Free My TV › Home Settings › Trakt."));
            return;
        }
        if (item == M_HIDE) {
            hidePick(p);
            return;
        }
        if (item == M_WATCHLIST) {
            toggleWatchlist(p);
            return;
        }
        String key = Recs.key(p);
        click.open();
        if (!stars(p)) {
            watched.add(key);       // once: from now on OK rates
            markWatched(p);
        } else {
            rated.put(key, starIndex + 1);   // that many of ten: Trakt's scale as it is
            ratePick = p;
            handler.removeCallbacks(rateNow);
            handler.postDelayed(rateNow, RATE_AFTER_MS);
        }
        aimMenu(false);
    }

    /** YouTube's results for the title's trailer, in the TV's language (in Russian: "<название> русский трейлер"). */
    private void openTrailer(Recs.Pick p) {
        String url = "https://www.youtube.com/results?search_query=" + Uri.encode(Ui.trf("{title} trailer", "title", p.title));
        RectF r = menuCard < slots ? cardRects[menuCard] : screen;
        if (HomeApps.open(activity, HomeApps.app(YOUTUBE, "YouTube", url), this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()))) return;
        try {   // no YouTube for TV: whichever app takes YouTube's links
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            showToast(Ui.trf("Couldn't open {app}.", "app", "YouTube"));
        }
    }

    /** Sent once (`watched` keeps a second press from sending it again); if Trakt refuses, the item comes back. */
    private void markWatched(Recs.Pick p) {
        String key = Recs.key(p), kind = p.kind, title = p.title;
        int id = p.id;
        LOADER.execute(() -> {
            try {
                Trakt.markWatched(kind, id);
                handler.post(() -> {
                    recs.seen(p);
                    showToast(Ui.trf("Marked as watched on Trakt: {title}", "title", title));
                });
            } catch (Exception e) {
                AppLog.w("Trakt watched: " + e.getMessage());
                handler.post(() -> {
                    watched.remove(key);
                    rated.remove(key);
                    if (ratePick != null && Recs.key(ratePick).equals(key)) {
                        ratePick = null;
                        handler.removeCallbacks(rateNow);
                    }
                    showToast(Ui.tr("Trakt didn't answer. Try again in a moment."));
                    aimMenu(false);
                });
            }
        });
    }

    /** The stars chosen, to Trakt (its 1 to 10), once: never again while they're the ones already sent. */
    private void sendRating() {
        handler.removeCallbacks(rateNow);
        Recs.Pick p = ratePick;
        ratePick = null;
        if (p == null) return;
        String key = Recs.key(p), kind = p.kind, title = p.title;
        Integer stars = rated.get(key);
        if (stars == null || stars.equals(ratedSent.get(key))) return;
        ratedSent.put(key, stars);
        int id = p.id, value = stars;
        String shown = starsText(stars);
        LOADER.execute(() -> {
            try {
                Trakt.rate(kind, id, value);
                handler.post(() -> showToast(Ui.trf("Rated {stars} of 10 on Trakt: {title}", "stars", shown, "title", title)));
            } catch (Exception e) {
                AppLog.w("Trakt rating: " + e.getMessage());
                handler.post(() -> {
                    if (stars.equals(ratedSent.get(key))) ratedSent.remove(key);   // the same stars may go again
                    showToast(Ui.tr("Trakt didn't answer. Try again in a moment."));
                });
            }
        });
    }

    private static String starsText(int stars) {
        return java.text.NumberFormat.getIntegerInstance(Ui.locale()).format(stars);
    }

    /**
     * "Don't recommend": out of Trakt's recommendations for good (its hidden list), and out of the row once the menu has
     * faded; if Trakt refuses, it stays, with a toast. One request per title.
     */
    private void hidePick(Recs.Pick p) {
        if (!hiding.add(Recs.key(p))) return;
        click.open();
        hideAfter = p;
        closePickMenu(false);
        String title = p.title, kind = p.kind;
        int id = p.id;
        LOADER.execute(() -> {
            try {
                Trakt.hideRecommendation(kind, id);
                handler.post(() -> showToast(Ui.trf("Won't be recommended again: {title}", "title", title)));
            } catch (Exception e) {
                AppLog.w("Trakt hide: " + e.getMessage());
                handler.post(() -> {
                    hiding.remove(Recs.key(p));
                    if (hideAfter == p) hideAfter = null;
                    recs.unhide(p);
                    showToast(Ui.tr("Trakt didn't answer. Try again in a moment."));
                });
            }
        });
    }

    /**
     * On the person's Trakt watchlist, or off it: here at once (the item says Remove or Add), then Trakt (one request at
     * a time per title); if Trakt refuses, it goes back, with a toast (a full watchlist says so). Taken off from the
     * Watchlist tab, the menu closes and the card leaves the row once it has faded.
     */
    private void toggleWatchlist(Recs.Pick p) {
        String key = Recs.key(p), kind = p.kind, title = p.title;
        if (!listing.add(key)) return;
        int id = p.id;
        boolean add = !recs.watchlisted(p);
        click.open();
        if (!add && Recs.WATCHLIST.equals(rowKind())) {
            unlistAfter = p;
            closePickMenu(false);
        } else {
            recs.setWatchlisted(p, add);
            menuDirty = true;
            announceForAccessibility(menuLabel(menuIndex));
        }
        LOADER.execute(() -> {
            try {
                Trakt.watchlist(kind, id, add);
                handler.post(() -> {
                    listing.remove(key);
                    showToast(add ? Ui.trf("Added to your Trakt watchlist: {title}", "title", title)
                            : Ui.trf("Removed from your Trakt watchlist: {title}", "title", title));
                });
            } catch (Exception e) {
                AppLog.w("Trakt watchlist: " + e.getMessage());
                boolean full = e.getMessage() != null && e.getMessage().contains("HTTP 420");
                handler.post(() -> {
                    listing.remove(key);
                    if (unlistAfter == p) unlistAfter = null;
                    else recs.setWatchlisted(p, !add);
                    menuDirty = true;
                    showToast(full ? Ui.tr("Your Trakt watchlist is full.") : Ui.tr("Trakt didn't answer. Try again in a moment."));
                });
            }
        });
    }

    private boolean menuCardShown() {
        return menuCard < slots && menuCard < cards.size() && cards.get(menuCard) == menuPick;
    }

    /**
     * Where the panel goes: beside the card (as drawn focused, 5% larger), on its right if there's room, else on its
     * left; level with its top. Its two layers take the panel's place, with room for its shadow, and grow from the card's
     * side.
     */
    private void placeMenu() {
        if (menuFilters) {
            placeFilters();
            return;
        }
        boolean card = menuCardShown();
        int items = menuItems();
        float pw = MENU_W * k, ph = items * MENU_ITEM_H * k + (items - 1) * MENU_GAP * k + 2 * MENU_PAD * k;
        float bw = card ? cardW[menuCard] : wideW, cx = card ? cardX[menuCard] + bw / 2 : w / 2f, half = bw * 1.05f / 2;
        float top = L_CARDS_TOP * k + L_CARD_H * k * (0.5f - 1.05f / 2) - 6 * k;
        menuRight = cx + half + 36 * k + pw <= w - 60 * k;
        float left = menuRight ? cx + half + 36 * k : Math.max(60 * k, cx - half - 36 * k - pw);
        menuRect.set(left, top, left + pw, top + ph);
        snap(menuRect);
        int m = Math.round(60 * k);
        menuBounds.set(Math.round(menuRect.left) - m, Math.round(menuRect.top) - m, Math.round(menuRect.right) + m,
                Math.round(menuRect.bottom) + 2 * m);
        for (View v : new View[] {menuGlass, menuItemsLayer}) {
            measure(v, menuBounds.width(), menuBounds.height());
            v.layout(menuBounds.left, menuBounds.top, menuBounds.right, menuBounds.bottom);
            v.setPivotX(menuRight ? m : menuBounds.width() - m);
            v.setPivotY(m);
        }
    }

    /** The filters: FilterPanel's panel, in the middle of the screen; it grows from its center. */
    private void placeFilters() {
        menuRect.set(filterPanel.place(w, h));
        int m = Math.round(60 * k);
        menuBounds.set(Math.round(menuRect.left) - m, Math.round(menuRect.top) - m, Math.round(menuRect.right) + m,
                Math.round(menuRect.bottom) + 2 * m);
        for (View v : new View[] {menuGlass, menuItemsLayer}) {
            measure(v, menuBounds.width(), menuBounds.height());
            v.layout(menuBounds.left, menuBounds.top, menuBounds.right, menuBounds.bottom);
            v.setPivotX(menuBounds.width() / 2f);
            v.setPivotY(menuBounds.height() / 2f);
        }
    }

    /** Behind the menu: the screen blurred and dimmed, and the card it belongs to sharp where it was. */
    private void drawMenuBack(Canvas c) {
        if (menuPick == null && !menuFilters) return;
        if (menuShot != null) {
            c.drawBitmap(menuShot, null, screen, shotPaint);
        } else {
            fill.setShader(null);
            fill.setColor(0x8C000000);
            c.drawRect(screen, fill);
        }
        if (menuCardShown()) {
            cardOverMenu = true;
            drawLightCard(c, menuCard);
            cardOverMenu = false;
            drawAbout(c);
        }
    }

    /**
     * The menu's card's words (drawAbout), made as it opens: its title, cut to the card (unless the card writes it), and
     * its whole story: in the card's width; longer, across the screen under the card and the menu (the panel ends far
     * above); longer still, smaller. Cut only if even that can't hold it, down to the screen's bottom.
     */
    private void aboutMenu() {
        Recs.Pick p = menuPick;
        aboutLine = "";
        aboutLayout = null;
        if (p == null || aboutTitle == null) return;
        String title = p.title == null ? "" : p.title.trim(), about = p.about == null ? "" : p.about;
        // The title in words: the cards never write it (a logo, when there is one, is on the photo).
        aboutLine = TextUtils.ellipsize(title, aboutTitle, wideW, TextUtils.TruncateAt.END).toString();
        float metaBase = (L_CARDS_TOP + L_CARD_H + 46) * k;
        aboutTitleBase = metaBase + 58 * k;
        aboutTop = aboutLine.isEmpty() ? metaBase + 22 * k : aboutTitleBase + 18 * k;
        if (about.isEmpty()) return;
        float room = h - 36 * k - aboutTop, full = (CONTENT_R - MARGIN) * k;
        float[][] tries = {{25, wideW}, {25, full}, {22, full}, {20, full}};   // text size, width
        for (float[] t : tries) {
            aboutText.setTextSize(t[0] * k);   // the layout draws with it: left at the one that fits
            aboutLayout = story(about, t[1], Integer.MAX_VALUE);
            if (aboutLayout.getHeight() <= room) return;
        }
        int lines = (int) (room / ((float) aboutLayout.getHeight() / aboutLayout.getLineCount()));
        aboutLayout = story(about, full, Math.max(1, lines));
    }

    private StaticLayout story(String text, float width, int lines) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), aboutText, Math.max(1, Math.round(width)))
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(aboutText.getTextSize() * 0.36f, 1).setIncludePad(false).build();
    }

    /**
     * Under the menu's card and its details, in the menu's ink: its age rating in a frame at the end of the details (as
     * the Apple TV app has it), its title in words (the logo on the card may be another language's or hard to read;
     * a card without a logo already writes it), then its whole story (aboutMenu). Drawn with the dimmed screen behind
     * the menu, so it fades in with it.
     */
    private void drawAbout(Canvas c) {
        Recs.Pick p = menuPick;
        CardText t = menuCard < cardWords.length ? cardWords[menuCard] : null;
        if (p == null || t == null) return;
        float left = cardX[menuCard], bottom = (L_CARDS_TOP + L_CARD_H) * k, metaBase = bottom + 46 * k;
        int ink = menuTone(L_INK, WHITE);
        String age = p.age == null ? "" : p.age;
        if (!age.isEmpty()) {
            float x = left + t.ratingW + (t.meta.isEmpty() ? 0 : lCardMeta.measureText(t.meta) + 18 * k);
            float pad = 9 * k, boxH = 31 * k, cy = metaBase - Ui.halfCap(lCardMeta);
            tmp.set(x, cy - boxH / 2, x + ageText.measureText(age) + 2 * pad, cy + boxH / 2);
            if (tmp.right <= left + wideW) {
                ageRim.setStrokeWidth(2 * k);
                ageRim.setColor(Ui.alpha(ink, 0.75f));
                c.drawRoundRect(tmp, 7 * k, 7 * k, ageRim);
                ageText.setColor(Ui.alpha(ink, 0.9f));
                c.drawText(age, x + pad, cy + Ui.halfCap(ageText), ageText);
            }
        }
        if (!aboutLine.isEmpty()) {
            aboutTitle.setColor(ink);
            c.drawText(aboutLine, left, aboutTitleBase, aboutTitle);
        }
        if (aboutLayout == null) return;
        aboutText.setColor(Ui.alpha(ink, 0.8f));
        c.save();
        c.translate(left, aboutTop);
        aboutLayout.draw(c);
        c.restore();
    }

    /**
     * The panel: Liquid Glass over what is really behind it (the menu's blurred copy of the screen), bending it near the
     * rim and catching the light there; a soft shadow under it. Smoked and nearly clear when dark (menuDark); light, a
     * frosted white, so its dark text and chips never sit on a dark photo showing through.
     */
    private void drawMenuGlass(Canvas c) {
        float radius = (menuFilters ? FilterPanel.RADIUS : MENU_ITEM_H / 2 + MENU_PAD) * k;
        tmp.set(menuRect);
        tmp.offset(0, 18 * k);
        shadow.setColor(menuTone(0x14000000, 0x40000000));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        boolean behind = menuShot != null && menuScene != null;
        (behind ? menuScene : scene).liquid(c, menuRect, radius, menuTone(0xFFFFFFFF, SMOKE), behind ? menuTone(0.6f, 0.1f) : menuTone(0.42f, 0.12f),
                menuTone(0.04f, 0.06f), menuTone(30f, 34f) * k, menuTone(22f, 28f) * k, menuTone(0.65f, 0.95f), 1);
    }

    /** The items, focused the tvOS way (a white pill); the second one, once watched, ten stars. */
    private void drawMenuItems(Canvas c) {
        if (menuFilters) {
            filterPanel.draw(c, menuDark());
            return;
        }
        if (menuPick == null) return;
        float ih = MENU_ITEM_H * k, pad = MENU_PAD * k, gap = MENU_GAP * k;
        for (int i = 0; i < menuItems(); i++) {
            float f = Ui.clamp(menuFocus[i].value), y = menuRect.top + pad + i * (ih + gap);
            cell.set(menuRect.left + pad, y, menuRect.right - pad, y + ih);
            if (f > 0.01f) {
                tmp.set(cell);
                tmp.offset(0, 6 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, menuTone(0.12f, 0.3f) * f));
                shadows.draw(c, tmp, ih / 2, SHADOW_BLUR * k, shadow);
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawRoundRect(cell, ih / 2, ih / 2, fill);
            }
            int item = menuItem(i);
            int ink = item != M_TRAILER && !menuSignedIn ? menuTone(L_FAINT, 0xFF8E8E93) : blend(menuTone(L_INK, WHITE), L_INK, f);
            float cy = cell.centerY(), textX = cell.left + 84 * k;
            String icon = item == M_TRAILER ? "play" : item == M_WATCHED ? "check" : item == M_HIDE ? "hide"
                    : recs.watchlisted(menuPick) ? "bookmark_remove" : "bookmark_add";
            Symbols.draw(getContext(), c, icon, cell.left + 46 * k, cy, 30 * k, ink);
            if (item == M_WATCHED && stars(menuPick)) {
                drawStars(c, textX, cy, ink);
            } else {
                menuText.setColor(ink);
                c.drawText(menuLabel(i), textX, cy + Ui.halfCap(menuText), menuText);
            }
        }
    }

    /**
     * Ten stars from x: filled up to the rating, the focused one larger; at the end, what OK on the focused one gives, as
     * Trakt's site says it: its number and its word ("8 · Great"), so a 10-point scale reads as what it means.
     */
    private void drawStars(Canvas c, float x, float cy, int ink) {
        Integer value = rated.get(Recs.key(menuPick));
        int stars = value == null ? 0 : value;
        float size = 26 * k, step = 34 * k;
        for (int j = 0; j < starFocus.length; j++) {
            float g = Ui.clamp(starFocus[j].value), sz = size * (1 + 0.3f * g);
            boolean have = j < stars;
            c.save();
            c.translate(x + size / 2 + j * step, cy);
            c.scale(sz, sz);
            if (have) {
                starPaint.setColor(0xFFFFCC00);
                c.drawPath(star, starPaint);
            }
            starLine.setColor(have ? 0xFFFFCC00 : Ui.alpha(ink, 0.75f));
            c.drawPath(star, starLine);
            c.restore();
        }
        String number = starsText(starIndex + 1), word = ratingWord(starIndex + 1);
        float left = x + (starFocus.length - 1) * step + size + 22 * k, end = cell.right - 18 * k;   // cell: the item's pill
        menuText.setColor(ink);
        c.drawText(number, left, cy + Ui.halfCap(menuText), menuText);
        left += menuText.measureText(number) + 12 * k;
        // A long word (another language's) gets smaller rather than cut: this is redrawn only while the focus moves.
        float size0 = menuHint.getTextSize(), ww = menuHint.measureText(word);
        if (ww > end - left) menuHint.setTextSize(size0 * Math.max(0.6f, (end - left) / ww));
        menuHint.setColor(Ui.alpha(ink, 0.7f));
        c.drawText(word, left, cy + Ui.halfCap(menuHint), menuHint);
        menuHint.setTextSize(size0);
    }

    /** Trakt's words for its 1 to 10, as its site shows them beside the hearts. */
    private static String ratingWord(int value) {
        switch (value) {
            case 1: return Ui.tr("rating|Weak Sauce :(");
            case 2: return Ui.tr("rating|Terrible");
            case 3: return Ui.tr("rating|Bad");
            case 4: return Ui.tr("rating|Poor");
            case 5: return Ui.tr("rating|Meh");
            case 6: return Ui.tr("rating|Fair");
            case 7: return Ui.tr("rating|Good");
            case 8: return Ui.tr("rating|Great");
            case 9: return Ui.tr("rating|Superb");
            default: return Ui.tr("rating|Totally Ninja!");
        }
    }

    // MARK: - Moving apps on the shelf

    private boolean canReorder() {
        if (sheetOpen || zone != Z_SHELF || shelfIndex >= shelf.size() || shelf.size() < 2) return false;
        HomeApps.Item it = shelf.get(shelfIndex);
        return it.kind == HomeApps.APP && it.pkg != null;
    }

    private void startReorder() {
        handler.removeCallbacks(holdStart);
        holdArmed = false;
        if (reordering || !canReorder()) return;
        wake();
        reordering = reorderAnim = okHeld = true;
        reorderOrigin = new ArrayList<>(shelf);
        liftIndex = shelfIndex;
        reorderAt = SystemClock.uptimeMillis();
        moveLift.target = 1;
        click.open();
        showToast(Ui.tr("← → to move · OK to drop · Back to cancel"));
        retarget(false);    // TalkBack reads "Moving <app>, position i of n"
    }

    /** Keys while moving an app: nothing else on the home screen gets them (system keys still go to the system). */
    private boolean reorderKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shiftReorder(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // The press that lifted the app is still held: its repeats and release do nothing; a new press drops it.
                if (down && e.getRepeatCount() == 0) {
                    okDown = okHeld = false;
                    dropReorder();
                } else if (!down) {
                    okDown = okHeld = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) {
                        cancelReorder(false);
                        click.move();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    /** Swaps the lifted app with its neighbor; both slide from where they are to their new slots. */
    private void shiftReorder(int dx) {
        int i = shelfIndex, j = i + dx;
        if (j < 0 || j >= shelf.size()) return;
        HomeApps.Item it = shelf.get(i);
        shelf.set(i, shelf.get(j));
        shelf.set(j, it);
        Spring f = tileFocus[i];
        tileFocus[i] = tileFocus[j];
        tileFocus[j] = f;
        Spring s = tileSlide[i];
        tileSlide[i] = tileSlide[j];
        tileSlide[j] = s;
        float d = tileRects[i].left - tileRects[j].left;
        tileSlide[j].value += d;
        tileSlide[i].value -= d;
        if (tileShaders.length == shelf.size()) {
            LinearGradient g = tileShaders[i];
            tileShaders[i] = tileShaders[j];
            tileShaders[j] = g;
        }
        shelfIndex = liftIndex = j;
        reorderAnim = true;
        wake();
        retarget(false);
        click.move();
    }

    /** OK: drops the app where it is and saves the shelf's new order. */
    private void dropReorder() {
        if (!reordering) return;
        reordering = false;
        moveLift.target = 0;
        boolean changed = false;
        for (int i = 0; i < shelf.size() && !changed; i++) changed = shelf.get(i) != reorderOrigin.get(i);
        reorderOrigin = null;
        click.open();
        retarget(false);
        if (!changed) return;
        final List<String> order = new ArrayList<>();
        for (HomeApps.Item it : shelf) order.add(it.pkg);
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            // The saved list may hold more than the shelf shows (apps not installed, past the limit): the shown ones
            // take their new order in the same slots, the others stay where they were.
            List<String> all = HomeApps.shelfPackages(c), out = new ArrayList<>(all);
            int next = 0;
            for (int s = 0; s < out.size() && next < order.size(); s++) {
                if (order.contains(out.get(s))) out.set(s, order.get(next++));
            }
            if (next < order.size()) {
                out = new ArrayList<>(order);
                for (String p : all) if (!order.contains(p)) out.add(p);
            }
            final List<String> save = out;
            handler.post(() -> Prefs.setShelf(save));
        });
    }

    /** Back, or leaving the home screen: the original order comes back (sliding, unless `snap`). */
    private void cancelReorder(boolean snap) {
        if (!reordering && !reorderAnim) return;
        if (reordering) {
            reordering = false;
            moveLift.target = 0;
            int n = shelf.size();
            if (reorderOrigin != null && reorderOrigin.size() == n) {
                HomeApps.Item lifted = shelf.get(shelfIndex);
                Spring[] focus = new Spring[n], slide = new Spring[n];
                LinearGradient[] shaders = tileShaders.length == n ? new LinearGradient[n] : tileShaders;
                for (int a = 0; a < n; a++) {
                    int b = shelf.indexOf(reorderOrigin.get(a));
                    focus[a] = tileFocus[b];
                    slide[a] = tileSlide[b];
                    slide[a].value += tileRects[b].left - tileRects[a].left;
                    if (shaders != tileShaders) shaders[a] = tileShaders[b];
                }
                shelf = reorderOrigin;
                tileFocus = focus;
                tileSlide = slide;
                tileShaders = shaders;
                shelfIndex = liftIndex = shelf.indexOf(lifted);
            }
            reorderOrigin = null;
            retarget(snap);
        }
        if (snap) {
            moveLift.snap(0);
            for (Spring s : tileSlide) s.snap(0);
            wiggle = 0;
            liftIndex = -1;
            reorderAnim = false;
            tilesDirty = true;
        } else {
            wake();
        }
    }

    /** One frame of the move: the slides, the lift and the wiggle. Returns true while any of them moves. */
    private boolean stepReorder(float dt, long up) {
        boolean moving = step(tileSlide, dt) | moveLift.step(dt);
        if (reordering || moveLift.value > 0.001f) {
            wiggle = WIGGLE_DEG * (float) Math.sin((up - reorderAt) * (2 * Math.PI / WIGGLE_MS));
            moving = true;
        } else {
            wiggle = 0;
        }
        reorderAnim = moving;
        return moving;
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    /** The drawer's weather (0) and quotes (1) cards: read by TalkBack, never focused by the remote. */
    private static final int GRID = 9, WIDGETS = 8;

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (sheetOpen) {
            for (int i = 0; i < others.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        if (searchOn()) out.add(a11yId(Z_SEARCH, 0));
        for (int i = picksOn() && !searchOpen ? 0 : TABS; i < TABS + lightButtons.size(); i++) out.add(a11yId(Z_TOP, i));
        for (int i = 0; i < cards.size(); i++) out.add(a11yId(Z_CARDS, i));
        for (int i = shelfFirst; i < Math.min(shelf.size(), shelfFirst + shelfVisible); i++) out.add(a11yId(Z_SHELF, i));
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
        if (drawerOpen && Prefs.weather()) out.add(a11yId(WIDGETS, 0));
        if (drawerOpen && Prefs.quotes() && HomeData.active().length > 0) out.add(a11yId(WIDGETS, 1));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + Ui.trf("{i} of {n}", "i", i + 1, "n", others.size());
            case Z_TOP:
                if (i >= TABS) return label(lightButtons, i - TABS);
                return Ui.trf("{name}, tab {i} of {n}", "name", tabs()[i], "i", i + 1, "n", TABS);
            case Z_CARDS: return i < cards.size() ? pickText(cards.get(i)) : "";
            case Z_SEARCH:
                if (!searchOpen) return Ui.tr("Search");
                if (onMic) return Ui.tr("Voice search");
                return query.isEmpty() ? Ui.tr("Search field") : Ui.trf("Search field: {text}", "text", query);
            case Z_SHELF:
                if (reordering && i == shelfIndex) {
                    return Ui.trf("Moving {app}, position {n} of {total}",
                            "app", label(shelf, i), "n", i + 1, "total", shelf.size());
                }
                return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            case WIDGETS: return i == 0 ? weatherText() : quotesText();
            default: return "";
        }
    }

    /**
     * Where the item is now. Items off screen (the closed drawer, the sheet's hidden rows, the top while the drawer is
     * open) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case GRID: {
                if (!sheetOpen || i >= others.size()) return false;
                float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
                float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k;
                float y = (GRID_TOP + (i / COLUMNS - scroll.target) * GRID_ROW) * k;
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i >= TABS + lightButtons.size()) return false;
                a11yRect.set(topRects[i]);
                dy = (i < TABS ? heroLayer : status).getTranslationY();
                break;
            case Z_CARDS:
                if (i >= cards.size()) return false;
                a11yRect.set(cardRects[i]);
                dy = heroLayer.getTranslationY();
                break;
            case Z_SEARCH:
                a11yRect.set(searchOpen ? fieldRect : searchRect);
                dy = heroLayer.getTranslationY();
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            case Z_DRAWER:
                if (i >= drawerRects.length) return false;
                a11yRect.set(drawerRects[i]);
                dy = drawerOpen ? 0 : h;
                break;
            case WIDGETS:
                if (!drawerOpen) return false;
                a11yRect.set(i == 0 ? weatherCard : quotesCard);
                dy = 0;
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        if (sheetOpen) return others.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        switch (zone) {
            case Z_TOP: return a11yId(Z_TOP, topIndex);
            case Z_CARDS: return cardIndex < cards.size() ? a11yId(Z_CARDS, cardIndex) : -1;
            case Z_SEARCH: return a11yId(Z_SEARCH, 0);
            case Z_SHELF: return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case Z_DRAWER: return drawerItems.isEmpty() ? -1 : a11yId(Z_DRAWER, drawerIndex);
            case WIDGETS: return a11yId(WIDGETS, widgetIndex);
            default: return -1;
        }
    }

    /** The drawer's widgets that are on (0 weather, 1 quotes), with the drawer open. */
    private List<Integer> widgets() {
        List<Integer> out = new ArrayList<>();
        if (!drawerOpen) return out;
        if (Prefs.weather()) out.add(0);
        if (Prefs.quotes() && HomeData.active().length > 0) out.add(1);
        return out;
    }

    /** A spoken-feedback service (TalkBack) is on. */
    private boolean talkBack() {
        AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private void a11yFocus(int id) {
        wake();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == GRID) {
            if (!sheetOpen || i >= others.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        if (sheetOpen || reordering) return;
        switch (group) {
            case Z_DRAWER:
                if (i >= drawerItems.size()) return;
                if (!drawerOpen) openDrawer();
                drawerIndex = i;
                break;
            case WIDGETS:
                if (!widgets().contains(i)) return;
                widgetIndex = i;
                break;
            case Z_TOP:
                if (i >= TABS + lightButtons.size() || (i < TABS && (!picksOn() || searchOpen))) return;
                if (drawerOpen) closeDrawer();
                topIndex = i;
                if (i < TABS) selectTab(i);
                break;
            case Z_SEARCH:
                if (!searchOn()) return;
                if (drawerOpen) closeDrawer();
                break;
            case Z_CARDS:
                if (i >= cards.size()) return;
                if (drawerOpen) closeDrawer();
                cardIndex = i;
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                shelfIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    /** A recommendation: title, details and the app that plays it. */
    private String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        int fresh = recs.newEpisodes(p);
        if (fresh > 0) s.append(", ").append(Ui.plural("plural|one:{n} new episode|other:{n} new episodes", fresh));
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    /** The weather card as TalkBack reads it: what it shows, in the same words and units. */
    private String weatherText() {
        StringBuilder s = new StringBuilder(Prefs.city().isEmpty() ? Ui.tr("Weather") : Prefs.city());
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) return s.append(". ").append(Ui.tr("No weather data right now.")).toString();
        s.append(". ").append(HomeData.degrees(wx.temp)).append(", ").append(HomeData.condition(wx.code, wx.isDay)).append(". ")
                .append(Ui.trf("High {max}, low {min}.", "max", HomeData.degrees(wx.max), "min", HomeData.degrees(wx.min)));
        if (Prefs.forecast().equals("days") && !wx.days.isEmpty()) {   // as the card: the hours until there are days
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < wx.days.size() && i < 5; i++) {
                // "Monday: High 25°, low 18°; Tuesday: …": the whole day ("Sun" and "Sat" are words of their own), and
                // which number is the high (the sentence's own end dropped).
                String range = Ui.trf("High {max}, low {min}.", "max", HomeData.degrees(wx.dayMax.get(i)),
                        "min", HomeData.degrees(wx.dayMin.get(i))).replaceAll("[.。।]$", "");
                if (i > 0) list.append("; ");
                list.append(HomeData.weekdayName(wx.days.get(i))).append(": ").append(range);
            }
            s.append(' ').append(Ui.trf("Next days: {list}.", "list", list));
        } else if (!wx.hours.isEmpty()) {
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                if (i > 0) list.append(", ");
                list.append(HomeData.hour(wx.hours.get(i))).append(' ').append(HomeData.degrees(wx.hourTemp.get(i)));
            }
            s.append(' ').append(Ui.trf("Next hours: {list}.", "list", list));
        }
        if (wx.sunrise != 0) s.append(' ').append(Ui.trf("talkback|Sunrise {time}", "time", Ui.time(wx.sunrise))).append('.');
        if (wx.sunset != 0) s.append(' ').append(Ui.trf("Sunset {time}", "time", Ui.time(wx.sunset))).append('.');
        return s.toString();
    }

    /** The quotes card as TalkBack reads it: each coin with its price and 24 h change. */
    private String quotesText() {
        StringBuilder s = new StringBuilder(Ui.tr("Crypto"));
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        s.append(", ").append(stale.isEmpty() ? Ui.tr("24h change") : Ui.trf("quotes|{ago}", "ago", stale)).append('.');
        for (int i : HomeData.active()) {
            s.append(' ').append(HomeData.coinName(i)).append(' ').append(HomeData.brl(data.prices[i]));
            if (!Double.isNaN(data.changes[i])) s.append(", ").append(HomeData.change(data.changes[i]));
            s.append('.');
        }
        return s.toString();
    }

    // MARK: - Light design: tabs, cards and icon-only buttons

    private void layoutLight() {
        if (lTab == null) return;
        // Text tabs; the focus pill extends 26 px past the text (the first tab's text starts at the margin).
        float pad = 26 * k, tabTop = L_TABBAR_TOP * k, tabBottom = (L_TABBAR_TOP + L_TABBAR_H) * k;
        float x = MARGIN * k - pad;
        // Before them, the search's magnifier: a glass circle as tall as a tab's pill, where the first tab's pill was, and
        // as far from it as the round buttons are from each other. The tabs' pills sit 4 apart (only one ever shows),
        // but the magnifier always shows: a focused tab's pill, 5% larger, would touch it.
        searchRect.set(x, tabTop, x + L_TABBAR_H * k, tabBottom);
        snap(searchRect);
        if (searchOn()) x = searchRect.right + L_BUTTON_GAP * k;
        for (int i = 0; i < TABS; i++) {
            float tw = Math.max(lTabOn.measureText(tabs()[i]), lTab.measureText(tabs()[i]));
            topRects[i].set(x, tabTop, x + tw + 2 * pad, tabBottom);
            snap(topRects[i]);
            x = topRects[i].right + 4 * k;
        }
        float b = L_BUTTON * k, right = CONTENT_R * k, cy = L_BUTTON_CY * k;
        for (int i = lightButtons.size() - 1; i >= 0; i--) {
            topRects[TABS + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + L_BUTTON_GAP * k;
        }
        clipLayers();
        // The open field grows from the magnifier, short of the round buttons (and the quotes or the sleep timer before them).
        float limit = (lightButtons.isEmpty() ? CONTENT_R * k : topRects[TABS].left) - 60 * k;
        fieldRect.set(searchRect.left, tabTop, Math.min(searchRect.left + SEARCH_FIELD_W * k, limit), tabBottom);
        snap(fieldRect);
        cutQuery();
        // The row: the wide card spans what two tall ones leave (three cards fill the 5-column grid exactly).
        cardGap = L_CARD_GAP * k;
        tallW = L_TALL_W * k;
        wideW = (CONTENT_R - MARGIN) * k - 2 * tallW - 2 * cardGap;
        float bh = L_CARD_H * k;
        // The focused card's glare and rim (the dock icons' glass edge), made once at the wide card's size (never per
        // frame while it widens).
        rowSheen = glassSheen(wideW, bh);
        rowRim = glassLight(wideW, bh);
        star.reset();
        for (int i = 0; i < 10; i++) {   // five points, outer radius 0.5
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float rr = i % 2 == 0 ? 0.5f : 0.2f;
            if (i == 0) star.moveTo((float) Math.cos(a) * rr, (float) Math.sin(a) * rr);
            else star.lineTo((float) Math.cos(a) * rr, (float) Math.sin(a) * rr);
        }
        star.close();
        for (CardText t : cardWords) if (t != null) t.generation = -1;   // cut again at the new size
        aimRow(true);
        tabPillX.snap(topRects[tab].left);
        tabPillW.snap(topRects[tab].width());
        for (HomeApps.Item it : lightButtons) {
            Symbols.warm(getContext(), it.symbol, 27 * k);
            Symbols.warm(getContext(), it.symbol, 27 * k * 1.1f);
        }
        Symbols.warmWeather(getContext(), 40 * k);
        heroDirty = statusDirty = true;
    }

    /**
     * The current tab's row (Explore, Movies, Shows): its cards, plus empty ones while more are being looked up, and
     * the background, from the focused card. Cards added at the end slide in; a different list takes its place at once
     * (another tab's fades in: swapRow).
     */
    private void updateCards() {
        String kind = rowKind();
        List<Recs.Pick> list = recs.now(kind);
        boolean grew = list.size() >= cards.size() && samePicks(cards, list.subList(0, cards.size()));
        boolean changed = !samePicks(list, cards);
        int gone = changed ? goneAt(cards, list) : -1;
        if (changed) cards = list;
        int n = recs.loading(kind) ? Math.max(list.size(), recs.shown(kind)) : list.size();
        boolean reset = rowReset || (changed && !grew && gone < 0);
        // One card gone (hidden from its menu): the row stays, and the cards from its place on fade in where they are.
        for (int i = Math.max(0, gone); gone >= 0 && i < imageAt.length; i++) imageAt[i] = 0;
        rowReset = false;
        if (n != slots || reset) setSlots(n, reset);
        long now = SystemClock.uptimeMillis();
        preload();
        for (int i = 0; i < cards.size(); i++) {
            Recs.Pick p = cards.get(i);
            cutText(i, p);
            // Only the cards around the focus load their images (the others, when the row gets to them): three back, so the
            // ones coming in from the left are ready too.
            Backdrop b = i >= cardIndex - 3 && i <= cardIndex + 4 ? recs.backdrop(p) : recs.ready(p);
            if (b == null) {
                imageAt[i] = 0;
            } else if (imageAt[i] == 0) {
                imageAt[i] = reset ? 1 : now;   // a photo that arrives fades in; one already there just shows
                if (!reset) fadingUntil = now + IMAGE_FADE_MS;
            }
        }
        // The other rows' first cards too: switching tabs is instant. Not while searching: the answers' photos have the
        // room (Recs keeps 12 ready), and the tab's come back with it.
        otherFirst.clear();
        for (String other : searchOpen ? new String[0] : TAB_KIND) {
            if (other == kind) continue;
            List<Recs.Pick> first = recs.now(other);
            for (int i = 0; i < Math.min(Recs.FIRST, first.size()); i++) recs.backdrop(first.get(i));
            if (!first.isEmpty()) otherFirst.add(first.get(0));
        }
        searchEmpty = searchMessage();
        // Picks bring their own background (the focused card's, fading in); without them (no TMDB key), your photo if
        // there is one.
        boolean own = !picksOn() && Photos.on(getContext());
        if (!own && !cards.isEmpty() && bgTo != null && !reset) {
            handler.removeCallbacks(focusBackground);
            handler.post(focusBackground);
        } else {
            Backdrop b = own ? Photos.backdrop(getContext(), ownReady) : focusedBackdrop();
            // A search without answers yet keeps the photo it opened over (fading to the gradient cost a full-screen
            // fill a frame while the field opened).
            if (b != null || (cards.isEmpty() && !own && !searchOpen)) showBackdrop(b);
        }
        if (zone == Z_CARDS && slots == 0) {
            zone = Z_SHELF;
            retarget(false);
        }
        // Only the row's springs: snapping them all would also stop the tabs' pill mid-slide.
        aimCards(reset);
        aimRow(reset);
        heroDirty = true;
        if (changed || reset || fadingUntil > now) wake();
    }

    /** The focused card's backdrop when it's ready, else the one showing now (an empty card has none). */
    private Backdrop focusedBackdrop() {
        if (cards.isEmpty()) return null;
        Backdrop b = recs.whole(cards.get(Math.min(cardIndex, cards.size() - 1)), false);
        return b != null ? b : bgTo;
    }

    /** The focused card's photo becomes the background, fading in (from the main loop, after the key that moved it). */
    private void backgroundFromFocus() {
        if (!running || !picksOn() || cardIndex >= cards.size()) return;
        // Not whole yet (only its card's part was kept): Recs builds its background first of all (preload) and notifies.
        Backdrop b = recs.whole(cards.get(cardIndex), false);
        if (b != null) showBackdrop(b);
        preload();
    }

    /**
     * Around the focus, three cards back and four on, the images load before they come into view, and keep their whole
     * backdrops (the next background is one of them), the focused one first, then the nearest. On every move: drawn only
     * once on screen, the card coming in at the left edge was gray until its photo came; and the background, built only
     * once the focus stopped, came a second or two late.
     */
    private void preload() {
        if (cards.isEmpty()) return;
        int at = Math.min(cardIndex, cards.size() - 1);
        near.clear();
        for (int d = 0; d <= 4; d++) {
            if (at + d < cards.size()) near.add(cards.get(at + d));
            if (d > 0 && d <= 3 && at - d >= 0) near.add(cards.get(at - d));
        }
        int row = near.size();
        near.addAll(otherFirst);   // last, the other tabs' first cards: a tab's row opens on it, its background ready
        recs.keepWhole(near);
        for (int i = 0; i < row; i++) recs.backdrop(near.get(i));
    }

    private final List<Recs.Pick> near = new ArrayList<>();
    /** Each other tab's first card (updateCards): switching tabs, the row opens on it. */
    private final List<Recs.Pick> otherFirst = new ArrayList<>();

    /** Springs, places and words for `n` slots; `reset` puts the row at rest at once (another tab, a new list). */
    private void setSlots(int n, boolean reset) {
        while (cardFocus.size() < n) {
            cardFocus.add(new Spring(0.32f, 0.78f, 0));
            cardGlow.add(new Spring(0.5f, 1, 0));
            cardWide.add(new Spring(0.4f, 0.76f, 0, 0.56f));   // wide in 0.22 s, ~2.5% over at 0.33 s, then softly back
        }
        if (cardRects.length < n) {
            int size = Math.max(n, cardRects.length * 2);
            RectF[] rects = java.util.Arrays.copyOf(cardRects, size);
            for (int i = cardRects.length; i < size; i++) rects[i] = new RectF();
            cardRects = rects;
            cardWords = java.util.Arrays.copyOf(cardWords, size);
            cardX = java.util.Arrays.copyOf(cardX, size);
            cardW = java.util.Arrays.copyOf(cardW, size);
            imageAt = java.util.Arrays.copyOf(imageAt, size);
        }
        if (reset) {
            java.util.Arrays.fill(imageAt, 0);
            for (int i = 0; i < cardFocus.size(); i++) {
                cardFocus.get(i).snap(0);
                cardGlow.get(i).snap(0);
                cardWide.get(i).snap(0);
            }
            rowScroll.snap(0);
        }
        slots = n;
        if (cardIndex >= n) {
            cardIndex = Math.max(0, n - 1);   // the empty cards went without titles: back to the last one
            if (!reset) bump(1);
        }
    }

    /** The card's title cut at both widths, and its details and rating: only when they change, never while drawing. */
    private void cutText(int i, Recs.Pick p) {
        CardText t = cardWords[i];
        if (t == null) t = cardWords[i] = new CardText();
        int fresh = recs.newEpisodes(p);
        boolean away = recs.away(p);   // a search's answer or a watchlist's title Filmix (or the TV's apps) hasn't: the card says so
        String key = Recs.key(p) + '|' + p.title + '|' + p.meta + '|' + p.rating + '|' + fresh + '|' + away;
        if (key.equals(t.key) && t.generation == Ui.generation && t.width == wideW) return;
        t.key = key;
        t.generation = Ui.generation;
        t.width = wideW;
        t.rating = p.rating > 0 ? String.format(Ui.locale(), "%.1f", p.rating) : "";
        fitPaint.set(lCardMeta);
        t.ratingW = t.rating.isEmpty() ? 0 : lCardMeta.getTextSize() * 1.1f + fitPaint.measureText(t.rating) + 22 * k;
        String meta = p.meta == null ? "" : p.meta;
        if (away) {
            String app = Recs.onlyApp(), where = app != null ? Ui.trf("Not on {app}", "app", app) : Ui.tr("Not on the TV's apps");
            meta = meta.isEmpty() ? where : meta + " · " + where;
        }
        t.meta = TextUtils.ellipsize(meta, fitPaint, wideW - t.ratingW, TextUtils.TruncateAt.END).toString();
        t.badge = fresh > 0 ? Ui.plural("plural|one:{n} new episode|other:{n} new episodes", fresh) : "";
        t.badgeW = t.badge.isEmpty() ? 0 : Math.min(lBadge.measureText(t.badge), tallW - 76 * k);
    }

    /** The row can't go further that way: it gives a little, then springs back (tvOS). */
    private void bump(int dx) {
        rowNudge.velocity -= dx * 520 * k;
        wake();
    }

    /** Your photo's background is ready (or new photos came in): draw it. */
    private final Runnable ownReady = () -> {
        if (!running) return;
        updatePicks();
        onWallpaper();   // your photos came in: the photo of the day goes, and Bing isn't asked
    };

    /** The tab, at once; its row takes the last one's place once that has faded out (doFrame: swapRow). */
    private void selectTab(int index) {
        if (index == tab) return;
        rowDir = index > tab ? 1 : -1;
        tab = index;
        rowOut.target = 0;
        heroDirty = true;
        wake();
    }

    /** The selected tab's row in place of the one showing, from its first card; `fade`: it fades in. */
    private void swapRow(boolean fade) {
        rowTab = tab;
        cardIndex = 0;
        rowReset = true;
        updateCards();
        rowOut.snap(1);
        rowIn.snap(fade ? 0 : 1);
        rowIn.target = 1;
    }

    /** How much the tabs have the focus (as one tab's spring rises, the last one's falls). */
    private float tabsFocus() {
        float f = 0;
        for (int i = 0; i < TABS; i++) f += topFocus[i].value;
        return Ui.clamp(f);
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private void drawLightSky(Canvas c) {
        Backdrop photo = photoBg();
        if (photo != null) {
            // Darker with the drawer open and at night (like the picks' night image), both at once when both apply.
            float open = 0.42f * Ui.clamp(drawer.value), dim = Backdrop.NIGHT_DIM * night;
            imagePaint.setColorFilter(veil(1 - (1 - open) * (1 - dim), 0xFF000000));
            drawImage(c, photo.image, 1);
            imagePaint.setColorFilter(null);
            return;
        }
        boolean fading = bgFadeAt != 0;
        // Drawer open: the background lightens (by day) or darkens (at night), as a filter on the drawing itself.
        android.graphics.ColorFilter v = veil(tone(0.32f, 0.42f) * Ui.clamp(drawer.value), blend(0xFFFFFFFF, 0xFF000000, night));
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) lightGradient = Ui.gradient(h, 0xFFF5F5F7, 0xFFE2E2E7);
            if (nightGradient == null) nightGradient = Ui.gradient(h, 0xFF1C1C1E, 0xFF0B0B0D);
            fill.setColorFilter(v);
            fill.setColor(0xFF000000);
            if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
        }
    }

    /**
     * The picks' backgrounds (blurred, Backdrop.GW × GH), in a layer of that size the GPU scales up to the screen: a
     * crossfade then fills the screen once, not once per image (this TV's GPU spends ~11 ms on each full-screen fill).
     */
    private void drawSkyImages(Canvas c) {
        if (photoBg() != null) return;
        long up = SystemClock.uptimeMillis();
        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up, BG_FADE_MS);
        boolean fading = bgFadeAt != 0;
        imagePaint.setColorFilter(veil(tone(0.32f, 0.42f) * Ui.clamp(drawer.value), blend(0xFFFFFFFF, 0xFF000000, night)));
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha, skyRect);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night, skyRect);
    }

    private void drawLightTop(Canvas c) {
        applyTone();
        if (!picksOn()) {
            drawClock(c);
            drawCaption(c);
            if (HomeData.coinCount() > 0 || playingTab != null) drawQuotes(c);
            return;
        }
        // Text tabs (the selected one in bold, in the main text color); with focus on the tabs, the white pill with a
        // shadow, a little larger (the tvOS focus), gliding from tab to tab, the names dark where it is. As the search's
        // field takes their place, they fade away drifting right: each part's own alpha (a
        // layer for them cost this TV's GPU a pass a frame, 40 frames a second instead of 50 as the field opened).
        float focus = tabsFocus(), gone = Ui.clamp(searchMorph.value), shown = 1 - gone;
        float pillL = tabPillX.value, pillR = pillL + tabPillW.value;
        c.save();
        c.translate(60 * k * gone, 0);
        if (focus > 0.01f && gone < 0.999f) {
            RectF r = topRects[tab];
            float s = 1 + 0.05f * focus, half = tabPillW.value * s / 2, mid = (pillL + pillR) / 2;
            cell.set(mid - half, r.centerY() - r.height() * s / 2, mid + half, r.centerY() + r.height() * s / 2);
            float rr = cell.height() / 2;
            tmp.set(cell);
            tmp.offset(0, 8 * k * focus);
            shadow.setColor(Ui.alpha(0xFF000000, tone(0.22f, 0.4f) * focus * shown));
            // Hollow: its width changes on every frame of the glide (a blur mask would be made and uploaded each time);
            // the white pill covers the middle.
            shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow, true);
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, focus * shown));
            c.drawRoundRect(cell, rr, rr, fill);
        }
        for (int i = 0; i < TABS && gone < 0.999f; i++) {
            RectF r = topRects[i];
            Paint p = i == tab ? lTabOn : lTab;
            if (i == tab) lTabOn.setColor(tone(L_INK, WHITE));
            int ink = p.getColor();
            p.setColor(Ui.alpha(ink, shown));
            float tw = p.measureText(tabs()[i]);
            c.drawText(tabs()[i], r.centerX() - tw / 2, r.centerY() + Ui.halfCap(p), p);
            p.setColor(ink);
            float under = focus * Ui.clamp((Math.min(r.right, pillR) - Math.max(r.left, pillL)) / r.width());
            if (under > 0.01f && TAB_KIND[i] == null && traktIn && !searchOpen) {
                // Focused, Explore says ↑ opens its filters: a small chevron over the pill (the tab keeps its height).
                float cx = r.centerX(), cy = r.centerY() - r.height() * (1 + 0.05f * focus) / 2 - 20 * k;
                c.save();
                c.rotate(180, cx, cy);
                Symbols.draw(getContext(), c, "chevron_down", cx, cy, 40 * k, Ui.alpha(tone(L_INK, WHITE), 0.85f * under * shown));
                c.restore();
            }
            if (TAB_KIND[i] == null && !recs.filter(null).none()) {
                // Filtered: a dot at the name's top right, in the accent.
                fill.setShader(null);
                fill.setColor(Ui.alpha(tone(Ui.ACCENT_DEEP, Ui.ACCENT), shown));
                c.drawCircle(r.centerX() + tw / 2 + 9 * k, r.centerY() - Ui.halfCap(p) - 2 * k, 5 * k, fill);
            }
        }
        if (focus > 0.01f && gone < 0.999f) {
            // On the pill (`cell`), the names again, always dark, cut at its edges: as it glides, a name darkens letter by
            // letter.
            c.save();
            c.clipRect(cell);
            for (int i = 0; i < TABS; i++) {
                RectF r = topRects[i];
                if (r.right <= cell.left || r.left >= cell.right) continue;
                Paint p = i == tab ? lTabOn : lTab;
                int ink = p.getColor();
                p.setColor(Ui.alpha(L_INK, focus * shown));
                c.drawText(tabs()[i], r.centerX() - p.measureText(tabs()[i]) / 2, r.centerY() + Ui.halfCap(p), p);
                p.setColor(ink);
            }
            c.restore();
        }
        c.restore();
        if (searchOn()) drawSearch(c);
        // Switching tabs: the row's alpha (each card's parts take it: no layer) and its drift.
        float out = Ui.clamp(rowOut.value), in = Ui.clamp(rowIn.value);
        rowA = out * in;
        c.save();
        c.translate(rowDir * k * (48 * (1 - in) - 24 * (1 - out)), 0);
        String kind = rowKind();
        String empty = searchOpen ? searchEmpty : slots > 0 || recs.loading(kind) ? null
                : Recs.WATCHLIST.equals(kind) ? (traktIn ? Ui.tr("Your Trakt watchlist is empty. Hold OK on a card to add it.")
                        : Ui.tr("Sign in to Trakt first: Free My TV › Home Settings › Trakt."))
                : !recs.filter(kind).none() ? Ui.tr("Nothing here with these filters. Press ↑ on the tab to change them.") : null;
        if (empty != null) {
            int emptyInk = lEmpty.getColor();
            lEmpty.setColor(Ui.alpha(emptyInk, rowA));
            c.drawText(empty, w / 2f, (L_CARDS_TOP + L_CARD_H / 2) * k + Ui.halfCap(lEmpty), lEmpty);
            lEmpty.setColor(emptyInk);
        }
        // The row, from each card's width this frame (never under the tall one); x0 is each card's place without the
        // stretch. The card the focus goes to pushes the next one only by what would bring their edges (as drawn, the
        // focused one 5% larger) closer than their gap once settled: so it moves with the stretch, not before. The card
        // after that moves half as far, so the two gaps between the tall cards narrow together, and the one after them
        // stays. What is off screen isn't drawn, and the lifted card goes last.
        float x0 = MARGIN * k - rowScroll.value + rowNudge.value - menuShift.value, reach = 60 * k, delta = wideW - tallW;
        float pushed = 0, after = 0;
        int top = -1;
        for (int i = 0; i < slots; i++) {
            float v = cardFocus.get(i).value, wide = cardWide.get(i).value, over = delta * Math.max(0, wide - 1);
            if (v > 0.02f && (top < 0 || v > cardFocus.get(top).value)) top = i;
            float bw = tallW + delta * Math.max(0, wide), next = x0 + bw - over + cardGap;
            cardX[i] = x0 + pushed;
            cardW[i] = bw;
            pushed = after;
            after = 0;
            if (i + 1 < slots && cardFocus.get(i).target == 1) {
                float nv = cardFocus.get(i + 1).value, nw = tallW + delta * Math.max(0, cardWide.get(i + 1).value);
                float right = x0 + bw * (2 + 0.05f * v) / 2, left = next - nw * 0.05f * nv / 2;
                pushed = Math.max(0, right + cardGap - restGrowth(i) - restGrowth(i + 1) - left);
                after = pushed / 2;
            }
            x0 = next;
        }
        // The lit cards' light first, under every card: it shows around them, never over a neighbor's photo.
        for (int i = 0; i < slots; i++) {
            if (cardGlow.get(i).value > 0.004f && cardX[i] + cardW[i] > -reach && cardX[i] < w + reach) {
                drawLightCard(c, i, true, false);
            }
        }
        for (int i = 0; i < slots; i++) {
            if (i != top && cardX[i] + cardW[i] > -reach && cardX[i] < w + reach) drawLightCard(c, i, false, true);
        }
        if (top >= 0) drawLightCard(c, top, false, true);
        c.restore();
        if (HomeData.coinCount() > 0 || playingTab != null) drawQuotes(c);
    }

    /** How far a card, once its springs settle, reaches past its place on each side (the focused one is 5% larger). */
    private float restGrowth(int i) {
        return cardFocus.get(i).target * 0.025f * (tallW + (wideW - tallW) * cardWide.get(i).target);
    }

    private boolean picksOn() {
        return Recs.hasKey(getContext());
    }

    /** At the top left, across from the buttons, on a dark chip: what is in the photo of the day and whose it is. */
    private void drawCaption(Canvas c) {
        if (photoBg() == null) return;
        String caption = wallpaper.caption();
        if (!caption.equals(captionFrom)) {
            captionFrom = caption;
            captionLine = TextUtils.ellipsize(caption, lCaption, (L_CAPTION_R - MARGIN - 2 * CAPTION_PAD) * k, TextUtils.TruncateAt.END).toString();
            captionW = lCaption.measureText(captionLine);
        }
        if (captionLine.isEmpty()) return;
        float base = L_CAPTION_BASE * k, mid = base - Ui.halfCap(lCaption), half = CAPTION_H / 2 * k;
        tmp.set(MARGIN * k, mid - half, MARGIN * k + captionW + 2 * CAPTION_PAD * k, mid + half);
        fill.setShader(null);
        fill.setColor(CAPTION_CHIP);
        c.drawRoundRect(tmp, half, half, fill);
        c.drawText(captionLine, (MARGIN + CAPTION_PAD) * k, base, lCaption);
    }

    private void drawClock(Canvas c) {
        float x = MARGIN * k;
        c.drawText(dateText, x - Ui.leftBearing(lDate, dateText), L_DATE_BASE * k, lDate);
        c.drawText(clockText, x - Ui.leftBearing(lClock, clockText), L_CLOCK_BASE * k, lClock);
        HomeData.Weather wx = data.weather;
        if (!Prefs.weather() || Double.isNaN(wx.temp)) return;
        if (data.weatherAt != weatherLineAt || Prefs.version != weatherLinePrefs || !Ui.language.equals(weatherLineLanguage)) {
            weatherLineAt = data.weatherAt;
            weatherLinePrefs = Prefs.version;
            weatherLineLanguage = Ui.language;
            weatherLine = HomeData.degrees(wx.temp) + "  " + HomeData.condition(wx.code, wx.isDay) + "   "
                    + HomeData.highLow(wx, "  ");
        }
        float size = 40 * k, base = L_WEATHER_BASE * k;
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(lWeather), size, lWeather.getColor());
        c.drawText(weatherLine, x + sw + 16 * k, base, lWeather);
    }

    /**
     * Recommendation card: the title's photo (its center at the card's width this frame, from one image big enough for
     * the wide card focused, so it stays sharp and a card that widens keeps the same picture), rounded corners and a
     * soft shadow; when focused, it grows 5%, rises and lights up its border, and while its width is stretched past its
     * size it is a little thinner, like jelly. On it, the title's logo when TMDB has one (drawLogo). As the card widens,
     * the rating and year · genre · runtime fade in below it. An empty card (its title still being looked up) pulses
     * softly.
     */
    private void drawLightCard(Canvas c, int i) {
        drawLightCard(c, i, true, true);
    }

    /** `light`: only its light around it (drawAmbient); `body`: all the rest. */
    private void drawLightCard(Canvas c, int i, boolean light, boolean body) {
        Recs.Pick p = i < cards.size() ? cards.get(i) : null;
        float v = cardFocus.get(i).value, f = Ui.clamp(v), s = 1 + 0.05f * v;
        float left = cardX[i], bw = cardW[i], bh = L_CARD_H * k, r0 = CARD_R * k, top = L_CARDS_TOP * k;
        float over = Math.max(0, cardWide.get(i).value - 1);   // how far its width is stretched past the wide one
        float ch = bh * (1 - CARD_SQUASH * over);   // the body's height; the words below keep their places
        float wide = Ui.clamp((bw - tallW) / (wideW - tallW));
        float cx = left + bw / 2, cy = top + bh / 2 - 6 * k * v;
        cell.set(cx - bw * s / 2, cy - ch * s / 2, cx + bw * s / 2, cy + ch * s / 2);
        float r = r0 * s;
        long up = SystemClock.uptimeMillis();
        Backdrop b = p == null ? null : recs.backdrop(p);
        float shown = b == null || b.card == null ? 0 : imageAt[i] <= 1 ? 1 : Ui.clamp((up - imageAt[i]) / (float) IMAGE_FADE_MS);
        // Focused, it shines with its photo's colors (drawAmbient) in place of its shadow: lit, a card casts none, and
        // this TV's GPU fills one soft edge for it, not two. The shadow is gone by the time the light is a third on (both
        // at once, on the card coming and the one going, cost the row a late frame in eight). Focus deepens and lowers the
        // shadow only a little: while the photo loads the card has it, and a hard one made the card sink when the light
        // took its place.
        float g = b == null || b.ambient == null ? 0 : Ui.clamp(cardGlow.get(i).value) * shown;
        if (light && g > 0.004f) drawAmbient(c, b, r, g * rowA);
        if (!body) return;
        float shade = (tone(0.14f, 0.32f) + tone(0.08f, 0.1f) * f) * Math.max(0, 1 - g / 0.35f) * rowA;
        if (shade > 0.004f) {
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -ch / 2, bw / 2, ch / 2);
            tmp.offset(0, (10 + 6 * f) * k);
            shadowDeep.setColor(Ui.alpha(0xFF000000, shade));
            // A card with a title is opaque (its photo, or its color until the photo comes): its shadow's middle, which
            // it covers, isn't drawn (with the taller cards, the row went from 58 to 57 frames a second without this).
            shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep, p != null);
            c.restore();
        }
        if (shown < 1) {
            // No photo yet: the card's color; an empty card breathes until its title comes.
            float pulse = p != null ? 1 : 0.75f + 0.25f * (float) Math.cos(up * (2 * Math.PI / 1400));
            fill.setShader(null);
            fill.setColor(Ui.alpha(tone(0xFFDADADF, 0xFF2C2C2E), pulse * rowA));
            c.drawRoundRect(cell, r, r, fill);
        }
        if (shown > 0) {
            imageFill.setAlpha(Math.round(255 * shown * rowA));
            coverImage(c, b.card, b.cardShader, cell, r);
            imageFill.setAlpha(255);
        }
        Bitmap logo = shown > 0 ? b.logo : null;
        if (logo != null) drawLogo(c, logo, cx, cy, bw, ch, s, wide, shown * rowA);
        CardText words = p == null ? null : cardWords[i];
        if (words != null && !words.badge.isEmpty() && shown > 0) drawBadge(c, words, cx, cy, bw, ch, s, shown * rowA);
        if (f > 0.01f) {
            int alpha = Math.round(255 * f * rowA);
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -ch / 2, bw / 2, ch / 2);
            sheen.setShader(rowSheen);
            sheen.setAlpha(alpha);
            c.drawRoundRect(tmp, r0, r0, sheen);
            sheen.setAlpha(255);
            float sw = 2.5f * k / s;   // inside the edge, as on the dock's icons
            tmp.inset(sw / 2, sw / 2);
            rim.setShader(rowRim);
            rim.setAlpha(alpha);
            rim.setStrokeWidth(sw);
            c.drawRoundRect(tmp, r0 - sw / 2, r0 - sw / 2, rim);
            rim.setStrokeWidth(2.5f * k);
            rim.setAlpha(255);
            c.restore();
        }
        CardText t = p == null ? null : cardWords[i];
        if (t == null) return;
        // No title under the card (its logo says it, and the menu in words): the details always have the same line (under
        // a title that went when the logo came, they jumped up).
        float details = smoothstep(0.55f, 1, wide) * rowA;
        if (details < 0.01f || (t.meta.isEmpty() && t.rating.isEmpty())) return;
        float metaBase = top + bh + 46 * k, x = left;
        int metaColor = lCardMeta.getColor();
        // Under the menu's card, on its shadow over the dimmed copy, the row's ink is lost: the menu's, dark or white.
        lCardMeta.setColor(Ui.alpha(cardOverMenu ? menuTone(L_INK, WHITE) : metaColor, details));
        underShadow(lCardMeta, g * details);
        if (!t.rating.isEmpty()) {
            // The rating: a star in Apple's yellow, then TMDB's average.
            float size = lCardMeta.getTextSize() * 1.1f, starY = metaBase - Ui.halfCap(lCardMeta);
            starPaint.setColor(Ui.alpha(0xFFFFCC00, details));
            c.save();
            c.translate(x + size / 2, starY);
            c.scale(size, size);
            c.drawPath(star, starPaint);
            c.restore();
            c.drawText(t.rating, x + size + 6 * k, metaBase, lCardMeta);
            x += t.ratingW;
        }
        if (!t.meta.isEmpty()) c.drawText(t.meta, x, metaBase, lCardMeta);
        lCardMeta.clearShadowLayer();
        lCardMeta.setColor(metaColor);
    }

    /**
     * Light words under a card get a soft dark shadow while its light (drawAmbient) is behind them, so they still read
     * over a bright photo's glow; dark ones (by day) need none. `a`: how much of it (with the light and the words' fade).
     * Its color carries its own alpha: a translucent shadow doesn't take the paint's.
     */
    private void underShadow(Paint p, float a) {
        int ink = p.getColor();
        float luma = (0.299f * ((ink >> 16) & 255) + 0.587f * ((ink >> 8) & 255) + 0.114f * (ink & 255)) / 255;
        float dark = 0.7f * a * smoothstep(0.48f, 0.58f, luma);   // the night's gray details (0.6) and white get it all
        if (dark < 0.01f) p.clearShadowLayer();
        else p.setShadowLayer(5 * k, 0, 1.5f * k, Ui.alpha(0xFF000000, dark));
    }

    /**
     * The focused card's light in its photo's colors (Backdrop.ambient), like a TV's ambient glow: the blurred photo
     * under the card (`cell`), its photo part a little larger than the card and lower, so the light shows a little past
     * its top and sides and more past its bottom. As `g` rises the photo part grows from inside the card to past its
     * edges and the light brightens; falling, it draws back the same way. Only what the card leaves uncovered is filled:
     * the four bands around it and its rounded corners (its photo covers the rest, and this TV's GPU pays for every
     * pixel: bands reaching a corner's radius into the card cost a third more).
     */
    private void drawAmbient(Canvas c, Backdrop b, float r, float g) {
        int pad = Backdrop.AMBIENT_PAD, reach = Backdrop.AMBIENT_REACH;
        float grow = (-24 + 38 * g) * k, drop = 14 * g * k;
        float sx = (cell.width() + 2 * grow) / Backdrop.AMBIENT_W, sy = (cell.height() + 2 * grow) / Backdrop.AMBIENT_H;
        float x0 = cell.left - grow - pad * sx, y0 = cell.top - grow + drop - pad * sy;
        // In the bitmap's pixels: where the light ends (the blur's reach past the photo), the card, and its corners.
        float l = pad - reach, t = pad - reach, rt = pad + Backdrop.AMBIENT_W + reach, bt = pad + Backdrop.AMBIENT_H + reach;
        float cl = Math.max(l, (cell.left - x0) / sx), ct = Math.max(t, (cell.top - y0) / sy);
        float cr = Math.min(rt, (cell.right - x0) / sx), cb = Math.min(bt, (cell.bottom - y0) / sy);
        float rx = Math.min(r / sx, (cr - cl) / 2), ry = Math.min(r / sy, (cb - ct) / 2);
        c.save();
        c.translate(x0, y0);
        c.scale(sx, sy);
        ambientPaint.setShader(b.ambientShader);
        ambientPaint.setAlpha(Math.round(255 * g * tone(0.85f, 1f)));
        c.drawRect(l, t, rt, ct, ambientPaint);
        c.drawRect(l, cb, rt, bt, ambientPaint);
        c.drawRect(l, ct, cl, cb, ambientPaint);
        c.drawRect(cr, ct, rt, cb, ambientPaint);
        c.drawRect(cl, ct, cl + rx, ct + ry, ambientPaint);
        c.drawRect(cr - rx, ct, cr, ct + ry, ambientPaint);
        c.drawRect(cl, cb - ry, cl + rx, cb, ambientPaint);
        c.drawRect(cr - rx, cb - ry, cr, cb, ambientPaint);
        ambientPaint.setShader(null);
        c.restore();
    }

    /**
     * A show's new episodes, on its card's top left: a white pill with a blue dot and "2 new episodes", in the card's own
     * transform (it follows the focus's growth and the jelly).
     */
    private void drawBadge(Canvas c, CardText t, float cx, float cy, float bw, float ch, float s, float alpha) {
        float h = 38 * k, pad = 14 * k, dot = 5 * k, inset = 16 * k;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        float left = -bw / 2 + inset, top = -ch / 2 + inset;
        tmp.set(left, top, left + pad + 2 * dot + 9 * k + t.badgeW + pad, top + h);
        fill.setShader(null);
        fill.setColor(Ui.alpha(0xF2FFFFFF, alpha));
        c.drawRoundRect(tmp, h / 2, h / 2, fill);
        fill.setColor(Ui.alpha(Ui.ACCENT, alpha));
        c.drawCircle(left + pad + dot, top + h / 2, dot, fill);
        lBadge.setColor(Ui.alpha(L_INK, alpha));
        c.save();
        c.clipRect(tmp);
        c.drawText(t.badge, left + pad + 2 * dot + 9 * k, top + h / 2 + Ui.halfCap(lBadge), lBadge);
        c.restore();
        c.restore();
    }

    /**
     * The title's logo on its card (whose photo darkens toward the bottom: Backdrop.card): on a tall card centered near
     * its bottom (82% of its width × 20% of its height at most), on the wide one at its bottom left (42% × 24%), moving
     * and growing between the two as the card widens. Drawn in the card's own transform, so it follows the focus's growth
     * and the jelly; the bitmap is the wide size's (Backdrop.logo), mipmapped for the tall cards.
     */
    private void drawLogo(Canvas c, Bitmap logo, float cx, float cy, float bw, float ch, float s, float wide, float alpha) {
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        float boxW = tallW * 0.82f + (wideW * 0.42f - tallW * 0.82f) * wide, boxH = L_CARD_H * k * (0.2f + 0.04f * wide);
        float fit = Math.min(boxW / logo.getWidth(), boxH / logo.getHeight());
        float lw = logo.getWidth() * fit, lh = logo.getHeight() * fit, pad = (26 + 8 * wide) * k;
        float lx = -lw / 2 + (-bw / 2 + pad + lw / 2) * wide;   // centered, then at the left as the card widens
        logoRect.set(lx, ch / 2 - pad - lh, lx + lw, ch / 2 - pad);
        logoPaint.setAlpha(Math.round(255 * alpha));
        c.drawBitmap(logo, null, logoRect, logoPaint);
        c.restore();
    }

    /**
     * The card's 16:9 photo covering `cell`: its center, scaled to the cell's height or width (whichever covers it), with
     * rounded corners. Drawn through the canvas's transform, so nothing is made per frame.
     */
    private void coverImage(Canvas c, Bitmap image, BitmapShader shader, RectF cell, float r) {
        float iw = image.getWidth(), ih = image.getHeight();
        float scale = Math.max(cell.width() / iw, cell.height() / ih);
        float vw = cell.width() / scale, vh = cell.height() / scale;
        c.save();
        c.translate(cell.centerX(), cell.centerY());
        c.scale(scale, scale);
        c.translate(-iw / 2, -ih / 2);
        imageFill.setShader(shader);
        c.drawRoundRect((iw - vw) / 2, (ih - vh) / 2, (iw + vw) / 2, (ih + vh) / 2, r / scale, r / scale, imageFill);
        imageFill.setShader(null);
        c.restore();
    }

    /**
     * The round buttons (Live TV, HDMI Inputs and Settings by default): icon-only glass circles (light by day, smoked at
     * night); the focused one turns white with a dark icon (the tvOS focus) and its name appears below it.
     */
    private void drawLightButtons(Canvas c) {
        applyTone();
        for (int i = 0; i < lightButtons.size(); i++) {
            HomeApps.Item it = lightButtons.get(i);
            RectF r = topRects[TABS + i];
            float f = Ui.clamp(topFocus[TABS + i].value), s = 1 + 0.1f * f, rad = r.width() / 2 * s;
            cell.set(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad);
            tmp.set(cell);
            tmp.offset(0, (6 + 8 * f) * k);
            shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
            shadows.draw(c, tmp, rad, SHADOW_BLUR * k, shadow);
            buttonGlass(c, cell, rad, 1 - f);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawCircle(cell.centerX(), cell.centerY(), rad, fill);
            }
            Symbols.draw(getContext(), c, it.symbol, cell.centerX(), cell.centerY(), 27 * k * s,
                    blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));
            if (f > 0.01f) {
                String label = it.label;
                float half = lTip.measureText(label) / 2;
                float x = Math.min(r.centerX(), CONTENT_R * k - half);
                lTip.setAlpha(Math.round(255 * f));
                c.drawText(label, x, r.bottom + (40 + 4 * f) * k, lTip);
            }
        }
        if (sleepText.isEmpty()) return;
        float right = (lightButtons.isEmpty() ? CONTENT_R * k : topRects[TABS].left) - 28 * k, cy = L_BUTTON_CY * k;
        float tw = lQuote.measureText(sleepText), size = lQuote.getTextSize();
        c.drawText(sleepText, right - tw, cy + Ui.halfCap(lQuote), lQuote);
        Symbols.draw(getContext(), c, "bedtime", right - tw - size * 0.9f, cy, size, lQuote.getColor());
    }

    private boolean moveLight(int dx, int dy) {
        switch (zone) {
            case Z_SHELF:
                // Up from an empty shelf too: move()'s older zones (the hero, the corner capsule) aren't drawn any more.
                if (dy >= 0) return false;
                if (cards.isEmpty() && searchOpen) {
                    zone = Z_SEARCH;
                    onMic = false;
                } else if (cards.isEmpty()) {
                    if (!picksOn() && lightButtons.isEmpty()) return true;   // no tabs and no buttons above
                    zone = Z_TOP;
                    topIndex = picksOn() ? tab : 3;
                } else {
                    zone = Z_CARDS;   // the row remembers its card (as on Apple TV): the same one is wide again
                }
                break;
            case Z_CARDS:
                if (dx != 0) {
                    int next = cardIndex + dx;
                    // Onto the last card: three more (empty at first, while Recs looks their titles up), so the next one's
                    // edge shows past it as the row scrolls.
                    if (next >= slots - 1 && dx > 0 && recs.more(rowKind())) updateCards();
                    if (next < 0 || next >= slots) {
                        bump(dx);
                        return true;
                    }
                    cardIndex = next;
                    // The background follows at once: with the shelf's and the buttons' glass clear, its crossfade only
                    // redraws the sky (JMGO N1S Ultra: 59 frames a second along the row, fades included).
                    handler.removeCallbacks(focusBackground);
                    handler.post(focusBackground);
                } else if (dy < 0 && searchOpen) {
                    zone = Z_SEARCH;    // the field's text
                    onMic = false;
                } else if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = tab;     // as on Apple TV: moving up to the tabs focuses the selected tab
                } else {
                    zone = Z_SHELF;
                    if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, tileRects.length, cardRects[cardIndex].centerX());
                }
                break;
            case Z_TOP:
                if (dx != 0) {
                    // Past the first tab, the magnifier; with the field open (the tabs gone), the field.
                    if (searchOn() && (searchOpen ? topIndex + dx < TABS : topIndex == 0 && dx < 0)) {
                        zone = Z_SEARCH;
                        onMic = voice != null;   // from the round buttons, the field's nearest end
                        break;
                    }
                    int next = Math.max(picksOn() ? 0 : TABS, Math.min(TABS - 1 + lightButtons.size(), topIndex + dx));
                    if (next == topIndex) return true;
                    topIndex = next;
                    if (topIndex < TABS) selectTab(topIndex);   // like Apple TV tabs: focusing a tab selects it
                } else if (dy > 0) {
                    zone = slots == 0 ? Z_SHELF : Z_CARDS;   // the row's own card, as from the shelf
                } else if (canFilter() && Recs.canFilter()) {
                    openFilters(false);   // up from Explore: its filters (a chevron above the tab says so)
                    return true;
                } else if (topIndex < TABS && !lightButtons.isEmpty()) {
                    topIndex = TABS;   // up from the tabs reaches the corner buttons without walking the row
                } else {
                    return true;
                }
                break;
            case Z_SEARCH:
                if (searchOpen) {
                    // The field: → ← between its text and its microphone, ↓ to the answers (the keyboard goes); ↑
                    // stays, so a key the keyboard lets through doesn't take the focus away while typing.
                    if (dx != 0 && voice != null && (dx > 0) != onMic) {
                        onMic = dx > 0;
                        releaseField();
                    } else if (dy > 0) {
                        releaseField();
                        zone = slots == 0 ? Z_SHELF : Z_CARDS;
                    } else {
                        return true;
                    }
                } else if (dx > 0) {
                    zone = Z_TOP;
                    topIndex = 0;
                    selectTab(0);
                } else if (dy > 0) {
                    zone = slots == 0 ? Z_SHELF : Z_CARDS;
                } else if (dy < 0 && !lightButtons.isEmpty()) {
                    zone = Z_TOP;
                    topIndex = TABS;
                } else {
                    return true;
                }
                break;
            default:
                return false;
        }
        retarget(false);
        click.move();
        return true;
    }

    // MARK: - Search

    /** Search needs Trakt (its client ID) and the picks' TMDB key (each answer's details and photo). */
    private boolean searchOn() {
        return picksOn() && Trakt.hasKey();
    }

    private void searchQuery() {
        recs.search(query);
    }

    /** The row's kind: the search's answers while its field is open, else the selected tab's. */
    private String rowKind() {
        return searchOpen ? Recs.SEARCH : TAB_KIND[rowTab];
    }

    /**
     * The keyboard's target: never drawn (alpha 0; the field is drawn with the tabs) and focusable only while the
     * search is open, so the home screen keeps the focus otherwise. Its Search key goes to the answers.
     */
    private EditText searchField(Activity a) {
        EditText e = new EditText(a);
        e.setAlpha(0);
        e.setBackground(null);
        e.setSingleLine(true);
        e.setCursorVisible(false);   // its blinking would redraw it for nothing
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        e.setFocusable(false);
        e.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the field (Spoken)
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString();
                cutQuery();
                restartCaret();
                wake();
                handler.removeCallbacks(searchNow);
                if (query.trim().isEmpty()) searchNow.run();
                else handler.postDelayed(searchNow, SEARCH_AFTER_MS);   // once the typing stops
            }
        });
        e.setOnEditorActionListener((v, action, event) -> {
            handler.removeCallbacks(searchNow);
            searchNow.run();
            releaseField();
            if (searchOpen && zone == Z_SEARCH && !cards.isEmpty()) {
                zone = Z_CARDS;
                retarget(false);
            }
            return true;
        });
        return e;
    }

    /** OK on the magnifier: the tabs become the field (the last text still in it), with the keyboard up. */
    private void openSearch() {
        if (searchOpen || !searchOn()) return;
        searchOpen = true;
        zone = Z_SEARCH;
        onMic = false;
        searchMorph.target = 1;
        cardIndex = 0;
        rowReset = true;
        updateCards();
        retarget(false);
        click.open();
        // The keyboard once the field has opened: both at once (another window sliding up, drawn by Gboard on the same
        // GPU) took this TV to 35 frames a second.
        handler.postDelayed(keyboardSoon, KEYBOARD_AFTER_MS);
    }

    /** Back to the tabs and the selected tab's row; the focus on the magnifier (or where it was, below). */
    private void closeSearch(boolean snap) {
        if (!searchOpen) return;
        searchOpen = false;
        handler.removeCallbacks(keyboardSoon);
        releaseField();
        searchMorph.target = 0;
        if (snap) searchMorph.snap(0);
        if (zone == Z_CARDS) zone = Z_SEARCH;
        cardIndex = 0;
        rowReset = true;
        updateCards();
        retarget(snap);
    }

    private void showKeyboard() {
        field.setFocusable(true);
        field.setFocusableInTouchMode(true);
        field.requestFocus();
        field.setSelection(field.length());
        InputMethodManager m = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (m != null) m.showSoftInput(field, 0);
    }

    /**
     * The TV's voice input for the search, Google's when there is more than one (Google TV also lists its speech
     * engine's): named outright, so Android doesn't ask which one. Null without any.
     */
    private static Intent voiceInput(Context c) {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        ResolveInfo pick = null;
        for (ResolveInfo r : c.getPackageManager().queryIntentActivities(i, 0)) {
            if (pick == null || GOOGLE_VOICE.equals(r.activityInfo.packageName)) pick = r;
        }
        if (pick == null) return null;
        return i.setClassName(pick.activityInfo.packageName, pick.activityInfo.name)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
    }

    /** OK on the microphone (or the remote's search key): the TV listens, and onVoice gets what was said. */
    private void startVoice() {
        if (voice == null) return;
        releaseField();
        click.open();
        Intent i = new Intent(voice).putExtra(RecognizerIntent.EXTRA_LANGUAGE, Ui.locale().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PROMPT, Ui.tr("search|Say the name of a movie or show"));
        try {
            activity.startActivityForResult(i, HomeActivity.VOICE);
        } catch (ActivityNotFoundException | SecurityException e) {
            showToast(Ui.trf("Couldn't open {app}.", "app", Ui.tr("Voice search")));
        }
    }

    /** What was said (null: nothing): the field's text, searched at once, the focus on the text. */
    void onVoice(Intent data) {
        ArrayList<String> said = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        String text = said == null || said.isEmpty() || said.get(0) == null ? "" : said.get(0).trim();
        if (!searchOpen || text.isEmpty()) return;
        field.setText(text);
        handler.removeCallbacks(searchNow);
        searchNow.run();
        zone = Z_SEARCH;
        onMic = false;
        retarget(false);
    }

    /** The keyboard goes, and the home screen takes the keys back until OK on the field brings it up again. */
    private void releaseField() {
        InputMethodManager m = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (m != null) m.hideSoftInputFromWindow(field.getWindowToken(), 0);
        field.setFocusable(false);   // focused, it lets the focus go
        requestFocus();
    }

    /**
     * The caret shows (and blinks) while the field has the focus. Elsewhere nothing is redrawn for it: retarget() calls
     * this on every move, the shelf's too.
     */
    private void restartCaret() {
        handler.removeCallbacks(caretBlink);
        if (!running || !searchOpen || zone != Z_SEARCH || onMic) return;
        if (!caretOn) {
            caretOn = true;
            heroDirty = true;
        }
        handler.postDelayed(caretBlink, CARET_MS);
        wake();
    }

    /** The text as the field shows it: its end, cut at the start when it's longer than the field. */
    private void cutQuery() {
        if (lSearch == null) return;
        float room = fieldRect.width() - L_TABBAR_H * k - (voice != null ? L_TABBAR_H + 12 : 40) * k;   // the microphone's room
        fitPaint.set(lSearch);
        queryLine = TextUtils.ellipsize(query, fitPaint, Math.max(0, room), TextUtils.TruncateAt.START).toString();
        queryLineW = lSearch.measureText(queryLine);
        heroDirty = true;
    }

    /** What the empty search row says (null: nothing), made when the row changes, never while drawing. */
    private String searchMessage() {
        if (!searchOpen || slots > 0) return null;
        switch (recs.searchState()) {
            case Recs.SEARCH_IDLE: return Ui.tr("Type the name of a movie or show.");
            case Recs.SEARCH_FAILED: return Ui.tr("Trakt didn't answer. Try again in a moment.");
            case Recs.SEARCH_DONE: return Ui.trf("Nothing found for “{text}”.", "text", recs.searchText());
            default: return null;
        }
    }

    /**
     * The magnifier, and the field it becomes: a glass circle like the round buttons at the top right (light by day,
     * smoked at night, its soft shadow), white and 10% larger when focused; open, it stretches to the field's width as
     * the tabs fade away (the field doesn't grow), glass while the focus is on the answers. In it, the text (or what to
     * type), and the caret while it has the focus.
     */
    private void drawSearch(Canvas c) {
        float f = Ui.clamp(searchFocus.value), m = Ui.clamp(searchMorph.value);
        float s = 1 + 0.1f * f * (1 - m);
        float right = searchRect.right + (fieldRect.right - searchRect.right) * Math.max(0, searchMorph.value);
        float cx = (searchRect.left + right) / 2, cy = searchRect.centerY(), hw = (right - searchRect.left) / 2 * s;
        float hh = searchRect.height() / 2 * s;
        cell.set(cx - hw, cy - hh, cx + hw, cy + hh);
        float rr = hh;
        tmp.set(cell);
        tmp.offset(0, (6 + 8 * f) * k);
        shadow.setColor(Ui.alpha(0xFF000000, tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f));
        shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
        buttonGlass(c, cell, rr, 1 - f);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(0xFFFFFFFF, f));
            c.drawRoundRect(cell, rr, rr, fill);
        }
        // Dark on the white circle; at rest as the round buttons' icons (dark by day, white at night), and the field's
        // text as dark.
        int ink = blend(tone(L_INK, WHITE), L_INK, f);
        Symbols.draw(getContext(), c, "search", cell.left + searchRect.width() / 2 * s, cy, SEARCH_ICON * k * s,
                blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));
        float text = smoothstep(0.55f, 1, m);
        if (text < 0.01f) return;
        float x = cell.left + L_TABBAR_H * k, base = cy + Ui.halfCap(lSearch);
        c.save();
        c.clipRect(cell);
        if (queryLine.isEmpty()) {
            lSearchHint.setColor(Ui.alpha(blend(tone(L_DIM, 0xFF8E8E93), L_DIM, f), text));
            c.drawText(Ui.tr("search|Movies and shows"), x, base, lSearchHint);
        } else {
            lSearch.setColor(Ui.alpha(ink, text));
            c.drawText(queryLine, x, base, lSearch);
        }
        if (searchOpen && zone == Z_SEARCH && !onMic && caretOn) {
            float cxr = x + queryLineW + 2 * k, half = 19 * k;
            fill.setColor(Ui.alpha(tone(Ui.ACCENT_DEEP, Ui.ACCENT), text));
            c.drawRoundRect(cxr, cy - half, cxr + 3 * k, cy + half, 1.5f * k, 1.5f * k, fill);
        }
        if (voice != null) {
            // The microphone at the field's right end: focused, a white circle of its own on the field's glass.
            float mf = Ui.clamp(micFocus.value), ms = 1 + 0.08f * mf, mx = cell.right - hh, mr = hh - 6 * k;
            if (mf > 0.01f) {
                fill.setColor(Ui.alpha(0xFFFFFFFF, mf * text));
                c.drawCircle(mx, cy, mr * ms, fill);
            }
            Symbols.draw(getContext(), c, "mic", mx, cy, 26 * k * ms,
                    Ui.alpha(blend(blend(tone(L_DIM, 0xFF8E8E93), L_DIM, f), L_INK, mf), text));
        }
        c.restore();
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    /** Rounds a shape's edges to whole pixels (crisp at rest; a scale animation moves them again). */
    private static void snap(RectF r) {
        r.set(Math.round(r.left), Math.round(r.top), Math.round(r.right), Math.round(r.bottom));
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
