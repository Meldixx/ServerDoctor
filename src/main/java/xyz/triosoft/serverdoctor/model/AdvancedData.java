package xyz.triosoft.serverdoctor.model;

import java.time.Instant;
import java.util.List;

/** Bounded, immutable supplementary diagnostics. No Bukkit objects cross threads. */
public final class AdvancedData {
    private AdvancedData() { }
    public record Spike(Instant when, double maxTickMs, double averageMspt, double tps, int players) { }
    public record GcEvent(Instant when, long pauseDeltaMs, long collectionsDelta) { }
    /** These are observed counters, not a root-cause analysis. */
    public record Insight(List<Spike> spikes, long gcCount, long gcTimeMs,
                          double nonHeapUsedMiB, double nonHeapCommittedMiB,
                          Instant capturedAt, List<GcEvent> gcEvents) {
        public Insight {
            spikes = List.copyOf(spikes);
            gcEvents = List.copyOf(gcEvents);
        }
        public Insight(List<Spike> spikes, long count, long pauseMs, double nonHeapUsed,
                       double nonHeapCommitted, Instant capturedAt) {
            this(spikes, count, pauseMs, nonHeapUsed, nonHeapCommitted, capturedAt, List.of());
        }
        public static Insight empty() { return new Insight(List.of(), 0, 0, -1, -1, Instant.EPOCH, List.of()); }
    }
    /** Entity counts -1 indicate an unloaded entity section that was deliberately skipped. */
    public record Hotspot(String world, int x, int z, int entities, int blockEntities,
                          int hoppers, int densityScore) { }
    public record Heatmap(List<Hotspot> hotspots, int chunksInspected, int chunksAvailable,
                          boolean truncated, Instant capturedAt) {
        public Heatmap { hotspots = List.copyOf(hotspots); }
        public static Heatmap empty() { return new Heatmap(List.of(), 0, 0, false, Instant.EPOCH); }
    }
}
