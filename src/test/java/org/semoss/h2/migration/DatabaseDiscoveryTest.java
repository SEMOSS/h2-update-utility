package org.semoss.h2.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseDiscoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void findDatabases_discoversMvDbFiles() throws IOException {
        Files.createFile(tempDir.resolve("test.mv.db"));
        Files.createFile(tempDir.resolve("ignore.txt"));

        List<Path> found = DatabaseDiscovery.findDatabases(tempDir);

        assertEquals(1, found.size());
        assertEquals("test.mv.db", found.get(0).getFileName().toString());
    }

    @Test
    void findDatabases_discoversH2DbFiles() throws IOException {
        Files.createFile(tempDir.resolve("legacy.h2.db"));

        List<Path> found = DatabaseDiscovery.findDatabases(tempDir);

        assertEquals(1, found.size());
        assertEquals("legacy.h2.db", found.get(0).getFileName().toString());
    }

    @Test
    void findDatabases_recursesIntoSubdirectories() throws IOException {
        Path sub = tempDir.resolve("engines/security");
        Files.createDirectories(sub);
        Files.createFile(sub.resolve("database.mv.db"));
        Files.createFile(tempDir.resolve("master.mv.db"));

        List<Path> found = DatabaseDiscovery.findDatabases(tempDir);

        assertEquals(2, found.size());
    }

    @Test
    void findDatabases_returnsEmptyForNoMatches() throws IOException {
        Files.createFile(tempDir.resolve("readme.txt"));

        List<Path> found = DatabaseDiscovery.findDatabases(tempDir);

        assertTrue(found.isEmpty());
    }

    @Test
    void findDatabases_returnsAbsoluteNormalizedPaths() throws IOException {
        Files.createFile(tempDir.resolve("db.mv.db"));

        List<Path> found = DatabaseDiscovery.findDatabases(tempDir);

        Path result = found.get(0);
        assertTrue(result.isAbsolute());
        assertEquals(result, result.normalize());
    }

    @Test
    void findDatabases_nullDir_throws() {
        assertThrows(IllegalArgumentException.class, () -> DatabaseDiscovery.findDatabases(null));
    }

    @Test
    void findDatabases_nonDirectory_throws() throws IOException {
        Path file = tempDir.resolve("notadir.txt");
        Files.createFile(file);

        assertThrows(IllegalArgumentException.class, () -> DatabaseDiscovery.findDatabases(file));
    }

    @Test
    void findDatabases_emptyDirectory_returnsEmpty() throws IOException {
        Path empty = tempDir.resolve("empty");
        Files.createDirectories(empty);

        assertTrue(DatabaseDiscovery.findDatabases(empty).isEmpty());
    }

    @Test
    void findDatabases_caseInsensitiveExtension() throws IOException {
        Files.createFile(tempDir.resolve("UPPER.MV.DB"));
        Files.createFile(tempDir.resolve("Mixed.H2.Db"));

        List<Path> found = DatabaseDiscovery.findDatabases(tempDir);

        assertEquals(2, found.size());
    }

    @Test
    void isH2Database_matchesExpectedExtensions() {
        assertTrue(DatabaseDiscovery.isH2Database(Path.of("test.mv.db")));
        assertTrue(DatabaseDiscovery.isH2Database(Path.of("test.h2.db")));
        assertFalse(DatabaseDiscovery.isH2Database(Path.of("test.sqlite")));
        assertFalse(DatabaseDiscovery.isH2Database(Path.of("test.db")));
    }
}
