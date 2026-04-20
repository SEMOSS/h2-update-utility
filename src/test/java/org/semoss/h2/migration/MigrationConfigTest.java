package org.semoss.h2.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void fromArgs_localMode_defaults() {
        String[] args = {"--db-dir=" + tempDir};
        MigrationConfig config = MigrationConfig.fromArgs(args);

        assertEquals(MigrationConfig.MigrationMode.LOCAL, config.mode());
        assertEquals(tempDir.toAbsolutePath().normalize(), config.dbDir());
        assertEquals(tempDir.resolve(".h2-backup").toAbsolutePath().normalize(), config.backupDir());
        assertFalse(config.dryRun());
        assertEquals("sa", config.defaultUsername());
        assertEquals("", config.defaultPassword());
        assertEquals("rclone", config.rclonePath());
    }

    @Test
    void fromArgs_localMode_allOptions() {
        String[] args = {
                "--db-dir=" + tempDir,
                "--backup-dir=" + tempDir.resolve("bkp"),
                "--dry-run",
                "--default-user=admin",
                "--default-password=secret"
        };
        MigrationConfig config = MigrationConfig.fromArgs(args);

        assertEquals(tempDir.resolve("bkp").toAbsolutePath().normalize(), config.backupDir());
        assertTrue(config.dryRun());
        assertEquals("admin", config.defaultUsername());
        assertEquals("secret", config.defaultPassword());
    }

    @Test
    void fromArgs_cloudMode_withS3() {
        String[] args = {
                "--mode=cloud",
                "--semoss-storage-provider=s3",
                "--s3-region=us-east-1",
                "--s3-bucket=my-bucket",
                "--s3-access-key=AKID",
                "--s3-secret-key=secret"
        };
        MigrationConfig config = MigrationConfig.fromArgs(args);

        assertEquals(MigrationConfig.MigrationMode.CLOUD, config.mode());
        assertNull(config.dbDir());
        assertEquals("s3", config.storageProvider());
        assertEquals("us-east-1", config.s3Region());
        assertEquals("my-bucket", config.s3Bucket());
        assertEquals("AKID", config.s3AccessKey());
        assertEquals("secret", config.s3SecretKey());
    }

    @Test
    void fromArgs_cloudMode_missingProvider_throws() {
        String[] args = {"--mode=cloud"};
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> MigrationConfig.fromArgs(args)
        );
        assertTrue(ex.getMessage().contains("SEMOSS_STORAGE_PROVIDER"));
    }

    @Test
    void fromArgs_localMode_missingDbDir_throws() {
        String[] args = {"--mode=local"};
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> MigrationConfig.fromArgs(args)
        );
        assertTrue(ex.getMessage().contains("--db-dir"));
    }

    @Test
    void fromArgs_modeCaseInsensitive() {
        String[] args = {"--mode=LOCAL", "--db-dir=" + tempDir};
        MigrationConfig config = MigrationConfig.fromArgs(args);
        assertEquals(MigrationConfig.MigrationMode.LOCAL, config.mode());
    }

    @Test
    void fromArgs_configFile_loadsProperties() throws IOException {
        Path propsFile = tempDir.resolve("migration.properties");
        Files.writeString(propsFile, """
                DB_DIR=%s
                DRY_RUN=true
                DEFAULT_USER=fileuser
                """.formatted(tempDir));
        String[] args = {"--config=" + propsFile};
        MigrationConfig config = MigrationConfig.fromArgs(args);

        assertTrue(config.dryRun());
        assertEquals("fileuser", config.defaultUsername());
    }

    @Test
    void fromArgs_cliOverridesConfigFile() throws IOException {
        Path propsFile = tempDir.resolve("migration.properties");
        Files.writeString(propsFile, """
                DB_DIR=%s
                DEFAULT_USER=fileuser
                """.formatted(tempDir));
        String[] args = {"--config=" + propsFile, "--default-user=cliuser"};
        MigrationConfig config = MigrationConfig.fromArgs(args);

        assertEquals("cliuser", config.defaultUsername());
    }

    @Test
    void usage_containsAllSections() {
        String usage = MigrationConfig.usage();
        assertTrue(usage.contains("--db-dir"));
        assertTrue(usage.contains("--mode"));
        assertTrue(usage.contains("--semoss-storage-provider"));
        assertTrue(usage.contains("--config"));
    }
}
