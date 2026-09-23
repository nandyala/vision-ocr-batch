#!/usr/bin/env bash
# Train Document Intelligence models without the Studio (REST API 2024-11-30).
#
#   export AZURE_DI_ENDPOINT=https://<name>.cognitiveservices.azure.com
#   export AZURE_DI_KEY=<key>
#
#   # Neural extraction model from a container that holds files + .labels.json/.ocr.json (Studio output)
#   scripts/azure-train.sh model autopay-neural-v1 "<container SAS URL>" [prefix]
#
#   # Classifier: one folder per class inside the container
#   scripts/azure-train.sh classifier vision-ocr-classifier-v1 "<container SAS URL>" auto_pay_auth other
#
# The SAS URL needs read + list permission on the container.
set -euo pipefail
API="api-version=2024-11-30"
: "${AZURE_DI_ENDPOINT:?set AZURE_DI_ENDPOINT}"
: "${AZURE_DI_KEY:?set AZURE_DI_KEY}"
EP="${AZURE_DI_ENDPOINT%/}"

submit() {  # $1 = url, $2 = json body; prints Operation-Location
  curl -sS -D - -o /dev/null -X POST "$1" \
    -H "Ocp-Apim-Subscription-Key: $AZURE_DI_KEY" -H "Content-Type: application/json" -d "$2" \
    | tr -d '\r' | awk -F': ' 'tolower($1)=="operation-location"{print $2}'
}

wait_for() {  # $1 = operation url
  while true; do
    body=$(curl -sS "$1" -H "Ocp-Apim-Subscription-Key: $AZURE_DI_KEY")
    status=$(echo "$body" | sed -n 's/.*"status" *: *"\([a-zA-Z]*\)".*/\1/p' | head -1)
    echo "  status: $status"
    case "$status" in
      succeeded) echo "$body"; return 0 ;;
      failed|canceled) echo "$body"; return 1 ;;
    esac
    sleep 20
  done
}

case "${1:-}" in
  model)
    id=$2; sas=$3; prefix=${4:-}
    op=$(submit "$EP/documentintelligence/documentModels:build?$API" \
      "{\"modelId\":\"$id\",\"buildMode\":\"neural\",\"azureBlobSource\":{\"containerUrl\":\"$sas\",\"prefix\":\"$prefix\"}}")
    [ -n "$op" ] || { echo "build request rejected"; exit 1; }
    echo "Training neural model $id ..."; wait_for "$op" ;;
  classifier)
    id=$2; sas=$3; shift 3
    [ $# -gt 0 ] || { echo "list at least one class folder"; exit 1; }
    types=""
    for c in "$@"; do
      types+="\"$c\":{\"azureBlobSource\":{\"containerUrl\":\"$sas\",\"prefix\":\"$c/\"}},"
    done
    op=$(submit "$EP/documentintelligence/documentClassifiers:build?$API" \
      "{\"classifierId\":\"$id\",\"docTypes\":{${types%,}}}")
    [ -n "$op" ] || { echo "build request rejected"; exit 1; }
    echo "Training classifier $id ..."; wait_for "$op" ;;
  *)
    sed -n '2,15p' "$0"; exit 1 ;;
esac
