package org.semoss.h2.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Secret-safe rclone transport for all supported cloud providers. */
public final class RCloneClient implements CloudObjectStore {

    private static final Logger LOGGER = LogManager.getLogger(RCloneClient.class);
    private static final Duration TIMEOUT = Duration.ofMinutes(10);
    private static final String REMOTE_NAME = "semossh2";
    private static final String ENV_PREFIX = "RCLONE_CONFIG_SEMOSSH2_";

    record ProcessResult(int exitCode, String stdout, String stderr) {}

    @FunctionalInterface
    interface ProcessExecutor {
        ProcessResult execute(List<String> command, Map<String, String> environment,
                              Duration timeout) throws IOException, InterruptedException;
    }

    private record RemoteSpec(String argument, Map<String, String> environment,
                              List<String> redactions) {}

    private final String rclonePath;
    private final CloudStorageLayout.Provider provider;
    private final String authenticationMode;
    private final Map<String, String> baseEnvironment;
    private final List<String> baseRedactions;
    private final List<String> additionalParams;
    private final ProcessExecutor executor;

    private RCloneClient(String rclonePath, CloudStorageLayout.Provider provider,
                         String authenticationMode, Map<String, String> baseEnvironment,
                         List<String> baseRedactions, List<String> additionalParams,
                         ProcessExecutor executor) {
        this.rclonePath = rclonePath;
        this.provider = provider;
        this.authenticationMode = authenticationMode;
        this.baseEnvironment = Map.copyOf(baseEnvironment);
        this.baseRedactions = List.copyOf(baseRedactions);
        this.additionalParams = List.copyOf(additionalParams);
        this.executor = executor;
    }

    public static RCloneClient fromConfig(MigrationConfig config) {
        return fromConfig(config, new DefaultProcessExecutor());
    }

    static RCloneClient fromConfig(MigrationConfig config, ProcessExecutor executor) {
        CloudStorageLayout.Provider provider = CloudStorageLayout.fromConfig(config).provider();
        Map<String, String> environment = new LinkedHashMap<>();
        List<String> redactions = new ArrayList<>();
        String authMode;

        switch (provider) {
            case S3 -> {
                put(environment, "TYPE", "s3");
                put(environment, "PROVIDER", "AWS");
                putIfPresent(environment, "REGION", config.s3Region());
                boolean hasAccess = present(config.s3AccessKey());
                boolean hasSecret = present(config.s3SecretKey());
                if (hasAccess != hasSecret) {
                    throw new IllegalArgumentException(
                            "S3_ACCESS_KEY and S3_SECRET_KEY must be supplied together");
                }
                if (hasAccess) {
                    put(environment, "ACCESS_KEY_ID", config.s3AccessKey());
                    put(environment, "SECRET_ACCESS_KEY", config.s3SecretKey());
                    putIfPresent(environment, "SESSION_TOKEN", config.s3SessionToken());
                    addSecrets(redactions, config.s3AccessKey(), config.s3SecretKey(),
                            config.s3SessionToken());
                    authMode = present(config.s3SessionToken())
                            ? "AWS_STATIC_TEMPORARY" : "AWS_STATIC";
                } else {
                    put(environment, "ENV_AUTH", "true");
                    authMode = "AWS_DEFAULT_CHAIN";
                }
            }
            case MINIO -> {
                require(config.minioEndpoint(), "MINIO_ENDPOINT");
                require(config.minioAccessKey(), "MINIO_ACCESS_KEY");
                require(config.minioSecretKey(), "MINIO_SECRET_KEY");
                put(environment, "TYPE", "s3");
                put(environment, "PROVIDER", "Minio");
                put(environment, "ENDPOINT", config.minioEndpoint());
                putIfPresent(environment, "REGION", config.minioRegion());
                put(environment, "ACCESS_KEY_ID", config.minioAccessKey());
                put(environment, "SECRET_ACCESS_KEY", config.minioSecretKey());
                addSecrets(redactions, config.minioAccessKey(), config.minioSecretKey());
                authMode = "MINIO_STATIC";
            }
            case GCS -> {
                put(environment, "TYPE", "google cloud storage");
                putIfPresent(environment, "LOCATION", config.gcsRegion());
                if (present(config.gcsServiceAccountJson())) {
                    put(environment, "SERVICE_ACCOUNT_CREDENTIALS", config.gcsServiceAccountJson());
                    addSecrets(redactions, config.gcsServiceAccountJson());
                    authMode = "GCP_SERVICE_ACCOUNT_JSON";
                } else if (present(config.gcsServiceAccountFile())) {
                    put(environment, "SERVICE_ACCOUNT_FILE", config.gcsServiceAccountFile());
                    authMode = "GCP_SERVICE_ACCOUNT_FILE";
                } else {
                    put(environment, "ENV_AUTH", "true");
                    authMode = "GCP_DEFAULT_CHAIN";
                }
            }
            case AZURE -> {
                put(environment, "TYPE", "azureblob");
                boolean account = present(config.azAccountName()) || present(config.azPrimaryKey());
                if (account && !(present(config.azAccountName()) && present(config.azPrimaryKey()))) {
                    throw new IllegalArgumentException(
                            "AZ_ACCOUNT_NAME and AZ_PRIMARY_KEY must be supplied together");
                }
                boolean connection = present(config.azConnString());
                int modes = (config.azUseMsi() ? 1 : 0) + (connection ? 1 : 0) + (account ? 1 : 0);
                if (modes != 1) {
                    throw new IllegalArgumentException(
                            "Azure requires exactly one auth mode: managed identity, connection string, or account/key");
                }
                if (connection) {
                    put(environment, "CONNECTION_STRING", config.azConnString());
                    addSecrets(redactions, config.azConnString());
                    authMode = "AZURE_CONNECTION_STRING";
                } else if (config.azUseMsi()) {
                    put(environment, "ENV_AUTH", "true");
                    authMode = "AZURE_MANAGED_IDENTITY";
                } else {
                    put(environment, "ACCOUNT", config.azAccountName());
                    put(environment, "KEY", config.azPrimaryKey());
                    addSecrets(redactions, config.azPrimaryKey());
                    authMode = "AZURE_ACCOUNT_KEY";
                }
            }
            case LOCAL -> authMode = "LOCAL_FILE_SYSTEM";
            default -> throw new IllegalArgumentException("Unsupported provider: " + provider);
        }

        return new RCloneClient(config.rclonePath(), provider, authMode,
                environment, redactions, parseAdditionalParams(config.additionalRcloneParams()),
                executor);
    }

