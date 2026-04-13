package org.semoss.h2.migration;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationReportTest {

    @TempDir
    Path tempDir;

    @Test
    void emptyReport_hasZeroCounts() {
        MigrationReport report = new MigrationReport();
        report.finish();

        assertEquals(0, report.totalCount());
        assertEquals(0, report.successCount());
        assertEquals(0, report.failedCount());
        assertEquals(0, report.skippedCount());
    }

    @Test
    void addResults_countsCorrectly() {
        MigrationReport report = new MigrationReport();
        report.addResult("/db/a.mv.db", MigrationReport.Status.SUCCESS, "ok", 100);
        report.addResult("/db/b.mv.db", MigrationReport.Status.FAILED, "IO error", 200);
        report.addResult("/db/c.mv.db", MigrationReport.Status.SKIPPED, "already v2", 5);
        report.addResult("/db/d.mv.db", MigrationReport.Status.SUCCESS, "ok", 150);
        report.finish();

        assertEquals(4, report.totalCount());
        assertEquals(2, report.successCount());
        assertEquals(1, report.failedCount());
        assertEquals(1, report.skippedCount());
    }

    @Test
    void toJson_producesValidStructure() {
        MigrationReport report = new MigrationReport();
        report.addResult("/db/test.mv.db", MigrationReport.Status.SUCCESS, "migrated", 500);
        report.finish();

        String json = report.toJson();

        assertTrue(json.contains("\"summary\""));
        assertTrue(json.contains("\"total\": 1"));
        assertTrue(json.contains("\"success\": 1"));
        assertTrue(json.contains("\"databases\""));
        assertTrue(json.contains("/db/test.mv.db"));
        assertTrue(json.contains("\"SUCCESS\""));
    }

    @Test
    void toJson_escapesSpecialChars() {
        MigrationReport report = new MigrationReport();
        report.addResult("C:\\db\\test.mv.db", MigrationReport.Status.FAILED, "error: \"bad\"\nnewline", 10);
        report.finish();

        String json = report.toJson();

        assertTrue(json.contains("C:\\\\db\\\\test.mv.db"));
        assertTrue(json.contains("\\\"bad\\\""));
        assertTrue(json.contains("\\n"));
    }

    @Test
    void writeJson_createsFile() throws IOException {
        MigrationReport report = new MigrationReport();
        report.addResult("/a.mv.db", MigrationReport.Status.SUCCESS, "ok", 100);
        report.finish();

        Path outFile = tempDir.resolve("reports/migration.json");
        report.writeJson(outFile);

        assertTrue(Files.exists(outFile));
        String content = Files.readString(outFile);
        assertTrue(content.startsWith("{"));
        assertTrue(content.contains("\"summary\""));
    }

    @Test
    void getResults_returnsUnmodifiableList() {
        MigrationReport report = new MigrationReport();
        report.addResult("/x.mv.db", MigrationReport.Status.SUCCESS, "ok", 50);

        assertThrows(UnsupportedOperationException.class,
                () -> report.getResults().add(
                        new MigrationReport.DatabaseResult("/y.mv.db", MigrationReport.Status.FAILED, "nope", 0)));
    }

    @Test
    void finish_calledImplicitly_byToJson() {
        MigrationReport report = new MigrationReport();
        report.addResult("/a.mv.db", MigrationReport.Status.SUCCESS, "ok", 100);
        // Don't call finish()
        String json = report.toJson();
        assertTrue(json.contains("\"endTime\""));
    }

    @Test
    @SuppressWarnings("unchecked")
    void toMap_returnsStructuredData() {
        MigrationReport report = new MigrationReport();
        report.addResult("/db/x.mv.db", MigrationReport.Status.SUCCESS, "ok", 300);
        report.addResult("/db/y.mv.db", MigrationReport.Status.FAILED, "timeout", 50);
        report.finish();

        Map<String, Object> map = report.toMap();

        assertNotNull(map.get("summary"));
        assertNotNull(map.get("databases"));

        Map<String, Object> summary = (Map<String, Object>) map.get("summary");
        assertEquals(2L, summary.get("total"));
        assertEquals(1L, summary.get("success"));
        assertEquals(1L, summary.get("failed"));

        List<Map<String, Object>> databases = (List<Map<String, Object>>) map.get("databases");
        assertEquals(2, databases.size());
        // Sorted by status alphabetically: FAILED before SUCCESS
        assertEquals("FAILED", databases.get(0).get("status"));
        assertEquals("/db/x.mv.db", databases.get(1).get("path"));
    }

    @Test
    void toJson_roundTripsViaGson() {
        MigrationReport report = new MigrationReport();
        report.addResult("/db/a.mv.db", MigrationReport.Status.SUCCESS, "migrated", 200);
        report.finish();

        String json = report.toJson();
        Map<String, Object> parsed = new Gson().fromJson(json,
                new TypeToken<Map<String, Object>>() {}.getType());

        assertNotNull(parsed.get("summary"));
        assertNotNull(parsed.get("databases"));
    }
}
