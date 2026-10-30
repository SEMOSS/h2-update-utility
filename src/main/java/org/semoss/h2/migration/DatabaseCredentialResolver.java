package org.semoss.h2.migration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.semoss.h2.migration.DatabaseTarget.CredentialSource;
import org.semoss.h2.migration.DatabaseTarget.TargetType;

/** Resolves source credentials and runtime URLs without leaking secrets. */
public final class DatabaseCredentialResolver {

    private static final SmssCredentialReader.Credentials GENERATED_H2_CREDENTIALS =
            new SmssCredentialReader.Credentials("sa", "");

    private DatabaseCredentialResolver() {}

    public static DatabaseTarget resolve(DatabaseTarget target, MigrationConfig config) {
        return resolve(target, config, null);
    }

    /** Resolve a cloud primary target against its exact downloaded SMSS scope. */
    public static DatabaseTarget resolve(DatabaseTarget target, MigrationConfig config,
                                         List<Path> explicitPrimarySmssCandidates) {
        return switch (target.targetType()) {
            case PERSISTED_INSIGHT_FRAME_H2 -> target.resolved(
                    CredentialSource.H2_FRAME_RUNTIME,
                    GENERATED_H2_CREDENTIALS,
                    null,
                    "H2",
                    RuntimeConnectionUrlBuilder.forH2Frame(target.databaseFile()),
                    List.of());
            case ENGINE_AUDIT_H2 -> target.resolved(
                    CredentialSource.GENERATED_AUDIT_RUNTIME,
                    GENERATED_H2_CREDENTIALS,
                    null,
                    "H2",
                    RuntimeConnectionUrlBuilder.forH2Nio(target.databaseFile()),
                    List.of());
            case PROJECT_INSIGHTS_H2 -> target.resolved(
                    CredentialSource.GENERATED_PROJECT_INSIGHTS_RUNTIME,
                    GENERATED_H2_CREDENTIALS,
                    null,
                    "H2",
                    RuntimeConnectionUrlBuilder.forH2Nio(target.databaseFile()),
                    List.of());
            case SYSTEM_H2, USER_ENGINE_H2 -> resolvePrimary(
                    target, config, explicitPrimarySmssCandidates);
            case TRANSIENT_INSIGHT_CACHE_H2, UNKNOWN_H2 -> target;
        };
    }

