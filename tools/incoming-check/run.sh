#!/bin/sh
# IncomingFile（外部「用其他应用打开」的判据）的断言。
#
#   用法（开发机 Git Bash 或 WSL 里都行）：
#     JAVA_HOME="C:/Program Files/Java/jdk-20" sh tools/incoming-check/run.sh
#
# 为什么单独一支：这条链路吃的是**对方应用可控的输入** —— 一个显示名加一个 URI。
# 消毒漏一个字节就是路径穿越，类型判错就是乱码或闪退，兜底判错就是「点了没反应」。
# 三件事在真机上都难复现，所以判据必须能脱离 Android、脱离开机，一条命令跑完。
#
# 零 Android 依赖，但**不是零依赖**：IncomingFile 的归档识别直接委托 Archives
# （解压能力的权威来源），所以这里用 -sourcepath 把纯 Java 的归档层一起编进来，
# 并把 app/libs 下的解压库（commons-compress/junrar/xz/slf4j）挂到 classpath。
# 这一片全是纯 Java，没有一行 Android API。

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
    -sourcepath "app/src/main/java" \
    -cp "app/libs/*" \
    -d "tools/incoming-check/build/classes" \
    "app/src/main/java/com/rxinfiles/tool/IncomingFile.java" \
    "tools/incoming-check/IncomingCheck.java"
if [ $? -ne 0 ]; then
    echo "编译失败" >&2
    exit 1
fi
echo "编译通过"
echo

# 运行时把 app/libs 也挂上：本用例只调 Archives 的扩展名判定（不真的解压），
# 但把库挂上最稳妥，免得哪天多测一条就 NoClassDefFoundError。
#
# classpath 分隔符随宿主变：Windows 上跑的 java.exe 认 `;`，POSIX 认 `:`，
# 用错了整串会被当成一个不存在的路径（ClassNotFoundException）。
case "$(uname -s 2>/dev/null)" in
    MINGW*|MSYS*|CYGWIN*) SEP=";" ;;
    *) SEP=":" ;;
esac
"$JAVA" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
    -cp "tools/incoming-check/build/classes${SEP}app/libs/*" \
    "com.rxinfiles.tool.IncomingCheck"
