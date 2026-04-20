package org.semoss.h2.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CloudDatabaseDiscoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void constructor_setsStagingDir() {
        Path staging = tempDir.resolve("staging");
        MigrationConfig config = buildCloudConfig("s3");
        RCloneClient client = RCloneClient.fromConfig(config);

        CloudDatabaseDiscovery discovery = new CloudDatabaseDiscovery(client, staging);
        assertEquals(staging, discovery.getStagingDir());
    }

    private MigrationConfig buildCloudConfig(String provider) {
        return new MigrationConfig(
                MigrationConfig.MigrationMode.CLOUD,
                null,
                tempDir.resolve("backup"),
                false, "sa", "",
                provider,
                provider.equals("s3") ? "us-east-1" : "",
                provider.equals("s3") ? "bucket" : "",
                provider.equals("s3") ? "key" : "",
                provider.equals("s3") ? "secret" : "",
                provider.equals("azure_blob") ? "account" : "",
                provider.equals("azure_blob") ? "key" : "",
                "", false, false,
                provider.equals("gcs") ? "/sa.json" : "",
                provider.equals("gcs") ? "gcsbucket" : "",
                provider.equals("gcs") ? "us-central1" : "",
                provider.equals("minio") ? "http://localhost:9000" : "",
                provider.equals("minio") ? "us-east-1" : "",
                provider.equals("minio") ? "mkey" : "",
                provider.equals("minio") ? "msecret" : "",
                provider.equals("minio") ? "minio-bucket" : "",
                "rclone", "", ""
        );
    }
}
