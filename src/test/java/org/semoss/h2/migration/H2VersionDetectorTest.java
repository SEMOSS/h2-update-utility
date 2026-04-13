package org.semoss.h2.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2VersionDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    void detect_h2dbFile_alwaysNeedsMigration() throws IOException {
        Path file = tempDir.resolve("old.h2.db");
        Files.writeString(file, "some content");

        var result = H2VersionDetector.detect(file);

        assertTrue(result.needsMigration());
        assertTrue(result.reason().contains(".h2.db"));
    }

    @Test
    void detect_mvdbFormat1_needsMigration() throws IOException {
        Path file = tempDir.resolve("test.mv.db");
        writeMvStoreHeader(file, 1);

        var result = H2VersionDetector.detect(file);

        assertTrue(result.needsMigration());
        assertTrue(result.reason().contains("format:1"));
        assertEquals("1", result.headerInfo().get("format"));
    }

    @Test
    void detect_mvdbFormat2_noMigration() throws IOException {
        Path file = tempDir.resolve("test.mv.db");
        writeMvStoreHeader(file, 2);

        var result = H2VersionDetector.detect(file);

        assertFalse(result.needsMigration());
        assertTrue(result.reason().contains("format:2"));
    }

    @Test
    void detect_emptyFile_noMigration() throws IOException {
        Path file = tempDir.resolve("empty.mv.db");
        Files.createFile(file);

        var result = H2VersionDetector.detect(file);

        assertFalse(result.needsMigration());
        assertTrue(result.reason().contains("Empty"));
    }

    @Test
    void detect_nonExistentFile_noMigration() {
        var result = H2VersionDetector.detect(tempDir.resolve("nope.mv.db"));

        assertFalse(result.needsMigration());
    }

    private void writeMvStoreHeader(Path file, int format) throws IOException {
        String header = "H:2,block:1,blockSize:1000,chunk:1,created:1,format:" + format + ",version:1,fletcher:abc\n";
        byte[] content = new byte[4096];
        byte[] headerBytes = header.getBytes();
        System.arraycopy(headerBytes, 0, content, 0, headerBytes.length);
        Files.write(file, content);
    }
}
