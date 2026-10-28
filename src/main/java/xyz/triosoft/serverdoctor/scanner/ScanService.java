package xyz.triosoft.serverdoctor.scanner;

import com.sun.management.OperatingSystemMXBean;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import xyz.triosoft.serverdoctor.ServerDoctorPlugin;
import xyz.triosoft.serverdoctor.analysis.Analyzer;
import xyz.triosoft.serverdoctor.analysis.Thresholds;
import xyz.triosoft.serverdoctor.model.ScanResult;
import xyz.triosoft.serverdoctor.util.Numbers;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/** Main-thread-only Paper API reads. Result is immutable before it enters the async report writer. */
public final class ScanService {
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private final ServerDoctorPlugin plugin;
    private BukkitTask task;
    private Session active;
    private long lastStartNanos = Long.MIN_VALUE;

    public ScanService(ServerDoctorPlugin plugin) { this.plugin = plugin; }

    public synchronized boolean isRunning() { return active != null; }

    public synchronized int sampleCount() { return active == null ? 0 : active.samples.size(); }
    public synchronized long targetSeconds() { return active == null ? 0 : active.durationNanos / 1_000_000_000L; }

    public synchronized long elapsedSeconds() {
        return active == null ? 0 : (System.nanoTime() - active.startedAtNanos) / 1_000_000_000L;
    }

    /** Invoke exclusively on Paper's primary thread. */
    public boolean begin(int durationSeconds, int cooldownSeconds, Thresholds thresholds,
                         boolean listPlugins, Consumer<ScanResult> callback) {
        if (active != null) return false;
        long now = System.nanoTime();
        if (lastStartNanos != Long.MIN_VALUE && now - lastStartNanos < (long) cooldownSeconds * 1_000_000_000L)
            return false;
        lastStartNanos = now;
        active = new Session(now, Instant.now(), durationSeconds * 1_000_000_000L,
                thresholds, listPlugins, callback);
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(), 0L, 20L);
        return true;
    }

    public long cooldownLeftSeconds(int cooldownSeconds) {
        if (lastStartNanos == Long.MIN_VALUE) return 0;
        long remainingNanos = (long) cooldownSeconds * 1_000_000_000L - (System.nanoTime() - lastStartNanos);
        return Math.max(0, (remainingNanos + 999_999_999L) / 1_000_000_000L);
    }

    private void tick() {
        Session session = active;
        if (session == null) return;
        try {
            long elapsed = System.nanoTime() - session.startedAtNanos;
            session.samples.add(readSample(Math.max(0, elapsed) / 1_000_000_000.0));
            if (elapsed >= session.durationNanos) {
                if (task != null) { task.cancel(); task = null; }
                active = null;
                ScanResult finished = finish(session, Math.max(0, elapsed) / 1_000_000L);
                session.callback.accept(finished);
            }
        } catch (Exception e) {
            if (task != null) { task.cancel(); task = null; }
            active = null;
            plugin.getLogger().severe("Diagnostic scan failed: " + e.getMessage());
        }
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        active = null;
    }

    private ScanResult.Sample readSample(double elapsedSeconds) {
        double[] tps = Bukkit.getTPS();
        double tps1m = tps.length > 0 ? tps[0] : -1;
        double mspt = Bukkit.getAverageTickTime();
        Runtime runtime = Runtime.getRuntime();
        long maxHeap = runtime.maxMemory();
        double heap = maxHeap > 0 ?
                100 * (double) (runtime.totalMemory() - runtime.freeMemory()) / maxHeap : -1;
        return new ScanResult.Sample(elapsedSeconds, valid(tps1m), valid(mspt), valid(heap), cpuLoad());
    }

    private static double cpuLoad() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean bean) {
                return valid(bean.getProcessCpuLoad() * 100);
            }
        } catch (RuntimeException | LinkageError ignored) { /* unsupported JVM */ }
        return -1;
    }

    private static double valid(double value) { return Double.isFinite(value) && value >= 0 ? value : -1; }

    private ScanResult finish(Session session, long elapsedMillis) {
        List<ScanResult.WorldStats> worlds = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            worlds.add(new ScanResult.WorldStats(world.getName(), world.getEnvironment().name(),
                    world.getPlayerCount(), world.getChunkCount(), world.getEntityCount(),
                    world.getTileEntityCount()));
        }
        List<ScanResult.PluginInfo> plugins = new ArrayList<>();
        if (session.listPlugins) {
            for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                plugins.add(new ScanResult.PluginInfo(p.getName(), p.getPluginMeta().getVersion()));
            }
            plugins.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        }
        List<Double> tpsSamples = session.samples.stream().map(ScanResult.Sample::tps).toList();
        List<Double> msptSamples = session.samples.stream().map(ScanResult.Sample::mspt).toList();
        List<Double> cpuSamples = session.samples.stream().map(ScanResult.Sample::cpuPercent).toList();
        double tpsMean = Numbers.mean(tpsSamples);
        double msptMean = Numbers.mean(msptSamples);
        double cpuMean = Numbers.mean(cpuSamples);
        Runtime runtime = Runtime.getRuntime();
        double heapUsedMiB = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576.0;
        double heapMaxMiB = runtime.maxMemory() / 1_048_576.0;
        double heapPercentage = heapMaxMiB > 0 ? heapUsedMiB / heapMaxMiB * 100 : -1;
        long diskFree = plugin.getDataFolder().getUsableSpace();
        long diskTotal = plugin.getDataFolder().getTotalSpace();
        Analyzer.Analysis analysis = Analyzer.evaluate(
                new Analyzer.Metrics(tpsMean, msptMean, heapPercentage, cpuMean,
                        diskFree, session.samples.size(), worlds), session.thresholds);
        String id = ID_TIME.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 6);
        return new ScanResult(id, session.startedAt, elapsedMillis,
                Bukkit.getVersion(), System.getProperty("java.version", "N/A"),
                System.getProperty("os.name", "N/A"),
                ManagementFactory.getRuntimeMXBean().getUptime(),
                Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers(),
                Bukkit.getPluginManager().getPlugins().length, diskFree, diskTotal,
                heapUsedMiB, heapMaxMiB,
                tpsMean, Numbers.min(tpsSamples), msptMean, Numbers.max(msptSamples), cpuMean,
                session.samples, worlds, plugins, analysis.findings(), analysis.score(),
                plugin.monitor().insight(), plugin.chunks().latest());
    }

    private static final class Session {
        final long startedAtNanos;
        final Instant startedAt;
        final long durationNanos;
        final Thresholds thresholds;
        final boolean listPlugins;
        final Consumer<ScanResult> callback;
        final List<ScanResult.Sample> samples = new ArrayList<>();

        Session(long startedAtNanos, Instant startedAt, long durationNanos,
                Thresholds thresholds, boolean listPlugins, Consumer<ScanResult> callback) {
            this.startedAtNanos = startedAtNanos;
            this.startedAt = startedAt;
            this.durationNanos = durationNanos;
            this.thresholds = thresholds;
            this.listPlugins = listPlugins;
            this.callback = callback;
        }
    }
}
