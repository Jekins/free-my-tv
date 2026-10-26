package io.freemytv;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;

import java.text.Collator;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * Explore's filters (↑ on its tab, or OK held there): a panel of Liquid Glass in the middle of the screen, drawn on
 * the home screen's menu layers (HomeView keeps the glass, the blurred copy of the screen behind it and the fade), laid
 * out as tvOS would: on the left its sections, each saying what it's set to, then Reset and Show as buttons; on the
 * right every option of the focused section at once, as capsules in even columns (the decades in a list, the years of
 * the focused one beside it), so the whole list is in sight and a few presses away. The focus is a white capsule that
 * glides and stretches from one option to the next. OK picks (genres: wanted, then left out, then neither; years: one,
 * then the span to a second); Back goes from the options to the sections, and from there closes the panel, which
 * applies the filters, as Show does.
 */
final class FilterPanel {
    interface Host {
        void dirty();                 // the items need drawing again
        void moved();                 // the focus moved
        void picked();                // OK took
        void announce(String text);
        void close();                 // close the panel and apply the filters
    }

    private static final int S_TYPE = 0, S_SORT = 1, S_GENRES = 2, S_YEARS = 3, S_STATUS = 4, S_RATINGS = 5,
            S_CONTENT = 6, SECTIONS = 7, RESET = 7, SHOW = 8, SIDE_ITEMS = 9;
    // What a capsule sets.
    private static final int K_TYPE = 0, K_SORT = 1, K_MATCH = 2, K_GENRE_ANY = 3, K_GENRE = 4, K_YEAR_ANY = 5,
            K_DECADE = 6, K_YEAR = 7, K_STATUS_ANY = 8, K_STATUS = 9, K_RATING = 10, K_PARENTAL = 11, K_UNRATED = 12;
    // A capsule's look: off, picked, left out (a genre), between two years picked.
    private static final int OFF = 0, ON = 1, OUT = 2, WITHIN = 3;
    /**
     * The statuses offered: ended, ongoing (returning series, with continuing: Recs.Filter.ONGOING) and canceled first,
     * then the rest from the present back to the most uncertain: released, upcoming, pilot, post production, in
     * production, planned, rumored.
     */
    private static final int[] STATUS_ORDER = {10, 1, 9, 0, 6, 7, 4, 3, 5, 8};
    /** The genres most films have (the rest, TV's and niche ones, come after them, apart). */
    private static final List<String> MAIN_GENRES = Arrays.asList("action", "adventure", "animation", "anime",
            "biography", "comedy", "crime", "documentary", "drama", "family", "fantasy", "history", "horror", "music",
            "musical", "mystery", "romance", "science-fiction", "superhero", "thriller", "war", "western");
    /** A move that leaves the options for the sections. */
    private static final int LEAVE = -1;
    private static final int RED = 0xFFFF453A, INK = 0xFF1D1D1F, WHITE = 0xFFF5F5F7;
    private static final int FIRST_YEAR = 1930;   // as far back as Trakt's own slider goes
    // Sizes at 1920 wide (× k).
    private static final float PANEL_W = 1560, PANEL_H = 880, PAD = 36, HEAD = 84, SIDE_W = 470, SIDE_H = 70,
            SIDE_GAP = 4, SIDE_SPACE = 24, PANE_GAP = 44, LABEL_H = 48, CHIP_H = 58, CHIP_GAP = 12, LINE_GAP = 12,
            GROUP_GAP = 26, CHIP_PAD = 26, GLYPH = 30, DECADE_GAP = 8, YEARS_GAP = 36;
    /** The panel's corners: the sections' capsules' plus the margin around them (the curves share a center). */
    static final float RADIUS = SIDE_H / 2 + PAD;

    private static final class Chip {
        final int kind, group, value;
        final String text;
        final float textW;
        final boolean multi;      // a check (or a minus) shows before the text when picked
        float x, y, w;
        float size = 1;           // the text's scale: a long name in a narrow cell is drawn smaller
        int line;

        Chip(int kind, int group, int value, String text, float textW, boolean multi) {
            this.kind = kind;
            this.group = group;
            this.value = value;
            this.text = text;
            this.textW = textW;
            this.multi = multi;
        }
    }

    private static final class Label {
        final String text;
        final float x, y;

        Label(String text, float x, float y) {
            this.text = text;
            this.x = x;
            this.y = y;
        }
    }

    private final Context context;
    private final Host host;
    private final Shadows shadows;
    private float k = 1;
    private TextPaint title, item, summaryPaint, chipText, labelPaint, hint;
    private float chipSize;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Recs.Filter draft = new Recs.Filter();
    private String[] typeNames = {"", "", ""};
    private String heading = "";
    private final String[] summary = new String[SECTIONS];
    private final RectF panel = new RectF(), pane = new RectF(), tmp = new RectF();
    // The options of the section shown (in the pane's own coordinates: x from its left, y from its content's top).
    private final List<Chip> chips = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private float[] lineTop = new float[0];   // each line's top, with its group's label if it's the group's first
    private int[] lineFirst = new int[0];     // each line's first capsule
    private float contentH;
    private int shown = -1;                   // the section in the pane
    private int yearsOf;                      // the decade whose years the Years section shows
    private int decades;                      // in the Years section: "any year" and the decades come first
    private float yearsX;                     // where the years start, beside the decades
    private int side, focus;                  // the focused section (or Reset, Show); the focused capsule
    private boolean inPane, okDown, backDown;
    private float wantX;                      // ↑ ↓ keep to this x (the capsule's center the focus came from)
    private final Spring sideY = new Spring(0.26f, 0.85f, 0), paneness = new Spring(0.22f, 1, 0),
            scroll = new Spring(0.32f, 1, 0);
    private final Spring[] pill = {new Spring(0.3f, 0.8f, 0), new Spring(0.3f, 0.8f, 0), new Spring(0.3f, 0.8f, 0),
        new Spring(0.3f, 0.8f, 0)};    // the focus capsule's left, top, width and height (the pane's coordinates)

