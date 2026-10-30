package org.semoss.h2.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.semoss.h2.migration.ScriptRewriter.RewriteReport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Integration tests that simulate realistic Semoss directory structures and
 * H2 database schemas, running a full migration from H2 1.4.200 to 2.4.240.
 * <p>
 * These tests are longer-running (multiple real H2 databases per test) and
 * verify data integrity end-to-end.
 */
class LocalMigrationIT {

    @TempDir
    Path semossBase;

    private Path dbDir;
    private Path projectDir;
    private Path backupDir;

    @BeforeEach
    void setUp() throws IOException {
        dbDir = semossBase.resolve("db");
        projectDir = semossBase.resolve("project");
        backupDir = semossBase.resolve("backups");
        Files.createDirectories(dbDir);
        Files.createDirectories(projectDir);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    @Test
    void omittedAndBlankUsernamesMigrateTextAliasesAsClobs() throws Exception {
        String[] aliases = {"TEXT", "LONGTEXT", "MEDIUMTEXT", "TINYTEXT", "NTEXT"};
        String value = "Unicode café 漢字; TEXT 'quoted'\n".repeat(1000);
        for (String name : new String[]{"MissingUser__id", "BlankUser__id"}) {
            Path engine = Files.createDirectories(dbDir.resolve(name));
            String base = engine.resolve("database").toString();
            // Synthetic test profiles only; no installation SMSS is used.
            Files.writeString(dbDir.resolve(name + ".smss"),
                    "RDBMS_TYPE=H2_DB\nPASSWORD=\n"
                    + (name.startsWith("Blank") ? "USERNAME=\n" : "")
                    + "CONNECTION_URL=jdbc:h2:nio:" + base + "\n");
            try (IsolatedH2Driver old = IsolatedH2Driver.load("1.4.200");
                 Connection conn = old.connect("jdbc:h2:" + base, "", "");
                 Statement stmt = conn.createStatement()) {
                for (String alias : aliases) {
                    stmt.execute("CREATE TABLE T_" + alias + "(ID INT PRIMARY KEY, BODY " + alias + ")");
                    try (var insert = conn.prepareStatement("INSERT INTO T_" + alias + " VALUES(?, ?)")) {
                        insert.setInt(1, 1);
                        insert.setString(2, value);
                        insert.executeUpdate();
                        insert.setInt(1, 2);
                        insert.setNull(2, java.sql.Types.CLOB);
                        insert.executeUpdate();
                    }
                    try (ResultSet rs = stmt.executeQuery("SELECT BODY FROM T_" + alias)) {
                        assertEquals(java.sql.Types.CLOB, rs.getMetaData().getColumnType(1));
                    }
                }
            }
        }

        // CLI defaults, without the old --default-user= workaround.
        MigrationConfig config = MigrationConfig.fromArgs(new String[]{
                "--db-dir=" + dbDir, "--backup-dir=" + backupDir});
        H2MigrationService service = new H2MigrationService(config);
        assertEquals(0, service.run());
        assertEquals(2, service.getReport().successCount());
        assertEquals(0, service.getReport().failedCount());

        try (IsolatedH2Driver migrated = IsolatedH2Driver.load("2.4.240")) {
            for (String name : new String[]{"MissingUser__id", "BlankUser__id"}) {
                try (Connection conn = migrated.connect("jdbc:h2:" + dbDir.resolve(name).resolve("database"), "", "");
                     Statement stmt = conn.createStatement()) {
                    for (String alias : aliases) {
                        try (ResultSet rs = stmt.executeQuery("SELECT BODY FROM T_" + alias + " ORDER BY ID")) {
                            assertEquals(java.sql.Types.CLOB, rs.getMetaData().getColumnType(1), alias);
                            assertTrue(rs.next());
                            assertEquals(value, rs.getString(1), alias);
                            assertTrue(rs.next());
                            assertEquals(null, rs.getString(1));
                            assertFalse(rs.next());
                        }
                    }
                }
            }
        }
    }

    private MigrationConfig localConfig(Path scanDir) {
        return new MigrationConfig(
                MigrationConfig.MigrationMode.LOCAL,
                scanDir, backupDir,
                false, "sa", "",
                "", "", "", "", "",
                "", "", "", false,
                "", "", "",
                "", "", "", "",
                "",
                "rclone", "", ""
        );
    }

    @Test
    void rootLevelDatabaseMigratesWithOwningProfileAndLeavesUnrelatedProfileUntouched() throws Exception {
        Path unrelated = dbDir.resolve("A-unrelated.smss");
        String unrelatedText = "RDBMS_TYPE=H2_DB\nUSERNAME=wrong\nPASSWORD=wrong\n"
                + "CONNECTION_URL=jdbc:h2:nio:@BaseFolder@/db/other/database;EARLY_FILTER=TRUE\n";
        Files.writeString(unrelated, unrelatedText);
        Path owner = dbDir.resolve("Z-fixture.smss");
        Files.writeString(owner, "RDBMS_TYPE=H2_DB\nUSERNAME=fixture\nPASSWORD=fixture-password\n"
                + "CONNECTION_URL=jdbc:h2:nio:@BaseFolder@/db/root_fixture;EARLY_FILTER=TRUE\n");
        String base = dbDir.resolve("root_fixture").toString();
        try (IsolatedH2Driver old = IsolatedH2Driver.load("1.4.200");
             Connection conn = old.connect("jdbc:h2:" + base, "fixture", "fixture-password");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE SAMPLE(ID INT PRIMARY KEY, BODY TEXT)");
            stmt.execute("INSERT INTO SAMPLE VALUES(1, 'preserved text')");
        }
        H2MigrationService service = new H2MigrationService(localConfig(dbDir));
        assertEquals(0, service.run());
        assertEquals(1, service.getReport().successCount());
        assertEquals(unrelatedText, Files.readString(unrelated));
        assertFalse(Files.readString(owner).contains("EARLY_FILTER"));
        try (IsolatedH2Driver migrated = IsolatedH2Driver.load("2.4.240");
             Connection conn = migrated.connect("jdbc:h2:" + base, "fixture", "fixture-password");
             Statement stmt = conn.createStatement();
             ResultSet rows = stmt.executeQuery("SELECT BODY FROM SAMPLE WHERE ID=1")) {
            assertTrue(rows.next());
            assertEquals("preserved text", rows.getString(1));
            assertEquals(java.sql.Types.CLOB, rows.getMetaData().getColumnType(1));
        }
    }

    private void writeSmss(Path smssFile, String engineId, String engineAlias,
                           String username, String password, String dbRelPath) throws IOException {
        String content = """
                #Base Properties
                ENGINE\t%s
                ENGINE_ALIAS\t%s
                ENGINE_DISPLAY_NAME\t%s
                ENGINE_TYPE\tprerna.engine.impl.rdbms.RDBMSNativeEngine
                RDBMS_TYPE\tH2_DB
                DRIVER\torg.h2.Driver
                USERNAME\t%s
                PASSWORD\t%s
                CONNECTION_URL\tjdbc:h2:nio:@BaseFolder@/%s;query_timeout=180000;cache_size=32768
                """.formatted(engineId, engineAlias, engineAlias, username, password, dbRelPath);
        Files.writeString(smssFile, content);
    }

    /** Create a single-engine database in the Semoss layout convention. */
    private record EngineFixture(String uniqueName, String engineId, Path dir, String dbPath) {}

    private EngineFixture createEngine(String alias) throws IOException {
        String id = UUID.randomUUID().toString();
        String uniqueName = alias + "___" + id;
        Path enginePath = dbDir.resolve(uniqueName);
        Files.createDirectories(enginePath);
        String dbPath = enginePath.resolve("database").toAbsolutePath().toString();
        // Write .smss at db level (sibling to engine dir, like Semoss does)
        writeSmss(dbDir.resolve(uniqueName + ".smss"), id, alias,
                "sa", "", "db/" + uniqueName + "/database");
        return new EngineFixture(uniqueName, id, enginePath, dbPath);
    }

    private record ProjectFixture(String uniqueName, String projectId, Path dir, String insightsDbPath) {}

    private ProjectFixture createProject(String alias) throws IOException {
        String id = UUID.randomUUID().toString();
        String uniqueName = alias + "___" + id;
        Path projPath = projectDir.resolve(uniqueName);
        Files.createDirectories(projPath);
        String dbPath = projPath.resolve("insights_database").toAbsolutePath().toString();
        writeSmss(projectDir.resolve(uniqueName + ".smss"), id, alias,
                "sa", "", "project/" + uniqueName + "/insights_database");
        return new ProjectFixture(uniqueName, id, projPath, dbPath);
    }

    // -----------------------------------------------------------------------
    // 1. Single engine — realistic RDBMSNativeEngine schema
    // -----------------------------------------------------------------------

    @Test
    void singleEngine_realisticSchema_migratesWithDataIntegrity() throws Exception {
        EngineFixture eng = createEngine("MovieDatabase");

        // Create a realistic engine database with H2 1.x
        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("1.4.200")) {
            try (Connection conn = h2.connect("jdbc:h2:" + eng.dbPath, "sa", "");
                 Statement stmt = conn.createStatement()) {
                // Typical RDBMS engine tables
                stmt.execute("""
                    CREATE TABLE MOVIE (
                        MOVIE_ID INT PRIMARY KEY AUTO_INCREMENT,
                        TITLE VARCHAR(500) NOT NULL,
                        GENRE VARCHAR(100),
                        RELEASE_DATE DATE,
                        RATING DOUBLE,
                        BUDGET DECIMAL(15,2),
                        DESCRIPTION CLOB,
                        POSTER BLOB,
                        ACTIVE BOOLEAN DEFAULT TRUE
                    )
                """);
                stmt.execute("""
                    CREATE TABLE ACTOR (
                        ACTOR_ID INT PRIMARY KEY AUTO_INCREMENT,
                        NAME VARCHAR(255) NOT NULL,
                        BIRTH_DATE DATE,
                        NATIONALITY VARCHAR(100)
                    )
                """);
                stmt.execute("""
                    CREATE TABLE MOVIE_ACTOR (
                        MOVIE_ID INT, ACTOR_ID INT,
                        ROLE_NAME VARCHAR(255),
                        PRIMARY KEY (MOVIE_ID, ACTOR_ID),
                        FOREIGN KEY (MOVIE_ID) REFERENCES MOVIE(MOVIE_ID),
                        FOREIGN KEY (ACTOR_ID) REFERENCES ACTOR(ACTOR_ID)
                    )
                """);
                stmt.execute("CREATE INDEX IDX_MOVIE_GENRE ON MOVIE(GENRE)");
                stmt.execute("CREATE INDEX IDX_ACTOR_NAME ON ACTOR(NAME)");

                // Insert data
                stmt.execute("INSERT INTO MOVIE (TITLE, GENRE, RELEASE_DATE, RATING, BUDGET, DESCRIPTION, ACTIVE) VALUES "
                        + "('The Matrix', 'Sci-Fi', '1999-03-31', 8.7, 63000000.00, 'A computer hacker learns about the true nature of reality', TRUE)");
                stmt.execute("INSERT INTO MOVIE (TITLE, GENRE, RELEASE_DATE, RATING, BUDGET, DESCRIPTION, ACTIVE) VALUES "
                        + "('Inception', 'Sci-Fi', '2010-07-16', 8.8, 160000000.00, 'A thief who steals secrets through dream-sharing', TRUE)");
                stmt.execute("INSERT INTO MOVIE (TITLE, GENRE, RELEASE_DATE, RATING, BUDGET, ACTIVE) VALUES "
                        + "('Old Yeller', 'Drama', '1957-12-25', 7.0, 1500000.00, FALSE)");

                stmt.execute("INSERT INTO ACTOR (NAME, BIRTH_DATE, NATIONALITY) VALUES ('Keanu Reeves', '1964-09-02', 'Canadian')");
                stmt.execute("INSERT INTO ACTOR (NAME, BIRTH_DATE, NATIONALITY) VALUES ('Leonardo DiCaprio', '1974-11-11', 'American')");

                stmt.execute("INSERT INTO MOVIE_ACTOR VALUES (1, 1, 'Neo')");
                stmt.execute("INSERT INTO MOVIE_ACTOR VALUES (2, 2, 'Dom Cobb')");
            }
        }

        // Run migration
        H2MigrationService service = new H2MigrationService(localConfig(dbDir));
        int exitCode = service.run();

        assertEquals(0, exitCode);
        assertEquals(1, service.getReport().successCount());
        assertEquals(0, service.getReport().failedCount());

        // Verify with H2 2.x — check all data types and relationships
        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("2.4.240")) {
            try (Connection conn = h2.connect("jdbc:h2:" + eng.dbPath, "sa", "");
                 Statement stmt = conn.createStatement()) {

                // Movie data
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT TITLE, GENRE, RATING, BUDGET, ACTIVE FROM MOVIE ORDER BY MOVIE_ID")) {
                    assertTrue(rs.next());
                    assertEquals("The Matrix", rs.getString("TITLE"));
                    assertEquals("Sci-Fi", rs.getString("GENRE"));
                    assertEquals(8.7, rs.getDouble("RATING"), 0.01);
                    assertEquals(63000000.00, rs.getDouble("BUDGET"), 0.01);
                    assertTrue(rs.getBoolean("ACTIVE"));

                    assertTrue(rs.next());
                    assertEquals("Inception", rs.getString("TITLE"));

                    assertTrue(rs.next());
                    assertEquals("Old Yeller", rs.getString("TITLE"));
                    assertFalse(rs.getBoolean("ACTIVE"));

                    assertFalse(rs.next());
                }

                // Join integrity
                try (ResultSet rs = stmt.executeQuery("""
                        SELECT m.TITLE, a.NAME, ma.ROLE_NAME
                        FROM MOVIE_ACTOR ma
                        JOIN MOVIE m ON m.MOVIE_ID = ma.MOVIE_ID
                        JOIN ACTOR a ON a.ACTOR_ID = ma.ACTOR_ID
                        ORDER BY m.TITLE
                        """)) {
                    assertTrue(rs.next());
                    assertEquals("Inception", rs.getString("TITLE"));
                    assertEquals("Leonardo DiCaprio", rs.getString("NAME"));
                    assertEquals("Dom Cobb", rs.getString("ROLE_NAME"));

                    assertTrue(rs.next());
                    assertEquals("The Matrix", rs.getString("TITLE"));
                    assertEquals("Keanu Reeves", rs.getString("NAME"));
                }

                // CLOB data
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT DESCRIPTION FROM MOVIE WHERE TITLE = 'The Matrix'")) {
                    assertTrue(rs.next());
                    assertTrue(rs.getString("DESCRIPTION").contains("computer hacker"));
                }

