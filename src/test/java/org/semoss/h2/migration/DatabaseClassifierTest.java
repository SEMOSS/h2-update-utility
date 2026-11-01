package org.semoss.h2.migration;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semoss.h2.migration.DatabaseTarget.Disposition;
import org.semoss.h2.migration.DatabaseTarget.TargetType;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseClassifierTest {

    @TempDir
    Path tempDir;

    @Test
    void macosMetadataIsSkippedByNameFolderOrContent() throws Exception {
        Path project = tempDir.resolve("project");
        assertTarget(project, project.resolve("App__id/app_root/version/App__id/._insights_database.mv.db"),
                TargetType.MACOS_METADATA, Disposition.SKIP);
        assertTarget(project, project.resolve("App__id/__MACOSX/insights_database.mv.db"),
                TargetType.MACOS_METADATA, Disposition.SKIP);

        // AppleDouble bytes under an ordinary name
        Path disguised = project.resolve("App__id/insights_database.mv.db");
        Files.createDirectories(disguised.getParent());
        Files.write(disguised, new byte[] {0x00, 0x05, 0x16, 0x07, 0x00, 0x02});
        assertTarget(project, disguised, TargetType.MACOS_METADATA, Disposition.SKIP);
        assertEquals("macOS metadata file (AppleDouble), not an H2 database; left in place",
                DatabaseClassifier.classify(project, disguised).resolutionError());

        // a real-looking file is unaffected
        Files.write(disguised, "H:2,block:2".getBytes());
        assertTarget(project, disguised, TargetType.PROJECT_INSIGHTS_H2, Disposition.MIGRATE);
    }

    @Test
    void classifiesEveryLocalSemossTargetClass() {
        Path semoss = tempDir.resolve("Semoss");

        assertTarget(semoss,
                semoss.resolve("InsightCache/session/insight/H2_Store_CACHE.mv.db"),
                TargetType.TRANSIENT_INSIGHT_CACHE_H2, Disposition.SKIP);
        assertTarget(semoss.resolve("project"),
                semoss.resolve("project/App__id/app_root/version/insight-id/H2_Store_FRAME.mv.db"),
                TargetType.PERSISTED_INSIGHT_FRAME_H2, Disposition.MIGRATE);
        assertTarget(semoss.resolve("db"),
                semoss.resolve("db/Engine__id/audit_log_database.mv.db"),
                TargetType.ENGINE_AUDIT_H2, Disposition.MIGRATE);
        assertTarget(semoss.resolve("project"),
                semoss.resolve("project/App__id/insights_database.mv.db"),
                TargetType.PROJECT_INSIGHTS_H2, Disposition.MIGRATE);
        assertTarget(semoss.resolve("db"),
                semoss.resolve("db/LocalMasterDatabase/databaseNewMaster.mv.db"),
                TargetType.SYSTEM_H2, Disposition.MIGRATE);
        assertTarget(semoss.resolve("db"),
                semoss.resolve("db/CustomerEngine__id/database.mv.db"),
                TargetType.USER_ENGINE_H2, Disposition.MIGRATE);
        assertTarget(tempDir.resolve("other"),
                tempDir.resolve("other/unowned.mv.db"),
                TargetType.UNKNOWN_H2, Disposition.MANUAL_REVIEW);
    }

    @Test
    void projectVersionStoreIsNotConfusedWithLiveInsightCache() {
        Path root = tempDir.resolve("project");
        Path file = root.resolve("Dashboards__id/app_root/version/version-id/H2_Store_ABC.mv.db");

        DatabaseTarget target = DatabaseClassifier.classify(root, file);

        assertEquals(TargetType.PERSISTED_INSIGHT_FRAME_H2, target.targetType());
        assertEquals(DatabaseTarget.RuntimeProfile.H2_FRAME, target.runtimeProfile());
    }

    private void assertTarget(Path root, Path file, TargetType type, Disposition disposition) {
        DatabaseTarget target = DatabaseClassifier.classify(root, file);
        assertEquals(type, target.targetType());
        assertEquals(disposition, target.disposition());
    }
}
