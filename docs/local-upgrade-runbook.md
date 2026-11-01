# Local upgrade runbook (JAR, assistant-driven)

Upgrades the H2 files of a local SEMOSS home from 1.4.x to 2.4.240 before
moving to SEMOSS 6.0.0. Written so an AI coding assistant can run it step by
step with the operator; steps marked **GATE** need the operator's explicit OK.

Files directly under `db/` share a directory with many engine profiles. The
utility requires exactly one H2 profile whose URL identifies that root-level
database (preserving `db/<basename>` when the home has been copied). Missing
or ambiguous matches require manual review; an unrelated profile is never
selected merely because its name sorts first.

## Rules for the assistant

- Never run a real migration while any SEMOSS/Tomcat process is using the home.
- Never delete, move, or overwrite the backups or the pre-migration copy.
- Do not edit `.smss` files by hand and do not print their contents; they hold
  credentials. The utility reads them itself.
- Stop and ask the operator on any `FAILED` result, any nonzero exit code you
  cannot explain from this runbook, or low disk space.
- Report the summary line and every non-`SUCCESS` row of each report verbatim.

## Inputs

Legacy `TEXT`, `LONGTEXT`, `MEDIUMTEXT`, `TINYTEXT`, and `NTEXT` column types
are rewritten to `CLOB` before import to preserve H2 1.4 LOB semantics. H2 2.x
accepting `TEXT` as `VARCHAR` is not sufficient schema preservation. Check JDBC
column types as well as row values when validating a migrated fixture.
ENUM values are written by H2 1.4 as 0-based ordinals and are rewritten to their
labels before import (2.x reads ordinals as 1-based). Indexes on `NULL`-typed
columns are stripped, and a never-initialized file is exported from a scratch
copy. See support guide Category M.
Files already converted to 2.x are skipped; this change does not repair their
column types. Test the fix on fresh copies of the pre-migration files.

