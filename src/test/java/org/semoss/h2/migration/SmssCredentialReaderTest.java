package org.semoss.h2.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SmssCredentialReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void readCredentials_fromParentSmssFile() throws IOException {
        // Simulates: db/themes/database.mv.db with db/themes.smss
        Path dbFolder = tempDir.resolve("themes");
        Files.createDirectories(dbFolder);
        Path dbFile = dbFolder.resolve("database.mv.db");
        Files.createFile(dbFile);

        Path smss = tempDir.resolve("themes.smss");
        Files.writeString(smss, """
                ENGINE\tthemes
                USERNAME\tsa
                PASSWORD\tabcd
                """);

        var creds = SmssCredentialReader.readCredentials(dbFile, "default", "default");

        assertEquals("sa", creds.username());
        assertEquals("abcd", creds.password());
    }

    @Test
    void readCredentials_aliasUuidFolder() throws IOException {
        // Simulates: db/AnimalDb__uuid/database.mv.db with db/AnimalDb__uuid.smss
        Path dbFolder = tempDir.resolve("AnimalDb__eb6a3fab-2425");
        Files.createDirectories(dbFolder);
        Path dbFile = dbFolder.resolve("database.mv.db");
        Files.createFile(dbFile);

        Path smss = tempDir.resolve("AnimalDb__eb6a3fab-2425.smss");
        Files.writeString(smss, """
                ENGINE\teb6a3fab-2425
                USERNAME\tadmin
                PASSWORD\tsecret123
                """);

        var creds = SmssCredentialReader.readCredentials(dbFile, "sa", "");

        assertEquals("admin", creds.username());
        assertEquals("secret123", creds.password());
    }

    @Test
    void readCredentials_emptyPassword_usesFallback() throws IOException {
        Path dbFolder = tempDir.resolve("mydb");
        Files.createDirectories(dbFolder);
        Path dbFile = dbFolder.resolve("database.mv.db");
        Files.createFile(dbFile);

        Path smss = tempDir.resolve("mydb.smss");
        Files.writeString(smss, """
                USERNAME\tsa
                PASSWORD
                """);

        var creds = SmssCredentialReader.readCredentials(dbFile, "sa", "fallback");

        assertEquals("sa", creds.username());
        assertEquals("fallback", creds.password());
    }

    @Test
    void readCredentials_noSmssFile_usesFallbacks() throws IOException {
        Path dbFolder = tempDir.resolve("orphan");
        Files.createDirectories(dbFolder);
        Path dbFile = dbFolder.resolve("database.mv.db");
        Files.createFile(dbFile);

        var creds = SmssCredentialReader.readCredentials(dbFile, "defuser", "defpass");

        assertEquals("defuser", creds.username());
        assertEquals("defpass", creds.password());
    }

    @Test
    void readCredentials_commentsAndBlankLinesIgnored() throws IOException {
        Path dbFolder = tempDir.resolve("commented");
        Files.createDirectories(dbFolder);
        Path dbFile = dbFolder.resolve("database.mv.db");
        Files.createFile(dbFile);

        Path smss = tempDir.resolve("commented.smss");
        Files.writeString(smss, """
                #Base Properties
                
                ENGINE\tcommented
                # This is a comment
                USERNAME\tsa
                PASSWORD\tmypass
                """);

        var creds = SmssCredentialReader.readCredentials(dbFile, "x", "y");

        assertEquals("sa", creds.username());
        assertEquals("mypass", creds.password());
    }

    @Test
    void readCredentials_smssInSameDirectory() throws IOException {
        // Fallback: .smss file in the same directory as the db file
        Path dbFolder = tempDir.resolve("flat");
        Files.createDirectories(dbFolder);
        Path dbFile = dbFolder.resolve("database.mv.db");
        Files.createFile(dbFile);

        Path smss = dbFolder.resolve("engine.smss");
        Files.writeString(smss, """
                USERNAME\tlocal
                PASSWORD\tlocalpw
                """);

        var creds = SmssCredentialReader.readCredentials(dbFile, "sa", "");

        assertEquals("local", creds.username());
        assertEquals("localpw", creds.password());
    }

    @Test
    void findSmssFile_returnsNull_whenNoneExists() throws IOException {
        Path dbFolder = tempDir.resolve("nope");
        Files.createDirectories(dbFolder);
        Path dbFile = dbFolder.resolve("database.mv.db");
        Files.createFile(dbFile);

        assertNull(SmssCredentialReader.findSmssFile(dbFile));
    }

    @Test
    void readMetadata_confirmsH2AndPreservesExplicitBlankPassword() throws IOException {
        Path smss = tempDir.resolve("h2.smss");
        Files.writeString(smss, """
                RDBMS_TYPE\tH2_DB
                DRIVER\torg.h2.Driver
                USERNAME\tsa
                PASSWORD
                CONNECTION_URL\tjdbc:h2:nio:/data/database
                """);

        var metadata = SmssCredentialReader.readMetadata(smss, "fallback", "fallback-password");

        assertEquals("H2", metadata.backend());
        assertEquals(true, metadata.confirmedH2());
        assertEquals("sa", metadata.credentials().username());
        assertEquals("", metadata.credentials().password());
    }

    @Test
    void readMetadata_rejectsConflictingBackendIndicators() throws IOException {
        Path smss = tempDir.resolve("conflict.smss");
        Files.writeString(smss, """
                RDBMS_TYPE\tPOSTGRES
                DRIVER\torg.h2.Driver
                CONNECTION_URL\tjdbc:postgresql://localhost/postgres
                """);

        var metadata = SmssCredentialReader.readMetadata(smss, "sa", "");

        assertEquals(false, metadata.confirmedH2());
        assertEquals(true, metadata.backend().startsWith("CONFLICTING:"));
    }

    @Test
    void credentialsToStringRedactsPassword() {
        var credentials = new SmssCredentialReader.Credentials("admin", "super-secret");

        assertEquals(false, credentials.toString().contains("super-secret"));
        assertEquals(true, credentials.toString().contains("<redacted>"));
    }

    @Test
    void readMetadata_acceptsCp1252BytesLikeSemoss() throws IOException {
        Path smss = tempDir.resolve("cp1252.smss");
        byte[] content = ("#Caf\u00e9 engine\nRDBMS_TYPE\tH2_DB\nUSERNAME\tsa\nPASSWORD\tp\u00e4ss\n"
                + "CONNECTION_URL\tjdbc:h2:nio:/db\n").getBytes(java.nio.charset.Charset.forName("windows-1252"));
        Files.write(smss, content);

        var metadata = SmssCredentialReader.readMetadata(smss, "fallback", "fallback");

        assertEquals("H2", metadata.backend());
        assertEquals("sa", metadata.credentials().username());
        assertEquals("p\u00e4ss", metadata.credentials().password());
        assertEquals("jdbc:h2:nio:/db", metadata.connectionUrl());
    }

    @Test
    void readMetadata_usesPropertiesSeparatorsAndEscapes() throws IOException {
        Path smss = tempDir.resolve("props.smss");
        Files.writeString(smss, """
                RDBMS_TYPE=H2_DB
                USERNAME : admin
                PASSWORD\tse\\\\cret
                CONNECTION_URL\tjdbc:h2:nio:C:\\\\SEMOSS\\\\db\\\\x
                """);

        var metadata = SmssCredentialReader.readMetadata(smss, "fallback", "fallback");

        assertEquals("H2", metadata.backend());
        assertEquals("admin", metadata.credentials().username());
        assertEquals("se\\cret", metadata.credentials().password());
        assertEquals("jdbc:h2:nio:C:\\SEMOSS\\db\\x", metadata.connectionUrl());
    }
}
