#!/bin/bash
# 回归断言：`ScriptRunner.buildCommand` 在 directExec 形态下**生成的那条命令**
# 是不是合法 shell、退出码有没有透出、没有执行位时说的话对不对、
# 126 / 127 有没有给出人话解释，
# **以及脚本跑完后外层 shell 还活着、还能不能接着执行后续语句**。
#
# 从 3.11.2 起命令尾部不再 `exit` —— 脚本结束必须停在交互提示符上，用户才有机会
# 复制输出。这里用「命令跑完后还能不能执行下一条语句」把这条锁死：谁把 exit 加回去，
# STILL_ALIVE 那一行就会消失，断言当场失败。
#
# 从 3.11.4 起尾部还多了一个 `case`，对 126 / 127 各给一句解释。
# 判据放在 shell 里是有意的：同一个文件 ROOT 能跑、App 的普通身份被 SELinux 拦住，
# 是很常见的组合 —— 要按**真正执行它的那个身份**判，Java 侧判不了。
#
# 这里手写的是「按 Kotlin 拼出来的字符串」的等价物 —— 用来确认命令形状本身没问题；
# 至于 Kotlin 有没有按这个形状拼，由 verify-scriptpipe.py 的源码断言负责。
# 改了 ScriptRunner.buildCommand 就要同步改下面的 emit()。
#
# 用法（WSL）：bash probe-direct-exec-cmd.sh

MK=/home/rouxin/shells/usr/bin/mksh
BB=/home/rouxin/bxbuild/host/busybox
W=/home/rouxin/dxtest
rm -rf "$W"; mkdir -p "$W" || exit 1
cd "$W" || exit 1

# 夹具①：有执行位，跑完固定退 7
printf '#!/bin/sh\nexit 7\n' > ok.sh;       chmod 755 ok.sh
# 夹具②：同样的内容，**去掉执行位**（模拟共享存储）
printf '#!/bin/sh\nexit 7\n' > noexec.sh;   chmod 644 noexec.sh
# 夹具③④：真的以 126 / 127 退出 —— 内核拒绝执行 与 找不到解释器 的退出码
printf '#!/bin/sh\nexit 126\n' > r126.sh;   chmod 755 r126.sh
printf '#!/bin/sh\nexit 127\n' > r127.sh;   chmod 755 r127.sh

# buildCommand(directExec) 的输出 —— 改了要同步 ScriptRunner.kt
emit() {
    q="'$1'"
    name=$(basename "$1")
    printf "%s" "if [ -x $q ]; then $q; else printf '\\n[RouXin] %s 没有执行位，无法执行。\\n' '$name'; printf '[RouXin] 这个文件是编译/加密过的，只能由内核装载。\\n'; printf '[RouXin] 共享存储（/sdcard）不支持执行位，chmod 也无效，需放到支持执行位的目录。\\n'; printf '[RouXin] 若它本来就在 /data 这类能记权限的目录里，用 ROOT 身份把执行位加上（chmod 755）即可。\\n'; false; fi; __rxin_rc=\$?; printf '\\n[RouXin] 脚本已结束，退出码 %s\\n' \"\$__rxin_rc\"; case \$__rxin_rc in 126) printf '[RouXin] 126 = 文件在，但内核或安全策略拒绝执行它（Permission denied）。\\n[RouXin] 最可能的原因：SELinux 不允许「当前身份」执行这个文件 —— App 的普通身份限制最多。\\n[RouXin] 回到列表页长按它，改用 ROOT 身份再试一次。\\n' ;; 127) printf '[RouXin] 127 = 找不到命令，或它的解释器不存在（首行 #! 指向的路径没有）。\\n' ;; esac"
}

pass=0
fail=0
ck() {  # ck <描述> <期望子串> <实际文本>
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
    for f in ok.sh noexec.sh r126.sh r127.sh; do
        cmd=$(emit "$W/$f")

        # 命令文本本身就不该有 exit（独立于行为，直接在字符串上判一次）
        case $cmd in
        *'exit "$__rxin_rc"'*)
            printf '  [FAIL] %s：命令尾部又带上了 exit\n' "$f"
            fail=$((fail + 1))
            ;;
        *)
            printf '  [ok] %s：命令尾部没有 exit\n' "$f"
            pass=$((pass + 1))
            ;;
        esac

        # 在同一条命令行里追加读取语句：带 exit 的话它根本不会被执行，
        # 那就等价于「用户没法接着敲命令 / 复制不到东西」。
        line=$(printf '%s; printf "STILL_ALIVE rc=%%s\\n" "$__rxin_rc"\n' "$cmd")
        if [ "$(basename "$sh")" = busybox ]; then
            out=$(printf '%s\n' "$line" | "$sh" sh 2>&1)
        else
            out=$(printf '%s\n' "$line" | "$sh" 2>&1)
        fi
        printf '%s\n' "$out" | sed 's/^/    | /'

        case $f in
        ok.sh)
            ck "$f 跑完后外层 shell 还活着" "STILL_ALIVE rc=7" "$out"
            ;;
        noexec.sh)
            ck "$f 跑完后外层 shell 还活着" "STILL_ALIVE rc=1" "$out"
            ck "$f 说明了没有执行位的原因" "没有执行位" "$out"
            ck "$f 指向 /sdcard 那种虚执行位的情形" "共享存储（/sdcard）不支持执行位" "$out"
            # 3.11.5：可执行位不是「只能换目录」——/data 这类真记权限的目录 chmod 一下就行
            ck "$f 给了 ROOT 下的补救办法（chmod 755）" "chmod 755" "$out"
            ;;
        r126.sh)
            # 退出码必须原样透出，不能被 case 里的 printf 覆盖掉
            ck "$f 跑完后外层 shell 还活着" "STILL_ALIVE rc=126" "$out"
            ck "$f 给出 126 的解释" "126 = 文件在" "$out"
            ck "$f 提示改用 ROOT 身份" "改用 ROOT 身份再试一次" "$out"
            ;;
        r127.sh)
            ck "$f 跑完后外层 shell 还活着" "STILL_ALIVE rc=127" "$out"
            ck "$f 给出 127 的解释" "127 = 找不到命令" "$out"
            # 127 不该顺带把 126 那段也打出来（case 分支互斥）
            case $out in
            *"改用 ROOT 身份"*)
                printf '  [FAIL] %s：127 分支把 126 的文案也打了出来\n' "$f"
                fail=$((fail + 1))
                ;;
            *)
                printf '  [ok] %s：两个分支互斥\n' "$f"
                pass=$((pass + 1))
                ;;
            esac
            ;;
        esac
    done
done

printf '\n结果: 通过 %d 项，失败 %d 项\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
