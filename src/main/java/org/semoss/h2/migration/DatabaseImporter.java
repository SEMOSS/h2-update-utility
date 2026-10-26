package org.semoss.h2.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Imports a SQL script into a new H2 2.x database using RUNSCRIPT FROM.
 * Uses an isolated classloader to load H2 2.4.240.
 *
 * <p>The target URL is opened with a deliberate set of 2.x compatibility flags:
 * <ul>
 *   <li>{@code MODE=LEGACY} — the single highest-leverage change. Covers
 *       {@code GROUP_CONCAT}, {@code CASEWHEN(…)}, boolean/int flex, {@code MERGE INTO … KEY},
 *       {@code IDENTITY}-as-column-type, and {@code AUTO_INCREMENT} (Categories D, F).</li>
 *   <li>{@code NON_KEYWORDS=…} — re-allows the H2 2.x reserved words that customer
 *       schemas still use as plain column/table names ({@code VALUE}, {@code KEY},
 *       {@code TOP}, etc.) (Category B). {@code ROW} cannot be suppressed here — the
 *       {@link ScriptRewriter} quotes it into {@code "ROW"} instead.</li>
 *   <li>{@code DEFAULT_NULL_ORDERING=LOW} — match the 1.x ordering so queries that rely
 *       on implicit null placement behave the same post-migration.</li>
 *   <li>{@code DATABASE_TO_UPPER=TRUE} — explicit about the default so the script's
 *       unquoted identifiers resolve to their 1.x-style uppercase counterparts.</li>
 * </ul>
 */
public final class DatabaseImporter {

    private static final Logger LOGGER = LogManager.getLogger(DatabaseImporter.class);

    /**
     * Reserved keywords introduced (or promoted) in H2 2.x that customer schemas
     * routinely use as plain column names. Passed via {@code NON_KEYWORDS=…} on the
     * target URL so the RUNSCRIPT import does not fail with "expected identifier"
     * on unquoted identifiers matching these tokens.
     *
     * <p>{@code ROW} is deliberately omitted — H2 2.x does not honour {@code ROW}
     * in {@code NON_KEYWORDS} (upstream h2database/h2database#3390). The
     * {@link ScriptRewriter} quotes bare {@code ROW} tokens into {@code "ROW"} instead.
     */
    public static final List<String> DEFAULT_NON_KEYWORDS = List.of(
            "VALUE", "KEY", "TOP", "RANGE", "INTERSECTS", "WINDOW", "OFFSET", "FETCH",
            "SESSION_USER", "LOCALTIME", "LOCALTIMESTAMP",
            "DAY", "MONTH", "YEAR", "HOUR", "MINUTE", "SECOND",
            "CURRENT_CATALOG", "CURRENT_PATH");

    private final IsolatedH2Driver driver;

    public DatabaseImporter(IsolatedH2Driver driver) {
        this.driver = driver;
    }

    /**
     * Build the H2 2.x target URL with the full set of migration compatibility flags.
     */
    public static String buildTargetUrl(Path dbFile) {
        String base = DatabaseExporter.stripExtension(dbFile);
        return "jdbc:h2:" + base
                + ";MODE=LEGACY"
                + ";NON_KEYWORDS=" + String.join(",", DEFAULT_NON_KEYWORDS)
                + ";DEFAULT_NULL_ORDERING=LOW"
                + ";DATABASE_TO_UPPER=TRUE";
    }

    /** The flags applied to the target URL, for reporting into the migration JSON. */
    public static List<String> targetUrlFlags() {
        return Arrays.asList(
                "MODE=LEGACY",
                "NON_KEYWORDS=" + String.join(",", DEFAULT_NON_KEYWORDS),
                "DEFAULT_NULL_ORDERING=LOW",
                "DATABASE_TO_UPPER=TRUE");
    }

    /**
     * Import a SQL script into a new H2 2.x database, replacing the original.
     *
     * @param dbFile     the original .mv.db file path (will be replaced)
     * @param scriptFile the exported SQL script
     * @param username   database username
     * @param password   database password
     * @throws SQLException if the import fails
     * @throws IOException  if file operations fail
     */
    public void importDatabase(Path dbFile, Path scriptFile, String username, String password)
            throws SQLException, IOException {
        // Delete the old database file so H2 2.x creates a fresh one
        Files.deleteIfExists(dbFile);

        String url = buildTargetUrl(dbFile);

        LOGGER.info("Importing " + scriptFile.getFileName() + " into new H2 " + driver.getVersion() + " database");
        LOGGER.debug("Target URL flags: " + targetUrlFlags());

        try (Connection conn = driver.connect(url, username, password);
             Statement stmt = conn.createStatement()) {
            stmt.execute("RUNSCRIPT FROM '" + scriptFile.toAbsolutePath().toString().replace("'", "''") + "' FROM_1X");
        }

        long size = Files.exists(dbFile) ? Files.size(dbFile) : 0;
        LOGGER.info("Imported into " + dbFile.getFileName() + " (" + size + " bytes)");
    }

    /**
     * Open the migrated database once with a {@code SELECT 1} to catch post-migration
     * breakage (Category I) before the utility declares victory. Returns {@code true}
     * on success; throws on failure so the caller can record context and restore backup.
     */
    public void smokeOpen(Path dbFile, String username, String password) throws SQLException {
        smokeOpenUrl(buildTargetUrl(dbFile), username, password);
    }

    /** Open through the target's actual SEMOSS runtime-style H2 URL. */
    public void smokeOpenRuntime(DatabaseTarget target) throws SQLException {
        if (target.runtimeUrl() == null || target.credentials() == null) {
            throw new SQLException("Target does not have a resolved runtime connection profile");
        }
        smokeOpenUrl(target.runtimeUrl(), target.credentials().username(), target.credentials().password());
    }

    private void smokeOpenUrl(String url, String username, String password) throws SQLException {
        try (Connection conn = driver.connect(url, username, password);
             Statement stmt = conn.createStatement()) {
            try (var rs = stmt.executeQuery("SELECT 1")) {
                if (!rs.next()) {
                    throw new SQLException("Post-migration smoke open returned no rows");
                }
            }
        }
    }
}
