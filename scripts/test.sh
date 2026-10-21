#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
"$ROOT/scripts/compile.sh"
java -cp "$ROOT/build/classes" com.paa.tests.ConformanceTests
java -cp "$ROOT/build/classes" com.paa.tests.IntegrationTests
java -cp "$ROOT/build/classes" com.paa.tests.ProviderTests
