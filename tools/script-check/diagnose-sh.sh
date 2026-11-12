#!/bin/sh
# 诊断「sh 脚本.sh 报语法错误、但 ./脚本.sh 或终端直接执行却正常」。
#
# 原理：`sh 文件` 把文件内容交给 sh 解析，**shebang 那行被当注释忽略**；
#       `./文件` 是内核 execve，读 shebang 找真解释器。两者可能不是同一个 shell。
#
# 报错措辞能反推是哪个 shell（实测）：
#   dash        : 1.sh: 5: Syntax error: "(" unexpected        （大写 S、双引号、位置在后）
#   busybox ash : 1.sh: line 5: syntax error: unexpected "("    （小写 s、双引号）
#   mksh        : E: 1.sh[5]: syntax error: unexpected '('      （小写 s、**单引号**）
#   bash        : 1.sh: line 5: syntax error near unexpected token `)'   （反引号+单引号）
#
# 三类报错的处置方式完全不同（实测结论，见第 2、6、7 节）：
#   unexpected '(' —— 可能是 bash 专属语法（数组 arr=(..) / for i in (..) / 进程替换 <(..)），
#                     也可能只是真的多了一个左括号。要按第 7 节逐行找。
#   unexpected ')' —— 若文件是**文本脚本**，那与 bash 语法无关：mksh 支持数组、[[ ]]、
#                     ${v//a/b}、${v^^}、${v:0:3}、${!v}、(( ))，全都不报这个错；它只会因为
#                     **源码里真的多了一个右括号**（括号层级已是 0 却又见到 `)`）而报。
#                     **但如果 `./文件` 能跑而 `sh 文件` 报这一句，那多半根本不是文本脚本** ——
#                     见第 2 节。实测 ELF 二进制改名成 .sh 时，mksh/dash/busybox ash
#                     三家**都**在第 1 行报 `unexpected ')'`。同一条错误不可能只在一种调用下出现，
#                     所以「两边表现不一致」本身就是「文件不是文本」的证据。
#
# 另外实测：CRLF 换行的报错形态是 `unexpected ''`（空 token，因为 \r 被当成一个词）、
# `unmatched 'if'`、`unexpected 'in'` —— **不会**长成 `unexpected ')'`。
#
# 用法：sh diagnose-sh.sh <脚本路径>
#
# 纯 POSIX 写法，dash / busybox ash / mksh / bash 都能跑，可直接丢到 Android 上执行。

set -u

F=${1:-}
if [ -z "$F" ]; then
    printf '用法: sh %s <脚本路径>\n' "$0"
    exit 2
fi
if [ ! -f "$F" ]; then
    printf '找不到文件: %s\n' "$F"
    exit 2
fi

sec() { printf '\n===== %s =====\n' "$1"; }

# ---- 小端 2 字节（4 位十六进制）→ 十进制 ----
# ELF 头部字段都是小端存放，`od -tx1` 读出来的字节序是反的，得自己拼回去。
#
# **先校验格式再算**：`$((0x * 256 + 0x))` 这种空操作数不只是「结果不对」——
# 多数 shell 遇到算术错误会**直接退出脚本**，后面几节全都不打印了。
h2d() {
    case $1 in
    [0-9a-f][0-9a-f][0-9a-f][0-9a-f]) ;;
    *) return 1 ;;
    esac
    echo $((0x${1#??} * 256 + 0x${1%??}))
}

# ---- 小端 4 字节（8 位十六进制）→ 十进制 ----
# 高位字节不为 0 时直接放弃：**mksh 的算术是 32 位**，`0x80 * 16777216` 会溢出成负数。
# 我们要的 e_phoff / p_offset / p_filesz 都是小值，超过 4GB 的文件本来也不在讨论范围。
h4d() {
    case $1 in
    [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) ;;
    *) return 1 ;;
    esac
    _q0=$(printf '%s' "$1" | cut -c1-2)
    _q1=$(printf '%s' "$1" | cut -c3-4)
    _q2=$(printf '%s' "$1" | cut -c5-6)
    _q3=$(printf '%s' "$1" | cut -c7-8)
    [ "$_q3" = "00" ] || return 1
    echo $((0x$_q0 + 0x$_q1 * 256 + 0x$_q2 * 65536))
}

# ---- 「这段明文是不是出现在文件的十六进制流里」 ----
# 用途：在 ELF 里找 PT_INTERP 那条解释器路径。
#
# 为什么绕这一圈：ELF 头里没有直接指向 PT_INTERP 的字段，要读程序头表再逐个比对
# 段类型，纯 POSIX 里太笨重。而链接器的名字本来就是明文存在文件里的，直接把
# **文件前 8KB 的十六进制流**与**候选路径的十六进制**做字符串比对即可。
#
# 为什么不用 `tr -c '[:print:]' '\n'` 把可打印串切出来（更直观的写法）：
# **busybox 的 tr 不支持 `[:print:]` 字符类**，实测输出的是
# `||||in|||||||||t|…` 这种碎片（它把 `[:print:]` 当成了字符集合 `:pritn]` 的补集），
# 拿去 grep 只会得到假阴性。这里用到的 `od -An -tx1 -v` + `tr -d ' \n'` 三家的行为
# 都一致，是实测过的。
hexstream() {   # hexstream <文件> <字节数>
    head -c "$2" "$1" 2>/dev/null | od -An -tx1 -v 2>/dev/null | tr -d ' \n'
}

hexof() {       # hexof <明文字符串>
    printf '%s' "$1" | od -An -tx1 -v 2>/dev/null | tr -d ' \n'
}

hexhas() {      # hexhas <十六进制流> <明文> → 命中返回 0
    _needle=$(hexof "$2")
    [ -n "$_needle" ] || return 1
    printf '%s' "$1" | grep -q "$_needle" 2>/dev/null
}

# ---- 十六进制流 → 可打印文本 ----
# 只用来还原 PT_INTERP 里那条解释器路径。不可打印的字节直接丢掉 ——
# 既避免把 NUL 泄到终端，也免得路径尾巴上的二进制把整行冲乱。
hex2txt() {
    awk '{
        s = $0; out = ""
        for (i = 1; i + 1 <= length(s); i += 2) {
            hi = index("0123456789abcdef", substr(s, i, 1)) - 1
            lo = index("0123456789abcdef", substr(s, i + 1, 1)) - 1
            if (hi < 0 || lo < 0) continue
            v = hi * 16 + lo
            if (v >= 32 && v < 127) out = out sprintf("%c", v)
        }
        printf "%s", out
    }'
}

