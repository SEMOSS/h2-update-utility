package org.semoss.h2.migration;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Recursively discovers H2 database files ({@code .mv.db} and {@code .h2.db})
 * under a root directory. Cross-platform — uses {@link java.nio.file.Path} throughout.
 */
public final class DatabaseDiscovery {

    private static final Logger LOGGER = LogManager.getLogger(DatabaseDiscovery.class);

    static final String MV_DB_SUFFIX = ".mv.db";
    static final String H2_DB_SUFFIX = ".h2.db";
    /** Name of the default backup folder; never scanned, wherever it is. */
    static final String DEFAULT_BACKUP_DIR_NAME = ".h2-backup";

    private DatabaseDiscovery() {}

    /**
     * Walk the directory tree under {@code rootDir} and return every H2
     * database file found. Symlinks are not followed. Unreadable subtrees
     * are logged and skipped rather than aborting the whole scan.
     *
     * @param rootDir directory to scan
     * @return list of absolute, normalized paths to H2 database files
     * @throws IOException if the root directory itself cannot be accessed
     * @throws IllegalArgumentException if rootDir is null or not a directory
     */
    public static List<Path> findDatabases(Path rootDir) throws IOException {
        return findTargets(rootDir).stream()
                .map(DatabaseTarget::databaseFile)
                .toList();
    }

    /**
     * Discover and classify every H2 file under the supplied SEMOSS root.
     * Classification is performed before credentials are considered so
     * generated databases cannot accidentally inherit an unrelated SMSS.
     */
    public static List<DatabaseTarget> findTargets(Path rootDir) throws IOException {
        return findTargets(rootDir, List.of());
    }

    /** As {@link #findTargets(Path)}, skipping the given directories (for example the backup dir). */
    public static List<DatabaseTarget> findTargets(Path rootDir, Collection<Path> excludedDirs) throws IOException {
        List<DatabaseTarget> results = new ArrayList<>();
        Path normalized = walk(rootDir, excludedDirs, file -> {
            if (isH2Database(file)) results.add(DatabaseClassifier.classify(rootDir.toAbsolutePath().normalize(), file));
        });
        results.sort(Comparator.comparing(target -> target.databaseFile().toString()));
        LOGGER.info("Discovered and classified " + results.size()
                + " H2 database candidate(s) under " + normalized);
        return results;
    }

    /** Find in-progress migration markers left by an interrupted run. */
    static List<Path> findMarkers(Path rootDir, Collection<Path> excludedDirs) throws IOException {
        List<Path> markers = new ArrayList<>();
        walk(rootDir, excludedDirs, file -> {
            if (MigrationMarker.isMarker(file)) markers.add(file);
        });
        markers.sort(Comparator.naturalOrder());
        return markers;
    }

    private static Path walk(Path rootDir, Collection<Path> excludedDirs,
                             Consumer<Path> visitor) throws IOException {
        if (rootDir == null) {
            throw new IllegalArgumentException("rootDir must not be null");
        }
        Path normalized = rootDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("Not a directory: " + normalized);
        }

        Set<Path> excluded = excludedDirs.stream()
                .map(p -> p.toAbsolutePath().normalize())
                .collect(Collectors.toSet());

        Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Path name = dir.getFileName();
                boolean backupName = name != null && DEFAULT_BACKUP_DIR_NAME.equals(name.toString());
                if (!dir.equals(normalized) && (backupName || excluded.contains(dir))) {
                    LOGGER.info("Not scanning backup directory " + dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                visitor.accept(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                LOGGER.warn("Skipping inaccessible path: " + file, exc);
                return FileVisitResult.CONTINUE;
            }
        });
        return normalized;
    }

    /**
     * Returns true if the file name ends with a known H2 database extension.
     */
    static boolean isH2Database(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        // temp files of an unfinished import are not databases
        if (name.contains(DatabaseImporter.TEMP_SUFFIX)) return false;
        return name.endsWith(MV_DB_SUFFIX) || name.endsWith(H2_DB_SUFFIX);
    }
}
