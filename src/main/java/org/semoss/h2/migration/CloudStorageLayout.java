package org.semoss.h2.migration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Provider-aware mapping of SEMOSS engine/project storage paths. */
public final class CloudStorageLayout {

    public enum Provider { S3, MINIO, GCS, AZURE, LOCAL }
    public enum ScopeType { ENGINE, PROJECT }

    public record ScopeKey(ScopeType type, String id) {}

    public record RemoteDatabase(
            ScopeKey scope,
            String remotePath,
            String relativePath,
            Path localPath
    ) {}

    public record ScopeDescriptor(
            ScopeKey key,
            String sourceRoot,
            String smssRoot,
            Path localRoot,
            List<RemoteDatabase> databases
    ) {
        public ScopeDescriptor {
            databases = List.copyOf(databases);
        }
    }

    private static final String WORK_ROOT = "_semoss-h2-migration";
    private static final String AZURE_WORK_CONTAINER = "semoss-h2-migration";

    private final Provider provider;
    private final String storageRoot;
    private final String searchPath;
    private final ScopeType scopeType;
    private final String fixedScopeId;
    private final String semossRootName;
    // Azure run over every container of one type: "db-" or "project-"; null otherwise
    private final String containerPrefix;

    private CloudStorageLayout(Provider provider, String storageRoot, String searchPath,
                               ScopeType scopeType, String fixedScopeId,
                               String semossRootName, String containerPrefix) {
        this.provider = provider;
        this.storageRoot = storageRoot;
        this.searchPath = searchPath;
        this.scopeType = scopeType;
        this.fixedScopeId = fixedScopeId;
        this.semossRootName = semossRootName;
        this.containerPrefix = containerPrefix;
    }

    public static CloudStorageLayout fromConfig(MigrationConfig config) {
        Objects.requireNonNull(config, "config");
        String providerValue = value(config.storageProvider()).toLowerCase(Locale.ROOT);
        Provider provider = switch (providerValue) {
            case "s3", "amazon_s3" -> Provider.S3;
            case "minio" -> Provider.MINIO;
            case "gcs", "google_cloud_storage" -> Provider.GCS;
            case "azure", "azure_blob" -> Provider.AZURE;
            case "local_file_system" -> Provider.LOCAL;
            default -> throw new IllegalArgumentException(
                    "Unsupported storage provider: " + config.storageProvider());
        };

        String searchPath = validateRelativePath(config.searchPath(), "--search-path");
        String storageRoot = switch (provider) {
            case S3 -> required(config.s3Bucket(), "S3_BUCKET");
            case MINIO -> required(config.minioBucket(), "MINIO_BUCKET");
            case GCS -> required(config.gcsBucket(), "GCS_BUCKET");
            case AZURE -> "";
            case LOCAL -> {
                if (config.dbDir() == null) {
                    throw new IllegalArgumentException(
                            "--db-dir is required for local_file_system cloud mode");
                }
                yield config.dbDir().toAbsolutePath().normalize().toString();
            }
        };

        if (provider == Provider.AZURE) {
            if (searchPath.startsWith("db-") && !searchPath.endsWith("-smss")) {
                return new CloudStorageLayout(provider, storageRoot, searchPath,
                        ScopeType.ENGINE, requiredId(searchPath.substring(3)), "semoss-db", null);
            }
            if (searchPath.startsWith("project-") && !searchPath.endsWith("-smss")) {
                return new CloudStorageLayout(provider, storageRoot, searchPath,
                        ScopeType.PROJECT, requiredId(searchPath.substring(8)), "semoss-project", null);
            }
            // every db-<engineId> / project-<projectId> container in the account
            if ("semoss-db".equals(searchPath)) {
                return new CloudStorageLayout(provider, storageRoot, "", ScopeType.ENGINE, null, "semoss-db", "db-");
            }
            if ("semoss-project".equals(searchPath)) {
                return new CloudStorageLayout(provider, storageRoot, "", ScopeType.PROJECT, null,
                        "semoss-project", "project-");
            }
            throw new IllegalArgumentException(
                    "Azure --search-path must be semoss-db, semoss-project, db-<engineId> or project-<projectId>");
        }

        String[] parts = searchPath.split("/");
        if (parts.length < 1 || parts.length > 2) {
            throw new IllegalArgumentException(
                    "--search-path must be semoss-db[/<engineId>] or semoss-project[/<projectId>]");
        }
        ScopeType type;
        if ("semoss-db".equals(parts[0])) {
            type = ScopeType.ENGINE;
        } else if ("semoss-project".equals(parts[0])) {
            type = ScopeType.PROJECT;
        } else {
            throw new IllegalArgumentException(
                    "--search-path must begin with semoss-db or semoss-project");
        }
        String fixedId = parts.length == 2 ? requiredId(parts[1]) : null;
        return new CloudStorageLayout(provider, storageRoot, searchPath, type, fixedId, parts[0], null);
    }

