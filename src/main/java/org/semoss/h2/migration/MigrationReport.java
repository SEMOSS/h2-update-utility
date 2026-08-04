package org.semoss.h2.migration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects per-database migration results and writes a JSON summary report.
 * {@link #toMap()} returns the structured report; {@link #toJson()} serialises it via Gson.
 *
 * <p>Each {@link DatabaseResult} carries:
 * <ul>
 *   <li>Status / reason / durationMs for the basic outcome.</li>
 *   <li>{@code sqlState}, {@code errorCode}, {@code errorClass}, {@code failingStatement}
 *       — populated when a {@link java.sql.SQLException} fails the migration (Category K).</li>
 *   <li>{@code preservedScriptPath} — absolute path to the export SQL script kept in the
 *       backup directory, so support can inspect exactly what H2 2.x rejected.</li>
 *   <li>{@code warnings} — non-blocking pre-flight / runtime observations (e.g. "script is
 *       180MB — consider raising heap", "CREATE AGGREGATE detected and stripped",
 *       "identifier ROW present — quoted via rewriter").</li>
 *   <li>{@code transformations} — structured map describing every modification applied to
 *       the database during migration (type-alias replacements by token, non-keywords
 *       passed on the target URL, aggregates stripped and later restored, pre-migration
 *       script size, mode flags in effect, smoke-open outcome). This is the primary
 *       on-disk record of what changed about the DB beyond an H2-version bump.</li>
 * </ul>
 */
public final class MigrationReport {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public enum Status { SUCCESS, FAILED, SKIPPED }

    /**
     * A single database's migration outcome. Mutable (fields are filled in progressively
     * through the pipeline) to avoid threading a builder through every step.
     */
    public static final class DatabaseResult {
        private final String path;
        private Status status;
        private String reason;
        private long durationMs;

        // SEMOSS target identity (never contains passwords or secret URL values)
        private String targetType;
        private String disposition;
        private String credentialSource;
        private String runtimeProfile;
        private String companionSmssPath;
        private String companionBackend;

        // Cloud identity and publication state (never contains authentication material)
        private String storageProvider;
        private String authenticationMode;
        private String scopeType;
        private String scopeId;
        private String remotePath;
        private Long sourceSize;
        private String sourceModified;
        private String sourceSha256;
        private String finalSha256;
        private String remoteBackupPath;
        private String remoteCandidatePath;
        private String promotionStatus;
        private String rollbackStatus;

        // Category K — detailed error context
        private String sqlState;
        private Integer errorCode;
        private String errorClass;
        private String failingStatement;

        // Artifacts
        private String preservedScriptPath;

        // Observability
        private final List<String> warnings = new ArrayList<>();
        private final Map<String, Object> transformations = new LinkedHashMap<>();

        public DatabaseResult(String path, Status status, String reason, long durationMs) {
            this.path = path;
            this.status = status;
            this.reason = reason;
            this.durationMs = durationMs;
        }

        public String path() { return path; }
        public Status status() { return status; }
        public String reason() { return reason; }
        public long durationMs() { return durationMs; }
        public String sqlState() { return sqlState; }
        public Integer errorCode() { return errorCode; }
        public String errorClass() { return errorClass; }
        public String failingStatement() { return failingStatement; }
        public String preservedScriptPath() { return preservedScriptPath; }
        public String targetType() { return targetType; }
        public String disposition() { return disposition; }
        public String credentialSource() { return credentialSource; }
        public String runtimeProfile() { return runtimeProfile; }
        public String companionSmssPath() { return companionSmssPath; }
        public String companionBackend() { return companionBackend; }
        public List<String> warnings() { return Collections.unmodifiableList(warnings); }
        public Map<String, Object> transformations() { return Collections.unmodifiableMap(transformations); }

        public DatabaseResult status(Status s) { this.status = s; return this; }
        public DatabaseResult reason(String r) { this.reason = r; return this; }
        public DatabaseResult durationMs(long d) { this.durationMs = d; return this; }
        public DatabaseResult sqlState(String s) { this.sqlState = s; return this; }
        public DatabaseResult errorCode(Integer c) { this.errorCode = c; return this; }
        public DatabaseResult errorClass(String c) { this.errorClass = c; return this; }
        public DatabaseResult failingStatement(String s) { this.failingStatement = s; return this; }
        public DatabaseResult preservedScriptPath(String p) { this.preservedScriptPath = p; return this; }
        public DatabaseResult target(DatabaseTarget target) {
            this.targetType = target.targetType().name();
            this.disposition = target.disposition().name();
            this.credentialSource = target.credentialSource().name();
            this.runtimeProfile = target.runtimeProfile().name();
            this.companionSmssPath = target.companionSmss() == null
                    ? null : target.companionSmss().toAbsolutePath().toString();
            this.companionBackend = target.companionBackend();
            for (String warning : target.warnings()) addWarning(warning);
            return this;
        }
        public DatabaseResult cloudIdentity(String provider, String authMode,
                                            String cloudScopeType, String cloudScopeId,
                                            String cloudRemotePath) {
            this.storageProvider = provider;
            this.authenticationMode = authMode;
            this.scopeType = cloudScopeType;
            this.scopeId = cloudScopeId;
            this.remotePath = cloudRemotePath;
            return this;
        }
        public DatabaseResult sourceObject(long size, String modified, String sha256) {
            this.sourceSize = size;
            this.sourceModified = modified;
            this.sourceSha256 = sha256;
            return this;
        }
        public DatabaseResult cloudWorkPaths(String backupPath, String candidatePath) {
            this.remoteBackupPath = backupPath;
            this.remoteCandidatePath = candidatePath;
            return this;
        }
        public DatabaseResult finalSha256(String sha256) { this.finalSha256 = sha256; return this; }
        public DatabaseResult promotionStatus(String status) { this.promotionStatus = status; return this; }
        public DatabaseResult rollbackStatus(String status) { this.rollbackStatus = status; return this; }
        public DatabaseResult addWarning(String w) { warnings.add(w); return this; }
        public DatabaseResult transformation(String key, Object value) { transformations.put(key, value); return this; }
    }

    private final Instant startTime;
    private final List<DatabaseResult> results = new ArrayList<>();
    private Instant endTime;

    public MigrationReport() {
        this.startTime = Instant.now();
    }

    /**
     * Add a basic result (backward-compatible). Returns the {@link DatabaseResult} so
     * callers can enrich it with transformations / warnings / error context before moving on.
     */
    public DatabaseResult addResult(String path, Status status, String reason, long durationMs) {
        DatabaseResult r = new DatabaseResult(path, status, reason, durationMs);
        results.add(r);
        return r;
    }

    public DatabaseResult addResult(DatabaseTarget target, Status status, String reason, long durationMs) {
        return addResult(target.databaseFile().toAbsolutePath().toString(), status, reason, durationMs)
                .target(target);
    }

    public void finish() {
        this.endTime = Instant.now();
    }

    public List<DatabaseResult> getResults() {
        return Collections.unmodifiableList(results);
    }

    public long totalCount() { return results.size(); }
    public long successCount() { return results.stream().filter(r -> r.status == Status.SUCCESS).count(); }
    public long failedCount() { return results.stream().filter(r -> r.status == Status.FAILED).count(); }
    public long skippedCount() { return results.stream().filter(r -> r.status == Status.SKIPPED).count(); }

    /**
     * Return the report as a structured Map (suitable for further processing).
     */
    public Map<String, Object> toMap() {
        if (endTime == null) finish();
        long totalDuration = Duration.between(startTime, endTime).toMillis();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", totalCount());
        summary.put("success", successCount());
        summary.put("failed", failedCount());
        summary.put("skipped", skippedCount());
        summary.put("durationMs", totalDuration);
        summary.put("startTime", startTime.toString());
        summary.put("endTime", endTime.toString());

        List<Map<String, Object>> databases = new ArrayList<>();
        List<DatabaseResult> sorted = new ArrayList<>(results);
        sorted.sort(Comparator.comparing(r -> r.status.name()));
        for (DatabaseResult r : sorted) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", r.path);
            entry.put("status", r.status.name());
            entry.put("reason", r.reason);
            entry.put("durationMs", r.durationMs);
            if (r.targetType != null) entry.put("targetType", r.targetType);
            if (r.disposition != null) entry.put("disposition", r.disposition);
            if (r.credentialSource != null) entry.put("credentialSource", r.credentialSource);
            if (r.runtimeProfile != null) entry.put("runtimeProfile", r.runtimeProfile);
            if (r.companionSmssPath != null) entry.put("companionSmssPath", r.companionSmssPath);
            if (r.companionBackend != null) entry.put("companionBackend", r.companionBackend);
            if (r.storageProvider != null) entry.put("storageProvider", r.storageProvider);
            if (r.authenticationMode != null) entry.put("authenticationMode", r.authenticationMode);
            if (r.scopeType != null) entry.put("scopeType", r.scopeType);
            if (r.scopeId != null) entry.put("scopeId", r.scopeId);
            if (r.remotePath != null) entry.put("remotePath", r.remotePath);
            if (r.sourceSize != null) entry.put("sourceSize", r.sourceSize);
            if (r.sourceModified != null) entry.put("sourceModified", r.sourceModified);
            if (r.sourceSha256 != null) entry.put("sourceSha256", r.sourceSha256);
            if (r.finalSha256 != null) entry.put("finalSha256", r.finalSha256);
            if (r.remoteBackupPath != null) entry.put("remoteBackupPath", r.remoteBackupPath);
            if (r.remoteCandidatePath != null) entry.put("remoteCandidatePath", r.remoteCandidatePath);
            if (r.promotionStatus != null) entry.put("promotionStatus", r.promotionStatus);
            if (r.rollbackStatus != null) entry.put("rollbackStatus", r.rollbackStatus);
            if (r.preservedScriptPath != null) entry.put("preservedScriptPath", r.preservedScriptPath);
            if (r.sqlState != null) entry.put("sqlState", r.sqlState);
            if (r.errorCode != null) entry.put("errorCode", r.errorCode);
            if (r.errorClass != null) entry.put("errorClass", r.errorClass);
            if (r.failingStatement != null) entry.put("failingStatement", r.failingStatement);
            if (!r.warnings.isEmpty()) entry.put("warnings", new ArrayList<>(r.warnings));
            if (!r.transformations.isEmpty()) entry.put("transformations", new LinkedHashMap<>(r.transformations));
            databases.add(entry);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("summary", summary);
        report.put("databases", databases);
        return report;
    }

    /**
     * Produce a pretty-printed JSON string of the report.
     */
    public String toJson() {
        return GSON.toJson(toMap());
    }

    /**
     * Write the report as JSON to the given file.
     */
    public void writeJson(Path outputFile) throws IOException {
        if (endTime == null) finish();
        Files.createDirectories(outputFile.getParent());
        try (Writer w = Files.newBufferedWriter(outputFile)) {
            w.write(toJson());
        }
    }
}
