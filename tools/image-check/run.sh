#!/bin/sh
# ImageSampling（图片解码尺寸策略）的断言。
#
#   用法（开发机 Git Bash 或 WSL 里都行）：
#     JAVA_HOME="C:/Program Files/Java/jdk-20" sh tools/image-check/run.sh
#
# 为什么单独一支：这条策略唯一的工作是「决定按多大采样率解码」，
# 算错了**不会当场报错** —— 只有当某个尺寸的图被交给 Canvas 时才会抛
# `Canvas: trying to draw too large(Nbytes) bitmap`，而绘制路径上没有 try/catch
# 能兜住，用户看到的就是闪退。这条策略以前埋在 Activity 里，一行都断言不了，
# 所以「要求两条边都超过阈值才降采样」那个写法才活到了真机上。
#
# 纯 Java、零 Android 依赖：只编 ImageSampling.java + ImageSamplingCheck.java。

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
    -d "tools/image-check/build/classes" \
    "app/src/main/java/com/rxinfiles/tool/ImageSampling.java" \
    "tools/image-check/ImageSamplingCheck.java"
if [ $? -ne 0 ]; then
    echo "编译失败" >&2
    exit 1
fi
echo "编译通过"
echo

"$JAVA" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
    -cp "tools/image-check/build/classes" \
    "com.rxinfiles.tool.ImageSamplingCheck"
