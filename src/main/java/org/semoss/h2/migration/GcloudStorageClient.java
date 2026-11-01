package org.semoss.h2.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Google Cloud Storage transport backed by the official gcloud storage CLI. */
public final class GcloudStorageClient implements CloudObjectStore {

    private static final Logger LOGGER = LogManager.getLogger(GcloudStorageClient.class);
    private static final Duration TIMEOUT = Duration.ofMinutes(10);
    private static final Duration BULK_TIMEOUT = Duration.ofMinutes(60);
    private static final int MAX_ERROR_LENGTH = 4096;

    record ProcessResult(int exitCode, String stdout, String stderr) {}

    @FunctionalInterface
    interface ProcessExecutor {
        ProcessResult execute(List<String> command, Map<String, String> environment,
                              Duration timeout) throws IOException, InterruptedException;
    }

    private record RemotePath(String bucket, String objectName) {
        String url() {
            return objectName.isBlank() ? "gs://" + bucket : "gs://" + bucket + "/" + objectName;
        }
    }

    private final String gcloudPath;
    private final String authenticationMode;
    private final Map<String, String> environment;
    private final List<String> redactions;
    private final ProcessExecutor executor;

    private GcloudStorageClient(String gcloudPath, String authenticationMode,
                                Map<String, String> environment, List<String> redactions,
                                ProcessExecutor executor) {
        this.gcloudPath = gcloudPath;
        this.authenticationMode = authenticationMode;
        this.environment = Map.copyOf(environment);
        this.redactions = List.copyOf(redactions);
        this.executor = executor;
    }

    public static GcloudStorageClient fromConfig(MigrationConfig config) {
        return fromConfig(config, new DefaultProcessExecutor(), System.getenv());
    }

    static GcloudStorageClient fromConfig(MigrationConfig config, ProcessExecutor executor) {
        return fromConfig(config, executor, Map.of());
    }

    static GcloudStorageClient fromConfig(MigrationConfig config, ProcessExecutor executor,
                                          Map<String, String> inheritedEnvironment) {
        if (CloudStorageLayout.fromConfig(config).provider() != CloudStorageLayout.Provider.GCS) {
            throw new IllegalArgumentException("gcloud transport requires the GCS provider");
        }
        if (present(config.gcsServiceAccountJson())) {
            throw new IllegalArgumentException(
                    "GCS_SERVICE_ACCOUNT_JSON is not supported by the gcloud backend; "
                            + "mount the JSON and use GCS_SERVICE_ACCOUNT_FILE");
        }

        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("CLOUDSDK_CORE_DISABLE_PROMPTS", "1");
        environment.put("CLOUDSDK_CORE_VERBOSITY", "error");
        List<String> redactions = new ArrayList<>();
        String authMode;
        if (present(config.gcsServiceAccountFile())) {
            environment.put("CLOUDSDK_AUTH_CREDENTIAL_FILE_OVERRIDE",
                    config.gcsServiceAccountFile());
            redactions.add(config.gcsServiceAccountFile());
            authMode = "GCP_GCLOUD_SERVICE_ACCOUNT_FILE";
        } else if (present(inheritedEnvironment.get("GOOGLE_APPLICATION_CREDENTIALS"))) {
            String credentialFile = inheritedEnvironment.get("GOOGLE_APPLICATION_CREDENTIALS");
            environment.put("CLOUDSDK_AUTH_CREDENTIAL_FILE_OVERRIDE", credentialFile);
            redactions.add(credentialFile);
            authMode = "GCP_GCLOUD_APPLICATION_CREDENTIALS";
        } else {
            authMode = "GCP_GCLOUD_ACTIVE_CONFIG";
        }

        String executable = present(config.gcloudPath())
                ? config.gcloudPath() : MigrationConfig.DEFAULT_GCLOUD;
        return new GcloudStorageClient(executable, authMode, environment,
                redactions, executor);
    }

    @Override
    public String authenticationMode() {
        return authenticationMode;
    }

