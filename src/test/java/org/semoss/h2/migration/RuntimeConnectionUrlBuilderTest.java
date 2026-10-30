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

    @Test
    void removesEveryLegacySettingH2TwoRejects() {
        String url = "jdbc:h2:nio:/db;MVCC=TRUE;MULTI_THREADED=1;MV_STORE=FALSE;ALIAS_COLUMN_NAME=TRUE"
                + ";COMPRESS_LOB=DEFLATE;ACCESS_MODE_LOG=rw;LOG=0;CACHE_SIZE=32768";

        assertEquals(java.util.List.of("MVCC", "MULTI_THREADED", "MV_STORE", "ALIAS_COLUMN_NAME",
                        "COMPRESS_LOB", "ACCESS_MODE_LOG", "LOG"),
                RuntimeConnectionUrlBuilder.unsupportedSettings(url));
        assertEquals("jdbc:h2:nio:/db;CACHE_SIZE=32768",
                RuntimeConnectionUrlBuilder.removeUnsupportedSettings(url));
    }

    @Test
    void keepsMvStoreTrue() {
        String url = "jdbc:h2:/db;MV_STORE=TRUE";

        assertTrue(RuntimeConnectionUrlBuilder.unsupportedSettings(url).isEmpty());
        assertEquals(url, RuntimeConnectionUrlBuilder.removeUnsupportedSettings(url));
    }

    @Test
    void sanitizedLegacyUrlOpensWithH2Two() throws Exception {
        String legacy = "jdbc:h2:" + tempDir.resolve("probe").toAbsolutePath()
                + ";MVCC=TRUE;MULTI_THREADED=TRUE;MV_STORE=FALSE;ALIAS_COLUMN_NAME=TRUE;COMPRESS_LOB=DEFLATE"
                + ";ACCESS_MODE_LOG=rw;EARLY_FILTER=TRUE;LOG=0;UNDO_LOG=0;REDO_LOG_BINARY=FALSE"
                + ";BINARY_COLLATION=UNSIGNED;UUID_COLLATION=UNSIGNED;LARGE_TRANSACTIONS=TRUE"
                + ";PAGE_STORE_INTERNAL_COUNT=TRUE;PAGE_STORE_MAX_GROWTH=1000;PAGE_STORE_TRIM=TRUE"
                + ";NESTED_JOINS=TRUE;OPTIMIZE_UPDATE=TRUE;FUNCTIONS_IN_SCHEMA=TRUE;BATCH_JOINS=TRUE"
                + ";MAX_COMPACT_COUNT=5;STORE_LOCAL_TIME=TRUE;SCOPE_GENERATED_KEYS=TRUE;CACHE_SIZE=32768";

        try (IsolatedH2Driver newH2 = IsolatedH2Driver.load("2.4.240");
             java.sql.Connection conn = newH2.connect(
                     RuntimeConnectionUrlBuilder.removeUnsupportedSettings(legacy), "sa", "");
             java.sql.Statement stmt = conn.createStatement();
             java.sql.ResultSet rs = stmt.executeQuery("SELECT 1")) {
            assertTrue(rs.next());
        }
    }
}
