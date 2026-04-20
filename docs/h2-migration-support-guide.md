# H2 Database Migration Support Guide

## For: Field Teams, Support Engineers, and Customers

This guide explains what can go wrong during H2 database migration (1.4.200 → 2.4.240) and how to resolve each issue.

---

## Quick Reference: Common Issues and Solutions

| Error Message | Likely Cause | Quick Fix |
|---|---|---|
| `Unknown data type: "DATETIME"` | Database schema uses legacy type names | ✅ Resolved — upgrade utility to latest version |
| `Syntax error in SQL statement "… CREATE TABLE FOO(VALUE VARCHAR …"` | Column or table named `VALUE`, `KEY`, `TOP`, etc. | ✅ Resolved — upgrade utility to latest version |
| `File corrupted` or `Wrong password` when opening DB | Database is encrypted with AES cipher | See **[Encrypted Databases](#encrypted-databases)** |
| `SELECT 1` succeeds but later Semoss queries fail with unknown functions | Legacy SQL functions like `GROUP_CONCAT()` no longer available | See **[Runtime Query Compatibility](#runtime-query-compatibility)** |
| Migration completes but database feels slower | Large exported SQL script exhausted memory during import | See **[Memory and Performance](#memory-and-performance)** |

---

## Category A: Legacy Data Types

### What the Customer Sees
```
Migration failed on database: test.mv.db
Error: Unknown data type: "DATETIME"
```

The utility rejects databases with column types no longer supported in H2 2.x. Common examples: `DATETIME`, `LONGVARCHAR`, `LONGVARBINARY`, `NUMBER`, `IDENTITY` (as a type).

### Why This Happens
H2 2.x renamed or removed data types for standards compliance. An older Semoss version or customer-uploaded data may have schema using the old names. When the migration utility reads the schema, H2 2.x doesn't recognize these types.

### How to Diagnose
Look at the error message — it names the unknown type. Common ones:
- `DATETIME` → should be `TIMESTAMP`
- `LONGVARCHAR` → should be `VARCHAR` or `CLOB`
- `LONGVARBINARY` → should be `VARBINARY` or `BLOB`
- `NUMBER` → should be `NUMERIC`

### How We Fixed It
The migration utility now automatically rewrites the schema export **before** importing it into H2 2.x. Our rewriter recognizes these legacy type names and converts them on the fly:
- `DATETIME` → `TIMESTAMP`
- `LONGVARCHAR` → `VARCHAR`
- `LONGVARBINARY` → `VARBINARY`
- `NUMBER` → `NUMERIC`
- And 8 others listed [in the detailed reference](#full-reference)

The rewrite is careful: it only touches type declarations, not string literals or function names.

### Resolution
**Update to the latest migration utility.** No action needed from the customer. The utility handles this automatically.

---

## Category B: Reserved Keywords in Column/Table Names

### What the Customer Sees
```
Migration failed on database: test.mv.db
Error: Syntax error in SQL statement "… CREATE TABLE FOO(VALUE VARCHAR …" expected identifier
```

H2 2.x made many new words "reserved keywords." If an older database has a column or table named `VALUE`, `KEY`, `TOP`, `YEAR`, etc., the import fails.

### Why This Happens
H2 2.x updated its SQL grammar to support new features (window functions, temporal queries). Some new syntax keywords collide with existing column names in customer databases.

### How to Diagnose
The error message shows the problematic CREATE TABLE statement. Look for column names that look like they're SQL keywords:
- `VALUE`, `KEY`, `TOP`, `ROW`
- `YEAR`, `MONTH`, `DAY`, `HOUR`, `MINUTE`, `SECOND`
- `WINDOW`, `RANGE`, `OFFSET`, `FETCH`

**Note:** Not every database has this issue. Only if your schema used these names as identifiers.

### How We Fixed It
Two-pronged defense:

1. **URL-level suppression:** When importing, we tell H2 2.x "please treat these words as normal identifiers, not keywords." The utility opens the target database with:
   ```
   MODE=LEGACY;NON_KEYWORDS=VALUE,KEY,TOP,YEAR,MONTH,...
   ```
   This re-allows them as column/table names.

2. **Automatic quoting for `ROW`:** H2's `NON_KEYWORDS` can't suppress `ROW` (a known limitation), so the utility automatically quotes bare `ROW` column references:
   ```sql
   -- Before: CREATE TABLE T (ROW INT)
   -- After:  CREATE TABLE T ("ROW" INT)
   ```
   But it's smart: `ROW(1, 2, 3)` value constructors and `ROW_NUMBER()` functions are left alone.

### Resolution
**Update to the latest migration utility.** No action needed from the customer. The utility handles this automatically.

---

## Category C: Custom Aggregates (SMSS_MEDIAN)

### What the Customer Sees
```
Migration completed successfully, but Semoss queries using SMSS_MEDIAN() fail
Error: Aggregate function "SMSS_MEDIAN" not found
```

Semoss uses a custom aggregate function `SMSS_MEDIAN`. During schema export, this function definition is included in the SQL dump. The migration utility can't import it because it doesn't have access to the Semoss code.

### Why This Happens
The schema export includes the full function definition. But the migration utility runs in an isolated environment with only H2 on its classpath. It can't re-register the function because the Java class `prerna.ds.rdbms.h2.H2MedianAggregation` isn't available.

### How to Diagnose
This only affects Semoss databases that use `SMSS_MEDIAN()` in views or stored procedures. Check:
1. Does the migration report mention `CREATE AGGREGATE`?
2. Does the migration report show `transformations.strippedAggregates` containing `SMSS_MEDIAN`?

### How We Fixed It
During the rewrite phase, the utility detects and removes `CREATE AGGREGATE` statements, preserving them as comments in the backup for your records:
```sql
-- [migration] stripped: CREATE AGGREGATE IF NOT EXISTS SMSS_MEDIAN FOR 'prerna.ds.rdbms.h2.H2MedianAggregation'
```

When Semoss reconnects to the migrated database for the first time, it re-registers `SMSS_MEDIAN` automatically. No data is lost; the aggregate is simply restored.

### Resolution
**Update to the latest migration utility and let it complete.** After migration, Semoss will automatically restore `SMSS_MEDIAN` on first connection. If you see the error above after migration, it means Semoss hasn't connected yet — wait a moment and retry.

---

## Category D: Legacy SQL Expressions in Views and Constraints

### What the Customer Sees
```
After migration, queries using views or INSERT statements fail:
Error: Function "GROUP_CONCAT" not found
Error: Function "CASEWHEN" not found
```

Some views or CHECK constraints in older databases use H2 1.x-only functions like `GROUP_CONCAT()` or `CASEWHEN()`. These fail when Semoss tries to use them after migration.

### Why This Happens
H2 2.x removed `GROUP_CONCAT` (replaced by `LISTAGG`) and `CASEWHEN` (replaced by `CASE WHEN`). These functions appear in views and constraints that survived the import phase but fail at runtime when the database tries to use them.

### How to Diagnose
This manifests as runtime failures, not migration failures. Look for:
1. Views that use `GROUP_CONCAT(... SEPARATOR ',')` or `CASEWHEN(...)`
2. CHECK constraints with these functions

### How We Fixed It
The utility opens the target database with `MODE=LEGACY`, which:
- Restores `GROUP_CONCAT` and `CASEWHEN` as aliases to their modern equivalents
- Relaxes boolean-to-integer comparisons (so `CHECK (flag = 1)` works when `flag` is BOOLEAN)
- Supports `MERGE INTO ... KEY(...)` syntax

`MODE=LEGACY` is persisted on the database itself, so every Semoss connection inherits it automatically.

### Resolution
**Update to the latest migration utility.** The `MODE=LEGACY` setting is applied during import and carried forward. No post-migration action needed.

If you have custom Semoss queries that also use `GROUP_CONCAT`, they'll work in the migrated database, but for portability consider updating them to use `LISTAGG` instead (this is a Semoss-side issue, not the migration utility).

---

## Category E: Large Database Exports (LOB Handling)

### What the Customer Sees
```
Migration process consumes all available memory and crashes
Error: java.lang.OutOfMemoryError
```

For very large databases (100+ MB in export form), the migration utility may run out of memory during the import phase.

### Why This Happens
H2 2.x imports using `RUNSCRIPT`, which reads large text files into memory. If a database contains massive CLOB (text) columns, the export file becomes very large, and a single INSERT statement with gigabytes of inline data exceeds the JVM's heap.

### How to Diagnose
Look at the migration report. It shows the exported script size:
```json
"transformations": {
  "scriptSizeBytes": 5368709120,
  "warnings": ["Script size 5.0 GB exceeds 50% of JVM max heap (10 GB)..."]
}
```

### How We Fixed It
The utility:
1. Tracks exported script size and JVM heap
2. Logs a warning if the script is larger than 50% of available heap
3. Records the warning in the migration report

### Resolution
**For very large databases:**
1. Allocate more memory to the migration utility. The launcher script uses `-Xmx` to set the JVM heap. Example:
   ```bash
   java -Xmx64g -jar migration-utility.jar ...
   ```
2. Consider exporting the database without large binary/text columns first, then loading them separately.
3. Consult the preserved export script path (shown in the migration report) — it contains the exact SQL that was being imported.

---

## Category F: Identity Columns and Sequences

### What the Customer Sees
```
After migration, INSERT statements fail:
Error: Syntax error in SQL statement "… IDENTITY …"
```

Older Semoss versions or customer databases may have columns using `IDENTITY` as a type, or auto-increment sequences configured differently.

### Why This Happens
H2 2.x changed how identity columns work:
- Old: `INT IDENTITY` or `INT AUTO_INCREMENT`
- New: `BIGINT GENERATED BY DEFAULT AS IDENTITY`

The syntax is incompatible.

### How We Fixed It
1. **Type-alias rewriting** (Category A) converts `IDENTITY` the type into `BIGINT GENERATED BY DEFAULT AS IDENTITY`.
2. **MODE=LEGACY** relaxes sequence constraints, supporting `CACHE 1` and other 1.x-style options.

### Resolution
**Update to the latest migration utility.** Both fixes happen automatically during migration. Test your application's INSERT statements post-migration to confirm auto-increment behavior works.

---

## Category G: Legacy H2 Database Format (PageStore)

### What the Customer Sees
```
Migration fails immediately:
Error: H2 2.x cannot open .h2.db files
```

Very old Semoss deployments (pre-2016) may use the legacy "PageStore" database format (`.h2.db` files instead of `.mv.db`).

### Why This Happens
H2 switched from PageStore to MVStore around 2015. H2 2.x dropped PageStore support entirely.

### How We Fixed It
The utility detects `.h2.db` files and automatically routes them through H2 1.4.200 (for export) and H2 2.4.240 (for import), converting to the modern `.mv.db` format in the process. No manual intervention needed.

### Resolution
**Update to the latest migration utility.** The utility handles this automatically. The migrated database will use the modern `.mv.db` format, which is smaller and faster.

---

## Category H: Encrypted Databases

### What the Customer Sees
```
Migration fails:
Error: EncryptedDatabaseException: Database appears to be CIPHER-encrypted. 
Utility does not support encrypted H2 migration; decrypt first.
```

If a customer enabled AES encryption on their H2 database (via `CIPHER=AES`), the migration utility can't proceed.

### Why This Happens
The migration utility reads the database file to detect encryption. Encrypted files have random-looking bytes that prevent normal H2 parsing. The utility can't decrypt without the cipher password, which it doesn't have.

### How to Diagnose
Look at the migration report:
```json
"errorClass": "EncryptedDatabaseException",
"status": "FAILED"
```

### How We Fixed It
The utility now detects encrypted `.mv.db` files early by:
1. Checking if the MVStore header is readable
2. Examining the first 64 bytes for high entropy (random = likely ciphertext)
3. Reporting a clear error instead of a cryptic "File corrupted" message

### Resolution
**Before migration:**
1. Decrypt the database. In H2, this typically means:
   ```sql
   BACKUP TO '<backup-file>' CIPHER AES PASSWORD '<cipher-password>';
   ```
2. Remove the encryption from the backup file.
3. Then migrate the decrypted database.

**If you don't have the cipher password:**
- This database cannot be automatically migrated.
- Contact Semoss support or your system administrator — they may have the password in deployment configuration.

---

## Category I: Deprecated H2 Connection Flags

### What the Customer Sees
```
Migration succeeds, but Semoss fails to connect post-migration:
Error: Unsupported parameter: "CACHE_TYPE=..."
```

Custom `.smss` files (Semoss security/configuration) may contain H2 connection flags that H2 2.x no longer supports.

### Why This Happens
Over H2's lifetime, various URL parameters have been added, changed, or deprecated. An `.smss` file with a custom `CONNECTION_URL` flag might reference something that no longer exists.

### How We Fixed It
After a successful migration, the utility "smoke tests" the connection:
1. Reopens the migrated database with the target connection URL
2. Runs a simple `SELECT 1` query
3. Records success or failure

If the smoke-open fails (e.g., due to an unsupported URL flag), the migration is marked as failed and the backup is restored.

### Resolution
**Check the migration report for** `transformations.smokeOpen`:
- If it says `"OK"` — the database opens fine under Semoss's normal configuration. No action needed.
- If it says `"FAILED"` — the post-migration connection has an issue (unsupported flag, missing credentials, etc.).

**To diagnose:**
1. Look at the migration report — it includes the SQLException details.
2. Check the companion `.smss` file for custom `CONNECTION_URL` flags.
3. Remove or update any deprecated flags.
4. Re-run the migration.

---

## Category J: Runtime Query Compatibility

### What the Customer Sees
```
After migration, some Semoss queries fail:
Error: Function "GROUP_CONCAT" not found
Error: Syntax error: "MERGE INTO ... KEY ..."
Error: Function "CASEWHEN" not found
```

Semoss itself uses some H2 1.x-only SQL syntax in its query builders. These queries fail at runtime against a migrated database.

### Why This Happens
This is a Semoss-side issue, not a migration utility issue. Semoss has internal SQL builders that generate queries with 1.x syntax. Those need to be updated for H2 2.x.

### How We Partially Fixed It
The migration utility applies `MODE=LEGACY` to the database, which restores many 1.x functions as compatibility aliases:
- `GROUP_CONCAT` → mapped to `LISTAGG`
- `CASEWHEN` → mapped to `CASE WHEN`
- `MERGE INTO ... KEY(...)` → supported

This handles most cases but is a stopgap. **The real fix is on the Semoss side**, which you'll receive in an updated Semoss build.

### Resolution
**Update both:**
1. Run the latest migration utility (for `MODE=LEGACY` support)
2. Update to the latest Semoss build (for H2 2.x-native query syntax)

---

## Category K: Detailed Error Reporting

### What the Customer Sees
```json
{
  "status": "FAILED",
  "reason": "Syntax error in SQL statement ...",
  "sqlState": "42000",
  "errorCode": 42001,
  "errorClass": "org.h2.jdbc.JdbcSQLSyntaxErrorException",
  "failingStatement": "CREATE TABLE FOO(VALUE VARCHAR(50)",
  "preservedScriptPath": "/backups/run1/test_export.sql"
}
```

When a migration fails, the report now contains detailed diagnostic information.

### How We Fixed It
The migration utility captures the full SQLException context:
- **sqlState** — ANSI SQL error state (e.g., `42000` = syntax error)
- **errorCode** — H2-specific numeric error code
- **errorClass** — Java exception class (e.g., `JdbcSQLSyntaxErrorException`)
- **failingStatement** — the exact SQL fragment that failed
- **preservedScriptPath** — absolute path to the exported script (for post-mortem inspection)
- **warnings[]** — non-blocking observations (views detected, aggregates stripped, heap warnings, etc.)

### Resolution
When migration fails:
1. Look at the `failingStatement` — this is the SQL that H2 2.x rejected.
2. Check `errorClass` and `sqlState` — these hint at the problem category.
3. Inspect `preservedScriptPath` — this is the actual SQL dump; support engineers can inspect it.
4. Look for matching issues in this guide — usually one of Categories A–J applies.
5. After resolving (e.g., fixing custom connection URL, updating schema), re-run the migration.

---

## Category L: Test Coverage and Confidence

### What It Means
The migration utility has been hardened with comprehensive test coverage, including:
- **Unit tests** — type-alias rewriting, keyword handling, aggregate stripping
- **Integration tests** — full 1.4.200 → 2.4.240 round-trips covering reserved keywords, legacy syntax, encrypted detection, PageStore conversion

All tests run against real H2 databases, not mocks, ensuring real-world compatibility.

### How We Fixed It
Every category above (A–K) has been tested with actual database export/import cycles. If you encounter a failure that doesn't match any of the above categories, please report it — this helps us expand coverage.

---

## Full Reference

### Supported Type-Alias Conversions

When the rewriter encounters these legacy types, they're automatically converted:

| Old Name (1.x) | New Name (2.x) | Notes |
|---|---|---|
| `DATETIME` | `TIMESTAMP` | Most common; confirmed in the wild |
| `LONGVARCHAR` | `VARCHAR` | May also use `CLOB` for very large strings |
| `LONGVARBINARY` | `VARBINARY` | May also use `BLOB` for very large binary |
| `LONGTEXT` | `CLOB` | Text LOB alias |
| `MEDIUMTEXT` | `VARCHAR` | Character string |
| `TINYTEXT` | `VARCHAR` | Small character string |
| `NTEXT` | `VARCHAR` | National character string |
| `MEDIUMBLOB` | `BLOB` | Binary LOB |
| `TINYBLOB` | `BLOB` | Small binary LOB |
| `NUMBER` | `NUMERIC` | Numeric type |
| `IDENTITY` (type) | `BIGINT GENERATED BY DEFAULT AS IDENTITY` | Auto-increment identity |

### Reserved Keywords Suppressed (NON_KEYWORDS)

These identifiers are re-allowed as column/table names:

```
VALUE, KEY, TOP, RANGE, INTERSECTS, WINDOW, OFFSET, FETCH,
SESSION_USER, LOCALTIME, LOCALTIMESTAMP, DAY, MONTH, YEAR,
HOUR, MINUTE, SECOND, CURRENT_CATALOG, CURRENT_PATH
```

**Note:** `ROW` cannot be suppressed via H2's `NON_KEYWORDS` mechanism (upstream limitation), so it's automatically quoted: `"ROW"`.

### URL Flags Applied During Migration

The target database is opened with:
```
MODE=LEGACY;
NON_KEYWORDS=VALUE,KEY,TOP,...;
DEFAULT_NULL_ORDERING=LOW;
DATABASE_TO_UPPER=TRUE
```

These flags are also recorded in the migration report for reference.

---

## FAQ

### Q: Will my data be lost?
**A:** No. The migration utility creates a backup of the original database before importing. If anything goes wrong, the original is restored. The migration report shows the backup location.

### Q: What if migration partially fails (some databases succeed, others fail)?
**A:** Each database is processed independently. If one fails, others continue. The final report shows success/failure counts. Failed databases are restored from backup; successful ones are migrated.

### Q: How long does migration take?
**A:** Depends on database size. As a rule of thumb: ~1 second per 10 MB of export size. Very large databases (50+ GB) may take hours. The utility logs progress.

### Q: Can I migrate while Semoss is running?
**A:** **No.** Stop Semoss before running the migration utility. The utility needs exclusive access to the database files.

### Q: What if I update Semoss first, then migrate the databases?
**A:** Not recommended. Migrate first, then update Semoss. Newer Semoss versions might expect H2 2.x, and older databases can't open with them.

### Q: Can I roll back after successful migration?
**A:** Yes. The migration utility preserves the original database. You can restore it from the backup directory shown in the migration report. However, rolling back requires stopping Semoss and manually swapping the database files back.

### Q: What should I do if I see a warning like "contains CREATE AGGREGATE"?
**A:** These are non-blocking observations. They tell you what the utility detected in your schema. For `CREATE AGGREGATE`, this is normal for Semoss databases — the aggregate is stripped during migration and re-registered by Semoss on reconnect.

### Q: How do I report a failure that doesn't match any of these categories?
**A:** Include the migration report JSON (especially `failingStatement`, `sqlState`, and `preservedScriptPath`) and share it with Semoss support. They can inspect the actual SQL that failed and help diagnose the issue.

### Q: Can the utility migrate encrypted databases?
**A:** No. Decrypt the database first (using H2's `BACKUP ... CIPHER AES` syntax), then migrate. The utility will detect encrypted databases and report a clear error with remediation steps.

### Q: What's the "smoke open" step in the report?
**A:** After a successful import, the utility reopens the database with Semoss's normal connection configuration and runs a simple `SELECT 1` to verify the post-migration connection works. This catches issues with deprecated URL flags or missing credentials. It's recorded as `transformations.smokeOpen`.

---

## Support Contact

If you encounter issues not covered here:
1. Collect the migration report (JSON output).
2. Preserve the backed-up original database.
3. Include the error details and any custom schema notes.
4. Contact Semoss support with this information.

We continuously expand this guide as new issues are discovered and resolved.