    private static DatabaseTarget resolvePrimary(DatabaseTarget target, MigrationConfig config,
                                                 List<Path> explicitCandidates) {
        List<Path> candidates = explicitCandidates == null
                ? SmssCredentialReader.findSmssFiles(target.databaseFile())
                : explicitCandidates.stream()
                        .map(path -> path.toAbsolutePath().normalize())
                        .sorted(Comparator
                                .comparing((Path path) -> !path.getFileName().toString()
                                        .toLowerCase(Locale.ROOT).contains("_h2.smss"))
                                .thenComparing(path -> path.getFileName().toString()
                                        .toLowerCase(Locale.ROOT)))
                        .toList();
        List<String> warnings = new ArrayList<>();
        SmssCredentialReader.SmssMetadata selected = null;
        SmssCredentialReader.SmssMetadata firstCandidate = null;
        boolean allCandidatesConfirmedNonH2 = !candidates.isEmpty();
        // A file directly in db/ shares its directory with every engine profile.
        // Alphabetical proximity is not an ownership relationship. Match its URL
        // before relocating it, or the relocation would hide a wrong-profile match.
        boolean sharedDbDirectory = explicitCandidates == null && isSharedDbDirectory(target.databaseFile());
        int matchingProfiles = 0;

        for (Path candidate : candidates) {
            SmssCredentialReader.SmssMetadata metadata = SmssCredentialReader.readMetadata(
                    candidate, config.primaryDefaultUsername(), config.defaultPassword());
            if (firstCandidate == null) firstCandidate = metadata;
            if (metadata.confirmedH2()) {
                if (!sharedDbDirectory || referencesRootDatabase(metadata.connectionUrl(), target.databaseFile())) {
                    matchingProfiles++;
                    if (selected == null) selected = metadata;
                }
            } else if (!metadata.confirmedH2()) {
                warnings.add("Ignoring non-H2 SMSS " + candidate + " (backend="
                        + metadata.backend() + ")");
            }
            if (!("POSTGRES".equals(metadata.backend()) || "SQLITE".equals(metadata.backend()))) {
                allCandidatesConfirmedNonH2 = false;
            }
        }

        if (sharedDbDirectory && matchingProfiles != 1) {
            return target.unresolved(matchingProfiles == 0
                            ? "No H2 SMSS profile references this root-level database file"
                            : "Multiple H2 SMSS profiles reference this root-level database file; resolve the ambiguity before migration",
                    null, "UNRESOLVED", warnings);
        }

        if (selected == null) {
            if (target.targetType() == TargetType.SYSTEM_H2 && allCandidatesConfirmedNonH2) {
                // migrate the retained file only, with the default system credentials;
                // the non-H2 SMSS is never edited (service only updates H2 SMSS files)
                warnings.add("Base system database is configured for " + firstCandidate.backend()
                        + "; migrating the retained H2 file only, SMSS is left unchanged");
                return target.resolved(
                        CredentialSource.RETAINED_SYSTEM_DEFAULT,
                        new SmssCredentialReader.Credentials(
                                config.defaultUsername(), config.defaultPassword()),
                        firstCandidate.path(),
                        firstCandidate.backend(),
                        RuntimeConnectionUrlBuilder.forH2Nio(target.databaseFile()),
                        warnings);
            }
            Path nearest = candidates.isEmpty() ? null : candidates.get(0);
            String backend = candidates.isEmpty() ? "MISSING" : "NON_H2_OR_UNKNOWN";
            String reason = candidates.isEmpty()
                    ? "No companion H2 SMSS profile was found"
                    : "No confirmed H2 SMSS profile was found; non-H2 credentials will not be used";
            return target.unresolved(reason, nearest, backend, warnings);
        }

        List<String> unsupportedSettings = RuntimeConnectionUrlBuilder.unsupportedSettings(
                selected.connectionUrl());
        String configuredRuntimeUrl = selected.connectionUrl();
        if (!unsupportedSettings.isEmpty()) {
            warnings.add("Migration will remove unsupported H2 URL settings from "
                    + selected.path() + ": " + String.join(", ", unsupportedSettings));
            configuredRuntimeUrl = RuntimeConnectionUrlBuilder.removeUnsupportedSettings(
                    configuredRuntimeUrl);
        }

        String runtimeUrl;
        try {
            runtimeUrl = RuntimeConnectionUrlBuilder.fromSmss(
                    target.databaseFile(), configuredRuntimeUrl);
        } catch (IllegalArgumentException e) {
            return target.unresolved(e.getMessage(), selected.path(), selected.backend(), warnings);
        }

        boolean fallback = selected.path().getFileName().toString().toLowerCase().contains("_h2.smss");
        return target.resolved(
                fallback ? CredentialSource.H2_FALLBACK_SMSS : CredentialSource.H2_SMSS,
                selected.credentials(),
                selected.path(),
                selected.backend(),
                runtimeUrl,
                warnings);
    }

    private static boolean isSharedDbDirectory(Path file) {
        Path parent = file.getParent();
        if (parent == null || parent.getFileName() == null) return false;
        String name = parent.getFileName().toString().toLowerCase(Locale.ROOT);
        return "db".equals(name) || "semoss-db".equals(name);
    }

    private static boolean referencesRootDatabase(String url, Path file) {
        if (url == null || !url.regionMatches(true, 0, "jdbc:h2:", 0, 8)) return false;
        String location = url.substring(8).split(";", 2)[0].replace('\\', '/');
        String lower = location.toLowerCase(Locale.ROOT);
        if (lower.startsWith("tcp:") || lower.startsWith("ssl:") || lower.startsWith("mem:")) return false;
        if (lower.startsWith("nio:")) location = location.substring(4);
        String base = DatabaseExporter.stripExtension(file).replace('\\', '/');
        // Keep the db/<basename> identity when the SEMOSS home has been copied.
        // This also handles @BaseFolder@/db/<basename> without reading live config.
        String suffix = "/" + file.getParent().getFileName() + "/"
                + Path.of(DatabaseExporter.stripExtension(file)).getFileName();
        return location.equals(base) || location.endsWith(suffix);
    }
}
