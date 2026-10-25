package io.freemytv;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative catalogue matching: a similar name alone is not proof of availability. */
final class FilmixMatch {
    static final int NONE = 0, NEAR = 1, EXACT = 2;
    private static final Pattern ENTITY = Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});");

    private FilmixMatch() {}

    /**
     * EXACT: same type and year, and Filmix's title (suggest_text_1) or original title (suggest_text_2) is ours.
     * NEAR: a year apart (Filmix often has the premiere's year, TMDB the release's), only when both titles are ours.
     */
    static int score(String title, String original, int year, boolean series,
                     String offered, String offeredOriginal, int offeredYear, boolean offeredSeries) {
        if (offered == null || year < 1888 || series != offeredSeries || Math.abs(year - offeredYear) > 1) return NONE;
        String name = normalize(decode(offered).replaceFirst("\\s*\\([0-9]{4}\\)\\s*$", ""));
        String other = normalize(decode(offeredOriginal));
        String ours = normalize(title), theirs = normalize(original);
        boolean byName = !name.isEmpty() && (name.equals(ours) || name.equals(theirs));
        boolean byOriginal = !other.isEmpty() && (other.equals(theirs) || other.equals(ours));
        if (year == offeredYear) return byName || byOriginal ? EXACT : NONE;
        return byName && byOriginal ? NEAR : NONE;
    }

    /** What to ask Filmix's search: it finds nothing for a plain apostrophe ("Ocean's"), only the typographic one. */
    static String query(String title) {
        return title.trim().replace('\'', '’');
    }

    static String normalize(String title) {
        if (title == null) return "";
        return Normalizer.normalize(title, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT)
                .replace('ё', 'е').replaceAll("\\p{M}+", "").replaceAll("[^\\p{L}\\p{Nd}]", "");
    }

    /**
     * Filmix's columns carry HTML entities ("Человек&nbsp;в…", "Schindler&rsquo;s", "D&#233;flagrations"). Numeric ones
     * become their character; named ones a space, which is enough for normalize (they're punctuation and spaces here).
     */
    static String decode(String text) {
        if (text == null || text.indexOf('&') < 0) return text == null ? "" : text;
        Matcher m = ENTITY.matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String e = m.group(1);
            String r = " ";
            if (e.charAt(0) == '#') {
                boolean hex = e.length() > 1 && (e.charAt(1) == 'x' || e.charAt(1) == 'X');
                int cp = Integer.parseInt(e.substring(hex ? 2 : 1), hex ? 16 : 10);
                if (Character.isValidCodePoint(cp)) r = new String(Character.toChars(cp));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(r));
        }
        m.appendTail(out);
        return out.toString();
    }
}
