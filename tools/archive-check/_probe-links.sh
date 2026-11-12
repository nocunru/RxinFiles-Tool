#!/bin/sh
# 一次性探测：RAR / 7z 里的符号链接条目到底是什么形态
#   关心两件事：① 命令能不能造出「链接条目」（unix 模式位 0xA000）；
#              ② 造出来以后，条目内容是「链接目标文本」还是「被指向文件的内容」
set -e
CACHE="${ARCHECK_TOOLS:-$HOME/.cache/archeck-tools}"
SEVEN="$CACHE/7zz"; RAR="$CACHE/rar624"
W=$(mktemp -d); cd "$W"
printf 'real target content\n' > top.txt
ln -s top.txt zlink.txt
ln -s /etc/hostname abslink.txt

echo "== 用 -ol 造 RAR 链接条目"
"$RAR" a -ma5 -ol -idq link-ol.rar zlink.txt abslink.txt
echo "--- link-ol.rar (7zz l -slt):"
"$SEVEN" l -slt link-ol.rar 2>&1 | grep -E '^Path|^Size|^Attributes|^Folder|^Symbolic|^Mode' | head -30

echo
echo "== 不用 -ol"
"$RAR" a -ma5 -idq link-follow.rar zlink.txt abslink.txt
"$SEVEN" l -slt link-follow.rar 2>&1 | grep -E '^Path|^Size|^Attributes|^Folder|^Symbolic|^Mode' | head -30

echo
echo "== 用 -snl 造 7z 链接条目"
"$SEVEN" a -t7z -snl link-snl.7z zlink.txt abslink.txt >/dev/null
"$SEVEN" l -slt link-snl.7z 2>&1 | grep -E '^Path|^Size|^Attributes|^Folder|^Symbolic|^Mode' | head -30

echo
echo "== 不用 -snl"
"$SEVEN" a -t7z link-nosnl.7z zlink.txt abslink.txt >/dev/null
"$SEVEN" l -slt link-nosnl.7z 2>&1 | grep -E '^Path|^Size|^Attributes|^Folder|^Symbolic|^Mode' | head -30

cp link-ol.rar link-follow.rar link-snl.7z link-nosnl.7z "$OLDPWD/" 2>/dev/null || true
echo
echo "留档目录: $W"
