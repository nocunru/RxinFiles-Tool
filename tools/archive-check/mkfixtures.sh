#!/bin/sh
# 生成归档校验夹具（RAR / 7z / bzip2 / xz / lzma / tar.*）。
#
# 用法（在开发机的 Git Bash 里）：
#   MSYS_NO_PATHCONV=1 wsl -d Ubuntu -- bash /mnt/c/<...>/tools/archive-check/mkfixtures.sh
#
# 为什么要外部工具：RAR 与 7z 都**只能解不能造**（没有纯 Java 的写入实现，
# junrar 只读、commons-compress 的 SevenZOutputFile 造不出标准 .7z 的各种滤镜组合）。
# 所以夹具由官方命令行工具生成：
#   7-Zip  https://www.7-zip.org/a/7z2501-linux-x64.tar.xz     （7zz 25.01，x86-64 Linux）
#   RAR    https://www.rarlab.com/rar/rarlinux-x64-624.tar.gz  （rar 6.24）
#
# **RAR 为什么用 6.24 而不是最新的 7.x**：RAR 7.0 起**删掉了 `-ma4`**，
# 只能用 `-ma5` 造 RAR5 包（实测 7.10 直接报 `ERROR: Unknown option: ma4`）。
# 而 RAR4 恰恰是旧手机/旧 Windows 上最常见的形态，必须覆盖，所以退到 6.24 ——
# 它 `-ma4` / `-ma5` 都支持（`rar -?` 里写作 `ma[4|5]`）。
# 两者都缓存在 ~/.cache/archeck-tools，重复跑不会重新下载。
# 夹具本身**提交进仓库**，所以别人不需要跑这个脚本也能直接跑 run-checks。

DEST=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1
CACHE="${ARCHECK_TOOLS:-$HOME/.cache/archeck-tools}"
mkdir -p "$CACHE" || exit 1
PW=rxpass

fetch() { # fetch <url> <outfile>
    out=$2
    if [ -s "$out" ]; then echo "  [cached] $(basename "$out")"; return 0; fi
    echo "  [get]    $1"
    curl -sSL -m 300 -o "$out.part" "$1" || { echo "  !! 下载失败"; return 1; }
    mv "$out.part" "$out"
}

echo "== 1. 准备外部工具（缓存 $CACHE）"

SEVEN="$CACHE/7zz"
if [ ! -x "$SEVEN" ]; then
    fetch https://www.7-zip.org/a/7z2501-linux-x64.tar.xz "$CACHE/7z.tar.xz" || exit 1
    rm -rf "$CACHE/7zdir"; mkdir -p "$CACHE/7zdir"
    tar -xJf "$CACHE/7z.tar.xz" -C "$CACHE/7zdir" || exit 1
    b=$(find "$CACHE/7zdir" -name 7zz -type f | head -1)
    [ -n "$b" ] || { echo "  !! 包里没有 7zz"; exit 1; }
    cp "$b" "$SEVEN"; chmod +x "$SEVEN"
fi
printf '  7zz     : %s (%s)\n' "$SEVEN" "$("$SEVEN" i 2>&1 | tr -d '\r' | sed -n '2p')"

RAR="$CACHE/rar624"
if [ ! -x "$RAR" ]; then
    fetch https://www.rarlab.com/rar/rarlinux-x64-624.tar.gz "$CACHE/rar624.tar.gz" || exit 1
    rm -rf "$CACHE/rar624dir"; mkdir -p "$CACHE/rar624dir"
    tar -xzf "$CACHE/rar624.tar.gz" -C "$CACHE/rar624dir" || exit 1
    b=$(find "$CACHE/rar624dir" -name rar -type f | head -1)
    [ -n "$b" ] || { echo "  !! 包里没有 rar"; exit 1; }
    cp "$b" "$RAR"; chmod +x "$RAR"
fi
printf '  rar     : %s (%s)\n' "$RAR" "$("$RAR" 2>&1 | tr -d '\r' | sed -n '2p')"

WORK="$CACHE/work"
rm -rf "$WORK"; mkdir -p "$WORK" || exit 1
cp -r "$DEST/zsrc" "$WORK/" || exit 1
cp -r "$DEST/src"  "$WORK/" || exit 1
cd "$WORK" || exit 1

