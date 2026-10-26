package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Free My TV's settings (the app's icon, "All Apps", or the gear on the home screen), in the style of Apple TV's
 * settings with Liquid Glass: on the left the screen's symbol on a glass tile, its title and what the focused row does;
 * on the right the rows on a glass platter, the white focus gliding between them. "Home screen" opens the Free My TV /
 * Original choice (with a check on the current one); the switch runs in the main process (HomeMode) and this screen
 * only asks for it and follows the state file.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private DreamView preview;   // "Screensaver": the screensaver right here, without changing the system setting

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (openFireHome(getIntent())) return;
        Watchdog.install(this);
        Ui.init(this);
        SleepTimer.listen(this);   // :home may start here, from the launcher icon
        SleepTimer.check(this);
        view = new SettingsView(this);
        setContentView(view);
    }

    /**
     * Fire TV: Amazon keeps the Home button, so the app's icon opens the home screen (FireHomeActivity) directly;
     * the gear on the home screen opens these settings (no launcher category).
     */
    private boolean openFireHome(Intent intent) {
        if (!HomeMode.AMAZON || intent == null || !(intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                || intent.hasCategory(Intent.CATEGORY_LAUNCHER)) || intent.getBooleanExtra("screensaver", false)) {
            return false;
        }
        startActivity(new Intent(this, FireHomeActivity.class));
        finish();
        return true;
    }

    @Override protected void onResume() {
        super.onResume();
        if (view == null) return;
        view.resume();
        // From ADB: am start -n io.freemytv/.SettingsActivity --ez screensaver true (straight to the preview).
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            view.pause();
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (openFireHome(intent)) return;
        setIntent(intent);
    }

    @Override protected void onPause() {
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        if (view != null) view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system); with Watch from the
            // screensaver on, OK opens the title first.
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        boolean ok = event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
                        if (ok && Prefs.saverWatch()) preview.watch();
                        closePreview();
                    }
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void showPreview() {
        preview = new DreamView(this);
        setContentView(preview);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        setContentView(view);
        view.resume();
    }

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, HOME_CHOICE = 1, HOME_SETTINGS = 2, REMOTE_KEYS = 3, PICKER = 4, SHELF_EDIT = 5,
                POWER = 6, TRAKT = 7, ABOUT = 8, CONFIRM = 9;
        // The layout at 1920 × 1080 (scaled by k): the left column's middle and its glass tile; the platter on the right,
        // its rows (a choice's, with a second line, taller) and how many a long list shows at once (it scrolls).
        private static final float LEFT_X = 484, TILE = 280, TILE_TOP = 190;
        private static final float PLATTER_L = 968, PLATTER_R = 1808, PLATTER_TOP = 190, PAD = 14;
        private static final float ROW_H = 64, PITCH = 70, CHOICE_H = 104, CHOICE_PITCH = 112;
        private static final int VISIBLE = 10, PICK_VISIBLE = VISIBLE, MAIN_ROWS = 9;
        private static final int AMBER = 0xFFFFC266, INK = 0xFF1C1C1E, RED = 0xFFFF453A, DEEP_RED = 0xFFD70015;

        private final SettingsActivity activity;
        private final Scene scene = new Scene(false);   // only its clear glass: no aurora, no frost to compute
        // The background: dark graphite, lit softly from behind the tile and warmly (the accent) from the bottom right;
        // drawn once per size at half the screen's, scaled up by the GPU (one fill a frame, nothing between frames).
        private Bitmap backdrop;
        private final Paint backdropPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF screenRect = new RectF();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Shadows shadows = new Shadows();
        // The focus capsule's top (from the platter's first row) and height, the list's scroll, the platter's height (it
        // follows each screen's rows, the glass morphing) and opacity, a new screen's content coming in, and the focus.
        private final Spring capY = new Spring(0.28f, 0.82f, 0), capH = new Spring(0.28f, 1, ROW_H),
                scrollY = new Spring(0.3f, 1, 0), platterH = new Spring(0.36f, 0.86f, 0), platterA = new Spring(0.24f, 1, 1),
                enter = new Spring(0.34f, 1, 1), focusA = new Spring(0.24f, 1, 1);
        private final Spring[] springs = {capY, capH, scrollY, platterH, platterA, enter, focusA};
        private int shownScreen = -1, enterDir = 1;
        // The confirmation (CONFIRM: sign out, remove photos) and the screen it came from.
        private String confirmTitle = "", confirmAction = "", confirmIcon = "";
        private Runnable confirmRun;
        private int backScreen, backFocus, backScroll;
        private final RectF platter = new RectF(), tile = new RectF(), capsule = new RectF();
        // The left column's text, wrapped once per text (and per language).
        private StaticLayout helpLayout;
        private String helpText;
        private int helpGeneration = -1;
        private float titleSize;
        private String titleFitted;
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        // The list picker (the app of a remote button): what it shows, which one is in use, where we are.
        private final List<String> pickLabels = new ArrayList<>();
        // The shelf editor (also the top buttons' one, with editingTop): what it can hold (selected ones first, in
        // order) and the selection.
        private final List<String[]> shelfRows = new ArrayList<>();
        private final List<String> shelfOrder = new ArrayList<>();
        private boolean editingTop;
        private int pickFor, pickCurrent, pickScroll;   // pickFor: the remote button (index + 1)
        private static final int PICK_CITY = 0, PICK_LANGUAGE = -1, PICK_SLEEP = -2, PICK_PLACE = -3, PICK_MOVIES = -4,
                PICK_SHOWS = -5;
        /** The pickers from PICK_SLEEP down (Sleep Timer's, the picks' lists): their choices as {value, name}, and the value now. */
        private final List<String[]> choices = new ArrayList<>();
        private String choiceNow = "";
        private final List<String[]> cities = new ArrayList<>();
        private String cityStatus = "";
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private boolean hasKey;
        private int screen = MAIN, focus;
        private boolean running, okDown, backDown, wasBusy;
        private StaticLayout errorLayout;   // the last error, wrapped (rebuilt only when it changes)
        private Toast whyToast;   // why the focused option is unavailable
        private String[] whyRows;  // each row's reason (all the screen's rows), worked out once per screen (null: again)
        private int whyScreen = -1;
        private int sleepLeft;     // the sleep timer's minutes left, read with the state, not on every frame
        private int photoCount;    // My Photos, counted with the state (a folder listing), not on every frame
        private int whyState = -1;
        private String errorShown;
        private final Spoken spoken;
        private long startedAt, lastNanos, pendingAt;
        private int w, h;
        private float k = 1;
        private Paint title, subtitle, version, rowLabel, rowValue, rowSub, header, cellLabel, cellValue, warn, spinner, white, shadow;
        private Paint traktUrl, traktCode, traktState, button, fine;
        private TextPaint notice, help;
        // Trakt sign-in (TRAKT): the code Trakt gave, what is happening, and which attempt late answers belong to.
        private Trakt.Device traktDevice;
        private String traktStatus = "", traktUser = "";
        private boolean traktWaiting, traktIn;
        private int traktRun;
        private int listsFor;   // the row (MOVIES_ROW, SHOWS_ROW) whose Trakt lists are being asked for, or 0
        private final RectF traktCard = new RectF();

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                sleepLeft = SleepTimer.minutesLeft(getContext());
                photoCount = Photos.files(getContext()).size();
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                traktIn = Trakt.hasOAuth();
                traktUser = traktIn ? Trakt.user() : "";
                boolean nowBusy = !busyText().isEmpty();
                if (nowBusy) pendingAt = 0;
                // What can change the reasons away from a key: a switch (or the watchdog's revert) and the overlay's and the
                // listener's checks.
                int whyNow = (freeActive() ? 1 : 0) | (Capabilities.refused(getContext(), Capabilities.OVERLAY) ? 2 : 0)
                        | (Capabilities.refused(getContext(), Capabilities.LISTENER) ? 4 : 0) | (traktIn ? 8 : 0);
                if (whyNow != whyState) whyRows = null;
                whyState = whyNow;
                // A switch just ended: TalkBack reads how it went (the newest notice).
                JSONArray notices = state.optJSONArray("notices");
                if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                    JSONObject newest = notices.optJSONObject(0);
                    if (newest != null) announceForAccessibility(Ui.named(newest.optString("text")));
                }
                wasBusy = nowBusy;
                invalidate();
                handler.postDelayed(this, busy() ? 500 : 2000);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            setFocusable(true);
            setContentDescription(Ui.NAME);
            // TalkBack: one item per row, id = row + 1.
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) { for (int i = 1; i <= rowCount(); i++) out.add(i); }
                @Override public String text(int id) { return id >= 1 && id <= rowCount() ? rowText(id - 1) : ""; }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > rowCount()) return false;
                    rowBox(id - 1).round(out);
                    return true;
                }
                @Override public int focused() { return focus + 1; }
                @Override public void focus(int id) {
                    focus = id - 1;
                    invalidate();
                }
                @Override public void click(int id) { activate(); }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        /** A row as TalkBack reads it: its name and its value, as on screen, and why where it's unavailable. */
        private String rowText(int row) {
            String why = unavailable(row);
            return why == null ? rowValueText(row) : rowValueText(row) + ", " + Ui.tr("Unavailable") + ". " + why;
        }

        private String rowValueText(int row) {
            switch (screen) {
                case MAIN: {
                    if (row == 0) {
                        String busyText = busyText();
                        String value = busy() ? (busyText.isEmpty() ? Ui.tr("Starting…") : busyText)
                                : freeActive() ? Ui.NAME : Ui.tr("Original");
                        return Ui.trf("Home Screen, {mode}", "mode", value);
                    }
                    String[] names = {Ui.tr("Screensaver, preview"), Ui.tr("Home Settings"),
                            Ui.tr("Shelf"), Ui.tr("Top Buttons"), Ui.tr("Remote Buttons"), Ui.tr("Sleep Timer"),
                            Ui.tr("TV Settings"), Ui.tr("About")};
                    return row - 1 < names.length ? names[row - 1] : "";
                }
                case ABOUT:
                    return aboutText();
                case CONFIRM:
                    return (row == 0 ? confirmAction : Ui.tr("Cancel")) + ", " + Ui.trf("{i} of {n}", "i", row + 1, "n", 2)
                            + (row == 0 ? "" : ". " + confirmTitle);
                case HOME_CHOICE: {
                    String name = row == 0 ? Ui.NAME + ", " + Ui.tr("no ads, with the new screensaver")
                            : "Original, " + Ui.tr("the TV's factory home screen");
                    boolean inUse = (row == 0) == freeActive();
                    return name + (inUse ? Ui.tr(". In use") : "") + ", " + Ui.trf("{i} of {n}", "i", row + 1, "n", 2);
                }
                case HOME_SETTINGS:
                case REMOTE_KEYS:
                case POWER: {
                    boolean keys = screen == REMOTE_KEYS;
                    String[] labels = listLabels(), values = listValues();
                    int at = keys ? row : pickScroll + row;
                    return at < labels.length ? labels[at] + ", " + values[at] : "";
                }
                case PICKER: {
                    int at = pickScroll + row;
                    if (at >= pickLabels.size()) return "";
                    return pickLabels.get(at) + (at == pickCurrent ? Ui.tr(", selected") : "")
                            + ", " + Ui.trf("{i} of {n}", "i", at + 1, "n", pickLabels.size());
                }
                case TRAKT:
                    if (traktDevice == null) return "Trakt. " + traktStatus;
                    return "Trakt. " + Ui.tr("Your Trakt recommendations become the home screen's picks. On your phone or computer, open the address below and enter the code.")
                            + " " + shortUrl(traktDevice.url) + ". " + TextUtils.join(" ", traktDevice.userCode.split("")) + ". " + traktStatus;
                case SHELF_EDIT: {
                    int at = pickScroll + row;
                    if (at >= shelfRows.size()) return "";
                    String[] app = shelfRows.get(at);
                    int place = shelfOrder.indexOf(app[0]);
                    if (editingTop) {
                        return app[1] + ", " + (place >= 0 ? Ui.trf("in the top buttons, place {n}", "n", place + 1)
                                : Ui.tr("not in the top buttons"));
                    }
                    return app[1] + ", " + (place >= 0 ? Ui.trf("on the shelf, place {n}", "n", place + 1)
                            : Ui.tr("not on the shelf"));
                }
                default:
                    return "";
            }
        }

        /** Visible row `row` where it rests on screen (TalkBack's box for it). */
        private RectF rowBox(int row) {
            if (screen == TRAKT) {
                focusBox.set(traktCard);
                return focusBox;
            }
            if (screen == ABOUT) {
                focusBox.set(platter);
                return focusBox;
            }
            float top = PLATTER_TOP + PAD + row * pitch();
            focusBox.set((PLATTER_L + PAD) * k, top * k, (PLATTER_R - PAD) * k, (top + rowH()) * k);
            return focusBox;
        }

        private float rowH() {
            return screen == HOME_CHOICE ? CHOICE_H : ROW_H;
        }

        private float pitch() {
            return screen == HOME_CHOICE ? CHOICE_PITCH : PITCH;
        }

        /** All of the screen's rows, the ones scrolled away too. */
        private int total() {
            switch (screen) {
                case MAIN: return MAIN_ROWS;
                case HOME_CHOICE: case CONFIRM: return 2;
                case HOME_SETTINGS: return settingLabels().length;
                case PICKER: return pickLabels.size();
                case SHELF_EDIT: return shelfRows.size();
                case POWER: return powerRows().length;
                case REMOTE_KEYS: return Prefs.KEYS.length;
                default: return 1;
            }
        }

        /** How deep a screen is: going deeper, a new screen comes in from the right; back, from the left. */
        private static int depth(int s) {
            return s == MAIN ? 0 : s == PICKER || s == TRAKT || s == CONFIRM ? 2 : 1;
        }

        void resume() {
            if (running) return;
            running = true;
            whyRows = null;
            if (screen == TRAKT) startTrakt();
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
            Photos.importInbox(getContext(), this::invalidate);   // photos a computer just sent
        }

        void pause() {
            running = false;
            traktRun++;   // no more checks with the screen away: coming back asks for a new code
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = busy();
            for (Spring s : springs) moving |= !s.settled();
            if (moving || (screen == TRAKT && traktWaiting)) invalidate();   // still: no frames
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            scene.setSize(w, h);
            makeBackdrop();
            for (String s : new String[] {"sparkles", "home", "tune", "apps", "widgets", "remote", "bedtime", "info",
                    "account", "language", "location", "movie", "tv", "photos"}) {
                Symbols.warm(getContext(), s, 124 * k);
            }
            Symbols.warm(getContext(), "chevron_right", 22 * k);
            Symbols.warm(getContext(), "chevron_down", 22 * k);
            Symbols.warm(getContext(), "check", 26 * k);
            title = Ui.text(Ui.displaySemibold, 50 * k, 0xFFFFFFFF);
            title.setTextAlign(Paint.Align.CENTER);
            titleFitted = null;
            subtitle = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF);
            subtitle.setTextAlign(Paint.Align.CENTER);
            version = Ui.text(Ui.regular, 22 * k, 0x66FFFFFF);
            version.setTextAlign(Paint.Align.CENTER);
            help = new TextPaint(Ui.text(Ui.regular, 25 * k, 0x99FFFFFF));
            helpGeneration = -1;
            rowLabel = Ui.text(Ui.medium, 30 * k, 0xF2FFFFFF);
            rowValue = Ui.text(Ui.regular, 28 * k, 0x8CFFFFFF);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowSub = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            button = Ui.text(Ui.medium, 30 * k, 0xF2FFFFFF);
            button.setTextAlign(Paint.Align.CENTER);
            header = Ui.text(Ui.semibold, 21 * k, 0x73FFFFFF);
            header.setLetterSpacing(0.06f);
            cellLabel = Ui.text(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue = Ui.numbers(Ui.regular, 25 * k, 0xD9FFFFFF);
            cellValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.regular, 23 * k, AMBER);
            notice = new TextPaint(Ui.text(Ui.regular, 23 * k, 0xB3FFFFFF));
            traktUrl = Ui.text(Ui.semibold, 34 * k, 0x991C1C1E);
            traktUrl.setTextAlign(Paint.Align.CENTER);
            traktCode = Ui.numbers(Ui.displaySemibold, 104 * k, INK);
            traktCode.setTextAlign(Paint.Align.CENTER);
            traktCode.setLetterSpacing(0.12f);
            traktState = Ui.text(Ui.medium, 28 * k, 0xF2FFFFFF);
            fine = Ui.text(Ui.regular, 21 * k, 0x66FFFFFF);
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFF5F5F7);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        }

        private void makeBackdrop() {
            int bw = Math.max(1, w / 2), bh = Math.max(1, h / 2);
            if (backdrop != null) backdrop.recycle();
            backdrop = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(backdrop);
            Paint p = new Paint(Paint.DITHER_FLAG);   // dark gradients band without it
            p.setShader(new LinearGradient(0, 0, 0, bh, 0xFF1A1A1D, 0xFF060607, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, bw, bh, p);
            float tx = LEFT_X / 1920 * bw, ty = (TILE_TOP + TILE / 2) / 1080 * bh;
            p.setShader(new RadialGradient(tx, ty, bw * 0.4f, 0x1CFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, bw, bh, p);
            p.setShader(new RadialGradient(bw * 0.86f, bh * 1.08f, bw * 0.55f, Ui.alpha(Ui.ACCENT, 0.09f),
                    Ui.alpha(Ui.ACCENT, 0), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, bw, bh, p);
            screenRect.set(0, 0, w, h);
        }

        private boolean busy() {
            return !busyText().isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        private String busyText() {
            long at = state.optLong("busyAt"), now = SystemClock.elapsedRealtime();
            boolean stale = now < at || now - at > 10 * 60_000L;
            return stale ? "" : state.optString("busy");
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        private int rowCount() {
            switch (screen) {
                case MAIN: return MAIN_ROWS;
                case HOME_CHOICE: case CONFIRM: return 2;
                case HOME_SETTINGS: return Math.min(SETTINGS_VISIBLE, settingLabels().length);
                case PICKER: return Math.min(PICK_VISIBLE, pickLabels.size());
                case SHELF_EDIT: return Math.min(PICK_VISIBLE, shelfRows.size());
                case POWER: return powerRows().length;
                case TRAKT: case ABOUT: return 1;
                default: return Prefs.KEYS.length;
            }
        }

        /** The first row shown: a long list's scroll; the other screens always show theirs from the top. */
        private int firstShown() {
            return scrolling() ? pickScroll : 0;
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            aim();
            for (Spring s : springs) s.step(dt);
            long up = SystemClock.uptimeMillis();
            float seconds = (up - startedAt) / 1000f;
            c.drawBitmap(backdrop, null, screenRect, backdropPaint);
            // A new screen's content comes in from the side it lies on (deeper: from the right) as it fades in.
            float e = Ui.clamp(enter.value), shift = enterDir * 44 * k * (1 - e);
            drawLeft(c, e, shift);
            drawPlatter(c);
            if (screen == TRAKT) drawTrakt(c, seconds, e, shift);
            else if (screen == ABOUT) drawAbout(c, e, shift);
            else drawRows(c, seconds, e, shift);
            if (screen == MAIN) drawStatus(c, e);
        }

        /**
         * The springs' targets from the screen and the focus. A new screen comes in with the focus already on its row and
         * its list where it was left; within a screen the focus glides and the list scrolls under it.
         */
        private void aim() {
            boolean list = screen != TRAKT && screen != ABOUT;
            float capTop = focus * pitch(), scroll = firstShown() * pitch();
            if (screen != shownScreen) {
                enterDir = shownScreen < 0 || depth(screen) >= depth(shownScreen) ? 1 : -1;
                if (shownScreen >= 0) {
                    enter.snap(0);
                    enter.target = 1;
                } else {
                    platterH.snap(platterHeight());
                }
                shownScreen = screen;
                capY.snap(capTop);
                capH.snap(rowH());
                scrollY.snap(scroll);
                focusA.snap(list ? 1 : 0);
            }
            capY.target = capTop;
            capH.target = rowH();
            scrollY.target = scroll;
            platterH.target = platterHeight();
            platterA.target = screen == TRAKT ? 0 : 1;
            focusA.target = list ? 1 : 0;
        }

        /** The platter's height (1080p px): the screen's rows (a long list's VISIBLE), About's text; Trakt's fades out. */
        private float platterHeight() {
            if (screen == TRAKT) return platterH.target;
            if (screen == ABOUT) return aboutH;
            int n = Math.min(total(), screen == MAIN ? MAIN_ROWS : VISIBLE);
            return 2 * PAD + n * pitch() - (pitch() - rowH());
        }

        /** The left column: the screen's symbol on a Liquid Glass tile, its title, and what the focused row does. */
        private void drawLeft(Canvas c, float e, float shift) {
            float cx = LEFT_X * k, r = 64 * k;
            tile.set(cx - TILE / 2 * k, TILE_TOP * k, cx + TILE / 2 * k, (TILE_TOP + TILE) * k);
            tmp.set(tile);
            tmp.offset(0, 18 * k);
            shadow.setColor(0x4D000000);
            shadows.draw(c, tmp, r, 22 * k, shadow);
            scene.clear(c, tile, r, 0xFFFFFFFF, 0.12f, 0.03f, 0.95f, 1);
            Symbols.draw(getContext(), c, symbol(), cx + shift * 0.4f, tile.centerY(), 124 * k, Ui.alpha(0xF7FFFFFF, e));
            float tx = cx + shift;
            String name = screenTitle();
            fitTitle(name, 700 * k);
            title.setAlpha(Math.round(255 * e));
            c.drawText(name, tx, (TILE_TOP + TILE + 96) * k, title);
            float y = (TILE_TOP + TILE + 150) * k;
            if (screen == MAIN) {
                subtitle.setAlpha(Math.round(0x99 * e));
                c.drawText(Ui.tr("Your TV's home screen and screensaver"), tx, y, subtitle);
                version.setAlpha(Math.round(0x66 * e));
                c.drawText(versionName(getContext()), tx, y + 44 * k, version);
                return;
            }
            String text = helpText();
            if (text.isEmpty()) return;
            if (helpLayout == null || !text.equals(helpText) || helpGeneration != Ui.generation) {
                helpText = text;
                helpGeneration = Ui.generation;
                helpLayout = StaticLayout.Builder.obtain(text, 0, text.length(), help, Math.round(620 * k))
                        .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(8)
                        .setEllipsize(TextUtils.TruncateAt.END).build();
            }
            help.setAlpha(Math.round(0x99 * e));
            c.save();
            c.translate(tx - 310 * k, y - helpLayout.getLineBaseline(0));
            helpLayout.draw(c);
            c.restore();
        }

        /** The title at 50 px, or smaller where it's longer than the column (measured once per title). */
        private void fitTitle(String name, float max) {
            if (name.equals(titleFitted)) return;
            titleFitted = name;
            title.setTextSize(50 * k);
            float width = title.measureText(name);
            if (width > max) title.setTextSize(50 * k * max / width);
        }

        private String symbol() {
            switch (screen) {
                case MAIN: return "sparkles";
                case HOME_CHOICE: return "home";
                case HOME_SETTINGS: return "tune";
                case SHELF_EDIT: return editingTop ? "widgets" : "apps";
                case REMOTE_KEYS: return "remote";
                case POWER: return "bedtime";
                case TRAKT: return "account";
                case ABOUT: return "info";
                case CONFIRM: return confirmIcon;
                default:
                    return pickFor == PICK_CITY ? "location" : pickFor == PICK_LANGUAGE ? "language"
                            : pickFor == PICK_MOVIES ? "movie" : pickFor == PICK_SHOWS ? "tv"
                            : pickFor <= PICK_SLEEP ? "bedtime" : "remote";
            }
        }

        private String screenTitle() {
            switch (screen) {
                case MAIN: return Ui.NAME;
                case HOME_CHOICE: return Ui.tr("Home Screen");
                case HOME_SETTINGS: return Ui.tr("Home Settings");
                case SHELF_EDIT: return editingTop ? Ui.tr("Top Buttons") : Ui.tr("Shelf");
                case REMOTE_KEYS: return Ui.tr("Remote Buttons");
                case POWER: return Ui.tr("Sleep Timer");
                case TRAKT: return "Trakt";
                case ABOUT: return Ui.tr("About");
                case CONFIRM: return confirmTitle;
                default:
                    return pickFor == PICK_CITY ? Ui.tr("City") : pickFor == PICK_LANGUAGE ? Ui.tr("Language")
                            : pickFor == PICK_SLEEP ? Ui.tr("Sleep Timer") : pickFor == PICK_PLACE ? Ui.tr("Position")
                            : pickFor == PICK_MOVIES ? Ui.tr("Movies") : pickFor == PICK_SHOWS ? Ui.tr("Shows")
                            : keyLabels()[pickFor - 1];
            }
        }

        /** Under the title: what the focused row does (or why it can't), or the screen's note. */
        private String helpText() {
            String why = unavailable(focus);
            int at = firstShown() + focus;
            switch (screen) {
                case HOME_CHOICE:
                    return Ui.tr("Switching takes a few seconds; the screen blinks while it measures.");
                case HOME_SETTINGS:
                    if (why != null) return why;
                    if (at == PHOTOS_ROW) return photoCount == 0 ? Ui.tr("Send them from a computer: see “Your own photos” in the README.")
                            : Ui.tr("OK removes them; the arrows turn them on or off.");
                    if (at == TRAKT_ROW) return traktIn ? Ui.tr("OK signs out of Trakt.") : Ui.tr("OK signs in: your Trakt recommendations become the picks.");
                    return Ui.tr("OK or the arrows change the value.");
                case REMOTE_KEYS:
                    return Ui.tr("OK or the arrows change the value.") + "\n\n" + (RemoteKeys.VESTEL ? Ui.tr("YouTube, Prime Video and Google Play: the TV itself controls those buttons.")
                            : RemoteKeys.KTC ? Ui.tr("Netflix, YouTube and Prime Video: the TV itself controls those buttons.")
                            : RemoteKeys.SONY ? Ui.tr("Sony default: the button opens what it always did.")
                            : RemoteKeys.FORMOVIE || RemoteKeys.JMGO ? Ui.tr("The other buttons: the TV itself controls them.")
                            : RemoteKeys.XIAOMI ? Ui.tr("Xiaomi default: the button opens what it always did.")
                            : RemoteKeys.HISENSE ? Ui.tr("Netflix: the TV itself controls that button.")
                            : RemoteKeys.supported() ? Ui.tr("Disney and Globoplay: the TV itself controls those buttons.")
                            : Ui.tr("TCL remote buttons: on this TV they work as always."));
                case POWER:
                    if (why != null) return why;
                    return powerRow() != P_SHOW ? Ui.tr("OK opens the list. ← → change the value.") : Ui.tr("OK or the arrows change the value.");
                case SHELF_EDIT: {
                    if (why != null) return why;
                    String hint = editingTop ? Ui.tr("OK adds or removes a button. ← → change the order.")
                            : HomeApps.maxShelf(getContext()) == Integer.MAX_VALUE ? Ui.tr("OK adds or removes an app. ← → change the order.")
                            : Ui.trf("OK adds or removes (up to {n}). ← → change the order.", "n", HomeApps.maxShelf(getContext()));
                    return !editingTop && HomeApps.lowRam(getContext())
                            ? hint + "\n\n" + Ui.tr("This TV has little memory, so the shelf holds fewer apps.") : hint;
                }
                case PICKER:
                    return Ui.tr("OK picks, Back cancels.");
                case TRAKT:
                    return Ui.tr("Your Trakt recommendations become the home screen's picks. On your phone or computer, open the address below and enter the code.");
                case ABOUT:
                    return versionName(getContext());
                default:
                    return "";
            }
        }

        /** The glass platter behind the rows: clear glass (the aurora shows through, bent at its rim), morphing in height. */
        private void drawPlatter(Canvas c) {
            float a = Ui.clamp(platterA.value);
            platter.set(PLATTER_L * k, PLATTER_TOP * k, PLATTER_R * k, (PLATTER_TOP + Math.max(0, platterH.value)) * k);
            if (a < 0.01f || platter.height() < 1) return;
            float r = Math.min(40 * k, platter.height() / 2);
            tmp.set(platter);
            tmp.offset(0, 16 * k);
            shadow.setColor(Ui.alpha(0x40000000, a));
            // Only outside it: through the clear glass, the shadow's slices showed as a frame. Hollow: the platter's
            // height changes as it morphs.
            c.save();
            c.clipOutRect(platter);
            shadows.draw(c, tmp, r, 22 * k, shadow, true);
            c.restore();
            scene.clear(c, platter, r, 0xFFFFFFFF, 0.06f, 0.015f, 0.6f, a);
        }

        private String[] frameLabels, frameValues;   // the list's labels and values, made once per frame

        /**
         * The rows on the platter, scrolled and clipped to it: the white capsule under the focused one, then every row,
         * then the rows the capsule covers once more in dark, cut at its edges (it glides between them as on the tabs).
         */
        private void drawRows(Canvas c, float seconds, float e, float shift) {
            int n = total();
            if (n == 0) return;
            if (screen == HOME_SETTINGS || screen == REMOTE_KEYS || screen == POWER) {
                frameLabels = listLabels();
                frameValues = listValues();
            }
            float p = pitch() * k, rh = rowH() * k, top = (PLATTER_TOP + PAD) * k, s = scrollY.value * k;
            float left = (PLATTER_L + PAD) * k, right = (PLATTER_R - PAD) * k;
            int first = Math.max(0, (int) Math.floor(s / p) - 1);
            int last = Math.min(n - 1, (int) Math.ceil((s + platter.height()) / p));
            float fa = Ui.clamp(focusA.value) * e;
            c.save();
            c.clipRect(platter.left, platter.top + 4 * k, platter.right, platter.bottom - 4 * k);
            c.translate(shift, 0);
            capsule.set(left, top + capY.value * k, right, top + (capY.value + capH.value) * k);
            float cr = Math.min(capsule.height() / 2, 32 * k);
            if (fa > 0.01f) {
                tmp.set(capsule);
                tmp.offset(0, 8 * k);
                shadow.setColor(Ui.alpha(0x59000000, fa));
                shadows.draw(c, tmp, cr, 22 * k, shadow, true);   // hollow: the white capsule covers the middle
                white.setAlpha(Math.round(255 * fa));
                c.drawRoundRect(capsule, cr, cr, white);
            }
            for (int a = first; a <= last; a++) {
                float y = top + a * p - s;
                rowRect.set(left, y, right, y + rh);
                drawRow(c, a, false, e, seconds);
            }
            if (fa > 0.01f) {
                c.clipRect(capsule);
                for (int a = first; a <= last; a++) {
                    float y = top + a * p - s;
                    if (y > capsule.bottom || y + rh < capsule.top) continue;
                    rowRect.set(left, y, right, y + rh);
                    drawRow(c, a, true, fa, seconds);
                }
            }
            c.restore();
            if (scrolling() && n > rowCount()) {
                // More above or below: a chevron past the platter's edge.
                int ink = Ui.alpha(0x80FFFFFF, e);
                if (pickScroll > 0) {
                    c.save();
                    c.rotate(180, platter.centerX(), platter.top - 24 * k);
                    Symbols.draw(getContext(), c, "chevron_down", platter.centerX(), platter.top - 24 * k, 22 * k, ink);
                    c.restore();
                }
                if (pickScroll + rowCount() < n) {
                    Symbols.draw(getContext(), c, "chevron_down", platter.centerX(), platter.bottom + 28 * k, 22 * k, ink);
                }
            }
        }

        /** Row `a` (of all the screen's, scrolled away or not) in rowRect: light on the glass, or `dark` on the capsule. */
        private void drawRow(Canvas c, int a, boolean dark, float alpha, float seconds) {
            boolean off = unavailableAt(a) != null;
            int ink = Ui.alpha(dark ? INK : 0xF2FFFFFF, alpha), soft = Ui.alpha(dark ? 0x991C1C1E : 0x8CFFFFFF, alpha);
            int dim = Ui.alpha(dark ? 0x731C1C1E : 0x73FFFFFF, alpha), accent = Ui.alpha(dark ? Ui.ACCENT_DEEP : Ui.ACCENT, alpha);
            float x = rowRect.left + 32 * k, right = rowRect.right - 32 * k, mid = rowRect.centerY();
            rowLabel.setColor(off ? dim : ink);
            rowValue.setColor(off ? dim : soft);
            float base = mid + Ui.halfCap(rowLabel);
            switch (screen) {
                case CONFIRM: {
                    button.setColor(a == 0 ? Ui.alpha(dark ? DEEP_RED : RED, alpha) : ink);
                    c.drawText(a == 0 ? confirmAction : Ui.tr("Cancel"), rowRect.centerX(), mid + Ui.halfCap(button), button);
                    return;
                }
                case HOME_CHOICE: {
                    String[][] options = {{Ui.NAME, Ui.tr("No ads, with the new screensaver")},
                            {Ui.tr("Original"), Ui.tr("The TV's factory home screen")}};
                    rowSub.setColor(soft);
                    c.drawText(options[a][0], x, rowRect.top + 44 * k, rowLabel);
                    c.drawText(options[a][1], x, rowRect.top + 80 * k, rowSub);
                    if (a == (freeActive() ? 0 : 1)) drawEnd(c, "check", right, mid, accent);
                    return;
                }
                case MAIN: {
                    c.drawText(mainLabel(a), x, base, rowLabel);
                    if (off) {
                        c.drawText(Ui.tr("Unavailable"), right, base, rowValue);
                    } else if (a == 0 && busy()) {
                        // Switching: the current step in place of the value, with the spinning arc.
                        String text = busyText().isEmpty() ? Ui.tr("Starting…") : busyText();
                        String shown = fit(text, rowValue, 470 * k);
                        c.drawText(shown, right, base, rowValue);
                        spinner.setColor(ink);
                        Ui.spinner(c, right - rowValue.measureText(shown) - 26 * k, mid, 12 * k, seconds * 1.3f, spinner);
                    } else if (a <= 1) {
                        c.drawText(a == 0 ? (freeActive() ? Ui.NAME : Ui.tr("Original")) : Ui.tr("Preview"), right, base, rowValue);
                    } else {
                        drawEnd(c, "chevron_right", right, mid, Ui.alpha(dark ? 0x991C1C1E : 0x66FFFFFF, alpha));
                    }
                    return;
                }
                case PICKER: {
                    c.drawText(fit(pickLabels.get(a), rowLabel, right - x - 60 * k), x, base, rowLabel);
                    if (a == pickCurrent) drawEnd(c, "check", right, mid, accent);
                    return;
                }
                case SHELF_EDIT: {
                    String[] app = shelfRows.get(a);
                    int place = shelfOrder.indexOf(app[0]);
                    if (place < 0 && !off) rowLabel.setColor(Ui.alpha(dark ? 0x801C1C1E : 0x80FFFFFF, alpha));
                    c.drawText(fit(app[1], rowLabel, 520 * k), x, base, rowLabel);
                    float end = right;
                    if (place >= 0 && !off) end -= drawEnd(c, "check", right, mid, accent) + 16 * k;
                    c.drawText(off ? Ui.tr("Unavailable") : place >= 0 ? "#" + (place + 1) : Ui.tr("shelf|Off"), end, base, rowValue);
                    return;
                }
                default: {   // Home Settings, Remote Buttons, Sleep Timer: the setting and its value
                    String label = frameLabels[a], value = off ? Ui.tr("Unavailable") : frameValues[a];
                    c.drawText(label, x, base, rowLabel);
                    float room = right - x - rowLabel.measureText(label) - 40 * k;
                    c.drawText(fit(value, rowValue, room), right, base, rowValue);
                }
            }
        }

        /** A symbol at the row's end (its right edge at `right`); returns its width. */
        private float drawEnd(Canvas c, String symbol, float right, float mid, int color) {
            float size = symbol.equals("check") ? 26 * k : 22 * k, sw = Symbols.width(getContext(), symbol, size);
            Symbols.draw(getContext(), c, symbol, right - sw / 2, mid, size, color);
            return sw;
        }

        /** `text`, cut with an ellipsis to `width` (only made again when it doesn't fit). */
        private static String fit(String text, Paint p, float width) {
            if (p.measureText(text) <= width) return text;
            return TextUtils.ellipsize(text, new TextPaint(p), Math.max(0, width), TextUtils.TruncateAt.END).toString();
        }

        private String mainLabel(int a) {
            switch (a) {
                case 0: return Ui.tr("Home Screen");
                case 1: return Ui.tr("Screensaver");
                case 2: return Ui.tr("Home Settings");
                case 3: return Ui.tr("Shelf");
                case 4: return Ui.tr("Top Buttons");
                case 5: return Ui.tr("Remote Buttons");
                case 6: return Ui.tr("Sleep Timer");
                case 7: return Ui.tr("TV Settings");
                default: return Ui.tr("About");
            }
        }

        /** Under Main's platter: what the switch asks for on the TV, or its last error, whole (up to five lines). */
        private void drawStatus(Canvas c, float e) {
            boolean working = busy();
            String error = Ui.named(state.optString("error"));
            float x = (PLATTER_L + 32) * k, y = platter.bottom + 54 * k;
            warn.setAlpha(Math.round(255 * e));
            if (working && busyText().equals(Ui.tr("Accept “Allow USB debugging?” on the TV (tick “Always allow”)."))) {
                c.drawText(Ui.tr("On the TV: tick “Always allow” and press OK."), x, y, warn);
            } else if (!working && !error.isEmpty()) {
                // Some say what to do (the Wi-Fi one, #14).
                int width = Math.round((PLATTER_R - PLATTER_L - 64) * k);
                if (errorLayout == null || !error.equals(errorShown) || errorLayout.getWidth() != width) {
                    errorShown = error;
                    errorLayout = StaticLayout.Builder.obtain(error, 0, error.length(), new TextPaint(warn), width)
                            .setMaxLines(5).setEllipsize(TextUtils.TruncateAt.END).build();
                }
                c.save();
                c.translate(x, y - errorLayout.getLineBaseline(0));
                errorLayout.draw(c);
                c.restore();
            }
        }

        private float aboutH = 640;          // About's platter height (1080p px), from its last drawing
        private StaticLayout recsLayout;
        private String recsShown;

        /**
         * About, on the platter: the two home screens compared (as measured at the last switch), the picks' state with the
         * credit TMDB asks for, and the latest notices.
         */
        private void drawAbout(Canvas c, float e, float shift) {
            float x = (PLATTER_L + 40) * k + shift, right = (PLATTER_R - 40) * k + shift, y = (PLATTER_TOP + 62) * k;
            float colA = right - 230 * k;
            header.setAlpha(Math.round(0x73 * e));
            c.drawText(Ui.tr("COMPARISON"), x, y, header);
            cellValue.setColor(Ui.alpha(0x73FFFFFF, e));
            c.drawText(Ui.tr("Original"), colA, y, cellValue);
            c.drawText(Ui.NAME, right, y, cellValue);
            cellValue.setColor(Ui.alpha(0xD9FFFFFF, e));
            cellLabel.setAlpha(Math.round(0xD9 * e));
            JSONObject metrics = state.optJSONObject("metrics");
            JSONObject ma = metrics == null ? null : metrics.optJSONObject(HomeMode.ORIGINAL);
            JSONObject mb = metrics == null ? null : metrics.optJSONObject(HomeMode.FREE);
            String[][] rows = comparisonRows();
            for (int i = 0; i < rows.length; i++) {
                float base = y + (52 + 44 * i) * k;
                c.drawText(rows[i][0], x, base, cellLabel);
                c.drawText(metric(ma, rows[i][1]), colA, base, cellValue);
                c.drawText(metric(mb, rows[i][1]), right, base, cellValue);
            }
            y += (52 + 44 * rows.length + 46) * k;

            c.drawText(Ui.tr("RECOMMENDATIONS"), x, y, header);
            String line = recsLine();
            int width = Math.round(right - x);
            if (recsLayout == null || !line.equals(recsShown) || recsLayout.getWidth() != width) {
                recsShown = line;
                recsLayout = StaticLayout.Builder.obtain(line, 0, line.length(), notice, width).setMaxLines(3)
                        .setEllipsize(TextUtils.TruncateAt.END).build();
            }
            notice.setAlpha(Math.round(0xB3 * e));
            c.save();
            c.translate(x, y + 44 * k - recsLayout.getLineBaseline(0));
            recsLayout.draw(c);
            c.restore();
            y += 44 * k + recsLayout.getLineBaseline(recsLayout.getLineCount() - 1) - recsLayout.getLineBaseline(0);
            fine.setAlpha(Math.round(0x66 * e));
            c.drawText(fit(Ui.tr("This product uses the TMDB API but is not endorsed or certified by TMDB."), fine, right - x), x, y + 38 * k, fine);
            y += 38 * k;
            if (!Config.WATCHMODE_KEY.trim().isEmpty()) {
                c.drawText(Ui.tr("Links to the titles by Watchmode."), x, y + 32 * k, fine);
                y += 32 * k;
            }
            y += 60 * k;

            c.drawText(Ui.tr("NOTICES"), x, y, header);
            JSONArray notices = state.optJSONArray("notices");
            int shown = 0;
            for (int i = 0; notices != null && i < notices.length() && shown < 4; i++) {
                JSONObject o = notices.optJSONObject(i);
                if (o == null) continue;
                c.drawText(fit(ago(o.optLong("at")) + " · " + Ui.named(o.optString("text")), notice, right - x), x, y + (44 + 38 * shown) * k, notice);
                shown++;
            }
            if (shown == 0) c.drawText(Ui.tr("None yet."), x, y + 44 * k, notice);
            y += (44 + 38 * (Math.max(1, shown) - 1)) * k;
            aboutH = (y - PLATTER_TOP * k) / k + 40;
            header.setAlpha(0x73);
            cellLabel.setAlpha(0xD9);
            notice.setAlpha(0xB3);
            fine.setAlpha(0x66);
        }

        private static String[][] comparisonRows() {
            return new String[][] {{Ui.tr("Home button"), "homeMs"}, {Ui.tr("Free memory"), "memAvailableMB"},
                    {Ui.tr("Home screen memory"), "homePssMB"}};
        }

        /** The picks' state, in a line. */
        private String recsLine() {
            String error = recs.optString("error");
            int count = recs.optInt("count"), waiting = recs.optInt("waiting");
            if (!hasKey) return Ui.tr("No TMDB key: see the README (config.properties).");
            if (error.equals(Recs.NO_KNOWN_APPS)) return Ui.tr("None of the TV's streaming apps can give picks.");
            if (error.equals(Recs.NO_APPS)) return Ui.trf("TMDB has none of the TV's apps for tmdb_region={region}.", "region", Recs.REGION);
            if (!error.isEmpty()) return Ui.trf("Error: {error}.", "error", error);
            // With Filmix, titles are looked up as the rows reach them: the rest of the list isn't missing, only waiting.
            if (count > 0 && waiting > 0) return Ui.trf("On · {n} titles ready, {m} more are looked up as you scroll.", "n", count, "m", waiting);
            if (count > 0) return Ui.trf("On · {n} titles from the TV's apps.", "n", count);
            return Ui.tr("Fetching titles…");
        }

        /** About for TalkBack: everything it shows, in order. */
        private String aboutText() {
            StringBuilder b = new StringBuilder(versionName(getContext())).append(". ");
            JSONObject metrics = state.optJSONObject("metrics");
            JSONObject ma = metrics == null ? null : metrics.optJSONObject(HomeMode.ORIGINAL);
            JSONObject mb = metrics == null ? null : metrics.optJSONObject(HomeMode.FREE);
            for (String[] row : comparisonRows()) {
                b.append(row[0]).append(": ").append(Ui.tr("Original")).append(' ').append(metric(ma, row[1]))
                        .append(", ").append(Ui.NAME).append(" ").append(metric(mb, row[1])).append(". ");
            }
            b.append(recsLine()).append(' ');
            JSONArray notices = state.optJSONArray("notices");
            for (int i = 0; notices != null && i < notices.length() && i < 4; i++) {
                JSONObject o = notices.optJSONObject(i);
                if (o != null) b.append(ago(o.optLong("at"))).append(": ").append(Ui.named(o.optString("text"))).append(". ");
            }
            return b.toString();
        }

        /** Asks before something that can't be taken back: `action` (in red) or Cancel, which has the focus. */
        private void openConfirm(String question, String action, String icon, Runnable run) {
            backScreen = screen;
            backFocus = focus;
            backScroll = pickScroll;
            confirmTitle = question;
            confirmAction = action;
            confirmIcon = icon;
            confirmRun = run;
            screen = CONFIRM;
            pickScroll = 0;
            focus = 1;   // one stray OK changes nothing
            invalidate();
            spoken.focusMoved();
        }

        /** Back where the question came from; `run`: the action was chosen. */
        private void closeConfirm(boolean run) {
            Runnable action = confirmRun;
            confirmRun = null;
            screen = backScreen;
            focus = backFocus;
            pickScroll = backScroll;
            if (run && action != null) action.run();
            invalidate();
            spoken.focusMoved();
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private static String metric(JSONObject m, String key) {
            if (m == null || !m.has(key)) return "—";
            long v = m.optLong(key);
            if (key.equals("homeMs")) return Ui.trf("{n} ms", "n", v);
            if (key.equals("memAvailableMB") && v >= 1024) return Ui.trf("{n} GB", "n", String.format(Ui.locale(), "%.2f", v / 1024.0));
            return Ui.trf("{n} MB", "n", v);
        }

        private static String ago(long at) {
            long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
            if (minutes < 1) return Ui.tr("now");
            if (minutes < 60) return Ui.trf("{n} min ago", "n", minutes);
            if (minutes < 48 * 60) return Ui.trf("{n} h ago", "n", minutes / 60);
            return Ui.plural("plural|one:{n} day ago|other:{n} days ago", minutes / 1440);
        }

        private static String versionName(Context c) {
            try {
                return Ui.NAME + " " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return Ui.NAME;
            }
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && focus > 0) focus--;
                    else if (down && scrolling() && pickScroll > 0) pickScroll--;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && focus < rowCount() - 1) focus++;
                    else if (down && scrolling() && pickScroll + rowCount() < listSize()) pickScroll++;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && screen == SHELF_EDIT) {
                        moveShelfApp(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    } else if (down && (screen == HOME_SETTINGS || screen == REMOTE_KEYS || screen == POWER)) {
                        adjust(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    }
                    whyRows = null;   // a value or the rows' order changed: what each row needs may have too
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) {
                            activate();
                            whyRows = null;   // the same after OK
                        }
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (screen == MAIN) return false;   // leaves the settings
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled() && screen == CONFIRM) {
                            closeConfirm(false);
                        } else if (backDown && !e.isCanceled()) {
                            if (screen == PICKER && (pickFor == PICK_MOVIES || pickFor == PICK_SHOWS)) {
                                showSetting(pickFor == PICK_MOVIES ? MOVIES_ROW : SHOWS_ROW);
                            } else if (screen == PICKER && pickFor <= PICK_SLEEP) {
                                focus = powerFocus(pickFor == PICK_SLEEP ? P_SLEEP : P_WHERE);
                                pickScroll = 0;   // the picker's scroll isn't this short list's
                                screen = POWER;
                            } else if (screen == PICKER && (pickFor == PICK_CITY || pickFor == PICK_LANGUAGE)) {
                                showSetting(pickFor == PICK_CITY ? CITY_ROW : 0);
                            } else if (screen == PICKER) {
                                focus = pickFor - 1;
                                pickScroll = 0;
                                screen = REMOTE_KEYS;
                            } else if (screen == TRAKT) {
                                traktRun++;   // the code is dropped: its checks stop
                                pickScroll = 0;
                                focus = TRAKT_ROW;
                                screen = HOME_SETTINGS;
                            } else {
                                focus = screen == HOME_SETTINGS ? 2 : screen == SHELF_EDIT ? (editingTop ? 4 : 3)
                                        : screen == REMOTE_KEYS ? 5 : screen == POWER ? 6 : screen == ABOUT ? 8 : 0;
                                pickScroll = 0;
                                screen = MAIN;
                            }
                        }
                        backDown = false;
                    }
                    invalidate();
                    spoken.focusMoved();
                    return true;
                default:
                    return false;
            }
        }

        // MARK: - Home Settings and Remote Buttons

        private static final String[] UNITS = {"auto", "C", "F"}, THEMES = {"auto", "light", "dark", "photo"};
        /** Home Settings: language, temperature, theme, my photos, sounds, watch from the screensaver, Trakt, the Trakt lists
         *  for Movies and Shows, weather, city, forecast, now playing, quotes, then one row per coin. */
        private static final int PHOTOS_ROW = 3, SAVER_ROW = 5, TRAKT_ROW = 6, MOVIES_ROW = 7, SHOWS_ROW = 8, WEATHER_ROW = 9,
                CITY_ROW = 10, FORECAST_ROW = 11, PLAYING_ROW = 12, QUOTES_ROW = 13, COIN_ROWS = 14;
        /** Home Settings rows on screen at once (what fits above the hints at 1080p); a longer list scrolls. */
        private static final int SETTINGS_VISIBLE = 10;

        private boolean dollar() {
            return "BRL".equals(Place.currency());
        }

        private String[] settingLabels() {
            List<String> l = new ArrayList<>();
            l.add(Ui.tr("Language"));
            l.add(Ui.tr("Temperature"));
            l.add(Ui.tr("Theme"));
            l.add(Ui.tr("My Photos"));
            l.add(Ui.tr("Navigation sounds"));
            l.add(Ui.tr("Watch from the screensaver"));
            l.add("Trakt");
            l.add(Ui.tr("Movies"));
            l.add(Ui.tr("Shows"));
            l.add(Ui.tr("Weather widget"));
            l.add(Ui.tr("City"));
            l.add(Ui.tr("Forecast"));
            l.add(Ui.tr("Now Playing widget"));
            l.add(Ui.tr("Quotes widget"));
            l.add("Bitcoin");
            l.add("Ethereum");
            l.add("Solana");
            if (dollar()) l.add(Ui.tr("Dollar"));
            return l.toArray(new String[0]);
        }

        private String[] settingValues() {
            String[] labels = settingLabels();
            String[] v = new String[labels.length];
            String lang = Prefs.language();
            v[0] = lang.equals("auto") ? Ui.tr("Automatic") : Ui.languageName(lang);
            String unit = Prefs.temperature();
            v[1] = unit.equals("C") ? "°C" : unit.equals("F") ? "°F" : Ui.tr("unit|Automatic");
            String theme = Prefs.theme();
            v[2] = theme.equals("light") ? Ui.tr("Light") : theme.equals("dark") ? Ui.tr("Dark")
                    : theme.equals("photo") && !Recs.hasKey(getContext()) ? Ui.tr("Photo of the day") : Ui.tr("Automatic");
            int photos = photoCount;
            v[PHOTOS_ROW] = photos == 0 ? Ui.tr("photos|None")
                    : Prefs.photos() ? Ui.plural("plural|one:{n} photo|other:{n} photos", photos) : Ui.tr("Off");
            v[4] = onOff(Prefs.sounds());
            v[SAVER_ROW] = onOff(Prefs.saverWatch());
            v[TRAKT_ROW] = !traktIn ? Ui.tr("Sign in") : traktUser.isEmpty() ? Ui.tr("Signed in") : traktUser;
            v[MOVIES_ROW] = listsFor == MOVIES_ROW ? Ui.tr("Loading your lists…") : listName("movie");
            v[SHOWS_ROW] = listsFor == SHOWS_ROW ? Ui.tr("Loading your lists…") : listName("tv");
            v[WEATHER_ROW] = onOff(Prefs.weather());
            v[CITY_ROW] = !cityStatus.isEmpty() ? cityStatus : Prefs.city().isEmpty() ? "—" : Prefs.city();
            v[FORECAST_ROW] = Prefs.forecast().equals("days") ? Ui.tr("Next days") : Ui.tr("Next hours");
            v[PLAYING_ROW] = onOff(Prefs.nowPlaying());
            v[QUOTES_ROW] = onOff(Prefs.quotes());
            // No quotes widget, nowhere to show prices: the coins read as off (and stay locked) until it is back on.
            for (int i = COIN_ROWS; i < v.length; i++) v[i] = onOff(Prefs.quotes() && Prefs.coin(i - COIN_ROWS));
            return v;
        }

        /** "auto", then English and every language with a catalogue. */
        private static String[] languageChoices() {
            String[] all = Ui.languages();
            String[] v = new String[all.length + 1];
            v[0] = "auto";
            System.arraycopy(all, 0, v, 1, all.length);
            return v;
        }

        /**
         * What row `a` (of all the screen's) needs from the TV (Capabilities.NEED_…), or null. A new option that needs
         * something adds its line here; drawing, keys and TalkBack then treat it as unavailable where the TV lacks it.
         */
        private String need(int a) {
            switch (screen) {
                case MAIN:
                    return a == 5 ? Capabilities.NEED_REMOTE_BUTTONS : a == 6 ? Capabilities.NEED_ADB : null;
                case HOME_SETTINGS:
                    return a == PLAYING_ROW ? Capabilities.NEED_LISTENER : null;
                case POWER: {
                    int[] rows = powerRows();
                    return a < rows.length && rows[a] == P_WHERE ? Capabilities.NEED_OVERLAY : null;
                }
                case SHELF_EDIT: {   // only to add it: one already among the buttons can still move or go
                    if (a >= shelfRows.size()) return null;
                    String key = shelfRows.get(a)[0];
                    return editingTop && HomeApps.TOP_LIVE.equals(key) && !shelfOrder.contains(key) ? Capabilities.NEED_LIVE_TV : null;
                }
                default:
                    return null;
            }
        }

        /** Why visible row `i` can't be used here, for the person to read; null where it works. */
        private String unavailable(int i) {
            return i < 0 || i >= rowCount() ? null : unavailableAt(firstShown() + i);
        }

        /** The same for row `a` of all the screen's (worked out for them all once per screen, and again after changes). */
        private String unavailableAt(int a) {
            int n = total();
            if (a < 0 || a >= n) return null;
            if (whyRows == null || whyRows.length != n || whyScreen != screen) {
                whyRows = new String[n];
                whyScreen = screen;
                for (int r = 0; r < n; r++) {
                    String need = need(r);
                    whyRows[r] = screen == HOME_SETTINGS && r >= COIN_ROWS && !Prefs.quotes()
                            ? Ui.tr("Turn on the Quotes widget to pick its coins.")
                            : screen == HOME_SETTINGS && r == TRAKT_ROW && !Trakt.hasKey()
                            ? Ui.tr("Needs trakt_client_id in config.properties: see the README.")
                            : screen == HOME_SETTINGS && (r == MOVIES_ROW || r == SHOWS_ROW) && !traktIn
                            ? Ui.tr("Sign in to Trakt to pick one of your lists.")
                            : need == null ? null : Capabilities.missing(getContext(), need);
                }
            }
            return whyRows[a];
        }

        /** OK on an unavailable row: a toast says why; false if it isn't unavailable. */
        private boolean tellWhy() {
            String why = unavailable(focus);
            if (whyToast != null) whyToast.cancel();
            whyToast = why == null ? null : Toast.makeText(getContext(), why, Toast.LENGTH_LONG);
            if (whyToast != null) whyToast.show();
            return why != null;
        }

        /**
         * Sleep Timer's rows, by what they set: the timer, whether it shows on screen and (where it shows over other
         * apps) where. Values are picked from lists (OK), as on the TV's own settings; ← → step them.
         */
        private static final int P_SLEEP = 0, P_SHOW = 1, P_WHERE = 2;

        private int[] powerRows() {
            return Prefs.sleepShown() ? new int[] {P_SLEEP, P_SHOW, P_WHERE} : new int[] {P_SLEEP, P_SHOW};
        }

        private int powerRow() {
            int[] rows = powerRows();
            return rows[Math.min(focus, rows.length - 1)];
        }

        private int powerFocus(int id) {
            int[] rows = powerRows();
            for (int i = 0; i < rows.length; i++) if (rows[i] == id) return i;
            return 0;
        }

        private String[] powerLabels() {
            int[] rows = powerRows();
            String[] out = new String[rows.length];
            for (int i = 0; i < rows.length; i++) {
                switch (rows[i]) {
                    case P_SLEEP: out[i] = Ui.tr("Sleep Timer"); break;
                    case P_SHOW: out[i] = Ui.tr("Show Time Left"); break;
                    default: out[i] = Ui.tr("Position");
                }
            }
            return out;
        }

        private String[] powerValues() {
            int[] rows = powerRows();
            String[] out = new String[rows.length];
            for (int i = 0; i < rows.length; i++) {
                switch (rows[i]) {
                    case P_SLEEP: out[i] = sleepLeft == 0 ? Ui.tr("Off") : Ui.trf("{time} left", "time", SleepTimer.duration(sleepLeft)); break;
                    case P_SHOW: out[i] = onOff(Prefs.sleepShown()); break;
                    default: out[i] = SleepOverlay.placeName(Prefs.sleepPlace());
                }
            }
            return out;
        }

        /** OK on a Sleep Timer row: its list of choices, or (on a switch) the other value. */
        private void activatePower() {
            Context c = getContext();
            List<String[]> list = new ArrayList<>();
            switch (powerRow()) {
                case P_SLEEP:
                    list.add(new String[] {"0", Ui.tr("Off")});
                    for (int m : new int[] {5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240}) {
                        list.add(new String[] {String.valueOf(m), SleepTimer.duration(m)});
                    }
                    int left = SleepTimer.minutesLeft(c);
                    openChoices(PICK_SLEEP, list, String.valueOf(left));
                    return;
                case P_WHERE:
                    for (int i = 0; i < SleepOverlay.PLACES; i++) list.add(new String[] {String.valueOf(i), SleepOverlay.placeName(i)});
                    openChoices(PICK_PLACE, list, String.valueOf(Prefs.sleepPlace()));
                    return;
                default:
                    adjust(1);
            }
        }

        /** What Movies (kind "movie") or Shows ("tv") draws from: the list picked for it, or Trakt's recommendations. */
        private static String listName(String kind) {
            return !Prefs.recsListRef(kind).isEmpty() ? Prefs.recsListName(kind) : Ui.tr("Trakt recommendations");
        }

        /**
         * OK on Movies or Shows: the person's Trakt lists, asked for now, to pick from: Trakt's recommendations, their
         * smart lists of that kind, their personal lists.
         */
        private void askLists(int row) {
            if (listsFor != 0) return;
            listsFor = row;
            invalidate();
            new Thread(() -> {
                List<Trakt.UserList> lists;
                try {
                    lists = Trakt.lists();
                } catch (IOException e) {
                    AppLog.w("Trakt lists: " + e.getMessage());
                    lists = null;
                }
                List<Trakt.UserList> found = lists;
                handler.post(() -> {
                    listsFor = 0;
                    invalidate();
                    if (screen != HOME_SETTINGS) return;
                    if (found == null) {
                        Toast.makeText(getContext(), Ui.tr("Trakt didn't answer. Try again in a moment."), Toast.LENGTH_LONG).show();
                        return;
                    }
                    String kind = row == MOVIES_ROW ? "movie" : "tv", media = row == MOVIES_ROW ? "movies" : "shows";
                    List<String[]> list = new ArrayList<>();
                    list.add(new String[] {"", Ui.tr("Trakt recommendations")});
                    for (Trakt.UserList l : found) {
                        if (l.media.isEmpty() || l.media.equals(media)) list.add(new String[] {l.ref, l.name});
                    }
                    openChoices(row == MOVIES_ROW ? PICK_MOVIES : PICK_SHOWS, list, Prefs.recsListRef(kind));
                    if (list.size() == 1) {
                        Toast.makeText(getContext(), Ui.tr("You have no lists on Trakt yet."), Toast.LENGTH_LONG).show();
                    }
                    spoken.focusMoved();
                });
            }, "trakt-lists").start();
        }

        /** A list picked for Movies or Shows: the tab draws from it (Recs fetches again), back on its row. */
        private void pickList() {
            String[] choice = choices.get(pickScroll + focus);
            boolean movies = pickFor == PICK_MOVIES;
            Prefs.setRecsList(movies ? "movie" : "tv", choice[0], choice[1]);
            Recs.get(getContext()).refreshIfStale();
            showSetting(movies ? MOVIES_ROW : SHOWS_ROW);
        }

        /** Home Settings, the focus on `row`, scrolled so that it shows. */
        private void showSetting(int row) {
            screen = HOME_SETTINGS;
            pickScroll = Math.max(0, row - (SETTINGS_VISIBLE - 1));
            focus = row - pickScroll;
        }

        private void openChoices(int which, List<String[]> list, String now) {
            choices.clear();
            choices.addAll(list);
            choiceNow = now;
            openPicker(which);
        }

        /** OK in a Sleep Timer picker: applied, and back to its row. */
        private void pickPower() {
            Context c = getContext();
            String[] choice = choices.get(pickScroll + focus);
            int row = pickFor == PICK_SLEEP ? P_SLEEP : P_WHERE;
            if (pickFor == PICK_SLEEP) {
                SleepTimer.set(c, Integer.parseInt(choice[0]));
                sleepLeft = SleepTimer.minutesLeft(c);
            } else {
                Prefs.setSleepPlace(Integer.parseInt(choice[0]));
                SleepOverlay.update(c);
            }
            pickScroll = 0;
            screen = POWER;
            focus = powerFocus(row);
        }

        private String[] listLabels() {
            return screen == REMOTE_KEYS ? keyLabels() : screen == POWER ? powerLabels() : settingLabels();
        }

        private String[] listValues() {
            return screen == REMOTE_KEYS ? keyValues() : screen == POWER ? powerValues() : settingValues();
        }

        private static String onOff(boolean on) {
            return on ? Ui.tr("On") : Ui.tr("Off");
        }

        private String[] keyLabels() {
            if (RemoteKeys.HISENSE) return new String[] {Ui.tr("Prime Video button"), Ui.tr("YouTube button"),
                    Ui.tr("Disney+ button"), Ui.tr("All Apps button"), Ui.tr("Settings button"), Ui.tr("Profile button")};
            if (RemoteKeys.JMGO) return new String[] {Ui.tr("Settings button")};
            if (RemoteKeys.STEM_NETFLIX) return new String[] {Ui.tr("Netflix button")};
            if (RemoteKeys.KTC) return new String[] {Ui.tr("Tabii button")};
            if (RemoteKeys.XIAOMI) return new String[] {Ui.tr("Mi button"), Ui.tr("YouTube button"), Ui.tr("PatchWall button"),
                    Ui.tr("Okko button"), Ui.tr("VK button"), Ui.tr("Rutube button"), Ui.tr("KION button"),
                    Ui.tr("Wink button"), Ui.tr("IVI button")};
            if (RemoteKeys.SONY) return new String[] {Ui.tr("BRAVIA CORE button"),
                    Ui.tr("Netflix button"), Ui.tr("Disney+ button"),
                    Ui.tr("Prime Video button"), Ui.tr("YouTube button"),
                    Ui.tr("Crunchyroll button"), Ui.tr("Google Play button")};
            return new String[] {Ui.tr("Netflix button"), Ui.tr("YouTube button"),
                    Ui.tr("Prime Video button"), Ui.tr("TCL Channel button")};
        }

        private String[] keyValues() {
            String[] v = new String[Prefs.KEYS.length];
            for (int i = 0; i < v.length; i++) {
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[i]));
                v[i] = at >= 0 ? appLabels.get(at) : Ui.tr("Not installed");
            }
            return v;
        }

        /** Installed TV apps, by name, after "Nothing": what a remote button can open. */
        private void loadApps() {
            List<String[]> found = new ArrayList<>();
            for (String[] app : HomeApps.launchable(getContext())) {
                if (!app[0].equals(getContext().getPackageName())) found.add(app);
            }
            Collections.sort(found, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
            appPkgs.clear();
            appLabels.clear();
            appPkgs.add("");
            // On Sony and Xiaomi, "nothing" leaves the key to the TV, which opens its usual app.
            appLabels.add(RemoteKeys.SONY ? Ui.tr("Sony default") : RemoteKeys.XIAOMI ? Ui.tr("Xiaomi default") : Ui.tr("Nothing"));
            // On Xiaomi the Mi button is the remote's Home button: it can open this home screen.
            if (RemoteKeys.XIAOMI) {
                appPkgs.add(HomeMode.PKG);
                appLabels.add(Ui.NAME);
            }
            if (RemoteKeys.HISENSE) {
                appPkgs.add(Prefs.ALL_APPS);
                appLabels.add(Ui.tr("All Apps"));
            }
            for (String[] f : found) {
                appPkgs.add(f[0]);
                appLabels.add(f[1]);
            }
        }

        /** Changes the focused row's value one step (OK and → go forward, ← goes back). */
        private void adjust(int dir) {
            // Refused or without Free My TV mode, Now Playing can still be turned off (and the refusal is then forgotten).
            boolean turnOff = screen == HOME_SETTINGS && pickScroll + focus == PLAYING_ROW && Prefs.nowPlaying();
            if (unavailable(focus) != null && !turnOff) return;
            if (screen == POWER) {
                int row = powerRow();
                if (row == P_SLEEP) {
                    SleepTimer.step(getContext(), dir);
                    sleepLeft = SleepTimer.minutesLeft(getContext());
                } else if (row == P_SHOW) {
                    Prefs.setSleepShown(!Prefs.sleepShown());
                    SleepOverlay.update(getContext());
                } else if (row == P_WHERE) {
                    Prefs.setSleepPlace((Prefs.sleepPlace() + dir + SleepOverlay.PLACES) % SleepOverlay.PLACES);
                    SleepOverlay.update(getContext());
                }
                focus = powerFocus(row);   // rows come and go with the switches above them
                return;
            }
            if (screen == REMOTE_KEYS) {
                int n = appPkgs.size();
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[focus]));
                int next = at < 0 ? (dir > 0 ? 0 : n - 1) : (at + dir + n) % n;
                Prefs.setKeyApp(Prefs.KEYS[focus], appPkgs.get(next));
                return;
            }
            int row = pickScroll + focus;
            switch (row) {
                case 0: break;   // OK opens the language picker.
                case 1: {
                    int at = java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature());
                    Prefs.setTemperature(UNITS[(at + dir + UNITS.length) % UNITS.length]);
                    break;
                }
                case 2: {
                    // The photo of the day shows only without picks (with them, the first card's photo is the background).
                    int n = Recs.hasKey(getContext()) ? THEMES.length - 1 : THEMES.length;
                    int at = java.util.Arrays.asList(THEMES).indexOf(Prefs.theme());
                    if (at >= n) at = 0;
                    Prefs.setTheme(THEMES[(at + dir + n) % n]);
                    SleepOverlay.update(getContext());   // the pill's glass follows
                    break;
                }
                case PHOTOS_ROW:
                    if (Photos.files(getContext()).isEmpty()) return;
                    Prefs.setPhotos(!Prefs.photos());
                    return;
                case 4: Prefs.setSounds(!Prefs.sounds()); break;
                case SAVER_ROW: Prefs.setSaverWatch(!Prefs.saverWatch()); break;
                case WEATHER_ROW: Prefs.setWeather(!Prefs.weather()); break;
                case CITY_ROW: return;
                case TRAKT_ROW: return;   // OK signs in or out
                case MOVIES_ROW: case SHOWS_ROW: return;   // OK opens the person's lists
                case FORECAST_ROW:   // both are in memory: nothing to fetch
                    Prefs.setForecast(Prefs.forecast().equals("days") ? "hours" : "days");
                    return;
                case PLAYING_ROW:   // the listener follows the widget: allowed while it's on, taken back when it goes off
                    Prefs.setNowPlaying(!Prefs.nowPlaying());
                    HomeMode.nowPlayingAccess(getContext());
                    break;
                case QUOTES_ROW: Prefs.setQuotes(!Prefs.quotes()); break;
                default:
                    if (!Prefs.quotes()) return;
                    Prefs.setCoin(row - COIN_ROWS, !Prefs.coin(row - COIN_ROWS));
                    break;
            }
            HomeData.get(getContext()).settingsChanged();
        }

        private void askCity() {
            if (Ui.tr("Searching…").equals(cityStatus)) return;
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setHint(Ui.tr("Type the city's name"));
            field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("City"))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Search"), (d, which) -> searchCity(field.getText().toString()))
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                searchCity(field.getText().toString());
                return true;
            });
            dialog.show();
        }

        // MARK: - My Photos (sent from a computer: tools/photos.py)

        /** OK on My Photos: asks, then deletes them all. */
        private void askRemovePhotos() {
            int n = Photos.files(getContext()).size();
            if (n == 0) return;
            openConfirm(Ui.plural("plural|one:Remove {n} photo?|other:Remove {n} photos?", n), Ui.tr("Remove"), "photos", () -> {
                Photos.removeAll(getContext());
                photoCount = Photos.files(getContext()).size();
            });
        }

        private void searchCity(String name) {
            if (name.trim().isEmpty()) return;
            cityStatus = Ui.tr("Searching…");
            invalidate();
            new Thread(() -> {
                List<String[]> found;
                String error;
                try {
                    found = HomeData.findCity(name);
                    error = found.isEmpty() ? Ui.tr("No city with that name.") : "";
                } catch (Exception e) {
                    found = new ArrayList<>();
                    error = Ui.trf("Couldn't search now: {error}", "error", e.getMessage());
                    AppLog.w("city search: " + e.getMessage());
                }
                List<String[]> result = found;
                String message = error;
                handler.post(() -> {
                    cityStatus = message;
                    if (!result.isEmpty() && screen == HOME_SETTINGS) {
                        cities.clear();
                        cities.addAll(result);
                        openPicker(PICK_CITY);
                        spoken.focusMoved();
                    }
                    invalidate();
                    if (!message.isEmpty()) handler.postDelayed(() -> { cityStatus = ""; invalidate(); }, 4000);
                });
            }).start();
        }

        private boolean scrolling() {
            return screen == PICKER || screen == SHELF_EDIT || screen == HOME_SETTINGS;
        }

        private int listSize() {
            return screen == SHELF_EDIT ? shelfRows.size() : screen == HOME_SETTINGS ? settingLabels().length : pickLabels.size();
        }

        // MARK: - Shelf editor

        private void openShelfEditor(boolean top) {
            editingTop = top;
            shelfOrder.clear();
            if (top) {
                shelfOrder.addAll(HomeApps.topButtonKeys());
                rebuildShelfRows(HomeApps.topCandidates());
            } else {
                // Only the apps the shelf shows: one that was uninstalled would hold a place (and count toward the
                // limit) without a row to take it off.
                PackageManager pm = getContext().getPackageManager();
                for (String pkg : HomeApps.shelfPackages(getContext())) if (HomeApps.launchIntent(pm, pkg) != null) shelfOrder.add(pkg);
                rebuildShelfRows(HomeApps.shelfCandidates(getContext()));
            }
            pickScroll = 0;
            focus = 0;
            screen = SHELF_EDIT;
        }

        /** Selected apps first, in shelf order, then the rest in the order they came. */
        private void rebuildShelfRows(List<String[]> candidates) {
            shelfRows.clear();
            for (String pkg : shelfOrder) {
                for (String[] cand : candidates) if (cand[0].equals(pkg)) { shelfRows.add(cand); break; }
            }
            for (String[] cand : candidates) if (!shelfOrder.contains(cand[0])) shelfRows.add(cand);
        }

        /** Same rows after a change: keeps the focus on the app that was focused. */
        private void refreshShelfRows(String focusedPkg) {
            List<String[]> all = new ArrayList<>(shelfRows);
            rebuildShelfRows(all);
            for (int i = 0; i < shelfRows.size(); i++) {
                if (!shelfRows.get(i)[0].equals(focusedPkg)) continue;
                int visible = rowCount();
                if (i < pickScroll) pickScroll = i;
                else if (i >= pickScroll + visible) pickScroll = i - visible + 1;
                focus = i - pickScroll;
                break;
            }
            if (editingTop) Prefs.setTopButtons(shelfOrder); else Prefs.setShelf(shelfOrder);
        }

        /** OK: puts the app on the shelf (at the end) or takes it off. */
        private void toggleShelfApp() {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            if (shelfOrder.contains(pkg)) shelfOrder.remove(pkg);
            else if (editingTop || shelfOrder.size() < HomeApps.maxShelf(getContext())) shelfOrder.add(pkg);   // three buttons: no limit to reach
            else return;
            refreshShelfRows(pkg);
        }

        /** ← → : moves a shelf app one place earlier or later. */
        private void moveShelfApp(int dir) {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            int at = shelfOrder.indexOf(pkg), to = at + dir;
            if (at < 0 || to < 0 || to >= shelfOrder.size()) return;
            shelfOrder.remove(at);
            shelfOrder.add(to, pkg);
            refreshShelfRows(pkg);
        }

        private void openPicker(int which) {
            pickFor = which;
            pickLabels.clear();
            if (which == PICK_CITY) {
                for (String[] city : cities) pickLabels.add(city[0]);
                pickCurrent = -1;
            } else if (which == PICK_LANGUAGE) {
                String[] choices = languageChoices();
                for (String code : choices) {
                    pickLabels.add(code.equals("auto") ? Ui.tr("Automatic") : Ui.languageName(code));
                }
                pickCurrent = java.util.Arrays.asList(choices).indexOf(Prefs.language());
            } else if (which <= PICK_SLEEP) {
                pickCurrent = -1;
                for (int i = 0; i < choices.size(); i++) {
                    pickLabels.add(choices.get(i)[1]);
                    if (choices.get(i)[0].equals(choiceNow)) pickCurrent = i;
                }
            } else {
                pickLabels.addAll(appLabels);
                pickCurrent = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[which - 1]));
            }
            int visible = Math.min(PICK_VISIBLE, pickLabels.size());
            int at = Math.max(0, pickCurrent);
            pickScroll = Math.max(0, Math.min(at - 3, pickLabels.size() - visible));
            focus = at - pickScroll;
            screen = PICKER;
        }

        // MARK: - Trakt sign-in (Home Settings → Trakt)

        /** Asks Trakt for a code, then checks at Trakt's pace whether it was entered (each attempt has its own run). */
        private void startTrakt() {
            int run = ++traktRun;
            traktDevice = null;
            traktWaiting = true;
            traktStatus = Ui.tr("Getting a code…");
            invalidate();
            new Thread(() -> {
                Trakt.Device d = null;
                String error = null;
                try {
                    d = Trakt.startSignIn();
                } catch (Exception e) {
                    error = e.getMessage();
                    AppLog.w("Trakt sign-in: " + error);
                }
                Trakt.Device device = d;
                String message = error;
                handler.post(() -> {
                    if (run != traktRun) return;
                    if (device == null) {
                        traktWaiting = false;
                        traktStatus = Ui.trf("Error: {error}.", "error", message == null ? "?" : message);
                    } else {
                        traktDevice = device;
                        traktStatus = Ui.tr("Waiting for the code…");
                        handler.postDelayed(() -> checkTrakt(run, device.intervalMs), device.intervalMs);
                    }
                    invalidate();
                    spoken.focusMoved();   // TalkBack reads the code
                });
            }, "trakt-sign-in").start();
        }

        private void checkTrakt(int run, long interval) {
            Trakt.Device d = traktDevice;
            if (run != traktRun || !running || d == null) return;
            new Thread(() -> {
                int result;
                String error = null;
                try {
                    result = Trakt.checkSignIn(d);
                } catch (Exception e) {
                    result = -1;
                    error = e.getMessage();
                }
                int r = result;
                String message = error;
                handler.post(() -> {
                    if (run != traktRun) return;
                    if (r == Trakt.SIGNED_IN) {
                        traktSignedIn();
                        return;
                    }
                    if (r == Trakt.EXPIRED) {
                        traktDevice = null;
                        traktWaiting = false;
                        traktStatus = Ui.tr("The code expired.");
                        invalidate();
                        spoken.focusMoved();
                        return;
                    }
                    // A failed check isn't the end: Trakt is asked again until the code expires.
                    traktStatus = r < 0 ? Ui.trf("Error: {error}.", "error", message == null ? "?" : message)
                            : Ui.tr("Waiting for the code…");
                    long next = r == Trakt.SLOW_DOWN ? interval + 1000 : interval;
                    handler.postDelayed(() -> checkTrakt(run, next), next);
                    invalidate();
                });
            }, "trakt-sign-in").start();
        }

        private void traktSignedIn() {
            traktRun++;
            traktIn = true;
            traktUser = Trakt.user();
            traktDevice = null;
            traktWaiting = false;
            Recs.get(getContext()).refreshIfStale();   // the picks come from this account now
            Toast.makeText(getContext(), traktUser.isEmpty() ? Ui.tr("Signed in")
                    : Ui.trf("Signed in to Trakt as {name}.", "name", traktUser), Toast.LENGTH_LONG).show();
            screen = HOME_SETTINGS;
            pickScroll = 0;
            focus = TRAKT_ROW;
            invalidate();
            spoken.focusMoved();
        }

        private void askSignOut() {
            openConfirm(Ui.tr("Sign out of Trakt?"), Ui.tr("Sign out"), "account", () -> new Thread(() -> {
                Trakt.signOut();
                handler.post(() -> {
                    traktIn = false;
                    traktUser = "";
                    whyRows = null;   // Movies and Shows need the account
                    Recs.get(getContext()).refreshIfStale();   // back to the lists (or TMDB's picks)
                    invalidate();
                    spoken.focusMoved();
                });
            }, "trakt-sign-out").start());
        }

        private static String shortUrl(String url) {
            return url.replaceFirst("^https?://", "").replaceFirst("/$", "");
        }

        /**
         * Trakt sign-in, as on Apple TV: what it does under the title on the left; on the right a white card with the
         * address and the code to enter there (big, from across the room), and how it's going.
         */
        private void drawTrakt(Canvas c, float seconds, float e, float shift) {
            traktCard.set(PLATTER_L * k + shift, PLATTER_TOP * k, PLATTER_R * k + shift, (PLATTER_TOP + 330) * k);
            float r = 44 * k;
            tmp.set(traktCard);
            tmp.offset(0, 18 * k);
            shadow.setColor(Ui.alpha(0x66000000, e));
            shadows.draw(c, tmp, r, 22 * k, shadow, true);   // hollow: the white card covers the middle
            white.setAlpha(Math.round(255 * e));
            c.drawRoundRect(traktCard, r, r, white);
            float cx = traktCard.centerX();
            if (traktDevice != null) {
                traktUrl.setAlpha(Math.round(0x99 * e));
                c.drawText(shortUrl(traktDevice.url), cx, traktCard.top + 92 * k, traktUrl);
                String code = traktDevice.userCode;
                if (code.length() == 8) code = code.substring(0, 4) + " " + code.substring(4);
                traktCode.setAlpha(Math.round(255 * e));
                c.drawText(code, cx, traktCard.top + 246 * k, traktCode);
            } else if (traktWaiting) {
                spinner.setColor(Ui.alpha(INK, e));
                Ui.spinner(c, cx, traktCard.centerY(), 22 * k, seconds * 1.3f, spinner);
            }
            float x = (PLATTER_L + 32) * k + shift, y = traktCard.bottom + 72 * k, sx = x;
            if (traktWaiting) {
                spinner.setColor(Ui.alpha(0xFFFFFFFF, e));
                Ui.spinner(c, x + 12 * k, y - Ui.halfCap(traktState), 12 * k, seconds * 1.3f, spinner);
                sx += 40 * k;
            }
            traktState.setAlpha(Math.round(0xF2 * e));
            c.drawText(traktStatus, sx, y, traktState);
            rowSub.setColor(Ui.alpha(0x8CFFFFFF, e));
            c.drawText(traktDevice == null && !traktWaiting ? Ui.tr("OK gets a new code. Back cancels.") : Ui.tr("Back cancels."),
                    x, y + 48 * k, rowSub);
        }

        private void activate() {
            Context c = getContext();
            if (tellWhy()) return;
            if (screen == HOME_CHOICE) {
                boolean wantFree = focus == 0;
                boolean already = wantFree ? freeActive() : HomeMode.ORIGINAL.equals(state.optString("mode", HomeMode.ORIGINAL));
                if (!already && !busy()) {
                    HomeMode.request(c, wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                    pendingAt = SystemClock.uptimeMillis();
                }
                screen = MAIN;
                focus = 0;
            } else if (screen == PICKER && pickFor == PICK_CITY) {
                String[] city = cities.get(pickScroll + focus);
                Prefs.setCity(city[1], city[2], city[3]);
                HomeData.get(c).settingsChanged();
                showSetting(CITY_ROW);
            } else if (screen == PICKER && (pickFor == PICK_MOVIES || pickFor == PICK_SHOWS)) {
                pickList();
            } else if (screen == PICKER && pickFor <= PICK_SLEEP) {
                pickPower();
            } else if (screen == PICKER && pickFor == PICK_LANGUAGE) {
                Prefs.setLanguage(languageChoices()[pickScroll + focus]);
                SleepOverlay.update(c);   // the pill speaks it too
                focus = 0;
                pickScroll = 0;
                screen = HOME_SETTINGS;
            } else if (screen == PICKER) {
                int at = pickScroll + focus;
                Prefs.setKeyApp(Prefs.KEYS[pickFor - 1], appPkgs.get(at));
                focus = pickFor - 1;
                pickScroll = 0;
                screen = REMOTE_KEYS;
            } else if (screen == SHELF_EDIT) {
                toggleShelfApp();
                invalidate();
                return;
            } else if (screen == REMOTE_KEYS) {
                openPicker(focus + 1);
            } else if (screen == HOME_SETTINGS && pickScroll + focus == 0) {
                openPicker(PICK_LANGUAGE);
            } else if (screen == HOME_SETTINGS && pickScroll + focus == CITY_ROW) {
                askCity();
                return;
            } else if (screen == HOME_SETTINGS && pickScroll + focus == PHOTOS_ROW) {
                askRemovePhotos();
                return;
            } else if (screen == HOME_SETTINGS && (pickScroll + focus == MOVIES_ROW || pickScroll + focus == SHOWS_ROW)) {
                askLists(pickScroll + focus);
                return;
            } else if (screen == HOME_SETTINGS && pickScroll + focus == TRAKT_ROW) {
                if (traktIn) {
                    askSignOut();
                    return;
                }
                screen = TRAKT;
                focus = 0;
                startTrakt();
            } else if (screen == TRAKT) {
                if (traktDevice == null && !traktWaiting) startTrakt();   // expired or failed: a new code
                return;
            } else if (screen == POWER) {
                activatePower();
            } else if (screen == HOME_SETTINGS) {
                adjust(1);
                invalidate();
                return;
            } else if (screen == ABOUT) {
                return;   // nothing to pick: Back leaves
            } else if (screen == CONFIRM) {
                closeConfirm(focus == 0);
                return;
            } else if (focus == 0) {
                if (busy()) return;
                screen = HOME_CHOICE;
                focus = freeActive() ? 0 : 1;
            } else if (focus == 1) {
                pause();
                activity.showPreview();
            } else if (focus == 2) {
                screen = HOME_SETTINGS;
                focus = 0;
                pickScroll = 0;
                if (traktIn && traktUser.isEmpty()) {   // signed in with the tokens built in: ask Trakt for the name
                    new Thread(() -> {
                        Trakt.refreshUser();
                        handler.post(() -> {
                            traktUser = Trakt.user();
                            invalidate();
                        });
                    }, "trakt-user").start();
                }
            } else if (focus == 3) {
                openShelfEditor(false);
            } else if (focus == 4) {
                openShelfEditor(true);
            } else if (focus == 5) {
                HomeMode.ensureRemoteService(c, 0);   // the buttons work from inside other apps through this service
                loadApps();
                screen = REMOTE_KEYS;
                focus = 0;
            } else if (focus == 6) {
                pickScroll = 0;
                screen = POWER;
                focus = 0;
            } else if (focus == 7) {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else {
                screen = ABOUT;
                focus = 0;
                pickScroll = 0;
            }
            invalidate();
            spoken.focusMoved();
        }
    }
}