    @Override
    public List<String> listFiles(String remotePath, String include)
            throws IOException, InterruptedException {
        RemotePath remote = parseRemotePath(remotePath);
        String pattern = validateInclude(include);
        String objectPattern = remote.objectName().isBlank()
                ? "**/" + pattern : remote.objectName() + "/**/" + pattern;
        String url = new RemotePath(remote.bucket(), objectPattern).url();
        ProcessResult result = execute(command("storage", "ls", "--json", url));
        if (result.exitCode() != 0 && isNoMatch(result.stderr())) return List.of();
        ensureSuccess(result, "gcloud storage ls");
        if (result.stdout().isBlank()) return List.of();

        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(result.stdout());
        } catch (RuntimeException e) {
            throw new IOException("gcloud storage ls returned invalid JSON", e);
        }
        if (!parsed.isJsonArray()) {
            throw new IOException("gcloud storage ls returned an unexpected response");
        }
        JsonArray objects = parsed.getAsJsonArray();
        List<String> files = new ArrayList<>();
        String prefix = remote.objectName().isBlank() ? "" : remote.objectName() + "/";
        for (JsonElement element : objects) {
            if (!element.isJsonObject()) continue;
            JsonObject wrapper = element.getAsJsonObject();
            JsonObject metadata = object(wrapper, "metadata");
            String name = string(metadata, "name");
            if (name.isBlank()) continue;
            if (!prefix.isBlank()) {
                if (!name.startsWith(prefix)) {
                    throw new IOException("gcloud listed an object outside the requested prefix");
                }
                name = name.substring(prefix.length());
            }
            files.add(CloudStorageLayout.validateRelativePath(name, "gcloud listed object"));
        }
        return List.copyOf(files);
    }

    @Override
    public CloudObjectMetadata stat(String remotePath)
            throws IOException, InterruptedException {
        RemotePath remote = parseRemotePath(remotePath);
        requireObject(remote);
        ProcessResult result = execute(command("storage", "objects", "describe",
                remote.url(), "--format=json"));
        ensureSuccess(result, "gcloud storage objects describe");
        JsonObject metadata;
        try {
            metadata = JsonParser.parseString(result.stdout()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("gcloud object describe returned invalid JSON", e);
        }
        long size = longValue(metadata, "size", -1L);
        String modTime = string(metadata, "update_time");
        Map<String, String> hashes = new LinkedHashMap<>();
        putIfPresent(hashes, "MD5", string(metadata, "md5_hash"));
        putIfPresent(hashes, "CRC32C", string(metadata, "crc32c_hash"));
        String generation = string(metadata, "generation");
        String metageneration = string(metadata, "metageneration");
        String version = generation.isBlank() ? ""
                : generation + (metageneration.isBlank() ? "" : ":" + metageneration);
        return new CloudObjectMetadata(size, modTime, hashes, version);
    }

    @Override
    public void download(String remotePath, Path localDest)
            throws IOException, InterruptedException {
        RemotePath remote = parseRemotePath(remotePath);
        requireObject(remote);
        Path destination = localDest.toAbsolutePath().normalize();
        if (destination.getParent() != null) Files.createDirectories(destination.getParent());
        ProcessResult result = execute(command("storage", "cp", remote.url(),
                destination.toString()));
        ensureSuccess(result, "gcloud storage cp download");
        LOGGER.info("Downloaded GCS object to " + destination);
    }

    @Override
    public void upload(Path localFile, String remotePath)
            throws IOException, InterruptedException {
        Path source = localFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IOException("Local upload source is not a regular file: " + source);
        }
        RemotePath remote = parseRemotePath(remotePath);
        requireObject(remote);
        ProcessResult result = execute(command("storage", "cp", source.toString(), remote.url()));
        ensureSuccess(result, "gcloud storage cp upload");
        LOGGER.info("Uploaded local object to GCS");
    }

    @Override
    public void delete(String remotePath) throws IOException, InterruptedException {
        RemotePath remote = parseRemotePath(remotePath);
        requireObject(remote);
        ProcessResult result = execute(command("storage", "rm", remote.url()));
        ensureSuccess(result, "gcloud storage rm");
    }

    // gcloud flattens wildcard copies; callers read the project id from the file
    @Override
    public void downloadProjectSmss(String root, Path localDir)
            throws IOException, InterruptedException {
        RemotePath remote = parseRemotePath(root);
        String pattern = remote.objectName().isBlank() ? "*-smss/*.smss" : remote.objectName() + "/*-smss/*.smss";
        Path destination = localDir.toAbsolutePath().normalize();
        Files.createDirectories(destination);
        ProcessResult result = execute(command("storage", "cp",
                new RemotePath(remote.bucket(), pattern).url(), destination + "/"), BULK_TIMEOUT);
        if (result.exitCode() != 0 && isNoMatch(result.stderr())) return;
        ensureSuccess(result, "gcloud storage cp project smss");
    }

    private ProcessResult execute(List<String> command)
            throws IOException, InterruptedException {
        return execute(command, TIMEOUT);
    }

    private ProcessResult execute(List<String> command, Duration timeout)
            throws IOException, InterruptedException {
        LOGGER.debug("Executing gcloud storage operation");
        ProcessResult raw;
        try {
            raw = executor.execute(command, environment, timeout);
        } catch (IOException e) {
            throw new IOException(sanitize(e.getMessage()));
        }
        return new ProcessResult(raw.exitCode(), redact(raw.stdout()), sanitize(raw.stderr()));
    }

    private List<String> command(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(gcloudPath);
        command.addAll(List.of(arguments));
        command.add("--quiet");
        command.add("--verbosity=error");
        return command;
    }

    private String sanitize(String text) {
        String value = redact(text);
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("<!doctype html") || lower.contains("<html")) {
            String title = extractTag(value, "title");
            String heading = extractTag(value, "h1");
            String detail = !title.isBlank() ? title : heading;
            return detail.isBlank() ? "Provider returned an HTML access error"
                    : "Provider returned an HTML access error: " + detail;
        }
        return value.length() <= MAX_ERROR_LENGTH
                ? value : value.substring(0, MAX_ERROR_LENGTH) + "...<truncated>";
    }

    private String redact(String text) {
        String value = text == null ? "" : text;
        for (String secret : redactions) value = value.replace(secret, "<redacted>");
        return value;
    }

    private static String extractTag(String value, String tag) {
        String lower = value.toLowerCase(Locale.ROOT);
        String open = "<" + tag;
        int start = lower.indexOf(open);
        if (start < 0) return "";
        start = lower.indexOf('>', start);
        if (start < 0) return "";
        int end = lower.indexOf("</" + tag + ">", start + 1);
        if (end < 0) return "";
        return value.substring(start + 1, end).replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static void ensureSuccess(ProcessResult result, String operation) throws IOException {
        if (result.exitCode() != 0) {
            throw new IOException(operation + " failed (exit " + result.exitCode() + "): "
                    + result.stderr());
        }
    }

    private static boolean isNoMatch(String error) {
        return error != null && error.toLowerCase(Locale.ROOT).contains("matched no objects");
    }

    private static RemotePath parseRemotePath(String path) {
        String normalized = normalizeRemotePath(path);
        int slash = normalized.indexOf('/');
        String bucket = slash < 0 ? normalized : normalized.substring(0, slash);
        String object = slash < 0 ? "" : normalized.substring(slash + 1);
        return new RemotePath(bucket, object);
    }

    private static String normalizeRemotePath(String path) {
        String normalized = path == null ? "" : path.trim().replace('\\', '/');
        if (normalized.isBlank()) throw new IllegalArgumentException("GCS remote path is required");
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")
                || normalized.contains("://")) {
            throw new IllegalArgumentException("GCS remote path must be relative");
        }
        for (String part : normalized.split("/")) {
            if (part.isBlank() || ".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("Unsafe GCS remote path: " + normalized);
            }
        }
        return normalized;
    }

    private static String validateInclude(String include) {
        if ("*.mv.db".equals(include) || "*.smss".equals(include) || "*.mosfet".equals(include)) return include;
        throw new IllegalArgumentException("Unsupported gcloud include pattern: " + include);
    }

    private static void requireObject(RemotePath remote) {
        if (remote.objectName().isBlank()) {
            throw new IllegalArgumentException("GCS object path is required");
        }
    }

    private static JsonObject object(JsonObject parent, String name) {
        JsonElement value = parent.get(name);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static long longValue(JsonObject object, String name, long fallback) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) return fallback;
        try {
            return value.getAsLong();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (present(value)) target.put(key, value);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static final class DefaultProcessExecutor implements ProcessExecutor {
        @Override
        public ProcessResult execute(List<String> command, Map<String, String> environment,
                                     Duration timeout) throws IOException, InterruptedException {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(false);
            builder.environment().putAll(environment);
            Process process = builder.start();

            try (ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<String> stdout = streams.submit(() -> new String(
                        process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                Future<String> stderr = streams.submit(() -> new String(
                        process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
                boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    process.waitFor();
                    throw new IOException("gcloud timed out after "
                            + timeout.toSeconds() + " seconds");
                }
                try {
                    return new ProcessResult(process.exitValue(), stdout.get(), stderr.get());
                } catch (ExecutionException e) {
                    throw new IOException("Failed to read gcloud process output", e.getCause());
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw e;
            }
        }
    }
}
