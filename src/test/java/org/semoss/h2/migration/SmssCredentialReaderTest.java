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
}
