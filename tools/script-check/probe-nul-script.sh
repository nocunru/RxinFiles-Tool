#!/bin/bash
# 一次性证据：**含 NUL 字节、但 sh 本来就能跑的脚本**确实存在。
#
# 为什么必须实测：3.11.1 把「前 8KB 含 NUL」一律判成「sh 读不了」，于是这类文件
# 被强行改走「直接执行」。可它们**明明是脚本**——shebang 就是写给 sh 看的，
# 只是文件里另外嵌了一段二进制载荷。用户的原话是「有些本来就可以直接 sh 执行的
# 非纯文本脚本，sh 又执行不了了」——本脚本就是把这句话变成可复核的事实。
#
# 三种真实形态：
#   1. 载荷在 `exit 0` **之后**  ← 自解压脚本/一体化安装脚本最常见的写法
#   2. 载荷在**引号包起来的 heredoc** 里 ← 另一种常见写法
#   3. 载荷在**行内**（shell 部分真会读到它）← 这种确实会报错，作为反例
# 再加两个对照组：ELF 改名 .sh、gzip 魔数开头 —— 它们才是**真的**读不了。
#
# 用法（WSL）：bash probe-nul-script.sh

MK=${MK:-/home/rouxin/shells/usr/bin/mksh}
BB=${BB:-/home/rouxin/bxbuild/host/busybox}
W=${W:-/home/rouxin/nultest}

pass=0; fail=0
ck() { # ck <说明> <期望子串> <实际>
    case $3 in
    *"$2"*) printf '  [ok]   %s\n' "$1"; pass=$((pass + 1)) ;;
    *)      printf '  [FAIL] %s\n         期望含: %s\n         实际: %s\n' \
                "$1" "$2" "$(printf '%s' "$3" | head -3 | tr '\n' '/')"
            fail=$((fail + 1)) ;;
    esac
}
cknot() { # cknot <说明> <不该出现的子串> <实际>
    case $3 in
    *"$2"*) printf '  [FAIL] %s\n         不该含: %s\n         实际: %s\n' \
                "$1" "$2" "$(printf '%s' "$3" | head -3 | tr '\n' '/')"
            fail=$((fail + 1)) ;;
    *)      printf '  [ok]   %s\n' "$1"; pass=$((pass + 1)) ;;
    esac
}

[ -x "$MK" ] || { echo "缺 mksh: $MK"; exit 1; }
[ -x "$BB" ] || { echo "缺 busybox: $BB"; exit 1; }
command -v dash >/dev/null || { echo '缺 dash'; exit 1; }
command -v python3 >/dev/null || { echo '缺 python3'; exit 1; }

rm -rf "$W"; mkdir -p "$W" || exit 1

# ---- 造夹具：二进制部分交给 python3，字节才可控 ----
python3 - "$W" <<'PY'
import os, sys
w = sys.argv[1]
# 一枚"魔数"用于验证载荷是否被原样带到目的地：前 16 字节固定，便于比对
PAYLOAD = b'\x00\x01\x02\x0d\x0a\x90\xff' + bytes(range(48)) + os.urandom(300)

def w_(name, data):
    with open(os.path.join(w, name), 'wb') as f:
        f.write(data)

# 1. 载荷在 exit 0 之后
w_('tail-after-exit.sh',
   b'#!/bin/sh\necho START\n' + b'echo PWD_OK\n' + b'exit 0\n' + PAYLOAD)

# 2. 载荷在引号 heredoc 里（shell 会真读到它）
w_('heredoc-payload.sh',
   b'#!/bin/sh\necho START\ncat > out.bin <<\'RXEOF\'\n' + PAYLOAD + b'\nRXEOF\necho END\n')

# 3. 载荷在行内 —— shell 真会把它当命令（反例：这种 sh 跑不了）
w_('inline-payload.sh',
   b'#!/bin/sh\necho START\n' + PAYLOAD + b'\necho END\n')

