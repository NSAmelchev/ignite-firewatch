#!/usr/bin/env bash
# Copies the ignite-core release the harness is compiled against (plus its runtime dependencies)
# into modules/harness/target/dev-libs, so the harness can run locally without building Apache Ignite, and the same plus
# ignite-indexing and ignite-calcite into modules/harness/target/dev-libs-sql for the sql-* scenarios (IGNITE_FIREWATCH_FAKE_BUILD uses both).
# Usage: modules/harness/dev-libs.sh [output-dir]
set -euo pipefail
cd "$(dirname "$0")"
OUT="${1:-target/dev-libs}"
mvn -q dependency:copy-dependencies -DincludeScope=provided -DoutputDirectory="$OUT"
# the SQL set from a throwaway pom: in the harness pom the modules' Jackson would lose to the harness's own
V=$(sed -n 's:.*<ignite.version>\(.*\)</ignite.version>.*:\1:p' ../../pom.xml)
P=target/dev-libs-sql-pom; mkdir -p "$P"
dep() { echo "<dependency><groupId>org.apache.ignite</groupId><artifactId>$1</artifactId><version>$V</version></dependency>"; }
echo "<project><modelVersion>4.0.0</modelVersion><groupId>local</groupId><artifactId>dev-libs-sql</artifactId><version>1</version>
  <dependencies>$(dep ignite-core)$(dep ignite-indexing)$(dep ignite-calcite)</dependencies></project>" > "$P/pom.xml"
OUT_SQL="$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")-sql"
mvn -q -f "$P/pom.xml" dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory="$OUT_SQL"
echo "Ignite libs in $(cd "$OUT" && pwd) and $OUT_SQL:"
ls -1 "$OUT"
