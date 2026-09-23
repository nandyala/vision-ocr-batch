#!/usr/bin/env bash
# Runs the batch job against Azure Document Intelligence.
# Settings: src/main/resources/application.properties, overridden by ./application-local.properties
set -euo pipefail
cd "$(dirname "$0")"
JAR=target/vision-ocr-batch.jar
[ -f "$JAR" ] || mvn -q -DskipTests package
exec java -Dconfig.file=application-local.properties -jar "$JAR" job-context.xml docExtractionJob -next
