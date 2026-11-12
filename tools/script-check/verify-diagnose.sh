#!/bin/bash
# diagnose-sh.sh 的回归：确认它在 mksh / busybox ash / dash 下
# 都能正确分流各类情况。用来锁住「措辞指纹」、「括号配平」、「加密/二进制形态」三块判断。
#
# ============================================================================
# ⚠ 这是**开发机（WSL / Linux）上的回归，不是给手机跑的工具。**
#   要在手机上诊断某个脚本，请直接用 diagnose-sh.sh 本身：
#       sh diagnose-sh.sh 你的脚本.sh
#
#   在手机上跑本脚本只会得到一片 [FAIL] —— 缺 /bin/dash、$HOME 为空、没有 python3、
#   没有可写的临时目录。那些不是「诊断脚本坏了」，而是「夹具造不出来」。
#   真机上就这么误报过 15 项：用户以为自己的脚本有 15 个问题。
#   现在：① 参数不再被静默忽略；② 缺什么在第 0 节列清楚；
#         ③ 夹具造不出来一律走 [skip]，不计入失败。
# ============================================================================
#
# 依赖（与 verify-scriptpipe.py 同一套约定）：
#   $HOME/shells/usr/bin/mksh     —— apt-get download mksh && dpkg -x
#   $HOME/bxbuild/host/busybox    —— tools/rxinbox/build-busybox.py 的主机版
# 缺哪个就跳过哪个场景，不算失败。
#
# 用法（在 WSL 里）：bash verify-diagnose.sh

HERE=$(cd "$(dirname "$0")" && pwd)
DG=$HERE/diagnose-sh.sh
# 第 7 节要把自己重新拉起来一次，必须用**绝对路径**：
# 那时 cwd 已经不在这里了，`bash "$0"` 里的相对路径会变成 No such file or directory
# （踩过：从仓库根目录 `bash tools/script-check/verify-diagnose.sh` 调用时必现）
SELF=$HERE/$(basename "$0")

