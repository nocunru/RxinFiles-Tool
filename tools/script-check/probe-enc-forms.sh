#!/bin/bash
# 一次性证据：把「加密/编译过的脚本」的几种真实形态造出来，
# 看 mksh / dash / busybox ash 各自说什么。
# 结论用于 diagnose-sh.sh 的「加密识别」小节 —— 别凭印象写判据。
#
# 用法（WSL）：bash probe-enc-forms.sh

MK=/home/rouxin/shells/usr/bin/mksh
BB=/home/rouxin/bxbuild/host/busybox
W=/home/rouxin/enctest
rm -rf "$W"; mkdir -p "$W" || exit 1
cd "$W" || exit 1

echo "=== A. ELF 二进制改名成 .sh（shc / upx 编译产物的形态） ==="
cp /bin/dash a.sh; chmod +x a.sh
echo "-- mksh -n a.sh :"; "$MK" -n a.sh 2>&1 | head -3
echo "-- dash -n a.sh :"; dash -n a.sh 2>&1 | head -3
echo "-- ash  -n a.sh :"; "$BB" sh -n a.sh 2>&1 | head -3
echo "-- 前 4 字节    : $(head -c 4 a.sh | od -An -tx1 | tr -d ' \n')"

echo
echo "=== B. 明文 stub + 裸 base64 密文块 ==="
{ printf '#!/bin/sh\necho start\n'; head -c 900 /dev/urandom | base64 -w 76; } > b.sh
echo "-- mksh -n b.sh :"; "$MK" -n b.sh 2>&1 | head -3

echo
echo "=== C. 明文 stub + 随机二进制尾部 ==="
{ printf '#!/bin/sh\necho start\n'; head -c 400 /dev/urandom; } > c.sh
echo "-- mksh -n c.sh :"; "$MK" -n c.sh 2>&1 | head -3

echo
echo "=== D. 密文包在引号里的自解密 stub（『正规』写法） ==="
{ printf '#!/bin/sh\necho start\n'
  printf "eval \"\$(echo '"
  head -c 300 /dev/urandom | base64 -w0
  printf "' | base64 -d)\"\n"; } > d.sh
"$MK" -n d.sh 2>&1 | head -3
echo "rc=$?"

echo
echo "=== E. shebang 指向自带的解密器（非标准 shell） ==="
printf '#!/data/data/com.x/files/bin/rxe\necho payload\n' > e.sh
chmod +x e.sh
echo "-- 首行        : $(sed -n 1p e.sh)"
echo "-- mksh -n e.sh :"; "$MK" -n e.sh 2>&1 | head -3

echo
echo "=== F. 可打印率 / base64 行 这两个判据能不能把 A~E 分开 ==="
for f in a.sh b.sh c.sh d.sh e.sh; do
    tot=$(wc -c < "$f")
    pr=$(LC_ALL=C tr -d -c '\11\12\15\40-\176' < "$f" | wc -c)
    # 长度 >200 且整行只有 base64 字符集的行数
    b64=$(awk 'length($0) > 200 && $0 ~ /^[A-Za-z0-9+\/=]+$/ { n++ } END { print n + 0 }' "$f")
    printf '  %-6s 总 %6s B  可打印 %6s (%s%%)  超长base64行 %s\n' \
        "$f" "$tot" "$pr" "$((pr * 100 / tot))" "$b64"
done

echo
echo "=== G. 工具可用性 / 实现差异（诊断脚本要靠这些命令在真机上活着） ==="
for t in od tr wc head sed awk grep cmp base64 xxd file; do
    printf '%s=%s  ' "$t" "$(command -v "$t" || echo NO)"
done
echo

TOY=$HOME/shells/usr/bin/toybox
echo
echo "-- NUL 计数：\`od\` 不带 -v 会把连续重复行压成 *，计数直接少算 --"
# 注意 od 的调用形态：多合一二进制要写 `busybox od`，独立 od 不能多写那一个词
# （写成 `/usr/bin/od od ...` 时 GNU od 会把 `od` 当文件名，且在没有可读输入时
#   转去读 **stdin** —— 探针会一直挂着，踩过）
if [ -x "$BB" ]; then
    printf '  %-18s -v=%s  不带-v=%s\n' busybox \
        "$("$BB" od -An -tx1 -v -N 8192 a.sh 2>/dev/null | tr ' ' '\n' | grep -c '^00$')" \
        "$("$BB" od -An -tx1    -N 8192 a.sh 2>/dev/null | tr ' ' '\n' | grep -c '^00$')"
fi
if [ -x "$TOY" ]; then
    printf '  %-18s -v=%s  不带-v=%s\n' toybox \
        "$("$TOY" od -An -tx1 -v -N 8192 a.sh 2>/dev/null | tr ' ' '\n' | grep -c '^00$')" \
        "$("$TOY" od -An -tx1    -N 8192 a.sh 2>/dev/null | tr ' ' '\n' | grep -c '^00$')"
fi
printf '  %-18s -v=%s  不带-v=%s\n' 'GNU od' \
    "$(od -An -tx1 -v -N 8192 a.sh 2>/dev/null | tr ' ' '\n' | grep -c '^00$')" \
    "$(od -An -tx1    -N 8192 a.sh 2>/dev/null | tr ' ' '\n' | grep -c '^00$')"

echo
echo "-- 两个工具箱各自缺什么（垫片只能垫它真有的 applet）--"
if [ -x "$TOY" ]; then
    for a in od tr awk sed grep head; do
        if "$TOY" 2>/dev/null | tr ' ' '\n' | grep -qx "$a"; then
            printf '  toybox  有 %s\n' "$a"
        else
            printf '  toybox  **没有** %s（Ubuntu 的包是精简配置；Android 上的配置更全）\n' "$a"
        fi
    done
fi
if [ -x "$BB" ]; then
    for a in od tr awk sed grep head; do
        if "$BB" --list 2>/dev/null | grep -qx "$a"; then
            printf '  busybox 有 %s\n' "$a"
        else
            printf '  busybox **没有** %s\n' "$a"
        fi
    done
fi
