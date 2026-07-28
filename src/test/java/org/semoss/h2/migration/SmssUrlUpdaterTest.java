package org.semoss.h2.migration;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmssUrlUpdaterTest {

    @TempDir
    Path tempDir;

    @Test
    void applyRemovesOnlyKnownUnsupportedSettings() throws Exception {
        Path smss = tempDir.resolve("engine.smss");
        Files.writeString(smss, """
                RDBMS_TYPE\tH2_DB
                CONNECTION_URL\tjdbc:h2:nio:/db;query_timeout=180000;early_filter=true;CACHE_SIZE=32768;query_cache_size=24
                """);

        var plan = SmssUrlUpdater.preview(smss);
        assertEquals(java.util.List.of("EARLY_FILTER", "QUERY_CACHE_SIZE"),
                plan.removedSettings());

        SmssUrlUpdater.apply(plan);

        String updated = Files.readString(smss);
        assertTrue(updated.contains("query_timeout=180000"));
        assertTrue(updated.contains("CACHE_SIZE=32768"));
        assertFalse(updated.toLowerCase().contains("early_filter"));
        assertFalse(updated.toLowerCase().contains("query_cache_size"));
    }
}
