package org.semoss.h2.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end cloud transaction tests using rclone's local filesystem backend. */
class CloudMigrationIT {

    @TempDir
    Path tempDir;

    @Test
    void dryRunDownloadsAndClassifiesWithoutRemoteWrites() throws Exception {
        EngineFixture fixture = createEngineFixture(true);
        String before = CloudDatabaseDiscovery.sha256(fixture.databaseFile());

        H2MigrationService service = new H2MigrationService(config(true, false, "semoss-db"));
        int exitCode = service.run();

        assertEquals(0, exitCode);
        assertEquals(1, service.getReport().skippedCount());
        assertEquals(before, CloudDatabaseDiscovery.sha256(fixture.databaseFile()));
        assertFalse(Files.exists(tempDir.resolve("remote/_semoss-h2-migration")));
        assertTrue(service.getReport().toJson().contains("LOCAL_FILE_SYSTEM"));
        assertTrue(service.getReport().toJson().contains("USER_ENGINE_H2"));
    }

    @Test
    void actualRunBacksUpPublishesAndVerifiesDatabaseAndSmss() throws Exception {
        EngineFixture fixture = createEngineFixture(true);
        String originalDbHash = CloudDatabaseDiscovery.sha256(fixture.databaseFile());
        String originalSmssHash = CloudDatabaseDiscovery.sha256(fixture.smssFile());

        H2MigrationService service = new H2MigrationService(config(false, true, "semoss-db"));
        int exitCode = service.run();

        assertEquals(0, exitCode);
        assertEquals(1, service.getReport().successCount());
        assertEquals(0, service.getReport().failedCount());
        assertFalse(originalDbHash.equals(CloudDatabaseDiscovery.sha256(fixture.databaseFile())));
        assertFalse(originalSmssHash.equals(CloudDatabaseDiscovery.sha256(fixture.smssFile())));
        String smss = Files.readString(fixture.smssFile()).toUpperCase();
        assertFalse(smss.contains("EARLY_FILTER"));
        assertFalse(smss.contains("QUERY_CACHE_SIZE"));

        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("2.4.240");
             Connection connection = h2.connect("jdbc:h2:nio:"
                     + DatabaseExporter.stripExtension(fixture.databaseFile()), "sa", "");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM TEST_DATA")) {
            assertTrue(result.next());
            assertEquals(2, result.getInt(1));
        }

