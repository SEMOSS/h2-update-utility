# Kubernetes: cloud-mode migration Job

Migrates every H2 object in SEMOSS remote storage before the 6.0.0 rollout.
One Job runs every scope in order (`semoss-db`, then `semoss-project`) through
`/app/migrate-all.sh`, writing one report folder per scope.

| File | Purpose |
|---|---|
| `pvc.yaml` | Optional: keeps `/work` (and the reports) after the pod; `job.yaml` defaults to an `emptyDir` |
| `secret.example.yaml` | Storage keys, only when the pod has no workload identity |
| `job.yaml` | The migration Job (dry run by default) |
| `report-reader.yaml` | PVC option only: mounts `/work` after the Job so report files can be copied out |

## Before you start

- Set the image tag in `job.yaml` and `report-reader.yaml` to a dated build (`YYYY-MM-DD`) instead of `latest`.
- Set the provider block in `job.yaml` (`SEMOSS_STORAGE_PROVIDER` plus that
  provider's bucket/region variables; see the main README's environment table).
- Credentials: give `serviceAccountName` bucket read/write via workload identity
  (IRSA, Azure workload identity/MSI, GKE Workload Identity), or create
  `h2-migration-storage` from `secret.example.yaml`. The pod needs write access:
  backups go to `_semoss-h2-migration/<runId>/backup/<semoss-db|semoss-project>/<id>` in the same bucket
  (Azure: `semoss-h2-migration` container).
- Azure: set `SCOPES` to every `db-<engineId>` and `project-<projectId>`
  container; the utility handles one container per invocation.
- Size `/work` (`emptyDir` `sizeLimit` and ephemeral-storage, or the PVC) for about 3x the largest single engine/project scope; each scope's
  local files are deleted once it finishes (a failed scope keeps its local backups).

## Run

```bash
NS=semoss
# kubectl -n $NS apply -f pvc.yaml             # only if using the PVC option in job.yaml
# kubectl -n $NS apply -f my-secret.yaml        # only without workload identity

# 1. dry run (SEMOSS can stay up; nothing in the bucket is changed)
kubectl -n $NS apply -f job.yaml
kubectl -n $NS logs -f job/h2-migration

# 2. save the log; each scope's JSON report is printed between
#    "=== BEGIN REPORT <scope>" and "=== END REPORT <scope>"
kubectl -n $NS logs job/h2-migration > h2-migration-dryrun.log
sed -n '/^=== BEGIN REPORT semoss-db$/,/^=== END REPORT/p' h2-migration-dryrun.log \
  | sed '1d;$d' > semoss-db-report.json
```

Save the log before deleting the Job: the default `emptyDir` work volume (and
the report files on it) is deleted with the pod. With the PVC option, the files
stay under `/work/<runId>/<scope>/` and `report-reader.yaml` mounts them for
`kubectl cp`.

Each scope's report should show only `SKIPPED` rows
with "Dry run - would migrate" or "Already H2 2.x". Fix any `FAILED` row first.

```bash
# 3. stop every SEMOSS pod that can reach the bucket
kubectl -n $NS scale deploy/<semoss> --replicas=0

# 4. real run: in job.yaml set DRY_RUN "false" and CONFIRM_SEMOSS_STOPPED "true"
kubectl -n $NS delete job h2-migration
kubectl -n $NS apply -f job.yaml
kubectl -n $NS logs -f job/h2-migration
kubectl -n $NS logs job/h2-migration > h2-migration-run.log
```

The log ends with `finished with exit 0` when every scope succeeded. A real run
stops at the first failed scope and the Job shows `Failed`; read its report,
fix the cause, save the log, delete the Job and apply it again (migrated files are skipped).
Then roll out SEMOSS 6.0.0 and scale it back up.

## Rollback

Every replaced object is backed up to `_semoss-h2-migration/<runId>/backup/<semoss-db|semoss-project>/<id>`
before promotion, and a failed promotion restores its scope automatically. To
undo a finished run, stop SEMOSS, copy the backup objects back over the
originals with your provider's tooling, and start the previous SEMOSS version.
Backups are never deleted by the utility.
