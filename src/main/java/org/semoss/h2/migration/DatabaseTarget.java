package org.semoss.h2.migration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A discovered H2 file together with the SEMOSS-specific information required
 * to decide whether and how it may be migrated.
 *
 * <p>Credentials and the concrete runtime URL are deliberately kept out of the
 * JSON report. The report receives only the non-secret source/profile labels.
 */
public record DatabaseTarget(
        Path databaseFile,
        TargetType targetType,
        Disposition disposition,
        CredentialSource credentialSource,
        RuntimeProfile runtimeProfile,
        SmssCredentialReader.Credentials credentials,
        Path companionSmss,
        String companionBackend,
        String runtimeUrl,
        List<String> warnings,
        String resolutionError
) {

    public enum TargetType {
        SYSTEM_H2,
        USER_ENGINE_H2,
        ENGINE_AUDIT_H2,
        PROJECT_INSIGHTS_H2,
        PERSISTED_INSIGHT_FRAME_H2,
        TRANSIENT_INSIGHT_CACHE_H2,
        UNKNOWN_H2
    }

    public enum Disposition {
        MIGRATE,
        SKIP,
        MANUAL_REVIEW
    }

    public enum CredentialSource {
        UNRESOLVED,
        H2_SMSS,
        H2_FALLBACK_SMSS,
        GENERATED_AUDIT_RUNTIME,
        GENERATED_PROJECT_INSIGHTS_RUNTIME,
        H2_FRAME_RUNTIME,
        NOT_APPLICABLE
    }

    public enum RuntimeProfile {
        H2_SMSS,
        H2_NIO,
        H2_FRAME,
        NONE
    }

    public DatabaseTarget {
        databaseFile = Objects.requireNonNull(databaseFile, "databaseFile")
                .toAbsolutePath().normalize();
        targetType = Objects.requireNonNull(targetType, "targetType");
        disposition = Objects.requireNonNull(disposition, "disposition");
        credentialSource = Objects.requireNonNull(credentialSource, "credentialSource");
        runtimeProfile = Objects.requireNonNull(runtimeProfile, "runtimeProfile");
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    public static DatabaseTarget discovered(Path file, TargetType type,
                                            Disposition disposition,
                                            RuntimeProfile runtimeProfile) {
        CredentialSource source = disposition == Disposition.MIGRATE
                ? CredentialSource.UNRESOLVED
                : CredentialSource.NOT_APPLICABLE;
        String error = disposition == Disposition.MANUAL_REVIEW
                ? "Unrecognized H2 database location; operator classification is required"
                : null;
        return new DatabaseTarget(file, type, disposition, source, runtimeProfile,
                null, null, null, null, List.of(), error);
    }

    public DatabaseTarget resolved(CredentialSource source,
                                   SmssCredentialReader.Credentials resolvedCredentials,
                                   Path smss,
                                   String backend,
                                   String resolvedRuntimeUrl,
                                   List<String> additionalWarnings) {
        List<String> mergedWarnings = new ArrayList<>(warnings);
        if (additionalWarnings != null) mergedWarnings.addAll(additionalWarnings);
        return new DatabaseTarget(databaseFile, targetType, disposition, source,
                runtimeProfile, resolvedCredentials, smss, backend,
                resolvedRuntimeUrl, mergedWarnings, null);
    }

    public DatabaseTarget unresolved(String error, Path smss, String backend,
                                     List<String> additionalWarnings) {
        List<String> mergedWarnings = new ArrayList<>(warnings);
        if (additionalWarnings != null) mergedWarnings.addAll(additionalWarnings);
        return new DatabaseTarget(databaseFile, targetType, Disposition.MANUAL_REVIEW,
                CredentialSource.UNRESOLVED, runtimeProfile, null, smss, backend,
                null, mergedWarnings, error);
    }

    public DatabaseTarget skipped(String reason, Path smss, String backend,
                                  List<String> additionalWarnings) {
        List<String> mergedWarnings = new ArrayList<>(warnings);
        if (additionalWarnings != null) mergedWarnings.addAll(additionalWarnings);
        return new DatabaseTarget(databaseFile, targetType, Disposition.SKIP,
                CredentialSource.NOT_APPLICABLE, RuntimeProfile.NONE, null, smss, backend,
                null, mergedWarnings, reason);
    }

    public boolean readyToMigrate() {
        return disposition == Disposition.MIGRATE
                && credentials != null
                && runtimeUrl != null
                && resolutionError == null;
    }

    @Override
    public String toString() {
        return "DatabaseTarget[databaseFile=" + databaseFile
                + ", targetType=" + targetType
                + ", disposition=" + disposition
                + ", credentialSource=" + credentialSource
                + ", runtimeProfile=" + runtimeProfile
                + ", companionSmss=" + companionSmss
                + ", companionBackend=" + companionBackend
                + ", warnings=" + warnings
                + ", resolutionError=" + resolutionError + "]";
    }
}
