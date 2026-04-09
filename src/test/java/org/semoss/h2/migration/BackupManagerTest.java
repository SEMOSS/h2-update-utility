package org.semoss.h2.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void backup_copiesFilePreservingRelativePath() throws IOException {
        Path baseDir = tempDir.resolve("db");
        Path subDir = baseDir.resolve("engines").resolve("myEngine");
        Files.createDirectories(subDir);
        Path dbFile = subDir.resolve("test.mv.db");
        Files.writeString(dbFile, "data");

        Path backupRoot = tempDir.resolve("backups");
        BackupManager mgr = new BackupManager(backupRoot);

        Path backed = mgr.backup(dbFile, baseDir, "run1");

        assertTrue(Files.exists(backed));
        assertEquals("data", Files.readString(backed));
        assertTrue(backed.toString().contains("run1"));
        assertTrue(backed.toString().contains("engines"));
    }

    @Test
    void restore_overwritesOriginal() throws IOException {
        Path original = tempDir.resolve("original.mv.db");
        Files.writeString(original, "old_data");

        Path backupFile = tempDir.resolve("backup.mv.db");
        Files.writeString(backupFile, "backed_up_data");

        BackupManager mgr = new BackupManager(tempDir);
        mgr.restore(backupFile, original);

        assertEquals("backed_up_data", Files.readString(original));
    }

    @Test
    void backup_createsDirectoriesOnDemand() throws IOException {
        Path baseDir = tempDir.resolve("base");
        Files.createDirectories(baseDir);
        Path dbFile = baseDir.resolve("test.mv.db");
        Files.writeString(dbFile, "content");

        Path backupRoot = tempDir.resolve("deep/nested/backups");
        BackupManager mgr = new BackupManager(backupRoot);

        Path backed = mgr.backup(dbFile, baseDir, "run2");
        assertTrue(Files.exists(backed));
    }

    @Test
    void generateRunLabel_containsMigrationPrefix() {
        String label = BackupManager.generateRunLabel();
        assertTrue(label.startsWith("migration_"));
        assertTrue(label.length() > 20);
    }

    @Test
    void getBackupRoot_returnsConfiguredPath() {
        Path root = tempDir.resolve("my-backups");
        BackupManager mgr = new BackupManager(root);
        assertEquals(root, mgr.getBackupRoot());
    }
}
