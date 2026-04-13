package org.semoss.h2.migration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects per-database migration results and writes a JSON summary report.
 * {@link #toMap()} returns the structured report; {@link #toJson()} serialises it via Gson.
 */
public final class MigrationReport {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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
     * Return the report as a structured Map (suitable for further processing).
     */
    public Map<String, Object> toMap() {
        if (endTime == null) finish();
        long totalDuration = Duration.between(startTime, endTime).toMillis();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", totalCount());
        summary.put("success", successCount());
        summary.put("failed", failedCount());
        summary.put("skipped", skippedCount());
        summary.put("durationMs", totalDuration);
        summary.put("startTime", startTime.toString());
        summary.put("endTime", endTime.toString());

        List<Map<String, Object>> databases = new ArrayList<>();
        List<DatabaseResult> sorted = new ArrayList<>(results);
        sorted.sort(Comparator.comparing(r -> r.status.name()));
        for (DatabaseResult r : sorted) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", r.path);
            entry.put("status", r.status.name());
            entry.put("reason", r.reason);
            entry.put("durationMs", r.durationMs);
            databases.add(entry);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("summary", summary);
        report.put("databases", databases);
        return report;
    }

    /**
     * Produce a pretty-printed JSON string of the report.
     */
    public String toJson() {
        return GSON.toJson(toMap());
    }

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
}