# 本脚本不吃参数。从前传进来的参数被默默忽略 —— 于是 `sh verify-diagnose.sh 驱动.sh`
# 会被当成「跑一遍回归」，用户以为在诊断自己的脚本，其实一个字都没验到。
if [ $# -gt 0 ]; then
    printf '本脚本是开发机上的回归，不接受参数（收到: %s）。\n' "$*"
    printf '要诊断某个脚本，请改用：sh %s/diagnose-sh.sh <脚本路径>\n' "$HERE"
    exit 2
fi

# HOME 为空时（Android 上很常见）$HOME/shells/... 会拼成 //shells/... 这种怪路径，
# 报错信息毫无提示性。显式判一次，顺便让第 0 节能如实说出来。
DEVDIR=${HOME:-}
[ -n "$DEVDIR" ] || DEVDIR=/nonexistent-home
MK=$DEVDIR/shells/usr/bin/mksh
BB=$DEVDIR/bxbuild/host/busybox

pass=0
fail=0
skipped=0
ck() {
    if [ "$2" = "$3" ]; then
        printf '  [ok]   %s\n' "$1"
        pass=$((pass + 1))
    else
        printf '  [FAIL] %s\n         期望: %s\n         实际: %s\n' "$1" "$3" "$2"
        fail=$((fail + 1))
    fi
}
# 夹具造不出来 → 记 skip，不记 fail。这是真机误报的治法：
# 一片 [FAIL] 里根本看不出「其实一项都没真跑」。
skip() { printf '  [skip] %s\n' "$1"; skipped=$((skipped + 1)); }

# ---- 临时目录：不假设 /tmp 存在 ------------------------------------------
# Android 上没有 /tmp（真机实测 `cd: /tmp: No such file or directory`），
# TMPDIR 也常是空的。逐级退回：$TMPDIR → /tmp → 当前目录。
TMPROOT=${TMPDIR:-}
if [ -z "$TMPROOT" ] || [ ! -d "$TMPROOT" ] || [ ! -w "$TMPROOT" ]; then
    if [ -d /tmp ] && [ -w /tmp ]; then TMPROOT=/tmp; else TMPROOT=$PWD; fi
fi
W=$(mktemp -d 2>/dev/null)
if [ -z "$W" ] || [ ! -d "$W" ]; then
    W=$TMPROOT/.rxin-vd.$$
    mkdir -p "$W" || { printf '建不了临时目录，退出\n'; exit 2; }
fi
mkdir -p "$W/bin"
cd "$W" || exit 1

# ---- 第 0 节：环境清单 ----------------------------------------------------
# 先把自己站在什么环境里说清楚，后面任何 [skip] 才有上下文。
printf '===== 0. 环境 =====\n'
printf '  sh       : %s\n' "$(command -v sh 2>/dev/null || echo 无)"
printf '  bash     : %s\n' "$(command -v bash 2>/dev/null || echo 无)"
printf '  dash     : %s\n' "$([ -x /bin/dash ] && echo /bin/dash || echo 无)"
printf '  mksh     : %s\n' "$([ -x "$MK" ] && echo "$MK" || echo 无)"
printf '  busybox  : %s\n' "$([ -x "$BB" ] && echo "$BB" || echo 无)"
printf '  python3  : %s\n' "$(command -v python3 2>/dev/null || echo 无)"
printf '  临时目录 : %s\n' "$W"

# 夹具：各类典型情况
printf '#!/bin/sh\necho start\nfoo() { echo a; }\n)\necho done\n' > extra-close.sh
printf '#!/bin/bash\narr=(one two three)\necho "${arr[0]}"\n'          > bashism.sh
printf '#!/bin/sh\necho "a\n)\necho b\n'                               > unclosed-quote.sh
printf '#!/bin/sh\nname=world\n[ "$name" = world ] && echo hi\n'       > clean.sh

# —— 加密 / 编译过的脚本（判据都是实测来的，见 probe-enc-forms.sh）——
# 驱动用的 shell：优先 dash（措辞最稳），退到 mksh / busybox / 系统 sh。
# 从前这一节写死 /bin/dash，而 run/runf 里的 `sh` 在符号链接是死链时会**顺着 PATH
# 静默回退到系统 sh** —— 于是「sh=dash」那一节其实跑在别的 shell 上，标签与实际不符
# （真机上就这么「通过」过）。现在显式解析，找不到就整组 skip。
DRV=""
for c in /bin/dash "$MK" "$BB" /bin/sh /system/bin/sh; do
    if [ -x "$c" ]; then DRV=$c; break; fi
done
# 标签用「真身」的名字：Android 上 /system/bin/sh 是指向 mksh 的符号链接，
# 只取 basename 会得到「sh」，看不出到底是谁在跑。
DRVNAME=""
if [ -n "$DRV" ]; then
    DRVNAME=$(basename "$DRV")
    if command -v readlink >/dev/null 2>&1; then
        _real=$(readlink -f "$DRV" 2>/dev/null)
        [ -n "$_real" ] && DRVNAME=$(basename "$_real")
    fi
fi

# ① shc 之类工具产出的 ELF，只是名字还叫 .sh。需要**任意一个 ELF** 当夹具：
#    开发机上是 /bin/dash；Android 上没有它，退到 /system/bin/sh、toybox 这些。
#    一个都找不到就跳过 ELF 相关断言 —— 不是失败。
ELFSRC=""
for c in /bin/dash /usr/bin/dash /bin/busybox /system/bin/sh /system/bin/toybox; do
    if [ -f "$c" ]; then ELFSRC=$c; break; fi
done
if [ -n "$ELFSRC" ]; then
    cp "$ELFSRC" enc-elf.sh
else
    skip "找不到任何 ELF 当夹具，跳过「ELF 形态」相关断言"
fi
# ② 明文首行 #! 指向自带解密器 + 后面的密文
printf '#!/data/data/com.demo/files/bin/rxe\nAAAA BBBB CCCC DDDD EEEE\n' > enc-stub.sh
# ③ 明文头 + 裸 base64 密文块（随机数据，怎么跑都是同一形态）
{ printf '#!/bin/sh\necho start\n'; head -c 400 /dev/urandom | base64 -w 76; } > cipher.sh
# ④ 压缩包改名的
printf '\x1f\x8b\x08\x00not-a-script-at-all' > archive.sh

# ⑤ 明文 shebang + 二进制载荷（含 NUL）——**它仍然是脚本**，`sh 文件` 跑得动。
#    3.11.1 起 App 把「含 NUL」和「命中魔数」当成同一件事，于是这类脚本被强行
#    改走「直接执行」，在 /sdcard 上只剩一句「没有执行位」（3.11.6 修正）。
#    用户的反馈就是这一条：「有些本来就可以直接 sh 执行的非纯文本脚本，sh 又执行不了了」。
{ printf '#!/bin/sh\necho start\nexit 0\n'; printf 'A\0B\0C'; head -c 64 /dev/urandom; } > nul-shebang.sh
# ⑥ 同样的载荷，但首行没有 #!、名字也不像脚本 —— 形态真的判不出来，只能如实说
{ printf 'echo start\nexit 0\n'; printf 'A\0B\0C'; } > nulplain

# run / runf 靠 PATH 里那个符号链接来选中解释器。链接若指向不存在的文件，
# 内核会跳过它继续搜 PATH —— 结果是**悄悄用了系统 sh**，而调用方以为用的是 dash。
# 所以先确认解释器真的存在，不存在就返回失败，让调用方走 skip。
RUN_OK=yes
run() {
    if [ ! -x "$1" ]; then RUN_OK=no; return 1; fi
    rm -f "$W/bin/sh"
    ln -sf "$1" "$W/bin/sh"
    PATH="$W/bin:$PATH" sh "$DG" "$2" 2>&1
}

# 输出落文件再 grep —— 二进制夹具会让 bash 的 $() 报 "ignored null byte"，落文件更干净
runf() {
    if [ ! -x "$1" ]; then RUN_OK=no; : > "$3"; return 1; fi
    rm -f "$W/bin/sh"
    ln -sf "$1" "$W/bin/sh"
    PATH="$W/bin:$PATH" sh "$DG" "$2" > "$3" 2>&1
}

has() { if grep -qF -- "$1" "$2" 2>/dev/null; then echo yes; else echo no; fi; }
# hasfl：直接在 diagnose-sh.sh 的**源码**里找一句字面量，而不是在本文件里复刻一份实现。
# 这样「谁把判据改回去」会当场失败，也不会像副本那样悄悄漂移。
hasfl() { if grep -qF -- "$1" "$DG" 2>/dev/null; then echo yes; else echo no; fi; }

echo "===== 1. 诊断脚本自身能被三个 shell 解析 ====="
for s in /bin/dash "$MK" "$BB"; do
    [ -x "$s" ] || { skip "$s 不存在"; continue; }
    case $s in
    *busybox) "$s" sh -n "$DG" && ck "$(basename "$s") 语法自检" ok ok ;;
    *)        "$s" -n "$DG"    && ck "$(basename "$s") 语法自检" ok ok ;;
    esac
