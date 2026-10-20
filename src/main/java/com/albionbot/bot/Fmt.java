package com.albionbot.bot;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Text formatting for Discord output. */
public final class Fmt {
    private Fmt() {}

    /** 987 -> "987", 12,345 -> "12.3k", 1,250,000 -> "1.25m". */
    public static String silver(double n) {
        double a = Math.abs(n);
        if (a >= 1_000_000) return String.format(Locale.ROOT, "%.2fm", n / 1_000_000);
        if (a >= 10_000) return String.format(Locale.ROOT, "%.1fk", n / 1_000);
        return String.format(Locale.ROOT, "%,.0f", n);
    }

    /** Items per day: 0.4 -> "<1", 7.6 -> "8", 1,520 -> "1.5k". */
    public static String perDay(double n) {
        if (n < 1) return "<1";
        if (n >= 1_000) return String.format(Locale.ROOT, "%.1fk", n / 1_000);
        return String.format(Locale.ROOT, "%.0f", n);
    }

    public static String signed(double n) {
        return (n >= 0 ? "+" : "-") + silver(Math.abs(n));
    }

    public static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.0f%%", fraction * 100);
    }

    public static String age(Optional<Duration> d) {
        if (d.isEmpty()) return "-";
        long minutes = Math.max(0, d.get().toMinutes());
        if (minutes < 60) return minutes + "m";
        if (minutes < 48 * 60) return (minutes / 60) + "h";
        return (minutes / (24 * 60)) + "d";
    }

    public static String trunc(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Monospace table; first column left-aligned, others right-aligned. Wrap the result in a code block. */
    public static String table(List<String> headers, List<List<String>> rows) {
        return table(headers, rows, 2);
    }

    /** As {@link #table(List, List)}, with {@code gap} spaces between columns (1 keeps wide tables from wrapping). */
    public static String table(List<String> headers, List<List<String>> rows, int gap) {
        int cols = headers.size();
        int[] w = new int[cols];
        for (int c = 0; c < cols; c++) {
            w[c] = headers.get(c).length();
            for (List<String> r : rows) w[c] = Math.max(w[c], r.get(c).length());
        }
        StringBuilder sb = new StringBuilder();
        String sep = " ".repeat(gap);
        appendRow(sb, headers, w, sep);
        for (List<String> r : rows) appendRow(sb, r, w, sep);
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, List<String> cells, int[] w, String sep) {
        for (int c = 0; c < cells.size(); c++) {
            if (c > 0) sb.append(sep);
            String cell = cells.get(c);
            String pad = " ".repeat(w[c] - cell.length());
            sb.append(c == 0 ? cell + pad : pad + cell);
        }
        sb.append('\n');
    }

    public static String code(String body) {
        return "```\n" + body + "```";
    }
}
