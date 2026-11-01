package org.semoss.h2.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.semoss.h2.migration.DatabaseTarget.Disposition;
import org.semoss.h2.migration.DatabaseTarget.RuntimeProfile;
import org.semoss.h2.migration.DatabaseTarget.TargetType;

/** Classifies discovered H2 files according to the durable SEMOSS layout. */
public final class DatabaseClassifier {

    private static final Set<String> SYSTEM_FOLDERS = Set.of(
            "auditlogs",
            "collaboration",
            "localmasterdatabase",
            "modelinferencelogsdatabase",
            "notification",
            "promptdatabase",
            "usertrackingdatabase",
            "scheduler",
            "security",
            "themes");

    private DatabaseClassifier() {}

    public static DatabaseTarget classify(Path rootDir, Path databaseFile) {
        Path root = rootDir.toAbsolutePath().normalize();
        Path file = databaseFile.toAbsolutePath().normalize();
        Path relative = file.startsWith(root) ? root.relativize(file) : file;

        List<String> context = new ArrayList<>();
        if (root.getFileName() != null) {
            context.add(lower(root.getFileName().toString()));
        }
        for (Path part : relative) context.add(lower(part.toString()));

        String fileName = lower(file.getFileName().toString());
        String parentName = file.getParent() != null && file.getParent().getFileName() != null
                ? lower(file.getParent().getFileName().toString()) : "";

        boolean underInsightCache = contains(context, "insightcache");
        boolean underProject = contains(context, "project") || contains(context, "semoss-project");
        boolean underDb = contains(context, "db") || contains(context, "semoss-db");

        // macOS AppleDouble sidecar (._name, __MACOSX/); SEMOSS never opens it
        if (fileName.startsWith("._") || contains(context, "__macosx") || isAppleDouble(file)) {
            return DatabaseTarget.discovered(file, TargetType.MACOS_METADATA,
                    Disposition.SKIP, RuntimeProfile.NONE)
                    .skipped("macOS metadata file (AppleDouble), not an H2 database; left in place",
                            null, null, List.of());
        }

        if (underInsightCache) {
            return DatabaseTarget.discovered(file, TargetType.TRANSIENT_INSIGHT_CACHE_H2,
                    Disposition.SKIP, RuntimeProfile.NONE);
        }

        if (underProject
                && fileName.startsWith("h2_store_")
                && fileName.endsWith(DatabaseDiscovery.MV_DB_SUFFIX)
                && containsSequence(context, "app_root", "version")) {
            return DatabaseTarget.discovered(file, TargetType.PERSISTED_INSIGHT_FRAME_H2,
                    Disposition.MIGRATE, RuntimeProfile.H2_FRAME);
        }

        if ("audit_log_database.mv.db".equals(fileName)
                || "audit_log_database.h2.db".equals(fileName)) {
            return DatabaseTarget.discovered(file, TargetType.ENGINE_AUDIT_H2,
                    Disposition.MIGRATE, RuntimeProfile.H2_NIO);
        }

        if ("insights_database.mv.db".equals(fileName)
                || "insights_database.h2.db".equals(fileName)) {
            return DatabaseTarget.discovered(file, TargetType.PROJECT_INSIGHTS_H2,
                    Disposition.MIGRATE, RuntimeProfile.H2_NIO);
        }

        String databaseBaseName = stripDatabaseSuffix(fileName);
        if (SYSTEM_FOLDERS.contains(parentName) || SYSTEM_FOLDERS.contains(databaseBaseName)) {
            return DatabaseTarget.discovered(file, TargetType.SYSTEM_H2,
                    Disposition.MIGRATE, RuntimeProfile.H2_SMSS);
        }

        if (underDb) {
            return DatabaseTarget.discovered(file, TargetType.USER_ENGINE_H2,
                    Disposition.MIGRATE, RuntimeProfile.H2_SMSS);
        }

        return DatabaseTarget.discovered(file, TargetType.UNKNOWN_H2,
                Disposition.MANUAL_REVIEW, RuntimeProfile.NONE);
    }

    private static final byte[] APPLE_DOUBLE_MAGIC = {0x00, 0x05, 0x16, 0x07};

    static boolean isAppleDouble(Path file) {
        if (!Files.isRegularFile(file)) return false;
        try (InputStream in = Files.newInputStream(file)) {
            return java.util.Arrays.equals(in.readNBytes(4), APPLE_DOUBLE_MAGIC);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean contains(List<String> parts, String expected) {
        return parts.stream().anyMatch(expected::equals);
    }

    private static boolean containsSequence(List<String> parts, String first, String second) {
        for (int i = 0; i + 1 < parts.size(); i++) {
            if (first.equals(parts.get(i)) && second.equals(parts.get(i + 1))) return true;
        }
        return false;
    }

    private static String stripDatabaseSuffix(String name) {
        if (name.endsWith(DatabaseDiscovery.MV_DB_SUFFIX)) {
            return name.substring(0, name.length() - DatabaseDiscovery.MV_DB_SUFFIX.length());
        }
        if (name.endsWith(DatabaseDiscovery.H2_DB_SUFFIX)) {
            return name.substring(0, name.length() - DatabaseDiscovery.H2_DB_SUFFIX.length());
        }
        return name;
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
