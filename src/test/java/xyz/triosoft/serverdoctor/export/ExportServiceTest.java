package xyz.triosoft.serverdoctor.export;
import org.junit.jupiter.api.Test;
import xyz.triosoft.serverdoctor.model.AdvancedData;
import xyz.triosoft.serverdoctor.model.ScanResult;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ExportServiceTest {
    static ScanResult report() {
        return new ScanResult("20261028-001500-abc123", Instant.parse("2026-10-28T00:15:00Z"), 15000,
                "Paper", "21", "Linux", 10000, 3, 50, 1, 60000000, 120000000,
                500, 1024, 19.8, 19.3, 15, 30, 10,
                List.of(new ScanResult.Sample(0.2,19.7,15,48,10)),
                List.of(new ScanResult.WorldStats("<script>","NORMAL",1,80,10,12)),
                List.of(), List.of(), 90,
                new AdvancedData.Insight(List.of(new AdvancedData.Spike(Instant.now(),135,34,18,3)),6,250,54,60,Instant.now()),
                new AdvancedData.Heatmap(List.of(new AdvancedData.Hotspot("world",2,4,9,5,1,37)),1,1,false,Instant.now()));
    }
    @Test void properlyEscapesJsonValues() {
        assertEquals("\\\"\\\\\\n\\u0001", ExportService.escapeJson("\"\\\n\u0001"));
        String json = ExportService.json(report());
        assertTrue(json.contains("\"worlds\""));
        assertTrue(json.contains("<script>")); // JSON isn't HTML; downstream must not inject into innerHTML.
        assertTrue(json.contains("\"chunks\""));
        assertFalse(json.contains("NaN"));
    }
    @Test void csvContainsMeasuredSamples() {
        assertTrue(ExportService.csv(report()).contains("seconds,tps,mspt,heap_percent,cpu_percent"));
        assertTrue(ExportService.csv(report()).contains("19.700"));
    }
    @Test void advancedReportHasExtraPanels() {
        String html = xyz.triosoft.serverdoctor.report.HtmlReport.generate(report());
        assertTrue(html.contains("Below the surface."));
        assertTrue(html.contains("GC cycles"));
        assertTrue(html.contains("Chunk density"));
    }
}
