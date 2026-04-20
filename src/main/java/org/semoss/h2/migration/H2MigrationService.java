package org.semoss.h2.migration;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class H2MigrationService {

    private static final Logger LOGGER = LogManager.getLogger(H2MigrationService.class);
    private static final String OLD_H2 = "1.4.200";
    private static final String NEW_H2 = "2.4.240";

    /**
     * Warn in the per-database report when the export script exceeds this fraction of
     * {@code -Xmx}. RUNSCRIPT buffers individual SQL statements fully into memory, so
     * very large inline CLOB/BLOB literals are an OOM risk (Category E).
     */
    private static final double SCRIPT_HEAP_WARN_FRACTION = 0.5;

    /**
     * Matches the H2 "Syntax error in SQL statement …" message so we can extract the
     * offending statement fragment into {@code failingStatement} for Category K reporting.
     */
    private static final Pattern FAILING_STATEMENT = Pattern.compile(
            "Syntax error in SQL statement \"([^\"]+)\"", Pattern.DOTALL);

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

        LOGGER.info("H2 Migration Service v1.1.0");
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
        CloudDatabaseDiscovery cloudDiscovery = new CloudDatabaseDiscovery(rclone, stagingDir);

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
                MigrationReport.DatabaseResult result = null;
                try {
                    // In dry-run, skip downloading entirely — just report what was found
                    if (config.dryRun()) {
                        report.addResult(dbId, MigrationReport.Status.SKIPPED,
                                "Dry run - would migrate", System.currentTimeMillis() - start);
                        continue;
                    }

                    // Download only the DB file
                    Path localFile = cloudDiscovery.downloadDatabase(remotePath, relPath);

                    // Encryption probe (Category H)
                    if (looksEncrypted(localFile)) {
                        report.addResult(dbId, MigrationReport.Status.FAILED,
                                        "Database appears to be CIPHER-encrypted; utility does not support "
                                                + "encrypted H2 migration — decrypt first",
                                        System.currentTimeMillis() - start)
                                .errorClass("EncryptedDatabaseException");
                        continue;
                    }

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
                    Path preserved = preserveScript(script, stagingDir, config.backupDir(), runLabel);

                    result = report.addResult(dbId, MigrationReport.Status.SUCCESS,
                            "pending", 0);
                    if (preserved != null) result.preservedScriptPath(preserved.toAbsolutePath().toString());

                    // Rewrite legacy type aliases, quote ROW, strip aggregates (Categories A/B/C)
                    ScriptRewriter.RewriteReport rr = ScriptRewriter.rewriteAndScanInPlace(script);
                    recordRewrite(result, rr);

                    // Record target-URL compatibility flags (Categories B/D/F)
                    result.transformation("targetUrlFlags", DatabaseImporter.targetUrlFlags());

                    try {
                        importer.importDatabase(localFile, script, creds.username(), creds.password());
                    } catch (SQLException e) {
                        recordSqlFailure(result, e);
                        result.status(MigrationReport.Status.FAILED)
                                .reason(e.getMessage())
                                .durationMs(System.currentTimeMillis() - start);
                        throw e;
                    }

                    // Post-migration smoke open (Category I)
                    try {
                        importer.smokeOpen(localFile, creds.username(), creds.password());
                        result.transformation("smokeOpen", "OK");
                    } catch (SQLException e) {
                        recordSqlFailure(result, e);
                        result.transformation("smokeOpen", "FAILED");
                        result.status(MigrationReport.Status.FAILED)
                                .reason("Post-migration smoke open failed: " + e.getMessage())
                                .durationMs(System.currentTimeMillis() - start);
                        throw e;
                    }

                    // Upload migrated file back
                    cloudDiscovery.uploadDatabase(remotePath, relPath, localFile);

                    // Cleanup staging script + rewriter backup
                    Files.deleteIfExists(script);
                    Files.deleteIfExists(script.resolveSibling(script.getFileName().toString() + ".pre-rewrite.bak"));

                    result.reason("Migrated and uploaded").durationMs(System.currentTimeMillis() - start);

                } catch (Exception e) {
                    LOGGER.warn("Failed to migrate cloud DB: " + relPath, e);
                    if (result == null) {
                        report.addResult(dbId, MigrationReport.Status.FAILED,
                                e.getMessage(), System.currentTimeMillis() - start);
                    }
                    // otherwise already updated above
                }
            }
        }
    }

    /**
     * Migrate a single local database file: detect → backup → export → rewrite → import
     * → smoke open → cleanup.
     */
    void migrateSingleDatabase(Path dbFile, Path baseDir, BackupManager backupMgr,
                               DatabaseExporter exporter, DatabaseImporter importer,
                               String runLabel) {
        long start = System.currentTimeMillis();
        String dbId = dbFile.toAbsolutePath().toString();
        MigrationReport.DatabaseResult result = null;
        Path backupFile = null;

        try {
            // Encryption probe (Category H) — do this first so we don't try to export
            if (looksEncrypted(dbFile)) {
                report.addResult(dbId, MigrationReport.Status.FAILED,
                                "Database appears to be CIPHER-encrypted; utility does not support "
                                        + "encrypted H2 migration — decrypt first",
                                System.currentTimeMillis() - start)
                        .errorClass("EncryptedDatabaseException");
                return;
            }

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
            backupFile = backupMgr.backup(dbFile, baseDir, runLabel);

            // 3. Read credentials from companion SMSS
            SmssCredentialReader.Credentials creds = SmssCredentialReader.readCredentials(
                    dbFile, config.defaultUsername(), config.defaultPassword());

            // 4. Export with old H2
            Path script = exporter.export(dbFile, creds.username(), creds.password());

            // Preserve export script in backup directory
            Path preserved = preserveScript(script, baseDir, config.backupDir(), runLabel);

            // 5. Create the report entry now so every downstream step enriches the same record
            result = report.addResult(dbId, MigrationReport.Status.SUCCESS, "pending", 0);
            if (preserved != null) result.preservedScriptPath(preserved.toAbsolutePath().toString());

            // 6. Rewrite (Categories A + B ROW + C aggregate strip)
            ScriptRewriter.RewriteReport rr = ScriptRewriter.rewriteAndScanInPlace(script);
            recordRewrite(result, rr);

            // 7. Record compatibility flags the target URL will carry
            result.transformation("targetUrlFlags", DatabaseImporter.targetUrlFlags());

            // 8. Import with new H2
            try {
                importer.importDatabase(dbFile, script, creds.username(), creds.password());
            } catch (SQLException e) {
                recordSqlFailure(result, e);
                // Restore backup on import failure
                LOGGER.warn("Import failed for " + dbFile + ", restoring backup", e);
                backupMgr.restore(backupFile, dbFile);
                result.status(MigrationReport.Status.FAILED)
                        .reason(e.getMessage())
                        .durationMs(System.currentTimeMillis() - start);
                return;
            }

            // 9. Post-migration smoke open (Category I)
            try {
                importer.smokeOpen(dbFile, creds.username(), creds.password());
                result.transformation("smokeOpen", "OK");
            } catch (SQLException e) {
                recordSqlFailure(result, e);
                result.transformation("smokeOpen", "FAILED");
                LOGGER.warn("Smoke open failed for " + dbFile + ", restoring backup", e);
                backupMgr.restore(backupFile, dbFile);
                result.status(MigrationReport.Status.FAILED)
                        .reason("Post-migration smoke open failed: " + e.getMessage())
                        .durationMs(System.currentTimeMillis() - start);
                return;
            }

            // 10. Cleanup local export script + rewriter backup (preserved copy is in backup dir)
            Files.deleteIfExists(script);
            Files.deleteIfExists(script.resolveSibling(script.getFileName().toString() + ".pre-rewrite.bak"));

            result.reason("Migrated from H2 " + OLD_H2 + " to " + NEW_H2)
                    .durationMs(System.currentTimeMillis() - start);

        } catch (Exception e) {
            LOGGER.warn("Failed to migrate " + dbFile, e);
            if (result == null) {
                report.addResult(dbId, MigrationReport.Status.FAILED,
                        e.getMessage(), System.currentTimeMillis() - start);
            } else {
                result.status(MigrationReport.Status.FAILED)
                        .reason(e.getMessage())
                        .durationMs(System.currentTimeMillis() - start);
            }
        }
    }

    /**
     * Copy the export SQL script into the backup directory, mirroring the relative path
     * from the base directory so scripts are preserved alongside their DB backups.
     *
     * @return absolute path of the preserved copy, or {@code null} if preservation failed
     */
    private Path preserveScript(Path script, Path baseDir, Path backupDir, String runLabel) {
        try {
            Path relative = baseDir.toAbsolutePath().relativize(script.toAbsolutePath());
            Path dest = backupDir.resolve(runLabel).resolve(relative);
            Files.createDirectories(dest.getParent());
            Files.copy(script, dest, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.debug("Preserved export script: " + dest);
            return dest;
        } catch (IOException e) {
            LOGGER.warn("Failed to preserve export script: " + script, e);
            return null;
        }
    }

    /**
     * Fold the rewriter's structured report into the {@link MigrationReport.DatabaseResult}
     * so the JSON captures exactly what was mutated about the DB.
     */
    private void recordRewrite(MigrationReport.DatabaseResult result, ScriptRewriter.RewriteReport rr) {
        if (rr.totalReplacements() > 0) {
            result.transformation("typeAliasReplacements", rr.replacementsByToken());
        }
        if (!rr.strippedAggregates().isEmpty()) {
            result.transformation("strippedAggregates", rr.strippedAggregates());
        }
        result.transformation("scriptSizeBytes", rr.scriptSizeBytes());
        result.transformation("scriptLineCount", rr.lineCount());
        for (String w : rr.warnings()) result.addWarning(w);

        // Category E — warn if the script is a large fraction of JVM max heap
        long heapMax = Runtime.getRuntime().maxMemory();
        if (heapMax > 0 && rr.scriptSizeBytes() > heapMax * SCRIPT_HEAP_WARN_FRACTION) {
            result.addWarning(String.format(
                    "Export script (%d bytes) exceeds %.0f%% of JVM max heap (%d bytes) — "
                            + "RUNSCRIPT may OOM on large inline CLOB/BLOB literals; consider raising -Xmx",
                    rr.scriptSizeBytes(), SCRIPT_HEAP_WARN_FRACTION * 100, heapMax));
        }
    }

    /**
     * Capture {@link SQLException} context — SQLState, vendor error code, class, and the
     * "Syntax error in SQL statement \"…\"" fragment when present — into the result.
     * This is Category K: turning a one-line failure message into something support can act on.
     */
    private void recordSqlFailure(MigrationReport.DatabaseResult result, SQLException e) {
        result.sqlState(e.getSQLState())
                .errorCode(e.getErrorCode())
                .errorClass(e.getClass().getName());

        String msg = e.getMessage();
        if (msg != null) {
            Matcher m = FAILING_STATEMENT.matcher(msg);
            if (m.find()) {
                result.failingStatement(m.group(1));
            }
        }
    }

    /**
     * Probe the first bytes of an {@code .mv.db} file for the H2 CIPHER signature
     * (Category H). Encrypted databases start with a header marker H2 2.x can't interpret,
     * and exporting would otherwise surface only "File corrupted" or "Wrong user name
     * or password".
     *
     * <p>H2's MVStore writes a plain-text header line (keys:values comma-separated) at the
     * start of the file. When CIPHER is in effect, that header is encrypted and the first
     * bytes are not printable ASCII. We detect this as: the file is ≥16 bytes but
     * {@link H2VersionDetector#readMvStoreHeader(Path)} returned an empty map (couldn't
     * parse any key:value pairs), AND the first 64 bytes contain a high proportion of
     * non-printable characters.
     */
    static boolean looksEncrypted(Path dbFile) {
        String name = dbFile.getFileName().toString().toLowerCase();
        if (!name.endsWith(".mv.db")) return false;
        try {
            if (Files.size(dbFile) < 16) return false;
            var header = H2VersionDetector.readMvStoreHeader(dbFile);
            if (!header.isEmpty()) return false;

            try (RandomAccessFile raf = new RandomAccessFile(dbFile.toFile(), "r")) {
                byte[] buf = new byte[64];
                int read = raf.read(buf);
                int nonPrintable = 0;
                for (int i = 0; i < read; i++) {
                    int b = buf[i] & 0xff;
                    // Treat anything outside printable ASCII + common whitespace as non-printable.
                    // H2 headers are strictly printable; ciphertext is high-entropy random bytes.
                    if ((b < 0x20 || b > 0x7e) && b != '\n' && b != '\r' && b != '\t') {
                        nonPrintable++;
                    }
                }
                // Plain MVStore headers are all printable ASCII + newline. Heavy
                // non-printable density in the first 64B is a strong cipher signal.
                return read > 0 && (nonPrintable * 100.0 / read) > 20.0;
            }
        } catch (IOException e) {
            return false;
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
}
