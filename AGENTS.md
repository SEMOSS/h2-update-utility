# AGENTS.md

Instructions for AI coding agents working in this repository. Two jobs are
covered:

1. **Upgrading a local SEMOSS home** from H2 1.4.200 to H2 2.4.240 with a
   backup and a rollback point (most readers want this).
2. **Changing the utility itself** (see "Working on the utility" at the end).

For cloud storage (S3, Azure, GCS, MinIO) use the Kubernetes Job in
[`deploy/k8s/README.md`](deploy/k8s/README.md), not this guide.

## Local upgrade: what you are doing

SEMOSS 6.0.0 ships `h2-2.4.240.jar`. H2 2.x cannot open files written by H2
1.4.x (error `90048`), so every H2 file under the SEMOSS home must be converted
**before** the new SEMOSS starts. This utility exports each file with H2 1.4.200
and re-imports it with H2 2.4.240 after backing it up. The import goes to a
temp file next to the original, is verified, and only then replaces it.

The detailed command reference is
[`docs/local-upgrade-runbook.md`](docs/local-upgrade-runbook.md). Follow it.
This file adds the rules, the preflight checks, and the known limitations you
must respect while doing so. If the two disagree, stop and ask the operator.

## Hard rules

- **Gates.** Three steps need the operator's explicit OK in chat: stopping
  SEMOSS, the real (non-dry) run, and starting the new SEMOSS. Do not assume a
  yes from an earlier step.
- **SEMOSS must be stopped.** Never run a real migration while any SEMOSS or
  Tomcat process can open the home. The utility refuses files that are locked
  when it reaches them, but cannot see a process that opens a file later.
- **Pass `--backup-dir` outside the SEMOSS home** (`$W/bk-db`, `$W/bk-project`)
  so backups sit next to the rollback copy. (The default `<db-dir>/.h2-backup`
  is never scanned as databases, but keeps backups inside the home.)
- **Never delete, move or overwrite** the pre-migration copy or any backup
  directory, including after a successful run. Cleanup is the operator's call.
- **Do not open, print or edit `.smss` files.** They hold credentials. The
  utility reads and updates them itself.
- **Two passes, never the home root.** Run `--db-dir=<home>/db` and
  `--db-dir=<home>/project` separately, each with its own backup dir. Use the
  real folder names: classification depends on them.
- **Run as the OS user that owns the SEMOSS files** so ownership is unchanged.
- **Do not improvise repairs.** No manual H2 shell, `RUNSCRIPT`, file renames
  or other H2 tools against the home. On any unexpected result, stop and report.
- **Report verbatim.** Give the operator each pass's `summary` line and every
  row whose `status` is not `SUCCESS`.

## Step by step

Set three variables first and use absolute paths throughout.

```bash
H=/path/to/semosshome            # contains db/ and project/
W=/path/to/h2-upgrade            # work folder OUTSIDE $H
J=$W/h2-migration-service.jar
```

Windows: use PowerShell, `$H="C:\SEMOSS"` etc., `robocopy` for copies and
`$LASTEXITCODE` for exit codes. See "Windows notes" in the runbook.

### 0. Preflight (no changes yet)

1. `java -version` must report 21 or newer.
2. Get the JAR into `$W`, either:
   - build it from this repo: `mvn -q clean package -DskipTests`, then copy
     `target/h2-migration-service.jar`; or
   - copy it out of the published image:
     `docker create --name h2u quay.io/semoss/h2-update-utility:latest`,
     `docker cp h2u:/app/h2-migration-service.jar "$J"`, `docker rm h2u`.
3. Confirm `$H/db` and `$H/project` exist and `$W` is not inside `$H`.
4. Disk space: the work folder needs about 3x the size of the H2 files. The
   SEMOSS home's disk also needs free space of at least the largest H2 file,
   because each import is written next to its original before replacing it.

   ```bash
   find "$H/db" "$H/project" \( -name '*.mv.db' -o -name '*.h2.db' \) -print0 | xargs -0 du -ck | tail -1
   find "$H/db" "$H/project" \( -name '*.mv.db' -o -name '*.h2.db' \) -print0 | xargs -0 du -k | sort -n | tail -1
   df -k "$W" "$H"
   ```

5. Note which SEMOSS build will be started afterwards. It must contain
   `h2-2.4.240.jar` and no `h2-1.4.200.jar` (runbook step 7).

### 1. Stop SEMOSS (GATE)

Ask the operator to stop SEMOSS. Then confirm no Tomcat/SEMOSS Java process is
running and nothing listens on its port. Do not continue until both are true.

### 2. Make the rollback copy

Runbook step 2: copy `$H/db` and `$H/project` to `$W/pre-migration/`. Check
that the copy completed (exit code, and file counts match) before going on.

### 3. Dry runs

