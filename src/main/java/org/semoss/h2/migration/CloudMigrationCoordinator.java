package org.semoss.h2.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.semoss.h2.migration.CloudDatabaseDiscovery.DownloadedObject;
import org.semoss.h2.migration.CloudDatabaseDiscovery.PreparedScope;
import org.semoss.h2.migration.MigrationReport.DatabaseResult;
import org.semoss.h2.migration.MigrationReport.Status;

/** Executes read-only cloud preflight and recoverable engine/project publication. */
final class CloudMigrationCoordinator {

    private static final Logger LOGGER = LogManager.getLogger(CloudMigrationCoordinator.class);

    private record TargetWork(DownloadedObject source, Path originalCopy,
                              DatabaseTarget target, DatabaseResult result,
                              boolean changed) {}

    private record SmssWork(DownloadedObject source, Path originalCopy,
                            String changedSha256, DatabaseResult owner) {}

    private static final class Publication {
        private final String originalRemote;
        private final String relativePath;
        private final Path originalCopy;
        private final Path changedFile;
        private final String originalSha256;
        private final String changedSha256;
        private final CloudObjectMetadata sourceMetadata;
        private final DatabaseTarget target;
        private final DatabaseResult result;
        private final String backupRemote;
        private final String candidateRemote;
        private Path verifiedCandidate;

        Publication(String originalRemote, String relativePath, Path originalCopy,
                    Path changedFile, String originalSha256, String changedSha256,
                    CloudObjectMetadata sourceMetadata, DatabaseTarget target,
                    DatabaseResult result, String backupRemote, String candidateRemote) {
            this.originalRemote = originalRemote;
            this.relativePath = relativePath;
            this.originalCopy = originalCopy;
            this.changedFile = changedFile;
            this.originalSha256 = originalSha256;
            this.changedSha256 = changedSha256;
            this.sourceMetadata = sourceMetadata;
            this.target = target;
            this.result = result;
            this.backupRemote = backupRemote;
            this.candidateRemote = candidateRemote;
        }
    }

    private final MigrationConfig config;
    private final MigrationReport report;
    private final H2MigrationService migrationService;
    private final CloudObjectStore objectStore;
    private final CloudStorageLayout layout;
    private final Path stagingRoot;
    private final CloudDatabaseDiscovery discovery;
    private final BackupManager backupManager;
    private final DatabaseExporter exporter;
    private final DatabaseImporter importer;
    private final String runId;

    CloudMigrationCoordinator(MigrationConfig config, MigrationReport report,
                              H2MigrationService migrationService,
                              IsolatedH2Driver oldDriver, IsolatedH2Driver newDriver)
            throws IOException {
        this(config, report, migrationService, oldDriver, newDriver,
                CloudStorageLayout.fromConfig(config), CloudObjectStoreFactory.fromConfig(config),
                Files.createTempDirectory("h2-cloud-staging-"));
    }

    CloudMigrationCoordinator(MigrationConfig config, MigrationReport report,
                              H2MigrationService migrationService,
                              IsolatedH2Driver oldDriver, IsolatedH2Driver newDriver,
                              CloudStorageLayout layout, CloudObjectStore objectStore,
                              Path stagingRoot) throws IOException {
        this.config = config;
        this.report = report;
        this.migrationService = migrationService;
        this.layout = layout;
        this.objectStore = objectStore;
        this.stagingRoot = stagingRoot.toAbsolutePath().normalize();
        Files.createDirectories(this.stagingRoot);
        this.discovery = new CloudDatabaseDiscovery(objectStore, layout, stagingRoot);
        this.backupManager = new BackupManager(config.backupDir());
        this.exporter = new DatabaseExporter(oldDriver);
        this.importer = new DatabaseImporter(newDriver);
        this.runId = BackupManager.generateRunLabel() + "-" + UUID.randomUUID();
    }

    void execute() throws IOException, InterruptedException {
        if (!config.dryRun() && !config.confirmSemossStopped()) {
            throw new IllegalArgumentException(
                    "--confirm-semoss-stopped is required for an actual cloud migration");
        }
        try {
            List<CloudStorageLayout.ScopeDescriptor> scopes = discovery.discoverScopes();
            if (scopes.isEmpty() && !config.dryRun()) {
                throw new IllegalArgumentException(
                        "Actual cloud migration scope contains no H2 database objects");
            }
            for (CloudStorageLayout.ScopeDescriptor scope : scopes) {
                processScope(scope);
            }
        } finally {
            cleanupStaging();
        }
    }