# ---- ELF 细节：只读文件头，无副作用 ----
#
# 「头 8 字节是 ELF」只说明它是可执行映像，回答不了**「在这台机器上跑不跑得起来」**。
# 那得看另外两样：**目标架构**（e_machine）与**是否需要动态解释器**（有没有 PT_INTERP）。
# x86-64 机器上编出来的 shc 产物在 arm64 手机上永远跑不起来，
# 而内核给出的只有一句没头没脑的 `Exec format error`。
#
# 头部字段位置（小端；下文一律用「十六进制流的第 N 个字符」定位，1 字节 = 2 字符）：
#   偏移 4   EI_CLASS   1 字节  → 01=32 位, 02=64 位   （字符 9-10）
#   偏移 16  e_type     2 字节  → 字符 33-36
#   偏移 18  e_machine  2 字节  → 字符 37-40
#   ELF64：e_phoff@32(8) → 字符 65-72 取其低 4 字节；e_phentsize@54 → 109-112；e_phnum@56 → 113-116
#   ELF32：e_phoff@28(4) → 字符 57-64；             e_phentsize@42 → 85-88；  e_phnum@44 → 89-92
#
# 结论留在 _archok / _hasinterp / _linkok / _verdict / ILDPATH 里，供第 9 节引用。
elf_detail() {
    _f=$1
    _verdict=""; _hasinterp=no; _linkok=""; ILDPATH=""
    _hdr=$(hexstream "$_f" 64)
    _cls=$(printf '%s' "$_hdr" | cut -c9-10)
    _etype=$(h2d "$(printf '%s' "$_hdr" | cut -c33-36)") || _etype=""
    _emach=$(h2d "$(printf '%s' "$_hdr" | cut -c37-40)") || _emach=""
    [ -n "$_etype" ] && [ -n "$_emach" ] || return 0

    case $_etype in
    2) _etname="EXEC（固定地址可执行）" ;;
    3) _etname="DYN（PIE，位置无关可执行）" ;;
    *) _etname="其它 (e_type=$_etype)" ;;
    esac

    case $_emach in
    3)   _emname="x86 (32 位)";     _emexpect="i386|i486|i586|i686|x86" ;;
    8)   _emname="MIPS";            _emexpect="mips|mips64" ;;
    40)  _emname="ARM (32 位)";     _emexpect="arm|armv7l|armv8l" ;;
    62)  _emname="x86-64";          _emexpect="x86_64|amd64" ;;
    183) _emname="AArch64 (arm64)"; _emexpect="aarch64|arm64" ;;
    243) _emname="RISC-V";          _emexpect="riscv32|riscv64" ;;
    *)   _emname="未知";            _emexpect="" ;;
    esac

    _uname=$(uname -m 2>/dev/null)
    printf 'ELF 类型  : %s\n' "$_etname"
    printf '目标架构  : %s\n' "$_emname"
    printf '本机架构  : %s\n' "${_uname:-(读不到)}"

    _archok=-1
    if [ -z "$_emexpect" ]; then
        printf '架构判定  : 认不出这个 e_machine，无法判断\n'
    elif printf '%s' "$_uname" | grep -Eq "^($_emexpect)\$" 2>/dev/null; then
        printf '架构判定  : 与本机一致\n'
        _archok=1
    else
        printf '架构判定  : **与本机不一致** —— 这个文件在这台机器上跑不起来，\n'
        printf '            只能换到对应架构的机器上重新编译。\n'
        _archok=0
    fi

    # ---- PT_INTERP：从程序头表里真正读出来 ----
    # 这里从前是「在前 8KB 里 grep 一张候选路径表」，那是**猜**：
    # 候选表之外的解释器（musl 的 ld-musl-*、别的发行版的 ld-linux-*）一律搜不到，
    # 于是「静态链接」和「解释器我不认识」被压成了同一句话 ——
    # 实测在真机上就报成了「解释器不是本机的」，把其实能跑的文件说成跑不了。
    # ELF 的规则是死的：程序头表里出现 PT_INTERP(3) 段就是动态链接，
    # 那条路径原样写在段里，读出来即可，不需要猜。
    _phoff=""; _pe=""; _pn=""
    case $_cls in
    02)
        _phoff=$(h4d "$(printf '%s' "$_hdr" | cut -c65-72)") || _phoff=""
        _pe=$(h2d "$(printf '%s' "$_hdr" | cut -c109-112)") || _pe=""
        _pn=$(h2d "$(printf '%s' "$_hdr" | cut -c113-116)") || _pn=""
        _ooff=8; _foff=32       # ELF64：p_offset @+8, p_filesz @+32
        ;;
    01)
        _phoff=$(h4d "$(printf '%s' "$_hdr" | cut -c57-64)") || _phoff=""
        _pe=$(h2d "$(printf '%s' "$_hdr" | cut -c85-88)") || _pe=""
        _pn=$(h2d "$(printf '%s' "$_hdr" | cut -c89-92)") || _pn=""
        _ooff=4; _foff=16       # ELF32：p_offset @+4, p_filesz @+16
        ;;
    *)
        _ooff=8; _foff=32
        ;;
    esac

    _ioff=0
    _ilen=0
    if [ -n "$_phoff" ] && [ -n "$_pe" ] && [ -n "$_pn" ] \
       && [ "$_pe" -ge 8 ] && [ "$_pn" -gt 0 ] && [ "$_pn" -le 256 ]; then
        _need=$((_phoff + _pn * _pe))
        if [ "$_need" -gt 0 ] && [ "$_need" -le 65536 ]; then
            _tbl=$(hexstream "$_f" "$_need")
            _i=0
            while [ "$_i" -lt "$_pn" ]; do
                _e=$((_phoff + _i * _pe))
                _pt=$(h2d "$(printf '%s' "$_tbl" | cut -c$((_e * 2 + 1))-$((_e * 2 + 4)))") || _pt=""
                if [ "$_pt" = 3 ]; then
                    _ioff=$(h4d "$(printf '%s' "$_tbl" | cut -c$(((_e + _ooff) * 2 + 1))-$(((_e + _ooff) * 2 + 8)))") || _ioff=0
                    _ilen=$(h4d "$(printf '%s' "$_tbl" | cut -c$(((_e + _foff) * 2 + 1))-$(((_e + _foff) * 2 + 8)))") || _ilen=0
                    _hasinterp=yes
                    break
                fi
                _i=$((_i + 1))
            done
        fi
    fi

    if [ "$_hasinterp" = yes ]; then
        _iend=$((_ioff + _ilen))
        if [ "$_ilen" -gt 0 ] && [ "$_iend" -le 65536 ]; then
            _istream=$(hexstream "$_f" "$_iend")
            ILDPATH=$(printf '%s' "$_istream" | cut -c$((_ioff * 2 + 1))-$((_iend * 2)) | hex2txt)
        fi
    fi

    if [ "$_hasinterp" = yes ]; then
        case $ILDPATH in
        /system/*|/apex/*)
            printf '动态解释器: %s（Android 系统 linker）\n' "$ILDPATH"
            _linkok=1
            ;;
        "")
            printf '动态解释器: 有 PT_INTERP 段，但路径读不出来（文件可能被截断）\n'
            _linkok=0
            ;;
        *)
            printf '动态解释器: %s（**不是 Android 的**）\n' "$ILDPATH"
            printf '            本机没有这个路径 —— 它是在别的系统上链接出来的。\n'
            _linkok=0
            ;;
        esac
    else
        printf '动态解释器: 没有 PT_INTERP 段 —— **静态链接**，不依赖任何 linker\n'
        _linkok=1
        # 静态还要再分一层：musl 的启动代码在 App 沙盒里会被 seccomp 杀掉（本项目实测），
        # glibc 的静态程序没有这个问题。只能靠文件里的字符串判，所以写「疑似」。
        _libc=""
        _scan=$(hexstream "$_f" 32768)
        if hexhas "$_scan" "musl"; then
            _libc=musl
        elif hexhas "$_scan" "GNU C Library" || hexhas "$_scan" "GLIBC_"; then
            _libc=glibc
        fi
        case $_libc in
        musl)
            printf '静态库    : 疑似 musl\n'
            printf '            **在 App 里可能跑不起来**：musl 的启动代码会调 set_robust_list，\n'
            printf '            App 沙盒的 seccomp 把它拦成 SIGSYS，一 exec 就被杀，连报错都没有。\n'
            printf '            ROOT 身份或 adb shell 不受这条限制。\n'
            ;;
        glibc)
            printf '静态库    : 疑似 glibc（一般可以直接跑）\n'
            ;;
        esac
    fi

    if [ "$_archok" = 0 ]; then
        printf '判定      : **架构不符，本机装不起来**（内核会报 Exec format error）\n'
        _verdict=badarch
    elif [ "$_archok" = -1 ]; then
        printf '判定      : 认不出目标架构，无法判断 —— 本机能跑就直接跑一次试试\n'
        _verdict=unknown
    elif [ "$_linkok" = 1 ]; then
        if [ "$_hasinterp" = yes ]; then
            printf '判定      : 架构一致、解释器也是本机的 —— **可以直接执行它**\n'
        else
            printf '判定      : 架构一致 + 静态链接 —— **可以直接执行它**（不需要 linker）\n'
        fi
        _verdict=ok
    else
        printf '判定      : 架构对得上，但解释器不是本机的 —— 直接执行会失败\n'
        _verdict=badinterp
    fi
}

# ---- 实测「这个目录到底能不能执行文件」 ----
#
# 为什么不看权限位：FUSE / sdcardfs 上的共享存储把权限位显示成 rwxrwxrwx，
# `test -x` 也返回真，可内核 execve 照样拒绝 —— 在 /sdcard 上权限位是**假的**。
# 唯一可靠的做法是真跑一次。探针是**我们自己新建的文件**，不碰用户的脚本，
# 也不管那脚本是什么形态，所以没有副作用。
#
# 为什么探针优先用**真 ELF**：要执行的目标是 ELF，而「执行 ELF」和「执行脚本」在
# SELinux 里是两条不同的判定（脚本还要额外过 execute_no_trans，在 AOSP 里是
# neverallow）。拿脚本当探针在部分机器上会给出假阴性。
#
# 为什么 ELF 探针选 **sh** 而不是 toybox/busybox：
# 那两个是「多合一」二进制，按 `argv[0]` 的 basename 分派 applet。探测时文件被叫
# `.rxin_exec_probe.1234`，它认不出这个名字，于是打印 "Unknown command" 并**返回 127**
# —— 与「内核拒绝执行」的 127 撞在一起，把能执行的目录判成不能执行（实测踩过）。
# sh 不需要 argv[0] 分派：不带参数 + stdin 给 /dev/null 时只读到 EOF，退 0。
# 实测 dash / mksh / busybox ash 三家都是 0；去掉执行位是 126，文件不存在是 127。
#
# 探针文件由我们自己创建、用完立刻删除，带的是**目标目录的标签**，正是要测的那个。
# 顺带 `ENV=` 清空环境：万一某个 shell 在非交互模式下也去 source $ENV，
# 沙箱 profile 的退出码会被误算到探针头上（实测三家都不读，但清掉不亏）。
#
# 输出：elf / script / denied / unknown
probe_exec_dir() {
    _d=$1
    _p="$_d/.rxin_exec_probe.$$"
    if [ ! -d "$_d" ] || [ ! -w "$_d" ]; then printf 'unknown'; return 0; fi

    _src=""
    for _c in /system/bin/sh /bin/sh; do
        if [ -x "$_c" ]; then _src=$_c; break; fi
    done
    if [ -z "$_src" ]; then
        _src=$(command -v sh 2>/dev/null || true)
    fi

    _mode=script
    if [ -n "$_src" ] && cp "$_src" "$_p" 2>/dev/null; then
        _mode=elf
    else
        printf '#!/bin/sh\nexit 0\n' > "$_p" 2>/dev/null || { rm -f "$_p" 2>/dev/null; printf 'unknown'; return 0; }
    fi
    chmod 755 "$_p" 2>/dev/null

    # 126 = 找到文件但执行被拒（EACCES，含 SELinux 拒绝）；127 = 找不到。
    # 其余任何退出码都说明**它真的被装载并跑起来了**。
    ENV= "$_p" < /dev/null > /dev/null 2>&1
    _rc=$?
    rm -f "$_p" 2>/dev/null

    case $_rc in
    126|127) printf 'denied' ;;
    *)       printf '%s' "$_mode" ;;
    esac
}

# elf_detail 的结论先占位 —— 它们只在「是 ELF」时才被赋值，
# 而本脚本开了 `set -u`，第 9 节要直接引用，未初始化会**直接终止脚本**。
_archok=""; _verdict=""; _hasinterp=""; _linkok=""; ILDPATH=""

# ---------------------------------------------------------------- 预读（第 1 节的告警是否适用要看它）
# 头 8 字节的十六进制。`-v` 不能省：不带时 GNU/busybox/toybox **三个实现都**会把
# 连续重复行压成 `*`，NUL 计数会少算（实测 5567 → 5543）。
H8=$(head -c 8 "$F" 2>/dev/null | od -An -tx1 -v 2>/dev/null | tr -d ' \n')

BINKIND=""
case $H8 in
7f454c46*)                      BINKIND="ELF 可执行文件" ;;
cafebabe*)                      BINKIND="Java class" ;;
cffaedfe*|cefaedfe*|feedface*|feedfacf*) BINKIND="Mach-O 可执行文件" ;;
1f8b*)                          BINKIND="gzip 压缩数据" ;;
1f9d*)                          BINKIND="compress(.Z) 压缩数据" ;;
425a68*)                        BINKIND="bzip2 压缩数据" ;;
fd377a585a00*)                  BINKIND="xz 压缩数据" ;;
28b52ffd*)                      BINKIND="zstd 压缩数据" ;;
04224d18*)                      BINKIND="lz4 压缩数据" ;;
377abcaf271c*)                  BINKIND="7z 压缩包" ;;
526172211a07*)                  BINKIND="RAR 压缩包" ;;
504b0304*|504b0506*|504b0708*)  BINKIND="ZIP 压缩包" ;;
25504446*)                      BINKIND="PDF 文档" ;;
89504e47*)                      BINKIND="PNG 图片" ;;
ffd8ff*)                        BINKIND="JPEG 图片" ;;
esac

# UTF-16 的文件是文本（双字节存放必然带 NUL），不能当二进制
UTF16=0
case $H8 in fffe*|feff*) UTF16=1 ;; esac

# NUL 字节：正常文本脚本（含 GBK/UTF-8 中文）永远不含它 ——
# 用可打印率判断会误伤 GBK 脚本（汉字字节都 ≥ 0x80），NUL 不会。
#
# 但它**只说明「不是纯文本」，不等于 sh 读不了**（3.11.6 修正）：
# 脚本里嵌一段二进制载荷（自解压脚本、把数据塞进引号 heredoc）也会在前 8KB
# 出现 NUL，而 `sh 文件` 本来就能跑完 —— 三家 shell 实测全退 0、载荷不会被
# 当命令执行（见 probe-nul-script.sh）。所以这里把 NUL 数**照实报出来**，
# 结论留给第 2 节结合 shebang 去下，别在这个变量上直接判死。
NULS=0
if [ "$UTF16" -eq 0 ]; then
    NULS=$(head -c 8192 "$F" 2>/dev/null | od -An -tx1 -v 2>/dev/null \
        | tr ' ' '\n' | grep -c '^00$' 2>/dev/null)
    NULS=${NULS:-0}
fi

sec "1. 文件本身"

# 绝对路径：下面的目录 / 文件系统判断都要用它（传进来的可能是相对路径）
ABSPATH=$F
case $F in
/*) ;;
*) ABSPATH=$PWD/$F ;;
esac
DIR=$(dirname "$ABSPATH")
BASE=$(basename "$ABSPATH")

printf '路径      : %s\n' "$F"
printf '绝对路径  : %s\n' "$ABSPATH"
printf '所在目录  : %s\n' "$DIR"

# 运行身份 —— 「可执行性」那条结论只代表**当前这个身份**，所以得先说清是谁在跑。
# uid=0 时探针验的是 ROOT 的能力，不代表 App 内的普通身份（两者 SELinux 域不同）。
_RUNUID=$(id -u 2>/dev/null || echo '?')
_RUNCTX=$(cat /proc/self/attr/current 2>/dev/null | tr -d '\r\n')
printf '运行身份  : uid=%s  SELinux=%s\n' "$_RUNUID" "${_RUNCTX:-读不到}"
if [ "$_RUNUID" = 0 ]; then
    printf '            （ROOT —— 下面的「可执行性」只证明 ROOT 跑得动，不代表 App 内）\n'
fi

# 挂载点与文件系统类型：拿 /proc/mounts 做**最长前缀匹配**。
# 为什么不用 `df -P 路径`：它的列布局在 toybox / busybox / GNU 三家之间不一致，
# 实测在真机上曾把一个毫不相干的路径当成挂载点打印出来（误导人的那种错）。
# /proc/mounts 的字段顺序由内核定，稳定。
MOUNT=""
FSTYPE=""
if [ -r /proc/mounts ]; then
    _m=$(awk -v p="$DIR" '
        {
            mp = $2
            gsub(/\\040/, " ", mp)
            pp = (p == "/" ? "/" : p "/")
            mm = (mp == "/" ? "/" : mp "/")
            if (index(pp, mm) == 1 && length(mp) > length(best)) { best = mp; bestfs = $3 }
        }
        END { if (best != "") printf "%s %s", best, bestfs }
    ' /proc/mounts 2>/dev/null)
    if [ -n "$_m" ]; then
        MOUNT=$(printf '%s' "$_m" | awk '{print $1}')
        FSTYPE=$(printf '%s' "$_m" | awk '{print $2}')
    fi
fi
if [ -n "$MOUNT" ]; then
    printf '挂载点    : %s  （文件系统 %s）\n' "$MOUNT" "${FSTYPE:-?}"
    case $FSTYPE in
    *fuse*|sdcardfs|fuseblk)
        printf '            —— 共享存储，execve 基本不可用（下面的探针会给准话）\n'
        ;;
    esac
fi
# 首行只取前 120 字节 —— 二进制文件的「第一行」可能有几百字节的乱码，直接打会把屏幕冲掉
printf '首行      : %s\n' "$(sed -n '1p' "$F" 2>/dev/null | head -c 120)"
printf '权限/大小 : %s\n' "$(ls -l "$F" 2>/dev/null | awk '{print $1, $5 "B"}')"

if [ -x "$F" ]; then
    printf '执行位    : 权限位显示「有」（可以直接 ./%s 执行）\n' "$BASE"
else
    printf '执行位    : **没有** —— 内核不会装载它，直接执行会 Permission denied\n'
fi

# 权限位可能是假的，这一条才是真的：在同目录下建个探针文件，实际跑一次
EXEC_DIR=$(probe_exec_dir "$DIR")
case $EXEC_DIR in
elf)
    printf '可执行性  : 该目录**可以**执行 ELF（自建探针：真 ELF 拷进来实测跑通）\n'
    ;;
script)
    printf '可执行性  : 该目录**可以**执行文件（探针是脚本 —— 本机拿不到可当 ELF 探针的 sh）\n'
    ;;
denied)
    printf '可执行性  : **该目录跑不了可执行文件** —— 自建探针同样被拒（退出码 126/127）。\n'
    printf '            权限位上的 rwx 与 `test -x` 在这个文件系统 / 策略下都是假的\n'
    printf '            （共享存储 /sdcard、/storage/emulated 就是这样）。\n'
    printf '            要直接执行这个文件，得把它挪到 /data 下的目录里，\n'
    printf '            例如 App 私有目录 /data/data/com.rxinfiles.tool/files/。\n'
    ;;
*)
    printf '可执行性  : 未知（该目录不可访问或不可写，建不了探针）\n'
    ;;
esac

if [ -n "$BINKIND" ] || [ "$NULS" -gt 0 ]; then
    printf '告警跳过  : 这份文件里有二进制内容，BOM / CRLF / 换行符这些判断对它没有意义\n'
    printf '            （App 也是这样：带二进制内容的文件一律不给「修复编码」，\n'
    printf '             免得把载荷里的 0x0D 一起删掉 —— 能跑 ≠ 能改。见第 2 节的形态结论）\n'
else
    bom=$(head -c 3 "$F" 2>/dev/null | od -An -tx1 2>/dev/null | tr -d ' \n')
    if [ "$bom" = "efbbbf" ]; then
        printf '警告      : 带 UTF-8 BOM —— 首行的 #! 不在行首，直接执行也会失败\n'
    fi

    crn=$(awk '/\r/ { n++ } END { print n + 0 }' "$F" 2>/dev/null)
    if [ "${crn:-0}" -gt 0 ]; then
        printf '警告      : 有 %s 行以 CR 结尾（CRLF 换行）。它的典型表现是\n' "$crn"
        printf '            unexpected （空 token）/ unmatched / not found …… 不是 unexpected ")"。\n'
        printf '            建议先修掉排除干扰：sed -i "s/\\r$//" %s\n' "$F"
    fi
fi

sec "2. 它到底是不是文本脚本（加密 / 编译过的脚本在这里现形）"
printf '头 8 字节 : %s\n' "$H8"

# ---- 2.3 首行 shebang 指向什么 ----
FIRST=$(sed -n '1p' "$F" 2>/dev/null)
SHPATH=""
SHNAME=""
FOREIGN=""
case $FIRST in
\#!*)
    SHPATH=$(printf '%s\n' "$FIRST" | sed -e 's/^#![[:space:]]*//' \
        | awk '{ for (i = 1; i <= NF; i++) { if ($i !~ /^-/) { print $i; exit } } }')
    SHNAME=$(basename "$SHPATH" 2>/dev/null)
    case $SHNAME in
    sh|ash|dash|mksh|ksh|busybox|toybox|bash|zsh|python|python3|node|perl|ruby|lua|php|env)
        ;;
    *)
        FOREIGN=$SHNAME
        ;;
    esac
    ;;