Runbook step 3: one dry run for `db`, one for `project`, backups in
`$W/bk-db` and `$W/bk-project`. Each writes `<backup-dir>/migration-report.json`
with a `summary` object and a `databases` array (sorted by status). To list the
rows that need attention:

```bash
jq -r '.summary, (.databases[] | select(.status != "SUCCESS") | [.status, .path, .reason] | @tsv)' "$W/bk-db/migration-report.json"
```

Sort every non-`SUCCESS` row with the table in the runbook. Exit code `0` means
no failures, `1` means at least one `FAILED` row.

### 4. Approve the real run (GATE)

Show the operator both summaries and every non-`SUCCESS` row, say which
`FAILED` rows (if any) they are choosing to leave, and wait for an explicit OK.

### 5. Real runs

Runbook step 5: the same two commands without `--dry-run`. Run `db` first,
read its report, then run `project`. If the `db` pass has an unexpected
`FAILED` row, stop before the `project` pass.

If a real run is killed (crash, Ctrl-C, reboot), rerun the same command. Files
left half-done are restored from their backup and migrated again; the report
row shows `recoveredInterruptedMigrationFrom`. Do not repair them by hand.

### 6. Verify

Runbook step 6: rerun both dry runs (every migrated row should now be
`SKIPPED` as already 2.x) and run the "no 1.4 files remain" check.

### 7. Start the new SEMOSS (GATE)

Runbook step 7. After start, search the Tomcat log for `90048`,
`Unsupported database file version` and `90113`, and ask the operator to open
an engine, an insight, and a saved insight with a frame.

### Rollback

Stop SEMOSS, copy `$W/pre-migration/db` and `$W/pre-migration/project` back
over the home, and start the previous SEMOSS version. H2 1.4 cannot open 2.x
files, so the old SEMOSS needs the old files.

## Known limitations (as of 2026-10-30)

Fixed in the utility (no workaround needed): interrupted imports are recovered
on rerun; locked files are refused and never restored over; a `.h2.db` with a
same-base `.mv.db` fails without touching either; the backup dir is not
rescanned; already-2.x rows still get their `.smss` URL repaired; all H2 1.x
URL settings 2.4.240 rejects are removed; `.smss` files are read like SEMOSS
reads them (Java properties, ISO-8859-1), so any code page works.

| Limitation | What to do |
|---|---|
| The lock check runs once per file, just before it is migrated. A process that opens the file afterwards is not detected. | Keep SEMOSS stopped for the whole run (Gate 1). |
| A row `FAILED` with "Both X.h2.db and X.mv.db exist" needs a human choice of which file SEMOSS really uses. | Report it; the operator decides and moves the other file out before a rerun. |
| A row `FAILED` with "Interrupted migration could not be recovered" means the backup named in the marker is gone or unreadable. | Stop; restore that database from `pre-migration`, then ask the operator. Do not delete the `.h2-migrating` marker. |
| `.smss` URL settings outside the known H2 1.x list are kept. A custom or unknown setting can still make SEMOSS log `90113`. | Report the engine and setting to the operator. Do not edit the `.smss` yourself. |
| A `.smss` whose `CONNECTION_URL` is repeated or continued over several lines is not rewritten; that row fails and is restored. | Report the row to the operator. |

Error codes and fixes are in the
[support guide](docs/h2-migration-support-guide.md).

## Report to the operator

At the end, give the operator:

- the JAR source and the SEMOSS home, work folder and backup folders used;
- each pass's dry-run and real-run `summary` and exit code;
- every non-`SUCCESS` row and what was decided for it;
- the verify results (rerun summaries, remaining 1.4 files);
- where the rollback copy is, and a reminder that nothing was deleted.

## Working on the utility

- Java 21, Maven 3.9+. Build: `mvn clean package` produces
  `target/h2-migration-service.jar` (both H2 drivers, isolated class loaders).
- Tests: `mvn test` (unit), `mvn verify` (adds the local and local-filesystem
  cloud integration suites). Never point tests at a working SEMOSS home; use
  temporary copies.
- The H2 versions are pinned in two places: `pom.xml` (`h2.old.version`,
  `h2.new.version`) and `H2MigrationService` (`OLD_H2`, `NEW_H2`). Change both.
- Keep the shared export/import path in `DatabaseExporter` and
  `DatabaseImporter`. New SEMOSS file kinds belong in `DatabaseClassifier`,
  `DatabaseTarget` and `DatabaseCredentialResolver`, not in a second algorithm.
- Never log or report credentials; keep secrets out of rclone/gcloud arguments.
- When behavior or guarantees change, update `README.md`,
  `docs/local-upgrade-runbook.md` and the Known limitations table above.
- Commits: `type(scope): message` (`feat`, `fix`, `docs`, `build`, `ci`, ...).
