package io.freemytv;

public final class FilmixMatchTest {
    public static void main(String[] args) {
        check(true, "Люпен", "Lupin", 2021, true, "Люпен (2021)", 2021, true);
        check(false, "Люпен", "Lupin", 2021, true, "Люпен III: Первый (2019)", 2019, false);
        check(false, "Dune", "Dune", 2021, false, "Dune (1984)", 1984, false);
        check(false, "The Last of Us", "The Last of Us", 2023, true, "The Last of Us (2023)", 2023, false);
        check(true, "Человек-паук: Нет пути домой", "Spider-Man: No Way Home", 2021, false,
                "Spider Man: No Way Home (2021)", 2021, false);
        check(true, "Всё везде и сразу", "Everything Everywhere All at Once", 2022, false,
                "Все везде и сразу (2022)", 2022, false);
        check(true, "Amélie", "Le Fabuleux Destin d'Amélie Poulain", 2001, false, "Amelie (2001)", 2001, false);
        check(false, "It", "It", 2017, false, "It Chapter Two (2017)", 2017, false);
        check(false, "", "", 2021, false, " (2021)", 2021, false);
        check(false, "Unknown", "Unknown", 0, false, "Unknown (2021)", 2021, false);
        check(false, "Unknown", "Unknown", 2021, false, null, 2021, false);
        check(false, "Lupin", "Lupin", 2021, true, "Lupin (2021)", 0, true);
        System.out.println("Filmix matching: 12 catalogue identity checks passed.");
    }

    private static void check(boolean expected, String title, String original, int year, boolean series,
                              String offered, int offeredYear, boolean offeredSeries) {
        boolean actual = FilmixMatch.matches(title, original, year, series, offered, offeredYear, offeredSeries);
        if (actual != expected) throw new AssertionError("Unexpected match for " + title + " against " + offered);
    }
}