esac

# ---- 2.4 尾部像不像密文块（只在「像文本」时才算，二进制的行长度毫无意义） ----
B64LINE=0
if [ -z "$BINKIND" ] && [ "$NULS" -eq 0 ]; then
    B64LINE=$(awk 'length($0) >= 64 && $0 ~ /^[A-Za-z0-9+\/=]+$/ { n++ } END { print n + 0 }' "$F" 2>/dev/null)
    B64LINE=${B64LINE:-0}
fi

FILEKIND=text
if [ -n "$BINKIND" ]; then
    FILEKIND=binary
    printf '形态      : **%s** —— 这不是文本脚本\n' "$BINKIND"
    if [ "$BINKIND" = "ELF 可执行文件" ]; then
        printf '            前 4 字节 7f454c46 是 ELF 魔数：`shc` 这类「脚本加密」工具\n'
        printf '            就是把脚本编译成这样一个 ELF。\n'
        # 「是 ELF」还不够 —— 得看它是不是**这台机器能装载的** ELF。
        # 放在「形态」之后打印：先让人知道它是什么，再看细节。
        elf_detail "$F"
    fi
elif [ "$NULS" -gt 0 ]; then
    # 含 NUL、但没命中魔数 —— **不能直接判死**（3.11.6）。两种情况都真实存在，
    # 分界线是**文件自己有没有声明读者**：首行 `#!`，或扩展名是脚本类型。
    # 单独一个 FILEKIND（而不是复用它 `binary`）：第 5/6/7 节照样跳过（它确实不是
    # 纯文本，括号配平没有意义），但第 9 节的结论完全不同。
    FILEKIND=nulbin
    printf '形态      : 含 NUL 字节（前 8KB 有 %s 个）\n' "$NULS"
    if [ -n "$FOREIGN" ]; then
        printf '            首行 `#!%s`（%s）—— 是「自带解密器的壳」，\n' "$SHPATH" "$FOREIGN"
        printf '            `./文件` 时内核按 shebang 拉起它去读文件解密。\n'
    elif [ -n "$SHNAME" ]; then
        printf '            但首行 `#!%s` 声明了解释器 —— **它仍然是文本脚本**，\n' "$SHPATH"
        printf '            只是另嵌了一段二进制载荷。`sh 文件` 读得动它：\n'
        printf '            三家 shell 实测都正常跑完（退出码 0），载荷不会被当命令执行\n'
        printf '            （载荷写在 `exit 0` 之后、或包在引号 heredoc 里时才如此）。\n'
        printf '            注意：三家 shell 都会**悄悄丢掉载荷里的 NUL**（实测 355→353 B），\n'
        printf '            所以「能跑」是真的，「载荷字节百分百保真」不是。\n'
    else
        printf '            首行没有 #!（不看扩展名）—— 单看字节判不出来：\n'
        printf '            可能是嵌了载荷的脚本（`sh` 读得动），也可能是魔数没覆盖到的\n'
        printf '            编译产物（只能直接执行）。见第 9 节。\n'
        printf '            注：App 还会把 `.sh` 这类扩展名也算作「声明了读者」，这里只看首行。\n'
    fi