    public String authenticationMode() {
        return authenticationMode;
    }

    public List<String> listFiles(String remotePath, String include)
            throws IOException, InterruptedException {
        RemoteSpec remote = remote(remotePath);
        List<String> command = command("lsf", remote.argument());
        command.add("--recursive");
        command.add("--files-only");
        if (present(include)) command.add("--include=" + include);
        ProcessResult result = execute(command, remote);
        ensureSuccess(result, "rclone lsf");
        return result.stdout().lines().filter(line -> !line.isBlank()).toList();
    }

    public CloudObjectMetadata stat(String remotePath) throws IOException, InterruptedException {
        RemoteSpec remote = remote(remotePath);
        List<String> command = command("lsjson", remote.argument());
        command.add("--stat");
        command.add("--hash");
        ProcessResult result = execute(command, remote);
        ensureSuccess(result, "rclone lsjson --stat");
        JsonObject json = JsonParser.parseString(result.stdout()).getAsJsonObject();
        long size = json.has("Size") ? json.get("Size").getAsLong() : -1L;
        String modTime = json.has("ModTime") ? json.get("ModTime").getAsString() : "";
        Map<String, String> hashes = new LinkedHashMap<>();
        JsonElement hashesElement = json.get("Hashes");
        if (hashesElement != null && hashesElement.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : hashesElement.getAsJsonObject().entrySet()) {
                hashes.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return new CloudObjectMetadata(size, modTime, hashes);
    }

    public void download(String remotePath, Path localDest)
            throws IOException, InterruptedException {
        RemoteSpec remote = remote(remotePath);
        List<String> command = command("copyto", remote.argument(),
                localDest.toAbsolutePath().normalize().toString());
        ProcessResult result = execute(command, remote);
        ensureSuccess(result, "rclone copyto download");
        LOGGER.info("Downloaded remote object to " + localDest);
    }

    public void upload(Path localFile, String remotePath)
            throws IOException, InterruptedException {
        RemoteSpec remote = remote(remotePath);
        List<String> command = command("copyto",
                localFile.toAbsolutePath().normalize().toString(), remote.argument());
        ProcessResult result = execute(command, remote);
        ensureSuccess(result, "rclone copyto upload");
        LOGGER.info("Uploaded local object to remote storage");
    }

    public void delete(String remotePath) throws IOException, InterruptedException {
        RemoteSpec remote = remote(remotePath);
        ProcessResult result = execute(command("deletefile", remote.argument()), remote);
        ensureSuccess(result, "rclone deletefile");
    }

    private ProcessResult execute(List<String> command, RemoteSpec remote)
            throws IOException, InterruptedException {
        List<String> fullCommand = new ArrayList<>(command);
        fullCommand.addAll(additionalParams);
        LOGGER.debug("Executing rclone subcommand "
                + (fullCommand.size() > 1 ? fullCommand.get(1) : "unknown"));
        List<String> redactions = new ArrayList<>(baseRedactions);
        redactions.addAll(remote.redactions());
        ProcessResult raw;
        try {
            raw = executor.execute(fullCommand, remote.environment(), TIMEOUT);
        } catch (IOException e) {
            String message = e.getMessage() == null || e.getMessage().isBlank()
                    ? "rclone process failed" : e.getMessage();
            throw new IOException(redact(message, redactions));
        }
        return new ProcessResult(raw.exitCode(), redact(raw.stdout(), redactions),
                redact(raw.stderr(), redactions));
    }

    private RemoteSpec remote(String path) {
        String normalized = normalizeRemotePath(path);
        if (provider == CloudStorageLayout.Provider.LOCAL) {
            return new RemoteSpec(normalized, Map.of(), List.of());
        }
        if (normalized.startsWith("/")) {
            throw new IllegalArgumentException("Cloud remote path must be relative");
        }
        Map<String, String> environment = new LinkedHashMap<>(baseEnvironment);
        return new RemoteSpec(REMOTE_NAME + ":" + normalized,
                Map.copyOf(environment), List.of());
    }

    private List<String> command(String subcommand, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(rclonePath);
        command.add(subcommand);
        command.addAll(List.of(arguments));
        return command;
    }

    private static void ensureSuccess(ProcessResult result, String operation) throws IOException {
        if (result.exitCode() != 0) {
            throw new IOException(operation + " failed (exit " + result.exitCode() + "): "
                    + result.stderr());
        }
    }

    private static List<String> parseAdditionalParams(String value) {
        if (!present(value)) return List.of();
        List<String> tokens = tokenize(value);
        Set<String> valueOptions = Set.of("--ca-cert", "--timeout", "--contimeout",
                "--retries", "--low-level-retries", "--transfers", "--checkers");
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String lower = token.toLowerCase(Locale.ROOT);
            boolean allowed = lower.equals("--no-check-certificate");
            for (String option : valueOptions) {
                if (lower.startsWith(option + "=")) allowed = true;
            }
            if (valueOptions.contains(lower)) {
                if (i + 1 >= tokens.size() || tokens.get(i + 1).startsWith("--")) {
                    throw new IllegalArgumentException(
                            "Missing value for ADDITIONAL_RCLONE_PARAMETERS option: " + token);
                }
                i++;
                allowed = true;
            }
            if (!allowed) {
                throw new IllegalArgumentException(
                        "Unsupported ADDITIONAL_RCLONE_PARAMETERS option: " + token);
            }
        }
        return List.copyOf(tokens);
    }