done

if [ -x "$MK" ]; then
    echo "===== 2. sh=mksh：多余右括号 ====="
    o=$(run "$MK" extra-close.sh)
    case $o in *"syntax error: unexpected ')'"*) ck "mksh 措辞带单引号" yes yes ;;
                *) ck "mksh 措辞带单引号" "$o" "含 unexpected ')'" ;; esac
    case $o in *"第 4 行：这里出现了一个多余的右括号"*) ck "配平定位到第 4 行" yes yes ;;
                *) ck "配平定位到第 4 行" "$o" "含第 4 行提示" ;; esac
    case $o in *"与 bash 语法无关"*) ck "结论走 close 分支" yes yes ;;
                *) ck "结论走 close 分支" "$o" "含 close 分支文案" ;; esac

    echo "===== 3. sh=mksh：引号未闭合 ====="
    o=$(run "$MK" unclosed-quote.sh)
    case $o in *"引号没闭合"*) ck "检出未闭合引号" yes yes ;;
                *) ck "检出未闭合引号" "$o" "含引号提示" ;; esac
    case $o in *"先把它补上"*) ck "结论指向引号问题" yes yes ;;
                *) ck "结论指向引号问题" "$o" "含引号结论" ;; esac
else
    skip "mksh 不在 $MK，跳过场景 2、3"
fi

if [ -x "$BB" ]; then
    echo "===== 4. sh=busybox ash：bash 数组 ====="
    o=$(run "$BB" bashism.sh)
    case $o in *'unexpected "("'*) ck "ash 措辞带双引号" yes yes ;;
                *) ck "ash 措辞带双引号" "$o" '含 unexpected "("' ;; esac
    case $o in *"数组赋值 name=(...)"*) ck "扫出数组赋值" yes yes ;;
                *) ck "扫出数组赋值" "$o" "含数组命中" ;; esac
    case $o in *"两种可能"*) ck "结论走 open 分支（不被双引号误判）" yes yes ;;
                *) ck "结论走 open 分支" "$o" "含 open 分支文案" ;; esac
else
    skip "busybox 不在 $BB，跳过场景 4"
fi

# 驱动 shell 未必是 dash：开发机上是，Android 上没有 /bin/dash。
# 标签按**实际用的那个**写 —— 从前固定写「sh=dash」，而 run() 会静默回退到系统 sh，
# 于是标签在说谎（真机上这条就这么「通过」过）。
if [ -n "$DRV" ] && [ -x "$DRV" ]; then
    echo "===== 5. sh=$DRVNAME：干净 POSIX 脚本 ====="
    o=$(run "$DRV" clean.sh)
    case $o in *"没报错"*) ck "识别为语法干净" yes yes ;;
                *) ck "识别为语法干净" "$o" "含未报错文案" ;; esac
    case $o in *"文本脚本（没有魔数"*) ck "形态判为文本脚本" yes yes ;;
                *) ck "形态判为文本脚本" "$o" "含文本形态" ;; esac