elif [ "$UTF16" -eq 1 ]; then
    printf '形态      : UTF-16 编码的文本脚本（有 BOM，所以先不看 NUL）\n'
    printf '            注意：`sh 文件` 读它也会错乱，需要先转成 UTF-8/无 BOM\n'
elif [ -n "$FOREIGN" ]; then
    FILEKIND=foreign
    printf '形态      : 文本，但首行 #! 指向 `%s`（%s）\n' "$FOREIGN" "$SHPATH"
    printf '            它不是 shell 解释器 —— 多半是脚本自带的**解密器**。\n'
    if [ -e "$SHPATH" ]; then
        printf '            该路径存在。`./%s` 时内核会把它拉起来去读文件；\n' "$(basename "$F")"
    else
        printf '            该路径在当前环境里**不存在**。\n'
    fi
    printf '            `sh %s` 时 #! 被当注释，sh 直接解析后面的内容。\n' "$(basename "$F")"
elif [ "$B64LINE" -ge 3 ]; then
    FILEKIND=cipher
    printf '形态      : 文本，但有 %s 行是「超长且只含 base64 字符」的行\n' "$B64LINE"
    printf '            —— 像「明文头 + base64 密文块」。`sh 文件` 会把密文\n'
    printf '            当命令名逐个执行，报一串 not found / 语法错误。\n'
