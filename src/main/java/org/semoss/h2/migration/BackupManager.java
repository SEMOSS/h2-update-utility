package org.semoss.h2.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Creates and restores backups of H2 database files before migration.
 * Backups are placed in a timestamped subdirectory under the configured backup root.
 */
public final class BackupManager {

    private static final Logger LOGGER = LogManager.getLogger(BackupManager.class);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final Path backupRoot;

    /**
     * @param backupRoot root directory where backups are stored.
     *                   A timestamped subdirectory is created per migration run.
     */
    public BackupManager(Path backupRoot) {
        this.backupRoot = backupRoot;
    }

    /**
     * Back up a single database file, preserving relative path structure
     * from the given base directory.
     *
     * @param dbFile   the .mv.db or .h2.db file to back up
     * @param baseDir  the root from which relative paths are derived
     * @param runLabel label for this run (used as subdirectory name)
     * @return the path to the backup copy
     * @throws IOException if the copy fails
     */
    public Path backup(Path dbFile, Path baseDir, String runLabel) throws IOException {
        Path relative = baseDir.toAbsolutePath().relativize(dbFile.toAbsolutePath());
        Path dest = backupRoot.resolve(runLabel).resolve(relative);
        Files.createDirectories(dest.getParent());
        Files.copy(dbFile, dest, StandardCopyOption.COPY_ATTRIBUTES);
        LOGGER.info("Backed up " + dbFile + " -> " + dest);
        return dest;
    }

    /**
     * Restore a previously backed-up file to its original location.
     *
     * @param backupFile the backup file
     * @param originalFile the original location to restore to
     * @throws IOException if the copy fails
     */
    public void restore(Path backupFile, Path originalFile) throws IOException {
        String originalName = originalFile.getFileName().toString();
        if (originalName.endsWith(DatabaseDiscovery.H2_DB_SUFFIX)) {
            String baseName = originalName.substring(
                    0, originalName.length() - DatabaseDiscovery.H2_DB_SUFFIX.length());
            Files.deleteIfExists(originalFile.resolveSibling(baseName + DatabaseDiscovery.MV_DB_SUFFIX));
        }
        Files.copy(backupFile, originalFile, StandardCopyOption.REPLACE_EXISTING);
        LOGGER.info("Restored " + backupFile + " -> " + originalFile);
    }

    /**
     * Generate a unique run label for this migration run.
     */
    public static String generateRunLabel() {
        return "migration_" + LocalDateTime.now().format(TS_FMT);
    }

    public Path getBackupRoot() {
        return backupRoot;
    }
}
