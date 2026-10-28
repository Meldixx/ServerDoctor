package xyz.triosoft.serverdoctor.export;

import xyz.triosoft.serverdoctor.model.AdvancedData;
import xyz.triosoft.serverdoctor.model.ScanResult;
import java.util.Locale;

/** Offline formats with explicit escaping and no third-party JSON dependency. */
public final class ExportService {
    private ExportService() { }
    public static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 12);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20 || c == '\u2028' || c == '\u2029')
                    b.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
                    else b.append(c); }
            }
        }
        return b.toString();
    }
    public static String q(String s) { return "\"" + escapeJson(s) + "\""; }
    private static String num(double n) { return Double.isFinite(n) ? String.format(Locale.ROOT,"%.3f",n) : "null"; }
    public static String json(ScanResult r) {
        StringBuilder b = new StringBuilder(4096);
        b.append("{\"format\":\"serverdoctor-v1\",\"id\":").append(q(r.id()))
                .append(",\"createdAt\":").append(q(r.createdAt().toString()))
                .append(",\"score\":").append(r.score())
                .append(",\"durationMs\":").append(r.elapsedMillis())
                .append(",\"tpsMean\":").append(num(r.tpsMean()))
                .append(",\"msptMean\":").append(num(r.msptMean()))
                .append(",\"msptMax\":").append(num(r.msptMax()))
                .append(",\"cpuMean\":").append(num(r.cpuMean()))
                .append(",\"heapUsedMiB\":").append(num(r.heapUsedMiB()))
                .append(",\"heapMaxMiB\":").append(num(r.heapMaxMiB()))
                .append(",\"onlinePlayers\":").append(r.onlinePlayers())
                .append(",\"gc\":{\"collections\":").append(r.insight().gcCount())
                .append(",\"pauseMs\":").append(r.insight().gcTimeMs())
                .append(",\"nonHeapMiB\":").append(num(r.insight().nonHeapUsedMiB())).append("}");
        b.append(",\"samples\":[");
        boolean first = true;
        for (ScanResult.Sample s : r.samples()) {
            if (!first) b.append(','); first = false;
            b.append("{\"seconds\":").append(num(s.second())).append(",\"tps\":")
                    .append(num(s.tps())).append(",\"mspt\":").append(num(s.mspt()))
                    .append(",\"cpu\":").append(num(s.cpuPercent()))
                    .append(",\"heapPercent\":").append(num(s.heapPercent())).append('}');
        }
        b.append("],\"worlds\":["); first = true;
        for (ScanResult.WorldStats w : r.worlds()) {
            if (!first) b.append(','); first = false;
            b.append("{\"name\":").append(q(w.name())).append(",\"chunks\":").append(w.chunks())
                    .append(",\"entities\":").append(w.entities()).append(",\"blockEntities\":")
                    .append(w.blockEntities()).append('}');
        }
        b.append("],\"spikes\":["); first = true;
        for (AdvancedData.Spike s : r.insight().spikes()) {
            if (!first) b.append(','); first = false;
            b.append("{\"when\":").append(q(s.when().toString())).append(",\"peakMs\":")
                    .append(num(s.maxTickMs())).append(",\"tps\":").append(num(s.tps())).append('}');
        }
        b.append("],\"gcEvents\":["); first = true;
        for (AdvancedData.GcEvent e : r.insight().gcEvents()) {
            if (!first) b.append(','); first = false;
            b.append("{\"when\":").append(q(e.when().toString()))
                .append(",\"pauseDeltaMs\":").append(e.pauseDeltaMs())
                .append(",\"collectionsDelta\":").append(e.collectionsDelta()).append('}');
        }
        b.append("],\"chunks\":{");
        b.append("\"inspected\":").append(r.heatmap().chunksInspected())
                .append(",\"available\":").append(r.heatmap().chunksAvailable())
                .append(",\"truncated\":").append(r.heatmap().truncated())
                .append(",\"hotspots\":["); first = true;
        for (AdvancedData.Hotspot h : r.heatmap().hotspots()) {
            if (!first) b.append(','); first = false;
            b.append("{\"world\":").append(q(h.world())).append(",\"x\":").append(h.x())
                    .append(",\"z\":").append(h.z()).append(",\"entities\":").append(h.entities())
                    .append(",\"blockEntities\":").append(h.blockEntities())
                    .append(",\"hoppers\":").append(h.hoppers()).append(",\"densityScore\":")
                    .append(h.densityScore()).append('}');
        }
        b.append("]},\"findings\":["); first = true;
        for (ScanResult.Finding f : r.findings()) {
            if (!first) b.append(','); first = false;
            b.append("{\"severity\":").append(q(f.severity().name()))
                    .append(",\"title\":").append(q(f.title()))
                    .append(",\"detail\":").append(q(f.detail()))
                    .append(",\"action\":").append(q(f.action())).append('}');
        }
        b.append("]}"); return b.toString();
    }
    public static String csv(ScanResult r) {
        StringBuilder b = new StringBuilder("seconds,tps,mspt,heap_percent,cpu_percent\r\n");
        for (ScanResult.Sample s : r.samples()) {
            b.append(num(s.second())).append(',').append(num(s.tps())).append(',')
                    .append(num(s.mspt())).append(',').append(num(s.heapPercent()))
                    .append(',').append(num(s.cpuPercent())).append("\r\n");
        }
        return b.toString();
    }
}
