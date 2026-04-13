package org.semoss.h2.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Discovers H2 database files in cloud storage via RClone,
 * downloads them to a local staging directory for migration,
 * and uploads migrated files back.
 * <p>
 * Semoss cloud storage conventions:
 * <ul>
 *   <li>S3/GCS/MinIO: {@code {bucket}/semoss-db/{engineId}/*.mv.db}</li>
 *   <li>Azure: Container {@code db-{engineId}} → {@code *.mv.db}</li>
 *   <li>Projects: {@code {bucket}/semoss-project/{projectId}/insights_database.mv.db}</li>
 *   <li>SMSS files: {@code {PREFIX}/{engineId}-smss/}</li>
 * </ul>
 */
public final class CloudDatabaseDiscovery {

    private static final Logger LOGGER = LogManager.getLogger(CloudDatabaseDiscovery.class);

    private final RCloneClient rclone;
    private final MigrationConfig config;
    private final Path stagingDir;

    /**
     * @param rclone     configured RClone client
     * @param config     migration config (for bucket/container info)
     * @param stagingDir local directory for downloaded cloud files
     */
    public CloudDatabaseDiscovery(RCloneClient rclone, MigrationConfig config, Path stagingDir) {
        this.rclone = rclone;
        this.config = config;
        this.stagingDir = stagingDir;
    }

    /**
     * List all .mv.db files in the cloud storage bucket/container.
     *
     * @param remotePath the bucket or container path to search
     * @return list of remote paths (relative to the remotePath root)
     */
    public List<String> discoverRemoteDatabases(String remotePath) throws IOException, InterruptedException {
        LOGGER.info("Discovering H2 databases in cloud: " + remotePath);
        List<String> files = rclone.listFiles(remotePath, "*.mv.db");
        LOGGER.info("Found " + files.size() + " .mv.db files in cloud storage");
        return files;
    }

    /**
     * Download a remote database file along with its companion SMSS file
     * to the local staging directory.
     *
     * @param remotePath base path (bucket/container)
     * @param relativeDbPath relative path of the .mv.db file
     * @return local path to the downloaded file
     */
    public Path downloadDatabase(String remotePath, String relativeDbPath)
            throws IOException, InterruptedException {
        Path localFile = stagingDir.resolve(relativeDbPath);
        Files.createDirectories(localFile.getParent());

        String fullRemote = remotePath + "/" + relativeDbPath;
        rclone.download(fullRemote, localFile);

        // Also try to download companion SMSS file
        downloadCompanionSmss(remotePath, relativeDbPath);

        return localFile;
    }

    /**
     * Upload a migrated database file back to cloud storage.
     *
     * @param remotePath base path (bucket/container)
     * @param relativeDbPath relative path of the .mv.db file
     * @param localFile the local migrated file
     */
    public void uploadDatabase(String remotePath, String relativeDbPath, Path localFile)
            throws IOException, InterruptedException {
        String fullRemote = remotePath + "/" + relativeDbPath;
        rclone.upload(localFile, fullRemote);
    }

    /**
     * Try to download the companion SMSS file for credential reading.
     * SMSS files live in a sibling directory: {engineId}-smss/ or same dir.
     */
    private void downloadCompanionSmss(String remotePath, String relativeDbPath)
            throws IOException, InterruptedException {
        // Try to find smss in same directory
        String dir = relativeDbPath.contains("/")
                ? relativeDbPath.substring(0, relativeDbPath.lastIndexOf('/'))
                : "";
        String smssDir = dir.isEmpty() ? "" : dir;

        try {
            List<String> smssFiles = rclone.listFiles(
                    remotePath + (smssDir.isEmpty() ? "" : "/" + smssDir),
                    "*.smss"
            );
            for (String smss : smssFiles) {
                String fullSmssRemote = remotePath + "/" +
                        (smssDir.isEmpty() ? smss : smssDir + "/" + smss);
                Path localSmss = stagingDir.resolve(
                        smssDir.isEmpty() ? smss : smssDir + "/" + smss);
                Files.createDirectories(localSmss.getParent());
                rclone.download(fullSmssRemote, localSmss);
            }
        } catch (IOException e) {
            LOGGER.debug("No companion SMSS found for " + relativeDbPath + ": " + e.getMessage());
        }
    }

    public Path getStagingDir() {
        return stagingDir;
    }
}
