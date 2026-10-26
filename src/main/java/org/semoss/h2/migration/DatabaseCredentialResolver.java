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

        for (Path candidate : candidates) {
            SmssCredentialReader.SmssMetadata metadata = SmssCredentialReader.readMetadata(
                    candidate, config.defaultUsername(), config.defaultPassword());
            if (firstCandidate == null) firstCandidate = metadata;
            if (metadata.confirmedH2() && selected == null) {
                selected = metadata;
            } else if (!metadata.confirmedH2()) {
                warnings.add("Ignoring non-H2 SMSS " + candidate + " (backend="
                        + metadata.backend() + ")");
            }
            if (!("POSTGRES".equals(metadata.backend()) || "SQLITE".equals(metadata.backend()))) {
                allCandidatesConfirmedNonH2 = false;
            }
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
}
