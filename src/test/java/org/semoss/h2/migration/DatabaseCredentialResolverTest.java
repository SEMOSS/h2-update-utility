package org.semoss.h2.migration;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semoss.h2.migration.DatabaseTarget.CredentialSource;
import org.semoss.h2.migration.DatabaseTarget.Disposition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseCredentialResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void persistedFrameUsesH2FrameProfileAndIgnoresPostgresSmss() throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Path version = projectRoot.resolve("App__id/app_root/version/insight-id");
        Files.createDirectories(version);
        Path dbFile = Files.createFile(version.resolve("H2_Store_ABC.mv.db"));
        Files.writeString(version.resolve("wrong.smss"), postgresSmss());

        DatabaseTarget target = DatabaseClassifier.classify(projectRoot, dbFile);
        DatabaseTarget resolved = DatabaseCredentialResolver.resolve(target, config(projectRoot));

        assertEquals(CredentialSource.H2_FRAME_RUNTIME, resolved.credentialSource());
        assertEquals("sa", resolved.credentials().username());
        assertEquals("", resolved.credentials().password());
        assertNull(resolved.companionSmss());
        assertTrue(resolved.runtimeUrl().startsWith("jdbc:h2:nio:"));
        assertTrue(resolved.runtimeUrl().endsWith(";CACHE_SIZE=65536;LOCK_MODE=1"));
    }

    @Test
    void primaryDatabaseRejectsPostgresOnlyProfile() throws Exception {
        Path dbRoot = tempDir.resolve("db");
        Path engine = dbRoot.resolve("Customer__id");
        Files.createDirectories(engine);
        Path dbFile = Files.createFile(engine.resolve("database.mv.db"));
        Files.writeString(dbRoot.resolve("Customer__id.smss"), postgresSmss());

        DatabaseTarget resolved = DatabaseCredentialResolver.resolve(
                DatabaseClassifier.classify(dbRoot, dbFile), config(dbRoot));

        assertEquals(Disposition.MANUAL_REVIEW, resolved.disposition());
        assertEquals(CredentialSource.UNRESOLVED, resolved.credentialSource());
        assertTrue(resolved.resolutionError().contains("No confirmed H2 SMSS"));
    }

    @Test
    void systemDatabaseMigratesRetainedH2FileWhenActiveProfileIsPostgres() throws Exception {
        Path dbRoot = tempDir.resolve("db");
        Path engine = dbRoot.resolve("Notification");
        Files.createDirectories(engine);
        Path dbFile = Files.createFile(engine.resolve("database.mv.db"));
        Path profile = dbRoot.resolve("Notification.smss");
        Files.writeString(profile, postgresSmss());

        DatabaseTarget resolved = DatabaseCredentialResolver.resolve(
                DatabaseClassifier.classify(dbRoot, dbFile), config(dbRoot));

        assertEquals(Disposition.MIGRATE, resolved.disposition());
        assertEquals(CredentialSource.RETAINED_SYSTEM_DEFAULT, resolved.credentialSource());
        assertTrue(resolved.readyToMigrate());
        assertEquals(profile.toAbsolutePath(), resolved.companionSmss());
        assertEquals("POSTGRES", resolved.companionBackend());
        assertEquals("sa", resolved.credentials().username());
        assertTrue(resolved.warnings().stream().anyMatch(w -> w.contains("SMSS is left unchanged")));
    }

    @Test
    void primaryDatabasePrefersConfirmedH2Fallback() throws Exception {
        Path dbRoot = tempDir.resolve("db");
        Path engine = dbRoot.resolve("ModelInferenceLogsDatabase");
        Files.createDirectories(engine);
        Path dbFile = Files.createFile(engine.resolve("database.mv.db"));
        Files.writeString(dbRoot.resolve("ModelInferenceLogsDatabase.smss"), postgresSmss());
        Path fallback = dbRoot.resolve("ModelInferenceLogsDatabase_h2.smss");
        Files.writeString(fallback, h2Smss("sa", ""));

        DatabaseTarget resolved = DatabaseCredentialResolver.resolve(
                DatabaseClassifier.classify(dbRoot, dbFile), config(dbRoot));

        assertEquals(CredentialSource.H2_FALLBACK_SMSS, resolved.credentialSource());
        assertEquals(fallback.toAbsolutePath(), resolved.companionSmss());
        assertEquals("H2", resolved.companionBackend());
        assertTrue(resolved.warnings().stream().anyMatch(w -> w.contains("POSTGRES")));
    }

    @Test
    void primaryDatabaseResolvesProfileFromDbSmssDirectory() throws Exception {
        Path dbRoot = tempDir.resolve("db");
        Path engine = dbRoot.resolve("form_builder_engine");
        Files.createDirectories(engine);
        Path dbFile = Files.createFile(engine.resolve("database.mv.db"));
        Path smssDirectory = Files.createDirectories(dbRoot.resolve("SMSS"));
        Path profile = smssDirectory.resolve("form_builder_engine.smss");
        Files.writeString(profile, h2Smss("sa", ""));

        DatabaseTarget resolved = DatabaseCredentialResolver.resolve(
                DatabaseClassifier.classify(dbRoot, dbFile), config(dbRoot));

        assertEquals(Disposition.MIGRATE, resolved.disposition());
        assertEquals(CredentialSource.H2_SMSS, resolved.credentialSource());
        assertEquals(profile.toAbsolutePath(), resolved.companionSmss());
    }

    @Test
    void primaryDatabasePlansCleanupForKnownH2OneSettings() throws Exception {
        Path dbRoot = tempDir.resolve("db");
        Path engine = dbRoot.resolve("Customer__id");
        Files.createDirectories(engine);
        Path dbFile = Files.createFile(engine.resolve("database.mv.db"));
        Files.writeString(dbRoot.resolve("Customer__id.smss"), """
                RDBMS_TYPE\tH2_DB
                DRIVER\torg.h2.Driver
                USERNAME\tsa
                PASSWORD
                CONNECTION_URL\tjdbc:h2:nio:/original/database;early_filter=true;query_cache_size=24
                """);

        DatabaseTarget resolved = DatabaseCredentialResolver.resolve(
                DatabaseClassifier.classify(dbRoot, dbFile), config(dbRoot));

        assertEquals(Disposition.MIGRATE, resolved.disposition());
        assertEquals(CredentialSource.H2_SMSS, resolved.credentialSource());
        assertNull(resolved.resolutionError());
        assertTrue(resolved.warnings().stream().anyMatch(w -> w.contains("EARLY_FILTER")));
        assertTrue(resolved.warnings().stream().anyMatch(w -> w.contains("QUERY_CACHE_SIZE")));
        assertTrue(!resolved.runtimeUrl().contains("early_filter"));
        assertTrue(!resolved.runtimeUrl().contains("query_cache_size"));
    }

    private MigrationConfig config(Path dbDir) {
        return MigrationConfig.fromArgs(new String[]{
                "--mode=local",
                "--db-dir=" + dbDir,
                "--backup-dir=" + tempDir.resolve("backup")
        });
    }

    @Test
    void missingPrimaryUsernameUsesH2DefaultUnlessOperatorOverridesIt() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("db"));
        Path folder = Files.createDirectories(root.resolve("Example__id"));
        Path file = Files.createFile(folder.resolve("database.mv.db"));
        Path profile = root.resolve("Example__id.smss");
        Files.writeString(profile, "RDBMS_TYPE=H2_DB\nPASSWORD=\nCONNECTION_URL=jdbc:h2:/old/database\n");
        DatabaseTarget target = DatabaseClassifier.classify(root, file);
        assertEquals("", DatabaseCredentialResolver.resolve(target, config(root)).credentials().username());

        MigrationConfig override = MigrationConfig.fromArgs(new String[]{
                "--db-dir=" + root, "--default-user=operator"});
        assertEquals("operator", DatabaseCredentialResolver.resolve(target, override).credentials().username());

        Files.writeString(profile, "RDBMS_TYPE=H2_DB\nUSERNAME=\nPASSWORD=\nCONNECTION_URL=jdbc:h2:/old/database\n");
        assertEquals("", DatabaseCredentialResolver.resolve(target, override).credentials().username());
        Files.writeString(profile, h2Smss("configured", ""));
        assertEquals("configured", DatabaseCredentialResolver.resolve(target, override).credentials().username());
    }

    private String postgresSmss() {
        return """
                RDBMS_TYPE\tPOSTGRES
                DRIVER\torg.postgresql.Driver
                USERNAME\tpostgres
                PASSWORD\tsecret
                CONNECTION_URL\tjdbc:postgresql://localhost/postgres
                """;
    }

    @Test
    void rootLevelDatabaseSelectsProfileWhoseUrlReferencesItsFile() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("db"));
        Path file = Files.createFile(root.resolve("fixture.mv.db"));
        Files.writeString(root.resolve("A-unrelated.smss"), h2Smss("wrong", "wrong"));
        Path correct = root.resolve("Z-fixture-engine.smss");
        Files.writeString(correct, h2Smss("fixture-user", "fixture-password")
                .replace("/original/database", "/original/home/db/fixture"));

        DatabaseTarget resolved = DatabaseCredentialResolver.resolve(
                DatabaseClassifier.classify(root, file), config(root));

        assertEquals(correct.toAbsolutePath(), resolved.companionSmss());
        assertEquals("fixture-user", resolved.credentials().username());
        assertTrue(resolved.runtimeUrl().contains(file.getParent().toString() + "/fixture"));
    }

    @Test
    void rootLevelDatabaseSupportsBaseFolderPlaceholder() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("db"));
        Path file = Files.createFile(root.resolve("fixture.mv.db"));
        Path profile = root.resolve("Fixture__id.smss");
        Files.writeString(profile, h2Smss("sa", "")
                .replace("/original/database", "@BaseFolder@/db/fixture"));
        DatabaseTarget resolved = DatabaseCredentialResolver.resolve(
                DatabaseClassifier.classify(root, file), config(root));
        assertEquals(profile.toAbsolutePath(), resolved.companionSmss());
        assertTrue(resolved.readyToMigrate());
    }

    @Test
    void rootLevelDatabaseRefusesUnrelatedAndRemoteProfiles() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("db"));
        Path file = Files.createFile(root.resolve("fixture.mv.db"));
        Files.writeString(root.resolve("A-unrelated.smss"), h2Smss("wrong", "wrong"));
        Files.writeString(root.resolve("Z-remote.smss"), h2Smss("remote", "")
                .replace("jdbc:h2:nio:/original/database", "jdbc:h2:tcp://server/other/db/fixture"));
        DatabaseTarget resolved = DatabaseCredentialResolver.resolve(
                DatabaseClassifier.classify(root, file), config(root));
        assertEquals(Disposition.MANUAL_REVIEW, resolved.disposition());
        assertEquals(CredentialSource.UNRESOLVED, resolved.credentialSource());
    }

    @Test
    void rootLevelDatabaseRefusesAmbiguousPathMatches() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("db"));
        Path file = Files.createFile(root.resolve("fixture.mv.db"));
        for (String name : new String[]{"A.smss", "B.smss"}) {
            Files.writeString(root.resolve(name), h2Smss(name, "")
                    .replace("/original/database", "/original/home/db/fixture"));
        }
        DatabaseTarget resolved = DatabaseCredentialResolver.resolve(
                DatabaseClassifier.classify(root, file), config(root));
        assertEquals(Disposition.MANUAL_REVIEW, resolved.disposition());
        assertTrue(resolved.resolutionError().contains("Multiple H2 SMSS profiles"));
    }

    private String h2Smss(String username, String password) {
        return """
                RDBMS_TYPE\tH2_DB
                DRIVER\torg.h2.Driver
                USERNAME\t%s
                PASSWORD\t%s
                CONNECTION_URL\tjdbc:h2:nio:/original/database;CACHE_SIZE=32768
                """.formatted(username, password);
    }
}
