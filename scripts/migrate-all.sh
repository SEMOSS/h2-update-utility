#!/bin/sh
# Migrates the whole bucket: runs cloud mode once per scope in SCOPES (default semoss-db, then semoss-project).
# Dry runs cover every scope; a real run stops at the first failed scope.
# Each report is also printed to the log, since the default work volume is an emptyDir.
set -u

SCOPES="${SCOPES:-semoss-db semoss-project}"
WORK_DIR="${WORK_DIR:-/work}"
RUN_ID="${RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
DRY_RUN="${DRY_RUN:-true}"
rc=0

# extract with: kubectl logs job/h2-migration | sed -n '/^=== BEGIN REPORT semoss-db$/,/^=== END REPORT/p' | sed '1d;$d'
print_report() {
  if [ -f "$2" ]; then
    echo "=== BEGIN REPORT $1"
    cat "$2"
    echo
    echo "=== END REPORT $1"
  else
    echo "=== no report written for $1"
  fi
}

for scope in $SCOPES; do
  out="$WORK_DIR/$RUN_ID/$scope"
  tmp="$WORK_DIR/tmp"
  mkdir -p "$out" "$tmp"
  echo "=== scope $scope (dry run: $DRY_RUN) -> $out"
  # staging downloads go to java.io.tmpdir, so keep it on the work volume
  if SEARCH_PATH="$scope" BACKUP_DIR="$out" \
      java -Djava.io.tmpdir="$tmp" -jar /app/h2-migration-service.jar --mode=cloud "$@"; then
    echo "=== scope $scope ok"
  else
    rc=1
    echo "=== scope $scope FAILED; see REPORT $scope below"
    if [ "$DRY_RUN" != "true" ]; then
      echo "=== real run stopped; scopes after $scope were not run"
      break
    fi
  fi
  print_report "$scope" "$out/migration-report.json"
  rm -rf "$tmp"
done

echo "=== run $RUN_ID finished with exit $rc; reports printed above and under $WORK_DIR/$RUN_ID"
exit "$rc"
