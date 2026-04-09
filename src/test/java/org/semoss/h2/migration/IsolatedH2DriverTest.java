package org.semoss.h2.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsolatedH2DriverTest {

    @TempDir
    Path tempDir;

    @Test
    void load_oldVersion_andConnect() throws Exception {
        try (IsolatedH2Driver driver = IsolatedH2Driver.load("1.4.200")) {
            assertEquals("1.4.200", driver.getVersion());

            String url = "jdbc:h2:" + tempDir.resolve("old_test");
            try (Connection conn = driver.connect(url, "sa", "")) {
                assertFalse(conn.isClosed());
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT 1")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                }
            }
        }
    }

    @Test
    void load_newVersion_andConnect() throws Exception {
        try (IsolatedH2Driver driver = IsolatedH2Driver.load("2.4.240")) {
            assertEquals("2.4.240", driver.getVersion());

            String url = "jdbc:h2:" + tempDir.resolve("new_test");
            try (Connection conn = driver.connect(url, "sa", "")) {
                assertFalse(conn.isClosed());
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT 1")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                }
            }
        }
    }

    @Test
    void load_bothVersionsSimultaneously() throws Exception {
        try (IsolatedH2Driver oldDriver = IsolatedH2Driver.load("1.4.200");
             IsolatedH2Driver newDriver = IsolatedH2Driver.load("2.4.240")) {

            String oldUrl = "jdbc:h2:" + tempDir.resolve("simul_old");
            String newUrl = "jdbc:h2:" + tempDir.resolve("simul_new");

            try (Connection oldConn = oldDriver.connect(oldUrl, "sa", "");
                 Connection newConn = newDriver.connect(newUrl, "sa", "")) {
                assertFalse(oldConn.isClosed());
                assertFalse(newConn.isClosed());
            }
        }
    }

    @Test
    void load_invalidVersion_throws() {
        assertThrows(IOException.class, () -> IsolatedH2Driver.load("99.99.99"));
    }

    @Test
    void close_isIdempotent() throws Exception {
        IsolatedH2Driver driver = IsolatedH2Driver.load("1.4.200");
        driver.close();
        assertDoesNotThrow(driver::close);
    }
}
