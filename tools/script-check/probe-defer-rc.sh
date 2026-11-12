#!/bin/bash
# 验证 deferToShell 形态里那条命令的**退出码透出**语义：
#
#   if [ -x F ]; then F; else sh F; fi ; __rxin_rc=$?; printf '… %s\n' "$__rxin_rc"
#
# 这里唯一的疑点是 `fi` 之后的 `$?` —— POSIX 规定复合命令 `if` 的退出状态是
# **所走分支最后一条命令**的状态，但 mksh / dash / busybox ash 实现各异，
# 必须三家都真跑一遍。若哪家返回 0，用户看到的「退出码」就是假的。
#
# 另外验证：`sh` 兜底那一支在没有执行位时确实被走到。
#
# 用法（WSL）：bash probe-defer-rc.sh

MK=/home/rouxin/shells/usr/bin/mksh
BB=/home/rouxin/bxbuild/host/busybox
W=/home/rouxin/dxtest-defer
rm -rf "$W"; mkdir -p "$W" || exit 1
cd "$W" || exit 1

printf '#!/bin/sh\nexit 7\n'   > ok.sh;     chmod 755 ok.sh    # 有执行位 → 直接执行，退 7
printf '#!/bin/sh\nexit 9\n'   > noexec.sh; chmod 644 noexec.sh # 无执行位 → sh 兜底，退 9
printf '#!/bin/sh\nexit 0\n'   > zero.sh;   chmod 755 zero.sh   # 退 0 也要能透出 0

# buildCommand(deferToShell) 的输出 —— 改了要同步 ScriptRunner.kt
emit() {
    q="'$1'"
    printf "%s" "if [ -x $q ]; then $q; else sh $q; fi; __rxin_rc=\$?; printf '\n[RouXin] 脚本已结束，退出码 %s\n' \"\$__rxin_rc\""
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
    for f in ok.sh noexec.sh zero.sh; do
        cmd=$(emit "$W/$f")
        # 同一条命令行里再追加一条：确认命令没把外层 shell 带走
        line=$(printf '%s; printf "STILL_ALIVE rc=%%s\\n" "$__rxin_rc"\n' "$cmd")
        if [ "$(basename "$sh")" = busybox ]; then
            out=$(printf '%s\n' "$line" | "$sh" sh 2>&1)
        else
            out=$(printf '%s\n' "$line" | "$sh" 2>&1)
        fi
        printf '%s\n' "$out" | sed 's/^/    | /'
        case $f in
        ok.sh)     ck "$f：走直接执行，退出码 7 透出"   "STILL_ALIVE rc=7" "$out" ;;
        noexec.sh) ck "$f：走 sh 兜底，退出码 9 透出"   "STILL_ALIVE rc=9" "$out" ;;
        zero.sh)   ck "$f：退出码 0 不被误读成失败"     "STILL_ALIVE rc=0" "$out" ;;
        esac
    done
done

# `sh` 兜底是否真的被走到（用一个「直接执行会失败、sh 能成功」的夹具）
printf 'echo RAN_VIA_SH\n' > via-sh.sh; chmod 644 via-sh.sh
q="'$W/via-sh.sh'"
printf '\n-- sh 兜底分支 --\n'
out=$(printf '%s; printf "rc=%%s\\n" "$?"\n' "if [ -x $q ]; then $q; else sh $q; fi" | /bin/dash 2>&1)
printf '%s\n' "$out" | sed 's/^/    | /'
ck "没有执行位时由 sh 兜底执行（而不是报 Permission denied）" "RAN_VIA_SH" "$out"
ck "sh 兜底分支的退出码仍是 0" "rc=0" "$out"

printf '\n结果: 通过 %d 项，失败 %d 项\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
