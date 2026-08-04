package org.semoss.h2.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RCloneClientTest {

    @TempDir
    Path tempDir;

    @Test
    void fromConfig_s3Provider_builds() {
        MigrationConfig config = new MigrationConfig(
                MigrationConfig.MigrationMode.CLOUD,
                null,
                tempDir.resolve("backup"),
                false, "sa", "",
                "s3", "us-east-1", "mybucket", "AKID", "SECRET",
                "", "", "", false,
                "", "", "",
                "", "", "", "",
                "",
                "rclone", "", "semoss-db"
        );

        RCloneClient client = RCloneClient.fromConfig(config);
        assertNotNull(client);
    }

    @Test
    void fromConfig_azureProvider_builds() {
        MigrationConfig config = new MigrationConfig(
                MigrationConfig.MigrationMode.CLOUD,
                null,
                tempDir.resolve("backup"),
                false, "sa", "",
                "azure_blob", "", "", "", "",
                "myaccount", "mykey", "", false,
                "", "", "",
                "", "", "", "",
                "",
                "rclone", "", "db-engine"
        );

        RCloneClient client = RCloneClient.fromConfig(config);
        assertNotNull(client);
    }

    @Test
    void fromConfig_gcsProvider_builds() {
        MigrationConfig config = new MigrationConfig(
                MigrationConfig.MigrationMode.CLOUD,
                null,
                tempDir.resolve("backup"),
                false, "sa", "",
                "gcs", "", "", "", "",
                "", "", "", false,
                "/path/to/sa.json", "gcsbucket", "us-central1",
                "", "", "", "",
                "",
                "rclone", "", "semoss-db"
        );

        RCloneClient client = RCloneClient.fromConfig(config);
        assertNotNull(client);
    }

    @Test
    void fromConfig_minioProvider_builds() {
        MigrationConfig config = new MigrationConfig(
                MigrationConfig.MigrationMode.CLOUD,
                null,
                tempDir.resolve("backup"),
                false, "sa", "",
                "minio", "", "", "", "",
                "", "", "", false,
                "", "", "",
                "http://localhost:9000", "us-east-1", "minio-key", "minio-secret",
                "minio-bucket",
                "rclone", "", "semoss-db"
        );

        RCloneClient client = RCloneClient.fromConfig(config);
        assertNotNull(client);
    }

    @Test
    void fromConfig_localFileSystem_builds() {
        MigrationConfig config = new MigrationConfig(
                MigrationConfig.MigrationMode.CLOUD,
                tempDir,
                tempDir.resolve("backup"),
                false, "sa", "",
                "local_file_system", "", "", "", "",
                "", "", "", false,
                "", "", "",
                "", "", "", "",
                "",
                "rclone", "", "semoss-db"
        );

        RCloneClient client = RCloneClient.fromConfig(config);
        assertNotNull(client);
    }

    @Test
    void fromConfig_unsupportedProvider_throws() {
        MigrationConfig config = new MigrationConfig(
                MigrationConfig.MigrationMode.CLOUD,
                null,
                tempDir.resolve("backup"),
                false, "sa", "",
                "dropbox", "", "", "", "",
                "", "", "", false,
                "", "", "",
                "", "", "", "",
                "",
                "rclone", "", ""
        );

        assertThrows(IllegalArgumentException.class, () -> RCloneClient.fromConfig(config));
    }
}