# 3b. 同样的内容，但**没有 shebang**（脚本靠 `sh 文件` 跑，扩展名才是唯一的"声明"）
w_('noshebang-tail.sh',
   b'echo START\nexit 0\n' + PAYLOAD)

# 4. 对照组：ELF 改名 .sh（真的只能内核装载）
with open('/bin/true', 'rb') as f:
    w_('ctrl-elf.sh', f.read())

# 5. 对照组：魔数开头（gzip）
w_('ctrl-gzip.sh', b'\x1f\x8b\x08\x00' + b'echo START\n')

# 供第 2 组比对用的原始载荷
w_('payload.expected', PAYLOAD)
PY
[ $? -eq 0 ] || { echo '夹具生成失败'; exit 1; }

echo "=== 0. 夹具清单（前 8 字节 + 前 8KB 里的 NUL 个数）==="
for f in tail-after-exit.sh heredoc-payload.sh inline-payload.sh noshebang-tail.sh ctrl-elf.sh ctrl-gzip.sh; do
    head8=$(od -An -v -tx1 -N8 "$W/$f" | tr -d ' \n')
    nul=$(od -An -v -tx1 -N8192 "$W/$f" | tr ' ' '\n' | grep -c '^00$')
    printf '  %-22s %-16s NUL=%s\n' "$f" "$head8" "$nul"
done
echo "  —— 除两个对照组外，全都含 NUL：App 3.11.1 起把它们一律判成『二进制』"

echo
echo "=== 1. 三种真实形态 × 三个 shell：到底能不能跑 ==="
for spec in "mksh:$MK" "dash:dash" "ash:$BB sh"; do
    label=${spec%%:*}; cmd=${spec#*:}
    echo "-- $label"
    for f in tail-after-exit.sh heredoc-payload.sh noshebang-tail.sh; do
        # 每组在自己的子目录里跑，免得 out.bin 互相覆盖
        d="$W/run-$label-${f%.sh}"; rm -rf "$d"; mkdir -p "$d"; cp -f "$W/$f" "$d/$f"
        out=$(cd "$d" && $cmd "$d/$f" 2>&1); rc=$?
        printf '     %-22s rc=%s  out=[%s]\n' "$f" "$rc" "$(printf '%s' "$out" | tr '\n' '/')"
        ck "  $label + $f 退出码 0" "rc=0" "rc=$rc"
        ck "  $label + $f 打印了 START" "START" "$out"
        cknot "  $label + $f 没有把载荷当命令（无 not found）" "not found" "$out"
    done
done

echo
echo "=== 2. heredoc 里的载荷：字节原样落盘了吗（顺带说明 shell 会不会动 NUL）==="
for spec in "mksh:$MK" "dash:dash" "ash:$BB sh"; do
    label=${spec%%:*}; cmd=${spec#*:}
    d="$W/run-$label-heredoc-payload"
    if [ -f "$d/out.bin" ]; then
        got=$(wc -c < "$d/out.bin"); want=$(wc -c < "$W/payload.expected")
        same=$(cmp -s "$d/out.bin" "$W/payload.expected" && echo same || echo DIFF)
        printf '  %-5s 写出 %s B（原始 %s B）字节比对=%s\n' "$label" "$got" "$want" "$same"
    else
        printf '  %-5s 没写出 out.bin\n' "$label"
    fi
done

echo
echo "=== 3. 对照组：这两个才真的『sh 读不了』 ==="
for spec in "mksh:$MK" "dash:dash" "ash:$BB sh"; do
    label=${spec%%:*}; cmd=${spec#*:}
    for f in ctrl-elf.sh ctrl-gzip.sh; do
        out=$(cd "$W" && $cmd "$W/$f" 2>&1); rc=$?
        printf '  %-5s + %-14s rc=%-3s first=[%s]\n' \
            "$label" "$f" "$rc" "$(printf '%s' "$out" | head -1)"
        cknot "  $label + $f 不该跑成功（无 START）" "START" "$out"
    done
done

echo
printf '结果: 通过 %d 项，失败 %d 项\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
