#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
mkdir -p "$ROOT/build/classes"
find "$ROOT/build/classes" -type f -name '*.class' -delete
find "$ROOT/src/main/java" "$ROOT/src/test/java" -name '*.java' -print > "$ROOT/build/sources.txt"
javac --release 17 --add-modules jdk.httpserver -Xlint:all -Werror -d "$ROOT/build/classes" @"$ROOT/build/sources.txt"