else
    skip "没有可用的驱动 shell（dash/mksh/busybox/sh 都没有），跳过场景 5~6.6"
fi

# ---------------------------------------------------------------------------
# 6. 加密 / 编译过的脚本：必须走「不是文本脚本」这条路，
#    并且**不能**被误判成「源码里多了一个右括号」。
#    判据本身与 shell 无关，驱动用哪个 shell 都行（见上面的 $DRV）。
#
#    6 ~ 6.6 整段包在一个函数里，只为了一件事：**在真机上它们应当整体 skip**。
#    没有 /bin/dash、没有 python3、夹具造不出来 —— 那些都不是「诊断脚本坏了」。
#    从前每一条都会记成 [FAIL]，真机上一次性报出 15 项，把人吓得以为脚本有问题。
# ---------------------------------------------------------------------------
body_enc() {
echo "===== 6. 加密/编译过的脚本（形态分流） ====="

if [ -f enc-elf.sh ]; then
runf "$DRV" enc-elf.sh "$W/o-elf.txt"
ck "ELF 认出来了"            "$(has 'ELF 可执行文件'    "$W/o-elf.txt")" yes
ck "ELF 走 binary 结论"      "$(has '这不是一个文本脚本' "$W/o-elf.txt")" yes
ck "ELF 提到内核 execve"     "$(has './enc-elf.sh` 能跑'  "$W/o-elf.txt")" yes
ck "ELF 不再去查括号配平"     "$(has '跳过：它不是文本'   "$W/o-elf.txt")" yes
ck "ELF 不落进 close 分支"    "$(has '与 bash 语法无关'   "$W/o-elf.txt")" no
else
    skip "没有 ELF 夹具（本机找不到任何 ELF），跳过 ELF 形态断言"
fi

runf "$DRV" enc-stub.sh "$W/o-stub.txt"
ck "外来 shebang 认出来了"    "$(has 'rxe'                 "$W/o-stub.txt")" yes
ck "判为自带解密器"          "$(has '多半是脚本自带的'   "$W/o-stub.txt")" yes
ck "外来 shebang 走 foreign" "$(has '自带解密器**的加密脚本' "$W/o-stub.txt")" yes
ck "提示解密器路径不存在"     "$(has '不存在'              "$W/o-stub.txt")" yes

runf "$DRV" cipher.sh "$W/o-cipher.txt"
ck "base64 密文块认出来了"    "$(has 'base64 密文块'       "$W/o-cipher.txt")" yes
ck "提示是 not found 而非语法错" "$(has 'not found'        "$W/o-cipher.txt")" yes

runf "$DRV" archive.sh "$W/o-arch.txt"
ck "gzip 认出来了"           "$(has 'gzip 压缩数据'       "$W/o-arch.txt")" yes

# 含 NUL 的脚本：**不能**判成「不是文本脚本」（3.11.6）。
# 这一条是用户实际踩到的坑：把「含 NUL」和「命中魔数」当成同一件事，
# 于是「#!/bin/sh + 一段二进制载荷」这种**本来 sh 就能跑**的脚本被改道，
# 在共享存储上只剩一句「没有执行位」。
runf "$DRV" nul-shebang.sh "$W/o-nulshebang.txt"
ck "含 NUL 但有 #! → 不判成二进制" "$(has '这不是一个文本脚本'  "$W/o-nulshebang.txt")" no
ck "含 NUL 但有 #! → 说它是脚本"   "$(has '仍然是文本脚本'      "$W/o-nulshebang.txt")" yes
ck "给出实测依据（三家 shell 退 0）" "$(has '退出码 0'            "$W/o-nulshebang.txt")" yes
ck "如实说明载荷里的 NUL 会被丢掉" "$(has '载荷字节百分百保真'  "$W/o-nulshebang.txt")" yes
ck "含 NUL 的脚本也不再提括号配平" "$(has '跳过：它不是文本'    "$W/o-nulshebang.txt")" yes

runf "$DRV" nulplain "$W/o-nulplain.txt"
ck "无 #! 的含 NUL 文件如实说判不出" "$(has '判不出来'          "$W/o-nulplain.txt")" yes
ck "给出 A/B 两条实测路"          "$(has '两条都实测一次'      "$W/o-nulplain.txt")" yes
ck "不替用户下「编译产物」的结论"  "$(has '这不是一个文本脚本'  "$W/o-nulplain.txt")" no

# 反向：正常脚本不能被误判成加密产物
runf "$DRV" clean.sh "$W/o-clean.txt"
ck "正常脚本不误报为二进制"   "$(has '这不是一个文本脚本'   "$W/o-clean.txt")" no
ck "正常脚本不误报为密文"     "$(has 'base64 密文块'       "$W/o-clean.txt")" no

# ---------------------------------------------------------------------------
# 6.5 ELF 细节：它是不是**这台机器能装载的** ELF（3.11.3 新增）
#
# 「头 8 字节是 ELF」只回答了「sh 读不了它」，回答不了「直接执行能不能跑起来」——
# 后者要看 e_machine（目标架构）与 PT_INTERP（动态解释器）。
# 顺带锁住「可执行性」的探针行为：目录可写时必须真跑一次，不可写就如实说未知。
# ---------------------------------------------------------------------------
echo "===== 6.5 ELF 架构 / 动态解释器 / 目录可执行性 ====="

# 复用第 6 节那份输出（ELF 夹具拷在临时目录里 → 目录可写，探针能建）。
# 没有那份输出（本机没找到 ELF 夹具）就整段 skip —— 别拿空文件去 grep 出一片 FAIL。
if [ -s "$W/o-elf.txt" ]; then
    ck "ELF 报出目标架构"       "$(has '目标架构'   "$W/o-elf.txt")" yes
    ck "ELF 报出动态解释器"     "$(has '动态解释器' "$W/o-elf.txt")" yes
    ck "给出可否执行的判定"     "$(has '判定      :' "$W/o-elf.txt")" yes
    ck "可写入目录的 ELF 探针跑通" "$(has '该目录**可以**执行 ELF' "$W/o-elf.txt")" yes
    ck "第 1 节报出运行身份"     "$(has '运行身份  : uid=' "$W/o-elf.txt")" yes
    ck "挂载点走 /proc/mounts"   "$(has '挂载点    : ' "$W/o-elf.txt")" yes
    ck "挂载点带上文件系统类型"   "$(has '（文件系统 ' "$W/o-elf.txt")" yes

    # 解释器那一行**必须给出确定形态**——三种之一，不能是含糊话。
    # 用三个 `grep -qF` 布尔量，**不要**去捕获匹配行：这份输出里含有 ELF 夹具的
    # 二进制字节（第 1 节的「首行」），toybox 的 grep 会把整份文件当二进制、直接打印
    # `Binary file … matches` 而不是那一行（真在 toybox 复验轮里踩到过）。
    # -q 只看退出码，不受二进制判定影响。
    _il=no
    for _p in 'Android 系统 linker' '没有 PT_INTERP 段' '不是 Android 的'; do
        [ "$(has "$_p" "$W/o-elf.txt")" = yes ] && _il=yes
    done
    ck "解释器一行给出确定形态" "$_il" yes

    # 桌面 linker 那两条只对「开发机上的 Linux 动态链接 ELF」成立。
    # 在 Android 上夹具会换成 /system/bin/sh 之类（形态不同），那时按 skip 处理，
    # 但**不能**因此放过「形态判错」——上面那条通用断言已经把这条路堵住了。
    if [ "$(has '/lib64/ld-linux-x86-64.so.2' "$W/o-elf.txt")" = yes ]; then
        ck "桌面 linker 路径读出来了" "$(has '/lib64/ld-linux-x86-64.so.2' "$W/o-elf.txt")" yes
        ck "桌面 linker 判为非 Android" "$(has '**不是 Android 的**' "$W/o-elf.txt")" yes
    else
        skip "夹具不是桌面 Linux 的动态链接 ELF，跳过「桌面 linker」两条断言"
    fi
else
    skip "没有 ELF 形态的输出（本机找不到 ELF 夹具），跳过 6.5 全部断言"
fi

# 探针判据本身（源码事实，不是复刻）：只有 126/127 才算「跑不了」。
# 专门锁这条，是因为踩过一次：toybox 这类多合一二进制**按 argv[0] 分派 applet**，
# 被改名成 .rxin_exec_probe.NNNN 后会打印 "Unknown command" 并自己返回 **127**
# —— 与内核拒绝执行的 127 撞车，把一个明明能执行的目录判成不能执行。
ck "探针源用的是 sh 而不是 toybox" "$(hasfl 'for _c in /system/bin/sh /bin/sh')" yes
ck "探针按 126/127 判为拒绝"       "$(hasfl "126|127) printf 'denied'")" yes
ck "探针执行前清空 ENV"            "$(hasfl 'ENV= "$_p" < /dev/null')" yes

