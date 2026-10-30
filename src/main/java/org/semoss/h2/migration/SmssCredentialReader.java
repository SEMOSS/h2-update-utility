package org.semoss.h2.migration;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Reads USERNAME and PASSWORD from Semoss {@code .smss} configuration files.
 * <p>
 * SMSS files are Java properties files (tabs are the typical separator):
 * <pre>
 * USERNAME    sa
 * PASSWORD    secret
 * </pre>
 * PASSWORD may be omitted or empty. Files are parsed like SEMOSS does it:
 * {@link Properties#load(InputStream)} (ISO-8859-1, backslash escapes), values trimmed.
 */
public final class SmssCredentialReader {

    private static final Logger LOGGER = LogManager.getLogger(SmssCredentialReader.class);

    private SmssCredentialReader() {}

    public record Credentials(String username, String password) {
        @Override
        public String toString() {
            return "Credentials[username=" + username + ", password=<redacted>]";
        }
    }

    /** Parsed non-secret connection metadata plus the credentials used for migration. */
    public record SmssMetadata(
            Path path,
            Credentials credentials,
            String backend,
            boolean confirmedH2,
            String connectionUrl,
            String rdbmsType,
            String driver
    ) {}

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
            LOGGER.debug("No .smss file found for " + dbFile + ", using defaults");
            return new Credentials(fallbackUser, fallbackPass);
        }

        return parseSmssFile(smssFile, fallbackUser, fallbackPass);
    }

    /**
     * Look for an .smss file using Semoss's naming conventions.
     */
    static Path findSmssFile(Path dbFile) {
        List<Path> candidates = findSmssFiles(dbFile);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * Return every nearby SMSS candidate in deterministic preference order.
     * H2-specific fallback profiles are considered before the primary profile.
     */
    static List<Path> findSmssFiles(Path dbFile) {
        Path parent = dbFile.getParent();
        if (parent == null) return List.of();

        Set<Path> candidates = new LinkedHashSet<>();

        Path grandParent = parent.getParent();
        if (grandParent != null) {
            String folderName = parent.getFileName().toString();
            addIfRegular(candidates, grandParent.resolve(folderName + "_h2.smss"));
            addIfRegular(candidates, grandParent.resolve(folderName + ".smss"));

            // Some local SEMOSS engines keep their profiles in db/SMSS rather
            // than adjacent to the engine directory (for example,
            // db/form_builder_engine with db/SMSS/form_builder_engine.smss).
            Path smssDirectory = grandParent.resolve("SMSS");
            addIfRegular(candidates, smssDirectory.resolve(folderName + "_h2.smss"));
            addIfRegular(candidates, smssDirectory.resolve(folderName + ".smss"));
        }

        try (var stream = Files.list(parent)) {
            List<Path> local = stream
                    .filter(p -> p.toString().toLowerCase().endsWith(".smss"))
                    .sorted(Comparator
                            .comparing((Path p) -> !p.getFileName().toString().toLowerCase().contains("_h2.smss"))
                            .thenComparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();
            candidates.addAll(local);
        } catch (IOException e) {
            LOGGER.warn("Error listing directory for .smss: " + parent, e);
        }
        return List.copyOf(candidates);
    }

    private static void addIfRegular(Set<Path> candidates, Path candidate) {
        if (Files.isRegularFile(candidate)) candidates.add(candidate.toAbsolutePath().normalize());
    }

    /**
     * Parse a .smss file for USERNAME and PASSWORD keys.
     */
    static Credentials parseSmssFile(Path smssFile, String fallbackUser, String fallbackPass) {
        String username = fallbackUser;
        String password = fallbackPass;

        Map<String, String> values = loadValues(smssFile);
        if (!values.getOrDefault("USERNAME", "").isEmpty()) username = values.get("USERNAME");
        if (!values.getOrDefault("PASSWORD", "").isEmpty()) password = values.get("PASSWORD");

        LOGGER.debug("Credentials from " + smssFile + ": user=" + username);
        return new Credentials(username, password);
    }

    /**
     * Parse credentials and backend indicators from an SMSS file. An explicit
     * blank PASSWORD is preserved as the empty H2 password.
     */
    static SmssMetadata readMetadata(Path smssFile, String fallbackUser, String fallbackPass) {
        Map<String, String> values = loadValues(smssFile);

        String username = values.getOrDefault("USERNAME", fallbackUser);
        if (username == null || username.isBlank()) username = fallbackUser;
        String password = values.containsKey("PASSWORD") ? values.get("PASSWORD") : fallbackPass;
        String rdbmsType = firstNonBlank(values.get("RDBMS_TYPE"), values.get("DBMS_TYPE"));
        String driver = values.getOrDefault("DRIVER", "");
        String connectionUrl = values.getOrDefault("CONNECTION_URL", "");
        String backend = detectBackend(rdbmsType, driver, connectionUrl);

        return new SmssMetadata(
                smssFile.toAbsolutePath().normalize(),
                new Credentials(username, password == null ? "" : password),
                backend,
                "H2".equals(backend),
                connectionUrl,
                rdbmsType,
                driver);
    }

    /** Load an SMSS file the way SEMOSS does; keys are upper-cased, values trimmed. */
    static Map<String, String> loadValues(Path smssFile) {
        try (InputStream in = Files.newInputStream(smssFile)) {
            Properties props = new Properties();
            props.load(in);
            return upperCaseKeys(props);
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.warn("Error reading .smss file: " + smssFile, e);
            return Map.of();
        }
    }

    /** Parse properties text (for example one SMSS line); same rules as {@link #loadValues}. */
    static Map<String, String> parseValues(String text) throws IOException {
        Properties props = new Properties();
        try (Reader reader = new StringReader(text)) {
            props.load(reader);
        }
        return upperCaseKeys(props);
    }

    private static Map<String, String> upperCaseKeys(Properties props) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            values.put(name.toUpperCase(Locale.ROOT), props.getProperty(name).trim());
        }
        return values;
    }

    private static String detectBackend(String rdbmsType, String driver, String connectionUrl) {
        Set<String> indicators = new LinkedHashSet<>();
        addBackendIndicator(indicators, rdbmsType);
        addBackendIndicator(indicators, driver);
        addBackendIndicator(indicators, connectionUrl);
        if (indicators.isEmpty()) return "UNKNOWN";
        if (indicators.size() == 1) return indicators.iterator().next();
        return "CONFLICTING:" + String.join(",", new ArrayList<>(indicators));
    }

    private static void addBackendIndicator(Set<String> indicators, String value) {
        if (value == null || value.isBlank()) return;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("postgres")) {
            indicators.add("POSTGRES");
        } else if (lower.contains("sqlite")) {
            indicators.add("SQLITE");
        } else if (lower.contains("h2_db") || lower.contains("org.h2")
                || lower.startsWith("jdbc:h2:") || "h2".equals(lower)) {
            indicators.add("H2");
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second == null ? "" : second;
    }
}