echo
echo "== 2. 7z（覆盖各压缩方法 / solid / 加密）"
# **必须先把上一次的产物删掉**：`7zz a` / `rar a` 是「追加或更新」语义，
# 目标文件已经存在时**不会重建**。源树换了名字（zsrc → zenc）再跑一次，
# 旧条目会原样留在包里，夹具就此变成「两棵树混装」而没人发现。
rm -f "$DEST"/sevenz-*.7z "$DEST"/evil.7z "$DEST"/rar*.rar "$DEST"/evil.rar
"$SEVEN" a -t7z -m0=lzma2  -mx=5 "$DEST/sevenz-lzma2.7z"    zsrc >/dev/null || exit 1
"$SEVEN" a -t7z -m0=lzma   -mx=5 "$DEST/sevenz-lzma.7z"     zsrc >/dev/null || exit 1
"$SEVEN" a -t7z -m0=bzip2  -mx=5 "$DEST/sevenz-bzip2.7z"    zsrc >/dev/null || exit 1
"$SEVEN" a -t7z -m0=deflate      "$DEST/sevenz-deflate.7z"  zsrc >/dev/null || exit 1
"$SEVEN" a -t7z -m0=copy         "$DEST/sevenz-copy.7z"     zsrc >/dev/null || exit 1
"$SEVEN" a -t7z -ms=on  -m0=lzma2 "$DEST/sevenz-solid.7z"   src  >/dev/null || exit 1
"$SEVEN" a -t7z -ms=off -m0=lzma2 "$DEST/sevenz-nonsolid.7z" src >/dev/null || exit 1
"$SEVEN" a -t7z -p"$PW"           "$DEST/sevenz-enc.7z"     zsrc >/dev/null || exit 1
"$SEVEN" a -t7z -p"$PW" -mhe=on   "$DEST/sevenz-enchdr.7z"  zsrc >/dev/null || exit 1

echo "== 3. RAR（RAR4 / RAR5 / solid / 加密）"
"$RAR" a -ma5 -m3 -idq "$DEST/rar5.rar" zsrc || exit 1
"$RAR" a -ma4 -m3 -idq "$DEST/rar4.rar" zsrc || exit 1
"$RAR" a -ma5 -m5 -s -idq "$DEST/rar5-solid.rar" src || exit 1
"$RAR" a -ma4 -m5 -s -idq "$DEST/rar4-solid.rar" src || exit 1
"$RAR" a -ma5 -m3 -p"$PW" -idq "$DEST/rar5-enc.rar" zsrc || exit 1
"$RAR" a -ma4 -m3 -p"$PW" -idq "$DEST/rar4-enc.rar" zsrc || exit 1
"$RAR" a -ma5 -m3 -hp"$PW" -idq "$DEST/rar5-enchdr.rar" zsrc || exit 1

echo "== 3b. ZIP 加密（ZipCrypto / WinZip AES / 明文加密混装）"
# 加密 ZIP 必须同时覆盖 **Deflate** 与 **Store** 两种形态：
#   - `zipcrypto-store.zip` 的长度关系最干净（compressed = 12 + 明文长度），
#     一旦忘了跳过那 12 字节随机头，长度会立刻对不上 —— 是最灵敏的一组断言；
#   - `zipcrypto.zip` / `zipaes*.zip` 走 deflate，覆盖「解密后再解压」的真实链路。
#
# **为什么源树是 `zenc` 而不是 `zsrc`**：7-Zip 写 zip 时会自己挑压缩方法，
# 对「小文件」和「压不动的内容」一律落 Store —— 实测连 `-mm=Deflate -mx=9`
# 都被忽略（`zsrc` 里的文件全部被写成 Store）。`zenc` 里有一个 240KB 的
# 高可压文本，deflate 才会真的被用上。
cp -r "$DEST/zenc" "$WORK/" || exit 1
cd "$WORK" || exit 1

# 同上的老问题：目标包存在时 `7zz a` 只会更新，不会重建
rm -f "$DEST"/zipcrypto*.zip "$DEST"/zipaes*.zip "$DEST"/zipmixed.zip

"$SEVEN" a -tzip -mm=Deflate -mx=9 -p"$PW" "$DEST/zipcrypto.zip"       zenc >/dev/null || exit 1
"$SEVEN" a -tzip -m0=copy    -p"$PW"       "$DEST/zipcrypto-store.zip" zenc >/dev/null || exit 1
"$SEVEN" a -tzip -mem=AES128 -mm=Deflate -mx=9 -p"$PW" "$DEST/zipaes128.zip" zenc >/dev/null || exit 1
"$SEVEN" a -tzip -mem=AES192 -mm=Deflate -mx=9 -p"$PW" "$DEST/zipaes192.zip" zenc >/dev/null || exit 1
"$SEVEN" a -tzip -mem=AES256 -mm=Deflate -mx=9 -p"$PW" "$DEST/zipaes256.zip" zenc >/dev/null || exit 1
"$SEVEN" a -tzip -mem=AES256 -m0=copy -p"$PW"       "$DEST/zipaes256-store.zip" zenc >/dev/null || exit 1