    static List<String> tokenize(String value) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (quote != 0) {
                if (c == quote) quote = 0;
                else current.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (escaped || quote != 0) {
            throw new IllegalArgumentException("Malformed ADDITIONAL_RCLONE_PARAMETERS quoting");
        }
        if (current.length() > 0) tokens.add(current.toString());
        return tokens;
    }

    private static String normalizeRemotePath(String path) {
        String normalized = path == null ? "" : path.trim().replace('\\', '/');
        if (normalized.isBlank()) throw new IllegalArgumentException("Remote path is required");
        for (String part : normalized.split("/")) {
            if (".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("Unsafe remote path: " + normalized);
            }
        }
        return normalized;
    }

    private static void put(Map<String, String> environment, String key, String value) {
        environment.put(ENV_PREFIX + key, value);
    }

    private static void putIfPresent(Map<String, String> environment, String key, String value) {
        if (present(value)) put(environment, key, value);
    }

    private static void require(String value, String name) {
        if (!present(value)) throw new IllegalArgumentException(name + " is required");
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static void addSecrets(List<String> redactions, String... values) {
        for (String value : values) if (present(value)) redactions.add(value);
    }

    private static String redact(String text, List<String> secrets) {
        String redacted = text == null ? "" : text;
        for (String secret : secrets) {
            if (present(secret)) redacted = redacted.replace(secret, "<redacted>");
        }
        return redacted;
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
                    throw new IOException("rclone timed out after " + timeout.toSeconds() + " seconds");
                }
                try {
                    return new ProcessResult(process.exitValue(), stdout.get(), stderr.get());
                } catch (ExecutionException e) {
                    throw new IOException("Failed to read rclone process output", e.getCause());
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw e;
            }
        }
    }
}