# 只读目录里的 ELF（普通用户不可写）→ 探针建不了，应当如实说「未知」而不是瞎猜。
# 目录挑一个真存在且不可写的：开发机是 /bin，Android 是 /system/bin。
RODIR=""; ROSH=""
for d in /bin /usr/bin /system/bin; do
    if [ -d "$d" ] && [ ! -w "$d" ]; then RODIR=$d; break; fi
done
if [ -n "$RODIR" ]; then
    for c in "$RODIR/dash" "$RODIR/sh" "$RODIR/toybox" "$RODIR/ls" "$RODIR/cat"; do
        if [ -f "$c" ] && [ -x "$c" ]; then ROSH=$c; break; fi
    done
fi
if [ -n "$DRV" ] && [ -n "$ROSH" ]; then
    runf "$DRV" "$ROSH" "$W/o-bin.txt"
    ck "只读目录如实报未知"     "$(has '可执行性  : 未知' "$W/o-bin.txt")" yes
    ck "只读目录仍报出架构"     "$(has '目标架构'        "$W/o-bin.txt")" yes
else
    skip "找不到「只读目录 + 其中的 ELF」，跳过未知场景（$RODIR / $ROSH）"
fi

# 反向：文本脚本不该打印 ELF 专属字段
if [ -s "$W/o-clean.txt" ]; then
    ck "文本脚本不打印 ELF 字段" "$(has '目标架构' "$W/o-clean.txt")" no
