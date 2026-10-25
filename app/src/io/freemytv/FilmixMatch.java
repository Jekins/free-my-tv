package io.freemytv;

import java.text.Normalizer;
import java.util.Locale;

/** Conservative catalogue matching: a similar name alone is not proof of availability. */
final class FilmixMatch {
    private FilmixMatch() {}

    static boolean matches(String title, String original, int year, boolean series,
                           String offered, int offeredYear, boolean offeredSeries) {
        if (offered == null || year < 1888 || year != offeredYear || series != offeredSeries) return false;
        String name = normalize(offered.replaceFirst("\\s*\\([0-9]{4}\\)\\s*$", ""));
        return !name.isEmpty() && (name.equals(normalize(title)) || name.equals(normalize(original)));
    }

    static String normalize(String title) {
        if (title == null) return "";
        return Normalizer.normalize(title, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT)
                .replace('\u0451', '\u0435').replaceAll("\\p{M}+", "").replaceAll("[^\\p{L}\\p{Nd}]", "");
    }
}
