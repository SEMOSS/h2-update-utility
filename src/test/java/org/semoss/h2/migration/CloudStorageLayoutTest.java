package org.semoss.h2.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CloudStorageLayoutTest {

    @TempDir
    Path tempDir;

    @Test
    void bucketEngineRootGroupsEachEngineAndPairsSmssRoot() {
        CloudStorageLayout layout = CloudStorageLayout.fromConfig(config("s3", "semoss-db"));
        var scopes = layout.groupDatabases(List.of(
                "engine-a/database.mv.db",
                "engine-a/audit_log_database.mv.db",
                "engine-b/database.mv.db"), tempDir.resolve("stage"));

        assertEquals(2, scopes.size());
        var first = scopes.get(0);
        assertEquals(CloudStorageLayout.ScopeType.ENGINE, first.key().type());
        assertEquals("engine-a", first.key().id());
        assertEquals("bucket/semoss-db/engine-a", first.sourceRoot());
        assertEquals("bucket/semoss-db/engine-a-smss", first.smssRoot());
        assertEquals(2, first.databases().size());
    }

    @Test
    void projectScopePreservesSavedFramePath() {
        CloudStorageLayout layout = CloudStorageLayout.fromConfig(
                config("gcs", "semoss-project/project-a"));
        var scope = layout.groupDatabases(List.of(
                "app_root/version/insight/H2_Store_ABC.mv.db"), tempDir).get(0);

        assertEquals(CloudStorageLayout.ScopeType.PROJECT, scope.key().type());
        assertEquals("project-a", scope.key().id());
        assertTrue(scope.databases().get(0).localPath().toString().contains("semoss-project"));
    }

    @Test
    void azureUsesContainerPerScopeAndSeparateSmssContainer() {
        CloudStorageLayout layout = CloudStorageLayout.fromConfig(
                config("azure", "db-engine-a"));
        var scope = layout.groupDatabases(List.of("database.mv.db"), tempDir).get(0);

        assertEquals("db-engine-a", scope.sourceRoot());
        assertEquals("db-engine-a-smss", scope.smssRoot());
        assertTrue(layout.backupPath("run-1", scope, "database.mv.db")
                .startsWith("semoss-h2-migration/run-1/backup/db-engine-a/"));
    }

    @Test
    void rejectsUnsafeOrUnrecognizedSearchPaths() {
        assertThrows(IllegalArgumentException.class,
                () -> CloudStorageLayout.fromConfig(config("s3", "../semoss-db")));
        assertThrows(IllegalArgumentException.class,
                () -> CloudStorageLayout.fromConfig(config("s3", "other")));
        assertThrows(IllegalArgumentException.class,
                () -> CloudStorageLayout.fromConfig(config("azure", "semoss-db")));
    }

    private MigrationConfig config(String provider, String searchPath) {
        return new MigrationConfig(
                MigrationConfig.MigrationMode.CLOUD,
                tempDir, tempDir.resolve("backup"), true, "sa", "",
                provider, "us-east-1", "bucket", "", "",
                "account", "key", "", false,
                "/secrets/gcp.json", "bucket", "us-east1",
                "http://localhost:9000", "us-east-1", "minio", "secret", "bucket",
                "rclone", "", searchPath);
    }
}