else
    printf '形态      : 文本脚本（没有魔数、没有 NUL、shebang 正常）\n'
fi

sec "3. 你的 sh 到底是谁"
printf 'command -v sh : %s\n' "$(command -v sh 2>/dev/null || echo '?')"
printf '/bin/sh 实体  : %s\n' "$(readlink -f /bin/sh 2>/dev/null || echo '(不存在)')"
printf '版本变量      : KSH_VERSION=%s  BASH_VERSION=%s\n' \
    "$(sh -c 'printf %s "${KSH_VERSION:-无}"' 2>/dev/null)" \
    "$(sh -c 'printf %s "${BASH_VERSION:-无}"' 2>/dev/null)"

# 最可靠的一招：喂一个必然报错的片段，用**报错措辞**反推解释器。
# 路径可能是链接、可能是 PATH 里某个同名文件，只有措辞不会骗人。
pr=$(mktemp 2>/dev/null || echo /tmp/.rxin-probe.$$)
printf 'echo a )\n' > "$pr" 2>/dev/null
printf '措辞指纹      : %s\n' "$(sh -n "$pr" 2>&1 | head -1)"
rm -f "$pr" 2>/dev/null

for h in dash ash mksh bash busybox toybox; do
    p=$(command -v "$h" 2>/dev/null) || continue
    printf '  %-8s %s\n' "$h" "$p"
