package org.semoss.h2.migration;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

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
        if (rootDir == null) {
            throw new IllegalArgumentException("rootDir must not be null");
        }
        Path normalized = rootDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("Not a directory: " + normalized);
        }

        List<Path> results = new ArrayList<>();

        Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (isH2Database(file)) {
                    results.add(file.toAbsolutePath().normalize());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                LOGGER.warn("Skipping inaccessible path: " + file, exc);
                return FileVisitResult.CONTINUE;
            }
        });

        LOGGER.info("Discovered " + results.size() + " H2 database(s) under " + normalized);
        return results;
    }

    /**
     * Returns true if the file name ends with a known H2 database extension.
     */
    static boolean isH2Database(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(MV_DB_SUFFIX) || name.endsWith(H2_DB_SUFFIX);
    }
}
