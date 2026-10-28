package xyz.triosoft.serverdoctor;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.triosoft.serverdoctor.analysis.Thresholds;
import xyz.triosoft.serverdoctor.command.DoctorCommand;
import xyz.triosoft.serverdoctor.model.ScanResult;
import xyz.triosoft.serverdoctor.monitor.MonitorService;
import xyz.triosoft.serverdoctor.chunk.ChunkInspector;
import xyz.triosoft.serverdoctor.notify.AlertService;
import xyz.triosoft.serverdoctor.web.WebDashboard;
import xyz.triosoft.serverdoctor.export.ExportService;
import org.bukkit.scheduler.BukkitTask;
import xyz.triosoft.serverdoctor.report.HtmlReport;
import xyz.triosoft.serverdoctor.scanner.ScanService;
import xyz.triosoft.serverdoctor.storage.HistoryStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;

public final class ServerDoctorPlugin extends JavaPlugin {
    private ScanService scanner;
    private HistoryStore history;
    private ExecutorService writer;
    private MonitorService monitor;
    private ChunkInspector chunks;
    private AlertService alerts;
    private WebDashboard web;
    private BukkitTask scheduledTask;
    private long lastAutomaticStart = System.nanoTime();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            history = new HistoryStore(getDataFolder().toPath());
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Cannot create report/history storage; disabling", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ServerDoctor-report-writer");
            t.setDaemon(true);
            return t;
        });
        scanner = new ScanService(this);
        alerts = new AlertService(this);
        monitor = new MonitorService(this);
        chunks = new ChunkInspector(this);
        web = new WebDashboard(this);
        PluginCommand command = getCommand("doctor");
        if (command == null) {
            getLogger().severe("Missing /doctor command in plugin.yml");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        DoctorCommand handler = new DoctorCommand(this);
        command.setExecutor(handler);
        command.setTabCompleter(handler);
        monitor.start();
        scheduleRecurring();
        web.start();
        getLogger().info("ServerDoctor v1.1.2 enabled | Paper 1.21.11 | private reports");
    }

    @Override
    public void onDisable() {
        if (web != null) web.stop();
        if (scheduledTask != null) scheduledTask.cancel();
        if (monitor != null) monitor.stop();
        if (chunks != null) chunks.stop();
        if (scanner != null) scanner.stop();
        if (writer != null) writer.shutdown();
    }

    public ScanService scanner() { return scanner; }
    public MonitorService monitor() { return monitor; }
    public ChunkInspector chunks() { return chunks; }
    public AlertService alerts() { return alerts; }
    public WebDashboard web() { return web; }
    public void reloadServices() {
        reloadConfig();
        monitor.start();
        scheduleRecurring();
        web.start();
    }
    private void scheduleRecurring() {
        if (scheduledTask != null) { scheduledTask.cancel(); scheduledTask = null; }
        lastAutomaticStart = System.nanoTime();
        if (!getConfig().getBoolean("scheduled.enabled", false)) return;
        int minutes = Math.max(1, Math.min(10080, getConfig().getInt("scheduled.interval-minutes", 30)));
        scheduledTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (System.nanoTime() - lastAutomaticStart < java.util.concurrent.TimeUnit.MINUTES.toNanos(minutes)) return;
            if (scanner.isRunning()) return;
            int duration = durationSeconds();
            boolean started = scanner.begin(duration, cooldownSeconds(), thresholds(), includePlugins(),
                    result -> saveReport(result, () -> getLogger().info("Scheduled report " + result.id()),
                            error -> getLogger().severe("Scheduled report save failure: " + error)));
            if (started) lastAutomaticStart = System.nanoTime();
        }, 20L, 200L);
    }
    public void runBackground(Runnable action) {
        try { if (writer != null && !writer.isShutdown()) writer.execute(action); }
        catch (RejectedExecutionException ignored) { }
    }
    public HistoryStore history() { return history; }

    public int durationSeconds() { return bounded(getConfig().getInt("scan.duration-seconds", 15), 5, 120); }
    public int cooldownSeconds() { return bounded(getConfig().getInt("scan.cooldown-seconds", 20), 0, 3600); }
    public int maxHistory() { return bounded(getConfig().getInt("scan.max-history", 40), 5, 200); }
    public boolean includePlugins() { return getConfig().getBoolean("privacy.include-plugin-list", false); }

    public Thresholds thresholds() {
        FileConfiguration c = getConfig();
        return new Thresholds(
                bounded(c.getDouble("thresholds.min-tps", 18.5), 0, 20),
                bounded(c.getDouble("thresholds.max-mspt", 45), 1, 300),
                bounded(c.getDouble("thresholds.max-heap-percent", 85), 1, 100),
                bounded(c.getDouble("thresholds.max-cpu-percent", 90), 1, 100),
                bounded(c.getInt("thresholds.max-world-entities", 1800), 1, 200_000),
                bounded(c.getInt("thresholds.max-world-block-entities", 4500), 1, 200_000),
                bounded(c.getInt("thresholds.max-world-chunks", 2500), 1, 100_000),
                bounded(c.getDouble("thresholds.min-free-disk-gb", 2), 0, 100_000));
    }

    private static int bounded(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static double bounded(double value, double min, double max) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : min;
    }

    /** Called after scanner returns to the main server thread. Writes report on a worker. */
    public void saveReport(ScanResult report, Runnable completed, java.util.function.Consumer<String> failed) {
        final int retention = maxHistory(); // Capture config on the primary thread.
        final boolean prune = getConfig().getBoolean("scheduled.prune-old-reports", true)
                && getConfig().getBoolean("scheduled.enabled", false);
        try {
            writer.execute(() -> {
                try {
                    Path output = history.reportPath(report.id());
                    Files.writeString(output, HtmlReport.generate(report), StandardCharsets.UTF_8);
                    Files.writeString(history.exportPath(report.id(), "json"), ExportService.json(report), StandardCharsets.UTF_8);
                    Files.writeString(history.exportPath(report.id(), "csv"), ExportService.csv(report), StandardCharsets.UTF_8);
                    history.add(report, retention);
                    if (prune) {
                        history.pruneGeneratedReports();
                    }
                    scheduleMain(completed);
                } catch (Exception ex) {
                    getLogger().log(Level.SEVERE, "Could not save diagnostic report " + report.id(), ex);
                    scheduleMain(() -> failed.accept(ex.getMessage()));
                }
            });
        } catch (RejectedExecutionException ex) {
            failed.accept("The report writer is stopping");
        }
    }

    private void scheduleMain(Runnable action) {
        if (isEnabled()) Bukkit.getScheduler().runTask(this, action);
    }
}
