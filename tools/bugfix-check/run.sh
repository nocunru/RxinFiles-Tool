#!/bin/sh
# BUG日志-20261110.md 里 7 个 Bug 的源码不变量断言。
#
#   用法（开发机 Git Bash 或 WSL 里都行）：
#     sh tools/bugfix-check/run.sh
#
# 为什么单独一支：这 7 个 Bug 都只在真机 / ROOT 环境出现（ROOT-only 权限失败、
# 持久 su 内的 exit、Activity 生命周期、跨进程 SharedPreferences 竞争、悬空
# 符号链接冲突）。没有真机就没法复现，只能把「修复后的契约」钉在源码上：
# 旧实现必须红，修复后必须绿。判据是只读的，不编译、不依赖 Android SDK。
#
# 纯 Python 3、零第三方依赖：只跑 verify-bugfix.py。

set -u

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1
ROOT=$(CDPATH= cd -- "$HERE/../.." && pwd) || exit 1

# Windows 上 python3 通常不在 PATH：依次找 python3 / python / 常见安装路径，
# 也可以用 PYTHON 环境变量直接指定解释器。
if [ -n "${PYTHON:-}" ] && [ -x "$PYTHON" ]; then
    :
elif command -v python3 >/dev/null 2>&1; then
    PYTHON=python3
elif command -v python >/dev/null 2>&1; then
    PYTHON=python
elif [ -x "C:/Python314/python.exe" ]; then
    PYTHON="C:/Python314/python.exe"
elif [ -x "C:/Python312/python.exe" ]; then
    PYTHON="C:/Python312/python.exe"
else
    echo "找不到 python。设一下 PYTHON，或把 python3 放到 PATH 里。" >&2
    exit 2
fi
echo "python: $PYTHON"
echo

cd "$ROOT" || exit 1
PYTHONIOENCODING=utf-8 "$PYTHON" "tools/bugfix-check/verify-bugfix.py"
