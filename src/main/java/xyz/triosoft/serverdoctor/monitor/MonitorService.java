package xyz.triosoft.serverdoctor.monitor;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import xyz.triosoft.serverdoctor.ServerDoctorPlugin;
import xyz.triosoft.serverdoctor.model.AdvancedData;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Collects actual Paper tick durations, JVM GC data; never profiles third-party plugin execution. */
public final class MonitorService {
    private final ServerDoctorPlugin plugin;
    private final ArrayDeque<AdvancedData.Spike> spikes = new ArrayDeque<>();
    private final ArrayDeque<Point> points = new ArrayDeque<>();
    private final ArrayDeque<AdvancedData.GcEvent> gcEvents = new ArrayDeque<>();
    private long previousGcCount = -1;
    private long previousGcTimeMs = -1;
    public record Telemetry(Status status, List<Point> points, AdvancedData.Insight insight) {
        public Telemetry { points = List.copyOf(points); }
    }
    private volatile Telemetry view = new Telemetry(currentStatus(), List.of(), AdvancedData.Insight.empty());
    private static Status currentStatus() { return new Status(Instant.EPOCH, -1, -1, -1, -1, 0); }
    private BukkitTask task;
    private volatile Status current = new Status(Instant.EPOCH, -1, -1, -1, -1, 0);
    private long lastSpikeNanos = Long.MIN_VALUE;

    public record Point(long epochMillis, double tps, double mspt, double heapPercent) { }
    public record Status(Instant when, double tps, double mspt, double heapPercent,
                         double maxRecentTickMs, int players) { }

    public MonitorService(ServerDoctorPlugin plugin) { this.plugin = plugin; }
    public void start() {
        stop();
        if (plugin.getConfig().getBoolean("monitor.enabled", true)) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::sample, 20L, 20L);
        }
    }
    public void stop() { if (task != null) { task.cancel(); task = null; } }
    private void sample() {
        try {
            double[] tpsArray = Bukkit.getTPS();
            double tps = tpsArray.length == 0 ? -1 : tpsArray[0];
            double mspt = Bukkit.getAverageTickTime();
            Runtime rt = Runtime.getRuntime();
            double heap = rt.maxMemory() > 0 ? (rt.totalMemory() - rt.freeMemory()) * 100d / rt.maxMemory() : -1;
            double maxTick = -1;
            // The Paper ring buffer is in nanoseconds; never infer which plugin caused a spike.
            for (long ns : Bukkit.getTickTimes()) {
                if (ns > 0) maxTick = Math.max(maxTick, ns / 1_000_000d);
            }
            current = new Status(Instant.now(), tps, mspt, heap, maxTick, Bukkit.getOnlinePlayers().size());
            points.addLast(new Point(System.currentTimeMillis(), tps, mspt, heap));
            while (points.size() > 300) points.removeFirst();
            long now = System.nanoTime();
            int cooldown = Math.max(5, plugin.getConfig().getInt("monitor.spike-cooldown-seconds", 30));
            double trigger = Math.max(50, plugin.getConfig().getDouble("monitor.spike-threshold-ms", 100));
            if (maxTick >= trigger && (lastSpikeNanos == Long.MIN_VALUE || now - lastSpikeNanos >= cooldown * 1_000_000_000L)) {
                lastSpikeNanos = now;
                AdvancedData.Spike spike = new AdvancedData.Spike(Instant.now(), maxTick, mspt, tps, current.players());
                spikes.addLast(spike);
                int maxEvents = Math.max(10, Math.min(500, plugin.getConfig().getInt("monitor.retain-events", 100)));
                while (spikes.size() > maxEvents) spikes.removeFirst();
                plugin.alerts().spike(spike);
            }
            plugin.alerts().check(current);
            // MXBean pause time deltas are approximations, sampled every second.
            long count = totalGcCount(), elapsed = totalGcTime();
            if (previousGcTimeMs >= 0 && elapsed >= previousGcTimeMs) {
                long deltaTime = elapsed - previousGcTimeMs;
                long deltaCount = Math.max(0, count - previousGcCount);
                int threshold = Math.max(50, plugin.getConfig().getInt("monitor.gc-pause-threshold-ms", 200));
                if (deltaTime >= threshold) {
                    gcEvents.addLast(new AdvancedData.GcEvent(Instant.now(), deltaTime, deltaCount));
                    while (gcEvents.size() > 50) gcEvents.removeFirst();
                    plugin.alerts().gc(deltaTime, deltaCount);
                }
            }
            previousGcCount = count;
            previousGcTimeMs = elapsed;
            view = new Telemetry(current, List.copyOf(points), insight());
        } catch (Exception e) {
            plugin.getLogger().warning("Monitoring sample failed: " + e.getMessage());
        }
    }
    public Status status() { return current; }
    public Telemetry view() { return view; }
    public List<Point> points() { return List.copyOf(points); } // main thread only
    private static long totalGcCount() {
        long result = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans())
            if (b.getCollectionCount() >= 0) result += b.getCollectionCount();
        return result;
    }
    private static long totalGcTime() {
        long result = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans())
            if (b.getCollectionTime() >= 0) result += b.getCollectionTime();
        return result;
    }
    public AdvancedData.Insight insight() { // main thread only
        long count = totalGcCount(), time = totalGcTime();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        return new AdvancedData.Insight(new ArrayList<>(spikes), count, time,
                nonHeap.getUsed() / 1048576d, nonHeap.getCommitted() / 1048576d, Instant.now(), List.copyOf(gcEvents));
    }
}
