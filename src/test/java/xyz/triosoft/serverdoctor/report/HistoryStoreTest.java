package xyz.triosoft.serverdoctor.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.triosoft.serverdoctor.storage.HistoryStore;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class HistoryStoreTest {
    @TempDir Path tmp;

    @Test void refusesTraversalAndAcceptsIds() throws Exception {
        HistoryStore store = new HistoryStore(tmp);
        assertFalse(HistoryStore.validId("../world/server.properties"));
        assertTrue(HistoryStore.validId("20261027-224000-abc123"));
        assertThrows(IllegalArgumentException.class, () -> store.reportPath("../../oops"));
        assertEquals(0, store.recent(20).size());
    }
}
