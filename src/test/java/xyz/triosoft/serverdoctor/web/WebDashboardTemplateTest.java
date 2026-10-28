package xyz.triosoft.serverdoctor.web;

import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class WebDashboardTemplateTest {
    private String template() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/dashboard.html")) {
            assertTrue(in != null, "The dashboard must be packaged inside the plugin JAR");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    @Test void reportsCurrentVersion() throws Exception {
        String html = template();
        assertTrue(html.contains("v1.1.2"));
        assertTrue(html.contains("SERVERDOCTOR OBSERVATORY"));
    }
    @Test void bindsToExistingApiEndpoints() throws Exception {
        String html = template();
        for (String route : new String[]{"/api/status", "/api/history", "/api/chunks"})
            assertTrue(html.contains(route), "Missing endpoint " + route);
        for (String id : new String[]{"login", "tps", "mspt", "players", "chart", "events", "reports", "chunkMap"})
            assertTrue(html.contains("id=\"" + id + "\""), "Missing element " + id);
    }
    @Test void hasNoExternalRuntimeAssets() throws Exception {
        String html = template();
        assertFalse(html.contains("<script src="));
        assertFalse(html.contains("<link rel=\"stylesheet\""));
        assertFalse(html.contains("https://cdn."));
    }
}
