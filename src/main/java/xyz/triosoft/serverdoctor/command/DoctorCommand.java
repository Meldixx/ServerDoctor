package xyz.triosoft.serverdoctor.command;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import xyz.triosoft.serverdoctor.ServerDoctorPlugin;
import xyz.triosoft.serverdoctor.storage.HistoryStore;
import xyz.triosoft.serverdoctor.util.Numbers;

import java.nio.file.Files;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DoctorCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
            .withZone(ZoneId.systemDefault());
    private static final List<String> COMMANDS = List.of("scan", "status", "latest", "history", "report", "chunks", "spark", "web", "schedule", "reload", "help");
    private static final String PREFIX = ChatColor.DARK_AQUA + "[" + ChatColor.AQUA + "ServerDoctor"
            + ChatColor.DARK_AQUA + "] " + ChatColor.GRAY;
    private final ServerDoctorPlugin plugin;

    public DoctorCommand(ServerDoctorPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("serverdoctor.admin")) {
            say(sender, ChatColor.RED + "You don't have permission (serverdoctor.admin).");
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "scan" -> scan(sender);
            case "status" -> status(sender);
            case "latest" -> latest(sender);
            case "history" -> history(sender, args);
            case "report" -> report(sender, args);
            case "chunks" -> chunks(sender);
            case "spark" -> spark(sender, args);
            case "web" -> web(sender);
            case "schedule" -> schedule(sender);
            case "reload" -> {
                plugin.reloadServices();
                say(sender, ChatColor.GREEN + "Configuration and live services reloaded.");
            }
            default -> help(sender);
        }
        return true;
    }

    private void scan(CommandSender sender) {
        if (plugin.scanner().isRunning()) {
            say(sender, ChatColor.YELLOW + "A scan is already running. Use /doctor status.");
            return;
        }
        long remaining = plugin.scanner().cooldownLeftSeconds(plugin.cooldownSeconds());
        if (remaining > 0) {
            say(sender, ChatColor.YELLOW + "Cooldown: " + remaining + "s remaining.");
            return;
        }
        // Do not retain a Player instance for an asynchronous I/O thread.
        String initiatingPlayerName = sender instanceof Player p ? p.getName() : null;
        boolean isConsole = !(sender instanceof Player);
        int duration = plugin.durationSeconds();
        boolean started = plugin.scanner().begin(duration, plugin.cooldownSeconds(), plugin.thresholds(),
                plugin.includePlugins(), result -> plugin.saveReport(result,
                        () -> {
                            String message = ChatColor.GREEN + "Scan complete! " + ChatColor.WHITE +
                                    "Score " + result.score() + "/100 " + ChatColor.GRAY + " | ID " + result.id();
                            notifySender(isConsole, initiatingPlayerName, message);
                            notifySender(isConsole, initiatingPlayerName,
                                    ChatColor.AQUA + "Report: plugins/ServerDoctor/reports/ServerDoctor-" + result.id() + ".html");
                        }, error -> notifySender(isConsole, initiatingPlayerName,
                                ChatColor.RED + "Scan finished, but report saving failed: " + error)));
        if (started) {
            say(sender, ChatColor.GREEN + "Scanning for ~" + duration + "s. Metrics are sampled every ~20 ticks.");
            say(sender, "Use /doctor status to see progress. No chunks will be loaded by the scan.");
        } else {
            say(sender, ChatColor.YELLOW + "Could not start scan. Retry after the cooldown.");
        }
    }

    private void notifySender(boolean console, String playerName, String message) {
        if (console) {
            Bukkit.getConsoleSender().sendMessage(PREFIX + message);
        } else if (playerName != null) {
            Player target = Bukkit.getPlayerExact(playerName);
            if (target != null && target.isOnline()) target.sendMessage(PREFIX + message);
            else plugin.getLogger().info(ChatColor.stripColor(message));
        }
    }

    private void status(CommandSender sender) {
        if (plugin.scanner().isRunning()) {
            say(sender, ChatColor.YELLOW + "Scanning... " + plugin.scanner().elapsedSeconds() +
                    "/" + plugin.scanner().targetSeconds() + "s | " + plugin.scanner().sampleCount() + " samples");
        } else {
            say(sender, ChatColor.GREEN + "Idle. No scan is running.");
        }
        double[] tps = Bukkit.getTPS();
        say(sender, "Live 1m TPS: " + ChatColor.WHITE + (tps.length > 0 ? Numbers.fixed(tps[0], 2) : "N/A")
                + ChatColor.GRAY + " | MSPT: " + ChatColor.WHITE + Numbers.fixed(Bukkit.getAverageTickTime(), 2));
    }

    private void latest(CommandSender sender) {
        HistoryStore.Entry e = plugin.history().latest();
        if (e == null) {
            say(sender, "No scans found. Start with /doctor scan.");
            return;
        }
        say(sender, ChatColor.WHITE + e.id() + ChatColor.GRAY + " | " + DATE.format(e.instant())
                + " | score " + e.score() + "/100 | " + e.issues() + " issues");
        say(sender, "Use /doctor report latest for the HTML filename.");
    }

    private void history(CommandSender sender, String[] args) {
        int page = 1;
        if (args.length > 1) {
            try { page = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) { page = 1; }
        }
        page = Math.max(1, Math.min(page, 999));
        List<HistoryStore.Entry> entries = plugin.history().recent(plugin.maxHistory());
        int pages = Math.max(1, (entries.size() + 5) / 6);
        if (page > pages) page = pages;
        say(sender, ChatColor.WHITE + "Scan history (page " + page + "/" + pages + ")");
        if (entries.isEmpty()) {
            say(sender, "No reports yet.");
            return;
        }
        for (int index = (page - 1) * 6; index < Math.min(entries.size(), page * 6); index++) {
            HistoryStore.Entry e = entries.get(index);
            say(sender, ChatColor.AQUA + e.id() + ChatColor.GRAY + " | " + e.score() + "/100 | "
                    + Numbers.fixed(e.tps(), 2) + " TPS | " + e.issues() + " issues");
        }
    }

    private void report(CommandSender sender, String[] args) {
        String id = args.length > 1 ? args[1] : "latest";
        if (id.equalsIgnoreCase("latest")) {
            HistoryStore.Entry entry = plugin.history().latest();
            if (entry == null) { say(sender, "No reports yet."); return; }
            id = entry.id();
        }
        if (!HistoryStore.validId(id) || !plugin.history().contains(id)) {
            say(sender, ChatColor.RED + "Unknown/invalid report ID. Use /doctor history.");
            return;
        }
        if (!Files.isRegularFile(plugin.history().reportPath(id))) {
            say(sender, ChatColor.YELLOW + "Index found, but the HTML file is missing.");
            return;
        }
        say(sender, ChatColor.AQUA + "plugins/ServerDoctor/reports/ServerDoctor-" + id + ".html");
        say(sender, "Exports: ServerDoctor-" + id + ".html / .json / .csv");
        say(sender, "Reports remain private on disk; the web panel shows telemetry, not report files.");
    }

    private void chunks(CommandSender sender) {
        if (plugin.chunks().running()) { say(sender, "Chunk inspection already running."); return; }
        boolean ok = plugin.chunks().begin(heatmap -> {
            say(sender, ChatColor.GREEN + "Inspected " + heatmap.chunksInspected() + "/" +
                    heatmap.chunksAvailable() + " loaded chunks" + (heatmap.truncated()? " (limit reached)" : ""));
            if (!heatmap.hotspots().isEmpty()) {
                var hot = heatmap.hotspots().getFirst();
                say(sender, "Top density: " + hot.world() + " [" + hot.x() + ", " + hot.z() +
                        "] | score " + hot.densityScore() + " (not measured CPU time)");
            }
            say(sender, "New /doctor scan reports will include this snapshot.");
        });
        if (ok) say(sender, ChatColor.GREEN + "Inspecting loaded chunks in bounded batches; no new chunks loaded.");
    }

    private void spark(CommandSender sender, String[] args) {
        if (args.length < 2 || (!args[1].equalsIgnoreCase("start") && !args[1].equalsIgnoreCase("stop"))) {
            say(sender, "Usage: /doctor spark start|stop"); return;
        }
        int timeout = Math.max(30, Math.min(900, plugin.getConfig().getInt("spark.timeout-seconds", 180)));
        String command = args[1].equalsIgnoreCase("start")
                ? "spark profiler start --timeout " + timeout : "spark profiler stop";
        try {
            boolean accepted = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            if (!accepted) { say(sender, ChatColor.RED + "Spark command not available. Check Paper console and /spark."); return; }
            say(sender, ChatColor.GREEN + "Sent to Paper: /" + command);
            say(sender, "Spark provides its own profile link in the server console. ServerDoctor does not invent plugin CPU percentages.");
        } catch (Exception ex) { say(sender, ChatColor.RED + "Spark launch failed: " + ex.getMessage()); }
    }

    private void web(CommandSender sender) {
        if (plugin.web().active()) {
            say(sender, ChatColor.GREEN + "Local dashboard: " + plugin.web().address());
            say(sender, "Access token is stored privately in plugins/ServerDoctor/config.yml (do not share it).");
        } else say(sender, "Dashboard disabled. Set web.enabled: true in config.yml and run /doctor reload.");
    }
    private void schedule(CommandSender sender) {
        boolean enabled = plugin.getConfig().getBoolean("scheduled.enabled", false);
        int minutes = plugin.getConfig().getInt("scheduled.interval-minutes", 30);
        say(sender, "Scheduled scans: " + (enabled ? ChatColor.GREEN + "enabled" : ChatColor.YELLOW + "disabled")
                + ChatColor.GRAY + " | every " + minutes + " minutes");
        say(sender, "Adjust scheduled.enabled and scheduled.interval-minutes in config.yml, then /doctor reload.");
    }

    private void help(CommandSender sender) {
        say(sender, ChatColor.WHITE + "ServerDoctor v1.1.1" + ChatColor.GRAY + " | Paper health tools");
        say(sender, "/doctor scan " + ChatColor.DARK_GRAY + "- start timed scan");
        say(sender, "/doctor status " + ChatColor.DARK_GRAY + "- scan progress and live TPS");
        say(sender, "/doctor latest " + ChatColor.DARK_GRAY + "- latest result");
        say(sender, "/doctor history [page] " + ChatColor.DARK_GRAY + "- previous scans");
        say(sender, "/doctor report [id|latest] " + ChatColor.DARK_GRAY + "- local report path");
        say(sender, "/doctor chunks " + ChatColor.DARK_GRAY + "- inspect loaded-chunk density");
        say(sender, "/doctor spark start|stop " + ChatColor.DARK_GRAY + "- run Paper spark profiler");
        say(sender, "/doctor web " + ChatColor.DARK_GRAY + "- show local dashboard status");
        say(sender, "/doctor schedule " + ChatColor.DARK_GRAY + "- scheduled scan settings");
        say(sender, "/doctor reload " + ChatColor.DARK_GRAY + "- reload config and services");
    }

    private static void say(CommandSender sender, String text) { sender.sendMessage(PREFIX + text); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("serverdoctor.admin")) return List.of();
        if (args.length == 1) return COMMANDS.stream()
                .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("report")) {
            List<String> options = new ArrayList<>();
            options.add("latest");
            plugin.history().recent(8).forEach(e -> options.add(e.id()));
            return options.stream().filter(v -> v.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("history")) return List.of("1", "2", "3");
        if (args.length == 2 && args[0].equalsIgnoreCase("spark"))
            return List.of("start", "stop").stream().filter(v -> v.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        return List.of();
    }
}
