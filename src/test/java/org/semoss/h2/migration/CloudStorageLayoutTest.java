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
    void workPathsKeepEngineAndProjectWithSameIdApart() {
        var engine = CloudStorageLayout.fromConfig(config("gcs", "semoss-db"))
                .groupDatabases(List.of("same-id/database.mv.db"), tempDir).get(0);
        var project = CloudStorageLayout.fromConfig(config("gcs", "semoss-project"))
                .groupDatabases(List.of("same-id/insights_database.mv.db"), tempDir).get(0);
        var dbLayout = CloudStorageLayout.fromConfig(config("gcs", "semoss-db"));
        var projectLayout = CloudStorageLayout.fromConfig(config("gcs", "semoss-project"));

        assertEquals("bucket/_semoss-h2-migration/run-1/backup/semoss-db/same-id/database.mv.db",
                dbLayout.backupPath("run-1", engine, "database.mv.db"));
        assertEquals("bucket/_semoss-h2-migration/run-1/backup/semoss-db/same-id-smss/same-id.smss",
                dbLayout.backupSmssPath("run-1", engine, "same-id.smss"));
        assertEquals("bucket/_semoss-h2-migration/run-1/backup/semoss-project/same-id/insights_database.mv.db",
                projectLayout.backupPath("run-1", project, "insights_database.mv.db"));
        assertEquals("bucket/_semoss-h2-migration/run-1/candidate/semoss-project/same-id/insights_database.mv.db",
                projectLayout.candidatePath("run-1", project, "insights_database.mv.db"));
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
                () -> CloudStorageLayout.fromConfig(config("azure", "other")));
    }

    @Test
    void azureSemossRootCoversEveryContainerOfItsType() {
        CloudStorageLayout engines = CloudStorageLayout.fromConfig(config("azure", "semoss-db"));
        var scopes = engines.groupDatabases(List.of(
                "db-a/database.mv.db",
                "db-a/audit_log_database.mv.db",
                "db-a-smss/x.mv.db",
                "project-p/insights_database.mv.db",
                "semoss-h2-migration/run/backup/db-a/database.mv.db",
                "db-b/database.mv.db"), tempDir);

        assertEquals("", engines.discoveryRoot());
        assertEquals("/db-*/**.mv.db", engines.listInclude("*.mv.db"));
        assertEquals(List.of("a", "b"), scopes.stream().map(scope -> scope.key().id()).toList());
        assertEquals("db-a", scopes.get(0).sourceRoot());
        assertEquals("db-a-smss", scopes.get(0).smssRoot());
        assertEquals("db-a/database.mv.db", scopes.get(0).databases().get(0).remotePath());

        CloudStorageLayout projects = CloudStorageLayout.fromConfig(config("azure", "semoss-project"));
        assertEquals("p", projects.scopeIdOf("project-p/app_root/version/i/i.mosfet"));
        assertEquals(null, projects.scopeIdOf("project-p-smss/App__p.smss"));
        assertEquals(null, projects.scopeIdOf("db-a/database.mv.db"));
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
