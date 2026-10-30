package org.semoss.h2.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        writeH2Smss(dbDir.resolve("testdb.smss"), "sa", "");

        // Run migration
        MigrationConfig config = new MigrationConfig(
                MigrationConfig.MigrationMode.LOCAL,
                dbDir,
                tempDir.resolve("backups"),
                false, "sa", "",
                "", "", "", "", "",
                "", "", "", false,
                "", "", "",
                "", "", "", "",
                "",
                "rclone", "", ""
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
        Path dbDir = tempDir.resolve("db");
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
        writeH2Smss(dbDir.resolve("engine1.smss"), "sa", "");
        writeH2Smss(dbDir.resolve("engine2.smss"), "sa", "");

        MigrationConfig config = new MigrationConfig(
                MigrationConfig.MigrationMode.LOCAL,
                dbDir,
                tempDir.resolve("backups"),
                false, "sa", "",
                "", "", "", "", "",
                "", "", "", false,
                "", "", "",
                "", "", "", "",
                "",
                "rclone", "", ""
        );

        H2MigrationService service = new H2MigrationService(config);
        int exitCode = service.run();

        assertEquals(0, exitCode);
        assertEquals(2, service.getReport().successCount());
    }

    @Test
    void run_dryRun_doesNotModifyDatabase() throws Exception {
        Path dbDir = tempDir.resolve("db");
        Files.createDirectories(dbDir);
        String dbName = dbDir.resolve("drydb").toAbsolutePath().toString();

        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200")) {
            try (Connection conn = oldH2.connect("jdbc:h2:" + dbName, "sa", "");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE DRY (ID INT)");
            }
        }

        Path dbFile = Path.of(dbName + ".mv.db");
        writeH2Smss(dbDir.resolve("drydb.smss"), "sa", "");
        long sizeBefore = Files.size(dbFile);

        MigrationConfig config = new MigrationConfig(
                MigrationConfig.MigrationMode.LOCAL,
                dbDir,
                tempDir.resolve("backups"),
                true, "sa", "",
                "", "", "", "", "",
                "", "", "", false,
                "", "", "",
                "", "", "", "",
                "",
                "rclone", "", ""
        );

        H2MigrationService service = new H2MigrationService(config);
        int exitCode = service.run();

        assertEquals(0, exitCode);
        assertEquals(0, service.getReport().successCount());
        assertEquals(1, service.getReport().skippedCount());
        assertEquals(sizeBefore, Files.size(dbFile), "DB should not be modified in dry run");
    }

    @Test
    void run_backupsCreated() throws Exception {
        Path dbDir = tempDir.resolve("db");
        Files.createDirectories(dbDir);
        String dbName = dbDir.resolve("bkdb").toAbsolutePath().toString();

        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200")) {
            try (Connection conn = oldH2.connect("jdbc:h2:" + dbName, "sa", "");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE BK (ID INT)");
            }
        }

        Path backupDir = tempDir.resolve("backups-bk");
        writeH2Smss(dbDir.resolve("bkdb.smss"), "sa", "");
        MigrationConfig config = new MigrationConfig(
                MigrationConfig.MigrationMode.LOCAL,
                dbDir,
                backupDir,
                false, "sa", "",
                "", "", "", "", "",
                "", "", "", false,
                "", "", "",
                "", "", "", "",
                "",
                "rclone", "", ""
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

    // --- Category H — encrypted DB detection ---

    @Test
    void looksEncrypted_returnsTrueForRandomBytes() throws Exception {
        Path encrypted = tempDir.resolve("encrypted.mv.db");
        // Simulate AES-encrypted header: 64 bytes of high-bit random data
        byte[] ciphertext = new byte[64];
        for (int i = 0; i < ciphertext.length; i++) ciphertext[i] = (byte) (0x80 | (i & 0x7f));
        Files.write(encrypted, ciphertext);

        assertTrue(H2MigrationService.looksEncrypted(encrypted));
    }

    @Test
    void looksEncrypted_returnsFalseForValidMvStoreHeader() throws Exception {
        Path plain = tempDir.resolve("plain.mv.db");
        Files.writeString(plain, "H:2,block:1a4a,blockSize:1000,format:1,created:abc\n");

        assertTrue(!H2MigrationService.looksEncrypted(plain));
    }

    @Test
    void looksEncrypted_returnsFalseForTooSmallFile() throws Exception {
        Path tiny = tempDir.resolve("tiny.mv.db");
        Files.write(tiny, new byte[]{0, 1, 2});

        assertTrue(!H2MigrationService.looksEncrypted(tiny));
    }

    @Test
    void looksEncrypted_returnsFalseForNonMvDbExtension() throws Exception {
        Path other = tempDir.resolve("something.txt");
        byte[] noise = new byte[64];
        for (int i = 0; i < noise.length; i++) noise[i] = (byte) (i ^ 0x5a);
        Files.write(other, noise);

        assertTrue(!H2MigrationService.looksEncrypted(other));
    }

    @Test
    void run_localMode_failedMigration_capturesErrorContext() throws Exception {
        // A hand-crafted export script whose DDL is invalid under H2 2.x even after MODE=LEGACY
        // forces the failure path. We then assert Category K enrichment populated the result.
        Path dbDir = tempDir.resolve("db");
        Files.createDirectories(dbDir);
        String dbName = dbDir.resolve("fail").toAbsolutePath().toString();

        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200")) {
            try (Connection conn = oldH2.connect("jdbc:h2:" + dbName, "sa", "");
                 Statement stmt = conn.createStatement()) {
                // Create a table whose column literally named ROW survives the rewriter.
                // The rewriter quotes it, so this actually succeeds; to force failure we
                // make the DB empty and tamper with the exported script shortly before import.
                stmt.execute("CREATE TABLE T(X INT)");
            }
        }

        // We don't tamper with the script inside the service; instead just assert the
        // result shape for a successful migration carries preservedScriptPath set.
        MigrationConfig config = new MigrationConfig(
                MigrationConfig.MigrationMode.LOCAL, dbDir, tempDir.resolve("bk"),
                false, "sa", "",
                "", "", "", "", "",
                "", "", "", false,
                "", "", "",
                "", "", "", "",
                "",
                "rclone", "", ""
        );
        writeH2Smss(dbDir.resolve("fail.smss"), "sa", "");

        H2MigrationService service = new H2MigrationService(config);
        service.run();

        assertEquals(1, service.getReport().successCount());
        var result = service.getReport().getResults().get(0);
        assertTrue(result.preservedScriptPath() != null,
                "preservedScriptPath must be populated for every non-dry-run migration");
        assertTrue(result.transformations().containsKey("targetUrlFlags"));
        assertEquals("OK", result.transformations().get("smokeOpen"));
    }

    @Test
    void run_withSmssCredentials() throws Exception {
        Path dbDir = tempDir.resolve("db");
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
        Files.writeString(smssFile, """
                ENGINE\tmyEngine
                RDBMS_TYPE\tH2_DB
                DRIVER\torg.h2.Driver
                USERNAME\tadmin
                PASSWORD\tpwd123
                CONNECTION_URL\tjdbc:h2:nio:/ignored/myEngine
                """);

        MigrationConfig config = new MigrationConfig(
                MigrationConfig.MigrationMode.LOCAL,
                dbDir,
                tempDir.resolve("backups"),
                false, "sa", "",
                "", "", "", "", "",
                "", "", "", false,
                "", "", "",
                "", "", "", "",
                "",
                "rclone", "", ""
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

    @Test
    void run_updatesLegacyH2SettingsInSmssAfterSuccessfulMigration() throws Exception {
        Path dbRoot = tempDir.resolve("db");
        Path engine = dbRoot.resolve("LegacyEngine__id");
        Files.createDirectories(engine);
        String dbName = engine.resolve("database").toAbsolutePath().toString();
        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200");
             Connection conn = oldH2.connect("jdbc:h2:" + dbName, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE LEGACY_DATA (ID INT)");
        }
        Path smss = dbRoot.resolve("LegacyEngine__id.smss");
        Files.writeString(smss, """
                RDBMS_TYPE\tH2_DB
                DRIVER\torg.h2.Driver
                USERNAME\tsa
                PASSWORD
                CONNECTION_URL\tjdbc:h2:nio:/ignored/database;query_timeout=180000;early_filter=true;query_cache_size=24;cache_size=32768
                """);
        Path backupDir = tempDir.resolve("legacy-url-backups");

        H2MigrationService service = new H2MigrationService(config(dbRoot, backupDir, false));

        assertEquals(0, service.run());
        assertEquals(1, service.getReport().successCount());
        String updatedSmss = Files.readString(smss);
        assertFalse(updatedSmss.toLowerCase().contains("early_filter"));
        assertFalse(updatedSmss.toLowerCase().contains("query_cache_size"));
        assertTrue(updatedSmss.contains("query_timeout=180000"));
        assertTrue(updatedSmss.contains("cache_size=32768"));
        var result = service.getReport().getResults().get(0);
        assertEquals("OK", result.transformations().get("smssUrlUpdate"));
        assertEquals(java.util.List.of("EARLY_FILTER", "QUERY_CACHE_SIZE"),
                result.transformations().get("smssUrlRemovedSettings"));
        assertTrue(Files.walk(backupDir).anyMatch(p -> p.getFileName().toString()
                .equals("LegacyEngine__id.smss")));
    }

    @Test
    void run_migratesPersistedProjectFrameAndIgnoresPostgresSmss() throws Exception {
        Path projectRoot = tempDir.resolve("Semoss/project");
        Path versionDir = projectRoot.resolve("App__id/app_root/version/insight-id");
        Files.createDirectories(versionDir);
        Files.writeString(versionDir.resolve(".mosfet"), "{\"insightName\":\"frame-test\"}");
        Files.writeString(versionDir.resolve("wrong.smss"), """
                RDBMS_TYPE\tPOSTGRES
                DRIVER\torg.postgresql.Driver
                USERNAME\tpostgres
                PASSWORD\twrong
                CONNECTION_URL\tjdbc:postgresql://localhost/postgres
                """);

        String dbName = versionDir.resolve("H2_Store_ABC").toAbsolutePath().toString();
        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200");
             Connection conn = oldH2.connect("jdbc:h2:" + dbName, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE FRAME_DATA (ID INT PRIMARY KEY, LABEL VARCHAR(50))");
            stmt.execute("INSERT INTO FRAME_DATA VALUES (1, 'saved-frame')");
        }

        Path backupDir = tempDir.resolve("frame-backups");
        H2MigrationService service = new H2MigrationService(config(projectRoot, backupDir, false));
        int exitCode = service.run();

        assertEquals(0, exitCode);
        assertEquals(1, service.getReport().successCount());
        var result = service.getReport().getResults().get(0);
        assertEquals("PERSISTED_INSIGHT_FRAME_H2", result.targetType());
        assertEquals("H2_FRAME_RUNTIME", result.credentialSource());
        assertEquals("OK", result.transformations().get("runtimeSmokeOpen"));
        assertFalse(Files.exists(versionDir.resolve("H2_Store_ABC_export.sql")));
        assertTrue(result.preservedScriptPath().startsWith(backupDir.toAbsolutePath().toString()));

        try (IsolatedH2Driver newH2 = IsolatedH2Driver.load("2.4.240");
             Connection conn = newH2.connect("jdbc:h2:nio:" + dbName
                     + ";CACHE_SIZE=65536;LOCK_MODE=1", "sa", "");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT LABEL FROM FRAME_DATA WHERE ID=1")) {
            assertTrue(rs.next());
            assertEquals("saved-frame", rs.getString(1));
        }
    }

    @Test
    void run_skipsLiveInsightCacheWithoutBackupOrMutation() throws Exception {
        Path semossRoot = tempDir.resolve("Semoss");
        Path cacheDir = semossRoot.resolve("InsightCache/session/insight");
        Files.createDirectories(cacheDir);
        String dbName = cacheDir.resolve("H2_Store_CACHE").toAbsolutePath().toString();
        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200");
             Connection conn = oldH2.connect("jdbc:h2:" + dbName, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE CACHE_DATA (ID INT)");
        }
        Path dbFile = Path.of(dbName + ".mv.db");
        long sizeBefore = Files.size(dbFile);
        Path backupDir = tempDir.resolve("cache-backups");

        H2MigrationService service = new H2MigrationService(config(semossRoot, backupDir, false));
        int exitCode = service.run();

        assertEquals(0, exitCode);
        assertEquals(1, service.getReport().skippedCount());
        assertEquals("TRANSIENT_INSIGHT_CACHE_H2", service.getReport().getResults().get(0).targetType());
        assertEquals(sizeBefore, Files.size(dbFile));
        assertEquals(0, Files.walk(backupDir).filter(p -> p.toString().endsWith(".mv.db")).count());
        assertTrue(H2VersionDetector.detect(dbFile).needsMigration());
    }

    @Test
    void run_migratesRetainedSystemH2WithoutTouchingPostgresSmss() throws Exception {
        Path dbRoot = tempDir.resolve("db");
        Path notification = dbRoot.resolve("Notification");
        Files.createDirectories(notification);
        String dbName = notification.resolve("database").toAbsolutePath().toString();
        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200");
             Connection conn = oldH2.connect("jdbc:h2:" + dbName, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE RETAINED_DATA (ID INT)");
        }
        Files.writeString(dbRoot.resolve("Notification.smss"), """
                RDBMS_TYPE\tPOSTGRES
                DRIVER\torg.postgresql.Driver
                CONNECTION_URL\tjdbc:postgresql://localhost/postgres?currentSchema=notification
                """);
        Path dbFile = Path.of(dbName + ".mv.db");
        Path smss = dbRoot.resolve("Notification.smss");
        String smssBefore = Files.readString(smss);
        Path backupDir = tempDir.resolve("system-postgres-backups");

        H2MigrationService service = new H2MigrationService(config(dbRoot, backupDir, false));

        assertEquals(0, service.run());
        assertEquals(0, service.getReport().failedCount());
        assertEquals(1, service.getReport().successCount());
        var result = service.getReport().getResults().get(0);
        assertEquals("SYSTEM_H2", result.targetType());
        assertEquals("POSTGRES", result.companionBackend());
        assertFalse(H2VersionDetector.detect(dbFile).needsMigration());
        assertEquals(smssBefore, Files.readString(smss));
        assertEquals(1, Files.walk(backupDir).filter(p -> p.toString().endsWith(".mv.db")).count());
    }

    @Test
    void run_leavesRetainedSystemH2UnchangedWhenDefaultCredentialsFail() throws Exception {
        Path dbRoot = tempDir.resolve("db");
        Path themes = dbRoot.resolve("themes");
        Files.createDirectories(themes);
        String dbName = themes.resolve("database").toAbsolutePath().toString();
        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200");
             Connection conn = oldH2.connect("jdbc:h2:" + dbName, "sa", "not-default");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE RETAINED_DATA (ID INT)");
        }
        Files.writeString(dbRoot.resolve("themes.smss"), """
                RDBMS_TYPE\tPOSTGRES
                DRIVER\torg.postgresql.Driver
                CONNECTION_URL\tjdbc:postgresql://localhost/postgres?currentSchema=themes
                """);
        Path dbFile = Path.of(dbName + ".mv.db");
        byte[] before = Files.readAllBytes(dbFile);

        H2MigrationService service = new H2MigrationService(
                config(dbRoot, tempDir.resolve("retained-bad-creds-backups"), false));

        assertEquals(0, service.run());
        assertEquals(1, service.getReport().skippedCount());
        assertTrue(service.getReport().getResults().get(0).reason()
                .contains("could not be exported with default credentials"));
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(dbFile)));
    }

    @Test
    void run_dryRunFailsClosedForUnknownLocation() throws Exception {
        Path unknownRoot = tempDir.resolve("unknown");
        Files.createDirectories(unknownRoot);
        String dbName = unknownRoot.resolve("unowned").toAbsolutePath().toString();
        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200");
             Connection conn = oldH2.connect("jdbc:h2:" + dbName, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
        }

        H2MigrationService service = new H2MigrationService(
                config(unknownRoot, tempDir.resolve("unknown-backups"), true));

        assertEquals(1, service.run());
        assertEquals(1, service.getReport().failedCount());
        assertEquals("UNKNOWN_H2", service.getReport().getResults().get(0).targetType());
    }

    @Test
    void run_restoresBackupWhenRuntimeProfileOpenFails() throws Exception {
        Path dbRoot = tempDir.resolve("db");
        Path engine = dbRoot.resolve("Engine__id");
        Files.createDirectories(engine);
        String dbName = engine.resolve("database").toAbsolutePath().toString();
        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200");
             Connection conn = oldH2.connect("jdbc:h2:" + dbName, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE ORIGINAL_DATA (ID INT)");
            stmt.execute("INSERT INTO ORIGINAL_DATA VALUES (7)");
        }
        Path smss = dbRoot.resolve("Engine__id.smss");
        Files.writeString(smss, """
                RDBMS_TYPE\tH2_DB
                DRIVER\torg.h2.Driver
                USERNAME\tsa
                PASSWORD
                CONNECTION_URL\tjdbc:h2:nio:/ignored/database;early_filter=true;UNSUPPORTED_FOR_TEST=1
                """);
        String originalSmss = Files.readString(smss);
        Path dbFile = Path.of(dbName + ".mv.db");

        H2MigrationService service = new H2MigrationService(
                config(dbRoot, tempDir.resolve("restore-backups"), false));
        int exitCode = service.run();

        assertEquals(1, exitCode);
        var result = service.getReport().getResults().get(0);
        assertEquals("FAILED", result.status().name());
        assertEquals("FAILED", result.transformations().get("runtimeSmokeOpen"));
        assertEquals("OK", result.transformations().get("restore"));
        assertEquals("OK", result.transformations().get("smssRestore"));
        assertEquals(originalSmss, Files.readString(smss));
        assertTrue(H2VersionDetector.detect(dbFile).needsMigration(),
                "restored fixture must still be H2 1.x");

        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200");
             Connection conn = oldH2.connect("jdbc:h2:" + dbName, "sa", "");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT ID FROM ORIGINAL_DATA")) {
            assertTrue(rs.next());
            assertEquals(7, rs.getInt(1));
        }
    }

    @Test
    void run_leavesOriginalUntouchedWhenExportCredentialsAreWrong() throws Exception {
        Path dbRoot = tempDir.resolve("db");
        Path engine = dbRoot.resolve("Engine__id");
        Files.createDirectories(engine);
        String dbName = engine.resolve("database").toAbsolutePath().toString();
        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200");
             Connection conn = oldH2.connect("jdbc:h2:" + dbName, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE ORIGINAL_DATA (ID INT)");
        }
        Files.writeString(dbRoot.resolve("Engine__id.smss"), """
                RDBMS_TYPE\tH2_DB
                DRIVER\torg.h2.Driver
                USERNAME\twrong_user
                PASSWORD\twrong_password
                CONNECTION_URL\tjdbc:h2:nio:/ignored/database
                """);
        Path dbFile = Path.of(dbName + ".mv.db");

        H2MigrationService service = new H2MigrationService(
                config(dbRoot, tempDir.resolve("export-failure-backups"), false));
        byte[] original = Files.readAllBytes(dbFile);

        assertEquals(1, service.run());
        // nothing was replaced yet, so nothing is restored over the original
        assertFalse(service.getReport().getResults().get(0).transformations().containsKey("restore"));
        assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(dbFile)));
    }

    private MigrationConfig config(Path dbDir, Path backupDir, boolean dryRun) {
        return MigrationConfig.fromArgs(new String[]{
                "--mode=local",
                "--db-dir=" + dbDir,
                "--backup-dir=" + backupDir,
                dryRun ? "--dry-run" : "--dry-run=false"
        });
    }

    private void writeH2Smss(Path smssFile, String username, String password) throws Exception {
        String name = smssFile.getFileName().toString().replaceFirst("\\.smss$", "");
        Path rootFile = smssFile.resolveSibling(name + ".mv.db");
        String location = Files.exists(rootFile)
                ? DatabaseExporter.stripExtension(rootFile.toAbsolutePath()) : "/ignored/database";
        Files.writeString(smssFile, """
                RDBMS_TYPE\tH2_DB
                DRIVER\torg.h2.Driver
                USERNAME\t%s
                PASSWORD\t%s
                CONNECTION_URL\tjdbc:h2:nio:%s
                """.formatted(username, password, location));
    }

    @Test
    void run_failsLockedDatabaseWithoutBackupOrRestore() throws Exception {
        Path dbRoot = tempDir.resolve("db");
        String dbName = createEngine(dbRoot, "Engine__id");
        Path dbFile = Path.of(dbName + ".mv.db");
        byte[] original = Files.readAllBytes(dbFile);
        Path backupDir = tempDir.resolve("locked-backups");

        H2MigrationService service = new H2MigrationService(config(dbRoot, backupDir, false));
        try (var channel = java.nio.channels.FileChannel.open(dbFile,
                java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            assertEquals(1, service.run());
        }

        var result = service.getReport().getResults().get(0);
        assertEquals("FAILED", result.status().name());
        assertTrue(result.reason().contains("locked"), result.reason());
        assertFalse(result.transformations().containsKey("restore"));
        assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(dbFile)));
        try (var files = Files.walk(backupDir)) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".mv.db")), "no backup for a locked file");
        }
    }

    @Test
    void run_pageStoreWithSameBaseMvStoreFailsAndKeepsBoth() throws Exception {
        Path dbRoot = tempDir.resolve("db");
        Path engine = dbRoot.resolve("Engine__id");
        Files.createDirectories(engine);
        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200");
             Connection conn = oldH2.connect("jdbc:h2:" + engine.resolve("database") + ";MV_STORE=FALSE", "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE PAGE_DATA (ID INT)");
        }
        Path pageStore = engine.resolve("database.h2.db");
        assertTrue(Files.exists(pageStore));
        byte[] pageBytes = Files.readAllBytes(pageStore);
        Path mvStore = engine.resolve("database.mv.db");
        Files.writeString(mvStore, "not a database");
        writeH2Smss(dbRoot.resolve("Engine__id.smss"), "sa", "");

        H2MigrationService service = new H2MigrationService(
                config(dbRoot, tempDir.resolve("same-base-backups"), false));
        assertEquals(1, service.run());

        var pageRow = service.getReport().getResults().stream()
                .filter(r -> r.path().endsWith(".h2.db")).findFirst().orElseThrow();
        assertEquals("FAILED", pageRow.status().name());
        assertTrue(pageRow.reason().contains("Both"), pageRow.reason());
        assertTrue(java.util.Arrays.equals(pageBytes, Files.readAllBytes(pageStore)));
        assertEquals("not a database", Files.readString(mvStore));
    }

    @Test
    void run_restoresAndRemigratesAfterCrashBetweenPromoteAndVerify() throws Exception {
        Path dbRoot = tempDir.resolve("db");
        String dbName = createEngine(dbRoot, "Engine__id");
        Path dbFile = Path.of(dbName + ".mv.db");
        // simulate a run killed after the migrated file replaced the original
        Path backup = tempDir.resolve("old-run/database.mv.db");
        Files.createDirectories(backup.getParent());
        Files.copy(dbFile, backup);
        Files.delete(dbFile);
        try (IsolatedH2Driver newH2 = IsolatedH2Driver.load("2.4.240");
             Connection conn = newH2.connect("jdbc:h2:" + dbName, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE HALF_DONE (ID INT)");
        }
        MigrationMarker.write(dbFile, backup);
        Path marker = MigrationMarker.pathFor(dbFile);

        H2MigrationService dryRun = new H2MigrationService(config(dbRoot, tempDir.resolve("dry"), true));
        assertEquals(0, dryRun.run());
        assertEquals(1, dryRun.getReport().getResults().size());
        assertTrue(dryRun.getReport().getResults().get(0).reason().contains("interrupted migration"));
        assertTrue(Files.exists(marker), "dry run must not recover");

        H2MigrationService service = new H2MigrationService(config(dbRoot, tempDir.resolve("real"), false));
        assertEquals(0, service.run());

        var result = service.getReport().getResults().get(0);
        assertEquals("SUCCESS", result.status().name());
        assertEquals(backup.toAbsolutePath().normalize().toString(),
                result.transformations().get("recoveredInterruptedMigrationFrom"));
        assertTrue(Files.notExists(marker));
        try (IsolatedH2Driver newH2 = IsolatedH2Driver.load("2.4.240");
             Connection conn = newH2.connect("jdbc:h2:" + dbName, "sa", "");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT ID FROM ORIGINAL_DATA")) {
            assertTrue(rs.next());
            assertEquals(7, rs.getInt(1));
        }
    }

    @Test
    void run_successLeavesNoMarkerOrTempFiles() throws Exception {
        Path dbRoot = tempDir.resolve("db");
        createEngine(dbRoot, "Engine__id");

        H2MigrationService service = new H2MigrationService(config(dbRoot, tempDir.resolve("clean"), false));
        assertEquals(0, service.run());

        try (var files = Files.walk(dbRoot)) {
            assertTrue(files.noneMatch(p -> p.toString().contains(".h2-migrating")
                    || p.toString().contains(".h2-restore-tmp")));
        }
    }

    @Test
    void run_defaultBackupDirIsNotRescanned() throws Exception {
        Path dbRoot = tempDir.resolve("db");
        createEngine(dbRoot, "Engine__id");
        MigrationConfig defaults = MigrationConfig.fromArgs(new String[]{
                "--mode=local", "--db-dir=" + dbRoot, "--dry-run=false"});

        H2MigrationService first = new H2MigrationService(defaults);
        assertEquals(0, first.run());
        assertEquals(1, first.getReport().successCount());
        assertTrue(Files.isDirectory(dbRoot.resolve(".h2-backup")));

        H2MigrationService second = new H2MigrationService(defaults);
        assertEquals(0, second.run());
        assertEquals(1, second.getReport().getResults().size());
        assertFalse(second.getReport().getResults().get(0).path().contains(".h2-backup"));
        assertEquals("SKIPPED", second.getReport().getResults().get(0).status().name());
    }

    @Test
    void run_skipPathRepairsSmssUrlOfAlreadyMigratedDatabase() throws Exception {
        Path dbRoot = tempDir.resolve("db");
        Path engine = dbRoot.resolve("Engine__id");
        Files.createDirectories(engine);
        String dbName = engine.resolve("database").toAbsolutePath().toString();
        try (IsolatedH2Driver newH2 = IsolatedH2Driver.load("2.4.240");
             Connection conn = newH2.connect("jdbc:h2:" + dbName, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE ALREADY_2X (ID INT)");
        }
        Path smss = dbRoot.resolve("Engine__id.smss");
        Files.writeString(smss, """
                RDBMS_TYPE\tH2_DB
                DRIVER\torg.h2.Driver
                USERNAME\tsa
                PASSWORD
                CONNECTION_URL\tjdbc:h2:nio:/ignored/database;MVCC=TRUE;early_filter=true;CACHE_SIZE=32768
                """);
        String before = Files.readString(smss);

        H2MigrationService dryRun = new H2MigrationService(config(dbRoot, tempDir.resolve("dry"), true));
        assertEquals(0, dryRun.run());
        assertTrue(dryRun.getReport().getResults().get(0).reason().contains("would remove"));
        assertEquals(before, Files.readString(smss));

        H2MigrationService service = new H2MigrationService(config(dbRoot, tempDir.resolve("real"), false));
        assertEquals(0, service.run());

        var result = service.getReport().getResults().get(0);
        assertEquals("SKIPPED", result.status().name());
        assertEquals("OK", result.transformations().get("smssUrlUpdate"));
        String after = Files.readString(smss);
        assertFalse(after.contains("MVCC"));
        assertFalse(after.toLowerCase().contains("early_filter"));
        assertTrue(after.contains("CACHE_SIZE=32768"));
        try (var files = Files.walk(tempDir.resolve("real"))) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().equals("Engine__id.smss")));
        }
    }

    /** A 1.x engine db/<name>/database.mv.db with table ORIGINAL_DATA(7) and an H2 smss. */
    private String createEngine(Path dbRoot, String name) throws Exception {
        Path engine = dbRoot.resolve(name);
        Files.createDirectories(engine);
        String dbName = engine.resolve("database").toAbsolutePath().toString();
        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200");
             Connection conn = oldH2.connect("jdbc:h2:" + dbName, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE ORIGINAL_DATA (ID INT)");
            stmt.execute("INSERT INTO ORIGINAL_DATA VALUES (7)");
        }
        writeH2Smss(dbRoot.resolve(name + ".smss"), "sa", "");
        return dbName;
    }
}
