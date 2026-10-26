package org.semoss.h2.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GcloudStorageClientTest {

    @TempDir
    Path tempDir;

    private static final class CapturingExecutor implements GcloudStorageClient.ProcessExecutor {
        List<String> command = List.of();
        Map<String, String> environment = Map.of();
        int exitCode;
        String stdout = "[]";
        String stderr = "";

        @Override
        public GcloudStorageClient.ProcessResult execute(
                List<String> command, Map<String, String> environment, Duration timeout) {
            this.command = new ArrayList<>(command);
            this.environment = new LinkedHashMap<>(environment);
            return new GcloudStorageClient.ProcessResult(exitCode, stdout, stderr);
        }
    }

    @Test
    void serviceAccountFileIsPassedOnlyThroughChildEnvironment() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        String credentialFile = "/var/run/secrets/gcp/service-account.json";
        GcloudStorageClient client = GcloudStorageClient.fromConfig(
                config("--gcs-service-account-file=" + credentialFile), executor, Map.of());

        client.listFiles("bucket/semoss-db", "*.mv.db");

        assertEquals("GCP_GCLOUD_SERVICE_ACCOUNT_FILE", client.authenticationMode());
        assertEquals(credentialFile,
                executor.environment.get("CLOUDSDK_AUTH_CREDENTIAL_FILE_OVERRIDE"));
        assertFalse(String.join(" ", executor.command).contains(credentialFile));
    }

    @Test
    void googleApplicationCredentialsIsMappedToGcloudCredentialOverride() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        GcloudStorageClient client = GcloudStorageClient.fromConfig(config(), executor,
                Map.of("GOOGLE_APPLICATION_CREDENTIALS", "/secrets/adc.json"));

        client.listFiles("bucket/semoss-db", "*.mv.db");

        assertEquals("GCP_GCLOUD_APPLICATION_CREDENTIALS", client.authenticationMode());
        assertEquals("/secrets/adc.json",
                executor.environment.get("CLOUDSDK_AUTH_CREDENTIAL_FILE_OVERRIDE"));
    }

    @Test
    void listFilesParsesGcloudJsonAndReturnsPathsRelativeToSearchRoot() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        executor.stdout = """
                [
                  {"metadata":{"name":"semoss-db/engine/audit_log_database.mv.db"}},
                  {"metadata":{"name":"semoss-db/engine/database.mv.db"}}
                ]
                """;
        GcloudStorageClient client = GcloudStorageClient.fromConfig(config(), executor, Map.of());

        List<String> files = client.listFiles("bucket/semoss-db", "*.mv.db");

        assertEquals(List.of("engine/audit_log_database.mv.db", "engine/database.mv.db"), files);
        assertTrue(executor.command.contains("gs://bucket/semoss-db/**/*.mv.db"));
        assertTrue(executor.command.contains("--json"));
    }

    @Test
    void largeSuccessfulListingIsNotTruncatedByErrorSanitization() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < 200; i++) {
            if (i > 0) json.append(',');
            json.append("{\"metadata\":{\"name\":\"semoss-project/project-")
                    .append(i).append("/insights_database.mv.db\"}}");
        }
        executor.stdout = json.append(']').toString();
        GcloudStorageClient client = GcloudStorageClient.fromConfig(config(), executor, Map.of());

        List<String> files = client.listFiles("bucket/semoss-project", "*.mv.db");

        assertEquals(200, files.size());
        assertTrue(executor.stdout.length() > 4096);
    }

    @Test
    void statParsesHashesAndGenerationVersion() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        executor.stdout = """
                {
                  "size": 139264,
                  "update_time": "2026-08-04T15:53:40+0000",
                  "md5_hash": "md5-value",
                  "crc32c_hash": "crc-value",
                  "generation": "12345",
                  "metageneration": 2
                }
                """;
        GcloudStorageClient client = GcloudStorageClient.fromConfig(config(), executor, Map.of());

        CloudObjectMetadata metadata = client.stat("bucket/semoss-db/engine/database.mv.db");

        assertEquals(139264L, metadata.size());
        assertEquals("12345:2", metadata.version());
        assertEquals("md5-value", metadata.hashes().get("MD5"));
        assertTrue(metadata.sameSourceVersion(new CloudObjectMetadata(
                139264L, "2026-08-04T15:53:40+0000",
                Map.of("MD5", "md5-value"), "12345:2")));
    }

    @Test
    void uploadUsesGcloudStorageCp() throws Exception {
        Path source = tempDir.resolve("candidate.mv.db");
        Files.writeString(source, "fixture");
        CapturingExecutor executor = new CapturingExecutor();
        GcloudStorageClient client = GcloudStorageClient.fromConfig(config(), executor, Map.of());

        client.upload(source, "bucket/_semoss-h2-migration/run/candidate/database.mv.db");

        assertEquals("gcloud", executor.command.get(0));
        assertEquals(List.of("storage", "cp"), executor.command.subList(1, 3));
        assertTrue(executor.command.contains(source.toAbsolutePath().toString()));
        assertTrue(executor.command.contains(
                "gs://bucket/_semoss-h2-migration/run/candidate/database.mv.db"));
    }

    @Test
    void noMatchListingReturnsEmptyList() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        executor.exitCode = 1;
        executor.stderr = "ERROR: One or more URLs matched no objects.";
        GcloudStorageClient client = GcloudStorageClient.fromConfig(config(), executor, Map.of());

        assertTrue(client.listFiles("bucket/semoss-db", "*.mv.db").isEmpty());
    }

    @Test
    void htmlProviderErrorsAreCondensed() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.exitCode = 1;
        executor.stderr = "<!DOCTYPE html><html><head><title>Noncompliant action</title>"
                + "</head><body>SECRET CORPORATE PAGE</body></html>";
        GcloudStorageClient client = GcloudStorageClient.fromConfig(config(), executor, Map.of());

        IOException error = assertThrows(IOException.class,
                () -> client.stat("bucket/semoss-db/engine/database.mv.db"));

        assertTrue(error.getMessage().contains("Noncompliant action"));
        assertFalse(error.getMessage().contains("SECRET CORPORATE PAGE"));
    }

    @Test
    void injectedJsonRequiresMountedFileForGcloudBackend() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> GcloudStorageClient.fromConfig(
                        config("--gcs-service-account-json={secret}"),
                        new CapturingExecutor(), Map.of()));
        assertTrue(error.getMessage().contains("GCS_SERVICE_ACCOUNT_FILE"));
    }

    @Test
    void factorySelectsGcloudOnlyWhenRequested() {
        assertInstanceOf(GcloudStorageClient.class,
                CloudObjectStoreFactory.fromConfig(config()));
        assertInstanceOf(RCloneClient.class,
                CloudObjectStoreFactory.fromConfig(config("--gcs-transfer-backend=rclone")));
        assertThrows(IllegalArgumentException.class,
                () -> CloudObjectStoreFactory.fromConfig(
                        config("--gcs-transfer-backend=unknown")));
    }

    private MigrationConfig config(String... options) {
        List<String> args = new ArrayList<>(List.of(
                "--mode=cloud", "--dry-run",
                "--semoss-storage-provider=gcs",
                "--gcs-bucket=bucket",
                "--gcs-transfer-backend=gcloud",
                "--search-path=semoss-db"));
        args.addAll(List.of(options));
        return MigrationConfig.fromArgs(args.toArray(String[]::new));
    }
}
