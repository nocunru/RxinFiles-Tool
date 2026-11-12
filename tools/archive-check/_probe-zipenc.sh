#!/bin/sh
# 一次性探针：用 7-Zip 交叉验证 genzipenc.py 造的中文密码夹具是不是**标准** ZIP。
#
# 用法：MSYS_NO_PATHCONV=1 wsl -d Ubuntu -- bash /mnt/c/<...>/tools/archive-check/_probe-zipenc.sh
#
# 只有 /utf8 那份能这样验：7-Zip 在 Linux 上把命令行给的密码按 UTF-8 送进算法，
# 正好和那份夹具的密码字节一致。/gbk 那份的密码字节是 GBK，命令行工具没有
# 「指定密码编码」的开关 —— 它的有效性由 Python 的 zipfile 与我们的 Java
# 两侧交叉确认（见 genzipenc.py 末尾的自检）。
#
# 这一步的价值：Python 那份加密器是本仓库自己写的，万一它对标准的理解和
# 7-Zip 不一致，我们的 Java「读得出来」就只是「和自家写错的地方对上了」。
# 7-Zip 说 Everything is Ok，才能说明这个包是全世界都认的 ZIP。
set -e
D=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
S="$HOME/.cache/archeck-tools/7zz"

echo "--- 7zz 列举（不带密码，文件名应当看得到）"
"$S" l "$D/zippw-utf8.zip" | sed -n '9,20p' | sed 's/^/  /'

echo "--- 7zz t 用正确密码（应为 Everything is Ok）"
"$S" t -p密码测试 "$D/zippw-utf8.zip" 2>&1 | tail -6 | sed 's/^/  /'

echo "--- 7zz t 用错误密码（应当报错）"
"$S" t -pwrong "$D/zippw-utf8.zip" 2>&1 | tail -4 | sed 's/^/  /' || true

echo "--- 7zz t 用 GBK 字节串当密码（应当报错 —— 证明它确实按 UTF-8 处理）"
"$S" t -p$'\xc3\xdc\xc2\xeb\xb2\xe2\xca\xd4' "$D/zippw-utf8.zip" 2>&1 | tail -4 | sed 's/^/  /' || true
