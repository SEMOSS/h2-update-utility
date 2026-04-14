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
     * Download only the remote database file to the local staging directory.
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
     * Download the companion SMSS file for credential reading.
     * Call this only when migration is actually needed.
     *
     * @param remotePath base path (bucket/container)
     * @param relativeDbPath relative path of the .mv.db file
     */
    public void downloadCompanionSmss(String remotePath, String relativeDbPath)
            throws IOException, InterruptedException {
        // The DB file is typically at: engineAlias__uuid/database.mv.db
        // The SMSS file lives as a sibling of the engine folder: engineAlias__uuid.smss
        // So we need to search the parent of the DB's directory (i.e. the directory
        // containing the engine folder) AND the DB's own directory.

        String dbDir = relativeDbPath.contains("/")
                ? relativeDbPath.substring(0, relativeDbPath.lastIndexOf('/'))
                : "";

        // Search 1: Parent directory of the engine folder (where .smss siblings live)
        // e.g. for "EngineAlias__uuid/database.mv.db", search at the root for *.smss
        if (!dbDir.isEmpty()) {
            String parentDir = dbDir.contains("/")
                    ? dbDir.substring(0, dbDir.lastIndexOf('/'))
                    : "";
            downloadSmssFromDir(remotePath, parentDir);
        }

        // Search 2: Same directory as the DB file (fallback for flat layouts)
        downloadSmssFromDir(remotePath, dbDir);
    }

    private void downloadSmssFromDir(String remotePath, String dir)
            throws InterruptedException {
        try {
            String searchPath = remotePath + (dir.isEmpty() ? "" : "/" + dir);
            List<String> smssFiles = rclone.listFiles(searchPath, "*.smss");
            for (String smss : smssFiles) {
                String fullSmssRemote = remotePath + "/" +
                        (dir.isEmpty() ? smss : dir + "/" + smss);
                Path localSmss = stagingDir.resolve(
                        dir.isEmpty() ? smss : dir + "/" + smss);
                Files.createDirectories(localSmss.getParent());
                rclone.download(fullSmssRemote, localSmss);
            }
        } catch (IOException e) {
            LOGGER.debug("No .smss files found in " + dir + ": " + e.getMessage());
        }
    }

    public Path getStagingDir() {
        return stagingDir;
    }
}