    private void processScope(CloudStorageLayout.ScopeDescriptor descriptor)
            throws IOException, InterruptedException {
        long scopeStart = System.currentTimeMillis();
        PreparedScope scope;
        try {
            scope = discovery.prepareScope(descriptor);
        } catch (Exception e) {
            report.addResult("CLOUD_SCOPE:" + descriptor.key().type() + ":" + descriptor.key().id(),
                    Status.FAILED, "Cloud scope download failed: " + safeMessage(e),
                    System.currentTimeMillis() - scopeStart)
                    .cloudIdentity(layout.provider().name(), objectStore.authenticationMode(),
                            descriptor.key().type().name(), descriptor.key().id(),
                            descriptor.sourceRoot());
            return;
        }

        Path originalsRoot = descriptor.localRoot().resolve(".migration-originals");
        Files.createDirectories(originalsRoot);
        Map<Path, Path> originalSmssCopies = new LinkedHashMap<>();
        for (DownloadedObject object : scope.smssFiles()) {
            originalSmssCopies.put(object.localPath(), safeCopy(object.localPath(),
                    originalsRoot.resolve("smss").resolve(object.relativePath())));
        }
        List<Path> smssCandidates = scope.smssFiles().stream()
                .map(DownloadedObject::localPath).toList();
        List<TargetWork> targets = new ArrayList<>();

        for (DownloadedObject database : scope.databases()) {
            Path originalCopy = safeCopy(database.localPath(), originalsRoot.resolve("databases")
                    .resolve(database.relativePath()));
            DatabaseTarget discovered = DatabaseClassifier.classify(stagingRoot, database.localPath());
            if (discovered.targetType() == DatabaseTarget.TargetType.SYSTEM_H2) {
                discovered = discovered.unresolved(
                        "Per-node SEMOSS system databases are not cloud migration targets",
                        null, "NOT_APPLICABLE", List.of());
            }
            DatabaseTarget target = DatabaseCredentialResolver.resolve(
                    discovered, config, smssCandidates);
            DatabaseResult result = migrationService.migrateSingleTarget(
                    target, stagingRoot, backupManager, exporter, importer, runId);
            result.cloudIdentity(layout.provider().name(), objectStore.authenticationMode(),
                            descriptor.key().type().name(), descriptor.key().id(), database.remotePath())
                    .sourceObject(database.sourceMetadata().size(),
                            database.sourceMetadata().modTime(), database.sha256());
            targets.add(new TargetWork(database, originalCopy, target, result,
                    result.status() == Status.SUCCESS));
        }

        List<SmssWork> smss = new ArrayList<>();
        for (DownloadedObject object : scope.smssFiles()) {
            Path originalCopy = originalSmssCopies.get(object.localPath());
            String changedHash = CloudDatabaseDiscovery.sha256(object.localPath());
            DatabaseResult owner = targets.stream()
                    .filter(work -> work.target().companionSmss() != null
                            && work.target().companionSmss().equals(object.localPath()))
                    .map(TargetWork::result)
                    .findFirst().orElse(null);
            smss.add(new SmssWork(object, originalCopy, changedHash, owner));
        }

        boolean localFailure = targets.stream().anyMatch(work -> work.result().status() == Status.FAILED);
        if (config.dryRun()) return;
        if (localFailure) {
            for (TargetWork work : targets) {
                if (work.result().status() == Status.SUCCESS) {
                    work.result().status(Status.FAILED)
                            .reason("Scope publication aborted because another target failed local migration")
                            .promotionStatus("NOT_STARTED");
                }
            }
            return;
        }

        List<Publication> publications = buildPublications(descriptor, targets, smss);
        if (publications.isEmpty()) return;
        publishScope(descriptor, publications);
    }

    private List<Publication> buildPublications(CloudStorageLayout.ScopeDescriptor scope,
                                                List<TargetWork> targets,
                                                List<SmssWork> smss) throws IOException {
        List<Publication> publications = new ArrayList<>();
        for (TargetWork work : targets) {
            if (!work.changed()) continue;
            String changedHash = CloudDatabaseDiscovery.sha256(work.source().localPath());
            String backup = layout.backupPath(runId, scope, work.source().relativePath());
            String candidate = layout.candidatePath(runId, scope, work.source().relativePath());
            work.result().cloudWorkPaths(backup, candidate);
            publications.add(new Publication(work.source().remotePath(), work.source().relativePath(),
                    work.originalCopy(), work.source().localPath(), work.source().sha256(), changedHash,
                    work.source().sourceMetadata(), work.target(), work.result(), backup, candidate));
        }
        for (SmssWork work : smss) {
            if (work.source().sha256().equals(work.changedSha256())) continue;
            String backup = layout.backupSmssPath(runId, scope, work.source().relativePath());
            String candidate = layout.candidateSmssPath(runId, scope, work.source().relativePath());
            if (work.owner() != null) {
                work.owner().transformation("remoteSmssBackupPath", backup)
                        .transformation("remoteSmssCandidatePath", candidate);
            }
            publications.add(new Publication(work.source().remotePath(), work.source().relativePath(),
                    work.originalCopy(), work.source().localPath(), work.source().sha256(),
                    work.changedSha256(), work.source().sourceMetadata(), null, work.owner(),
                    backup, candidate));
        }
        return publications;
    }

