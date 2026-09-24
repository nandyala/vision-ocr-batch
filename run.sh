#!/usr/bin/env bash
# ./run.sh      runs the batch job against Azure Document Intelligence
# ./run.sh ui   starts the demo web app on http://localhost:8080
# Settings: ocr-batch/src/main/resources/application.properties, overridden by ./application-local.properties
set -euo pipefail
cd "$(dirname "$0")"
JOB_JAR=ocr-batch/target/vision-ocr-batch.jar
UI_JAR=ocr-ui/target/vision-ocr-ui.jar
# always rebuild (fast when nothing changed) so a stale jar is never started; SKIP_BUILD=1 to skip
[ -n "${SKIP_BUILD:-}" ] || mvn -q -DskipTests package
if [ "${1:-}" = "ui" ]; then
  exec java -Dconfig.file=application-local.properties -jar "$UI_JAR"
fi
exec java -Dconfig.file=application-local.properties -jar "$JOB_JAR" job-context.xml docExtractionJob -next
