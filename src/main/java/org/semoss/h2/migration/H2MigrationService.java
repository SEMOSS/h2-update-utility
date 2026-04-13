package org.semoss.h2.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class H2MigrationService {

    private static final Logger LOGGER = LogManager.getLogger(H2MigrationService.class);
    private static final String OLD_H2 = "1.4.200";
    private static final String NEW_H2 = "2.4.240";

    private final MigrationConfig config;
    private final MigrationReport report;

    public H2MigrationService(MigrationConfig config) {
        this.config = config;
        this.report = new MigrationReport();
    }

    public static void main(String[] args) {

        if (args.length == 0 || "--help".equals(args[0])) {
            System.out.println(MigrationConfig.usage());
            System.exit(args.length == 0 ? 1 : 0);
        }

        MigrationConfig config;
        try {
            config = MigrationConfig.fromArgs(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println();
            System.err.println(MigrationConfig.usage());
            System.exit(1);
            return;
        }

        LOGGER.info("H2 Migration Service v1.0.0");
        LOGGER.info("Mode:               " + config.mode());
        LOGGER.info("Database directory:  " + config.dbDir());
        LOGGER.info("Backup directory:    " + config.backupDir());
        LOGGER.info("Storage provider:    " + config.storageProvider());
        LOGGER.info("Dry run:             " + config.dryRun());

        H2MigrationService service = new H2MigrationService(config);
        int exitCode = service.run();
        System.exit(exitCode);
    }

    /**
     * Execute the migration pipeline. Returns 0 on success (all migrated or skipped),
     * 1 if any database failed.
     */
    public int run() {
        try {
            switch (config.mode()) {
                case LOCAL -> migrateLocal();
                case CLOUD -> migrateCloud();
            }
        } catch (Exception e) {
            LOGGER.error("Migration pipeline failed", e);
            report.addResult("PIPELINE", MigrationReport.Status.FAILED, e.getMessage(), 0);
        }

        report.finish();

        // Write report
        Path reportFile = config.backupDir().resolve("migration-report.json");
        try {
            report.writeJson(reportFile);
            LOGGER.info("Report written to " + reportFile);
        } catch (IOException e) {
            LOGGER.warn("Failed to write report", e);
        }

        LOGGER.info("Migration complete: " + report.successCount() + " succeeded, "
                + report.failedCount() + " failed, " + report.skippedCount() + " skipped");

        return report.failedCount() > 0 ? 1 : 0;
    }

    /**
     * Migrate all H2 databases found on the local filesystem.
     */
    void migrateLocal() throws IOException {
        List<Path> databases = DatabaseDiscovery.findDatabases(config.dbDir());
        LOGGER.info("Found " + databases.size() + " H2 database files locally");

        if (databases.isEmpty()) return;

        try (IsolatedH2Driver oldDriver = IsolatedH2Driver.load(OLD_H2);
             IsolatedH2Driver newDriver = IsolatedH2Driver.load(NEW_H2)) {

            BackupManager backupMgr = new BackupManager(config.backupDir());
            DatabaseExporter exporter = new DatabaseExporter(oldDriver);
            DatabaseImporter importer = new DatabaseImporter(newDriver);
            String runLabel = BackupManager.generateRunLabel();

            for (Path dbFile : databases) {
                migrateSingleDatabase(dbFile, config.dbDir(), backupMgr, exporter, importer, runLabel);
            }
        }
    }

    /**
     * Migrate H2 databases in cloud storage via RClone.
     */
    void migrateCloud() throws IOException, InterruptedException {
        RCloneClient rclone = RCloneClient.fromConfig(config);
        Path stagingDir = Files.createTempDirectory("h2-cloud-staging");
        CloudDatabaseDiscovery cloudDiscovery = new CloudDatabaseDiscovery(rclone, config, stagingDir);

        String remotePath = resolveRemotePath();
        List<String> remoteFiles = cloudDiscovery.discoverRemoteDatabases(remotePath);
        LOGGER.info("Found " + remoteFiles.size() + " H2 database files in cloud");

        if (remoteFiles.isEmpty()) return;

        try (IsolatedH2Driver oldDriver = IsolatedH2Driver.load(OLD_H2);
             IsolatedH2Driver newDriver = IsolatedH2Driver.load(NEW_H2)) {

            BackupManager backupMgr = new BackupManager(config.backupDir());
            DatabaseExporter exporter = new DatabaseExporter(oldDriver);
            DatabaseImporter importer = new DatabaseImporter(newDriver);
            String runLabel = BackupManager.generateRunLabel();

            for (String relPath : remoteFiles) {
                long start = System.currentTimeMillis();
                String dbId = relPath;
                try {
                    // In dry-run, skip downloading entirely — just report what was found
                    if (config.dryRun()) {
                        report.addResult(dbId, MigrationReport.Status.SKIPPED,
                                "Dry run - would migrate", System.currentTimeMillis() - start);
                        continue;
                    }

                    // Download only the DB file
                    Path localFile = cloudDiscovery.downloadDatabase(remotePath, relPath);

                    // Check version
                    var detection = H2VersionDetector.detect(localFile);
                    if (!detection.needsMigration()) {
                        report.addResult(dbId, MigrationReport.Status.SKIPPED,
                                detection.reason(), System.currentTimeMillis() - start);
                        continue;
                    }

                    // Download companion SMSS only when we know migration is needed
                    cloudDiscovery.downloadCompanionSmss(remotePath, relPath);

                    // Backup, export, import
                    backupMgr.backup(localFile, stagingDir, runLabel);
                    SmssCredentialReader.Credentials creds = SmssCredentialReader.readCredentials(
                            localFile, config.defaultUsername(), config.defaultPassword());
                    Path script = exporter.export(localFile, creds.username(), creds.password());

                    // Preserve export script in backup directory
                    preserveScript(script, stagingDir, config.backupDir(), runLabel);

                    importer.importDatabase(localFile, script, creds.username(), creds.password());

                    // Upload migrated file back
                    cloudDiscovery.uploadDatabase(remotePath, relPath, localFile);

                    // Cleanup staging script (preserved copy is in backup dir)
                    Files.deleteIfExists(script);

                    report.addResult(dbId, MigrationReport.Status.SUCCESS,
                            "Migrated and uploaded", System.currentTimeMillis() - start);

                } catch (Exception e) {
                    LOGGER.warn("Failed to migrate cloud DB: " + relPath, e);
                    report.addResult(dbId, MigrationReport.Status.FAILED,
                            e.getMessage(), System.currentTimeMillis() - start);
                }
            }
        }
    }

    /**
     * Migrate a single local database file: detect → backup → export → import → cleanup.
     */
    void migrateSingleDatabase(Path dbFile, Path baseDir, BackupManager backupMgr,
                               DatabaseExporter exporter, DatabaseImporter importer,
                               String runLabel) {
        long start = System.currentTimeMillis();
        String dbId = dbFile.toAbsolutePath().toString();

        try {
            // 1. Version detection
            var detection = H2VersionDetector.detect(dbFile);
            if (!detection.needsMigration()) {
                report.addResult(dbId, MigrationReport.Status.SKIPPED,
                        detection.reason(), System.currentTimeMillis() - start);
                return;
            }

            if (config.dryRun()) {
                report.addResult(dbId, MigrationReport.Status.SKIPPED,
                        "Dry run - would migrate (" + detection.reason() + ")",
                        System.currentTimeMillis() - start);
                return;
            }

            // 2. Backup
            Path backupFile = backupMgr.backup(dbFile, baseDir, runLabel);

            // 3. Read credentials from companion SMSS
            SmssCredentialReader.Credentials creds = SmssCredentialReader.readCredentials(
                    dbFile, config.defaultUsername(), config.defaultPassword());

            // 4. Export with old H2
            Path script = exporter.export(dbFile, creds.username(), creds.password());

            // Preserve export script in backup directory
            preserveScript(script, baseDir, config.backupDir(), runLabel);

            // 5. Import with new H2
            try {
                importer.importDatabase(dbFile, script, creds.username(), creds.password());
            } catch (SQLException e) {
                // Restore backup on import failure
                LOGGER.warn("Import failed for " + dbFile + ", restoring backup", e);
                backupMgr.restore(backupFile, dbFile);
                throw e;
            }

            // 6. Cleanup local export script (preserved copy is in backup dir)
            Files.deleteIfExists(script);

            report.addResult(dbId, MigrationReport.Status.SUCCESS,
                    "Migrated from H2 " + OLD_H2 + " to " + NEW_H2,
                    System.currentTimeMillis() - start);

        } catch (Exception e) {
            LOGGER.warn("Failed to migrate " + dbFile, e);
            report.addResult(dbId, MigrationReport.Status.FAILED,
                    e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    /**
     * Determine the remote bucket/container path based on the storage provider.
     */
    private String resolveRemotePath() {
        String provider = config.storageProvider().toLowerCase();
        String bucket = switch (provider) {
            case "s3", "amazon_s3" -> config.s3Bucket();
            case "gcs", "google_cloud_storage" -> config.gcsBucket();
            case "minio" -> config.minioBucket();
            case "azure_blob", "azure" -> "";
            case "local_file_system" -> config.dbDir() != null ? config.dbDir().toString() : "";
            default -> "";
        };
        String searchPath = config.searchPath();
        if (searchPath != null && !searchPath.isEmpty()) {
            return bucket.isEmpty() ? searchPath : bucket + "/" + searchPath;
        }
        return bucket;
    }

    public MigrationReport getReport() {
        return report;
    }

    /**
     * Copy the export SQL script into the backup directory, mirroring the relative path
     * from the base directory so scripts are preserved alongside their DB backups.
     */
    private void preserveScript(Path script, Path baseDir, Path backupDir, String runLabel) {
        try {
            Path relative = baseDir.toAbsolutePath().relativize(script.toAbsolutePath());
            Path dest = backupDir.resolve(runLabel).resolve(relative);
            Files.createDirectories(dest.getParent());
            Files.copy(script, dest, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.debug("Preserved export script: " + dest);
        } catch (IOException e) {
            LOGGER.warn("Failed to preserve export script: " + script, e);
        }
    }
}