| Item | Example |
|---|---|
| SEMOSS home (contains `db/` and `project/`) | `/opt/semosshome`, `C:\SEMOSS` |
| Utility JAR | `h2-migration-service.jar` from the [latest release](https://github.com/SEMOSS/h2-update-utility/releases) (check `h2-migration-service.jar.sha256`), or `mvn clean package` |
| Work folder outside the home | `/opt/h2-upgrade`, `C:\h2-upgrade` |
| Java | 21 or newer (`java -version`) |

Free space in the work folder: about 3x the total size of `*.mv.db` and
`*.h2.db` files under the home. Free space on the home's own disk: at least the
largest single H2 file, because each import is written next to its original
before it replaces it.

## 1. Stop SEMOSS (GATE)

Ask the operator to stop SEMOSS, then confirm nothing listens on its port and
no Tomcat/SEMOSS Java process is running (`ps -ef | grep -i tomcat`, or
`Get-Process java` on Windows). A file still in use fails the export; do not
work around it.

## 2. Copy the home

```bash
H=/opt/semosshome; W=/opt/h2-upgrade; J=$W/h2-migration-service.jar
mkdir -p "$W/pre-migration"
cp -Rp "$H/db" "$H/project" "$W/pre-migration/"
```

Windows: `robocopy C:\SEMOSS\db C:\h2-upgrade\pre-migration\db /E /COPY:DAT`
(same for `project`). This copy is the rollback point; the utility's own backup
directories are a second one.

## 3. Dry runs

Run `db/` and `project/` as separate passes, each with its own backup folder:

```bash
java -jar "$J" --mode=local --db-dir="$H/db"      --backup-dir="$W/bk-db"      --dry-run; echo "exit=$?"
java -jar "$J" --mode=local --db-dir="$H/project" --backup-dir="$W/bk-project" --dry-run; echo "exit=$?"
```

Use absolute paths, and point `--db-dir` at the real `db`/`project` folders:
classification depends on those folder names. Each pass writes
`<backup-dir>/migration-report.json`.

Read each report and sort the rows:

| Result | Meaning | Action |
|---|---|---|
| `SKIPPED`, already 2.x | File is already format 2+ | none |
| `SKIPPED`, already 2.x, "repaired .smss CONNECTION_URL" (dry run: "would remove") | File was migrated earlier; its `.smss` URL still had settings 2.x rejects | none |
| `SKIPPED`, "Dry run - interrupted migration found" | An earlier real run was killed mid-file | none: the real run restores it from the named backup and migrates it again |
| `SKIPPED`, `InsightCache` | Cache, rebuilt by SEMOSS | none |
| `SUCCESS` with warning "migrating the retained H2 file only" | System DB whose `.smss` points to Postgres/SQLite; the leftover H2 file is converted, `.smss` untouched | none |
| `FAILED`, "Unrecognized H2 database location" | H2 file the utility cannot classify, often a custom app's own file under `project/*/app_root/` | ask the operator: leave it, or handle it separately |
| `FAILED`, "locked by another process" or "lock file ... exists" | Something (usually SEMOSS) still has the file open; nothing was changed | stop SEMOSS and rerun; if nothing runs, report the stale `.lock.db` to the operator |
| `FAILED`, "Both X.h2.db and X.mv.db exist" | Two H2 files share one base name; neither was touched | ask the operator which one SEMOSS uses |
| `FAILED`, "Interrupted migration could not be recovered" | A marker from a killed run points at a missing backup | stop; restore that database from `pre-migration` |
| any other `FAILED` | credentials, corrupt file, missing `.smss` | stop; see the [support guide](h2-migration-support-guide.md) |

Exit code `0` means no failures; `1` means at least one `FAILED` row.

For a confirmed H2 primary profile, an absent username defaults to empty, as
in H2/SEMOSS. `--default-user=USER` (or `DEFAULT_USER`) explicitly overrides
that missing value. An explicit blank or named username in the profile is
preserved. Generated audit/project/frame credentials remain `sa` with an empty
password; retained system H2 files with only a non-H2 profile still default to
`sa`. A dry run does not authenticate, so inspect the real-run report as well.

## 4. Approve the real run (GATE)

Show the operator both summaries and every non-`SUCCESS` row. Continue only on
an explicit OK.

## 5. Real runs

Same commands without `--dry-run`, still with SEMOSS stopped:

```bash
java -jar "$J" --mode=local --db-dir="$H/db"      --backup-dir="$W/bk-db";      echo "exit=$?"
java -jar "$J" --mode=local --db-dir="$H/project" --backup-dir="$W/bk-project"; echo "exit=$?"
```

Each migrated file is checked for locks, backed up, exported with H2 1.4.200
and imported with 2.4.240 into a temp file next to it
(`<name>.h2-migrating-tmp.mv.db`). The temp file is verified and then moved over
the original in one atomic step; H2 `.smss` URLs drop settings 2.x rejects. A
failure before that move leaves the original untouched; a failure after it
restores the file from its backup.

If a real run is killed, rerun the same command. A `<name>.h2-migrating` marker
next to a file means it was replaced but not yet verified: the rerun restores it
from the backup named in the marker and migrates it again (the row shows
`recoveredInterruptedMigrationFrom`). Never delete a marker by hand.

## 6. Verify

- Rerun both dry runs: every row should now be `SKIPPED` (already 2.x), apart
  from rows the operator chose to leave.
- No 1.4 files remain (expect no output, except files the operator left):

  ```bash
  find "$H/db" "$H/project" -name '*.mv.db' -not -path '*InsightCache*' | while read -r f; do
    head -c 4096 "$f" | tr -c '[:print:]' '\n' | grep -q 'format:1' && echo "$f"
  done
  ```

- File ownership is unchanged (run the JAR as the SEMOSS OS user).

## 7. Upgrade and start SEMOSS (GATE)

Install SEMOSS 6.0.0. For an exploded `webapps/Monolith`, check
`WEB-INF/lib` has `h2-2.4.240.jar` and no `h2-1.4.200.jar` (copying a new WAR
over an exploded folder can leave the old jar behind). Start SEMOSS and search
the log for `90048`, `Unsupported database file version`, or `90113`
(unsupported URL setting). Open an engine, an insight, and a saved frame.

## Rollback

Stop SEMOSS, copy `pre-migration/db` and `pre-migration/project` back over the
home, and start the previous SEMOSS version. 2.x files cannot be opened by 1.4,
so the old version needs the old files.

## Windows notes

- Use PowerShell and `$LASTEXITCODE` for the exit code.
- Keep the work folder path short (backups nest the source path; 260-char limit).
- Pause antivirus real-time scanning on the home and work folder if exports
  fail with access-denied errors.
- `.smss` files are read the way SEMOSS reads them (Java properties,
  ISO-8859-1), so files saved in a Windows code page are fine. Do not re-save
  them.
