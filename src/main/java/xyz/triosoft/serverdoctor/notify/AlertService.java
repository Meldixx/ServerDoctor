package xyz.triosoft.serverdoctor.notify;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import xyz.triosoft.serverdoctor.ServerDoctorPlugin;
import xyz.triosoft.serverdoctor.model.AdvancedData;
import xyz.triosoft.serverdoctor.monitor.MonitorService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import xyz.triosoft.serverdoctor.export.ExportService;

/** Rate-limited alerts. Webhook requests are opt-in, async and constrained to Discord HTTPS. */
public final class AlertService {
    private final ServerDoctorPlugin plugin;
    private final Map<String, Long> recent = new HashMap<>();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();

    public AlertService(ServerDoctorPlugin plugin) { this.plugin = plugin; }
    public void gc(long pauseMs, long collections) {
        notify("gc", "GC pause activity: " + pauseMs + " ms across " + collections +
                " collection(s) since the last sample. Inspect JVM GC logs if repeated.");
    }
    public void spike(AdvancedData.Spike spike) {
        notify("spike", String.format(java.util.Locale.ROOT,
                "Tick spike observed: %.1f ms (rolling TPS %.2f). Investigate with spark.",
                spike.maxTickMs(), spike.tps()));
    }
    public void check(MonitorService.Status sample) {
        if (!plugin.getConfig().getBoolean("alerts.enabled", true)) return;
        if (sample.tps() >= 0 && sample.tps() < plugin.getConfig().getDouble("alerts.low-tps", 17))
            notify("tps", String.format(java.util.Locale.ROOT, "Low TPS: %.2f", sample.tps()));
        if (sample.mspt() > plugin.getConfig().getDouble("alerts.high-mspt", 60))
            notify("mspt", String.format(java.util.Locale.ROOT, "High average MSPT: %.1f ms", sample.mspt()));
        if (sample.heapPercent() > plugin.getConfig().getDouble("alerts.high-heap-percent", 92))
            notify("heap", String.format(java.util.Locale.ROOT, "High JVM heap usage: %.1f%%", sample.heapPercent()));
    }
    private void notify(String key, String message) {
        if (!plugin.getConfig().getBoolean("alerts.enabled", true)) return;
        long now = System.nanoTime();
        long interval = Math.max(30, plugin.getConfig().getLong("alerts.min-interval-seconds", 300));
        Long previous = recent.get(key);
        if (previous != null && now - previous < TimeUnit.SECONDS.toNanos(interval)) return;
        recent.put(key, now);
        plugin.getLogger().warning("[Alert] " + message);
        if (plugin.getConfig().getBoolean("alerts.in-game", true)) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("serverdoctor.alerts")) {
                    player.sendMessage(ChatColor.RED + "[ServerDoctor] " + ChatColor.YELLOW + message);
                }
            }
        }
        if (plugin.getConfig().getBoolean("alerts.discord.enabled", false)) {
            String url = plugin.getConfig().getString("alerts.discord.webhook-url", "");
            if (isValidDiscordWebhook(url)) plugin.runBackground(() -> sendWebhook(url, message));
            else plugin.getLogger().warning("Discord webhook disabled: invalid Discord HTTPS webhook URL.");
        }
    }
    public static boolean isValidDiscordWebhook(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && host != null
                    && (host.equalsIgnoreCase("discord.com") || host.equalsIgnoreCase("discordapp.com"))
                    && uri.getPath().matches("/api/webhooks/[0-9]+/[^/]+")
                    && uri.getUserInfo() == null && uri.getPort() == -1
                    && uri.getQuery() == null && uri.getFragment() == null;
        } catch (Exception e) { return false; }
    }
    private void sendWebhook(String url, String message) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(6))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"" +
                            ExportService.escapeJson("**ServerDoctor** · " + message) + "\"}"))
                    .build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status < 200 || status >= 300) plugin.getLogger().warning("Discord alert HTTP " + status);
        } catch (Exception e) { plugin.getLogger().warning("Discord alert delivery failed: " + e.getClass().getSimpleName()); }
    }
}
