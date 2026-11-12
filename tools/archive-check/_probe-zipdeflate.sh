#!/bin/sh
# 一次性探针：7-Zip 写 ZIP 时到底怎么挑压缩方法（Store 还是 Deflate）。
#
# 用法：MSYS_NO_PATHCONV=1 wsl -d Ubuntu -- bash /mnt/c/<...>/tools/archive-check/_probe-zipdeflate.sh
#
# **它解释了 `zenc/` 为什么必须存在。** 结论（实测）：
#
#   7-Zip 对「小文件」和「压不动的内容」**一律落 Store** —— 连显式给
#   `-mm=Deflate -mx=9` 也被忽略。所以加密夹具如果沿用 `zsrc`（最大 3KB、
#   其中还有个 3000 字节的随机 `deep.bin`），包里一个 Deflate 条目都不会有，
#   「解密后再解压」那条链路**根本没被执行过**，断言却全绿。
#
#   换成 `zenc/`（240KB 高可压文本）之后，`big.txt` / `table.csv` 才真的走 Deflate，
#   而 `top.txt` / `a.sh` 仍是 Store —— **同一个包里两种形态都有**，正好一起覆盖。
#
# 产物写在临时目录，不落到仓库里（`_probe-*` 一律不留垃圾）。

set -e
D=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
S="$HOME/.cache/archeck-tools/7zz"

cd /tmp || exit 1
rm -rf zipdeflate; mkdir zipdeflate; cd zipdeflate || exit 1
cp -r "$D/zsrc" "$D/zenc" .

show() { # show <包名>
    printf -- '--- %s\n' "$1"
    "$S" l -slt -prxpass "$1" |
        grep -E '^Path = |^Size = |^Packed Size|^Method = ' |
        sed -n '2,$p' | paste - - - - | sed 's/^/    /'
}

echo "########## 对照组：-mm=Deflate -mx=9 对 zsrc（最大 3KB，含随机 bin）"
"$S" a -tzip -mm=Deflate -mx=9 -prxpass s-deflate.zip zsrc >/dev/null
show s-deflate.zip

echo
echo "########## 实验组：同样的开关，源树换成 zenc（240KB 高可压文本）"
"$S" a -tzip -mm=Deflate -mx=9 -prxpass e-deflate.zip zenc >/dev/null
show e-deflate.zip

echo
echo "########## 结论：zsrc 全是 Store（-mm 被忽略），zenc 才出现 Deflate"
