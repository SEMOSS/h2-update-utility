# H2 Migration Service

Standalone Java service that migrates H2 databases from 1.4.x to 2.x, addressing critical CVEs (CVE-2021-42392, CVE-2022-23221, CVE-2021-23463, CVE-2022-45868).

Designed to run **before** upgrading Semoss to a version built against H2 2.x.

## Features

- **Local mode**: Scans a directory tree for `.mv.db` / `.h2.db` files and migrates them in-place
- **Cloud mode**: Uses RClone to discover, download, migrate, and re-upload H2 databases from S3, Azure Blob, GCS, or MinIO
- **Classloader isolation**: Loads H2 1.4.200 and 2.4.240 simultaneously via separate URLClassLoaders
- **Automatic backups**: Backs up every database before migration with restore-on-failure
- **SQL script preservation**: Export scripts are copied to the backup directory for post-mortem analysis
- **SMSS credential reading**: Reads USERNAME/PASSWORD from Semoss `.smss` config files
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

## Usage

### Local Mode

```bash
java -jar target/h2-migration-service.jar \
  --mode=local \
  --db-dir=/path/to/semoss/db \
  --backup-dir=/path/to/backups
```

### Cloud Mode (S3)

```bash
java -jar target/h2-migration-service.jar \
  --mode=cloud \
  --backup-dir=/tmp/h2-backups \
  --semoss-storage-provider=s3 \
  --s3-region=us-east-1 \
  --s3-bucket=my-semoss-bucket \
  --s3-access-key=AKID \
  --s3-secret-key=SECRET
```

#### AWS S3 Setup with Temporary Credentials (STS)

For testing or environments where long-lived credentials are not desirable, you can use
AWS STS to generate temporary access keys that expire automatically.

**1. Create an IAM policy** scoped to your bucket (IAM → Policies → Create policy → JSON):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject", "s3:ListBucket", "s3:DeleteObject"],
      "Resource": [
        "arn:aws:s3:::YOUR-BUCKET-NAME",
        "arn:aws:s3:::YOUR-BUCKET-NAME/*"
      ]
    }
  ]
}
```

**2. Create an IAM role** (IAM → Roles → Create role):

- Trusted entity: **AWS account → This account**
- Attach the policy created above
- Note the role ARN (e.g. `arn:aws:iam::123456789012:role/h2-migration-test`)

**3. Update the role's trust policy** to allow your identity to assume it
(IAM → Roles → your role → Trust relationships → Edit):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "AWS": "arn:aws:iam::123456789012:role/YOUR_CALLER_ROLE"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
```

> **Tip:** Run `aws sts get-caller-identity` to find your current identity ARN.
> If you are using AWS SSO / Identity Center, the role ARN may include a path prefix
> (e.g. `role/aws-reserved/sso.amazonaws.com/YourRole`). The trust policy principal
> must match the full ARN exactly.

**4. Assume the role** to get temporary credentials:

```bash
aws sts assume-role \
  --role-arn arn:aws:iam::123456789012:role/h2-migration-test \
  --role-session-name h2-migration-test \
  --duration-seconds 3600
```

> If the trust policy includes an `sts:ExternalId` condition, add `--external-id <value>`.

This returns `AccessKeyId`, `SecretAccessKey`, and `SessionToken` (valid for 1 hour).

**5. Run the migration** with the temporary credentials:

```bash
java -jar target/h2-migration-service.jar \
  --mode=cloud \
  --semoss-storage-provider=s3 \
  --s3-region=us-east-2 \
  --s3-bucket=my-semoss-bucket \
  --s3-access-key=<AccessKeyId> \
  --s3-secret-key=<SecretAccessKey> \
  --additional-rclone-parameters="--s3-session-token=<SessionToken>" \
  --dry-run
```

Or using environment variables:

```bash
export S3_ACCESS_KEY="<AccessKeyId>"
export S3_SECRET_KEY="<SecretAccessKey>"
export ADDITIONAL_RCLONE_PARAMETERS="--s3-session-token=<SessionToken>"

java -jar target/h2-migration-service.jar \
  --mode=cloud \
  --semoss-storage-provider=s3 \
  --s3-region=us-east-2 \
  --s3-bucket=my-semoss-bucket \
  --dry-run
```

