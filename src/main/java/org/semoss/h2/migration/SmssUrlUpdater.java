package org.semoss.h2.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
            "(?im)^([\\t\\f ]*CONNECTION_URL(?:[\\t\\f ]*[=:][\\t\\f ]*|[\\t\\f ]+))([^\\r\\n]*?)(\\r?)$");

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
        // ISO-8859-1 round-trips every byte, so non-UTF-8 files are written back unchanged
        String content = new String(Files.readAllBytes(smssFile), StandardCharsets.ISO_8859_1);
        Matcher matcher = CONNECTION_URL_LINE.matcher(content);
        if (!matcher.find()) {
            throw new IOException("SMSS does not contain a CONNECTION_URL line: " + smssFile);
        }
        int start = matcher.start();
        String prefix = matcher.group(1);
        String rawUrl = matcher.group(2).strip();
        String lineEnd = matcher.group(3);
        int end = matcher.end();
        if (matcher.find()) {
            throw new IOException("SMSS has more than one CONNECTION_URL line: " + smssFile);
        }
        if (endsWithOddBackslashes(rawUrl)) {
            throw new IOException("SMSS CONNECTION_URL continues on the next line; not supported: " + smssFile);
        }
        if (!plan.originalUrl().equals(propertyValue(rawUrl))) {
            throw new IOException("SMSS CONNECTION_URL changed after preflight: " + smssFile);
        }
        // Strip settings from the raw text so escapes are kept, then check it parses as planned
        String sanitizedRaw = RuntimeConnectionUrlBuilder.removeUnsupportedSettings(rawUrl);
        if (!plan.sanitizedUrl().equals(propertyValue(sanitizedRaw))) {
            throw new IOException("SMSS CONNECTION_URL could not be rewritten safely: " + smssFile);
        }

        String updated = content.substring(0, start) + prefix + sanitizedRaw + lineEnd + content.substring(end);
        Path parent = smssFile.getParent();
        Path temp = Files.createTempFile(parent, smssFile.getFileName().toString(), ".tmp");
        try {
            Files.copy(smssFile, temp, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            Files.write(temp, updated.getBytes(StandardCharsets.ISO_8859_1));
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

    private static String propertyValue(String rawValue) throws IOException {
        return SmssCredentialReader.parseValues("CONNECTION_URL=" + rawValue).getOrDefault("CONNECTION_URL", "");
    }

    private static boolean endsWithOddBackslashes(String value) {
        int count = 0;
        for (int i = value.length() - 1; i >= 0 && value.charAt(i) == '\\'; i--) count++;
        return count % 2 == 1;
    }
}