else
    skip "没有正常脚本的输出，跳过「不打印 ELF 字段」反向断言"
fi

# arm64 夹具用本工程随包发的 busybox。**先拷到临时目录**再跑 ——
# 否则「可执行性」探针会往 jniLibs 源码目录里写临时文件
JNI=$HERE/../../app/src/main/jniLibs/arm64-v8a/libbusybox.so
if [ -f "$JNI" ] && [ -n "$DRV" ]; then
    cp "$JNI" "$W/arm64elf.sh"
    runf "$DRV" "$W/arm64elf.sh" "$W/o-arm.txt"
    ck "arm64 ELF 认得出架构"    "$(has 'AArch64 (arm64)'      "$W/o-arm.txt")" yes
    ck "arm64 ELF 认得出 linker" "$(has '/system/bin/linker64'  "$W/o-arm.txt")" yes
    case $(uname -m) in
    aarch64|arm64)
        ck "本机就是 arm64 → 判定一致"   "$(has '架构判定  : 与本机一致' "$W/o-arm.txt")" yes ;;
    *)
        ck "本机非 arm64 → 判定不一致"   "$(has '与本机不一致'          "$W/o-arm.txt")" yes ;;
    esac
else
    skip "没有 $JNI 或没有驱动 shell，跳过 arm64 夹具"
fi

# ---------------------------------------------------------------------------
# 6.6 静态链接 / 外来解释器 / 架构不符 —— 用**自己造的 ELF** 精确验证
#
# 现实里凑不齐这些组合（本机没有 arm64 的静态程序，也没有「arm64 头 + 桌面 linker」
# 这种东西）。ELF 头与程序头表的结构是死的，用 struct 拼出来即可 ——
# 被测脚本只读头部、不会执行它，所以造出来的文件不需要真能跑。
#
# 这一段锁的是**上一版的错**：从前是「在前 8KB 里 grep 一张候选路径表」，
# 搜不到就一律判成「解释器不是本机的 → 直接执行大概率失败」，
# 于是**静态链接的文件（其实能跑）也被判成跑不了** —— 真机上就是这么报错的。
# ---------------------------------------------------------------------------
HOSTM=$(uname -m)
# 判据不是「PATH 上有没有 python3」，而是**夹具真的被造出来了**。
# 真机上可能压根没有 python3，也可能有一个跑不通的 —— 两种都是「造不出夹具」，
# 都该走 skip。从前只看 command -v，于是夹具不存在时下面十几条会一起报 FAIL。
if command -v python3 >/dev/null 2>&1; then
    python3 - "$W" <<'PY'
import struct, sys, os
w = sys.argv[1]

