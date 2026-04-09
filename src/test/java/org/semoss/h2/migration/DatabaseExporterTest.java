package org.semoss.h2.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void export_producesValidSqlScript() throws Exception {
        // Create a database with H2 1.4.200
        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200")) {
            String dbName = tempDir.resolve("exporttest").toAbsolutePath().toString();
            String url = "jdbc:h2:" + dbName;

            // Create a table and insert data
            try (Connection conn = oldH2.connect(url, "sa", "");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE T1 (ID INT PRIMARY KEY, NAME VARCHAR(255))");
                stmt.execute("INSERT INTO T1 VALUES (1, 'hello')");
                stmt.execute("INSERT INTO T1 VALUES (2, 'world')");
            }

            // Export
            Path dbFile = Path.of(dbName + ".mv.db");
            assertTrue(Files.exists(dbFile), "DB file should exist");

            DatabaseExporter exporter = new DatabaseExporter(oldH2);
            Path script = exporter.export(dbFile, "sa", "");

            assertTrue(Files.exists(script));
            String content = Files.readString(script);
            assertTrue(content.contains("CREATE"), "Script should contain CREATE statements");
            assertTrue(content.contains("T1"), "Script should reference table T1");
            assertTrue(content.contains("hello"), "Script should contain data");
        }
    }

    @Test
    void export_emptyDatabase_producesScript() throws Exception {
        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200")) {
            String dbName = tempDir.resolve("emptydb").toAbsolutePath().toString();
            try (Connection conn = oldH2.connect("jdbc:h2:" + dbName, "sa", "");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT 1"); // just open and close
            }

            Path dbFile = Path.of(dbName + ".mv.db");
            DatabaseExporter exporter = new DatabaseExporter(oldH2);
            Path script = exporter.export(dbFile, "sa", "");

            assertTrue(Files.exists(script));
            assertTrue(Files.size(script) > 0, "Even empty DB produces some SQL");
        }
    }

    @Test
    void export_withPassword_works() throws Exception {
        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200")) {
            String dbName = tempDir.resolve("pwdb").toAbsolutePath().toString();
            try (Connection conn = oldH2.connect("jdbc:h2:" + dbName, "admin", "secret123");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE STUFF (ID INT)");
            }

            Path dbFile = Path.of(dbName + ".mv.db");
            DatabaseExporter exporter = new DatabaseExporter(oldH2);
            Path script = exporter.export(dbFile, "admin", "secret123");

            assertTrue(Files.exists(script));
        }
    }

    @Test
    void stripExtension_handlesMvDb() {
        Path p = Path.of("/data/test.mv.db");
        String result = DatabaseExporter.stripExtension(p);
        assertTrue(result.endsWith("test"));
        assertFalse(result.endsWith(".mv.db"));
    }

    @Test
    void stripExtension_handlesH2Db() {
        Path p = Path.of("/data/test.h2.db");
        String result = DatabaseExporter.stripExtension(p);
        assertTrue(result.endsWith("test"));
        assertFalse(result.endsWith(".h2.db"));
    }

    @Test
    void stripExtension_noKnownExtension_returnsAsIs() {
        Path p = Path.of("/data/test.sqlite");
        String result = DatabaseExporter.stripExtension(p);
        assertTrue(result.endsWith("test.sqlite"));
    }
}
