package xyz.triosoft.serverdoctor.analysis;

import org.junit.jupiter.api.Test;
import xyz.triosoft.serverdoctor.model.ScanResult;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzerTest {
    private final Thresholds thresholds = new Thresholds(18.5, 45, 85, 90, 1800, 4500, 2500, 2);

    @Test void healthySnapshotProducesMaxScore() {
        Analyzer.Analysis result = Analyzer.evaluate(
                new Analyzer.Metrics(19.9, 18, 45, 30, 30L << 30, 12,
                        List.of(new ScanResult.WorldStats("world", "NORMAL", 2, 500, 200, 300))), thresholds);
        assertEquals(100, result.score());
        assertEquals(ScanResult.Severity.INFO, result.findings().getFirst().severity());
    }

    @Test void highMsptAndEntitiesCreateFindings() {
        Analyzer.Analysis result = Analyzer.evaluate(
                new Analyzer.Metrics(17, 53, 92, 98, 200_000, 15,
                        List.of(new ScanResult.WorldStats("world", "NORMAL", 2, 800, 2500, 700))), thresholds);
        assertTrue(result.score() < 70);
        assertTrue(result.findings().stream().anyMatch(f -> f.title().equals("High MSPT")));
        assertTrue(result.findings().stream().anyMatch(f -> f.title().startsWith("Many entities")));
    }

    @Test void missingProcessCpuDoesNotTriggerCpuAlert() {
        Analyzer.Analysis result = Analyzer.evaluate(
                new Analyzer.Metrics(20, 10, 15, -1, 40L << 30, 15, List.of()), thresholds);
        assertFalse(result.findings().stream().anyMatch(f -> f.title().contains("CPU")));
    }
}
