package xyz.triosoft.serverdoctor.util;

import java.util.List;
import java.util.Locale;

public final class Numbers {
    private Numbers() { }

    public static String fixed(double value, int places) {
        return Double.isFinite(value) && value >= 0
                ? String.format(Locale.US, "%1$." + places + "f", value)
                : "N/A";
    }

    public static double mean(List<Double> values) {
        return values.stream().filter(v -> v != null && Double.isFinite(v) && v >= 0)
                .mapToDouble(Double::doubleValue).average().orElse(-1);
    }

    public static double min(List<Double> values) {
        return values.stream().filter(v -> v != null && Double.isFinite(v) && v >= 0)
                .mapToDouble(Double::doubleValue).min().orElse(-1);
    }

    public static double max(List<Double> values) {
        return values.stream().filter(v -> v != null && Double.isFinite(v) && v >= 0)
                .mapToDouble(Double::doubleValue).max().orElse(-1);
    }

    public static String escapeHtml(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            switch (input.charAt(i)) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(input.charAt(i));
            }
        }
        return sb.toString();
    }

    public static String percent(double fraction) {
        return fixed(fraction, 1) + "%";
    }
}
