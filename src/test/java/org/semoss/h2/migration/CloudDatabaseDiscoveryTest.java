package org.semoss.h2.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CloudDatabaseDiscoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void constructor_setsStagingDir() {
        Path staging = tempDir.resolve("staging");
        MigrationConfig config = buildCloudConfig("s3");
        RCloneClient client = RCloneClient.fromConfig(config);

        CloudDatabaseDiscovery discovery = new CloudDatabaseDiscovery(client, config, staging);
        assertEquals(staging, discovery.getStagingDir());
    }

    @Test
    void constructor_acceptsAllProviders() {
        for (String provider : new String[]{"s3", "azure_blob", "gcs", "minio", "local_file_system"}) {
            MigrationConfig config = buildCloudConfig(provider);
            RCloneClient client = RCloneClient.fromConfig(config);

            CloudDatabaseDiscovery discovery = new CloudDatabaseDiscovery(
                    client, config, tempDir.resolve("staging-" + provider));
            assertNotNull(discovery);
        }
    }

    private MigrationConfig buildCloudConfig(String provider) {
        return new MigrationConfig(
                MigrationConfig.MigrationMode.CLOUD,
                null,
                tempDir.resolve("backup"),
                false, false, "sa", "",
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
                "rclone", ""
        );
    }
}