done
printf '\n对照（同一条错误在四个 shell 下的写法）：\n'
printf '  dash        : 1.sh: 5: Syntax error: ")" unexpected\n'
printf '  busybox ash : 1.sh: line 5: syntax error: unexpected ")"\n'
printf '  mksh        : E: 1.sh[5]: syntax error: unexpected '"'"')'"'"'\n'
printf '  bash        : 1.sh: line 5: syntax error near unexpected token `)'"'"'\n'

sec "4. sh -n 语法检查（只解析、不执行）"
OUT=$(sh -n "$F" 2>&1)
RC=$?
if [ -n "$OUT" ]; then printf '%s\n' "$OUT"; fi
if [ "$RC" -eq 0 ]; then
    printf '退出码: 0 —— sh 认为语法没问题\n'
else
    printf '退出码: %s\n' "$RC"
fi

N=$(printf '%s\n' "$OUT" | sed -n 's/^[^:]*: *\([0-9][0-9]*\):.*/\1/p' | head -1)
if [ -z "$N" ]; then
    N=$(printf '%s\n' "$OUT" | sed -n 's/.*\[\([0-9][0-9]*\)\].*/\1/p' | head -1)
fi
if [ -z "$N" ]; then
    N=$(printf '%s\n' "$OUT" | sed -n 's/.*line \([0-9][0-9]*\):.*/\1/p' | head -1)
fi

if [ -n "$N" ]; then
    S=$((N - 6)); [ "$S" -lt 1 ] && S=1
    E=$((N + 6))
    sec "5. 出错位置附近（第 $S - $E 行，>> 是报出的第 $N 行）"
    if [ "$FILEKIND" = binary ] || [ "$FILEKIND" = nulbin ]; then
        printf '  （二进制内容，不打印 —— 那串 `unexpected ")"` 是字节凑出来的 token）\n'
    else
        awk -v s="$S" -v e="$E" -v n="$N" \
            'NR >= s && NR <= e { printf "%s%5d | %s\n", (NR == n ? ">>" : "  "), NR, $0 }' "$F"
    fi
fi

sec "6. 括号 / 引号配平（文本脚本报 unexpected \")\" 请看这里）"
if [ "$FILEKIND" = binary ] || [ "$FILEKIND" = nulbin ]; then
    printf '  （跳过：它不是文本，括号配平无从谈起 —— 别去源码里找那个右括号）\n'
    BAL=""
else
# 逐字符扫，跳过单引号、双引号内部（双引号里 \\ 转义下一个字符）。
# 注释只处理行首的 #，行尾注释里的括号会算进去 —— 属于「粗略但够用」。
BAL=$(awk '
{
    n = length($0)
    for (i = 1; i <= n; i++) {
        c = substr($0, i, 1)
        if (insq) { if (c == "\047") insq = 0; continue }
        if (indq) {
            if (c == "\\") { i++; continue }
            if (c == "\"") indq = 0
            continue
        }
        if (c == "#" && i == 1) break
        if (c == "\047") { insq = 1; continue }
        if (c == "\"")   { indq = 1; continue }
        if (c == "(")    { depth++; opens++;  if (depth > maxd) maxd = depth; continue }
        if (c == ")") {
            depth--
            closes++
            if (depth < 0) {
                printf "  >> 第 %d 行：这里出现了一个多余的右括号（配平已到 0）\n", NR
                printf "     %s\n", $0
                extra++
                depth = 0
            }
        }
    }
}
END {
    printf "  左括号 %d 个，右括号 %d 个，最大嵌套深度 %d\n", opens, closes, maxd
    if (opens == closes && extra == 0) {
        printf "  数量配平，且没有提前出现的右括号\n"
    } else {
        if (extra > 0) printf "  有 %d 处「配平已到 0 却仍见到 )」—— 这就是 unexpected \")\" 的来源\n", extra
        if (opens > closes) printf "  还有 %d 个左括号没闭合（可能在别的行，或跨行字符串里）\n", opens - closes
        if (closes > opens) printf "  右括号比左括号多 %d 个\n", closes - opens
    }
    if (insq) printf "  单引号没闭合！后面全部内容会被当成字符串，解析会整体漂移\n"
    if (indq) printf "  双引号没闭合！后面全部内容会被当成同一个词\n"
}
' "$F" 2>/dev/null)
printf '%s\n' "$BAL"
fi

QFLAG=0
case $BAL in
*"引号没闭合"*) QFLAG=1 ;;
esac

sec "7. 逐行扫描 bash / mksh 专属写法（unexpected \"(\" 请重点看这里）"
HITS=0