        Path remoteWork = tempDir.resolve("remote/_semoss-h2-migration");
        assertTrue(Files.walk(remoteWork)
                .anyMatch(path -> path.toString().endsWith("database.mv.db")));
        assertTrue(Files.walk(remoteWork)
                .anyMatch(path -> path.toString().endsWith("Engine__engine-id.smss")));
        assertFalse(Files.walk(remoteWork)
                .anyMatch(path -> Files.isRegularFile(path)
                        && path.toString().contains("/candidate/")));
        assertTrue(service.getReport().toJson().contains("\"promotionStatus\": \"VERIFIED\""));
        // finished scope's local backup and export script are removed
        assertFalse(hasLocalBackupFiles());
    }

    @Test
    void actualRunRequiresStoppedSemossAttestation() throws Exception {
        EngineFixture fixture = createEngineFixture(false);
        String before = CloudDatabaseDiscovery.sha256(fixture.databaseFile());

        H2MigrationService service = new H2MigrationService(config(false, false, "semoss-db"));
        int exitCode = service.run();

        assertEquals(1, exitCode);
        assertEquals(before, CloudDatabaseDiscovery.sha256(fixture.databaseFile()));
        assertTrue(service.getReport().toJson().contains("confirm-semoss-stopped"));
    }

    @Test
    void failureAfterPromotionStartsRestoresEntireScope() throws Exception {
        EngineFixture fixture = createEngineFixture(true);
        String originalDb = CloudDatabaseDiscovery.sha256(fixture.databaseFile());
        String originalSmss = CloudDatabaseDiscovery.sha256(fixture.smssFile());
        MigrationConfig config = config(false, true, "semoss-db");
        H2MigrationService service = new H2MigrationService(config);
        CloudStorageLayout layout = CloudStorageLayout.fromConfig(config);
        FaultInjectingLocalStore store = new FaultInjectingLocalStore();

        try (IsolatedH2Driver oldDriver = IsolatedH2Driver.load("1.4.200");
             IsolatedH2Driver newDriver = IsolatedH2Driver.load("2.4.240")) {
            CloudMigrationCoordinator coordinator = new CloudMigrationCoordinator(
                    config, service.getReport(), service, oldDriver, newDriver,
                    layout, store, tempDir.resolve("fault-staging"));
            coordinator.execute();
        }

        assertEquals(1, service.getReport().failedCount());
        assertEquals(originalDb, CloudDatabaseDiscovery.sha256(fixture.databaseFile()));
        assertEquals(originalSmss, CloudDatabaseDiscovery.sha256(fixture.smssFile()));
        assertTrue(service.getReport().toJson().contains("\"rollbackStatus\": \"VERIFIED\""));
        // failed scope keeps its local backup for inspection
        assertTrue(hasLocalBackupFiles());
    }

    private boolean hasLocalBackupFiles() throws Exception {
        Path backup = tempDir.resolve("local-backup");
        if (!Files.exists(backup)) return false;
        try (var paths = Files.walk(backup)) {
            return paths.anyMatch(path -> Files.isRegularFile(path)
                    && !path.getFileName().toString().equals("migration-report.json"));
        }
    }

    @Test
    void projectScopeMigratesInsightsAndPersistedFramesButSkipsLiveCache() throws Exception {
        Path project = tempDir.resolve("remote/semoss-project/project-id");
        Path insights = project.resolve("insights_database.mv.db");
        Path frame = project.resolve("app_root/version/insight/H2_Store_SAVED.mv.db");
        Path cache = project.resolve("InsightCache/session/H2_Store_LIVE.mv.db");
        createSimpleH2(insights, "INSIGHT_DATA");
        createSimpleH2(frame, "FRAME_DATA");
        createSimpleH2(cache, "CACHE_DATA");
        String cacheHash = CloudDatabaseDiscovery.sha256(cache);

        H2MigrationService service = new H2MigrationService(
                config(false, true, "semoss-project/project-id"));
        int exitCode = service.run();

        assertEquals(0, exitCode);
        assertEquals(2, service.getReport().successCount());
        assertEquals(1, service.getReport().skippedCount());
        assertEquals(cacheHash, CloudDatabaseDiscovery.sha256(cache));
        assertTrue(service.getReport().toJson().contains("PROJECT_INSIGHTS_H2"));
        assertTrue(service.getReport().toJson().contains("PERSISTED_INSIGHT_FRAME_H2"));
        assertTrue(service.getReport().toJson().contains("TRANSIENT_INSIGHT_CACHE_H2"));
    }

    @Test
    void unknownProjectDatabaseFailsScopeWithoutRemoteWrites() throws Exception {
        Path unknown = tempDir.resolve("remote/semoss-project/project-id/other.mv.db");
        createSimpleH2(unknown, "UNKNOWN_DATA");
        String before = CloudDatabaseDiscovery.sha256(unknown);

        H2MigrationService service = new H2MigrationService(
                config(false, true, "semoss-project/project-id"));
        int exitCode = service.run();

        assertEquals(1, exitCode);
        assertEquals(before, CloudDatabaseDiscovery.sha256(unknown));
        assertFalse(Files.exists(tempDir.resolve("remote/_semoss-h2-migration")));
        assertTrue(service.getReport().toJson().contains("UNKNOWN_H2"));
    }

    @Test
    void actualRunRejectsAnEmptyRecognizedScope() throws Exception {
        Files.createDirectories(tempDir.resolve("remote/semoss-project"));

        H2MigrationService service = new H2MigrationService(
                config(false, true, "semoss-project"));
        int exitCode = service.run();

        assertEquals(1, exitCode);
        assertTrue(service.getReport().toJson().contains("contains no H2 database objects"));
        assertFalse(Files.exists(tempDir.resolve("remote/_semoss-h2-migration")));
    }

    @Test
    void alreadyMigratedCloudScopeIsAnIdempotentRerun() throws Exception {
        EngineFixture fixture = createEngineFixture(true);
        H2MigrationService first = new H2MigrationService(config(false, true, "semoss-db"));
        assertEquals(0, first.run());
        String migratedHash = CloudDatabaseDiscovery.sha256(fixture.databaseFile());

        H2MigrationService rerun = new H2MigrationService(config(false, true, "semoss-db"));
        int exitCode = rerun.run();

        assertEquals(0, exitCode);
        assertEquals(0, rerun.getReport().successCount());
        assertEquals(1, rerun.getReport().skippedCount());
        assertEquals(migratedHash, CloudDatabaseDiscovery.sha256(fixture.databaseFile()));
        assertTrue(rerun.getReport().toJson().contains("Already H2 2.x"));
    }

    private EngineFixture createEngineFixture(boolean legacyUrlOptions) throws Exception {
        Path remote = tempDir.resolve("remote");
        Path engine = remote.resolve("semoss-db/engine-id");
        Path smssRoot = remote.resolve("semoss-db/engine-id-smss");
        Files.createDirectories(engine);
        Files.createDirectories(smssRoot);
        Path databaseFile = engine.resolve("database.mv.db");
        String databaseBase = DatabaseExporter.stripExtension(databaseFile).toString();
        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("1.4.200");
             Connection connection = h2.connect("jdbc:h2:nio:" + databaseBase, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE TEST_DATA (ID INT PRIMARY KEY, NAME VARCHAR(100))");
            statement.execute("INSERT INTO TEST_DATA VALUES (1, 'one'), (2, 'two')");
        }
        Path smss = smssRoot.resolve("Engine__engine-id.smss");
        String options = legacyUrlOptions ? ";EARLY_FILTER=TRUE;QUERY_CACHE_SIZE=32" : "";
        Files.writeString(smss, """
                ENGINE engine-id
                ENGINE_ALIAS Engine
                RDBMS_TYPE H2_DB
                DRIVER org.h2.Driver
                USERNAME sa
                PASSWORD
                CONNECTION_URL jdbc:h2:nio:/remote/database%s
                """.formatted(options));
        return new EngineFixture(databaseFile, smss);
    }

    private void createSimpleH2(Path databaseFile, String table) throws Exception {
        Files.createDirectories(databaseFile.getParent());
        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("1.4.200");
             Connection connection = h2.connect("jdbc:h2:nio:"
                     + DatabaseExporter.stripExtension(databaseFile), "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (ID INT PRIMARY KEY, VALUE VARCHAR(20))");
            statement.execute("INSERT INTO " + table + " VALUES (1, 'value')");
        }
    }

    private MigrationConfig config(boolean dryRun, boolean confirmed, String searchPath) {
        var args = new java.util.ArrayList<>(java.util.List.of(
                "--mode=cloud",
                "--db-dir=" + tempDir.resolve("remote"),
                "--backup-dir=" + tempDir.resolve("local-backup"),
                "--semoss-storage-provider=local_file_system",
                "--search-path=" + searchPath));
        if (dryRun) args.add("--dry-run");
        if (confirmed) args.add("--confirm-semoss-stopped");
        return MigrationConfig.fromArgs(args.toArray(String[]::new));
    }

    private record EngineFixture(Path databaseFile, Path smssFile) {}

    /** Local object store that fails once while promoting the SMSS after the DB was promoted. */
    private static final class FaultInjectingLocalStore implements CloudObjectStore {
        private boolean promotionFailureInjected;

        @Override
        public String authenticationMode() {
            return "TEST_LOCAL";
        }

        @Override
        public List<String> listFiles(String remotePath, String include) throws java.io.IOException {
            Path root = Path.of(remotePath);
            if (!Files.exists(root)) throw new java.io.IOException("not found");
            String suffix = include != null && include.contains(".smss") ? ".smss" : ".mv.db";
            try (var stream = Files.walk(root)) {
                return stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(suffix))
                        .map(root::relativize)
                        .map(path -> path.toString().replace('\\', '/'))
                        .toList();
            }
        }

        @Override
        public CloudObjectMetadata stat(String remotePath) throws java.io.IOException {
            Path path = Path.of(remotePath);
            return new CloudObjectMetadata(Files.size(path),
                    Files.getLastModifiedTime(path).toString(), Map.of());
        }

        @Override
        public void download(String remotePath, Path localDest) throws java.io.IOException {
            Files.createDirectories(localDest.getParent());
            Files.copy(Path.of(remotePath), localDest, StandardCopyOption.REPLACE_EXISTING);
        }

        @Override
        public void upload(Path localFile, String remotePath) throws java.io.IOException {
            boolean sourceSmssPromotion = remotePath.contains("/semoss-db/engine-id-smss/")
                    && !remotePath.contains("/_semoss-h2-migration/");
            if (sourceSmssPromotion && !promotionFailureInjected) {
                promotionFailureInjected = true;
                throw new java.io.IOException("injected promotion failure");
            }
            Path destination = Path.of(remotePath);
            Files.createDirectories(destination.getParent());
            Files.copy(localFile, destination, StandardCopyOption.REPLACE_EXISTING);
        }

        @Override
        public void delete(String remotePath) throws java.io.IOException {
            Files.deleteIfExists(Path.of(remotePath));
        }
    }
}
