#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
"$ROOT/scripts/compile.sh"
if [ "${1:-}" = "--typecheck-only" ] || [ "${1:-}" = "--syntax-only" ]; then
  echo "Java compilation, syntax, and strict lint checks passed."
  exit 0
fi
java -cp "$ROOT/build/classes" com.paa.checks.PaaChecks "$@"
