#!/bin/sh
# 归档层回归校验：把 app 里那几个「不依赖 Android API」的 Java 类单独编出来，
# 对真实归档夹具跑一遍列举 / 解压 / 路径逃逸 / 加密 / solid 的断言。
#
#   用法（开发机 Git Bash 里）：
#     tools/archive-check/run-checks.sh
#
# 为什么可以这样测：Archives / TarArchive / RarArchive / SevenZArchive 四个文件
# 一行 Android API 都没有，只用 java.io + app/libs 里的三方 jar。所以不需要
# 模拟器、不需要 Gradle，改完立刻能验。
#
# 夹具已经在仓库里（`mkfixtures.sh` 生成过），正常情况下不需要重跑那个脚本。

set -u

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1
ROOT=$(CDPATH= cd -- "$HERE/../.." && pwd) || exit 1
APP="$ROOT/app/src/main/java/com/rxinfiles/tool"
LIBS="$ROOT/app/libs"

# classpath 分隔符：Windows（Git Bash / MSYS / Cygwin）用 ;，其余用 :
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
    *) SEP=':' ;;
esac

# Git Bash 给的 /c/... 路径 javac/java 不认，要转成 C:/...。
# 一律用 -m（混合风格）：带盘符又是正斜杠，路径里不会出现转义问题。
topath() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$1"
    else
        printf '%s' "$1"
    fi
}

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
echo "libs  : $LIBS"
echo "夹具  : $HERE"

# ---------------------------------------------------------------- 编译
BUILD="$HERE/build"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" || exit 1

# 在工程根目录下用相对路径编译，避开 MSYS 路径转换的所有坑
cd "$ROOT" || exit 1
LIBS_CP="app/libs/*"
"$JAVAC" -encoding UTF-8 -nowarn -cp "$LIBS_CP" -d "tools/archive-check/build/classes" \
    "app/src/main/java/com/rxinfiles/tool/Archives.java" \
    "app/src/main/java/com/rxinfiles/tool/TarArchive.java" \
    "app/src/main/java/com/rxinfiles/tool/RarArchive.java" \
    "app/src/main/java/com/rxinfiles/tool/SevenZArchive.java" \
    "app/src/main/java/com/rxinfiles/tool/ZipArchive.java" \
    "app/src/main/java/com/rxinfiles/tool/TreeRails.java" \
    "tools/archive-check/Check.java" \
    "tools/archive-check/ZipCheck.java" \
    "tools/archive-check/ZipEncCheck.java" \
    "tools/archive-check/RarCheck.java" \
    "tools/archive-check/SevenZCheck.java" \
    "tools/archive-check/StreamCheck.java" \
    "tools/archive-check/OrderCheck.java" \
    "tools/archive-check/TarCheck.java" \
    "tools/archive-check/SelectCheck.java"
if [ $? -ne 0 ]; then
    echo "编译失败" >&2
    exit 1
fi
echo "编译通过"

# ---------------------------------------------------------------- 跑断言
CP="$(topath "$BUILD/classes")${SEP}$(topath "$LIBS")/*"
cd "$HERE" || exit 1

FAILED=0
# 程序清单**只写这一处**，TOTAL 由它数出来 —— 原来 TOTAL 是写死的 6，
# 加程序时忘了改计数就会「少报一个程序」而不自知（TarCheck / SelectCheck
# 写了却一直没进这个清单，见 3.15.10 的自检）。
PROGRAMS="ZipCheck ZipEncCheck RarCheck SevenZCheck StreamCheck OrderCheck TarCheck SelectCheck"
TOTAL=$(echo $PROGRAMS | wc -w | tr -d ' ')
for c in $PROGRAMS; do
    echo
    echo "################################################################"
    echo "#  $c"
    echo "################################################################"
    # stdout.encoding 是 JDK 19+ 才认的属性，老 JDK 会忽略，无害
    "$JAVA" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$CP" "com.rxinfiles.tool.$c"
    rc=$?
    if [ $rc -ne 0 ]; then
        FAILED=$((FAILED + 1))
        echo ">>>>>>>> $c 失败，退出码 $rc"
    fi
done

echo
echo "################################################################"
if [ $FAILED -eq 0 ]; then
    echo "#  全部通过：$TOTAL 个校验程序"
    exit 0
fi
echo "#  $FAILED / $TOTAL 个校验程序有失败"
exit 1