sc() {
    desc=$1
    shift
    [ $# -gt 0 ] || return 0
    r=$(grep -n "$@" "$F" 2>/dev/null | head -5)
    [ -n "$r" ] || return 0
    HITS=$((HITS + 1))
    printf '\n[%s]\n' "$desc"
    printf '%s\n' "$r" | sed 's/^/  /'
}

if [ "$FILEKIND" = binary ] || [ "$FILEKIND" = nulbin ]; then
    printf '  （跳过：不是文本，扫不出有意义的写法）\n'
else

sc '数组赋值 name=(...)'          -E '^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*=\('
sc 'for i in ( ... )'             -E '(^|[[:space:]])in[[:space:]]+\('
sc '进程替换 <( ) >( )'            -E '[<>]\('
sc '算术命令 (( ... ))'            -E '[^$]\(\(|^[[:space:]]*\(\('
sc '数组元素 ${name[...]}'         -E '\$\{[A-Za-z_][A-Za-z0-9_]*\['
sc '数组赋值 name[...]=...'        -E '[A-Za-z_][A-Za-z0-9_]*\[[A-Za-z0-9_@*+-]+\]\+?='
sc '双方括号 [[ ... ]]'            -E '\[\[[[:space:]]'
sc '方括号里用了 =='              -E '\[\[?.*[[:space:]]==[[:space:]]?'
sc 'ANSI-C 引号 美元加单引号'      -F "\$'"
sc '参数替换 ${v//a/b} ${v^^}'     -E '\$\{[A-Za-z_][A-Za-z0-9_]*[/^,]'
sc '子串 ${v:0:3}'                -E '\$\{[A-Za-z_][A-Za-z0-9_]*:[^=+?-]'
sc '间接引用 ${!v}'               -F '${!'
sc 'here-string <<<'              -F '<<<'
sc 'function 关键字'              -E '^[[:space:]]*function[[:space:]]'
sc 'declare / typeset / local -n' -E '(^|[[:space:]])(declare|typeset)[[:space:]]|local[[:space:]]+-n'
sc 'mapfile / readarray'          -E '(^|[[:space:]])(mapfile|readarray)([[:space:]]|$)'
sc 'let 算术'                     -E '(^|[[:space:]])let[[:space:]]'
sc 'select 循环'                  -E '(^|[[:space:]])select[[:space:]]'
sc 'case 扩展 ;;& 或 ;&'           -E ';;&|;&'
sc '字符串追加 +='                -E '[A-Za-z_][A-Za-z0-9_]*\+='
sc '重定向 &>'                    -F '&>'
sc '花括号展开 {a,b}'              -E '\{[A-Za-z0-9_-]+,[A-Za-z0-9_-]+\}'

if [ "$HITS" -eq 0 ]; then
    printf '  （没扫到已知的 bash 专属写法）\n'
fi
fi

sec "8. 换别的解释器对照（同样只做语法检查）"
FOUND=0
for h in bash mksh ash busybox; do
    p=$(command -v "$h" 2>/dev/null) || continue
    FOUND=1
    if [ "$h" = "busybox" ]; then
        r=$("$p" sh -n "$F" 2>&1)
    else
        r=$("$p" -n "$F" 2>&1)
    fi
    if [ -z "$r" ]; then
        printf '  %-8s 语法 OK   <-- 脚本是 %s 语法，用它跑就行了\n' "$h" "$h"
    else
        printf '  %-8s %s\n' "$h" "$(printf '%s\n' "$r" | head -1)"
    fi
done
[ "$FOUND" -eq 1 ] || printf '  （本机没有 bash / mksh / ash 可对照）\n'

sec "9. 结论"
# 分流只看括号字符本身，不认引号样式 ——
# dash/busybox ash 用双引号（"("）、mksh 用单引号（'('）、bash 用反引号加单引号（`('）。
# 只匹配单引号那一种会把 ash 的报错误判成「不是括号类」（踩过）。
KIND=none
case $OUT in
*')'*) KIND=close ;;
*'('*) KIND=open ;;
esac

case $FILEKIND in
binary)
    printf '这不是一个文本脚本（%s），所以：\n\n' "$BINKIND"
    printf '  `sh %s` 必然报错 —— sh 拿到的是二进制内容，只能胡乱解析，\n' "$(basename "$F")"
    printf '  报出来的 `unexpected ")"` 是二进制字节凑出来的 token，**并不是**你源码里\n'
    printf '  多了个右括号。第 6 节的括号配平对它没有意义，不要去改源码。\n\n'
    printf '  `./%s` 能跑，是因为走的是内核 execve：内核读到 ELF 头就直接装载它了。\n\n' "$(basename "$F")"
    printf '该怎么办：\n'
    printf '  1) 永远用**直接执行**（`./%s` 或带完整路径），不要 `sh 路径`；\n' "$(basename "$F")"
    printf '  2) 它必须有执行位（见第 1 节）；共享存储 /sdcard 给不了执行位，\n'
    printf '     所以这类文件要放在支持执行位的目录里；\n'
    printf '  3) 别指望「把内容喂给 sh」的通道 —— 内容不是脚本文本，喂进去只会重现这堆语法错。\n'
    if [ "$BINKIND" = "ELF 可执行文件" ]; then
        case $_verdict in
        ok)
            if [ "$_hasinterp" = yes ]; then
                printf '  4) 第 2 节已确认：**架构一致、解释器也是本机的** —— 用完整路径直接执行即可。\n'
            else
                printf '  4) 第 2 节已确认：**架构一致 + 静态链接** —— 用完整路径直接执行即可，\n'
                printf '     不需要任何 linker。\n'
            fi
            ;;
        badarch)
            printf '  4) 但第 2 节查出**架构不符** —— 这台机器装载不了它，\n'
            printf '     改权限、换目录都没用，只能按本机架构重新编译。\n'
            ;;
        badinterp)
            printf '  4) 但第 2 节查出**解释器不是本机的**（%s）——\n' "$ILDPATH"
            printf '     本机没有那条路径，必须针对 Android 重新链接。\n'
            ;;
        unknown)
            printf '  4) 第 2 节认不出它的目标架构，本机能不能装载说不准 —— 直接跑一次最快。\n'
            ;;
        esac
    fi
    ;;
