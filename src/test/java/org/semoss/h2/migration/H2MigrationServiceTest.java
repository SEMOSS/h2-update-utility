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

class H2MigrationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void run_localMode_migratesSingleDatabase() throws Exception {
        // Create an H2 1.x database
        Path dbDir = tempDir.resolve("db");
        Files.createDirectories(dbDir);
        String dbName = dbDir.resolve("testdb").toAbsolutePath().toString();

        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200")) {
            try (Connection conn = oldH2.connect("jdbc:h2:" + dbName, "sa", "");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE ITEMS (ID INT PRIMARY KEY, LABEL VARCHAR(100))");
                stmt.execute("INSERT INTO ITEMS VALUES (1, 'alpha')");
                stmt.execute("INSERT INTO ITEMS VALUES (2, 'beta')");
            }
        }

        assertTrue(Files.exists(Path.of(dbName + ".mv.db")));

        // Run migration
        MigrationConfig config = new MigrationConfig(
                MigrationConfig.MigrationMode.LOCAL,
                dbDir,
                tempDir.resolve("backups"),
                false, true, "sa", "",
                "", "", "", "", "",
                "", "", "", false, false,
                "", "", "",
                "", "", "", "",
                "rclone", ""
        );

        H2MigrationService service = new H2MigrationService(config);
        int exitCode = service.run();

        assertEquals(0, exitCode);
        assertEquals(1, service.getReport().successCount());
        assertEquals(0, service.getReport().failedCount());

        // Verify migrated DB works with H2 2.x
        try (IsolatedH2Driver newH2 = IsolatedH2Driver.load("2.4.240")) {
            try (Connection conn = newH2.connect("jdbc:h2:" + dbName, "sa", "");
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ITEMS")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }
        }
    }

    @Test
    void run_localMode_multipleDatabases() throws Exception {
        Path dbDir = tempDir.resolve("multi");
        Path sub1 = dbDir.resolve("engine1");
        Path sub2 = dbDir.resolve("engine2");
        Files.createDirectories(sub1);
        Files.createDirectories(sub2);

        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200")) {
            for (Path sub : new Path[]{sub1, sub2}) {
                String name = sub.resolve("data").toAbsolutePath().toString();
                try (Connection conn = oldH2.connect("jdbc:h2:" + name, "sa", "");
                     Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE TABLE T (V INT)");
                    stmt.execute("INSERT INTO T VALUES (42)");
                }
            }
        }

        MigrationConfig config = new MigrationConfig(
                MigrationConfig.MigrationMode.LOCAL,
                dbDir,
                tempDir.resolve("backups"),
                false, true, "sa", "",
                "", "", "", "", "",
                "", "", "", false, false,
                "", "", "",
                "", "", "", "",
                "rclone", ""
        );

        H2MigrationService service = new H2MigrationService(config);
        int exitCode = service.run();

        assertEquals(0, exitCode);
        assertEquals(2, service.getReport().successCount());
    }

    @Test
    void run_dryRun_doesNotModifyDatabase() throws Exception {
        Path dbDir = tempDir.resolve("dry");
        Files.createDirectories(dbDir);
        String dbName = dbDir.resolve("drydb").toAbsolutePath().toString();

        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200")) {
            try (Connection conn = oldH2.connect("jdbc:h2:" + dbName, "sa", "");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE DRY (ID INT)");
            }
        }

        Path dbFile = Path.of(dbName + ".mv.db");
        long sizeBefore = Files.size(dbFile);

        MigrationConfig config = new MigrationConfig(
                MigrationConfig.MigrationMode.LOCAL,
                dbDir,
                tempDir.resolve("backups"),
                true, false, "sa", "",
                "", "", "", "", "",
                "", "", "", false, false,
                "", "", "",
                "", "", "", "",
                "rclone", ""
        );

        H2MigrationService service = new H2MigrationService(config);
        int exitCode = service.run();

        assertEquals(0, exitCode);
        assertEquals(0, service.getReport().successCount());
        assertEquals(1, service.getReport().skippedCount());
        assertEquals(sizeBefore, Files.size(dbFile), "DB should not be modified in dry run");
    }

    @Test
    void run_emptyDir_noFailures() throws Exception {
        Path dbDir = tempDir.resolve("empty");
        Files.createDirectories(dbDir);

        MigrationConfig config = new MigrationConfig(
                MigrationConfig.MigrationMode.LOCAL,
                dbDir,
                tempDir.resolve("backups"),
                false, false, "sa", "",
                "", "", "", "", "",
                "", "", "", false, false,
                "", "", "",
                "", "", "", "",
                "rclone", ""
        );

        H2MigrationService service = new H2MigrationService(config);
        int exitCode = service.run();

        assertEquals(0, exitCode);
        assertEquals(0, service.getReport().totalCount());
    }

    @Test
    void run_writesReportJson() throws Exception {
        Path dbDir = tempDir.resolve("reporttest");
        Files.createDirectories(dbDir);

        MigrationConfig config = new MigrationConfig(
                MigrationConfig.MigrationMode.LOCAL,
                dbDir,
                tempDir.resolve("backups"),
                false, false, "sa", "",
                "", "", "", "", "",
                "", "", "", false, false,
                "", "", "",
                "", "", "", "",
                "rclone", ""
        );

        H2MigrationService service = new H2MigrationService(config);
        service.run();

        Path reportFile = tempDir.resolve("backups/migration-report.json");
        assertTrue(Files.exists(reportFile));
        String json = Files.readString(reportFile);
        assertTrue(json.contains("\"summary\""));
    }

    @Test
    void run_backupsCreated() throws Exception {
        Path dbDir = tempDir.resolve("bktest");
        Files.createDirectories(dbDir);
        String dbName = dbDir.resolve("bkdb").toAbsolutePath().toString();

        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200")) {
            try (Connection conn = oldH2.connect("jdbc:h2:" + dbName, "sa", "");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE BK (ID INT)");
            }
        }

        Path backupDir = tempDir.resolve("backups-bk");
        MigrationConfig config = new MigrationConfig(
                MigrationConfig.MigrationMode.LOCAL,
                dbDir,
                backupDir,
                false, false, "sa", "",
                "", "", "", "", "",
                "", "", "", false, false,
                "", "", "",
                "", "", "", "",
                "rclone", ""
        );

        H2MigrationService service = new H2MigrationService(config);
        service.run();

        // Backup directory should contain the original file
        assertTrue(Files.exists(backupDir));
        long backupCount = Files.walk(backupDir)
                .filter(p -> p.toString().endsWith(".mv.db"))
                .count();
        assertEquals(1, backupCount, "Should have one backup file");
    }

    @Test
    void run_withSmssCredentials() throws Exception {
        Path dbDir = tempDir.resolve("smss");
        Path engineDir = dbDir.resolve("myEngine");
        Files.createDirectories(engineDir);

        // Create DB with custom credentials
        String dbName = engineDir.resolve("myEngine").toAbsolutePath().toString();
        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200")) {
            try (Connection conn = oldH2.connect("jdbc:h2:" + dbName, "admin", "pwd123");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE CREDS_TEST (V VARCHAR(50))");
                stmt.execute("INSERT INTO CREDS_TEST VALUES ('secret_data')");
            }
        }

        // Write SMSS file
        Path smssFile = dbDir.resolve("myEngine.smss");
        Files.writeString(smssFile, "ENGINE\tmyEngine\nUSERNAME\tadmin\nPASSWORD\tpwd123\n");

        MigrationConfig config = new MigrationConfig(
                MigrationConfig.MigrationMode.LOCAL,
                dbDir,
                tempDir.resolve("backups"),
                false, true, "sa", "",
                "", "", "", "", "",
                "", "", "", false, false,
                "", "", "",
                "", "", "", "",
                "rclone", ""
        );

        H2MigrationService service = new H2MigrationService(config);
        int exitCode = service.run();

        assertEquals(0, exitCode);
        assertEquals(1, service.getReport().successCount());

        // Verify with new driver
        try (IsolatedH2Driver newH2 = IsolatedH2Driver.load("2.4.240")) {
            try (Connection conn = newH2.connect("jdbc:h2:" + dbName, "admin", "pwd123");
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT V FROM CREDS_TEST")) {
                assertTrue(rs.next());
                assertEquals("secret_data", rs.getString(1));
            }
        }
    }
}
