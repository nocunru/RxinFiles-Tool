#!/bin/sh
# 文本查看器「读取 / 修改」链路的性能基准。
#
#   用法（开发机 Git Bash 里）：
#     JAVA_HOME="C:/Program Files/Java/jdk-20" sh tools/perf-check/run.sh
#
# 产出的是**各步骤之间的相对比例**与**候选写法之间的相对速度**，用来判断
# 「该往哪儿优化」。绝对数字是 JVM 的，比真机小 —— 看比例就行，
# 别拿去当手机上的指标。
#
# 两支基准：
#   SliceBench  3.14.0 起的切片链路（**直接调真的 TextSlicer**）+ 扫描写法对比
#   PerfRead    3.14.0 之前的读取链路（已被删掉，留着是为了对照「旧的那 10ms」）
#
# 结论见 README.md。哪天又有人说「读取太慢了」「该换 native 了」，
# 先跑一遍这个再动手 —— 这是它存在的全部意义。

set -u

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1
ROOT=$(CDPATH= cd -- "$HERE/../.." && pwd) || exit 1

# ---------------------------------------------------------------- 找 JDK
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    JAVAC="$JAVA_HOME/bin/javac"; JAVA="$JAVA_HOME/bin/java"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac.exe" ]; then
    JAVAC="$JAVA_HOME/bin/javac.exe"; JAVA="$JAVA_HOME/bin/java.exe"
elif [ -x "C:/Program Files/Java/jdk-20/bin/javac.exe" ]; then
    JAVAC="C:/Program Files/Java/jdk-20/bin/javac.exe"
    JAVA="C:/Program Files/Java/jdk-20/bin/java.exe"
elif command -v javac >/dev/null 2>&1; then
    JAVAC=javac; JAVA=java
else
    echo "找不到 javac。设一下 JAVA_HOME，或把 jdk-20 放到 C:/Program Files/Java/jdk-20。" >&2
    exit 2
fi
echo "java  : $JAVA"
echo

# 夹具（7.8MB / 50MB / 1MB 单行）放临时目录，不污染工程
OUT="${TMPDIR:-/tmp}/rxin-perf"
mkdir -p "$OUT" || exit 1
echo "夹具  : $OUT"
echo

# ---------------------------------------------------------------- 1. 切片链路
BUILD="$HERE/build"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" || exit 1

cd "$ROOT" || exit 1
"$JAVAC" -encoding UTF-8 -nowarn \
    -d "tools/perf-check/build/classes" \
    "app/src/main/java/com/rxinfiles/tool/TextSlicer.java" \
    "tools/perf-check/SliceBench.java"
if [ $? -ne 0 ]; then
    echo "编译失败" >&2
    exit 1
fi
echo "############ 1. 切片链路（3.14.0 起，真 TextSlicer）############"
"$JAVA" -Xmx1g -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
    -cp "tools/perf-check/build/classes" \
    "com.rxinfiles.tool.SliceBench" "$OUT/slice"
echo

# ---------------------------------------------------------------- 2. 旧链路
echo "############ 2. 旧读取链路（3.14.0 已删，仅作对照）############"
"$JAVA" -Xmx1g -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
    "$HERE/PerfRead.java" "${OUT}/perfread"
