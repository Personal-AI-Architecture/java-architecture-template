#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SOURCE=""
SHARED=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --source-checkout) SOURCE=$2; shift 2 ;;
    --shared-validator) SHARED=$2; shift 2 ;;
    *) shift ;;
  esac
done
if [ -n "$SOURCE" ]; then
  "$ROOT/scripts/compile.sh"
  java -cp "$ROOT/build/classes" com.paa.checks.PaaChecks --source-checkout "$SOURCE"
else
  "$ROOT/scripts/check.sh"
fi
"$ROOT/scripts/test.sh"
"$ROOT/scripts/acceptance.sh"
if [ -n "$SHARED" ]; then
  if [ -n "$SOURCE" ]; then
    node "$SHARED" --target "$ROOT/paa.target.json" --source-checkout "$SOURCE"
  else
    node "$SHARED" --target "$ROOT/paa.target.json"
  fi
fi
echo "Java PAA template validation passed."
