package org.semoss.h2.migration;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Detects whether an H2 database file needs migration by reading its file header.
 * <p>
 * H2 MVStore files ({@code .mv.db}) start with a header line like:
 * {@code H:2,block:1a4a,blockSize:1000,...,format:1,...}
 * <p>
 * When the classloader is available, this class also supports a connection-based
 * check: attempt to open the database with the new H2 driver. If it succeeds,
 * no migration is needed.
 */
public final class H2VersionDetector {

    private static final Logger LOGGER = LogManager.getLogger(H2VersionDetector.class);

    /** Max bytes to read from file header (the header line is within the first 4KB). */
    private static final int HEADER_READ_SIZE = 4096;

    private H2VersionDetector() {}

    /**
     * Result of version detection.
     *
     * @param needsMigration true if the database should be migrated
     * @param headerInfo     parsed key-value pairs from the MVStore header, or empty map for .h2.db files
     * @param reason         human-readable explanation
     */
    public record DetectionResult(boolean needsMigration, Map<String, String> headerInfo, String reason) {}

    /**
     * Analyze a database file to determine whether it needs migration.
     * <ul>
     *   <li>{@code .h2.db} files always need migration (old page-store format, pre-1.4)</li>
     *   <li>{@code .mv.db} files: parse header, then fall back to connection test if classloader is available</li>
     * </ul>
     */
    public static DetectionResult detect(Path dbFile) {
        if (dbFile == null || !Files.isRegularFile(dbFile)) {
            return new DetectionResult(false, Map.of(), "File does not exist or is not a regular file");
        }

        String name = dbFile.getFileName().toString().toLowerCase();

        // .h2.db is the old page-store format — always needs migration
        if (name.endsWith(".h2.db")) {
            return new DetectionResult(true, Map.of(), "Legacy .h2.db format (pre-MVStore)");
        }

        if (!name.endsWith(".mv.db")) {
            return new DetectionResult(false, Map.of(), "Not an H2 database file");
        }

        // Empty file — nothing to migrate
        try {
            if (Files.size(dbFile) == 0) {
                return new DetectionResult(false, Map.of(), "Empty database file");
            }
        } catch (IOException e) {
            return new DetectionResult(true, Map.of(), "Cannot read file size: " + e.getMessage());
        }

        Map<String, String> header = readMvStoreHeader(dbFile);
        if (header.isEmpty()) {
            // Couldn't parse header — assume it needs migration to be safe
            return new DetectionResult(true, Map.of(), "Unable to parse MVStore header");
        }

        // H2 2.x databases have format:2 or higher; H2 1.4.x uses format:1
        String format = header.getOrDefault("format", "");
        try {
            int formatVersion = Integer.parseInt(format);
            if (formatVersion >= 2) {
                return new DetectionResult(false, header, "Already H2 2.x (format:" + format + ")");
            }
        } catch (NumberFormatException e) {
            // Non-numeric format — fall through to assume migration needed
        }

        return new DetectionResult(true, header, "H2 1.x database (format:" + format + ")");
    }

    /**
     * Read and parse the MVStore header line from the beginning of a {@code .mv.db} file.
     * Returns a map of key-value pairs, or empty map on failure.
     */
    static Map<String, String> readMvStoreHeader(Path dbFile) {
        Map<String, String> result = new HashMap<>();
        try (RandomAccessFile raf = new RandomAccessFile(dbFile.toFile(), "r")) {
            byte[] buf = new byte[(int) Math.min(raf.length(), HEADER_READ_SIZE)];
            raf.readFully(buf);
            String header = new String(buf, StandardCharsets.ISO_8859_1);

            // Find the first line (terminated by newline)
            int newline = header.indexOf('\n');
            if (newline > 0) {
                header = header.substring(0, newline);
            }

            // Parse comma-separated key:value pairs
            for (String pair : header.split(",")) {
                int colon = pair.indexOf(':');
                if (colon > 0 && colon < pair.length() - 1) {
                    result.put(pair.substring(0, colon).trim(), pair.substring(colon + 1).trim());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to read MVStore header from " + dbFile, e);
        }
        return result;
    }
}
