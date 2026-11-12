#!/bin/sh
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$HERE/../.." && pwd)
BUILD="$HERE/build"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes"
cd "$ROOT"

JAVAC=${JAVAC:-javac}
JAVA=${JAVA:-java}
"$JAVAC" -encoding UTF-8 -d "tools/fileops-check/build/classes" \
    "app/src/main/java/com/rxinfiles/tool/FileSafety.java" \
    "tools/fileops-check/FileSafetyCheck.java"
"$JAVA" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
    -cp "tools/fileops-check/build/classes" com.rxinfiles.tool.FileSafetyCheck
