package io.freemytv;

public final class FilmixMatchTest {
    private static int checks;

    public static void main(String[] args) {
        int exact = FilmixMatch.EXACT, near = FilmixMatch.NEAR, none = FilmixMatch.NONE;
        check(exact, "Люпен", "Lupin", 2021, true, "Люпен (2021)", "", 2021, true);
        check(none, "Люпен", "Lupin", 2021, true, "Люпен III: Первый (2019)", "", 2019, false);
        check(none, "Dune", "Dune", 2021, false, "Dune (1984)", "", 1984, false);
        check(none, "The Last of Us", "The Last of Us", 2023, true, "The Last of Us (2023)", "", 2023, false);
        check(exact, "Человек-паук: Нет пути домой", "Spider-Man: No Way Home", 2021, false,
                "Spider Man: No Way Home (2021)", "", 2021, false);
        check(exact, "Всё везде и сразу", "Everything Everywhere All at Once", 2022, false,
                "Все везде и сразу (2022)", "", 2022, false);
        check(exact, "Amélie", "Le Fabuleux Destin d'Amélie Poulain", 2001, false, "Amelie (2001)", "", 2001, false);
        check(none, "It", "It", 2017, false, "It Chapter Two (2017)", "It Chapter Two", 2017, false);
        check(none, "", "", 2021, false, " (2021)", "", 2021, false);
        check(none, "Unknown", "Unknown", 0, false, "Unknown (2021)", "", 2021, false);
        check(none, "Unknown", "Unknown", 2021, false, null, null, 2021, false);
        check(none, "Lupin", "Lupin", 2021, true, "Lupin (2021)", "", 0, true);

        // Filmix's Russian title is another translation: its original title (suggest_text_2) is the proof.
        check(exact, "Неогранённые алмазы", "Uncut Gems", 2019, false,
                "Неогранённые драгоценности (2019)", "Uncut Gems", 2019, false);
        check(exact, "Звёздные войны: Эпизод 8 - Последние джедаи", "Star Wars: The Last Jedi", 2017, false,
                "Звёздные Войны: Последние джедаи (2017)", "Star Wars: The Last Jedi", 2017, false);
        check(none, "Задержка в развитии", "Arrested Development", 2003, true,
                "Замедленное развитие (2003)", "Arrested Development", 2003, false);
        check(none, "Sweet Tooth", "Sweet Tooth", 2021, true, "Мальчик с оленьими рогами (2021)", "", 2021, true);
        check(none, "X", "", 2020, false, "Y (2020)", "", 2020, false);
        // Filmix writes HTML entities in both columns.
        check(exact, "Человек в высоком замке", "The Man in the High Castle", 2015, true,
                "Человек&nbsp;в высоком замке (2015)", "", 2015, true);
        check(exact, "Список Шиндлера 2", "Schindler's List", 1993, false,
                "Список Шиндлера (1993)", "Schindler&rsquo;s List", 1993, false);
        check(exact, "Вспышки", "Déflagrations", 2020, false, "Взрыв (2020)", "D&#233;flagrations", 2020, false);
        // A year apart (premiere vs release), only when both the Russian and the original titles agree.
        check(near, "Стрингер", "Nightcrawler", 2014, false, "Стрингер (2013)", "Nightcrawler", 2013, false);
        check(near, "Умерь свой энтузиазм", "Curb Your Enthusiasm", 2000, true,
                "Умерь свой энтузиазм (1999)", "Curb Your Enthusiasm", 1999, true);
        check(none, "Живая ярость", "火遮眼", 2026, false, "Живая ярость (2025)", "Fo ze ngaan", 2025, false);
        check(near, "Рейд", "Serbuan Maut", 2012, false, "Рейд (2011)", "Serbuan maut", 2011, false);
        check(none, "Рейд", "Serbuan Maut", 2012, false, "Рейд (2011)", "", 2011, false);
        check(none, "Dune", "Dune", 2021, false, "Dune (2019)", "Dune", 2019, false);
        check(none, "Стрингер", "Nightcrawler", 2014, false, "Стрингер (2013)", "Nightcrawler", 2013, true);
        check(none, "Взрыв", "Blast", 2026, false, "Взрыв (2004)", "Blast", 2004, false);

        // Filmix's search finds nothing for a plain apostrophe; the typographic one is what it stores.
        same("Ocean’s Eleven", FilmixMatch.query("Ocean's Eleven"));
        same("Человек-паук", FilmixMatch.query(" Человек-паук "));

        // The card is opened with Filmix's own slug: some carry a dot ("Black Widow").
        slug(true, "29.9-chernaya-vdova-2020");
        slug(true, "legenda-o-volkah_2020");
        slug(false, "../films");
        slug(false, "a..b");
        slug(false, ".hidden");
        slug(false, "chernaya vdova");
        slug(false, "чёрная-вдова");
        slug(false, "");
        slug(false, null);
        System.out.println("Filmix matching: " + checks + " catalogue identity checks passed.");
    }

    private static void check(int expected, String title, String original, int year, boolean series,
                              String offered, String offeredOriginal, int offeredYear, boolean offeredSeries) {
        int actual = FilmixMatch.score(title, original, year, series, offered, offeredOriginal, offeredYear, offeredSeries);
        if (actual != expected) {
            throw new AssertionError("Match " + actual + " (expected " + expected + ") for " + title + " against " + offered);
        }
        checks++;
    }

    private static void slug(boolean expected, String slug) {
        if (FilmixMatch.slug(slug) != expected) throw new AssertionError("Slug " + slug + " (expected " + expected + ")");
        checks++;
    }

    private static void same(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("Query " + actual + " (expected " + expected + ")");
        checks++;
    }
}
