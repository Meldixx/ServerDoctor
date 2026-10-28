package xyz.triosoft.serverdoctor.analysis;

import xyz.triosoft.serverdoctor.model.ScanResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static xyz.triosoft.serverdoctor.model.ScanResult.*;

/** Heuristics only. Does not claim to identify the exact cause of a performance problem. */
public final class Analyzer {
    private Analyzer() {}

    public static Analysis evaluate(Metrics metrics, Thresholds limits) {
        List<Finding> result = new ArrayList<>();
        if (isKnown(metrics.tpsMean()) && metrics.tpsMean() < limits.minTps()) {
            result.add(new Finding(Severity.CRITICAL, "Low TPS",
                    "Average sampled 1-minute TPS is below the configured threshold.",
                    "Run a separate spark profile during a lag spike and review its hot paths."));
        }
        if (isKnown(metrics.msptMean()) && metrics.msptMean() > limits.maxMspt()) {
            result.add(new Finding(metrics.msptMean() > 50.0 ? Severity.CRITICAL : Severity.WARNING,
                    "High MSPT", "The average tick time approaches or exceeds the 50 ms budget for 20 TPS.",
                    "Inspect entity AI, plugin tasks, chunk generation, and redstone with spark."));
        }
        if (isKnown(metrics.heapPercent()) && metrics.heapPercent() > limits.maxHeapPercent()) {
            result.add(new Finding(Severity.WARNING, "High heap utilization",
                    "Used JVM heap exceeds the configured percentage of the maximum heap.",
                    "Review allocation/GC patterns before raising -Xmx; leave RAM for the OS."));
        }
        if (isKnown(metrics.cpuMean()) && metrics.cpuMean() > limits.maxCpuPercent()) {
            result.add(new Finding(Severity.WARNING, "High process CPU usage",
                    "The Paper process is using a high share of CPU capacity during the scan.",
                    "Compare CPU spikes with tick times and capture a spark profile."));
        }
        if (metrics.diskUsableBytes() >= 0 &&
                metrics.diskUsableBytes() < limits.minFreeDiskGiB() * 1024 * 1024 * 1024) {
            result.add(new Finding(Severity.CRITICAL, "Low disk space",
                    "The volume hosting ServerDoctor's data directory has little free disk space.",
                    "Free disk space and check backup/world growth; world files may be on a different volume."));
        }
        for (WorldStats world : metrics.worlds()) {
            if (world.entities() > limits.maxWorldEntities()) {
                result.add(new Finding(Severity.WARNING, "Many entities: " + world.name(),
                        "The world currently contains " + world.entities() + " loaded entities.",
                        "Inspect farms and entity hotspots. Do not remove entities without checking ownership."));
            }
            if (world.blockEntities() > limits.maxWorldBlockEntities()) {
                result.add(new Finding(Severity.WARNING, "Many block entities: " + world.name(),
                        "The world currently contains " + world.blockEntities() + " loaded block entities.",
                        "Review chests, hoppers and other block entities. Counts alone do not prove lag."));
            }
            if (world.chunks() > limits.maxWorldChunks()) {
                result.add(new Finding(Severity.WARNING, "Many chunks: " + world.name(),
                        "The world currently holds " + world.chunks() + " loaded chunks.",
                        "Check view/simulation distance, chunk tickets and player distribution."));
            }
        }
        if (metrics.sampleCount() < 5) {
            result.add(new Finding(Severity.INFO, "Short observation window",
                    "Fewer than five valid scheduled samples were collected.",
                    "Repeat the scan over a longer interval to capture typical server load."));
        }
        if (result.isEmpty()) {
            result.add(new Finding(Severity.INFO, "No threshold exceeded",
                    "The sampled counters did not cross the configured limits.",
                    "This does not rule out intermittent lag or problems invisible to these counters."));
        }
        result.sort(Comparator.comparingInt(f -> switch (f.severity()) {
            case CRITICAL -> 0;
            case WARNING -> 1;
            case INFO -> 2;
        }));
        int score = 100;
        for (Finding finding : result) {
            score -= switch (finding.severity()) {
                case CRITICAL -> 22;
                case WARNING -> 9;
                case INFO -> 0;
            };
        }
        return new Analysis(List.copyOf(result), Math.max(0, score));
    }

    public static boolean isKnown(double value) {
        return Double.isFinite(value) && value >= 0;
    }

    public record Metrics(double tpsMean, double msptMean, double heapPercent,
                          double cpuMean, long diskUsableBytes,
                          int sampleCount, List<WorldStats> worlds) { }
    public record Analysis(List<Finding> findings, int score) { }
}