    public Provider provider() { return provider; }
    public ScopeType scopeType() { return scopeType; }
    public String authenticationLayoutName() { return provider.name(); }

    public String discoveryRoot() {
        return join(storageRoot, searchPath);
    }

    /** Listing filter; an all-containers Azure run only walks containers of its own type. */
    public String listInclude(String pattern) {
        return containerPrefix == null ? pattern : "/" + containerPrefix + "*/**" + pattern.substring(1);
    }

    /** Scope id of a listed path (relative to discoveryRoot), or null when it is not inside a scope. */
    public String scopeIdOf(String listedPath) {
        if (fixedScopeId != null) return fixedScopeId;
        String path = value(listedPath).replace('\\', '/');
        int slash = path.indexOf('/');
        if (slash <= 0) return null;
        String first = path.substring(0, slash);
        if (containerPrefix == null) return first;
        if (!first.startsWith(containerPrefix) || first.endsWith("-smss")) return null;
        return first.substring(containerPrefix.length());
    }

    public List<ScopeDescriptor> groupDatabases(List<String> listedPaths, Path stagingRoot) {
        Map<ScopeKey, List<RemoteDatabase>> grouped = new LinkedHashMap<>();
        for (String listed : listedPaths) {
            if (containerPrefix != null && scopeIdOf(listed) == null) continue;
            String safe = validateRelativePath(listed, "remote database path");
            String id;
            String relative;
            if (containerPrefix != null) {
                // other containers (the -smss ones, other types, work storage) are not scopes
                id = requiredId(scopeIdOf(safe));
                relative = validateRelativePath(safe.substring(safe.indexOf('/') + 1), "scope-relative database path");
            } else if (fixedScopeId != null) {
                id = fixedScopeId;
                relative = safe;
            } else {
                int slash = safe.indexOf('/');
                if (slash <= 0 || slash == safe.length() - 1) {
                    throw new IllegalArgumentException(
                            "Remote database is not inside an engine/project scope: " + safe);
                }
                id = requiredId(safe.substring(0, slash));
                relative = validateRelativePath(safe.substring(slash + 1), "scope-relative database path");
            }
            if (id.endsWith("-smss")) {
                throw new IllegalArgumentException("Database discovered under an SMSS scope: " + safe);
            }
            ScopeKey key = new ScopeKey(scopeType, id);
            String sourceRoot = sourceRoot(key);
            Path localRoot = stagingRoot.resolve(semossRootName).resolve(id).normalize();
            Path localPath = localRoot.resolve(relative).normalize();
            if (!localPath.startsWith(localRoot)) {
                throw new IllegalArgumentException("Remote path escapes staging scope: " + safe);
            }
            RemoteDatabase db = new RemoteDatabase(key, join(sourceRoot, relative), relative, localPath);
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(db);
        }

        List<ScopeDescriptor> scopes = new ArrayList<>();
        for (Map.Entry<ScopeKey, List<RemoteDatabase>> entry : grouped.entrySet()) {
            ScopeKey key = entry.getKey();
            scopes.add(new ScopeDescriptor(key, sourceRoot(key), smssRoot(key),
                    stagingRoot.resolve(semossRootName).resolve(key.id()).normalize(),
                    entry.getValue()));
        }
        return List.copyOf(scopes);
    }

