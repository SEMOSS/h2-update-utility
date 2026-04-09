package org.semoss.h2.migration;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads an H2 JDBC driver from an embedded JAR resource using an isolated
 * {@link URLClassLoader}, preventing classpath conflicts between H2 versions.
 * <p>
 * Each instance extracts the embedded JAR to a temp file (cleaned up on close)
 * and creates a private classloader that only sees that JAR.
 */
public final class IsolatedH2Driver implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(IsolatedH2Driver.class.getName());
    private static final String DRIVER_CLASS = "org.h2.Driver";

    private final String version;
    private final URLClassLoader classLoader;
    private final Driver driver;
    private final Path tempJar;

    private IsolatedH2Driver(String version, URLClassLoader classLoader, Driver driver, Path tempJar) {
        this.version = version;
        this.classLoader = classLoader;
        this.driver = driver;
        this.tempJar = tempJar;
    }

    /**
     * Load the H2 driver for the given version from the embedded {@code lib/} resources.
     *
     * @param version e.g. "1.4.200" or "2.4.240"
     * @return an isolated driver instance
     * @throws IOException if the JAR cannot be extracted
     * @throws RuntimeException if the driver class cannot be loaded
     */
    public static IsolatedH2Driver load(String version) throws IOException {
        String resourcePath = "lib/h2-" + version + ".jar";
        LOGGER.info("Loading isolated H2 driver v" + version + " from " + resourcePath);

        // Extract embedded JAR to a temp file so URLClassLoader can read it
        Path tempJar = Files.createTempFile("h2-" + version + "-", ".jar");
        try (InputStream in = IsolatedH2Driver.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                Files.deleteIfExists(tempJar);
                throw new IOException("Embedded H2 JAR not found: " + resourcePath);
            }
            Files.copy(in, tempJar, StandardCopyOption.REPLACE_EXISTING);
        }

        // Create isolated classloader with platform classloader as parent
        // (provides java.sql.* but not the application classpath)
        URL jarUrl = tempJar.toUri().toURL();
        URLClassLoader cl = new URLClassLoader(new URL[]{jarUrl}, ClassLoader.getPlatformClassLoader());

        try {
            Class<?> driverClass = cl.loadClass(DRIVER_CLASS);
            Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
            LOGGER.info("H2 v" + version + " driver loaded (accepts jdbc:h2: = " + driver.acceptsURL("jdbc:h2:test") + ")");
            return new IsolatedH2Driver(version, cl, driver, tempJar);
        } catch (Exception e) {
            cl.close();
            Files.deleteIfExists(tempJar);
            throw new RuntimeException("Failed to load H2 driver v" + version, e);
        }
    }

    /**
     * Create a JDBC connection using this isolated H2 driver.
     *
     * @param url      JDBC URL (e.g. {@code jdbc:h2:/path/to/db})
     * @param username database username
     * @param password database password
     * @return a live JDBC connection
     * @throws SQLException if the connection fails
     */
    public Connection connect(String url, String username, String password) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        Connection conn = driver.connect(url, props);
        if (conn == null) {
            throw new SQLException("Driver returned null for URL: " + url);
        }
        return conn;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public void close() {
        try {
            classLoader.close();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error closing classloader for H2 v" + version, e);
        }
        try {
            Files.deleteIfExists(tempJar);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error deleting temp JAR for H2 v" + version, e);
        }
    }
}
