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
