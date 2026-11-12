#!/bin/bash
# 验证 ScriptRunner.probeAsIdentity 里那条探测命令在三家真 shell 上的输出**可解析**：
#
#   [ -r F ] && printf '##r1\n' || printf '##r0\n'; \
#   [ -x F ] && printf '##x1\n' || printf '##x0\n'; \
#   head -c 8192 F 2>/dev/null | od -An -v -tx1 2>/dev/null
#
# 要证明三件事：
#  1. `##r?` / `##x?` 两行在任何一家都打得出来（`&&`/`||` 的短路组合不被误解析）；
#  2. `od` 的输出行**只由十六进制字符与空格组成** —— 否则 Kotlin 侧 parseProbe 会把
#     它整行丢掉，头部读不出来（宁可丢掉也不能把错误提示当数据，但正常情况不能丢）；
#  3. 前 4 字节解出来确实是 `7f 45 4c 46`（ELF 魔数），与 ScriptFormat 的判据对得上。
#
# 用法（WSL）：bash probe-sniff-cmd.sh

MK=/home/rouxin/shells/usr/bin/mksh
BB=/home/rouxin/bxbuild/host/busybox
W=/home/rouxin/dxtest-sniff
rm -rf "$W"; mkdir -p "$W" || exit 1
cd "$W" || exit 1

printf '#!/bin/sh\necho hi\n' > text.sh; chmod 755 text.sh
printf 'a\r\nb\r\n'            > crlf.sh; chmod 644 crlf.sh
cp "$MK" elf.bin;                        chmod 755 elf.bin
: > empty.bin;                           chmod 644 empty.bin

SYN='SYNTAX_ERROR'

emit() {
    q="'$1'"
    printf "%s" "[ -r $q ] && printf '##r1\\n' || printf '##r0\\n'; [ -x $q ] && printf '##x1\\n' || printf '##x0\\n'; head -c 8192 $q 2>/dev/null | od -An -v -tx1 2>/dev/null"
}

pass=0
fail=0
ck() {
    case $3 in
    *"$2"*)
        printf '  [ok] %s\n' "$1"
        pass=$((pass + 1))
        ;;
    *)
        printf '  [FAIL] %s\n        期望含: %s\n' "$1" "$2"
        fail=$((fail + 1))
        ;;
    esac
}

for sh in "$MK" "$BB" /bin/dash; do
    [ -x "$sh" ] || continue
    printf '\n=============== %s ===============\n' "$(basename "$sh")"
    for f in text.sh crlf.sh elf.bin empty.bin; do
        cmd=$(emit "$W/$f")
        if [ "$(basename "$sh")" = busybox ]; then
            out=$(printf '%s\n' "$cmd" | "$sh" sh 2>&1)
        else
            out=$(printf '%s\n' "$cmd" | "$sh" 2>&1)
        fi

        # ① 两行标记必须**存在且顺序固定**：第一行 `##r?`、第二行 `##x?`。
        #    值本身取决于夹具权限，所以只校形状，不写死 1。
        r_line=$(printf '%s\n' "$out" | sed -n 1p)
        x_line=$(printf '%s\n' "$out" | sed -n 2p)
        if printf '%s\n' "$r_line" | grep -qE '^##r[01]$' &&
            printf '%s\n' "$x_line" | grep -qE '^##x[01]$'; then
            ck "$f：r/x 标记行齐、顺序对（$r_line $x_line）" "##x" "$out"
        else
            printf '  [FAIL] %s：标记行不对（第1行「%s」第2行「%s」）\n' "$f" "$r_line" "$x_line"
            fail=$((fail + 1))
        fi

        # ② 除了标记行，其余非空行必须**整行**只由十六进制与空格组成
        bad=$(printf '%s\n' "$out" | sed '1,2d' | grep -v '^$' | grep -vc '^[0-9a-fA-F ]*$')
        case $bad in
        0) ck "$f：od 输出行全部可被 Kotlin 侧接受" "0" "0" ;;
        *) printf '  [FAIL] %s：有 %s 行不是纯十六进制（会被丢掉）\n' "$f" "$bad"
           printf '%s\n' "$out" | sed '1,2d' | grep -v '^$' | grep -v '^[0-9a-fA-F ]*$' | head -3 | sed 's/^/        /'
           fail=$((fail + 1)) ;;
        esac

        # ③ 头部字节解出来要和文件真实内容一致（前 4 字节足够区分 ELF / 文本）
        head4=$(printf '%s\n' "$out" | sed '1,2d' | tr -d ' \n' | cut -c1-8)
        expect=$(od -An -v -tx1 -N4 "$W/$f" 2>/dev/null | tr -d ' \n')
        ck "$f：头部 4 字节解出来 = 「$expect」" "$expect" "$head4"

        # ④ 空文件是**读得到但没内容**：应当只有标记行、没有头 —— 走「让 shell 现判」那条路
        if [ ! -s "$W/$f" ]; then
            case $head4 in
            "") ck "$f：空文件不产生假头部（按「没读到」处理）" "" "" ;;
            *)  printf '  [FAIL] %s：空文件竟解出头部 %s\n' "$f" "$head4"; fail=$((fail + 1)) ;;
            esac
        fi
    done
done

printf '\n=============== su 回显污染的两条前提 ===============\n'
# ScriptRunner.parseProbe 的第二道门槛是「十六进制行必须出现在标记行之后」，用来挡住
# su 持久会话把**命令本身**回显出来（命令里就写着 `##r1` 这些字面量）。
# 这条门槛依赖两个前提，这里直接验：
#   前提① 回显的那一整行 trim 之后**不等于** `##r1` / `##x1`（否则会被当成标记行）；
#   前提② 真正的输出里，标记行确实**排在**十六进制行前面。
cmd=$(emit "$W/elf.bin")

case "$cmd" in
'##r1') printf '  [FAIL] 前提①：命令文本本身就是 ##r1\n'; fail=$((fail + 1)) ;;
*)      ck "前提①：回显的整行不等于 ##r1（精确等值才认标记）" "##r1" "$cmd" ;;
esac

out=$(printf '%s\n' "$cmd" | "$MK" 2>&1)
first_marker=$(printf '%s\n' "$out" | grep -nE '^##[rx][01]$' | head -1 | cut -d: -f1)
first_hex=$(printf '%s\n' "$out" | grep -nE '^[0-9a-fA-F ]+$' | grep -vE '^[0-9]+:$' | head -1 | cut -d: -f1)
if [ -n "$first_marker" ] && [ -n "$first_hex" ] && [ "$first_marker" -lt "$first_hex" ]; then
    printf '  [ok] 前提②：标记行（第 %s 行）排在十六进制行（第 %s 行）之前\n' "$first_marker" "$first_hex"
    pass=$((pass + 1))
else
    printf '  [FAIL] 前提②：标记行 %s / 十六进制行 %s，顺序不对\n' "$first_marker" "$first_hex"
    fail=$((fail + 1))
fi

printf '\n结果: 通过 %d 项，失败 %d 项\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
