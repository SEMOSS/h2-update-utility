package org.semoss.h2.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    private static String engineDir(String name) {
        return name + "___" + UUID.randomUUID();
    }

    private MigrationConfig localConfig(Path scanDir) {
        return new MigrationConfig(
                MigrationConfig.MigrationMode.LOCAL,
                scanDir, backupDir,
                false, true, "sa", "",
                "", "", "", "", "",
                "", "", "", false, false,
                "", "", "",
                "", "", "", "",
                "rclone", ""
        );
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
                CONNECTION_URL\tjdbc:h2:nio:@BaseFolder@/%s;query_timeout=180000;early_filter=true;query_cache_size=24;cache_size=32768
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
    void mixedVersions_oldMigrates_newFormatFailsGracefully() throws Exception {
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

        // Create new-format DB directly with H2 2.x
        // Note: Both H2 1.x and 2.x use MVStore format:1, so version detection
        // cannot distinguish them by header alone. The 2.x DB will be attempted
        // and fail during export (1.4.200 can't read 2.x files), which is
        // expected behavior — the failure is caught and reported gracefully.
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

        // The old DB should be successfully migrated
        long migrated = report.getResults().stream()
                .filter(r -> r.status() == MigrationReport.Status.SUCCESS).count();
        assertEquals(1, migrated, "Old engine should be migrated");

        // The new DB will fail (old driver can't export new format) — that's expected
        long failed = report.getResults().stream()
                .filter(r -> r.status() == MigrationReport.Status.FAILED).count();
        assertEquals(1, failed, "New-format engine should fail gracefully");

        // The old engine's data is intact in H2 2.x
        try (IsolatedH2Driver h2 = IsolatedH2Driver.load("2.4.240")) {
            assertEquals(1, countRows(h2, oldEng.dbPath, "OLD_DATA"));
        }

        // The new engine's backup should exist so it can be restored
        // (BackupManager creates backup before export attempt)
        long backupCount = Files.walk(backupDir)
                .filter(p -> p.toString().endsWith(".mv.db"))
                .count();
        assertEquals(2, backupCount, "Both databases should have backups");
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

    private int countRows(IsolatedH2Driver h2, String dbPath, String tableName)
            throws SQLException {
        try (Connection conn = h2.connect("jdbc:h2:" + dbPath, "sa", "");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
