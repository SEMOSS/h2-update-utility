package org.semoss.h2.migration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationReportTest {

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
    @SuppressWarnings("unchecked")
    void failedResult_capturesSqlFailureContext() {
        MigrationReport report = new MigrationReport();
        report.addResult("/db/bad.mv.db", MigrationReport.Status.FAILED, "syntax error", 100)
                .sqlState("42000")
                .errorCode(42001)
                .errorClass("org.h2.jdbc.JdbcSQLSyntaxErrorException")
                .failingStatement("CREATE TABLE FOO(VALUE VARCHAR(50)")
                .preservedScriptPath("/backups/run1/bad_export.sql")
                .addWarning("CREATE VIEW present — expressions re-parsed under 2.x");
        report.finish();

        Map<String, Object> map = report.toMap();
        List<Map<String, Object>> dbs = (List<Map<String, Object>>) map.get("databases");
        Map<String, Object> first = dbs.get(0);

        assertEquals("42000", first.get("sqlState"));
        assertEquals(42001, first.get("errorCode"));
        assertEquals("org.h2.jdbc.JdbcSQLSyntaxErrorException", first.get("errorClass"));
        assertEquals("CREATE TABLE FOO(VALUE VARCHAR(50)", first.get("failingStatement"));
        assertEquals("/backups/run1/bad_export.sql", first.get("preservedScriptPath"));
        assertTrue(((List<?>) first.get("warnings")).size() == 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void successResult_capturesTransformations() {
        MigrationReport report = new MigrationReport();
        report.addResult("/db/good.mv.db", MigrationReport.Status.SUCCESS, "ok", 500)
                .transformation("typeAliasReplacements", Map.of("DATETIME", 3, "LONGVARCHAR", 1))
                .transformation("targetUrlFlags", List.of("MODE=LEGACY", "NON_KEYWORDS=VALUE,KEY"))
                .transformation("smokeOpen", "OK");
        report.finish();

        String json = report.toJson();
        assertTrue(json.contains("typeAliasReplacements"));
        // Gson pretty-prints nested arrays; the important thing is the value round-trips
        assertTrue(json.contains("MODE") && json.contains("LEGACY"), "got json: " + json);
        assertTrue(json.contains("smokeOpen") && json.contains("OK"));

        Map<String, Object> map = report.toMap();
        List<Map<String, Object>> dbs = (List<Map<String, Object>>) map.get("databases");
        Map<String, Object> t = (Map<String, Object>) dbs.get(0).get("transformations");
        assertNotNull(t.get("typeAliasReplacements"));
        assertNotNull(t.get("targetUrlFlags"));
        assertEquals("OK", t.get("smokeOpen"));
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

}
