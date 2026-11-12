#!/bin/sh
# 一次性探测：rarlab 的 Linux 命令行包（含 rar 压缩器）到底叫什么名字
cd "$HOME/archeck" || exit 1
for f in rarlinux-x64-7.1.6.tar.gz rarlinux-x64-7.0.1.tar.gz \
         rarlinux-x64-7.0.0.tar.gz rarlinux-x64-6.2.4.tar.gz \
         rarlinux-x64-700.tar.gz rarlinux-x64-624.tar.gz \
         rarlinux-x64-710.tar.gz rarlinux-x64-701.tar.gz ; do
  code=$(curl -sSL -m 20 -o /dev/null -w '%{http_code}' "https://www.rarlab.com/rar/$f" 2>/dev/null)
  printf '%s  %s\n' "$code" "$f"
done
