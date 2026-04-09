package org.semoss.h2.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Imports a SQL script into a new H2 2.x database using RUNSCRIPT FROM.
 * Uses an isolated classloader to load H2 2.4.240.
 */
public final class DatabaseImporter {

    private static final Logger LOGGER = Logger.getLogger(DatabaseImporter.class.getName());

    private final IsolatedH2Driver driver;

    public DatabaseImporter(IsolatedH2Driver driver) {
        this.driver = driver;
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

        String dbPath = DatabaseExporter.stripExtension(dbFile);
        String url = "jdbc:h2:" + dbPath;

        LOGGER.info("Importing " + scriptFile.getFileName() + " into new H2 " + driver.getVersion() + " database");

        try (Connection conn = driver.connect(url, username, password);
             Statement stmt = conn.createStatement()) {
            stmt.execute("RUNSCRIPT FROM '" + scriptFile.toAbsolutePath().toString().replace("'", "''") + "'");
        }

        long size = Files.exists(dbFile) ? Files.size(dbFile) : 0;
        LOGGER.info("Imported into " + dbFile.getFileName() + " (" + size + " bytes)");
    }
}