# 明文 + 加密混装：ZIP 的加密是**逐条目**的，同一个包里两种条目可以并存。
# 这条夹具专门用来验「列举照样列得出来、但一点开就必须要密码」。
rm -f "$DEST/zipmixed.zip"
rm -rf "$DEST/zmixed"; mkdir -p "$DEST/zmixed"
printf 'plain payload\n'  > "$DEST/zmixed/plain-here.txt"
printf 'secret payload\n' > "$DEST/zmixed/secret-here.txt"
cp "$DEST/zmixed/plain-here.txt"  .
cp "$DEST/zmixed/secret-here.txt" .
"$SEVEN" a -tzip         "$DEST/zipmixed.zip" plain-here.txt  >/dev/null || exit 1
"$SEVEN" a -tzip -p"$PW" "$DEST/zipmixed.zip" secret-here.txt >/dev/null || exit 1

echo "== 3c. 中文密码夹具（命令行工具没法指定密码编码，只能自己造）"
# 命令行工具把密码按 UTF-8 送进算法，而 Windows 上的工具按本机代码页（GBK）——
# 要同时覆盖两条路，就得能指定「密码用哪种字节编码」，这只有自己写加密器才行。
# genzipenc.py 里是一份独立的 ZipCrypto 实现（顺便当第二套参考实现）。
if command -v python3 >/dev/null 2>&1; then
    ( cd "$DEST" && python3 genzipenc.py ) | sed 's/^/  /'
else
    echo "  !! 没有 python3，跳过 —— zippw-*.zip 需要手动跑 genzipenc.py"
fi

echo "== 4. 纯压缩流与 tar.*"
xz  --format=lzma -c zsrc/top.txt > "$DEST/single.txt.lzma" || exit 1
bzip2 -c zsrc/top.txt > "$DEST/single.txt.bz2" || exit 1
xz    -c zsrc/top.txt > "$DEST/single.txt.xz"  || exit 1
tar -cjf "$DEST/test.tar.bz2" src || exit 1
tar -cJf "$DEST/test.tar.xz"  src || exit 1

# GNU LongLink（typeflag 'L'）：tar 条目名超过 100 字节时，GNU tar 会先写一个
# `././@LongLink` 头，载荷是「完整名字 + NUL」。仓库里原有的 test.tar / test.tar.gz
# 都是 **pax**（'x'）格式，所以 'L' 这条分支一直没被覆盖到 —— 而 GNU tar 恰恰是 Linux 默认。
# 夹具必须让最深的名字超过 100 字节（src 的前缀长度正好卡在这个边界上）。
tar --format=gnu -cf "$DEST/test-longname.tar" src || exit 1
# 同一棵树再来一份 pax 版，用来证明「两种长名扩展头都要认」
tar --format=pax -cf "$DEST/test-pax-long.tar" src || exit 1

echo "== 5. 路径逃逸（能造出来就造，造不出记录原因）"
# 逃逸条目靠各工具自带的「指定包内路径」开关造：
#   rar  -ap../        把后续文件全放进 ../ 这个包内目录
#   7zz  -si../<名字>  从 stdin 读内容，条目标题由开关给出
printf 'escaped payload\n' > escaped3.txt
printf 'good payload\n' > good3.txt
"$RAR" a -ma5 -idq "$DEST/evil.rar" good3.txt
"$RAR" a -ma5 -ap../ -idq "$DEST/evil.rar" escaped3.txt 2>&1 | sed 's/^/     rar: /'
"$SEVEN" a -t7z "$DEST/evil.7z" good3.txt >/dev/null
"$SEVEN" a -t7z -si../escaped4.txt "$DEST/evil.7z" < escaped3.txt >/dev/null 2>&1

echo "== 6. 符号链接条目（tar 侧也不落地，rar/7z 各留一个）"
mkdir -p linkdir
printf 'link target content\n' > linkdir/target.txt
ln -s target.txt     linkdir/rel.txt
ln -s /etc/hostname  linkdir/abs.txt
# -ol / -snl 才是「存成链接」；不给这两个开关，工具会把链接**跟随**成普通文件
"$RAR" a -ma5 -ol -idq "$DEST/rar5-link.rar" linkdir || exit 1
"$SEVEN" a -t7z -snl "$DEST/sevenz-link.7z" linkdir >/dev/null || exit 1

echo
echo "== 7. 产物清单"
ls -l "$DEST"/*.7z "$DEST"/*.rar "$DEST"/*.xz "$DEST"/*.bz2 "$DEST"/*.lzma "$DEST"/test-longname.tar 2>/dev/null |
    awk '{printf "  %10d  %s\n", $5, $9}'

for f in evil.rar evil.7z rar5-link.rar sevenz-link.7z; do
    [ -s "$DEST/$f" ] || continue
    printf '  %s 里的条目：\n' "$f"
    "$SEVEN" l -slt "$DEST/$f" 2>&1 |
        grep -E '^Path|^Attributes|^Symbolic Link' | sed 's/^/      /'
done
