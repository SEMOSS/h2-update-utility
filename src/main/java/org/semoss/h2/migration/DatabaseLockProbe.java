package org.semoss.h2.migration;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Detects H2 files that another process (for example a running SEMOSS) still holds open. */
public final class DatabaseLockProbe {

    private DatabaseLockProbe() {}

    /** Returns why the file looks in use, or {@code null} when it is free. */
    public static String lockReason(Path dbFile) {
        // page-store databases (and FILE_LOCK=FILE) keep a .lock.db next to the file
        Path lockFile = Path.of(DatabaseExporter.stripExtension(dbFile) + ".lock.db");
        if (Files.exists(lockFile)) {
            return "H2 lock file " + lockFile.getFileName() + " exists (stop SEMOSS; if nothing uses the "
                    + "database the lock file is stale)";
        }
        // MVStore holds an OS file lock while open
        try (FileChannel channel = FileChannel.open(dbFile, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            if (lock == null) return "Database file is locked by another process (stop SEMOSS first)";
            lock.release();
            return null;
        } catch (OverlappingFileLockException e) {
            return "Database file is locked by this process";
        } catch (IOException e) {
            return "Database file cannot be opened for writing: " + e.getMessage();
        }
    }
}
