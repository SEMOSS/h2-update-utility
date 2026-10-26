package org.semoss.h2.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RCloneAuthenticationTest {

    private static final class CapturingExecutor implements RCloneClient.ProcessExecutor {
        List<String> command = List.of();
        Map<String, String> environment = Map.of();
        int exitCode;
        String stdout = "engine/database.mv.db\n";
        String stderr = "";

        @Override
        public RCloneClient.ProcessResult execute(List<String> command,
                                                  Map<String, String> environment,
                                                  Duration timeout) {
            this.command = new ArrayList<>(command);
            this.environment = new LinkedHashMap<>(environment);
            return new RCloneClient.ProcessResult(exitCode, stdout, stderr);
        }
    }

    @Test
    void s3WithoutStaticKeysUsesAwsDefaultCredentialChain() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        RCloneClient client = RCloneClient.fromConfig(config("s3"), executor);

        client.listFiles("bucket/semoss-db", "*.mv.db");

        assertEquals("AWS_DEFAULT_CHAIN", client.authenticationMode());
        assertEquals("true", executor.environment.get("RCLONE_CONFIG_SEMOSSH2_ENV_AUTH"));
        assertTrue(executor.command.stream().noneMatch(arg -> arg.contains("access-key")));
    }

    @Test
    void temporaryAwsCredentialsAreOnlyInChildEnvironment() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        MigrationConfig config = config("s3",
                "--s3-access-key=AKID-SECRET",
                "--s3-secret-key=AWS-SECRET",
                "--s3-session-token=SESSION-SECRET");
        RCloneClient client = RCloneClient.fromConfig(config, executor);

        client.listFiles("bucket/semoss-db", "*.mv.db");

        assertEquals("AWS_STATIC_TEMPORARY", client.authenticationMode());
        String command = String.join(" ", executor.command);
        assertFalse(command.contains("AKID-SECRET"));
        assertFalse(command.contains("AWS-SECRET"));
        assertFalse(command.contains("SESSION-SECRET"));
        assertEquals("SESSION-SECRET",
                executor.environment.get("RCLONE_CONFIG_SEMOSSH2_SESSION_TOKEN"));
    }

    @Test
    void gcpServiceAccountFileUsesMountedPath() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        RCloneClient client = RCloneClient.fromConfig(config("gcs",
                "--gcs-service-account-file=/var/run/secrets/gcp/key.json"), executor);

        client.listFiles("bucket/semoss-db", "*.mv.db");

        assertEquals("GCP_SERVICE_ACCOUNT_FILE", client.authenticationMode());
        assertEquals("google cloud storage",
                executor.environment.get("RCLONE_CONFIG_SEMOSSH2_TYPE"));
        assertEquals("/var/run/secrets/gcp/key.json",
                executor.environment.get("RCLONE_CONFIG_SEMOSSH2_SERVICE_ACCOUNT_FILE"));
    }

    @Test
    void gcpWithoutExplicitCredentialsUsesDefaultChain() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        RCloneClient client = RCloneClient.fromConfig(config("gcs"), executor);

        client.listFiles("bucket/semoss-db", "*.mv.db");

        assertEquals("GCP_DEFAULT_CHAIN", client.authenticationMode());
        assertEquals("true", executor.environment.get("RCLONE_CONFIG_SEMOSSH2_ENV_AUTH"));
    }

    @Test
    void azureManagedIdentityUsesEnvironmentAuthenticationOnly() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        RCloneClient client = RCloneClient.fromConfig(
                config("azure", "--az-use-msi"), executor);

        client.listFiles("db-engine", "*.mv.db");

        assertEquals("AZURE_MANAGED_IDENTITY", client.authenticationMode());
        assertEquals("true", executor.environment.get("RCLONE_CONFIG_SEMOSSH2_ENV_AUTH"));
        assertFalse(executor.environment.containsKey("RCLONE_CONFIG_SEMOSSH2_USE_MSI"));
    }

    @Test
    void minioRequiresCompleteEndpointAndCredentials() {
        assertThrows(IllegalArgumentException.class,
                () -> RCloneClient.fromConfig(config("minio", "--minio-secret-key=")));
        assertThrows(IllegalArgumentException.class,
                () -> RCloneClient.fromConfig(config("minio", "--minio-endpoint=")));
    }

    @Test
    void transportOptionsMayUseSeparatedValuesButCannotOverrideRemoteConfig() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        RCloneClient client = RCloneClient.fromConfig(config("s3",
                "--additional-rclone-parameters=--ca-cert /secrets/ca.pem --timeout=2m"), executor);

        client.listFiles("bucket/semoss-db", "*.mv.db");

        assertTrue(executor.command.contains("--ca-cert"));
        assertTrue(executor.command.contains("/secrets/ca.pem"));
        assertThrows(IllegalArgumentException.class,
                () -> RCloneClient.fromConfig(config("s3",
                        "--additional-rclone-parameters=--config /tmp/rclone.conf")));
    }

    @Test
    void timeoutErrorsDoNotExposeInjectedSecrets() {
        RCloneClient.ProcessExecutor timeoutExecutor = (command, environment, timeout) -> {
            assertEquals(Duration.ofMinutes(10), timeout);
            throw new IOException("rclone timed out while using AWS-SECRET");
        };
        RCloneClient client = RCloneClient.fromConfig(config("s3",
                "--s3-access-key=AKID", "--s3-secret-key=AWS-SECRET"), timeoutExecutor);

        IOException error = assertThrows(IOException.class,
                () -> client.listFiles("bucket/semoss-db", "*.mv.db"));
        assertFalse(error.getMessage().contains("AWS-SECRET"));
    }

    @Test
    void azureAccountKeyIsNotPlacedInCommand() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        RCloneClient client = RCloneClient.fromConfig(config("azure",
                "--az-account-name=account",
                "--az-primary-key=AZURE-SECRET"), executor);

        client.listFiles("db-engine", "*.mv.db");

        assertEquals("AZURE_ACCOUNT_KEY", client.authenticationMode());
        assertFalse(String.join(" ", executor.command).contains("AZURE-SECRET"));
        assertEquals("AZURE-SECRET",
                executor.environment.get("RCLONE_CONFIG_SEMOSSH2_KEY"));
    }

    @Test
    void azureConnectionStringIsOnlyInChildEnvironment() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        String connection = "DefaultEndpointsProtocol=https;AccountName=account;"
                + "AccountKey=YWJjZA==;EndpointSuffix=core.windows.net";
        RCloneClient client = RCloneClient.fromConfig(config("azure",
                "--az-conn-string=" + connection), executor);

        client.listFiles("db-engine", "*.mv.db");

        assertEquals("AZURE_CONNECTION_STRING", client.authenticationMode());
        assertFalse(String.join(" ", executor.command).contains(connection));
        assertEquals(connection,
                executor.environment.get("RCLONE_CONFIG_SEMOSSH2_CONNECTION_STRING"));
    }

    @Test
    void gcpInjectedJsonIsOnlyInChildEnvironment() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        String json = "{\"type\":\"service_account\",\"private_key\":\"SECRET-JSON\"}";
        RCloneClient client = RCloneClient.fromConfig(config("gcs",
                "--gcs-service-account-json=" + json), executor);

        client.listFiles("bucket/semoss-db", "*.mv.db");

        assertEquals("GCP_SERVICE_ACCOUNT_JSON", client.authenticationMode());
        assertFalse(String.join(" ", executor.command).contains("SECRET-JSON"));
        assertEquals(json,
                executor.environment.get("RCLONE_CONFIG_SEMOSSH2_SERVICE_ACCOUNT_CREDENTIALS"));
    }

    @Test
    void secretValuesAreRedactedFromRcloneErrors() {
        CapturingExecutor executor = new CapturingExecutor();
        executor.exitCode = 1;
        executor.stderr = "authentication failed for AWS-SECRET";
        RCloneClient client = RCloneClient.fromConfig(config("s3",
                "--s3-access-key=AKID", "--s3-secret-key=AWS-SECRET"), executor);

        IOException error = assertThrows(IOException.class,
                () -> client.listFiles("bucket/semoss-db", "*.mv.db"));
        assertFalse(error.getMessage().contains("AWS-SECRET"));
        assertTrue(error.getMessage().contains("<redacted>"));
    }

    @Test
    void migrationConfigToStringRedactsSecrets() {
        MigrationConfig config = config("s3",
                "--s3-access-key=AKID", "--s3-secret-key=AWS-SECRET");
        assertFalse(config.toString().contains("AWS-SECRET"));
        assertFalse(config.toString().contains("AKID"));
        assertTrue(config.toString().contains("<redacted>"));
    }

    @Test
    void rejectsPartialCredentialsAndAuthOverridingRcloneArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> RCloneClient.fromConfig(config("s3", "--s3-access-key=only")));
        assertThrows(IllegalArgumentException.class,
                () -> RCloneClient.fromConfig(config("s3",
                        "--additional-rclone-parameters=--s3-provider=Other")));
        assertThrows(IllegalArgumentException.class,
                () -> RCloneClient.fromConfig(config("azure",
                        "--az-use-msi", "--az-account-name=account", "--az-primary-key=key")));
    }

    private MigrationConfig config(String provider, String... options) {
        List<String> args = new ArrayList<>(List.of(
                "--mode=cloud", "--dry-run",
                "--semoss-storage-provider=" + provider,
                "--search-path=" + ("azure".equals(provider) ? "db-engine" : "semoss-db"),
                "--s3-bucket=bucket", "--s3-region=us-east-1",
                "--gcs-bucket=bucket", "--minio-bucket=bucket",
                "--minio-endpoint=http://localhost:9000",
                "--minio-access-key=minio", "--minio-secret-key=secret"));
        args.addAll(List.of(options));
        return MigrationConfig.fromArgs(args.toArray(String[]::new));
    }
}
