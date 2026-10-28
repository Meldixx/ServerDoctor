package xyz.triosoft.serverdoctor.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import xyz.triosoft.serverdoctor.ServerDoctorPlugin;
import xyz.triosoft.serverdoctor.export.ExportService;
import xyz.triosoft.serverdoctor.model.AdvancedData;
import xyz.triosoft.serverdoctor.monitor.MonitorService;
import xyz.triosoft.serverdoctor.storage.HistoryStore;

import java.io.InputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/** Read-only HTTP listener. NEVER reads Bukkit objects from HTTP threads. Loopback only. */
public final class WebDashboard {
    private final ServerDoctorPlugin plugin;
    private HttpServer server;
    private ExecutorService pool;
    private String token;
    private String address;
    public WebDashboard(ServerDoctorPlugin plugin) { this.plugin = plugin; }
    public boolean active() { return server != null; }
    public String address() { return address; }
    public void start() {
        stop();
        if (!plugin.getConfig().getBoolean("web.enabled", false)) return;
        try {
            String host = plugin.getConfig().getString("web.bind", "127.0.0.1");
            InetAddress bind = InetAddress.getByName(host);
            if (!bind.isLoopbackAddress()) throw new IllegalArgumentException(
                    "For safety, the HTTP panel only binds to loopback. Use SSH tunnel or a TLS reverse proxy.");
            token = plugin.getConfig().getString("web.token", "").trim();
            if (token.isBlank()) {
                byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
                token = java.util.HexFormat.of().formatHex(bytes);
                plugin.getConfig().set("web.token", token);
                plugin.saveConfig();
            }
            if (token.length() < 24) throw new IllegalArgumentException("web.token must contain at least 24 characters");
            int port = plugin.getConfig().getInt("web.port", 8765);
            if (port < 1024 || port > 65535) throw new IllegalArgumentException("web.port must be 1024..65535");
            server = HttpServer.create(new InetSocketAddress(bind, port), 32);
            pool = Executors.newFixedThreadPool(2, r -> {
                Thread thread = new Thread(r, "ServerDoctor-web"); thread.setDaemon(true); return thread;
            });
            server.setExecutor(pool);
            server.createContext("/", this::handle);
            server.start();
            address = "http://" + bind.getHostAddress() + ":" + port;
            plugin.getLogger().info("Private web dashboard listening at " + address + " (token configured in config.yml)");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not start private web dashboard", e);
            stop();
        }
    }
    public void stop() {
        if (server != null) { server.stop(0); server = null; }
        if (pool != null) { pool.shutdownNow(); pool = null; }
        address = null;
    }
    private boolean authorized(HttpExchange x) {
        String input = x.getRequestHeaders().getFirst("Authorization");
        byte[] supplied = (input == null ? "" : input).getBytes(StandardCharsets.UTF_8);
        byte[] expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(supplied, expected);
    }
    private void handle(HttpExchange x) throws IOException {
        try {
            x.getResponseHeaders().set("Cache-Control", "no-store, max-age=0");
            x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            x.getResponseHeaders().set("X-Frame-Options", "DENY");
            x.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            x.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'");
            if (!"GET".equals(x.getRequestMethod())) { respond(x, 405, "text/plain", "Method not allowed"); return; }
            String origin = x.getRequestHeaders().getFirst("Origin");
            if (origin != null && !origin.equals(address)) { respond(x, 403, "text/plain", "Origin denied"); return; }
            String path = x.getRequestURI().getPath();
            if (path.equals("/")) { respond(x, 200, "text/html", dashboard()); return; }
            if (!authorized(x)) { respond(x, 401, "application/json", "{\"error\":\"unauthorized\"}"); return; }
            switch (path) {
                case "/api/status" -> respond(x, 200, "application/json", renderStatus());
                case "/api/history" -> respond(x, 200, "application/json", renderHistory());
                case "/api/chunks" -> respond(x, 200, "application/json", renderChunks());
                default -> respond(x, 404, "application/json", "{\"error\":\"not_found\"}");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Web request failed: " + e.getClass().getSimpleName());
            if (x.getResponseCode() == -1) respond(x, 500, "text/plain", "Internal error");
        } finally { x.close(); }
    }
    private static String dashboard() throws IOException {
        try (InputStream in = WebDashboard.class.getResourceAsStream("/dashboard.html")) {
            if (in == null) throw new IOException("Missing dashboard.html");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static void respond(HttpExchange x, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        x.sendResponseHeaders(status, bytes.length);
        try (var out = x.getResponseBody()) { out.write(bytes); }
    }
    private String renderStatus() {
        MonitorService.Telemetry snap = plugin.monitor().view();
        var v = snap.status(); var insight = snap.insight();
        StringBuilder b = new StringBuilder(8000);
        b.append("{\"time\":").append(ExportService.q(v.when().toString()))
                .append(",\"tps\":").append(n(v.tps()))
                .append(",\"mspt\":").append(n(v.mspt()))
                .append(",\"heapPercent\":").append(n(v.heapPercent()))
                .append(",\"maxTickMs\":").append(n(v.maxRecentTickMs()))
                .append(",\"players\":").append(v.players())
                .append(",\"gcCount\":").append(insight.gcCount())
                .append(",\"gcTimeMs\":").append(insight.gcTimeMs())
                .append(",\"nonHeapMiB\":").append(n(insight.nonHeapUsedMiB()))
                .append(",\"points\":[");
        boolean first=true;
        for(var p : snap.points()) {
            if(!first)b.append(','); first=false;
            b.append('[').append(p.epochMillis()).append(',').append(n(p.tps())).append(',')
                    .append(n(p.mspt())).append(',').append(n(p.heapPercent())).append(']');
        }
        b.append("],\"gcEvents\":["); first=true;
        for(var gc : insight.gcEvents()) {
            if (!first) b.append(','); first=false;
            b.append("{\"when\":").append(ExportService.q(gc.when().toString()))
             .append(",\"pauseMs\":").append(gc.pauseDeltaMs()).append('}');
        }
        b.append("],\"spikes\":["); first=true;
        for(var s : insight.spikes()) {
            if(!first)b.append(','); first=false;
            b.append("{\"time\":").append(ExportService.q(s.when().toString()))
                    .append(",\"ms\":").append(n(s.maxTickMs())).append('}');
        }
        return b.append("]}").toString();
    }
    private String renderHistory() {
        StringBuilder b=new StringBuilder("{\"reports\":["); boolean first=true;
        for(HistoryStore.Entry e:plugin.history().recent(30)) {
            if(!first)b.append(','); first=false;
            b.append("{\"id\":").append(ExportService.q(e.id())).append(",\"time\":")
                    .append(e.epoch()).append(",\"score\":").append(e.score())
                    .append(",\"issues\":").append(e.issues()).append('}');
        }
        return b.append("]}").toString();
    }
    private String renderChunks() {
        AdvancedData.Heatmap h=plugin.chunks().latest();
        StringBuilder b=new StringBuilder("{\"inspected\":").append(h.chunksInspected())
                .append(",\"available\":").append(h.chunksAvailable())
                .append(",\"truncated\":").append(h.truncated())
                .append(",\"hotspots\":["); boolean first=true;
        for(AdvancedData.Hotspot spot:h.hotspots()) {
            if(!first)b.append(','); first=false;
            b.append("{\"world\":").append(ExportService.q(spot.world()))
                    .append(",\"x\":").append(spot.x()).append(",\"z\":").append(spot.z())
                    .append(",\"entities\":").append(spot.entities())
                    .append(",\"tiles\":").append(spot.blockEntities())
                    .append(",\"hoppers\":").append(spot.hoppers()).append(",\"density\":")
                    .append(spot.densityScore()).append('}');
        }
        return b.append("]}").toString();
    }
    private static String n(double d) { return Double.isFinite(d) ? String.format(Locale.ROOT,"%.2f",d) : "null"; }
}
