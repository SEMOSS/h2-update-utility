package org.semoss.h2.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Exports an H2 1.x database to a SQL script using the SCRIPT TO command.
 * Uses an isolated classloader to load H2 1.4.200.
 */
public final class DatabaseExporter {

    private static final Logger LOGGER = LogManager.getLogger(DatabaseExporter.class);

    private final IsolatedH2Driver driver;

    public DatabaseExporter(IsolatedH2Driver driver) {
        this.driver = driver;
    }

    /**
     * Export the given H2 database to a SQL script file.
     *
     * @param dbFile   path to the .mv.db file (without extension in JDBC URL)
     * @param username database username
     * @param password database password
     * @return path to the generated SQL script
     * @throws SQLException if the export query fails
     * @throws IOException  if the output path cannot be prepared
     */
    public Path export(Path dbFile, String username, String password) throws SQLException, IOException {
        Path scriptFile = dbFile.resolveSibling(dbFile.getFileName().toString().replace(".mv.db", "") + "_export.sql");
        return export(dbFile, scriptFile, username, password);
    }

    /**
     * Export to an explicit work/backup location so migration never creates
     * temporary SQL files beside SEMOSS project assets.
     */
    public Path export(Path dbFile, Path scriptFile, String username, String password)
            throws SQLException, IOException {
        Path normalizedScript = scriptFile.toAbsolutePath().normalize();
        if (normalizedScript.getParent() != null) {
            Files.createDirectories(normalizedScript.getParent());
        }

        String dbPath = stripExtension(dbFile);
        // Use ;ACCESS_MODE_DATA=r to open in read-only mode, preventing lock conflicts
        String url = "jdbc:h2:" + dbPath + ";ACCESS_MODE_DATA=r";

        LOGGER.info("Exporting " + dbFile + " using H2 " + driver.getVersion());

        try (Connection conn = driver.connect(url, username, password);
             Statement stmt = conn.createStatement()) {
            // SCRIPT TO exports all DDL and DML to a SQL file
            stmt.execute("SCRIPT TO '" + normalizedScript.toString().replace("'", "''") + "'");
        }

        long size = Files.size(normalizedScript);
        LOGGER.info("Exported " + dbFile.getFileName() + " -> " + normalizedScript.getFileName()
                + " (" + size + " bytes)");
        return normalizedScript;
    }

    /**
     * Strip .mv.db or .h2.db extension from the file path for the JDBC URL.
     */
    static String stripExtension(Path dbFile) {
        String abs = dbFile.toAbsolutePath().toString();
        if (abs.endsWith(".mv.db")) {
            return abs.substring(0, abs.length() - ".mv.db".length());
        }
        if (abs.endsWith(".h2.db")) {
            return abs.substring(0, abs.length() - ".h2.db".length());
        }
        return abs;
    }
}
