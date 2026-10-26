# H2 Migration Service

Standalone Java service that migrates H2 databases from 1.4.x to 2.x, addressing critical CVEs (CVE-2021-42392, CVE-2022-23221, CVE-2021-23463, CVE-2022-45868).

Licensed under the [Apache License 2.0](LICENSE).

Designed to run **before** upgrading [Semoss](https://github.com/SEMOSS/Semoss)
to a version built against H2 2.x. Local migration is intentionally aware of
the SEMOSS `db/`, `project/`, and `InsightCache/` layouts. Files outside a known
SEMOSS target class fail closed instead of being opened with guessed credentials.

## Features

- **Local mode**: Scans a directory tree for `.mv.db` / `.h2.db` files and migrates them in-place
- **Cloud mode**: Uses RClone to discover, download, migrate, and re-upload H2 databases from S3, Azure Blob, GCS, or MinIO
- **Classloader isolation**: Loads H2 1.4.200 and 2.4.240 simultaneously via separate URLClassLoaders
- **Automatic backups**: Backs up every database before migration with restore-on-failure
- **SQL script preservation**: Export scripts are copied to the backup directory for post-mortem analysis
- **SMSS credential validation**: Reads primary-database credentials only from a
  profile whose type, driver, or URL confirms that it is H2
- **SEMOSS target classification**: Distinguishes system stores, user engines,
  generated audit/insight databases, persisted insight-frame stores, and live
  `InsightCache` files before credentials are selected
- **Generated database profiles**: Uses the SEMOSS runtime `sa`/empty profile for
  audit, project-insight, and saved `H2_Store_*.mv.db` frame databases instead of
  borrowing a neighboring project/engine `.smss`
- **Backend-aware primary handling**: User-engine files require a confirmed H2
  `.smss`; base system files whose active `.smss` is confirmed PostgreSQL or
  SQLite have their retained H2 file migrated with the default system
  credentials (`--default-user`/`--default-password`), and the non-H2 `.smss` is
  left unchanged. If the defaults cannot open the file it is reported as
  `SKIPPED` and left untouched. Non-H2 credentials are never used to open a
  physical H2 file
- **Version detection**: Skips databases already in H2 2.x format (MVStore format ≥ 2)
- **Dry-run support**: Preview what would be migrated without changes
- **JSON report**: Writes a migration report with per-database status, sorted by outcome

## Prerequisites

- Java 21+
- Maven 3.9+ (for building)
- RClone (for cloud mode only)

## Build

```bash
mvn clean package
```

Produces `target/h2-migration-service.jar` — a fat JAR containing both H2 versions.

## Usage + Testing

### Local Mode

For a step-by-step local upgrade (including one driven by an AI assistant),
follow the [Local upgrade runbook](docs/local-upgrade-runbook.md).

```bash
java -jar target/h2-migration-service.jar \
  --mode=local \
  --db-dir=/path/to/semoss/db \
  --backup-dir=/path/to/backups
```

Run local SEMOSS homes as two maintenance passes while every SEMOSS process is
stopped:

```bash
# System stores, user engines, and per-engine audit databases
java -jar target/h2-migration-service.jar \
  --mode=local --db-dir=/path/to/semoss/db --backup-dir=/path/to/backups/db

# Project insight metadata and persisted saved-insight H2 frames
java -jar target/h2-migration-service.jar \
  --mode=local --db-dir=/path/to/semoss/project --backup-dir=/path/to/backups/project
```

`InsightCache/**` databases are reported as `SKIPPED`. Saved
`project/*/app_root/version/*/H2_Store_*.mv.db` files remain migration targets.
Unknown locations or primary databases without a confirmed H2 `.smss` are
reported as failures and produce a nonzero exit code, including during dry-run.
For confirmed H2 profiles, the utility removes the known H2 1.x-only
`EARLY_FILTER`, `QUERY_CACHE_SIZE`, `LOG`, and `UNDO_LOG` settings from the
persisted `.smss` after a successful database import. The `.smss` is backed up
first and restored with the database if the update or runtime-open validation
fails. Dry-run reports the planned settings removal without editing the file.

### Cloud storage layouts

S3, MinIO, and GCS use the same object layout inside one bucket:

```text
<bucket>/
├── semoss-db/
│   ├── <engineId>/
│   │   ├── database.mv.db
│   │   └── audit_log_database.mv.db
│   └── <engineId>-smss/
│       └── <engine-name>__<engineId>.smss
└── semoss-project/
    ├── <projectId>/
    │   ├── insights_database.mv.db
    │   └── app_root/version/<insightId>/H2_Store_<id>.mv.db
    └── <projectId>-smss/
        └── <project-name>__<projectId>.smss
```

The engine's `-smss` prefix is required when its primary `database.mv.db` is
present. The project `-smss` prefix is part of the SEMOSS storage layout, but
project insight and persisted-frame databases use generated H2 runtime profiles,
so the utility does not download that SMSS object for those migrations.

Azure uses one container for each engine/project and separate SMSS containers:

```text
container: db-<engineId>
├── database.mv.db
└── audit_log_database.mv.db

container: db-<engineId>-smss
└── <engine-name>__<engineId>.smss

container: project-<projectId>
├── insights_database.mv.db
└── app_root/version/<insightId>/H2_Store_<id>.mv.db

container: project-<projectId>-smss
└── <project-name>__<projectId>.smss
```

For S3, MinIO, and GCS, `SEARCH_PATH` must be `semoss-db`,
`semoss-db/<engineId>`, `semoss-project`, or `semoss-project/<projectId>`.
Azure processes one container per invocation, so `SEARCH_PATH` must be
`db-<engineId>` or `project-<projectId>`.

### Basic S3 example

Use the utility's `S3_*` variables for injected credentials. `S3_SESSION_TOKEN`
is required only for temporary STS credentials.

```bash
export MODE=cloud
export BACKUP_DIR=/tmp/h2-backups/s3
export SEMOSS_STORAGE_PROVIDER=s3
export S3_REGION=us-east-1
export S3_BUCKET=my-semoss-bucket
export S3_ACCESS_KEY='<access-key>'
export S3_SECRET_KEY='<secret-key>'
# export S3_SESSION_TOKEN='<session-token>'
export SEARCH_PATH=semoss-db
export DRY_RUN=true

java -jar target/h2-migration-service.jar
```

If `S3_ACCESS_KEY` and `S3_SECRET_KEY` are omitted, rclone uses the inherited
AWS default credential chain, including EKS/ECS/EC2 role credentials and the
standard `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, and `AWS_SESSION_TOKEN`
variables.

### Basic MinIO example

```bash
export MODE=cloud
export BACKUP_DIR=/tmp/h2-backups/minio
export SEMOSS_STORAGE_PROVIDER=minio
export MINIO_ENDPOINT='https://minio.example.com'
export MINIO_REGION=us-east-1
export MINIO_BUCKET=semoss
export MINIO_ACCESS_KEY='<access-key>'
export MINIO_SECRET_KEY='<secret-key>'
export SEARCH_PATH=semoss-db
export DRY_RUN=true

java -jar target/h2-migration-service.jar
```

For a private CA, mount the certificate and set, for example,
`ADDITIONAL_RCLONE_PARAMETERS='--ca-cert /var/run/secrets/minio/ca.crt'`.

### Basic GCS example

Mount the service-account JSON as a read-only file and pass its path:

```bash
export MODE=cloud
export BACKUP_DIR=/tmp/h2-backups/gcs
export SEMOSS_STORAGE_PROVIDER=gcs
export GCS_BUCKET=my-semoss-bucket
export GCS_REGION=us-east1
export GCS_TRANSFER_BACKEND=gcloud
export GCS_SERVICE_ACCOUNT_FILE=/var/run/secrets/gcp/service-account.json
export SEARCH_PATH=semoss-project
export DRY_RUN=true

java -jar target/h2-migration-service.jar
```

The `gcloud` backend uses `gcloud storage` for listing, metadata, downloads,
uploads, and deletes. The mounted credential path is passed to the child process
through `CLOUDSDK_AUTH_CREDENTIAL_FILE_OVERRIDE`; the JSON contents are never
placed in command arguments, logs, or reports. `GOOGLE_APPLICATION_CREDENTIALS`
is mapped to the same override when `GCS_SERVICE_ACCOUNT_FILE` is omitted.

`GCS_TRANSFER_BACKEND` defaults to `rclone` for backward compatibility. Set it
to `gcloud` to avoid rclone entirely for GCS. `GCLOUD` can override the binary
path and defaults to `gcloud`. The injected `GCS_SERVICE_ACCOUNT_JSON` value is
supported only by the rclone backend; use a read-only mounted file with the
gcloud backend.

### Basic Azure Blob example

The normal Azure setup uses a storage connection string:

```bash
export MODE=cloud
export BACKUP_DIR=/tmp/h2-backups/azure
export SEMOSS_STORAGE_PROVIDER=azure
export AZ_CONN_STRING='<azure-storage-connection-string>'
export SEARCH_PATH=db-ENGINE_ID
export DRY_RUN=true

java -jar target/h2-migration-service.jar
```

Do not also set `AZ_USE_MSI`, `AZ_ACCOUNT_NAME`, or `AZ_PRIMARY_KEY`; Azure
requires exactly one authentication mode. The connection string is passed to
rclone only through its child-process environment. It is never converted into a
different credential or placed in command arguments, logs, or reports.
The bundled migration image pins rclone `v1.74.2`, which supports Azure Blob's
direct `connection_string` configuration. When running the JAR outside the
image, use a connection-string-capable rclone release rather than the older
`v1.71.0` client.

### Running the approved actual migration

Every provider example above is intentionally a read-only dry run. Review
`$BACKUP_DIR/migration-report.json`, stop every SEMOSS node that can access the
selected storage scope, and then run:

```bash
export DRY_RUN=false
export CONFIRM_SEMOSS_STOPPED=true

java -jar target/h2-migration-service.jar
```

Run a separate invocation for `semoss-db` and `semoss-project`. For Azure, run
one invocation for each `db-<engineId>` or `project-<projectId>` container.

### Dry Run

Add `--dry-run` to preview without remote writes. Cloud dry-run still downloads
the selected databases and exact SMSS objects so it can verify H2 headers,
versions, credentials, target classifications, hashes, and planned URL changes.

The JSON report includes each database's `targetType`, `disposition`,
`credentialSource`, `runtimeProfile`, companion SMSS/backend when applicable,
and configuration warnings. Passwords and concrete runtime URLs are never
written to the report.

Actual cloud runs create a verified remote backup before replacing any source
object. S3, MinIO, and GCS retain backups under
`_semoss-h2-migration/<runId>/backup`; Azure retains them in the
`semoss-h2-migration` container. Candidate objects are removed after successful
scope verification, while backups remain until an operator deliberately removes
them. If publication fails after promotion begins, every changed object in that
engine/project scope is restored and hash-verified from the remote backup.

### Environment Variables

All CLI flags can be set via environment variables (matching Semoss conventions):

| Variable | Description |
|---|---|
| `MODE` | `local` or `cloud` |
| `DB_DIR` | Local database directory |
| `BACKUP_DIR` | Backup directory |
| `DRY_RUN` | `true`/`false` |
| `DEFAULT_USER` | Fallback DB username (default: `sa`) |
| `DEFAULT_PASSWORD` | Fallback DB password (default: empty) |
| `SEARCH_PATH` | Subdirectory within storage root to search |
| `CONFIRM_SEMOSS_STOPPED` | Required acknowledgement for actual cloud migration |
| `SEMOSS_STORAGE_PROVIDER` | Cloud provider: `s3`, `azure` (also accepts `azure_blob`), `gcs`, `minio`, `local_file_system` |
| `S3_REGION`, `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_SESSION_TOKEN` | AWS S3 config; static values are optional when workload identity is available |
| `AZ_ACCOUNT_NAME`, `AZ_PRIMARY_KEY`, `AZ_CONN_STRING` | Azure account/key or connection-string authentication |
| `AZ_USE_MSI` | Enable Azure managed-identity authentication instead of connection-string or account/key authentication |
| `GCS_SERVICE_ACCOUNT_FILE`, `GCS_SERVICE_ACCOUNT_JSON`, `GCS_BUCKET`, `GCS_REGION` | GCS credentials and bucket configuration |
| `GCS_TRANSFER_BACKEND` | GCS transfer implementation: `rclone` (default) or `gcloud` |
| `GCLOUD` | Path to the gcloud binary (default: `gcloud`) |
| `MINIO_ENDPOINT`, `MINIO_REGION`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`, `MINIO_BUCKET` | MinIO config |
| `RCLONE` | Path to rclone binary (default: `rclone`) |
| `ADDITIONAL_RCLONE_PARAMETERS` | Restricted transport options such as timeouts, retries, and CA certificate configuration |

## Docker and Kubernetes (cloud mode)

The container image is meant for cloud mode: a Kubernetes Job that migrates the
H2 objects in remote storage before the 6.0.0 rollout. For a local SEMOSS home,
use the JAR directly; see [Local upgrade runbook](docs/local-upgrade-runbook.md).

The image pins its base images (`maven:3.9.11-amazoncorretto-21-debian` to build,
`azul/zulu-openjdk:21.0.11-jre` to run), bundles rclone `v1.74.2` and gcloud
`577.0.0`, and runs as uid/gid `10001`. Override the bases with
`--build-arg BUILD_IMAGE=...` / `RUNTIME_IMAGE=...`, keeping both on the Java
release set by `maven.compiler.release` in `pom.xml` (21). Any Java 21+ runtime
produces identical files, since the conversion is done by the bundled H2 drivers.

```bash
docker build -t h2-migration-service:latest .

# TODO: instructions to pull from the published registry
```

### Kubernetes Job

Manifests and the step-by-step run order are in [`deploy/k8s/`](deploy/k8s/README.md).
One Job runs every scope in order (`SCOPES`, default `semoss-db semoss-project`)
through `/app/migrate-all.sh`:

- Start with `DRY_RUN=true`, read the reports, scale SEMOSS to zero, then rerun
  with `DRY_RUN=false` and `CONFIRM_SEMOSS_STOPPED=true`.
- A scratch `emptyDir` at `/work` (a PVC option is commented in `job.yaml`)
  holds download staging (`java.io.tmpdir`), local backups and one report per
  scope under `/work/<runId>/<scope>/`. Each report is also printed to the Job
  log between `=== BEGIN REPORT <scope>` and `=== END REPORT <scope>`. Durable
  backups live in the bucket under `_semoss-h2-migration/<runId>/backup`
  (Azure: `semoss-h2-migration`).
- A dry run checks every scope; a real run stops at the first failed scope.
  Exit code `1` (Job `Failed`) means at least one target failed.
- Azure needs `SCOPES` set to every `db-<engineId>` / `project-<projectId>`
  container.

### Image CI

`.github/workflows/docker-image.yml` runs the unit tests, builds the image,
smoke-tests it (uid, Java 21 JRE, rclone, gcloud, runner script) and pushes
`linux/amd64` + `linux/arm64` to `quay.io/semoss/h2-update-utility` (repo
variable `IMAGE_NAME` overrides). Tags: `vX.Y.Z` git tags push `X.Y.Z`, `X.Y`
and `latest`; `main` pushes `main` and `main-<sha>`; pull requests build
without pushing. Needs the `QUAY_ROBOT_USERNAME` / `QUAY_ROBOT_PASSWORD`
secrets.

### Local volumes with Docker

The image can also migrate a mounted SEMOSS home, though the JAR is the usual
path. Stop SEMOSS first, mount `db/` at `/data/db` and `project/` at
`/data/project` (engines are classified by the folder name, so a `db/` mounted
directly at `/data` reports every user engine as unrecognized), keep the backup
mount outside the database folder, and run as the owner of the files:

```bash
docker run --rm --user "$(id -u semoss):$(id -g semoss)" \
  -v /opt/semosshome/db:/data/db -v /path/to/backups/db:/backup \
  h2-migration-service:latest --mode=local --db-dir=/data/db --backup-dir=/backup --dry-run
```

## Architecture

```
MigrationConfig         ← CLI/env/properties parsing
    ↓
H2MigrationService      ← Orchestrator
    ├── DatabaseDiscovery       ← Local file scan
    ├── CloudDatabaseDiscovery  ← Provider-neutral cloud scan
    ├── CloudStorageLayout      ← Provider-aware SEMOSS scope mapping
    ├── CloudMigrationCoordinator ← Scope backup/candidate/promotion/rollback
    ├── H2VersionDetector       ← Skip already-migrated DBs
    ├── SmssCredentialReader    ← Read DB credentials from .smss
    ├── BackupManager           ← Backup/restore
    ├── IsolatedH2Driver        ← Classloader isolation
    │   ├── DatabaseExporter    ← SCRIPT TO (H2 1.4.200)
    │   └── DatabaseImporter    ← RUNSCRIPT FROM (H2 2.4.240)
    ├── CloudObjectStore        ← Provider-neutral storage operations
    │   ├── RCloneClient        ← S3, MinIO, Azure, and optional GCS transport
    │   └── GcloudStorageClient ← GCS-specific gcloud storage transport
    └── MigrationReport         ← JSON report output
```

## Running Tests

```bash
mvn test
```

## Troubleshooting and Support

For help diagnosing and resolving migration issues, see the [H2 Migration Support Guide](docs/h2-migration-support-guide.md). It explains common errors, why they occur, how to diagnose them, and how to resolve each issue.

## Deployment Scenarios

### Important: Stop Semoss Before Migrating

H2 databases hold file locks during operation. **All Semoss instances must be stopped** before running the migration tool to avoid corruption or lock conflicts.

### Single Node

Run the tool once against the Semoss `db/` directory:

```bash
java -jar h2-migration-service.jar \
  --mode=local \
  --db-dir=/opt/semoss/db \
  --backup-dir=/opt/semoss/h2-backups
```

### Non-Cloud Cluster (Shared Filesystem)

All nodes mount the same `db/` directory (NFS, shared volume, SAN). Since there is only one copy of each database file, run the migration **once from any node**:

1. Stop all Semoss nodes
2. Run the migration from any node with access to the shared filesystem
3. Upgrade all nodes to the Semoss version built against H2 2.x
4. Start all nodes

### Non-Cloud Cluster (Independent Local Storage)

Each node has its own `db/` directory (e.g. separate Docker volumes). Run the migration **on every node**:

1. Stop Semoss on all nodes
2. Run the migration on each node:
   ```bash
   # On each node
   java -jar h2-migration-service.jar \
     --mode=local \
     --db-dir=/opt/semoss/db \
     --backup-dir=/opt/semoss/h2-backups
   ```
   This can be automated via Ansible, a Kubernetes Job (one per PV), or an init container that runs before Semoss boots.
3. Upgrade and start all nodes

### Cloud-Enabled Cluster (`SEMOSS_IS_CLUSTER=true`)

Semoss cloud clusters can store durable user-engine and project databases in
cloud storage (S3/MinIO/Azure/GCS). System databases (security, scheduler,
LocalMasterDatabase, UserTrackingDatabase, Notification, etc.) are not cloud
migration targets; migrate any local system H2 files with local mode.

This means cloud migration requires **two steps**:

1. **Cloud mode** — migrate user engine databases in the bucket:
   ```bash
   java -jar h2-migration-service.jar \
     --mode=cloud \
     --backup-dir=/tmp/h2-backups \
     --semoss-storage-provider=s3 \
     --s3-region=us-east-1 \
     --s3-bucket=my-semoss-bucket \
     --search-path=semoss-db \
     --confirm-semoss-stopped
   ```

2. **Local mode on each pod** — migrate system databases that only exist locally:
   ```bash
   java -jar h2-migration-service.jar \
     --mode=local \
     --db-dir=/opt/semoss/db \
     --backup-dir=/opt/semoss/h2-backups
   ```
   Use a Kubernetes init container or Job to run this on every pod before Semoss starts.

Full procedure:

1. Stop all Semoss pods
2. Run separate cloud passes for `semoss-db` and `semoss-project` as applicable;
   each actual pass requires `--confirm-semoss-stopped`
3. Run `--mode=local` on each pod to migrate system databases
4. Upgrade all pods to the Semoss version built against H2 2.x
5. Start all pods — each will pull the migrated user engines from cloud storage on boot

## Migration Report

After migration, a JSON report is written to `{backup-dir}/migration-report.json`:

```json
{
  "summary": {
    "total": 5,
    "success": 4,
    "failed": 0,
    "skipped": 1,
    "durationMs": 12345
  },
  "databases": [...]
}
```
