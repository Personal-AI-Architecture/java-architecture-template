#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
"$ROOT/scripts/compile.sh"
java -cp "$ROOT/build/classes" com.paa.PaaDemo
