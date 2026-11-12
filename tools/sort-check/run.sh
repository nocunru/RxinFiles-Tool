#!/usr/bin/env bash
#
# FileSort 行为断言 —— 纯 JVM，不需要 Android、不需要真机。
#
# 排序是「看着对、其实错」的重灾区：普通字符串比较在 file1/file2/file10 这种名字上
# 一眼看不出毛病，而用户的目录里到处都是这种名字。所以判据单独抽成纯 Java
# （见 app/src/main/java/com/rxinfiles/tool/FileSort.java），在这里直接断言。
#
# 用法：bash tools/sort-check/run.sh
set -e

cd "$(dirname "$0")/../.."

JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# 源文件里有中文，必须显式指定编码；输出同理（JDK 19+ 用 stdout.encoding）
"$JAVAC" -encoding UTF-8 -d "$OUT" \
    app/src/main/java/com/rxinfiles/tool/FileSort.java \
    tools/sort-check/FileSortCheck.java

"$JAVA" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "$OUT" FileSortCheck
