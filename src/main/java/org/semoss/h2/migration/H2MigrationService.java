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

import org.semoss.h2.migration.MigrationReport.DatabaseResult;
import org.semoss.h2.migration.MigrationReport.Status;
import org.semoss.h2.migration.ScriptRewriter.RewriteReport;

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

        if (args.length > 0 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            System.out.println(MigrationConfig.usage());
            System.exit(0);
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

        LOGGER.info("H2 Migration Service v1.2.0");
        LOGGER.info("Mode:               " + config.mode());
        LOGGER.info("Database directory:  " + config.dbDir());
        LOGGER.info("Backup directory:    " + config.backupDir());
        LOGGER.info("Storage provider:    " + config.storageProvider());
        if ("gcs".equalsIgnoreCase(config.storageProvider())
                || "google_cloud_storage".equalsIgnoreCase(config.storageProvider())) {
            LOGGER.info("GCS transfer backend: " + config.gcsTransferBackend());
        }
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
            report.addResult("PIPELINE", Status.FAILED, e.getMessage(), 0);
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
        List<DatabaseTarget> targets = DatabaseDiscovery.findTargets(config.dbDir());
        LOGGER.info("Found " + targets.size() + " classified H2 database candidates locally");

        if (targets.isEmpty()) return;

        try (IsolatedH2Driver oldDriver = IsolatedH2Driver.load(OLD_H2);
             IsolatedH2Driver newDriver = IsolatedH2Driver.load(NEW_H2)) {

            BackupManager backupMgr = new BackupManager(config.backupDir());
            DatabaseExporter exporter = new DatabaseExporter(oldDriver);
            DatabaseImporter importer = new DatabaseImporter(newDriver);
            String runLabel = BackupManager.generateRunLabel();

            for (DatabaseTarget discovered : targets) {
                DatabaseTarget resolved = DatabaseCredentialResolver.resolve(discovered, config);
                migrateSingleTarget(resolved, config.dbDir(), backupMgr, exporter, importer, runLabel);
            }
        }
    }

    /**
     * Migrate H2 databases in cloud storage via RClone.
     */
    void migrateCloud() throws IOException, InterruptedException {
        try (IsolatedH2Driver oldDriver = IsolatedH2Driver.load(OLD_H2);
             IsolatedH2Driver newDriver = IsolatedH2Driver.load(NEW_H2)) {
            CloudMigrationCoordinator coordinator = new CloudMigrationCoordinator(
                    config, report, this, oldDriver, newDriver);
            coordinator.execute();
        }
    }

    /**
     * Migrate a single local database file: detect → backup → export → rewrite → import
     * → smoke open → cleanup.
     */
    DatabaseResult migrateSingleTarget(DatabaseTarget target, Path baseDir, BackupManager backupMgr,
                                       DatabaseExporter exporter, DatabaseImporter importer,
                                       String runLabel) {
        long start = System.currentTimeMillis();
        Path dbFile = target.databaseFile();
        DatabaseResult result = null;
        Path backupFile = null;
        Path smssBackupFile = null;
        SmssUrlUpdater.UpdatePlan smssUpdate = null;
        boolean migrationSucceeded = false;
        String stage = "preflight";

        try {
            if (target.disposition() == DatabaseTarget.Disposition.SKIP) {
                String reason = target.resolutionError() == null
                        ? "Transient live InsightCache database; runtime will recreate it"
                        : target.resolutionError();
                return report.addResult(target, Status.SKIPPED,
                        reason,
                        System.currentTimeMillis() - start);
            }

            if (target.disposition() == DatabaseTarget.Disposition.MANUAL_REVIEW
                    || target.resolutionError() != null || !target.readyToMigrate()) {
                String reason = target.resolutionError() == null
                        ? "Target is not resolved for migration" : target.resolutionError();
                return report.addResult(target, Status.FAILED, reason,
                        System.currentTimeMillis() - start);
            }

            if (looksEncrypted(dbFile)) {
                return report.addResult(target, Status.FAILED,
                                "Database appears to be CIPHER-encrypted; utility does not support "
                                        + "encrypted H2 migration — decrypt first",
                                System.currentTimeMillis() - start)
                        .errorClass("EncryptedDatabaseException");
            }

            // 1. Version detection
            var detection = H2VersionDetector.detect(dbFile);
            if (!detection.needsMigration()) {
                return report.addResult(target, Status.SKIPPED,
                        detection.reason(), System.currentTimeMillis() - start);
            }

            if (config.dryRun()) {
                return report.addResult(target, Status.SKIPPED,
                        "Dry run - would migrate (" + detection.reason() + ")",
                        System.currentTimeMillis() - start);
            }

            result = report.addResult(target, Status.SUCCESS, "pending", 0);

            stage = "backup";
            backupFile = backupMgr.backup(dbFile, baseDir, runLabel);

            if (target.companionSmss() != null && "H2".equals(target.companionBackend())) {
                smssUpdate = SmssUrlUpdater.preview(target.companionSmss());
                if (smssUpdate.required()) {
                    stage = "SMSS backup";
                    smssBackupFile = backupMgr.backup(target.companionSmss(), baseDir, runLabel);
                    result.transformation("smssUrlRemovedSettings", smssUpdate.removedSettings());
                }
            }

            SmssCredentialReader.Credentials creds = target.credentials();

            stage = "export";
            Path scriptPath = exportScriptPath(dbFile, baseDir, config.backupDir(), runLabel);
            Path script = exporter.export(dbFile, scriptPath, creds.username(), creds.password());
            result.preservedScriptPath(script.toAbsolutePath().toString());

            stage = "rewrite";
            RewriteReport rr = ScriptRewriter.rewriteAndScanInPlace(script);
            recordRewrite(result, rr);

            result.transformation("targetUrlFlags", DatabaseImporter.targetUrlFlags());

            stage = "import";
            importer.importDatabase(dbFile, script, creds.username(), creds.password());

            stage = "generic smoke open";
            importer.smokeOpen(dbFile, creds.username(), creds.password());
            result.transformation("smokeOpen", "OK");

            if (smssUpdate != null && smssUpdate.required()) {
                stage = "SMSS URL update";
                SmssUrlUpdater.apply(smssUpdate);
                result.transformation("smssUrlUpdate", "OK");
            }

            stage = "runtime profile smoke open";
            importer.smokeOpenRuntime(target);
            result.transformation("runtimeSmokeOpen", "OK");

            result.reason("Migrated from H2 " + OLD_H2 + " to " + NEW_H2)
                    .durationMs(System.currentTimeMillis() - start);
            migrationSucceeded = true;

        } catch (Exception e) {
            LOGGER.warn("Failed to migrate " + dbFile, e);
            if (result != null && "export".equals(stage)
                    && target.credentialSource() == DatabaseTarget.CredentialSource.RETAINED_SYSTEM_DEFAULT) {
                // retained file not in use and not openable with defaults: leave it untouched
                result.status(Status.SKIPPED)
                        .reason("Retained H2 file could not be exported with default credentials; left unchanged: "
                                + e.getMessage())
                        .durationMs(System.currentTimeMillis() - start);
            } else if (result == null) {
                result = report.addResult(target, Status.FAILED,
                        stage + " failed: " + e.getMessage(), System.currentTimeMillis() - start);
            } else {
                if (e instanceof SQLException sqlException) recordSqlFailure(result, sqlException);
                if (stage.contains("smoke open")) {
                    result.transformation(stage.startsWith("runtime")
                            ? "runtimeSmokeOpen" : "smokeOpen", "FAILED");
                }
                result.status(Status.FAILED)
                        .reason(stage + " failed: " + e.getMessage())
                        .durationMs(System.currentTimeMillis() - start);
            }
        } finally {
            if (backupFile != null && !migrationSucceeded) {
                try {
                    backupMgr.restore(backupFile, dbFile);
                    if (result != null) result.transformation("restore", "OK");
                } catch (IOException restoreError) {
                    LOGGER.error("Failed to restore backup for " + dbFile, restoreError);
                    if (result != null) {
                        result.addWarning("Backup restore failed: " + restoreError.getMessage())
                                .transformation("restore", "FAILED");
                    }
                }
            }
            if (smssBackupFile != null && !migrationSucceeded) {
                try {
                    backupMgr.restore(smssBackupFile, target.companionSmss());
                    if (result != null) result.transformation("smssRestore", "OK");
                } catch (IOException restoreError) {
                    LOGGER.error("Failed to restore SMSS backup for " + target.companionSmss(), restoreError);
                    if (result != null) {
                        result.addWarning("SMSS backup restore failed: " + restoreError.getMessage())
                                .transformation("smssRestore", "FAILED");
                    }
                }
            }
        }
        return result;
    }

    private Path exportScriptPath(Path dbFile, Path baseDir, Path backupDir, String runLabel) {
        Path relative;
        try {
            relative = baseDir.toAbsolutePath().normalize().relativize(dbFile.toAbsolutePath().normalize());
        } catch (IllegalArgumentException e) {
            relative = dbFile.getFileName();
        }
        String name = relative.getFileName().toString();
        if (name.endsWith(DatabaseDiscovery.MV_DB_SUFFIX)) {
            name = name.substring(0, name.length() - DatabaseDiscovery.MV_DB_SUFFIX.length());
        } else if (name.endsWith(DatabaseDiscovery.H2_DB_SUFFIX)) {
            name = name.substring(0, name.length() - DatabaseDiscovery.H2_DB_SUFFIX.length());
        }
        Path relativeScript = relative.resolveSibling(name + "_export.sql");
        return backupDir.resolve(runLabel).resolve("scripts").resolve(relativeScript);
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
     * Fold the rewriter's structured report into the {@link DatabaseResult}
     * so the JSON captures exactly what was mutated about the DB.
     */
    private void recordRewrite(DatabaseResult result, RewriteReport rr) {
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
    private void recordSqlFailure(DatabaseResult result, SQLException e) {
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

    public MigrationReport getReport() {
        return report;
    }
}
