package org.semoss.h2.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseImporterTest {

    @TempDir
    Path tempDir;

    // --- Target URL construction ---

    @Test
    void buildTargetUrl_includesAllCompatibilityFlags() {
        Path db = tempDir.resolve("mydb.mv.db");
        String url = DatabaseImporter.buildTargetUrl(db);

        assertTrue(url.startsWith("jdbc:h2:"));
        // The path portion (before the first ';') should have had .mv.db stripped
        String pathPart = url.substring("jdbc:h2:".length(), url.indexOf(';'));
        assertTrue(pathPart.endsWith("mydb"),
                "stripExtension should remove .mv.db from " + pathPart);
        assertTrue(url.contains(";MODE=LEGACY"));
        assertTrue(url.contains(";DEFAULT_NULL_ORDERING=LOW"));
        assertTrue(url.contains(";DATABASE_TO_UPPER=TRUE"));
        assertTrue(url.contains(";NON_KEYWORDS="));
    }

    @Test
    void defaultNonKeywordsIncludesCommonReservedIdentifiers() {
        // These are identifiers customers routinely use that H2 2.x made reserved
        assertTrue(DatabaseImporter.DEFAULT_NON_KEYWORDS.contains("VALUE"));
        assertTrue(DatabaseImporter.DEFAULT_NON_KEYWORDS.contains("KEY"));
        assertTrue(DatabaseImporter.DEFAULT_NON_KEYWORDS.contains("TOP"));
        assertTrue(DatabaseImporter.DEFAULT_NON_KEYWORDS.contains("YEAR"));
        assertTrue(DatabaseImporter.DEFAULT_NON_KEYWORDS.contains("OFFSET"));
        // ROW is deliberately omitted — H2 2.x does not honour it in NON_KEYWORDS
        assertTrue(!DatabaseImporter.DEFAULT_NON_KEYWORDS.contains("ROW"),
                "ROW must not be in NON_KEYWORDS — ScriptRewriter quotes it instead");
    }

    // --- Post-migration smoke open (Category I) ---

    @Test
    void smokeOpen_successfullyOpensMigratedDatabase() throws Exception {
        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200");
             IsolatedH2Driver newH2 = IsolatedH2Driver.load("2.4.240")) {
            String dbName = tempDir.resolve("smoke_ok").toAbsolutePath().toString();
            try (java.sql.Connection conn = oldH2.connect("jdbc:h2:" + dbName, "sa", "");
                 java.sql.Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE T(X INT)");
            }
            Path dbFile = Path.of(dbName + ".mv.db");
            Path script = new DatabaseExporter(oldH2).export(dbFile, "sa", "");
            DatabaseImporter imp = new DatabaseImporter(newH2);
            imp.importDatabase(dbFile, script, "sa", "");

            // smokeOpen should not throw
            imp.smokeOpen(dbFile, "sa", "");
        }
    }

    // --- Category B + D — reserved-keyword columns + MODE=LEGACY ---

    @Test
    void reservedKeywordColumns_migrateViaTargetUrlFlags() throws Exception {
        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200");
             IsolatedH2Driver newH2 = IsolatedH2Driver.load("2.4.240")) {

            String dbName = tempDir.resolve("reserved").toAbsolutePath().toString();
            try (java.sql.Connection conn = oldH2.connect("jdbc:h2:" + dbName, "sa", "");
                 java.sql.Statement stmt = conn.createStatement()) {
                // All of these are 2.x reserved words that used to be fine identifiers in 1.x
                // Use identifiers that are legal in 1.4.200 but were promoted to reserved
                // words in 2.x. VALUE/KEY/TOP/YEAR all survive 1.x as plain identifiers.
                stmt.execute("CREATE TABLE CONFIG (" +
                        "VALUE VARCHAR(100), KEY VARCHAR(100), TOP INT, YEAR INT)");
                stmt.execute("INSERT INTO CONFIG VALUES ('enabled', 'feature_x', 10, 2024)");
            }

            Path dbFile = Path.of(dbName + ".mv.db");
            Path script = new DatabaseExporter(oldH2).export(dbFile, "sa", "");
            new DatabaseImporter(newH2).importDatabase(dbFile, script, "sa", "");

            // Verify we can still query the reserved-word columns by name
            try (java.sql.Connection conn = newH2.connect(
                        DatabaseImporter.buildTargetUrl(dbFile), "sa", "");
                 java.sql.Statement stmt = conn.createStatement();
                 java.sql.ResultSet rs = stmt.executeQuery(
                         "SELECT VALUE, KEY, TOP, YEAR FROM CONFIG")) {
                assertTrue(rs.next());
                assertEquals("enabled", rs.getString(1));
                assertEquals("feature_x", rs.getString(2));
                assertEquals(10, rs.getInt(3));
                assertEquals(2024, rs.getInt(4));
            }
        }
    }

    @Test
    void importDatabase_fullRoundTrip() throws Exception {
        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200");
             IsolatedH2Driver newH2 = IsolatedH2Driver.load("2.4.240")) {

            // 1. Create DB with H2 1.x
            String dbName = tempDir.resolve("roundtrip").toAbsolutePath().toString();
            try (Connection conn = oldH2.connect("jdbc:h2:" + dbName, "sa", "");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE USERS (ID INT PRIMARY KEY, NAME VARCHAR(100))");
                stmt.execute("INSERT INTO USERS VALUES (1, 'Alice')");
                stmt.execute("INSERT INTO USERS VALUES (2, 'Bob')");
            }

            // 2. Export with old driver
            Path dbFile = Path.of(dbName + ".mv.db");
            DatabaseExporter exporter = new DatabaseExporter(oldH2);
            Path script = exporter.export(dbFile, "sa", "");
            assertTrue(Files.exists(script));

            // 3. Import with new driver
            DatabaseImporter importer = new DatabaseImporter(newH2);
            importer.importDatabase(dbFile, script, "sa", "");
            assertTrue(Files.exists(dbFile));

            // 4. Verify data with new driver
            try (Connection conn = newH2.connect("jdbc:h2:" + dbName, "sa", "");
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM USERS")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }
        }
    }

    @Test
    void importDatabase_preservesDataTypes() throws Exception {
        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200");
             IsolatedH2Driver newH2 = IsolatedH2Driver.load("2.4.240")) {

            String dbName = tempDir.resolve("types").toAbsolutePath().toString();
            try (Connection conn = oldH2.connect("jdbc:h2:" + dbName, "sa", "");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE TYPES_TEST (" +
                        "I INT, B BOOLEAN, D DOUBLE, V VARCHAR(50), C CLOB, BL BLOB)");
                stmt.execute("INSERT INTO TYPES_TEST VALUES (42, TRUE, 3.14, 'text', 'clob_data', X'DEADBEEF')");
            }

            Path dbFile = Path.of(dbName + ".mv.db");
            Path script = new DatabaseExporter(oldH2).export(dbFile, "sa", "");
            new DatabaseImporter(newH2).importDatabase(dbFile, script, "sa", "");

            try (Connection conn = newH2.connect("jdbc:h2:" + dbName, "sa", "");
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT I, B, D, V FROM TYPES_TEST")) {
                assertTrue(rs.next());
                assertEquals(42, rs.getInt("I"));
                assertTrue(rs.getBoolean("B"));
                assertEquals(3.14, rs.getDouble("D"), 0.001);
                assertEquals("text", rs.getString("V"));
            }
        }
    }

    @Test
    void importDatabase_withPassword() throws Exception {
        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200");
             IsolatedH2Driver newH2 = IsolatedH2Driver.load("2.4.240")) {

            String dbName = tempDir.resolve("pwtest").toAbsolutePath().toString();
            try (Connection conn = oldH2.connect("jdbc:h2:" + dbName, "admin", "pass!");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE SECURE_DATA (VAL INT)");
                stmt.execute("INSERT INTO SECURE_DATA VALUES (999)");
            }

            Path dbFile = Path.of(dbName + ".mv.db");
            Path script = new DatabaseExporter(oldH2).export(dbFile, "admin", "pass!");
            new DatabaseImporter(newH2).importDatabase(dbFile, script, "admin", "pass!");

            try (Connection conn = newH2.connect("jdbc:h2:" + dbName, "admin", "pass!");
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT VAL FROM SECURE_DATA")) {
                assertTrue(rs.next());
                assertEquals(999, rs.getInt(1));
            }
        }
    }

}