    private void publishScope(CloudStorageLayout.ScopeDescriptor scope,
                              List<Publication> publications) {
        String stage = "remote backup";
        boolean promotionStarted = false;
        try {
            for (Publication publication : publications) {
                objectStore.upload(publication.originalCopy, publication.backupRemote);
                verifyHash(publication.backupRemote, publication.originalSha256,
                        verificationPath(scope, "backup", publication.relativePath));
            }

            stage = "candidate upload and verification";
            for (Publication publication : publications) {
                objectStore.upload(publication.changedFile, publication.candidateRemote);
                Path verified = verificationPath(scope, "candidate", publication.relativePath);
                verifyHash(publication.candidateRemote, publication.changedSha256, verified);
                publication.verifiedCandidate = verified;
                smokeOpenIfDatabase(publication, verified);
            }

            stage = "source concurrency check";
            for (Publication publication : publications) {
                CloudObjectMetadata current = objectStore.stat(publication.originalRemote);
                if (!publication.sourceMetadata.sameSourceVersion(current)) {
                    throw new IOException("Source object changed after preflight: "
                            + publication.originalRemote);
                }
            }

            stage = "promotion";
            promotionStarted = true;
            for (Publication publication : publications) {
                objectStore.upload(publication.verifiedCandidate, publication.originalRemote);
                if (publication.result != null) publication.result.promotionStatus("PROMOTED");
            }

            stage = "final verification";
            for (Publication publication : publications) {
                Path verified = verificationPath(scope, "final", publication.relativePath);
                verifyHash(publication.originalRemote, publication.changedSha256, verified);
                smokeOpenIfDatabase(publication, verified);
                if (publication.result != null) {
                    publication.result.finalSha256(publication.changedSha256)
                            .promotionStatus("VERIFIED")
                            .reason("Migrated and published with verified remote backup");
                }
            }

            for (Publication publication : publications) {
                try {
                    objectStore.delete(publication.candidateRemote);
                } catch (Exception cleanupError) {
                    if (publication.result != null) {
                        publication.result.addWarning(
                                "Candidate cleanup failed; verified candidate was retained")
                                .transformation("candidateCleanup", "FAILED");
                    }
                }
            }
        } catch (Exception failure) {
            LOGGER.error("Cloud scope publication failed during " + stage + " for "
                    + scope.key().type() + " " + scope.key().id(), failure);
            boolean rollbackOk = !promotionStarted || rollback(publications, scope);
            for (Publication publication : publications) {
                if (publication.result != null) {
                    publication.result.status(Status.FAILED)
                            .reason(stage + " failed: " + safeMessage(failure))
                            .promotionStatus("FAILED")
                            .rollbackStatus(promotionStarted
                                    ? (rollbackOk ? "VERIFIED" : "FAILED") : "NOT_REQUIRED");
                }
            }
        }
    }

    private boolean rollback(List<Publication> publications,
                             CloudStorageLayout.ScopeDescriptor scope) {
        boolean success = true;
        for (Publication publication : publications) {
            try {
                Path restored = verificationPath(scope, "rollback-source", publication.relativePath);
                objectStore.download(publication.backupRemote, restored);
                requireHash(restored, publication.originalSha256, "remote backup");
                objectStore.upload(restored, publication.originalRemote);
                verifyHash(publication.originalRemote, publication.originalSha256,
                        verificationPath(scope, "rollback-final", publication.relativePath));
            } catch (Exception rollbackFailure) {
                success = false;
                LOGGER.error("Critical: failed to restore remote object "
                        + publication.originalRemote, rollbackFailure);
            }
        }
        return success;
    }

    private void smokeOpenIfDatabase(Publication publication, Path databaseFile)
            throws Exception {
        if (publication.target != null) {
            Path smokeCopy = databaseFile.resolveSibling(
                    ".runtime-open-" + databaseFile.getFileName());
            Files.copy(databaseFile, smokeCopy, StandardCopyOption.REPLACE_EXISTING);
            importer.smokeOpenRuntime(publication.target.relocated(smokeCopy));
        }
    }

    private void verifyHash(String remotePath, String expectedSha, Path localPath)
            throws IOException, InterruptedException {
        Files.createDirectories(localPath.getParent());
        objectStore.download(remotePath, localPath);
        requireHash(localPath, expectedSha, remotePath);
    }

    private static void requireHash(Path file, String expectedSha, String description)
            throws IOException {
        String actual = CloudDatabaseDiscovery.sha256(file);
        if (!expectedSha.equals(actual)) {
            throw new IOException("SHA-256 verification failed for " + description);
        }
    }

    private Path verificationPath(CloudStorageLayout.ScopeDescriptor scope,
                                  String stage, String relativePath) {
        Path root = stagingRoot.resolve("verification").resolve(stage)
                .resolve(scope.key().type().name().toLowerCase())
                .resolve(scope.key().id()).normalize();
        Path output = root.resolve(relativePath).normalize();
        if (!output.startsWith(root)) {
            throw new IllegalArgumentException("Verification path escapes staging directory");
        }
        return output;
    }

    private static Path safeCopy(Path source, Path destination) throws IOException {
        Path normalized = destination.toAbsolutePath().normalize();
        Files.createDirectories(normalized.getParent());
        return Files.copy(source, normalized, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
    }

    private void cleanupStaging() {
        try (var paths = Files.walk(stagingRoot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to remove cloud staging directory " + stagingRoot, e);
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }
}