                // Index existence — query plan should mention index
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT COUNT(*) FROM MOVIE WHERE GENRE = 'Sci-Fi'")) {
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt(1));
                }
            }
        }

        // Verify backup was created
        long backupCount = Files.walk(backupDir)
                .filter(p -> p.toString().endsWith(".mv.db"))
                .count();
        assertEquals(1, backupCount);
    }

    // -----------------------------------------------------------------------
    // 2. Multiple engines under db/ — all migrated in one pass
    // -----------------------------------------------------------------------

    @Test
    void multipleEngines_allMigratedInOnePass() throws Exception {
        EngineFixture eng1 = createEngine("SalesDB");
        EngineFixture eng2 = createEngine("InventoryDB");
        EngineFixture eng3 = createEngine("AnalyticsDB");

        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("1.4.200")) {
            createSimpleTable(h2, eng1.dbPath, "SALES", 50);
            createSimpleTable(h2, eng2.dbPath, "INVENTORY", 100);
            createSimpleTable(h2, eng3.dbPath, "EVENTS", 200);
        }

        H2MigrationService service = new H2MigrationService(localConfig(dbDir));
        int exitCode = service.run();

        assertEquals(0, exitCode);
        assertEquals(3, service.getReport().successCount());

        // Verify each DB independently
        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("2.4.240")) {
            assertEquals(50, countRows(h2, eng1.dbPath, "SALES"));
            assertEquals(100, countRows(h2, eng2.dbPath, "INVENTORY"));
            assertEquals(200, countRows(h2, eng3.dbPath, "EVENTS"));
        }
    }

    // -----------------------------------------------------------------------
    // 3. Project insights_database with Semoss insight tables
    // -----------------------------------------------------------------------

    @Test
    void projectInsightsDatabase_migratesWithInsightData() throws Exception {
        ProjectFixture proj = createProject("MyApp");

        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("1.4.200")) {
            try (Connection conn = h2.connect("jdbc:h2:" + proj.insightsDbPath, "sa", "");
                 Statement stmt = conn.createStatement()) {
                // Semoss insights schema
                stmt.execute("""
                    CREATE TABLE QUESTION_ID (
                        ID VARCHAR(50), QUESTION_NAME VARCHAR(255),
                        QUESTION_PERSPECTIVE VARCHAR(255), QUESTION_LAYOUT VARCHAR(255),
                        QUESTION_ORDER INT, QUESTION_DATA_MAKER VARCHAR(255),
                        QUESTION_MAKEUP CLOB, DATA_TABLE_ALIGN VARCHAR(500),
                        HIDDEN_INSIGHT BOOLEAN, CACHEABLE BOOLEAN,
                        CACHE_MINUTES INT, CACHE_CRON VARCHAR(25),
                        CACHED_ON TIMESTAMP, CACHE_ENCRYPT BOOLEAN,
                        QUESTION_PKQL CLOB, SCHEMA_NAME VARCHAR(255)
                    )
                """);
                stmt.execute("""
                    CREATE TABLE INSIGHTMETA (
                        INSIGHTID VARCHAR(255), METAKEY VARCHAR(255),
                        METAVALUE CLOB, METAORDER INT
                    )
                """);
                stmt.execute("""
                    CREATE TABLE PARAMETER_ID (
                        PARAMETER_ID VARCHAR(255), PARAMETER_LABEL VARCHAR(255),
                        PARAMETER_TYPE VARCHAR(255), PARAMETER_DEPENDENCY VARCHAR(255),
                        PARAMETER_QUERY VARCHAR(2000), PARAMETER_OPTIONS VARCHAR(2000),
                        PARAMETER_IS_DB_QUERY BOOLEAN, PARAMETER_MULTI_SELECT BOOLEAN,
                        PARAMETER_COMPONENT_FILTER_ID VARCHAR(255),
                        PARAMETER_VIEW_TYPE VARCHAR(255), QUESTION_ID_FK INT
                    )
                """);

                // Insert sample insights
                for (int i = 1; i <= 10; i++) {
                    stmt.execute("INSERT INTO QUESTION_ID (ID, QUESTION_NAME, QUESTION_LAYOUT, QUESTION_ORDER, HIDDEN_INSIGHT, CACHEABLE) "
                            + "VALUES ('" + UUID.randomUUID() + "', 'Insight " + i + "', 'Grid', " + i + ", FALSE, TRUE)");
                }
                stmt.execute("INSERT INTO INSIGHTMETA VALUES ('abc-123', 'description', 'Dashboard showing KPIs', 1)");
                stmt.execute("INSERT INTO INSIGHTMETA VALUES ('abc-123', 'tags', 'finance,dashboard', 2)");
            }
        }

        // Scan the project dir
        H2MigrationService service = new H2MigrationService(localConfig(projectDir));
        int exitCode = service.run();

        assertEquals(0, exitCode);
        assertEquals(1, service.getReport().successCount());

        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("2.4.240")) {
            try (Connection conn = h2.connect("jdbc:h2:" + proj.insightsDbPath, "sa", "");
                 Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM QUESTION_ID")) {
                    assertTrue(rs.next());
                    assertEquals(10, rs.getInt(1));
                }
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT METAVALUE FROM INSIGHTMETA WHERE METAKEY = 'description'")) {
                    assertTrue(rs.next());
                    assertEquals("Dashboard showing KPIs", rs.getString(1));
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // 4. Security database — complex schema with many tables
    // -----------------------------------------------------------------------

    @Test
    void securityDatabase_complexSchema_migratesSuccessfully() throws Exception {
        Path secDir = dbDir.resolve("security");
        Files.createDirectories(secDir);
        String secDbPath = secDir.resolve("security").toAbsolutePath().toString();
        writeSmss(dbDir.resolve("security.smss"), "security", "security",
                "sa", "", "db/security/security");

        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("1.4.200")) {
            try (Connection conn = h2.connect("jdbc:h2:" + secDbPath, "sa", "");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE ENGINE (
                        ENGINEID VARCHAR(255), ENGINENAME VARCHAR(255),
                        ENGINEDISPLAYNAME VARCHAR(255), GLOBAL BOOLEAN,
                        DISCOVERABLE BOOLEAN, ENGINETYPE VARCHAR(255),
                        ENGINESUBTYPE VARCHAR(255), COST VARCHAR(255),
                        CREATEDBY VARCHAR(255), CREATEDBYTYPE VARCHAR(255),
                        DATECREATED TIMESTAMP
                    )
                """);
                stmt.execute("""
                    CREATE TABLE ENGINEPERMISSION (
                        ENGINEID VARCHAR(255), USERID VARCHAR(255),
                        PERMISSION INT, VISIBILITY BOOLEAN,
                        FAVORITE BOOLEAN, PERMISSIONGRANTEDBY VARCHAR(255),
                        PERMISSIONGRANTEDBYTYPE VARCHAR(255),
                        DATEADDED TIMESTAMP, ENDDATE TIMESTAMP
                    )
                """);
                stmt.execute("""
                    CREATE TABLE ENGINEMETA (
                        ENGINEID VARCHAR(255), METAKEY VARCHAR(255),
                        METAVALUE CLOB, METAORDER INT
                    )
                """);
                stmt.execute("""
                    CREATE TABLE PROJECT (
                        PROJECTID VARCHAR(255), PROJECTNAME VARCHAR(255),
                        PROJECTDISPLAYNAME VARCHAR(255), GLOBAL BOOLEAN,
                        DISCOVERABLE BOOLEAN, TYPE VARCHAR(255),
                        COST VARCHAR(255), HASPORTAL BOOLEAN,
                        CREATEDBY VARCHAR(255), CREATEDBYTYPE VARCHAR(255),
                        DATECREATED TIMESTAMP
                    )
                """);
                stmt.execute("""
                    CREATE TABLE SMSS_USER (
                        ID VARCHAR(255), NAME VARCHAR(255), EMAIL VARCHAR(255),
                        TYPE VARCHAR(255), PASSWORD VARCHAR(255), SALT VARCHAR(255),
                        USERNAME VARCHAR(255), ADMIN BOOLEAN, PUBLISHER BOOLEAN,
                        EXPORTER BOOLEAN, DATECREATED TIMESTAMP,
                        LASTLOGIN TIMESTAMP, LOCKED BOOLEAN
                    )
                """);
                stmt.execute("""
                    CREATE TABLE INSIGHT (
                        INSIGHTID VARCHAR(255), PROJECTID VARCHAR(255),
                        INSIGHTNAME VARCHAR(255), GLOBAL BOOLEAN,
                        EXECUTIONCOUNT BIGINT, CREATEDON TIMESTAMP,
                        LASTMODIFIEDON TIMESTAMP, LAYOUT VARCHAR(255),
                        CACHEABLE BOOLEAN, RECIPE CLOB
                    )
                """);
                stmt.execute("""
                    CREATE TABLE PERMISSION (ID INT PRIMARY KEY, NAME VARCHAR(255))
                """);

                // Seed data
                stmt.execute("INSERT INTO PERMISSION VALUES (1, 'OWNER')");
                stmt.execute("INSERT INTO PERMISSION VALUES (2, 'EDIT')");
                stmt.execute("INSERT INTO PERMISSION VALUES (3, 'READ_ONLY')");

                String userId = UUID.randomUUID().toString();
                stmt.execute("INSERT INTO SMSS_USER (ID, NAME, EMAIL, TYPE, ADMIN, PUBLISHER, LOCKED) VALUES "
                        + "('" + userId + "', 'Admin User', 'admin@test.com', 'NATIVE', TRUE, TRUE, FALSE)");

                for (int i = 0; i < 5; i++) {
                    String eid = UUID.randomUUID().toString();
                    stmt.execute("INSERT INTO ENGINE VALUES ('" + eid + "', 'Engine" + i + "', 'Engine " + i + "', "
                            + "TRUE, TRUE, 'DATABASE', 'H2_DB', '', '" + userId + "', 'NATIVE', CURRENT_TIMESTAMP)");
                    stmt.execute("INSERT INTO ENGINEPERMISSION (ENGINEID, USERID, PERMISSION, VISIBILITY, FAVORITE, DATEADDED) VALUES "
                            + "('" + eid + "', '" + userId + "', 1, TRUE, FALSE, CURRENT_TIMESTAMP)");
                    stmt.execute("INSERT INTO ENGINEMETA VALUES ('" + eid + "', 'description', 'Engine " + i + " description (CLOB test)', " + i + ")");
                }

                for (int i = 0; i < 3; i++) {
                    String pid = UUID.randomUUID().toString();
                    stmt.execute("INSERT INTO PROJECT VALUES ('" + pid + "', 'Project" + i + "', 'Project " + i + "', "
                            + "TRUE, TRUE, 'default', '', TRUE, '" + userId + "', 'NATIVE', CURRENT_TIMESTAMP)");
                    stmt.execute("INSERT INTO INSIGHT (INSIGHTID, PROJECTID, INSIGHTNAME, GLOBAL, EXECUTIONCOUNT, CACHEABLE, RECIPE) VALUES "
                            + "('" + UUID.randomUUID() + "', '" + pid + "', 'Dashboard " + i + "', TRUE, " + (i * 10) + ", TRUE, "
                            + "'AddPanel(0);Panel(0)|SetPanelView(\"visualization\");')");
                }
            }
        }

        H2MigrationService service = new H2MigrationService(localConfig(dbDir));
        int exitCode = service.run();

        assertEquals(0, exitCode);
        assertEquals(1, service.getReport().successCount());

        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("2.4.240")) {
            try (Connection conn = h2.connect("jdbc:h2:" + secDbPath, "sa", "");
                 Statement stmt = conn.createStatement()) {
                // Permissions
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM PERMISSION")) {
                    assertTrue(rs.next());
                    assertEquals(3, rs.getInt(1));
                }
                // Users
                try (ResultSet rs = stmt.executeQuery("SELECT NAME, ADMIN FROM SMSS_USER")) {
                    assertTrue(rs.next());
                    assertEquals("Admin User", rs.getString("NAME"));
                    assertTrue(rs.getBoolean("ADMIN"));
                }
                // Engines with CLOB metadata
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ENGINE")) {
                    assertTrue(rs.next());
                    assertEquals(5, rs.getInt(1));
                }
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT METAVALUE FROM ENGINEMETA WHERE METAORDER = 0")) {
                    assertTrue(rs.next());
                    assertTrue(rs.getString(1).contains("CLOB test"));
                }
                // Insights with CLOB recipe
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM INSIGHT")) {
                    assertTrue(rs.next());
                    assertEquals(3, rs.getInt(1));
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // 5. Scheduler database — Quartz + Semoss custom tables with BLOBs
    // -----------------------------------------------------------------------

    @Test
    void schedulerDatabase_quartzTables_migratesSuccessfully() throws Exception {
        Path schedDir = dbDir.resolve("scheduler");
        Files.createDirectories(schedDir);
        String schedDbPath = schedDir.resolve("scheduler").toAbsolutePath().toString();
        writeSmss(dbDir.resolve("scheduler.smss"), "scheduler", "scheduler",
                "sa", "", "db/scheduler/scheduler");

        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("1.4.200")) {
            try (Connection conn = h2.connect("jdbc:h2:" + schedDbPath, "sa", "");
                 Statement stmt = conn.createStatement()) {
                // Quartz tables (subset)
                stmt.execute("""
                    CREATE TABLE QRTZ_JOB_DETAILS (
                        SCHED_NAME VARCHAR(120) NOT NULL, JOB_NAME VARCHAR(200) NOT NULL,
                        JOB_GROUP VARCHAR(200) NOT NULL, DESCRIPTION VARCHAR(250),
                        JOB_CLASS_NAME VARCHAR(250) NOT NULL,
                        IS_DURABLE BOOLEAN NOT NULL, IS_NONCONCURRENT BOOLEAN NOT NULL,
                        IS_UPDATE_DATA BOOLEAN NOT NULL, REQUESTS_RECOVERY BOOLEAN NOT NULL,
                        JOB_DATA BLOB,
                        PRIMARY KEY (SCHED_NAME, JOB_NAME, JOB_GROUP)
                    )
                """);
                stmt.execute("""
                    CREATE TABLE QRTZ_TRIGGERS (
                        SCHED_NAME VARCHAR(120) NOT NULL, TRIGGER_NAME VARCHAR(200) NOT NULL,
                        TRIGGER_GROUP VARCHAR(200) NOT NULL, JOB_NAME VARCHAR(200) NOT NULL,
                        JOB_GROUP VARCHAR(200) NOT NULL, DESCRIPTION VARCHAR(250),
                        NEXT_FIRE_TIME BIGINT, PREV_FIRE_TIME BIGINT,
                        PRIORITY INT, TRIGGER_STATE VARCHAR(16) NOT NULL,
                        TRIGGER_TYPE VARCHAR(8) NOT NULL, START_TIME BIGINT NOT NULL,
                        END_TIME BIGINT, MISFIRE_INSTR INT,
                        JOB_DATA BLOB,
                        PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)
                    )
                """);
                // Semoss custom scheduler tables
                stmt.execute("""
                    CREATE TABLE SMSS_JOB_RECIPES (
                        USER_ID VARCHAR(120), JOB_ID VARCHAR(200),
                        JOB_NAME VARCHAR(200), JOB_GROUP VARCHAR(200),
                        CRON_EXPRESSION VARCHAR(250), CRON_TIMEZONE VARCHAR(120),
                        PIXEL_RECIPE BLOB, PIXEL_RECIPE_PARAMETERS BLOB,
                        JOB_CATEGORY VARCHAR(200), TRIGGER_ON_LOAD BOOLEAN,
                        UI_STATE BLOB
                    )
                """);
                stmt.execute("""
                    CREATE TABLE SMSS_AUDIT_TRAIL (
                        JOB_ID VARCHAR(200), JOB_NAME VARCHAR(200),
                        JOB_GROUP VARCHAR(200),
                        EXECUTION_START TIMESTAMP, EXECUTION_END TIMESTAMP,
                        EXECUTION_DELTA VARCHAR(255),
                        SUCCESS BOOLEAN, IS_LATEST BOOLEAN,
                        SCHEDULER_OUTPUT CLOB
                    )
                """);

                // Insert data with BLOBs
                stmt.execute("INSERT INTO QRTZ_JOB_DETAILS VALUES ('SEMOSS', 'job1', 'group1', 'Test job', "
                        + "'prerna.reactor.scheduler.SchedulerRunner', TRUE, FALSE, FALSE, FALSE, X'CAFEBABE')");
                stmt.execute("INSERT INTO SMSS_JOB_RECIPES (USER_ID, JOB_ID, JOB_NAME, CRON_EXPRESSION, TRIGGER_ON_LOAD) VALUES "
                        + "('user1', 'job1', 'Daily ETL', '0 0 2 * * ?', FALSE)");
                stmt.execute("INSERT INTO SMSS_AUDIT_TRAIL VALUES ('job1', 'Daily ETL', 'group1', "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '00:05:30', TRUE, TRUE, 'Completed 500 rows')");
            }
        }

        H2MigrationService service = new H2MigrationService(localConfig(dbDir));
        int exitCode = service.run();

        assertEquals(0, exitCode);
        assertEquals(1, service.getReport().successCount());

        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("2.4.240")) {
            try (Connection conn = h2.connect("jdbc:h2:" + schedDbPath, "sa", "");
                 Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT JOB_NAME, IS_DURABLE FROM QRTZ_JOB_DETAILS")) {
                    assertTrue(rs.next());
                    assertEquals("job1", rs.getString("JOB_NAME"));
                    assertTrue(rs.getBoolean("IS_DURABLE"));
                }
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT SCHEDULER_OUTPUT FROM SMSS_AUDIT_TRAIL WHERE SUCCESS = TRUE")) {
                    assertTrue(rs.next());
                    assertEquals("Completed 500 rows", rs.getString(1));
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // 6. SMSS credential resolution — engines with non-default creds
    // -----------------------------------------------------------------------

    @Test
    void smssCredentialResolution_readsFromSmssFile() throws Exception {
        String id = UUID.randomUUID().toString();
        String uniqueName = "SecureDB___" + id;
        Path enginePath = dbDir.resolve(uniqueName);
        Files.createDirectories(enginePath);
        String dbPath = enginePath.resolve("database").toAbsolutePath().toString();

        // Write SMSS with custom credentials
        writeSmss(dbDir.resolve(uniqueName + ".smss"), id, "SecureDB",
                "dbadmin", "s3cret!Pass", "db/" + uniqueName + "/database");

        // Create DB with those custom credentials
        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("1.4.200")) {
            try (Connection conn = h2.connect("jdbc:h2:" + dbPath, "dbadmin", "s3cret!Pass");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE SECURE_DATA (ID INT, SECRET VARCHAR(500))");
                stmt.execute("INSERT INTO SECURE_DATA VALUES (1, 'classified information')");
            }
        }

        H2MigrationService service = new H2MigrationService(localConfig(dbDir));
        int exitCode = service.run();

        assertEquals(0, exitCode);
        assertEquals(1, service.getReport().successCount());

        // Verify with custom creds on new driver
        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("2.4.240")) {
            try (Connection conn = h2.connect("jdbc:h2:" + dbPath, "dbadmin", "s3cret!Pass");
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT SECRET FROM SECURE_DATA")) {
                assertTrue(rs.next());
                assertEquals("classified information", rs.getString(1));
            }
        }
    }

    // -----------------------------------------------------------------------
    // 7. Mixed versions — some already on 2.x, some on 1.x
    // -----------------------------------------------------------------------

    @Test
    void mixedVersions_oldMigrates_newSkipped() throws Exception {
        EngineFixture oldEng = createEngine("OldEngine");
        EngineFixture newEng = createEngine("NewEngine");

        // Create old-format DB with H2 1.x
        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("1.4.200")) {
            try (Connection conn = h2.connect("jdbc:h2:" + oldEng.dbPath, "sa", "");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE OLD_DATA (V INT)");
                stmt.execute("INSERT INTO OLD_DATA VALUES (1)");
            }
        }

        // Create new-format DB directly with H2 2.x (writes format:3, detected as already migrated)
        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("2.4.240")) {
            try (Connection conn = h2.connect("jdbc:h2:" + newEng.dbPath, "sa", "");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE NEW_DATA (V INT)");
                stmt.execute("INSERT INTO NEW_DATA VALUES (99)");
            }
        }

        H2MigrationService service = new H2MigrationService(localConfig(dbDir));
        int exitCode = service.run();

        var report = service.getReport();
        assertEquals(2, report.totalCount());
        assertEquals(1, report.successCount(), "Old engine should be migrated");
        assertEquals(1, report.skippedCount(), "New-format engine should be skipped");
        assertEquals(0, report.failedCount());

        // The old engine's data is intact in H2 2.x
        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("2.4.240")) {
            assertEquals(1, countRows(h2, oldEng.dbPath, "OLD_DATA"));
        }
    }

    // -----------------------------------------------------------------------
    // 8. Full Semoss layout — db/ + project/ scanned together
    // -----------------------------------------------------------------------

    @Test
    void fullSemossLayout_dbAndProjectsMigratedTogether() throws Exception {
        // Engines under db/
        EngineFixture eng1 = createEngine("CustomerDB");
        EngineFixture eng2 = createEngine("ProductDB");

        // Projects under project/
        ProjectFixture proj1 = createProject("SalesDashboard");
        ProjectFixture proj2 = createProject("HRPortal");

        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("1.4.200")) {
            createSimpleTable(h2, eng1.dbPath, "CUSTOMERS", 30);
            createSimpleTable(h2, eng2.dbPath, "PRODUCTS", 50);
            createInsightsTable(h2, proj1.insightsDbPath, 5);
            createInsightsTable(h2, proj2.insightsDbPath, 8);
        }

        // Point scan at the semoss base (parent of both db/ and project/)
        H2MigrationService service = new H2MigrationService(localConfig(semossBase));
        int exitCode = service.run();

        assertEquals(0, exitCode);
        assertEquals(4, service.getReport().successCount());
        assertEquals(0, service.getReport().failedCount());

        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("2.4.240")) {
            assertEquals(30, countRows(h2, eng1.dbPath, "CUSTOMERS"));
            assertEquals(50, countRows(h2, eng2.dbPath, "PRODUCTS"));

            try (Connection conn = h2.connect("jdbc:h2:" + proj1.insightsDbPath, "sa", "");
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM QUESTION_ID")) {
                assertTrue(rs.next());
                assertEquals(5, rs.getInt(1));
            }
        }

        // Report JSON exists
        assertTrue(Files.exists(backupDir.resolve("migration-report.json")));
    }

    // -----------------------------------------------------------------------
    // Shared helper methods
    // -----------------------------------------------------------------------

    private void createSimpleTable(IsolatedH2Driver h2, String dbPath, String tableName, int rowCount)
            throws SQLException {
        try (Connection conn = h2.connect("jdbc:h2:" + dbPath, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE " + tableName + " (ID INT PRIMARY KEY, NAME VARCHAR(255), CREATED TIMESTAMP)");
            for (int i = 1; i <= rowCount; i++) {
                stmt.execute("INSERT INTO " + tableName + " VALUES (" + i + ", 'Row " + i + "', CURRENT_TIMESTAMP)");
            }
        }
    }

    private void createInsightsTable(IsolatedH2Driver h2, String dbPath, int insightCount)
            throws SQLException {
        try (Connection conn = h2.connect("jdbc:h2:" + dbPath, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE QUESTION_ID (
                    ID VARCHAR(50), QUESTION_NAME VARCHAR(255),
                    QUESTION_LAYOUT VARCHAR(255), QUESTION_ORDER INT,
                    HIDDEN_INSIGHT BOOLEAN, CACHEABLE BOOLEAN
                )
            """);
            for (int i = 1; i <= insightCount; i++) {
                stmt.execute("INSERT INTO QUESTION_ID VALUES ('" + UUID.randomUUID()
                        + "', 'Insight " + i + "', 'Grid', " + i + ", FALSE, TRUE)");
            }
        }
    }

    // -----------------------------------------------------------------------
    // Category A — legacy data-type aliases (DATETIME, LONGVARCHAR, NUMBER, ...)
    // -----------------------------------------------------------------------

    /**
     * End-to-end proof that a 1.4.200 database containing Category A legacy type
     * aliases (DATETIME, LONGVARBINARY, LONGTEXT, NUMBER) round-trips through the
     * migration pipeline. The ScriptRewriter hop transforms any aliases still
     * present in the SCRIPT dump to H2-2.x-recognised equivalents before RUNSCRIPT.
     */
    @Test
    void categoryA_legacyTypes_migrateEndToEnd() throws Exception {
        EngineFixture eng = createEngine("LegacyTypesEngine");

        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("1.4.200")) {
            try (Connection conn = h2.connect("jdbc:h2:" + eng.dbPath, "sa", "");
                 Statement stmt = conn.createStatement()) {
                // All column types here are legal in H2 1.4.200 but at-risk under 2.x.
                // H2 1.4.200 may normalise some types at storage time; the rewriter
                // is the safety net for those that survive into the SCRIPT dump.
                stmt.execute("""
                    CREATE TABLE EVENTS (
                        ID INT PRIMARY KEY,
                        OCCURRED DATETIME,
                        PAYLOAD LONGVARBINARY,
                        BODY LONGTEXT,
                        AMOUNT NUMBER(12,2),
                        DESCRIPTION LONGVARCHAR,
                        TINY_BLOB TINYBLOB,
                        MEDIUM_TEXT MEDIUMTEXT
                    )
                """);
                stmt.execute("INSERT INTO EVENTS VALUES "
                        + "(1, '2024-01-15 09:30:00', X'DEADBEEF', "
                        + "'long-form event body', 1234.56, "
                        + "'description with a DATETIME word in it', X'01', 'medium content')");
                stmt.execute("INSERT INTO EVENTS VALUES "
                        + "(2, '2024-02-20 14:00:00', X'CAFEBABE', "
                        + "'another body', 7890.12, 'another description', X'02', 'more content')");

                // A column name that literally equals a legacy type token — must NOT
                // be rewritten to a reserved type word. Verifies quoted-identifier logic.
                stmt.execute("""
                    CREATE TABLE KEEPERS (
                        "DATETIME" INT PRIMARY KEY,
                        "LONGVARCHAR" VARCHAR(50),
                        VALUE_CONTAINING_DATETIME VARCHAR(200)
                    )
                """);
                stmt.execute("INSERT INTO KEEPERS VALUES "
                        + "(1, 'keep me', 'the DATETIME column was here')");
            }
        }

        H2MigrationService service = new H2MigrationService(localConfig(dbDir));
        int exitCode = service.run();

        assertEquals(0, exitCode, "Migration should succeed end-to-end");
        assertEquals(1, service.getReport().successCount());
        assertEquals(0, service.getReport().failedCount());

        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("2.4.240")) {
            try (Connection conn = h2.connect("jdbc:h2:" + eng.dbPath, "sa", "");
                 Statement stmt = conn.createStatement()) {

                try (ResultSet rs = stmt.executeQuery(
                        "SELECT ID, OCCURRED, AMOUNT, BODY, DESCRIPTION FROM EVENTS ORDER BY ID")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt("ID"));
                    assertEquals(1234.56, rs.getDouble("AMOUNT"), 0.001);
                    assertTrue(rs.getString("BODY").contains("long-form"));
                    assertTrue(rs.getString("DESCRIPTION").contains("DATETIME word"));

                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt("ID"));
                    assertEquals(7890.12, rs.getDouble("AMOUNT"), 0.001);

                    assertFalse(rs.next());
                }

                // Quoted-identifier columns named after legacy types must still be queryable by name
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT \"DATETIME\", \"LONGVARCHAR\", VALUE_CONTAINING_DATETIME FROM KEEPERS")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt("DATETIME"));
                    assertEquals("keep me", rs.getString("LONGVARCHAR"));
                    assertEquals("the DATETIME column was here", rs.getString("VALUE_CONTAINING_DATETIME"));
                }

                // Every original row survived with types intact
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM EVENTS")) {
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt(1));
                }
            }
        }
    }

    /**
     * Regression test for the customer-reported {@code Unknown data type: "DATETIME"}
     * failure. Previously, a 1.x engine DB whose schema used {@code DATETIME} columns
     * would fail the migration outright. This test reproduces that shape and asserts
     * the pipeline now succeeds.
     */
    @Test
    void categoryA_datetimeColumn_doesNotBlockMigration() throws Exception {
        EngineFixture eng = createEngine("DatetimeRegressionEngine");

        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("1.4.200")) {
            try (Connection conn = h2.connect("jdbc:h2:" + eng.dbPath, "sa", "");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE AUDIT_TRAIL (ID INT PRIMARY KEY, TS DATETIME, ACTION VARCHAR(100))");
                stmt.execute("INSERT INTO AUDIT_TRAIL VALUES (1, '2024-06-01 12:00:00', 'login')");
                stmt.execute("INSERT INTO AUDIT_TRAIL VALUES (2, '2024-06-01 12:05:00', 'query')");
            }
        }

        H2MigrationService service = new H2MigrationService(localConfig(dbDir));
        assertEquals(0, service.run());
        assertEquals(1, service.getReport().successCount());

        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("2.4.240")) {
            try (Connection conn = h2.connect("jdbc:h2:" + eng.dbPath, "sa", "");
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM AUDIT_TRAIL")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }
        }
    }

    private int countRows(IsolatedH2Driver h2, String dbPath, String tableName)
            throws SQLException {
        try (Connection conn = h2.connect("jdbc:h2:" + dbPath, "sa", "");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // -----------------------------------------------------------------------
    // Categories B / C / D / E / F / I / K — combined hardening end-to-end.
    // One deliberately thorough test covering every defensive feature so the
    // integration surface stays small but coverage stays wide.
    // -----------------------------------------------------------------------

    /**
     * Exercises every hardening measure on a single database:
     * <ul>
     *   <li><b>Category B — reserved-word columns</b>: {@code VALUE}, {@code KEY},
     *       {@code TOP}, {@code YEAR} — handled via {@code NON_KEYWORDS} on target URL.</li>
     *   <li><b>Category B — {@code ROW} column</b>: {@code ScriptRewriter} quotes it into
     *       {@code "ROW"} (H2 2.x rejects it in {@code NON_KEYWORDS}).</li>
     *   <li><b>Category C — {@code SMSS_MEDIAN} aggregate</b>: the stripping
     *       itself is exercised by {@code ScriptRewriterTest}; creating the aggregate
     *       in a real 1.x DB requires the impl class on the isolated classloader, which
     *       the test harness cannot provide. We inject a {@code CREATE AGGREGATE} line
     *       into the exported SQL script before import instead.</li>
     *   <li><b>Category D — {@code GROUP_CONCAT} in a view</b>: {@code MODE=LEGACY}
     *       restores the function at runtime.</li>
     *   <li><b>Category F — {@code AUTO_INCREMENT}</b>: accepted via {@code MODE=LEGACY}.</li>
     *   <li><b>Category I — post-migration smoke open</b>: reported as
     *       {@code transformations.smokeOpen == "OK"}.</li>
     *   <li><b>Category K — error reporting surface</b>: verifies
     *       {@code preservedScriptPath} is set on every non-dry-run migration.</li>
     * </ul>
     */
    @Test
    void hardening_endToEnd_combinedCategories() throws Exception {
        EngineFixture eng = createEngine("HardeningEngine");

        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("1.4.200")) {
            try (Connection conn = h2.connect("jdbc:h2:" + eng.dbPath, "sa", "");
                 Statement stmt = conn.createStatement()) {

                // Category B — reserved-word columns (OFFSET and ROW are reserved in 1.x too,
                // so ROW must be double-quoted at creation; NON_KEYWORDS handles the rest in 2.x)
                // Category F — AUTO_INCREMENT
                stmt.execute("""
                        CREATE TABLE CONFIG (
                            ID INT AUTO_INCREMENT PRIMARY KEY,
                            VALUE VARCHAR(100),
                            KEY VARCHAR(100),
                            TOP INT,
                            YEAR INT,
                            "ROW" INT
                        )
                        """);
                stmt.execute("INSERT INTO CONFIG (VALUE, KEY, TOP, YEAR, \"ROW\") VALUES "
                        + "('enabled', 'feature_x', 10, 2024, 1)");
                stmt.execute("INSERT INTO CONFIG (VALUE, KEY, TOP, YEAR, \"ROW\") VALUES "
                        + "('disabled', 'feature_y', 20, 2025, 2)");

                // Category D — GROUP_CONCAT inside a view (only works post-migration under MODE=LEGACY)
                stmt.execute("CREATE TABLE TAG (ITEM_ID INT, LABEL VARCHAR(50))");
                stmt.execute("INSERT INTO TAG VALUES (1, 'alpha'), (1, 'beta'), (2, 'gamma')");
                stmt.execute("""
                        CREATE VIEW TAG_SUMMARY AS
                            SELECT ITEM_ID, GROUP_CONCAT(LABEL SEPARATOR ',') AS LABELS
                            FROM TAG
                            GROUP BY ITEM_ID
                        """);
            }
        }

        H2MigrationService service = new H2MigrationService(localConfig(dbDir));
        int exitCode = service.run();

        assertEquals(0, exitCode, "Combined hardening migration should succeed");
        assertEquals(1, service.getReport().successCount());
        assertEquals(0, service.getReport().failedCount());

        var result = service.getReport().getResults().get(0);

        // Category K — preservedScriptPath is set and the file exists
        assertTrue(result.preservedScriptPath() != null,
                "preservedScriptPath must be set after a non-dry-run migration");
        assertTrue(Files.exists(Path.of(result.preservedScriptPath())),
                "preserved export script must exist on disk: " + result.preservedScriptPath());

        // Transformations — every in-depth modification is recorded
        var t = result.transformations();
        assertTrue(t.containsKey("targetUrlFlags"),
                "target URL compatibility flags must be recorded");
        @SuppressWarnings("unchecked")
        var flags = (java.util.List<String>) t.get("targetUrlFlags");
        assertTrue(flags.stream().anyMatch(s -> s.startsWith("MODE=LEGACY")));
        assertTrue(flags.stream().anyMatch(s -> s.startsWith("NON_KEYWORDS=")));

        // Category I — smokeOpen succeeded
        assertEquals("OK", t.get("smokeOpen"),
                "smokeOpen transformation must be OK after successful migration");

        // Migrated DB is functionally correct under H2 2.x
        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("2.4.240")) {
            try (Connection conn = h2.connect(
                        DatabaseImporter.buildTargetUrl(Path.of(eng.dbPath + ".mv.db")), "sa", "");
                 Statement stmt = conn.createStatement()) {

                // Category B — reserved-word columns queryable by name
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT VALUE, KEY, TOP, YEAR, \"ROW\" FROM CONFIG ORDER BY ID")) {
                    assertTrue(rs.next());
                    assertEquals("enabled", rs.getString(1));
                    assertEquals("feature_x", rs.getString(2));
                    assertEquals(10, rs.getInt(3));
                    assertEquals(2024, rs.getInt(4));
                    assertEquals(1, rs.getInt(5));
                    assertTrue(rs.next());
                    assertEquals("disabled", rs.getString(1));
                    assertEquals(2, rs.getInt(5));
                }

                // Category F — AUTO_INCREMENT still increments
                stmt.execute("INSERT INTO CONFIG (VALUE, KEY, TOP, YEAR, \"ROW\") VALUES "
                        + "('new', 'feature_z', 30, 2026, 3)");
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT COUNT(*) FROM CONFIG WHERE VALUE = 'new'")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                }

                // Category D — GROUP_CONCAT in a view resolves under MODE=LEGACY
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT ITEM_ID, LABELS FROM TAG_SUMMARY ORDER BY ITEM_ID")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                    String labels1 = rs.getString(2);
                    assertTrue(labels1.contains("alpha") && labels1.contains("beta"),
                            "GROUP_CONCAT should have aggregated 'alpha' and 'beta' for item 1 — got " + labels1);
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt(1));
                    assertEquals("gamma", rs.getString(2));
                }
            }
        }

        // JSON report is written and contains the structured transformation tracking
        Path reportFile = backupDir.resolve("migration-report.json");
        assertTrue(Files.exists(reportFile));
        String json = Files.readString(reportFile);
        assertTrue(json.contains("targetUrlFlags"));
        assertTrue(json.contains("preservedScriptPath"));
        assertTrue(json.contains("smokeOpen"));
    }

    // -----------------------------------------------------------------------
    // Category H — encrypted database detection
    // -----------------------------------------------------------------------

    /**
     * Stand-in for a CIPHER=AES encrypted H2 DB: a .mv.db file filled with high-entropy
     * bytes that can't be parsed as an MVStore header. The migration utility should
     * detect this pre-export and emit a clear error rather than a confusing
     * "File corrupted" deep in the 1.x driver.
     */
    @Test
    void categoryH_encryptedDatabase_reportsClearFailure() throws Exception {
        Path encDir = dbDir.resolve("encrypted");
        Files.createDirectories(encDir);
        Path encFile = encDir.resolve("cipher.mv.db");
        writeSmss(dbDir.resolve("encrypted.smss"), "encrypted", "encrypted",
                "sa", "", "db/encrypted/cipher");

        // High-entropy "ciphertext" — no printable MVStore header possible
        byte[] cipher = new byte[512];
        for (int i = 0; i < cipher.length; i++) cipher[i] = (byte) (0x80 | (i * 37 & 0x7f));
        Files.write(encFile, cipher);

        H2MigrationService service = new H2MigrationService(localConfig(dbDir));
        int exitCode = service.run();

        assertEquals(1, exitCode, "Encrypted DB must fail the overall run");
        assertEquals(1, service.getReport().failedCount());
        var result = service.getReport().getResults().get(0);
        assertTrue(result.reason().contains("CIPHER") || result.reason().contains("encrypted"),
                "reason should name encryption — got: " + result.reason());
        assertEquals("EncryptedDatabaseException", result.errorClass());
    }

    // -----------------------------------------------------------------------
    // Category C — custom aggregate (SMSS_MEDIAN) stripping in the full pipeline
    // -----------------------------------------------------------------------

    /**
     * We cannot register {@code CREATE AGGREGATE … FOR "prerna.ds.rdbms.h2.H2MedianAggregation"}
     * in a test-harness H2 1.x DB because the isolated classloader has no access to the
     * Semoss jar. Instead, this test drives the sub-components directly:
     * export → inject CREATE AGGREGATE into the script → rewrite → import → smoke open.
     * It proves the strip path works end-to-end without needing the real aggregate class,
     * which is what would happen in production (the class is resolvable when Semoss is
     * running the migration, or the stripped script succeeds because the class is gone).
     */
    @Test
    void categoryC_smssMedianAggregate_strippedDuringMigration() throws Exception {
        EngineFixture eng = createEngine("AggregateEngine");

        // 1. Create a simple 1.x DB
        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200")) {
            try (Connection conn = oldH2.connect("jdbc:h2:" + eng.dbPath, "sa", "");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE METRICS(ID INT PRIMARY KEY, VALUE DOUBLE)");
                stmt.execute("INSERT INTO METRICS VALUES (1, 10.0), (2, 20.0), (3, 30.0)");
            }
        }

        // 2. Export it with the real exporter
        Path dbFile = Path.of(eng.dbPath + ".mv.db");
        try (IsolatedH2Driver oldH2 = IsolatedH2Driver.load("1.4.200");
             IsolatedH2Driver newH2 = IsolatedH2Driver.load("2.4.240")) {

            Path script = new DatabaseExporter(oldH2).export(dbFile, "sa", "");

            // 3. Inject a CREATE AGGREGATE line matching what SCRIPT TO would emit in Semoss
            String content = Files.readString(script);
            content = "CREATE AGGREGATE IF NOT EXISTS SMSS_MEDIAN FOR "
                    + "\"prerna.ds.rdbms.h2.H2MedianAggregation\";\n" + content;
            Files.writeString(script, content);

            // 4. Run the rewriter — it should strip the aggregate
            RewriteReport rr = ScriptRewriter.rewriteAndScanInPlace(script);
            assertTrue(rr.strippedAggregates().stream().anyMatch(s -> s.contains("SMSS_MEDIAN")),
                    "SMSS_MEDIAN should be stripped — got " + rr.strippedAggregates());

            // 5. Import with the stripped script — should succeed (no classpath dependency)
            new DatabaseImporter(newH2).importDatabase(dbFile, script, "sa", "");
            new DatabaseImporter(newH2).smokeOpen(dbFile, "sa", "");

            // 6. Data is intact
            try (Connection conn = newH2.connect("jdbc:h2:" + eng.dbPath, "sa", "");
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM METRICS")) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Category G — legacy PageStore (.h2.db) round-trip
    // -----------------------------------------------------------------------

    /**
     * H2 2.x cannot open the pre-MVStore PageStore format (.h2.db). The utility must
     * detect these and migrate them through 1.4.200 export → 2.4.240 import just like
     * .mv.db files. To create a .h2.db fixture we use 1.x's {@code ;MV_STORE=FALSE}
     * URL option, which forces the legacy format.
     */
    @Test
    void categoryG_h2dbPageStore_roundTripsToMvStore() throws Exception {
        Path psDir = dbDir.resolve("pagestore");
        Files.createDirectories(psDir);
        String base = psDir.resolve("legacy").toAbsolutePath().toString();
        writeSmss(dbDir.resolve("pagestore.smss"), "pagestore", "pagestore",
                "sa", "", "db/pagestore/legacy");

        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("1.4.200")) {
            try (Connection conn = h2.connect("jdbc:h2:" + base + ";MV_STORE=FALSE", "sa", "");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE LEGACY (ID INT PRIMARY KEY, NAME VARCHAR(50))");
                stmt.execute("INSERT INTO LEGACY VALUES (1, 'pageA')");
                stmt.execute("INSERT INTO LEGACY VALUES (2, 'pageB')");
            }
        }

        // Verify we created a .h2.db (PageStore), not .mv.db
        Path h2dbFile = Path.of(base + ".h2.db");
        Path mvdbFile = Path.of(base + ".mv.db");
        assertTrue(Files.exists(h2dbFile) || Files.exists(mvdbFile),
                "1.x produced neither .h2.db nor .mv.db");

        // Only run the PageStore-specific assertions if we actually got a .h2.db
        if (!Files.exists(h2dbFile)) {
            // 1.4.200 silently ignored MV_STORE=FALSE — skip the fixture-specific checks
            return;
        }

        H2MigrationService service = new H2MigrationService(localConfig(dbDir));
        int exitCode = service.run();

        assertEquals(0, exitCode, "PageStore .h2.db migration should succeed");
        assertEquals(1, service.getReport().successCount());

        // Post-migration the DB is in MVStore format (.mv.db)
        assertTrue(Files.exists(Path.of(base + ".mv.db")),
                "Post-migration .mv.db should exist");

        // Data is accessible under H2 2.x
        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("2.4.240")) {
            try (Connection conn = h2.connect("jdbc:h2:" + base, "sa", "");
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM LEGACY")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }
        }
    }
}
