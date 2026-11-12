#!/bin/sh
# 文本查看器「查找」逻辑的独立校验。
#
#   用法（开发机 Git Bash 里）：
#     JAVA_HOME="C:/Program Files/Java/jdk-20" sh tools/find-check/run.sh
#
# FindCheck.java 里的方法是**从 TextViewerActivity.kt 逐行抄过来的**
# （scanMatches / tailMatches / firstMatchAtOrAfter / liveWindow / cursorIndices /
# lineNumbersFor / snippetAt），所以它能在没有模拟器的机器上验证查找逻辑 ——
# 包括「命中太多时窗口怎么收敛」这种真机上很难复现的边界。
#
# **改了 Kotlin 那边记得同步这里**，否则这份断言会慢慢变成「校验一个不存在的实现」。
#
# 这份脚本是 3.15.10 补的：FindCheck.java 早就写好了，但**没进任何 run 脚本**，
# 等于从来没被执行过（同时补进来的还有 archive-check 的 TarCheck / SelectCheck）。

set -u

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1
ROOT=$(CDPATH= cd -- "$HERE/../.." && pwd) || exit 1

# Git Bash 给的 /c/... 路径 javac/java 不认，要转成 C:/...（-m = 混合风格：
# 带盘符又是正斜杠，路径里不会出现转义问题）。
# **必须转**：脚本中间会 cd，这时候相对路径会相对到别的地方去 ——
# 第一版就是这么挂的（`ClassNotFoundException: FindCheck`）。
topath() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$1"
    else
        printf '%s' "$1"
    fi
}

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

cd "$ROOT" || exit 1
BUILD="$HERE/build"
rm -rf "$BUILD"
mkdir -p "$BUILD" || exit 1

"$JAVAC" -encoding UTF-8 -nowarn -d "$(topath "$BUILD")" "tools/find-check/FindCheck.java" || {
    echo "编译失败" >&2
    exit 1
}

cd "$HERE" || exit 1
"$JAVA" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$(topath "$BUILD")" FindCheck
rc=$?
if [ $rc -ne 0 ]; then
    echo ">>>>>>>> FindCheck 失败，退出码 $rc" >&2
    exit 1
fi
exit 0
