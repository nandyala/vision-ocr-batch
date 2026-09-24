#!/usr/bin/env bash
# Demo reset, part 2: moves every file out of the input folder into data/archive/<timestamp>/.
# Run it together with ops/reset-demo-data.sql - otherwise the next job run registers the old
# files again (they are no longer in the database) and they reappear in the demo.
# Usage: ops/reset-demo-files.sh [input folder]     (default: data/input, as input.dir)
set -euo pipefail
cd "$(dirname "$0")/.."
IN="${1:-data/input}"
[ -d "$IN" ] || { echo "Input folder $IN does not exist - nothing to do."; exit 0; }
if [ -z "$(ls -A "$IN")" ]; then echo "Input folder $IN is already empty."; exit 0; fi
DEST="data/archive/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$DEST"
mv "$IN"/* "$DEST"/
echo "Moved the contents of $IN to $DEST"
