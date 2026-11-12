#!/bin/sh
# 脚本层「纯 Java」部分的断言：把不依赖 Android API 的类单独编出来直接跑。
#
#   用法（开发机 Git Bash 里）：
#     JAVA_HOME="C:/Program Files/Java/jdk-20" sh tools/script-check/run-java-check.sh
#
# 目前只有 ScriptDone（脚本结束标记协议）。它与归档层的 run-checks.sh 是同一个
# 路数：改动 ScriptRunner / TerminalActivity / ScriptDone 之后都要能一条命令验完，
# 所以脚本侧不必跑 Gradle。
#
# shell 那一半（printf 到底打出什么字节）在 verify-scriptpipe.py 的第 9 节，
# 那一条要在 WSL 里跑（需要 mksh / dash / busybox）。

set -u

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1
ROOT=$(CDPATH= cd -- "$HERE/../.." && pwd) || exit 1

# ---------------------------------------------------------------- 找 JDK
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    JAVAC="$JAVA_HOME/bin/javac"
    JAVA="$JAVA_HOME/bin/java"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac.exe" ]; then
    JAVAC="$JAVA_HOME/bin/javac.exe"
    JAVA="$JAVA_HOME/bin/java.exe"
elif [ -x "C:/Program Files/Java/jdk-20/bin/javac.exe" ]; then
    JAVAC="C:/Program Files/Java/jdk-20/bin/javac.exe"
    JAVA="C:/Program Files/Java/jdk-20/bin/java.exe"
elif command -v javac >/dev/null 2>&1; then
    JAVAC=javac
    JAVA=java
else
    echo "找不到 javac。设一下 JAVA_HOME，或把 jdk-20 放到 C:/Program Files/Java/jdk-20。" >&2
    exit 2
fi
echo "javac : $JAVAC"

BUILD="$HERE/build"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" || exit 1

# 在工程根目录下用相对路径编译，避开 MSYS 路径转换的坑
cd "$ROOT" || exit 1
"$JAVAC" -encoding UTF-8 -nowarn \
    -d "tools/script-check/build/classes" \
    "app/src/main/java/com/rxinfiles/tool/ScriptDone.java" \
    "tools/script-check/DoneMarkCheck.java"
if [ $? -ne 0 ]; then
    echo "编译失败" >&2
    exit 1
fi
echo "编译通过"
echo

"$JAVA" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
    -cp "tools/script-check/build/classes" \
    "com.rxinfiles.tool.DoneMarkCheck"
