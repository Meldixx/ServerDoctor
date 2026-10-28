package xyz.triosoft.serverdoctor.report;

import org.junit.jupiter.api.Test;
import xyz.triosoft.serverdoctor.model.ScanResult;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class HtmlReportTest {
    @Test void worldNamesAndPluginsAreEscaped() {
        ScanResult report = new ScanResult("20261027-223000-abcdef", Instant.now(), 15100,
                "Paper <unsafe>", "21", "Linux", 1000, 1, 20, 2,
                50_000_000_000L, 100_000_000_000L, 500, 2048, 19.9, 19.7,
                10, 12, 11.2,
                List.of(new ScanResult.Sample(1, 19.8, 10, 25, 11.2)),
                List.of(new ScanResult.WorldStats("<img src=x onerror=alert(1)>", "NORMAL", 1, 1, 5, 1)),
                List.of(new ScanResult.PluginInfo("<script>alert(1)</script>", "1.0")),
                List.of(new ScanResult.Finding(ScanResult.Severity.INFO, "None", "All good", "Keep monitoring")), 100);
        String html = HtmlReport.generate(report);
        assertFalse(html.contains("<script>alert(1)</script>"));
        assertFalse(html.contains("<img src=x onerror=alert(1)>"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"));
        assertTrue(html.contains("<svg"));
        assertTrue(html.contains("Read between the ticks."));
        assertTrue(html.contains("data-filter=\"critical\""));
        assertTrue(html.contains("report-template.html") == false);
        // SVG namespace identifiers are not remote asset requests.
        assertTrue(html.contains("xmlns=\"http://www.w3.org/2000/svg\""));
        assertFalse(Pattern.compile("(?i)\\b(?:src|href)\\s*=\\s*['\"]\\s*https?://")
                .matcher(html).find(), "Unexpected remote asset in offline report");
        assertFalse(Pattern.compile("(?i)@import\\s+(?:url\\s*\\()?\\s*['\"]?\\s*https?://")
                .matcher(html).find(), "Unexpected external CSS import");
        assertFalse(Pattern.compile("(?i)\\bfetch\\s*\\(\\s*['\"]\\s*https?://")
                .matcher(html).find(), "Unexpected external fetch");
        assertFalse(html.contains("{{REPORT_ID}}"));
        assertTrue(html.contains("Search installed plugins"));
        assertTrue(html.contains("data-series=\""));
        assertTrue(html.contains("Every tick"));
        assertTrue(html.contains("World observatory") || html.contains("WORLD OBSERVATORY"));
    }
    @Test void missingTimeSeriesDoesNotCrash() {
        ScanResult r = new ScanResult("20261027-223000-xyz", Instant.now(), 0,
            "Paper", "21", "Linux", 0, 0, 20, 0, -1, -1,
            0, 0, -1, -1, -1, -1, -1,
            List.of(), List.of(), List.of(), List.of(), 100);
        String html = HtmlReport.generate(r);
        assertTrue(html.contains("No valid samples collected"));
        assertTrue(html.contains("No worlds collected"));
        assertTrue(html.contains("No diagnostic findings recorded"));
    }

    @Test void templateTokensInsideUntrustedDataNeverExpand() {
        ScanResult r = new ScanResult("20261027-223000-abcdef", Instant.now(), 0,
            "Paper", "21", "Linux", 0, 0, 20, 0, -1, -1,
            0, 0, 19, 19, 30, 30, 25,
            List.of(), List.of(new ScanResult.WorldStats("{{SCORE}} & <a>", "NORMAL", 0, 0, 0, 0)),
            List.of(), List.of(), 87);
        String html = HtmlReport.generate(r);
        assertTrue(html.contains("{{SCORE}} &amp; &lt;a&gt;"));
        assertFalse(html.contains("87 &amp; &lt;a&gt;"));
    }
}
