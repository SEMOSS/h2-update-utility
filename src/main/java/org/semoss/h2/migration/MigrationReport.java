package org.semoss.h2.migration;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects per-database migration results and writes a JSON summary report.
 * No external JSON library — hand-produces a simple, valid JSON structure.
 */
public final class MigrationReport {

    public enum Status { SUCCESS, FAILED, SKIPPED }

    public record DatabaseResult(
            String path,
            Status status,
            String reason,
            long durationMs
    ) {}

    private final Instant startTime;
    private final List<DatabaseResult> results = new ArrayList<>();
    private Instant endTime;

    public MigrationReport() {
        this.startTime = Instant.now();
    }

    public void addResult(String path, Status status, String reason, long durationMs) {
        results.add(new DatabaseResult(path, status, reason, durationMs));
    }

    public void finish() {
        this.endTime = Instant.now();
    }

    public List<DatabaseResult> getResults() {
        return Collections.unmodifiableList(results);
    }

    public long totalCount() { return results.size(); }
    public long successCount() { return results.stream().filter(r -> r.status == Status.SUCCESS).count(); }
    public long failedCount() { return results.stream().filter(r -> r.status == Status.FAILED).count(); }
    public long skippedCount() { return results.stream().filter(r -> r.status == Status.SKIPPED).count(); }

    /**
     * Write the report as JSON to the given file.
     */
    public void writeJson(Path outputFile) throws IOException {
        if (endTime == null) finish();
        Files.createDirectories(outputFile.getParent());
        try (Writer w = Files.newBufferedWriter(outputFile)) {
            w.write(toJson());
        }
    }

    /**
     * Produce a JSON string of the report.
     */
    public String toJson() {
        if (endTime == null) finish();
        long totalDuration = Duration.between(startTime, endTime).toMillis();

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"summary\": {\n");
        sb.append("    \"total\": ").append(totalCount()).append(",\n");
        sb.append("    \"success\": ").append(successCount()).append(",\n");
        sb.append("    \"failed\": ").append(failedCount()).append(",\n");
        sb.append("    \"skipped\": ").append(skippedCount()).append(",\n");
        sb.append("    \"durationMs\": ").append(totalDuration).append(",\n");
        sb.append("    \"startTime\": \"").append(startTime).append("\",\n");
        sb.append("    \"endTime\": \"").append(endTime).append("\"\n");
        sb.append("  },\n");
        sb.append("  \"databases\": [\n");
        for (int i = 0; i < results.size(); i++) {
            DatabaseResult r = results.get(i);
            sb.append("    {\n");
            sb.append("      \"path\": ").append(jsonString(r.path)).append(",\n");
            sb.append("      \"status\": \"").append(r.status).append("\",\n");
            sb.append("      \"reason\": ").append(jsonString(r.reason)).append(",\n");
            sb.append("      \"durationMs\": ").append(r.durationMs).append("\n");
            sb.append("    }");
            if (i < results.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}");
        return sb.toString();
    }

    private static String jsonString(String value) {
        if (value == null) return "null";
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }
}
