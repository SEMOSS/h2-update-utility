package org.semoss.h2.migration;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeConnectionUrlBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void fromSmssRelocatesFileAndPreservesOptions() {
        Path dbFile = tempDir.resolve("clone/database.mv.db");

        String url = RuntimeConnectionUrlBuilder.fromSmss(dbFile,
                "jdbc:h2:nio:/original/database;QUERY_TIMEOUT=180000;CACHE_SIZE=32768");

        assertTrue(url.startsWith("jdbc:h2:nio:" + tempDir.resolve("clone/database").toAbsolutePath()));
        assertTrue(url.endsWith(";QUERY_TIMEOUT=180000;CACHE_SIZE=32768"));
    }

    @Test
    void h2FrameUsesSemossRuntimeOptions() {
        Path dbFile = tempDir.resolve("H2_Store_ABC.mv.db");

        String url = RuntimeConnectionUrlBuilder.forH2Frame(dbFile);

        assertEquals("jdbc:h2:nio:" + tempDir.resolve("H2_Store_ABC").toAbsolutePath()
                + ";CACHE_SIZE=65536;LOCK_MODE=1", url);
    }

    @Test
    void fromSmssRejectsNonFileUrl() {
        assertThrows(IllegalArgumentException.class, () ->
                RuntimeConnectionUrlBuilder.fromSmss(tempDir.resolve("database.mv.db"),
                        "jdbc:h2:tcp://localhost/database"));
    }

    @Test
    void unsupportedSettingsFindsKnownH2OneOptionsCaseInsensitively() {
        var settings = RuntimeConnectionUrlBuilder.unsupportedSettings(
                "jdbc:h2:nio:/db;early_filter=true;CACHE_SIZE=1;query_cache_size=24;LOG=0");

        assertEquals(java.util.List.of("EARLY_FILTER", "QUERY_CACHE_SIZE", "LOG"), settings);
    }

    @Test
    void removeUnsupportedSettingsPreservesSupportedOptionsAndOrder() {
        String sanitized = RuntimeConnectionUrlBuilder.removeUnsupportedSettings(
                "jdbc:h2:nio:/db;query_timeout=180000;early_filter=true;"
                        + "CACHE_SIZE=32768;query_cache_size=24;LOCK_MODE=1");

        assertEquals("jdbc:h2:nio:/db;query_timeout=180000;CACHE_SIZE=32768;LOCK_MODE=1",
                sanitized);
    }
}