    FilterPanel(Context context, Host host, Shadows shadows) {
        this.context = context;
        this.host = host;
        this.shadows = shadows;
        setScale(1);
    }

    /** The screen's scale (its width / 1920): the paints' sizes. */
    void setScale(float k) {
        this.k = k;
        title = paint(Ui.semibold, 34 * k);
        item = paint(Ui.medium, 30 * k);
        summaryPaint = paint(Ui.medium, 25 * k);
        chipSize = 27 * k;
        chipText = paint(Ui.medium, chipSize);
        labelPaint = paint(Ui.semibold, 23 * k);
        hint = paint(Ui.medium, 23 * k);
        shadow.setMaskFilter(new BlurMaskFilter(14 * k, BlurMaskFilter.Blur.NORMAL));
        stroke.setStyle(Paint.Style.STROKE);
        Symbols.warm(context, "check", 24 * k);
        Symbols.warm(context, "chevron_down", 22 * k);
    }

    private static TextPaint paint(android.graphics.Typeface face, float size) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(size);
        return p;
    }

    /** Opens on `current` (a copy is edited); `types`: All, Movies, Shows as the tabs say them; `tab`: Explore's name. */
    void open(Recs.Filter current, String[] types, String tab) {
        draft = current.copy();
        yearsOf = draft.yearFrom > 0 ? draft.yearFrom / 10 * 10 : thisDecade();
        typeNames = types;
        heading = Ui.trf("Filters: {tab}", "tab", tab);
        side = 0;
        inPane = okDown = backDown = false;
        shown = -1;
        paneness.snap(0);
        summarize();
    }

    /** The filters as they are now (a copy). */
    Recs.Filter draft() {
        return draft.copy();
    }

    /** The panel's place: the middle of a w × h screen. Lays the sections and the options out. */
    RectF place(int w, int h) {
        float pw = Math.min(PANEL_W * k, w - 80 * k), ph = Math.min(PANEL_H * k, h - 60 * k);
        panel.set(Math.round((w - pw) / 2), Math.round((h - ph) / 2), Math.round((w + pw) / 2), Math.round((h + ph) / 2));
        pane.set(panel.left + PAD * k + SIDE_W * k + PANE_GAP * k, panel.top + PAD * k + HEAD * k, panel.right - PAD * k,
                panel.bottom - PAD * k);
        summarize();
        build(side < SECTIONS ? side : Math.max(0, shown));
        sideY.snap(sideTop(side));
        if (inPane) aimFocus(true);
        return panel;
    }

    /** What the focus is on, to be read out. */
    String label() {
        if (inPane && focus < chips.size()) return chips.get(focus).text;
        return sideName(side) + (side < SECTIONS ? ", " + summary[side] : "");
    }

    boolean step(float dt) {
        boolean moving = sideY.step(dt) | paneness.step(dt) | scroll.step(dt);
        for (Spring s : pill) moving |= s.step(dt);
        return moving;
    }

    // MARK: - Keys

    boolean key(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) {
                    int code = e.getKeyCode();
                    move(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0,
                            code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (e.getRepeatCount() == 0) okDown = true;   // the press that opened the panel isn't one
                } else {
                    if (okDown && !e.isCanceled()) ok();
                    okDown = false;
                }
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

    private void move(int dx, int dy) {
        if (!inPane) {
            if (dy != 0) {
                int next = Math.max(0, Math.min(SIDE_ITEMS - 1, side + dy));
                if (next == side) return;
                side = next;
                if (side < SECTIONS) build(side);
                sideY.target = sideTop(side);
                host.moved();
                host.announce(label());
            } else if (dx > 0 && side < SECTIONS) {
                enterPane();
            }
            host.dirty();
            return;
        }
        if (chips.isEmpty()) return;
        int next = shown == S_YEARS ? nextYear(dx, dy) : nextChip(dx, dy);
        if (next == LEAVE) {
            leavePane();
            return;
        }
        if (next == focus) return;
        focus = next;
        Chip to = chips.get(focus);
        if (to.kind == K_DECADE && to.value != yearsOf) {
            yearsOf = to.value;      // the decade under the focus shows its years below it (the focus keeps its place)
            build(S_YEARS, false);
        }
        aimFocus(false);
        host.moved();
        host.announce(chipLabel(chips.get(focus)));
        host.dirty();
    }

    /** Among options in lines: along the line (← past its start: LEAVE), or to the nearest one above or below. */
    private int nextChip(int dx, int dy) {
        Chip c = chips.get(focus);
        int next = focus;
        if (dx != 0) {
            int first = lineFirst[c.line], last = lastOf(c.line);
            if (dx < 0 && focus == first) return LEAVE;
            next = Math.max(first, Math.min(last, focus + dx));
            if (next != focus) wantX = chips.get(next).x + chips.get(next).w / 2;
            return next;
        }
        int line = c.line + dy;
        if (line < 0 || line >= lineFirst.length) return focus;
        float best = Float.MAX_VALUE;
        for (int i = lineFirst[line]; i <= lastOf(line); i++) {
            Chip o = chips.get(i);
            float d = Math.abs(o.x + o.w / 2 - wantX);
            if (d < best) {
                best = d;
                next = i;
            }
        }
        return next;
    }

    /**
     * The Years section: ↑ ↓ along the decades (each shows its years beside it), → into its years (to the one picked,
     * else the first), ← from them back to their decade; the years are two lines of five.
     */
    private int nextYear(int dx, int dy) {
        if (focus < decades) {
            if (dy != 0) return Math.max(0, Math.min(decades - 1, focus + dy));
            if (dx < 0) return LEAVE;
            for (int i = decades; i < chips.size(); i++) if (state(chips.get(i)) == ON) return i;
            return decades;
        }
        int at = focus - decades, row = at / 5, col = at % 5;
        if (dx < 0 && col == 0) {
            for (int i = 0; i < decades; i++) if (chips.get(i).kind == K_DECADE && chips.get(i).value == yearsOf) return i;
            return 0;
        }
        if (dx != 0) return decades + row * 5 + Math.max(0, Math.min(4, col + dx));
        int to = row + dy;
        return to < 0 || to > 1 ? focus : decades + to * 5 + col;
    }

    private int lastOf(int line) {
        return line + 1 < lineFirst.length ? lineFirst[line + 1] - 1 : chips.size() - 1;
    }

    private void ok() {
        if (!inPane) {
            if (side < SECTIONS) {
                enterPane();
            } else if (side == RESET) {
                draft = new Recs.Filter();
                yearsOf = thisDecade();
                host.picked();
                summarize();
                build(shown);
                host.announce(Ui.tr("Reset filters"));
            } else {
                host.picked();
                host.close();
            }
            host.dirty();
            return;
        }
        if (focus >= chips.size()) return;
        Chip c = chips.get(focus);
        pick(c);
        host.picked();
        summarize();
        host.announce(chipLabel(c));
        host.dirty();
    }

    private void back() {
        if (inPane) {
            leavePane();
        } else {
            host.moved();
            host.close();
        }
    }

    private void enterPane() {
        if (chips.isEmpty()) return;
        focus = 0;
        for (int i = 0; i < chips.size(); i++) {   // the first option that's picked (in a section of several, the first)
            if (state(chips.get(i)) == ON) {
                focus = i;
                break;
            }
        }
        Chip c = chips.get(focus);
        wantX = c.x + c.w / 2;
        inPane = true;
        paneness.target = 1;
        // The capsule leaves the section's row and flies to the option.
        float[] row = sideRow();
        for (int i = 0; i < 4; i++) pill[i].snap(row[i]);
        aimFocus(false);
        host.moved();
        host.announce(chipLabel(c));
        host.dirty();
    }

    private void leavePane() {
        inPane = false;
        paneness.target = 0;
        float[] row = sideRow();   // and flies back to it, fading into the section's own highlight
        for (int i = 0; i < 4; i++) pill[i].target = row[i];
        host.moved();
        host.announce(label());
        host.dirty();
    }

    /** The focused section's row in the pane's coordinates (left, top, width, height). */
    private float[] sideRow() {
        return new float[] {panel.left + PAD * k - pane.left, sideTop(side) - pane.top + scroll.value, SIDE_W * k, SIDE_H * k};
    }

    /** The focus capsule to the focused option (a little larger), the pane scrolled to show it with its group's name. */
    private void aimFocus(boolean snap) {
        if (focus >= chips.size()) return;
        Chip c = chips.get(focus);
        float grow = 3 * k, ch = CHIP_H * k;
        float[] to = {c.x - grow, c.y - grow, c.w + 2 * grow, ch + 2 * grow};
        for (int i = 0; i < 4; i++) {
            if (snap) pill[i].snap(to[i]);
            else pill[i].target = to[i];
        }
        float top = lineTop[c.line] - 6 * k, bottom = c.y + ch + 14 * k, view = pane.height();
        float s = scroll.target;
        if (top < s) s = top;
        if (bottom > s + view) s = bottom - view;
        s = Math.max(0, Math.min(Math.max(0, contentH - view), s));
        if (snap) scroll.snap(s);
        else scroll.target = s;
    }

    // MARK: - What the options set

    private int state(Chip c) {
        Recs.Filter d = draft;
        switch (c.kind) {
            case K_TYPE: return d.type == c.value ? ON : OFF;
            case K_SORT: return d.source == c.value ? ON : OFF;
            case K_MATCH: return d.genres != 0 && (d.allGenres ? 1 : 0) == c.value ? ON : OFF;   // with genres wanted
            case K_GENRE_ANY: return d.genres == 0 && d.without == 0 ? ON : OFF;
            case K_GENRE: return (d.genres & 1L << c.value) != 0 ? ON : (d.without & 1L << c.value) != 0 ? OUT : OFF;
            case K_YEAR_ANY: return d.yearFrom == 0 ? ON : OFF;
            case K_DECADE:
                if (d.yearFrom == c.value && d.yearTo == c.value + 9) return ON;
                return d.yearFrom > 0 && d.yearFrom <= c.value + 9 && d.yearTo >= c.value ? WITHIN : OFF;   // years in it
            case K_YEAR:
                if (d.yearFrom == 0) return OFF;
                if (c.value == d.yearFrom || c.value == d.yearTo) return ON;
                return c.value > d.yearFrom && c.value < d.yearTo ? WITHIN : OFF;
            case K_STATUS_ANY: return d.statuses == 0 ? ON : OFF;
            case K_STATUS: return (d.statuses & 1 << c.value) != 0 ? ON : OFF;
            case K_RATING: return d.ratings[c.group] == c.value ? ON : OFF;
            case K_PARENTAL: return d.parental[c.group] == c.value ? ON : OFF;
            default: return (d.unrated ? 1 : 0) == c.value ? ON : OFF;
        }
    }

    private void pick(Chip c) {
        Recs.Filter d = draft;
        switch (c.kind) {
            case K_TYPE: d.setType(c.value); break;
            case K_SORT: d.source = c.value; break;
            case K_MATCH: d.allGenres = c.value == 1; break;
            case K_GENRE_ANY: d.genres = d.without = 0; break;
            case K_GENRE:
                long bit = 1L << c.value;
                if ((d.genres & bit) != 0) {
                    d.genres &= ~bit;
                    d.without |= bit;
                } else if ((d.without & bit) != 0) {
                    d.without &= ~bit;
                } else {
                    d.genres |= bit;
                }
                break;
            case K_YEAR_ANY: d.yearFrom = d.yearTo = 0; break;
            case K_DECADE:
                if (d.yearFrom == c.value && d.yearTo == c.value + 9) {
                    d.yearFrom = d.yearTo = 0;
                } else {
                    d.yearFrom = c.value;
                    d.yearTo = c.value + 9;
                }
                break;
            case K_YEAR:
                // One year; a second one makes the span between them; OK on a lone year again lets it go.
                if (d.yearFrom == 0 || d.yearFrom != d.yearTo) {
                    d.yearFrom = d.yearTo = c.value;
                } else if (d.yearFrom == c.value) {
                    d.yearFrom = d.yearTo = 0;
                } else {
                    d.yearFrom = Math.min(d.yearFrom, c.value);
                    d.yearTo = Math.max(d.yearTo, c.value);
                }
                break;
            case K_STATUS_ANY: d.statuses = 0; break;
            case K_STATUS: {
                int bits = c.value == 1 ? Recs.Filter.ONGOING : 1 << c.value;   // ongoing: returning and continuing
                if ((d.statuses & 1 << c.value) != 0) d.statuses &= ~bits;
                else d.statuses |= bits;
                break;
            }
            case K_RATING: d.ratings[c.group] = c.value; break;
            case K_PARENTAL: d.parental[c.group] = c.value; break;
            default: d.unrated = c.value == 1; break;
        }
    }

    private String chipLabel(Chip c) {
        int s = state(c);
        String text = c.text;
        if (s == ON && c.multi) return Ui.trf("filter|{option}, picked", "option", text);
        if (s == OUT) return Ui.trf("filter|{option}, left out", "option", text);
        return text;
    }

    // MARK: - Layout

    private float sideTop(int i) {
        float top = panel.top + PAD * k + HEAD * k + i * (SIDE_H + SIDE_GAP) * k;
        if (i >= RESET) top += SIDE_SPACE * k;   // the buttons, apart from the sections
        if (i >= SHOW) top += 8 * k;
        return top;
    }

    private static String sideName(int i) {
        switch (i) {
            case S_TYPE: return Ui.tr("filter|Type");
            case S_SORT: return Ui.tr("filter|Sort");
            case S_GENRES: return Ui.tr("filter|Genres");
            case S_YEARS: return Ui.tr("filter|Years");
            case S_STATUS: return Ui.tr("filter|Status");
            case S_RATINGS: return Ui.tr("filter|Ratings");
            case S_CONTENT: return Ui.tr("filter|Content");
            case RESET: return Ui.tr("Reset filters");
            default: return Ui.tr("filter|Show titles");
        }
    }

    // While building: where the next capsule goes.
    private float cx, cy;
    private int lines;
    private final List<Float> tops = new ArrayList<>();
    private final List<Integer> firsts = new ArrayList<>();
    private boolean groupStart;

    private void build(int section) {
        build(section, true);
    }

    /**
     * The options of `section`, laid out in lines (wrapping at the pane's width). With the focus on them, it stays on
     * the same one, moving there at once (`snap`) or gliding.
     */
    private void build(int section, boolean snap) {
        if (section < 0) return;
        shown = section;
        chips.clear();
        labels.clear();
        tops.clear();
        firsts.clear();
        cx = cy = 0;
        lines = 0;
        switch (section) {
            case S_TYPE: {
                group(null);
                List<Chip> types = new ArrayList<>();
                for (int i = 0; i < typeNames.length; i++) types.add(make(K_TYPE, 0, i, typeNames[i], false));
                grid(types, 3);   // all, movies, shows: one line, in the columns the other sections have
                break;
            }
            case S_SORT:
                group(null);
                for (int i = 0; i < Recs.Filter.SOURCES.length; i++) chip(K_SORT, 0, i, sortName(i), false);
                break;
            case S_GENRES: {
                // Any genre, or the ones picked: any of them or all at once; then the genres in even columns, in
                // alphabetical order, the ones most films have first and TV's and the niche ones apart.
                group(null);
                chip(K_GENRE_ANY, 0, 0, Ui.tr("filter|Any genre"), false);
                chip(K_MATCH, 0, 0, Ui.tr("filter|Any of the genres"), false);
                chip(K_MATCH, 0, 1, Ui.tr("filter|All of the genres"), false);
                Collator collator = Collator.getInstance(Ui.locale());
                for (int part = 0; part < 2; part++) {
                    List<Chip> list = new ArrayList<>();
                    for (int i = 0; i < Recs.Filter.GENRES.length; i++) {
                        String g = Recs.Filter.GENRES[i];
                        if (MAIN_GENRES.contains(g) == (part == 0)) list.add(make(K_GENRE, 0, i, genreName(g), true));
                    }
                    Collections.sort(list, (a, b) -> collator.compare(a.text, b.text));
                    group(part == 0 ? Ui.tr("filter|Main genres") : Ui.tr("filter|More genres"));
                    grid(list, 3);
                }
                break;
            }
            case S_YEARS:
                yearsSection();
                break;
            case S_STATUS: {
                group(null);
                List<Chip> list = new ArrayList<>();
                list.add(make(K_STATUS_ANY, 0, 0, Ui.tr("filter|Any status"), false));
                int have = Recs.Filter.statusesOf(Recs.Filter.TYPES[draft.type]);
                for (int i : STATUS_ORDER) if ((have & 1 << i) != 0) list.add(make(K_STATUS, 0, i, statusName(i), true));
                grid(list, 3);
                break;
            }
            case S_RATINGS:
                for (int r = 0; r < Recs.Filter.RATINGS.length; r++) {
                    group(ratingSource(r, false));
                    chip(K_RATING, r, 0, Ui.tr("filter|All"), false);   // short, as Trakt says it: the line has six
                    for (int i = 1; i <= Recs.Filter.RATING_MIN[r].length; i++) chip(K_RATING, r, i, ratingMin(r, i), false);
                }
                break;
            default:
                for (int p = 0; p < Recs.Filter.PARENTAL.length; p++) {
                    group(parentalName(p));
                    for (int i = 0; i < 4; i++) chip(K_PARENTAL, p, i, parentalLevel(i), false);
                }
                group(Ui.tr("parental|Titles the guide hasn't rated"));
                chip(K_UNRATED, 0, 0, Ui.tr("parental|Hide them"), false);
                chip(K_UNRATED, 0, 1, Ui.tr("parental|Show them"), false);
                break;
        }
        contentH = chips.isEmpty() ? 0 : cy + CHIP_H * k;
        lineTop = new float[tops.size()];
        lineFirst = new int[firsts.size()];
        for (int i = 0; i < lineTop.length; i++) {
            lineTop[i] = tops.get(i);
            lineFirst[i] = firsts.get(i);
        }
        if (!inPane) {
            scroll.snap(0);
            focus = 0;
        } else {
            focus = Math.min(focus, chips.size() - 1);
            aimFocus(snap);
        }
    }

    private static int thisDecade() {
        return Calendar.getInstance().get(Calendar.YEAR) / 10 * 10;
    }

    /** A new group of options under `name` (none: no name), on a line of its own. */
    private void group(String name) {
        if (!chips.isEmpty()) cy += CHIP_H * k + GROUP_GAP * k;
        float top = cy;
        if (name != null) {
            labels.add(new Label(name, 4 * k, cy + LABEL_H * k * 0.62f));
            cy += LABEL_H * k;
        }
        cx = 0;
        groupStart = true;
        tops.add(top);
        firsts.add(chips.size());
        lines = tops.size();
    }

    private void chip(int kind, int group, int value, String text, boolean multi) {
        float tw = chipText.measureText(text), w = tw + 2 * CHIP_PAD * k + (multi ? GLYPH * k : 0);
        if (!groupStart && cx + w > pane.width()) newLine();
        Chip c = new Chip(kind, group, value, text, tw, multi);
        c.x = cx;
        c.y = cy;
        c.w = w;
        c.line = lines - 1;
        chips.add(c);
        cx += w + CHIP_GAP * k;
        groupStart = false;
    }

    private void newLine() {
        cy += CHIP_H * k + LINE_GAP * k;
        cx = 0;
        tops.add(cy);
        firsts.add(chips.size());
        lines = tops.size();
    }

    private Chip make(int kind, int group, int value, String text, boolean multi) {
        return new Chip(kind, group, value, text, chipText.measureText(text), multi);
    }

    /**
     * Options in `cols` even columns, line after line, so every group of a section lines up; a name too long for its
     * cell is drawn smaller (down to 70%) rather than costing a column.
     */
    private void grid(List<Chip> items, int cols) {
        if (!groupStart) newLine();
        float gap = CHIP_GAP * k, cell = (pane.width() - (cols - 1) * gap) / cols;
        for (int i = 0; i < items.size(); i++) {
            if (i > 0 && i % cols == 0) newLine();
            Chip c = items.get(i);
            c.x = (i % cols) * (cell + gap);
            c.y = cy;
            c.w = cell;
            c.line = lines - 1;
            float room = cell - 2 * 16 * k - (c.multi ? GLYPH * k : 0);
            if (c.textW > room) c.size = Math.max(0.7f, room / c.textW);
            chips.add(c);
        }
        groupStart = false;
    }

    /**
     * Any year and the decades in a list on the left, each a line of its own; beside them the years of one decade (the
     * one the focus is on, or was last), two lines of five. A few roomy capsules at a time, as tvOS would have it,
     * rather than a century of narrow ones; ↑ ↓ never leave the list.
     */
    private void yearsSection() {
        List<Chip> list = new ArrayList<>();
        list.add(make(K_YEAR_ANY, 0, 0, Ui.tr("filter|Any year"), false));
        for (int decade = thisDecade(); decade >= FIRST_YEAR; decade -= 10) {
            list.add(make(K_DECADE, 0, decade, Ui.trf("filter|{year}s", "year", String.valueOf(decade)), false));
        }
        float listW = 0;
        for (Chip c : list) listW = Math.max(listW, c.textW + 2 * CHIP_PAD * k);
        for (int i = 0; i < list.size(); i++) {
            Chip c = list.get(i);
            c.x = 0;
            c.y = i * (CHIP_H + DECADE_GAP) * k;
            c.w = listW;
            c.line = i;
            tops.add(c.y);
            firsts.add(chips.size());
            chips.add(c);
        }
        decades = list.size();
        yearsX = listW + YEARS_GAP * k;
        float cell = (pane.width() - yearsX - 4 * CHIP_GAP * k) / 5;
        labels.add(new Label(Ui.trf("filter|{year}s", "year", String.valueOf(yearsOf)), yearsX + 4 * k, LABEL_H * k * 0.62f));
        for (int i = 0; i < 10; i++) {
            float y = LABEL_H * k + (i / 5) * (CHIP_H + LINE_GAP) * k;
            if (i % 5 == 0) {
                tops.add(i == 0 ? 0 : y);   // the first line with the decade's name over it
                firsts.add(chips.size());
            }
            Chip c = make(K_YEAR, 0, yearsOf + i, String.valueOf(yearsOf + i), false);
            c.x = yearsX + (i % 5) * (cell + CHIP_GAP * k);
            c.y = y;
            c.w = cell;
            c.line = tops.size() - 1;
            chips.add(c);
        }
        cy = (decades - 1) * (CHIP_H + DECADE_GAP) * k;   // the list is the tallest: its last line ends the content
        lines = tops.size();
    }

    // MARK: - Words

    private String sortName(int i) {
        switch (i) {
            case 0: return Ui.tr("sort|Recommended");
            case 1: return Ui.tr("sort|Trending");
            case 2: return Ui.tr("sort|Popular");
            default: return Ui.tr("sort|Anticipated");
        }
    }

    private static String statusName(int i) {
        switch (Recs.Filter.STATUSES[i]) {
            case "released": return Ui.tr("status|Released");
            case "returning series": return Ui.tr("status|Returning");
            case "in production": return Ui.tr("status|In Production");
            case "post production": return Ui.tr("status|Post Production");
            case "planned": return Ui.tr("status|Planned");
            case "upcoming": return Ui.tr("status|Upcoming");
            case "pilot": return Ui.tr("status|Pilot");
            case "rumored": return Ui.tr("status|Rumored");
            case "canceled": return Ui.tr("status|Canceled");
            default: return Ui.tr("status|Ended");
        }
    }

    /** A rating source's name: in full over its line, or short in the section's summary. */
    private static String ratingSource(int r, boolean short_) {
        switch (r) {
            case 0: return "Trakt";
            case 1: return "IMDb";
            case 2: return "TMDB";
            case 3: return short_ ? "RT" : Ui.tr("rating|Rotten Tomatoes critics");
            case 4: return short_ ? Ui.tr("rating|RT audience") : Ui.tr("rating|Rotten Tomatoes audience");
            case 5: return "Metacritic";
            default: return "Letterboxd";
        }
    }

    /** The least a source may give (`i` from 1), in its own scale: Trakt's 0–100 shown out of 10, as on its site. */
    private static String ratingMin(int r, int i) {
        float v = Recs.Filter.RATING_MIN[r][i - 1];
        if (r == 0) v /= 10;
        NumberFormat f = NumberFormat.getNumberInstance(Ui.locale());
        f.setMaximumFractionDigits(1);
        String n = f.format(v);
        return r == 3 || r == 4 ? Ui.trf("filter|{n}%+", "n", n) : Ui.trf("filter|{n}+", "n", n);
    }

    private static String parentalName(int p) {
        switch (Recs.Filter.PARENTAL[p]) {
            case "nudity": return Ui.tr("parental|Sex & Nudity");
            case "violence": return Ui.tr("parental|Violence & Gore");
            case "profanity": return Ui.tr("parental|Profanity");
            case "alcohol": return Ui.tr("parental|Alcohol, Drugs & Smoking");
            default: return Ui.tr("parental|Frightening & Intense Scenes");
        }
    }

    private static String parentalLevel(int i) {
        switch (i) {
            case 0: return Ui.tr("parental|Any");
            case 1: return Ui.tr("parental|Up to moderate");
            case 2: return Ui.tr("parental|Up to mild");
            default: return Ui.tr("parental|None");
        }
    }

    /** Trakt's genres in the TV's language. */
    static String genreName(String slug) {
        switch (slug) {
            case "action": return Ui.tr("genre|Action");
            case "adventure": return Ui.tr("genre|Adventure");
            case "animation": return Ui.tr("genre|Animation");
            case "anime": return Ui.tr("genre|Anime");
            case "biography": return Ui.tr("genre|Biography");
            case "children": return Ui.tr("genre|Kids");
            case "comedy": return Ui.tr("genre|Comedy");
            case "crime": return Ui.tr("genre|Crime");
            case "documentary": return Ui.tr("genre|Documentary");
            case "donghua": return Ui.tr("genre|Donghua");
            case "drama": return Ui.tr("genre|Drama");
            case "family": return Ui.tr("genre|Family");
            case "fantasy": return Ui.tr("genre|Fantasy");
            case "game-show": return Ui.tr("genre|Game Show");
            case "history": return Ui.tr("genre|History");
            case "holiday": return Ui.tr("genre|Holiday");
            case "home-and-garden": return Ui.tr("genre|Home & Garden");
            case "horror": return Ui.tr("genre|Horror");
            case "mini-series": return Ui.tr("genre|Miniseries");
            case "music": return Ui.tr("genre|Music");
            case "musical": return Ui.tr("genre|Musical");
            case "mystery": return Ui.tr("genre|Mystery");
            case "news": return Ui.tr("genre|News");
            case "reality": return Ui.tr("genre|Reality");
            case "romance": return Ui.tr("genre|Romance");
            case "science-fiction": return Ui.tr("genre|Science Fiction");
            case "short": return Ui.tr("genre|Short");
            case "soap": return Ui.tr("genre|Soap");
            case "special-interest": return Ui.tr("genre|Special Interest");
            case "sporting-event": return Ui.tr("genre|Sporting Event");
            case "superhero": return Ui.tr("genre|Superhero");
            case "suspense": return Ui.tr("genre|Suspense");
            case "talk-show": return Ui.tr("genre|Talk");
            case "thriller": return Ui.tr("genre|Thriller");
            case "war": return Ui.tr("genre|War");
            default: return Ui.tr("genre|Western");
        }
    }

    /** What each section is set to, cut to the room beside its name. */
    private void summarize() {
        Recs.Filter d = draft;
        String[] full = new String[SECTIONS];
        full[S_TYPE] = typeNames[d.type];
        full[S_SORT] = sortName(d.source);
        StringBuilder g = new StringBuilder();
        String join = d.allGenres && Long.bitCount(d.genres) > 1 ? " + " : ", ";
        for (int i = 0; i < Recs.Filter.GENRES.length; i++) {
            if ((d.genres & 1L << i) != 0) g.append(g.length() > 0 ? join : "").append(genreName(Recs.Filter.GENRES[i]));
        }
        for (int i = 0; i < Recs.Filter.GENRES.length; i++) {
            if ((d.without & 1L << i) != 0) g.append(g.length() > 0 ? ", " : "").append('−').append(genreName(Recs.Filter.GENRES[i]));
        }
        full[S_GENRES] = g.length() > 0 ? g.toString() : Ui.tr("filter|Any genre");
        full[S_YEARS] = d.yearFrom == 0 ? Ui.tr("filter|Any year") : d.yearFrom == d.yearTo ? String.valueOf(d.yearFrom)
                : d.yearFrom % 10 == 0 && d.yearTo == d.yearFrom + 9 ? Ui.trf("filter|{year}s", "year", String.valueOf(d.yearFrom))
                : Ui.trf("filter|{from}–{to}", "from", String.valueOf(d.yearFrom), "to", String.valueOf(d.yearTo));
        StringBuilder s = new StringBuilder();
        for (int i : STATUS_ORDER) if ((d.statuses & 1 << i) != 0) s.append(s.length() > 0 ? ", " : "").append(statusName(i));
        full[S_STATUS] = s.length() > 0 ? s.toString() : Ui.tr("filter|Any status");
        StringBuilder r = new StringBuilder();
        for (int i = 0; i < d.ratings.length; i++) {
            if (d.ratings[i] != 0) r.append(r.length() > 0 ? ", " : "").append(ratingSource(i, true)).append(' ').append(ratingMin(i, d.ratings[i]));
        }
        full[S_RATINGS] = r.length() > 0 ? r.toString() : Ui.tr("filter|Any rating");
        int limits = 0;
        for (int p : d.parental) if (p != 0) limits++;
        full[S_CONTENT] = limits == 0 ? Ui.tr("filter|No limits") : Ui.plural("plural|one:{n} limit|other:{n} limits", limits);
        float room = SIDE_W * k - 2 * 26 * k - 24 * k;
        for (int i = 0; i < SECTIONS; i++) {
            float space = room - item.measureText(sideName(i));
            summary[i] = space <= 0 ? "" : TextUtils.ellipsize(full[i], summaryPaint, space, TextUtils.TruncateAt.END).toString();
        }
    }

    // MARK: - Drawing

    private int ink, glassChip, rim;
    private float dark;

    /**
     * The panel's contents (HomeView draws its glass): the heading and the hint for the keys, the sections with their
     * values, then the shown section's options, the focus capsule gliding among them. `dark`: 0 by day … 1 at night.
     */
    void draw(Canvas c, float dark) {
        this.dark = dark;
        ink = blend(INK, WHITE, dark);
        glassChip = blend(Ui.alpha(0xFF000000, 0.06f), Ui.alpha(0xFFFFFFFF, 0.12f), dark);
        rim = blend(Ui.alpha(0xFFFFFFFF, 0.75f), Ui.alpha(0xFFFFFFFF, 0.16f), dark);
        float left = panel.left + PAD * k;
        title.setColor(ink);
        c.drawText(heading, left + 26 * k, panel.top + PAD * k + HEAD * k * 0.5f + Ui.halfCap(title) - 6 * k, title);
        String keys = inPane ? paneHint() : Ui.tr("filter|→ or OK: options · Back: show the titles");
        hint.setColor(Ui.alpha(ink, 0.55f));
        c.drawText(keys, panel.right - PAD * k - 10 * k - hint.measureText(keys),
                panel.top + PAD * k + HEAD * k * 0.5f + Ui.halfCap(hint) - 6 * k, hint);
        drawSide(c, left);
        stroke.setStrokeWidth(Math.max(1, k));
        stroke.setColor(Ui.alpha(ink, 0.1f));
        float lx = pane.left - PANE_GAP * k / 2;
        c.drawLine(lx, pane.top, lx, pane.bottom, stroke);
        drawPane(c);
    }

    private String paneHint() {
        switch (shown) {
            case S_GENRES: return Ui.tr("filter|OK: wanted · again: left out · again: neither");
            case S_YEARS: return Ui.tr("filter|OK on two years: the years between");
            case S_STATUS: return Ui.tr("filter|OK ticks a status: as many as you like");
            case S_CONTENT: return Ui.tr("filter|IMDb's parental guide: the most each may have");
            default: return Ui.tr("filter|OK: pick · Back: the sections");
        }
    }

    private void drawSide(Canvas c, float left) {
        float ih = SIDE_H * k, r = ih / 2, p = Ui.clamp(paneness.value);
        // Reset and Show are buttons: glass with a bright rim, Show filled with the accent.
        stroke.setStrokeWidth(1.5f * k);
        for (int i = RESET; i <= SHOW; i++) {
            float top = sideTop(i);
            tmp.set(left, top, left + SIDE_W * k, top + ih);
            fill.setColor(i == SHOW ? Ui.ACCENT : glassChip);
            c.drawRoundRect(tmp, r, r, fill);
            if (i == RESET) {
                stroke.setColor(rim);
                tmp.inset(0.75f * k, 0.75f * k);
                c.drawRoundRect(tmp, r, r, stroke);
            }
        }
        // The section's highlight: the white focus while the sections have it, glass while its options do.
        tmp.set(left, sideY.value, left + SIDE_W * k, sideY.value + ih);
        if (p < 0.99f) {
            tmp.offset(0, 5 * k * (1 - p));
            shadow.setColor(Ui.alpha(0xFF000000, blend01(0.12f, 0.3f) * (1 - p)));
            shadows.draw(c, tmp, r, 14 * k, shadow);
            tmp.offset(0, -5 * k * (1 - p));
        }
        int glass = blend(Ui.alpha(0xFFFFFFFF, 0.55f), Ui.alpha(0xFFFFFFFF, 0.14f), dark);
        fill.setColor(blend(glass, 0xFFFFFFFF, 1 - p));
        c.drawRoundRect(tmp, r, r, fill);
        for (int i = 0; i < SIDE_ITEMS; i++) {
            float top = sideTop(i), cyy = top + ih / 2;
            float f = 1 - Math.min(1, Math.abs(sideY.value - top) / (ih + SIDE_GAP * k));   // under the focus
            float on = f * (1 - p);
            int color = i == SHOW ? INK : blend(ink, INK, on);   // dark on the yellow and on the white focus
            if (i == RESET && draft.none()) color = Ui.alpha(color, 0.45f);
            item.setColor(color);
            String name = sideName(i);
            float x = i >= RESET ? left + (SIDE_W * k - item.measureText(name)) / 2 : left + 26 * k;   // a button's, centered
            c.drawText(name, x, cyy + Ui.halfCap(item), item);
            if (i < SECTIONS && !summary[i].isEmpty()) {
                summaryPaint.setColor(Ui.alpha(color, 0.62f));
                c.drawText(summary[i], left + SIDE_W * k - 26 * k - summaryPaint.measureText(summary[i]), cyy + Ui.halfCap(summaryPaint), summaryPaint);
            }
        }
    }

    private void drawPane(Canvas c) {
        float ch = CHIP_H * k, r = ch / 2, p = Ui.clamp(paneness.value), s = scroll.value;
        c.save();
        c.clipRect(pane.left - 12 * k, pane.top, pane.right + 12 * k, pane.bottom);
        c.translate(pane.left, pane.top - s);
        float viewTop = s - ch, viewBottom = s + pane.height() + ch;
        labelPaint.setColor(Ui.alpha(ink, 0.6f));
        for (int i = 0; i < labels.size(); i++) {   // no iterator: drawn on every frame the focus moves
            Label l = labels.get(i);
            if (l.y > viewTop && l.y < viewBottom + LABEL_H * k) c.drawText(l.text, l.x, l.y, labelPaint);
        }
        // Fills: picked in the accent, left out red, the years between faintly in the accent; the rest glass, with a bright
        // rim.
        stroke.setStrokeWidth(1.5f * k);
        for (int i = 0; i < chips.size(); i++) {
            Chip chip = chips.get(i);
            if (chip.y < viewTop || chip.y > viewBottom) continue;
            tmp.set(chip.x, chip.y, chip.x + chip.w, chip.y + ch);
            int st = state(chip);
            fill.setColor(st == ON ? Ui.ACCENT : st == OUT ? Ui.alpha(RED, 0.92f) : st == WITHIN ? Ui.alpha(Ui.ACCENT, 0.3f) : glassChip);
            c.drawRoundRect(tmp, r, r, fill);
            if (st == OFF) {
                stroke.setColor(rim);
                tmp.inset(0.75f * k, 0.75f * k);
                c.drawRoundRect(tmp, r, r, stroke);
            }
        }
        c.restore();
        // The focus: white, a little larger, with a soft shadow; it glides between options (unclipped: it flies in
        // from the sections and back).
        c.save();
        c.translate(pane.left, pane.top - s);
        if (p > 0.01f && !chips.isEmpty()) {
            tmp.set(pill[0].value, pill[1].value, pill[0].value + pill[2].value, pill[1].value + pill[3].value);
            float pr = tmp.height() / 2;
            tmp.offset(0, 5 * k);
            shadow.setColor(Ui.alpha(0xFF000000, blend01(0.14f, 0.32f) * p));
            shadows.draw(c, tmp, pr, 14 * k, shadow);
            tmp.offset(0, -5 * k);
            fill.setColor(Ui.alpha(0xFFFFFFFF, p));
            c.drawRoundRect(tmp, pr, pr, fill);
        }
        c.restore();
        c.save();
        c.clipRect(pane.left - 12 * k, pane.top, pane.right + 12 * k, pane.bottom);
        c.translate(pane.left, pane.top - s);
        float pl = pill[0].value, pt = pill[1].value, pw = pill[2].value, ph = pill[3].value;
        for (int i = 0; i < chips.size(); i++) {
            Chip chip = chips.get(i);
            if (chip.y < viewTop || chip.y > viewBottom) continue;
            int st = state(chip);
            // Dark on the yellow, white on the red. Under the focus (as much of it as the capsule covers): dark text, or the
            // accent's deep yellow for a picked one, red for one left out.
            float ox = Math.max(0, Math.min(chip.x + chip.w, pl + pw) - Math.max(chip.x, pl));
            float oy = Math.max(0, Math.min(chip.y + ch, pt + ph) - Math.max(chip.y, pt));
            float f = p * Math.min(1, ox * oy / (chip.w * ch));
            int rest = st == ON ? INK : st == OUT ? 0xFFFFFFFF : ink;
            int focused = st == ON ? Ui.ACCENT_DEEP : st == OUT ? RED : INK;
            int color = blend(rest, focused, f);
            boolean glyph = chip.multi && (st == ON || st == OUT);
            float gw = glyph ? GLYPH * k : 0, x = chip.x + (chip.w - chip.textW * chip.size - gw) / 2, mid = chip.y + ch / 2;
            if (glyph) {
                if (st == ON) {
                    Symbols.draw(context, c, "check", x + 11 * k, mid, 24 * k, color);
                } else {
                    stroke.setColor(color);
                    stroke.setStrokeWidth(3 * k);
                    stroke.setStrokeCap(Paint.Cap.ROUND);
                    c.drawLine(x + 3 * k, mid, x + 19 * k, mid, stroke);
                    stroke.setStrokeCap(Paint.Cap.BUTT);
                }
            }
            chipText.setColor(color);
            chipText.setStrikeThruText(st == OUT);
            if (chip.size != 1) chipText.setTextSize(chipSize * chip.size);
            c.drawText(chip.text, x + gw, mid + Ui.halfCap(chipText), chipText);
            if (chip.size != 1) chipText.setTextSize(chipSize);
            chipText.setStrikeThruText(false);
        }
        c.restore();
        if (contentH - s > pane.height() + 1) {
            // More below: a chevron at the pane's foot.
            Symbols.draw(context, c, "chevron_down", pane.centerX(), pane.bottom + PAD * k * 0.45f, 22 * k, Ui.alpha(ink, 0.45f));
        }
    }

    private float blend01(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark;
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
