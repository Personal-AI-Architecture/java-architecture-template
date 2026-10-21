#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
"$ROOT/scripts/compile.sh"
java -cp "$ROOT/build/classes" com.paa.PaaServer "${1:-127.0.0.1}" "${2:-8000}"
