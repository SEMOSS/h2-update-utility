package org.semoss.h2.migration;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Transaction-safe rewrite support for legacy H2 CONNECTION_URL settings. */
public final class SmssUrlUpdater {

    private static final Pattern CONNECTION_URL_LINE = Pattern.compile(
            "(?im)^([\\t ]*CONNECTION_URL[\\t ]+)([^\\r\\n]*)(\\r?)$");

    private SmssUrlUpdater() {}

    public record UpdatePlan(Path smssFile, String originalUrl, String sanitizedUrl,
                             List<String> removedSettings) {
        public UpdatePlan {
            smssFile = smssFile.toAbsolutePath().normalize();
            removedSettings = List.copyOf(removedSettings);
        }

        public boolean required() {
            return !removedSettings.isEmpty();
        }
    }

    public static UpdatePlan preview(Path smssFile) {
        SmssCredentialReader.SmssMetadata metadata = SmssCredentialReader.readMetadata(
                smssFile, "", "");
        String original = metadata.connectionUrl();
        List<String> removed = RuntimeConnectionUrlBuilder.unsupportedSettings(original);
        return new UpdatePlan(smssFile, original,
                RuntimeConnectionUrlBuilder.removeUnsupportedSettings(original), removed);
    }

    public static void apply(UpdatePlan plan) throws IOException {
        if (!plan.required()) return;

        Path smssFile = plan.smssFile();
        String content = Files.readString(smssFile);
        Matcher matcher = CONNECTION_URL_LINE.matcher(content);
        if (!matcher.find()) {
            throw new IOException("SMSS does not contain a CONNECTION_URL line: " + smssFile);
        }
        if (!matcher.group(2).trim().equals(plan.originalUrl())) {
            throw new IOException("SMSS CONNECTION_URL changed after preflight: " + smssFile);
        }

        String replacement = matcher.group(1) + plan.sanitizedUrl() + matcher.group(3);
        String updated = matcher.replaceFirst(Matcher.quoteReplacement(replacement));
        Path parent = smssFile.getParent();
        Path temp = Files.createTempFile(parent, smssFile.getFileName().toString(), ".tmp");
        try {
            Files.copy(smssFile, temp, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            Files.writeString(temp, updated);
            try {
                Files.move(temp, smssFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, smssFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
