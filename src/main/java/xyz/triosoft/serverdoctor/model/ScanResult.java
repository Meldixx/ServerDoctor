package xyz.triosoft.serverdoctor.model;

import java.time.Instant;
import java.util.List;

/** Immutable snapshot: safe to pass from Bukkit's main thread to file/HTML worker. */
public record ScanResult(
        String id,
        Instant createdAt,
        long elapsedMillis,
        String serverVersion,
        String javaVersion,
        String operatingSystem,
        long uptimeMillis,
        int onlinePlayers,
        int maxPlayers,
        int pluginCount,
        long diskUsableBytes,
        long diskTotalBytes,
        double heapUsedMiB,
        double heapMaxMiB,
        double tpsMean,
        double tpsMin,
        double msptMean,
        double msptMax,
        double cpuMean,
        List<Sample> samples,
        List<WorldStats> worlds,
        List<PluginInfo> plugins,
        List<Finding> findings,
        int score,
        AdvancedData.Insight insight,
        AdvancedData.Heatmap heatmap
) {
    public ScanResult {
        samples = List.copyOf(samples);
        worlds = List.copyOf(worlds);
        plugins = List.copyOf(plugins);
        findings = List.copyOf(findings);
        insight = insight == null ? AdvancedData.Insight.empty() : insight;
        heatmap = heatmap == null ? AdvancedData.Heatmap.empty() : heatmap;
    }

    /** Compatibility for reports/tests created before the advanced diagnostics release. */
    public ScanResult(String id, Instant createdAt, long elapsedMillis, String serverVersion,
            String javaVersion, String operatingSystem, long uptimeMillis, int onlinePlayers,
            int maxPlayers, int pluginCount, long diskUsableBytes, long diskTotalBytes,
            double heapUsedMiB, double heapMaxMiB, double tpsMean, double tpsMin,
            double msptMean, double msptMax, double cpuMean, List<Sample> samples,
            List<WorldStats> worlds, List<PluginInfo> plugins, List<Finding> findings, int score) {
        this(id, createdAt, elapsedMillis, serverVersion, javaVersion, operatingSystem,
                uptimeMillis, onlinePlayers, maxPlayers, pluginCount, diskUsableBytes,
                diskTotalBytes, heapUsedMiB, heapMaxMiB, tpsMean, tpsMin, msptMean,
                msptMax, cpuMean, samples, worlds, plugins, findings, score,
                AdvancedData.Insight.empty(), AdvancedData.Heatmap.empty());
    }

    public record Sample(double second, double tps, double mspt, double heapPercent, double cpuPercent) { }
    public record WorldStats(String name, String environment, int players, int chunks, int entities, int blockEntities) { }
    public record PluginInfo(String name, String version) { }
    public enum Severity { CRITICAL, WARNING, INFO }
    public record Finding(Severity severity, String title, String detail, String action) { }
}
