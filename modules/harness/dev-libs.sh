#!/usr/bin/env bash
# Copies the ignite-core release the harness is compiled against (plus its runtime dependencies)
# into modules/harness/target/dev-libs, so the harness can run locally without building Apache Ignite, and the same plus
# ignite-indexing and ignite-calcite into modules/harness/target/dev-libs-sql for the sql-* scenarios (IGNITE_FIREWATCH_FAKE_BUILD uses both).
# Usage: modules/harness/dev-libs.sh [output-dir]
set -euo pipefail
cd "$(dirname "$0")"
OUT="${1:-target/dev-libs}"
mkdir -p "$(dirname "$OUT")"
OUT="$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")"
OUT_SQL="$OUT-sql"
# both sets from throwaway poms: no build of this project is needed, and in the harness pom the modules' Jackson would
# lose to the harness's own
V=$(sed -n 's:.*<ignite.version>\(.*\)</ignite.version>.*:\1:p' ../../pom.xml)
dep() { echo "<dependency><groupId>org.apache.ignite</groupId><artifactId>$1</artifactId><version>$V</version></dependency>"; }
libs() { # name, output dir, artifacts
  local p="target/$1-pom"; mkdir -p "$p"
  echo "<project><modelVersion>4.0.0</modelVersion><groupId>local</groupId><artifactId>$1</artifactId><version>1</version>
  <dependencies>$(for a in "${@:3}"; do dep "$a"; done)</dependencies></project>" > "$p/pom.xml"
  mvn -q -f "$p/pom.xml" dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory="$2"
}
libs dev-libs "$OUT" ignite-core
libs dev-libs-sql "$OUT_SQL" ignite-core ignite-indexing ignite-calcite
echo "Ignite libs in $(cd "$OUT" && pwd) and $OUT_SQL:"
ls -1 "$OUT"