nulbin)
    # 含 NUL、没命中魔数。**结论取决于文件自己有没有声明读者**（3.11.6）。
    # 3.11.1~3.11.5 的 App 在这里一律判成「编译产物 → 直接执行」，
    # 于是「本来 `sh 文件` 就能跑」的脚本在 /sdcard 上只剩一句「没有执行位」——
    # 用户的原话是「有些本来就可以直接 sh 执行的非纯文本脚本，sh 又执行不了了」。
    printf '这个文件含二进制内容（前 8KB 有 %s 个 NUL 字节），但**先别急着断定它不是脚本**：\n\n' "$NULS"
    if [ -n "$FOREIGN" ]; then
        printf '  首行 `#!%s`（%s）说明它是「自带解密器的壳」：\n' "$SHPATH" "$FOREIGN"
        printf '    `./%s` → 内核按 shebang 拉起它去读文件解密；\n' "$(basename "$F")"
        printf '    `sh %s` → #! 被当注释，sh 直接解析后面的密文，于是报错。\n\n' "$(basename "$F")"
        printf '该怎么办：用**直接执行**（或 `%s %s`），并保证它有执行位（见第 1 节）。\n' \
            "$SHPATH" "$(basename "$F")"
    elif [ -n "$SHNAME" ]; then
        printf '  首行 `#!%s` 就是写给 shell 看的 —— **它仍然是脚本**，只是另嵌了一段载荷。\n\n' "$SHPATH"
        printf '  `sh %s` 读得动它：三家 shell 实测都正常跑完、退出码 0，载荷不会被当成\n' "$(basename "$F")"
        printf '  命令执行（前提是载荷写在 `exit 0` 之后、或包在引号 heredoc / 引号串里）。\n'
        printf '  反过来，把它喂给 sh 得到一串 not found，只可能是因为载荷被**裸放**在了\n'
        printf '  会被执行到的位置 —— 那种情况下 `sh 文件` 本来就跑不了。\n\n'
        printf '  顺带一个实测事实：三家 shell 都会**悄悄丢掉载荷里的 NUL 字节**\n'
        printf '  （probe-nul-script.sh：355 B 的载荷落盘成 353 B），所以「能跑」是真的，\n'
        printf '  「载荷字节百分百保真」不是。要字节保真的话，用它自带的解密器/装载方式。\n\n'
        printf '别做的两件事：\n'
        printf '  ✗ 不要因为「有 NUL」就断定它是编译产物 —— 这恰恰是 App 3.11.1~3.11.5 的错误：\n'
        printf '    它把这类脚本强行改道「直接执行」，在 /sdcard 上只剩一句「没有执行位」，\n'
        printf '    本来能跑的脚本反而跑不了（3.11.6 已修）。\n'
        printf '  ✗ 别让它去「修复换行符 / 去 BOM」—— 那会连载荷里的 0x0D 一起删掉，是改坏文件。\n'
    else
        printf '  首行没有 #!（本脚本只看首行；App 还会把 `.sh` 这类扩展名也算作\n'
        printf '  「声明了读者」）—— 单看字节真的判不出来，三种可能都在：\n\n'
        printf '    A. 「脚本 + 二进制载荷」 → `sh %s` 能跑；\n' "$(basename "$F")"
        printf '    B. 编译 / 加密产物       → 只能直接执行（`./%s`，需要有执行位）；\n' "$(basename "$F")"
        printf '    C. 拿不准               → 两条都实测一次，别猜。\n\n'
        printf '  App 3.11.6 起对这类文件不再替用户拍板，而是执行时让 shell 现判：\n'
        printf '  有执行位就直接跑它本身（编译产物只能这样跑），没有才交给 `sh`。\n'
    fi
    ;;
foreign)
    printf '这是一个**自带解密器**的加密脚本：首行 `#!%s`。\n\n' "$SHPATH"
    printf '  `./%s` → 内核按 shebang 拉起 %s，由它去读文件解密；\n' "$(basename "$F")" "$FOREIGN"
    printf '  `sh %s` → #! 被当注释，sh 直接解析后面的密文，于是报错。\n\n' "$(basename "$F")"
    printf '该怎么办：\n'
    printf '  1) 用**直接执行**，或显式调用那个解密器：`%s %s`；\n' "$SHPATH" "$(basename "$F")"
    printf '  2) 它同样需要执行位，且那个解密器必须在当前环境里可达（第 2 节已给结论）；\n'
    printf '  3) 「把内容喂给 sh」的通道对它无效 —— 它要的是**路径**，不是内容。\n'
    ;;
cipher)
    printf '文件是文本，但尾部像 base64 密文块（%s 行超长 base64 行）。\n\n' "$B64LINE"
    printf '这类「明文头 + 密文」的脚本，`sh 文件` 能解析头部，之后把密文当命令名逐个执行，\n'
    printf '所以看到的更多是一串 `not found` 而不是语法错。\n\n'
    printf '该怎么办：用它的设计调用方式（通常是 ./文件 或它自己的解密器），别用 `sh 文件`。\n'
    ;;
*)
    case $KIND in
    close)
        printf '报错是 unexpected ")"，且文件是文本脚本 —— 与 bash 语法无关，是**真的多了一个右括号**。\n\n'
        printf '按这个顺序查：\n'
        printf '  1) 第 6 节会直接指出「配平已到 0 却仍见到 )」的行号，先看那里\n'
        printf '  2) 第 5 节里出错行**前面几行**：引号、heredoc 结束词是否成对\n'
        printf '  3) 常见手误：case 分支多写一个 ) / $(cmd)) 多一个 / if [ ... ] ) ; then\n'
        printf '     复制粘贴时留下的尾括号 / 函数块后面多一行 )\n\n'
        printf '交叉验证：如果你在终端里 `./%s` 能跑而 `sh %s` 报这一句，\n' "$(basename "$F")" "$(basename "$F")"
        printf '那它就不是文本脚本 —— 回头看第 2 节，那里已经给出形态。\n'
        ;;
    open)
        printf '报错是 unexpected "(" —— 两种可能，按第 7 节判断：\n\n'
        if [ "$HITS" -gt 0 ]; then
            printf '  第 7 节扫到 %s 类 bash 专属写法，先核对是不是它们所在的行。\n' "$HITS"
        else
            printf '  第 7 节没扫到 bash 专属写法，那更像是真的多了一个左括号。\n'
        fi
        printf '\n两条路：\n'
        printf '  A. 用认识它的解释器跑 —— bash 脚本.sh；或 chmod +x 后 ./脚本.sh（走 shebang）\n'
        printf '  B. 改写成 POSIX —— 每改一处跑一次 sh -n 验证\n'
        ;;
    *)
        if [ "$QFLAG" -eq 1 ]; then
            printf '第 6 节查出**引号没闭合** —— 这是最容易被误判的一种：\n'
            printf '一个没配对的引号会把后面所有内容吞成一个词，于是括号计数、命令边界全部错位，\n'
            printf '报出来的行号往往离真正的问题很远。先把它补上，再重跑一次本脚本。\n'
        elif [ "$RC" -eq 0 ]; then
            printf 'sh -n 没报错 —— 语法层面是干净的。\n\n'
            printf '那「sh 跑不了但 ./xxx 能跑」的差异就不在语法，而在别处，按顺序查：\n'
            printf '  1) 脚本里的相对路径：`sh 路径参数` 与 `./路径` 的**工作目录**是否相同\n'
            printf '  2) 脚本内 . source ./xxx 这类相对引用，依赖当前目录\n'
            printf '  3) shebang 带了参数（如 #!/bin/sh -e），`sh 文件` 不会带上这些开关\n'
            printf '  4) 环境变量：终端里 PATH / 沙箱 profile 注入的变量，`sh 文件` 未必有\n'
        else
            printf '报错不是括号类。把第 4、5 节的原文贴出来，按措辞可以反推是哪个 shell：\n'
            printf '  dash        -> Syntax error: "..." unexpected\n'
            printf '  busybox ash -> syntax error: unexpected "..."\n'
            printf '  mksh        -> syntax error: unexpected '"'"'...'"'"'\n'
            printf '  bash        -> syntax error near unexpected token `...'"'"'\n'
        fi
        ;;
    esac
    ;;
esac