    public Path smssLocalRoot(Path stagingRoot, ScopeKey key) {
        return stagingRoot.resolve(semossRootName).resolve(key.id() + "-smss").normalize();
    }

    public String backupPath(String runId, ScopeDescriptor scope, String relativePath) {
        return workPath(runId, "backup", scope, relativePath);
    }

    public String candidatePath(String runId, ScopeDescriptor scope, String relativePath) {
        return workPath(runId, "candidate", scope, relativePath);
    }

    public String backupSmssPath(String runId, ScopeDescriptor scope, String fileName) {
        return workPath(runId, "backup", scopeFolder(scope.smssRoot()), fileName);
    }

    public String candidateSmssPath(String runId, ScopeDescriptor scope, String fileName) {
        return workPath(runId, "candidate", scopeFolder(scope.smssRoot()), fileName);
    }

    private String workPath(String runId, String stage, ScopeDescriptor scope, String relativePath) {
        return workPath(runId, stage, scopeFolder(scope.sourceRoot()), relativePath);
    }

    // keep the semoss-db/semoss-project level so an engine and a project with the same id never share a folder
    private String scopeFolder(String root) {
        if (provider == Provider.AZURE) return lastSegment(root);
        return join(semossRootName, lastSegment(root));
    }

    private String workPath(String runId, String stage, String scopeFolder, String relativePath) {
        String safeRun = validateSimpleName(runId, "runId");
        String safeRelative = validateRelativePath(relativePath, "work relative path");
        if (provider == Provider.AZURE) {
            return join(AZURE_WORK_CONTAINER, safeRun, stage, scopeFolder, safeRelative);
        }
        return join(storageRoot, WORK_ROOT, safeRun, stage, scopeFolder, safeRelative);
    }

    private String sourceRoot(ScopeKey key) {
        if (provider == Provider.AZURE) {
            return (key.type() == ScopeType.ENGINE ? "db-" : "project-") + key.id();
        }
        return join(storageRoot, semossRootName, key.id());
    }

    private String smssRoot(ScopeKey key) {
        return sourceRoot(key) + "-smss";
    }

    static String validateRelativePath(String path, String label) {
        String value = value(path).replace('\\', '/');
        if (value.isBlank()) throw new IllegalArgumentException(label + " is required");
        if (value.startsWith("/") || value.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException(label + " must be relative: " + value);
        }
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        for (String part : value.split("/")) {
            if (part.isBlank() || ".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException(label + " contains an unsafe path component: " + value);
            }
            if (WORK_ROOT.equalsIgnoreCase(part) || AZURE_WORK_CONTAINER.equalsIgnoreCase(part)) {
                throw new IllegalArgumentException(label + " points into migration work storage: " + value);
            }
        }
        return value;
    }

    private static String requiredId(String id) {
        return validateSimpleName(id, "engine/project id");
    }

    private static String validateSimpleName(String value, String label) {
        String safe = value(value);
        if (safe.isBlank() || safe.contains("/") || safe.contains("\\")
                || ".".equals(safe) || "..".equals(safe)) {
            throw new IllegalArgumentException("Invalid " + label + ": " + safe);
        }
        return safe;
    }

    private static String required(String value, String label) {
        String required = value(value);
        if (required.isBlank()) throw new IllegalArgumentException(label + " is required");
        return required;
    }

    private static String join(String... parts) {
        StringBuilder out = new StringBuilder();
        for (String raw : parts) {
            String part = value(raw).replace('\\', '/');
            while (part.startsWith("/")) part = part.substring(1);
            while (part.endsWith("/")) part = part.substring(0, part.length() - 1);
            if (part.isBlank()) continue;
            if (out.length() > 0) out.append('/');
            out.append(part);
        }
        if (parts.length > 0 && value(parts[0]).startsWith("/")) {
            return "/" + out;
        }
        return out.toString();
    }

    private static String lastSegment(String path) {
        String value = value(path).replace('\\', '/');
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }
}