def mk64(name, machine, interp=None, extra=b""):
    phesz, phoff = 56, 64
    phnum = 2 if interp else 1
    dataoff = phoff + phnum * phesz
    blob = (interp.encode() + b"\0") if interp else b""
    total = dataoff + len(blob) + len(extra)
    ident = b"\x7fELF" + bytes([2, 1, 1, 0]) + b"\0" * 8
    eh = ident + struct.pack("<HHIQQQIHHHHHH", 3, machine, 1, 0, phoff, 0,
                             0x40, 64, phesz, phnum, 64, 0, 0)
    ph = struct.pack("<IIQQQQQQ", 1, 5, 0, 0, 0, total, total, 0x1000)
    if interp:
        # PT_INTERP：类型 3，p_offset 指向那一小段解释器路径
        ph += struct.pack("<IIQQQQQQ", 3, 4, dataoff, dataoff, dataoff,
                          len(blob), len(blob), 1)
    open(os.path.join(w, name), "wb").write(eh + ph + blob + extra)

def mk32(name, machine, interp=None, extra=b""):
    phesz, phoff = 32, 52
    phnum = 2 if interp else 1
    dataoff = phoff + phnum * phesz
    blob = (interp.encode() + b"\0") if interp else b""
    total = dataoff + len(blob) + len(extra)
    ident = b"\x7fELF" + bytes([1, 1, 1, 0]) + b"\0" * 8
    eh = ident + struct.pack("<HHIIIIIHHHHHH", 3, machine, 1, 0, phoff, 0,
                             0x40, 52, phesz, phnum, 40, 0, 0)
    ph = struct.pack("<IIIIIIII", 1, 0, 0, 0, total, total, 5, 0x1000)
    if interp:
        # ELF32 的 p_offset @+4、p_filesz @+16，与 ELF64 不同 —— 故意留一个
        # 这样的夹具，就是为了让写错偏移量的实现在这里当场失败
        ph += struct.pack("<IIIIIIII", 3, dataoff, dataoff, dataoff,
                          len(blob), len(blob), 4, 1)
    open(os.path.join(w, name), "wb").write(eh + ph + blob + extra)

mk64("st-native.sh", 62)                                    # 静态，架构与本机一致
mk64("dy-foreign.sh", 62, "/lib64/ld-linux-x86-64.so.2")    # 动态，链到桌面的 ld-linux
mk64("st-musl.sh", 62, None, b"musl libc (static) xxxxxxxx")  # 静态 + musl 标记
mk64("dy-android.sh", 183, "/system/bin/linker64")          # arm64 + Android linker
mk32("dy-elf32.sh", 3, "/lib/ld-linux.so.2")                # 32 位头，另走一套偏移量
PY
fi

if [ -f "$W/st-native.sh" ] && [ -n "$DRV" ]; then
    runf "$DRV" "$W/st-native.sh" "$W/o-st.txt"
    ck "静态 ELF 判为静态"       "$(has '没有 PT_INTERP 段' "$W/o-st.txt")" yes
    ck "静态 ELF 不报外来解释器"  "$(has '不是 Android 的'   "$W/o-st.txt")" no

    runf "$DRV" "$W/dy-foreign.sh" "$W/o-dyf.txt"
    ck "外来 linker 路径读得对"   "$(has '/lib64/ld-linux-x86-64.so.2' "$W/o-dyf.txt")" yes
    ck "外来 linker 判为非 Android" "$(has '**不是 Android 的**' "$W/o-dyf.txt")" yes

    runf "$DRV" "$W/st-musl.sh" "$W/o-musl.txt"
    ck "静态 ELF 里的 musl 标记"  "$(has '疑似 musl' "$W/o-musl.txt")" yes
    ck "musl 提示 seccomp 风险"   "$(has 'set_robust_list' "$W/o-musl.txt")" yes

    runf "$DRV" "$W/dy-android.sh" "$W/o-and.txt"
    ck "Android linker 认出来了"  "$(has '/system/bin/linker64' "$W/o-and.txt")" yes

    runf "$DRV" "$W/dy-elf32.sh" "$W/o-32.txt"
    ck "ELF32 头解析得出机器码"   "$(has 'x86 (32 位)'        "$W/o-32.txt")" yes
    ck "ELF32 的 PT_INTERP 也读得出" "$(has '/lib/ld-linux.so.2' "$W/o-32.txt")" yes

    case $HOSTM in
    x86_64|amd64)
        ck "静态 + 架构一致 → 判定可直接执行" \
           "$(has '架构一致 + 静态链接' "$W/o-st.txt")" yes
        ck "外来解释器 → 判定会失败" \
           "$(has '解释器不是本机的 —— 直接执行会失败' "$W/o-dyf.txt")" yes
        ck "arm64 文件 → 判定架构不符" \
           "$(has '架构不符，本机装不起来' "$W/o-and.txt")" yes
        ;;
    *)
        skip "本机是 $HOSTM，跳过三条与 x86-64 前提绑定的判定断言"
        ;;
    esac
