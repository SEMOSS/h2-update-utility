package org.semoss.h2.migration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Builds relocated H2 runtime URLs for post-migration validation. */
public final class RuntimeConnectionUrlBuilder {

    private static final String H2_PREFIX = "jdbc:h2:";
    private static final Set<String> UNSUPPORTED_H2_2_SETTINGS = Set.of(
            "EARLY_FILTER", "QUERY_CACHE_SIZE", "LOG", "UNDO_LOG");

    private RuntimeConnectionUrlBuilder() {}

    public static String forH2Frame(Path databaseFile) {
        return forH2Nio(databaseFile) + ";CACHE_SIZE=65536;LOCK_MODE=1";
    }

    public static String forH2Nio(Path databaseFile) {
        return H2_PREFIX + "nio:" + DatabaseExporter.stripExtension(databaseFile);
    }

    /**
     * Preserve the configured H2 connection options but relocate the database
     * path to the discovered file. This prevents fixture testing from opening
     * the original database referenced by an SMSS file.
     */
    public static String fromSmss(Path databaseFile, String configuredUrl) {
        if (configuredUrl == null || configuredUrl.isBlank()) {
            throw new IllegalArgumentException("H2 SMSS does not define CONNECTION_URL");
        }
        if (!configuredUrl.regionMatches(true, 0, H2_PREFIX, 0, H2_PREFIX.length())) {
            throw new IllegalArgumentException("SMSS connection URL is not H2");
        }

        String remainder = configuredUrl.substring(H2_PREFIX.length());
        int optionStart = remainder.indexOf(';');
        String location = optionStart >= 0 ? remainder.substring(0, optionStart) : remainder;
        String options = optionStart >= 0 ? remainder.substring(optionStart) : "";
        String lowerLocation = location.toLowerCase();
        if (lowerLocation.startsWith("tcp:") || lowerLocation.startsWith("ssl:")
                || lowerLocation.startsWith("mem:")) {
            throw new IllegalArgumentException("Physical H2 file is configured with a non-file H2 URL");
        }

        boolean nio = lowerLocation.startsWith("nio:");
        return H2_PREFIX + (nio ? "nio:" : "")
                + DatabaseExporter.stripExtension(databaseFile) + options;
    }

    /** Return known H2 1.x-only URL settings that must be removed before migration. */
    public static List<String> unsupportedSettings(String configuredUrl) {
        if (configuredUrl == null || configuredUrl.isBlank()) return List.of();
        Set<String> found = new LinkedHashSet<>();
        String[] segments = configuredUrl.split(";");
        for (int i = 1; i < segments.length; i++) {
            String segment = segments[i].trim();
            int equals = segment.indexOf('=');
            String key = (equals >= 0 ? segment.substring(0, equals) : segment)
                    .trim().toUpperCase(Locale.ROOT);
            if (UNSUPPORTED_H2_2_SETTINGS.contains(key)) found.add(key);
        }
        return new ArrayList<>(found);
    }

    /** Remove only known H2 1.x-only URL settings, preserving all other options. */
    public static String removeUnsupportedSettings(String configuredUrl) {
        if (configuredUrl == null || configuredUrl.isBlank()) return configuredUrl;
        String[] segments = configuredUrl.split(";", -1);
        StringBuilder sanitized = new StringBuilder(segments[0]);
        for (int i = 1; i < segments.length; i++) {
            String segment = segments[i];
            String trimmed = segment.trim();
            int equals = trimmed.indexOf('=');
            String key = (equals >= 0 ? trimmed.substring(0, equals) : trimmed)
                    .trim().toUpperCase(Locale.ROOT);
            if (!UNSUPPORTED_H2_2_SETTINGS.contains(key)) {
                sanitized.append(';').append(segment);
            }
        }
        return sanitized.toString();
    }
}
