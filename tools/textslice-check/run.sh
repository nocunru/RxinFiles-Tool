#!/bin/sh
# TextSlicer（大文本切片 / 拼接）的断言。
#
#   用法（开发机 Git Bash 或 WSL 里都行）：
#     JAVA_HOME="C:/Program Files/Java/jdk-20" sh tools/textslice-check/run.sh
#
# 为什么单独一支：切片的正确性全是「字节与字符的换算」，错一位的后果是
# 「保存之后文件少了半行」或者「变成乱码」—— 都是**不会当场报错**的那类问题。
# 所以它必须能脱离 Android、脱离开机，一条命令跑完。
#
# 纯 Java、零 Android 依赖：只编 TextSlicer.java + TextSliceCheck.java。

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
    -d "tools/textslice-check/build/classes" \
    "app/src/main/java/com/rxinfiles/tool/TextSlicer.java" \
    "tools/textslice-check/TextSliceCheck.java"
if [ $? -ne 0 ]; then
    echo "编译失败" >&2
    exit 1
fi
echo "编译通过"
echo

"$JAVA" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
    -cp "tools/textslice-check/build/classes" \
    "com.rxinfiles.tool.TextSliceCheck"