else
    skip "造不出 ELF 夹具（没有 python3 / python3 跑不通 / 没有驱动 shell），跳过 6.6"
fi
}

# 驱动 shell 有没有，决定第 6 ~ 6.6 是「真跑」还是「整体 skip」。
# 在 Android 上 /bin/dash 与 mksh/busybox 夹具都不在，但 /system/bin/sh 在 ——
# 于是这一段仍会真跑一部分，而不是像从前那样甩出一片 FAIL。
if [ -n "$DRV" ] && [ -x "$DRV" ]; then
    body_enc
else
    printf '===== 6. 加密/编译过的脚本（形态分流） =====\n'
    skip "没有可用的驱动 shell（dash / mksh / busybox / 系统 sh 都没有），第 6 ~ 6.6 全部跳过"
fi

cd "$TMPROOT"
rm -rf "$W"
printf '\n结果: 通过 %d 项，失败 %d 项，跳过 %d 项\n' "$pass" "$fail" "$skipped"
if [ "$pass" -eq 0 ] && [ "$fail" -eq 0 ]; then
    printf '注意: 一项都没真跑 —— 这是开发机回归，需要 mksh / busybox / dash / python3。\n'
    printf '      要在手机上诊断脚本，请用 diagnose-sh.sh：sh diagnose-sh.sh <脚本路径>\n'
fi

# ---------------------------------------------------------------------------
# 7. 换成工具箱 applet 再跑一遍整套断言。
#
# 诊断脚本最终要丢到 Android 上执行，那里的 od / tr / grep / sed / awk 是
# **toybox（系统）或 busybox（本 App 随包发的那个）**，不是 GNU 的 ——
# 用 GNU 工具链跑通不等于在真机跑得通。已知的真实差异：
#   * `od` 不带 `-v` 会把连续重复行压成 `*`，NUL 计数会少算（实测 5543 vs 5567）；
#   * Ubuntu 的 toybox 根本**没有** tr / awk，把它们垫成 toybox 会直接把命令弄没。
# 所以这里**按被测工具箱自己的 applet 清单**建垫片，只垫它真有的那几个，
# 并打印垫了几个，避免「其实一个都没垫上」的假通过。
# ---------------------------------------------------------------------------
TOOL_ROUND_FAIL=0

tool_round() {
    label=$1
    prog=$2
    list=$3
    printf '\n===== 7.%s 换成 %s 的命令再跑一遍整套断言 =====\n' "$label" "$label"
    TB=$(mktemp -d)
    n=0
    for a in od tr grep awk sed head wc basename mktemp readlink ls cat cp mkdir rm ln; do
        printf '%s\n' "$list" | tr ' ' '\n' | grep -qx "$a" || continue
        ln -sf "$prog" "$TB/$a"
        n=$((n + 1))
    done
    if [ "$n" -lt 4 ]; then
        printf '  [skip] %s 只提供 %s 个可用 applet，垫片意义不大\n' "$label" "$n"
        rm -rf "$TB"
        return 0
    fi
    printf '  垫片 %s 个 applet（其余仍走系统工具）\n' "$n"
    PATH="$TB:$PATH" RXIN_DIAG_NO_TOOL_ROUND=1 bash "$SELF"
    rc=$?
    printf '  %s 那一轮: %s\n' "$label" "$([ "$rc" -eq 0 ] && echo 通过 || echo 失败)"
    rm -rf "$TB"
    [ "$rc" -eq 0 ] || TOOL_ROUND_FAIL=$((TOOL_ROUND_FAIL + 1))
}

if [ -n "${RXIN_DIAG_NO_TOOL_ROUND:-}" ]; then
    : # 已经是复验的子进程了，不再递归
elif ! command -v bash >/dev/null 2>&1; then
    # 复验要 `bash "$SELF"` 把自己重新拉起来一次。没有 bash 就没法做（Android 上
    # 系统里没有 bash），直接跳过 —— 不是失败。
    skip "没有 bash，跳过工具箱语义复验（复验要重新拉起本脚本）"
else
    TOY=$DEVDIR/shells/usr/bin/toybox
    if [ -x "$TOY" ]; then
        tool_round toybox "$TOY" "$("$TOY" 2>/dev/null)"
    else
        skip "没有 $TOY，跳过 toybox 语义复验"
    fi

    if [ -x "$BB" ]; then
        tool_round busybox "$BB" "$("$BB" --list 2>/dev/null)"
    else
        skip "没有 $BB，跳过 busybox 语义复验"
    fi
fi

[ "$fail" -eq 0 ] && [ "$TOOL_ROUND_FAIL" -eq 0 ]
