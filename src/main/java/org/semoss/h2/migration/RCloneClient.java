package org.semoss.h2.migration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Wraps the RClone CLI to list, download, and upload H2 database files
 * from cloud storage providers (S3, Azure Blob, GCS, MinIO, local_file_system).
 * <p>
 * Generates ephemeral rclone configs inline via environment variables,
 * matching the Semoss convention.
 */
public final class RCloneClient {

    private static final Logger LOGGER = LogManager.getLogger(RCloneClient.class);
    private static final long TIMEOUT_SECONDS = 600;

    private final String rclonePath;
    private final String additionalParams;
    private final String backendType;
    private final List<String> configFlags;

    private RCloneClient(String rclonePath, String additionalParams,
                         String backendType, List<String> configFlags) {
        this.rclonePath = rclonePath;
        this.additionalParams = additionalParams;
        this.backendType = backendType;
        this.configFlags = configFlags;
    }

    /**
     * Build an RCloneClient from MigrationConfig, selecting the correct
     * provider config flags.
     */
    public static RCloneClient fromConfig(MigrationConfig config) {
        String provider = config.storageProvider().toLowerCase();
        String backendType;
        List<String> flags = new ArrayList<>();

        switch (provider) {
            case "s3", "amazon_s3" -> {
                backendType = "s3";
                flags.addAll(List.of(
                        "--s3-provider=AWS",
                        "--s3-region=" + config.s3Region(),
                        "--s3-access-key-id=" + config.s3AccessKey(),
                        "--s3-secret-access-key=" + config.s3SecretKey()
                ));
            }
            case "azure_blob", "azure" -> {
                backendType = "azureblob";
                if (config.azUseMsi()) {
                    flags.add("--azureblob-use-msi=true");
                } else if (!config.azConnString().isEmpty()) {
                    // Connection string takes precedence
                } else {
                    flags.addAll(List.of(
                            "--azureblob-account=" + config.azAccountName(),
                            "--azureblob-key=" + config.azPrimaryKey()
                    ));
                }
            }
            case "gcs", "google_cloud_storage" -> {
                backendType = "gcs";
                flags.addAll(List.of(
                        "--gcs-service-account-file=" + config.gcsServiceAccountFile(),
                        "--gcs-location=" + config.gcsRegion()
                ));
            }
            case "minio" -> {
                backendType = "s3";
                flags.addAll(List.of(
                        "--s3-provider=Minio",
                        "--s3-endpoint=" + config.minioEndpoint(),
                        "--s3-region=" + config.minioRegion(),
                        "--s3-access-key-id=" + config.minioAccessKey(),
                        "--s3-secret-access-key=" + config.minioSecretKey()
                ));
            }
            case "local_file_system" -> {
                backendType = "local";
            }
            default -> throw new IllegalArgumentException("Unsupported storage provider: " + provider);
        }

        return new RCloneClient(
                config.rclonePath(),
                config.additionalRcloneParams(),
                backendType,
                flags
        );
    }

    /**
     * List files matching a pattern in a remote path.
     *
     * @param remotePath the remote bucket/container path (e.g. "mybucket/semoss-db")
     * @param include    glob pattern for filtering (e.g. "*.mv.db")
     * @return list of relative file paths found
     */
    public List<String> listFiles(String remotePath, String include) throws IOException, InterruptedException {
        List<String> cmd = buildCommand("lsf", resolveRemote(remotePath));
        cmd.add("--recursive");
        cmd.add("--files-only");
        if (include != null && !include.isEmpty()) {
            cmd.add("--include=" + include);
        }

        ProcessResult result = execute(cmd);
        if (result.exitCode != 0) {
            throw new IOException("rclone lsf failed (exit " + result.exitCode + "): " + result.stderr);
        }

        return result.stdout.lines()
                .filter(l -> !l.isBlank())
                .toList();
    }

    /**
     * Download a remote file to a local path.
     *
     * @param remotePath remote file path (e.g. "mybucket/semoss-db/engine/db.mv.db")
     * @param localDest  local destination file
     */
    public void download(String remotePath, Path localDest) throws IOException, InterruptedException {
        List<String> cmd = buildCommand("copyto",
                resolveRemote(remotePath),
                localDest.toAbsolutePath().toString());

        ProcessResult result = execute(cmd);
        if (result.exitCode != 0) {
            throw new IOException("rclone copyto (download) failed: " + result.stderr);
        }
        LOGGER.info("Downloaded " + remotePath + " -> " + localDest);
    }

    /**
     * Upload a local file to a remote path.
     *
     * @param localFile   local file to upload
     * @param remotePath  remote destination path
     */
    public void upload(Path localFile, String remotePath) throws IOException, InterruptedException {
        List<String> cmd = buildCommand("copyto",
                localFile.toAbsolutePath().toString(),
                resolveRemote(remotePath));

        ProcessResult result = execute(cmd);
        if (result.exitCode != 0) {
            throw new IOException("rclone copyto (upload) failed: " + result.stderr);
        }
        LOGGER.info("Uploaded " + localFile + " -> " + remotePath);
    }

    private String resolveRemote(String path) {
        // For local backend, use paths directly
        if ("local".equals(backendType)) {
            return path;
        }
        return ":" + backendType + ":" + path;
    }

    private List<String> buildCommand(String subcommand, String... pathArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(rclonePath);
        cmd.add(subcommand);
        cmd.addAll(configFlags);

        for (String arg : pathArgs) {
            cmd.add(arg);
        }

        if (additionalParams != null && !additionalParams.isBlank()) {
            for (String param : additionalParams.split("\\s+")) {
                if (!param.isBlank()) cmd.add(param);
            }
        }

        return cmd;
    }

    private ProcessResult execute(List<String> cmd) throws IOException, InterruptedException {
        LOGGER.debug("Executing: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process proc = pb.start();

        String stdout;
        String stderr;
        try (BufferedReader outReader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
             BufferedReader errReader = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
            stdout = String.join("\n", outReader.lines().toList());
            stderr = String.join("\n", errReader.lines().toList());
        }

        boolean finished = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            throw new IOException("rclone timed out after " + TIMEOUT_SECONDS + "s");
        }

        int exitCode = proc.exitValue();
        if (exitCode != 0) {
            LOGGER.warn("rclone exit " + exitCode + ": " + stderr);
        }
        return new ProcessResult(exitCode, stdout, stderr);
    }

    record ProcessResult(int exitCode, String stdout, String stderr) {}
}
