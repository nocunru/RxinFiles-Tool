#!/bin/sh
# 一次性探测：rar 的 -ma4 / -idq / -ap 开关是否存在
RAR="${ARCHECK_TOOLS:-$HOME/.cache/archeck-tools}/rar624"
[ -x "$RAR" ] || { echo "no rar624"; exit 1; }
"$RAR" -? 2>&1 | tr -d '\r' | grep -nE '\-ma[0-9]|\-idq|\-ap<|\-hp|\-p\[|solid|\-s\b' | head -30
echo "--- 版本:"
"$RAR" 2>&1 | tr -d '\r' | sed -n '2p'
