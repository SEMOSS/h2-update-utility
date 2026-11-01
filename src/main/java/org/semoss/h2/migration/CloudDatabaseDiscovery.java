package org.semoss.h2.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Discovers and downloads provider-aware SEMOSS cloud migration scopes. */
public final class CloudDatabaseDiscovery {

    private static final Logger LOGGER = LogManager.getLogger(CloudDatabaseDiscovery.class);

    public record DownloadedObject(
            String remotePath,
            String relativePath,
            Path localPath,
            CloudObjectMetadata sourceMetadata,
            String sha256
    ) {}

    public record PreparedScope(
            CloudStorageLayout.ScopeDescriptor descriptor,
            List<DownloadedObject> databases,
            List<DownloadedObject> smssFiles
    ) {
        public PreparedScope {
            databases = List.copyOf(databases);
            smssFiles = List.copyOf(smssFiles);
        }
    }

    private final CloudObjectStore objectStore;
    private final CloudStorageLayout layout;
    private final Path stagingDir;

    public CloudDatabaseDiscovery(CloudObjectStore objectStore, CloudStorageLayout layout, Path stagingDir) {
        this.objectStore = objectStore;
        this.layout = layout;
        this.stagingDir = stagingDir.toAbsolutePath().normalize();
    }

    /** Scopes plus the saved-insight (.mosfet) files found under the same root. */
    public record Discovery(List<CloudStorageLayout.ScopeDescriptor> scopes, List<String> mosfetFiles) {}

    public Discovery discover(boolean withMosfets) throws IOException, InterruptedException {
        if (!withMosfets) return new Discovery(discoverScopes(), List.of());
        String root = layout.discoveryRoot();
        List<String> databases = new ArrayList<>();
        List<String> mosfets = new ArrayList<>();
        if (objectStore instanceof RCloneClient) {
            // one listing for both: the walk, not the filter, is what costs time
            for (String file : objectStore.listFiles(root, "*.{mv.db,mosfet}")) {
                (file.endsWith(".mosfet") ? mosfets : databases).add(file);
            }
        } else {
            databases.addAll(objectStore.listFiles(root, "*.mv.db"));
            mosfets.addAll(objectStore.listFiles(root, "*.mosfet"));
        }
        List<CloudStorageLayout.ScopeDescriptor> scopes = layout.groupDatabases(databases, stagingDir);
        LOGGER.info("Found " + databases.size() + " database object(s) in " + scopes.size()
                + " SEMOSS scope(s) and " + mosfets.size() + " saved insight file(s)");
        return new Discovery(scopes, List.copyOf(mosfets));
    }

    public List<CloudStorageLayout.ScopeDescriptor> discoverScopes()
            throws IOException, InterruptedException {
        String root = layout.discoveryRoot();
        LOGGER.info("Discovering H2 databases in configured cloud search path");
        List<String> files = objectStore.listFiles(root, "*.mv.db");
        List<CloudStorageLayout.ScopeDescriptor> scopes = layout.groupDatabases(files, stagingDir);
        LOGGER.info("Found " + files.size() + " database object(s) in " + scopes.size()
                + " SEMOSS scope(s)");
        return scopes;
    }

    public PreparedScope prepareScope(CloudStorageLayout.ScopeDescriptor scope)
            throws IOException, InterruptedException {
        List<DownloadedObject> databases = new ArrayList<>();
        for (CloudStorageLayout.RemoteDatabase database : scope.databases()) {
            Files.createDirectories(database.localPath().getParent());
            databases.add(download(database.remotePath(), database.relativePath(), database.localPath()));
        }

        List<DownloadedObject> smss = new ArrayList<>();
        // Only primary engine databases need an SMSS credential/configuration object.
        // Do not turn authentication, permission, or missing-container errors into an
        // apparently valid empty result: engine scopes must fail closed.
        List<String> smssNames = scope.key().type() == CloudStorageLayout.ScopeType.ENGINE
                ? objectStore.listFiles(scope.smssRoot(), "*.smss")
                : List.of();
        Path smssRoot = layout.smssLocalRoot(stagingDir, scope.key());
        for (String name : smssNames) {
            String safeName = CloudStorageLayout.validateRelativePath(name, "remote SMSS path");
            Path local = smssRoot.resolve(safeName).normalize();
            if (!local.startsWith(smssRoot)) {
                throw new IOException("SMSS object escapes staging directory: " + name);
            }
            Files.createDirectories(local.getParent());
            smss.add(download(join(scope.smssRoot(), safeName), safeName, local));
        }
        return new PreparedScope(scope, databases, smss);
    }

    private DownloadedObject download(String remotePath, String relativePath, Path localPath)
            throws IOException, InterruptedException {
        CloudObjectMetadata metadata = objectStore.stat(remotePath);
        objectStore.download(remotePath, localPath);
        if (metadata.size() >= 0 && Files.size(localPath) != metadata.size()) {
            throw new IOException("Downloaded object size changed for " + remotePath);
        }
        return new DownloadedObject(remotePath, relativePath, localPath,
                metadata, sha256(localPath));
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String join(String left, String right) {
        return left.endsWith("/") ? left + right : left + "/" + right;
    }

    public Path getStagingDir() {
        return stagingDir;
    }
}
