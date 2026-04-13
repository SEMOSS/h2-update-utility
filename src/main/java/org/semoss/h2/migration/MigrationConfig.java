package org.semoss.h2.migration;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Immutable configuration for the migration service.
 * Built from CLI arguments, environment variables, and/or a properties file.
 */
public record MigrationConfig(
        MigrationMode mode,
        Path dbDir,
        Path backupDir,
        boolean dryRun,
        String defaultUsername,
        String defaultPassword,
        // Cloud storage fields (used in cloud mode)
        String storageProvider,
        String s3Region,
        String s3Bucket,
        String s3AccessKey,
        String s3SecretKey,
        String azAccountName,
        String azPrimaryKey,
        String azConnString,
        boolean azUseMsi,
        boolean azGenerateDynamicSas,
        String gcsServiceAccountFile,
        String gcsBucket,
        String gcsRegion,
        String minioEndpoint,
        String minioRegion,
        String minioAccessKey,
        String minioSecretKey,
        String minioBucket,
        String rclonePath,
        String additionalRcloneParams,
        String searchPath
) {

    public enum MigrationMode {
        LOCAL, CLOUD
    }

    public static final String DEFAULT_USERNAME = "sa";
    public static final String DEFAULT_PASSWORD = "";
    public static final String DEFAULT_RCLONE = "rclone";

    /**
     * Build config by layering: defaults → properties file → env vars → CLI args.
     * Later sources override earlier ones. Cloud fields fall back to env vars
     * matching Semoss conventions (S3_REGION, AZ_ACCOUNT_NAME, etc.).
     */
    public static MigrationConfig fromArgs(String[] args) {
        Properties props = new Properties();

        // First pass: find --config flag to load properties file
        for (String arg : args) {
            if (arg.startsWith("--config=")) {
                String path = arg.substring("--config=".length());
                try (var in = new FileInputStream(path)) {
                    props.load(in);
                } catch (IOException e) {
                    throw new IllegalArgumentException("Cannot read config file: " + path, e);
                }
            }
        }

        // Layer env vars on top of properties (env wins)
        layerEnvVars(props);

        // Layer CLI args on top of everything
        for (String arg : args) {
            if (arg.startsWith("--config=")) continue; // already handled
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 0) {
                String key = arg.substring(2, eq).replace('-', '_').toUpperCase();
                props.setProperty(key, arg.substring(eq + 1));
            } else if (arg.startsWith("--")) {
                String key = arg.substring(2).replace('-', '_').toUpperCase();
                props.setProperty(key, "true");
            } else {
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }

        return buildFromProperties(props);
    }

    private static void layerEnvVars(Properties props) {
        String[] envKeys = {
                "MODE", "DB_DIR", "BACKUP_DIR", "DRY_RUN",
                "DEFAULT_USER", "DEFAULT_PASSWORD",
                "SEMOSS_STORAGE_PROVIDER",
                "S3_REGION", "S3_BUCKET", "S3_ACCESS_KEY", "S3_SECRET_KEY",
                "AZ_ACCOUNT_NAME", "AZ_PRIMARY_KEY", "AZ_CONN_STRING",
                "AZ_USE_MSI", "AZ_GENERATE_DYNAMIC_SAS",
                "GCS_SERVICE_ACCOUNT_FILE", "GCS_BUCKET", "GCS_REGION",
                "MINIO_ENDPOINT", "MINIO_REGION", "MINIO_ACCESS_KEY", "MINIO_SECRET_KEY",
                "MINIO_BUCKET",
                "RCLONE", "ADDITIONAL_RCLONE_PARAMETERS",
                "SEARCH_PATH"
        };
        for (String key : envKeys) {
            String val = System.getenv(key);
            if (val != null && !val.isEmpty()) {
                props.setProperty(key, val);
            }
        }
    }

    private static MigrationConfig buildFromProperties(Properties p) {
        String modeStr = p.getProperty("MODE", "local").toUpperCase();
        MigrationMode mode = MigrationMode.valueOf(modeStr);

        Path dbDir = null;
        if (p.containsKey("DB_DIR")) {
            dbDir = Path.of(p.getProperty("DB_DIR")).toAbsolutePath().normalize();
        }

        if (mode != MigrationMode.CLOUD && dbDir == null) {
            throw new IllegalArgumentException("--db-dir is required for mode=" + mode);
        }

        Path backupDir;
        if (p.containsKey("BACKUP_DIR")) {
            backupDir = Path.of(p.getProperty("BACKUP_DIR")).toAbsolutePath().normalize();
        } else if (dbDir != null) {
            backupDir = dbDir.resolve(".h2-backup");
        } else {
            backupDir = Path.of(System.getProperty("java.io.tmpdir"), "h2-migration-backup")
                    .toAbsolutePath().normalize();
        }

        String provider = p.getProperty("SEMOSS_STORAGE_PROVIDER", p.getProperty("STORAGE_PROVIDER", ""));
        if (mode == MigrationMode.CLOUD && provider.isEmpty()) {
            throw new IllegalArgumentException(
                    "SEMOSS_STORAGE_PROVIDER is required for mode=" + mode);
        }

        return new MigrationConfig(
                mode,
                dbDir,
                backupDir,
                bool(p, "DRY_RUN"),
                p.getProperty("DEFAULT_USER", DEFAULT_USERNAME),
                p.getProperty("DEFAULT_PASSWORD", DEFAULT_PASSWORD),
                provider,
                p.getProperty("S3_REGION", ""),
                p.getProperty("S3_BUCKET", ""),
                p.getProperty("S3_ACCESS_KEY", ""),
                p.getProperty("S3_SECRET_KEY", ""),
                p.getProperty("AZ_ACCOUNT_NAME", ""),
                p.getProperty("AZ_PRIMARY_KEY", ""),
                p.getProperty("AZ_CONN_STRING", ""),
                bool(p, "AZ_USE_MSI"),
                bool(p, "AZ_GENERATE_DYNAMIC_SAS"),
                p.getProperty("GCS_SERVICE_ACCOUNT_FILE", ""),
                p.getProperty("GCS_BUCKET", ""),
                p.getProperty("GCS_REGION", ""),
                p.getProperty("MINIO_ENDPOINT", ""),
                p.getProperty("MINIO_REGION", ""),
                p.getProperty("MINIO_ACCESS_KEY", ""),
                p.getProperty("MINIO_SECRET_KEY", ""),
                p.getProperty("MINIO_BUCKET", ""),
                p.getProperty("RCLONE", DEFAULT_RCLONE),
                p.getProperty("ADDITIONAL_RCLONE_PARAMETERS", ""),
                p.getProperty("SEARCH_PATH", "")
        );
    }

    private static boolean bool(Properties p, String key) {
        return "true".equalsIgnoreCase(p.getProperty(key, "false"));
    }

    public static String usage() {
        return """
                Usage: java -jar h2-migration-service.jar [OPTIONS]

                Modes:
                  --mode=local             Migrate local H2 databases (default)
                  --mode=cloud             Migrate H2 databases in cloud storage via RClone

                Local options:
                  --db-dir=PATH            Root directory containing H2 databases (required for local)
                  --backup-dir=PATH        Backup location (default: <db-dir>/.h2-backup)

                Common options:
                  --config=FILE            Load configuration from a properties file
                  --dry-run                Discover databases and report without migrating
                  --default-user=USER      Fallback H2 username (default: sa)
                  --default-password=PWD   Fallback H2 password (default: empty)
                  --search-path=PATH       Subdirectory within the storage root to search (default: root)

                Cloud storage (env vars or CLI flags):
                  --semoss-storage-provider=PROVIDER   s3, azure, gcs, minio, local_file_system
                  --s3-region=REGION       --s3-bucket=BUCKET   --s3-access-key=KEY   --s3-secret-key=SECRET
                  --az-account-name=NAME   --az-primary-key=KEY --az-use-msi          --az-conn-string=STR
                  --gcs-service-account-file=PATH      --gcs-bucket=BUCKET   --gcs-region=REGION
                  --minio-endpoint=URL     --minio-region=REGION --minio-access-key=KEY --minio-secret-key=SECRET
                  --minio-bucket=BUCKET
                  --rclone=PATH            Path to rclone binary (default: rclone)
                """;
    }
}