> Start with `--dry-run` to verify bucket connectivity and database discovery
> before running an actual migration.

### Cloud Mode (MinIO)

```bash
java -jar target/h2-migration-service.jar \
  --mode=cloud \
  --backup-dir=/tmp/h2-backups \
  --storage-provider=minio \
  --minio-endpoint=http://localhost:9000 \
  --minio-region=us-east-1 \
  --minio-access-key=minioadmin \
  --minio-secret-key=minioadmin \
  --minio-bucket=semoss
```

### Narrowing the Search Path

Use `--search-path` to limit discovery to a subdirectory within the storage root (local or cloud):

```bash
java -jar target/h2-migration-service.jar \
  --mode=cloud \
  --backup-dir=/tmp/h2-backups \
  --search-path=db/ \
  --storage-provider=s3 \
  ...
```

### Dry Run

Add `--dry-run` to any command to preview without modifying files.

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
| `SEMOSS_STORAGE_PROVIDER` | Cloud provider: `s3`, `azure_blob`, `gcs`, `minio`, `local_file_system` |
| `S3_REGION`, `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY` | AWS S3 config |
| `AZ_ACCOUNT_NAME`, `AZ_PRIMARY_KEY`, `AZ_CONN_STRING` | Azure config |
| `AZ_USE_MSI`, `AZ_GENERATE_DYNAMIC_SAS` | Azure auth flags (`true`/`false`) |
| `GCS_SERVICE_ACCOUNT_FILE`, `GCS_BUCKET`, `GCS_REGION` | GCS config |
| `MINIO_ENDPOINT`, `MINIO_REGION`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`, `MINIO_BUCKET` | MinIO config |
| `RCLONE` | Path to rclone binary (default: `rclone`) |
| `ADDITIONAL_RCLONE_PARAMETERS` | Extra flags passed to every rclone invocation |

## Docker

```bash
# Build locally
docker build -t h2-migration-service:1.0.0 .

# TODO: instructions to pull from Docker Hub

# Run local mode
docker run -v /path/to/db:/data -v /path/to/backups:/backup \
  h2-migration-service:1.0.0 --mode=local --db-dir=/data --backup-dir=/backup

# Run cloud mode
docker run -e SEMOSS_STORAGE_PROVIDER=s3 \
  -e S3_REGION=us-east-1 -e S3_BUCKET=mybucket \
  -e S3_ACCESS_KEY=AKID -e S3_SECRET_KEY=SECRET \
  h2-migration-service:1.0.0 --mode=cloud --backup-dir=/backup
```

## Architecture

```
MigrationConfig         ← CLI/env/properties parsing
    ↓
H2MigrationService      ← Orchestrator
    ├── DatabaseDiscovery       ← Local file scan
    ├── CloudDatabaseDiscovery  ← RClone-based cloud scan
    ├── H2VersionDetector       ← Skip already-migrated DBs
    ├── SmssCredentialReader    ← Read DB credentials from .smss
    ├── BackupManager           ← Backup/restore
    ├── IsolatedH2Driver        ← Classloader isolation
    │   ├── DatabaseExporter    ← SCRIPT TO (H2 1.4.200)
    │   └── DatabaseImporter    ← RUNSCRIPT FROM (H2 2.4.240)
    ├── RCloneClient            ← Cloud storage operations
    └── MigrationReport         ← JSON report output
```

## Running Tests

```bash
mvn test
```

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

Semoss cloud clusters store **only user/client engine databases** in cloud storage (S3/MinIO/Azure/GCS). System databases (security, scheduler, LocalMasterDatabase, UserTrackingDatabase, Notification, etc.) are **not tracked in cloud storage** — they exist only on each pod's local filesystem and are synced through the container image or volume mounts.

This means cloud migration requires **two steps**:

1. **Cloud mode** — migrate user engine databases in the bucket:
   ```bash
   java -jar h2-migration-service.jar \
     --mode=cloud \
     --backup-dir=/tmp/h2-backups \
     --storage-provider=s3 \
     --s3-region=us-east-1 \
     --s3-bucket=my-semoss-bucket \
     --s3-access-key=AKID \
     --s3-secret-key=SECRET
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
2. Run `--mode=cloud` once to migrate user engines in the bucket
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
