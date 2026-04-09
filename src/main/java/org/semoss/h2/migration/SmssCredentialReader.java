package org.semoss.h2.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads USERNAME and PASSWORD from Semoss {@code .smss} configuration files.
 * <p>
 * SMSS files use whitespace-delimited key/value pairs (tabs are typical):
 * <pre>
 * USERNAME    sa
 * PASSWORD    secret
 * </pre>
 * PASSWORD may be omitted or empty.
 */
public final class SmssCredentialReader {

    private static final Logger LOGGER = Logger.getLogger(SmssCredentialReader.class.getName());

    private SmssCredentialReader() {}

    public record Credentials(String username, String password) {}

    /**
     * Locate the {@code .smss} file adjacent to the given database file and
     * extract USERNAME / PASSWORD. The lookup strategy tries multiple patterns
     * because Semoss uses two naming conventions:
     * <ol>
     *   <li>Same directory, same base name: {@code themes/themes.smss} for {@code themes/database.mv.db}</li>
     *   <li>Parent directory with engine alias: {@code db/AnimalDb__uuid.smss} for {@code db/AnimalDb__uuid/database.mv.db}</li>
     * </ol>
     *
     * @param dbFile    path to the {@code .mv.db} or {@code .h2.db} file
     * @param fallbackUser default username if not found
     * @param fallbackPass default password if not found
     * @return credentials parsed from the nearest .smss, or fallbacks
     */
    public static Credentials readCredentials(Path dbFile, String fallbackUser, String fallbackPass) {
        Path smssFile = findSmssFile(dbFile);
        if (smssFile == null) {
            LOGGER.fine("No .smss file found for " + dbFile + ", using defaults");
            return new Credentials(fallbackUser, fallbackPass);
        }

        return parseSmssFile(smssFile, fallbackUser, fallbackPass);
    }

    /**
     * Look for an .smss file using Semoss's naming conventions.
     */
    static Path findSmssFile(Path dbFile) {
        Path parent = dbFile.getParent();
        if (parent == null) return null;

        // Pattern 1: .smss file in the parent's parent directory named after the folder
        // e.g. db/themes/database.mv.db → db/themes.smss
        Path grandParent = parent.getParent();
        if (grandParent != null) {
            String folderName = parent.getFileName().toString();
            Path candidate = grandParent.resolve(folderName + ".smss");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }

            // Pattern 2: folder name might have alias__uuid format
            // e.g. db/AnimalDb__uuid/database.mv.db → db/AnimalDb__uuid.smss
            // Already covered by pattern 1 since folderName = "AnimalDb__uuid"
        }

        // Pattern 3: .smss file in the same directory as the db file
        try (var stream = Files.list(parent)) {
            return stream
                    .filter(p -> p.toString().toLowerCase().endsWith(".smss"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error listing directory for .smss: " + parent, e);
            return null;
        }
    }

    /**
     * Parse a .smss file for USERNAME and PASSWORD keys.
     */
    static Credentials parseSmssFile(Path smssFile, String fallbackUser, String fallbackPass) {
        String username = fallbackUser;
        String password = fallbackPass;

        try {
            List<String> lines = Files.readAllLines(smssFile);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                // Split on first whitespace run (tab or spaces)
                String[] parts = trimmed.split("\\s+", 2);
                if (parts.length == 0) continue;

                String key = parts[0].toUpperCase();
                String value = parts.length > 1 ? parts[1].trim() : "";

                if ("USERNAME".equals(key) && !value.isEmpty()) {
                    username = value;
                } else if ("PASSWORD".equals(key) && !value.isEmpty()) {
                    password = value;
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error reading .smss file: " + smssFile, e);
        }

        LOGGER.fine("Credentials from " + smssFile + ": user=" + username);
        return new Credentials(username, password);
    }
}
