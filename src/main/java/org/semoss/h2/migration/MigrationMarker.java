package org.semoss.h2.migration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * In-progress marker written next to a database just before its migrated file replaces
 * the original, and removed once the migration is verified or rolled back. A marker
 * found on a later run means that run was interrupted: the original is restored from
 * the recorded backup and migrated again instead of being skipped as already 2.x.
 */
public record MigrationMarker(Path markerFile, Path sourceFile, Path backupFile) {

    static final String SUFFIX = ".h2-migrating";

    /** Marker path for a database file (shared by its .mv.db and .h2.db forms). */
    static Path pathFor(Path dbFile) {
        return Path.of(DatabaseExporter.stripExtension(dbFile) + SUFFIX);
    }

    static boolean isMarker(Path file) {
        return file.getFileName().toString().endsWith(SUFFIX);
    }

    /** Write the marker atomically so a crash never leaves a half-written one. */
    static MigrationMarker write(Path sourceFile, Path backupFile) throws IOException {
        Path marker = pathFor(sourceFile);
        Properties props = new Properties();
        props.setProperty("SOURCE", sourceFile.getFileName().toString());
        props.setProperty("BACKUP", backupFile.toAbsolutePath().normalize().toString());
        Path temp = marker.resolveSibling(marker.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            props.store(out, "h2-migration-service: migration in progress; do not delete");
        }
        Files.move(temp, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return new MigrationMarker(marker, sourceFile, backupFile);
    }

    static MigrationMarker read(Path markerFile) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(markerFile)) {
            props.load(in);
        }
        String source = props.getProperty("SOURCE", "");
        String backup = props.getProperty("BACKUP", "");
        if (source.isBlank() || backup.isBlank() || source.contains("/") || source.contains("\\")) {
            throw new IOException("Migration marker is incomplete: " + markerFile);
        }
        return new MigrationMarker(markerFile, markerFile.resolveSibling(source), Path.of(backup));
    }

    void delete() throws IOException {
        Files.deleteIfExists(markerFile);
    }
}
