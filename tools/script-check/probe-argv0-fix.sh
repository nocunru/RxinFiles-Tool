#!/bin/sh
# 探测「stdin 喂脚本」时修复 $0 的几条路子，以及 PWD 的语义。
#
# 背景（probe-sh0.sh 的实测结论）：`sh -s -- <a> <b>` 里 <a> 变成 $1 而不是 $0，
# $0 恒为 shell 自己的名字。所以 $0 只能另想办法 —— 本脚本比较三种：
#   A. 直接把赋值语句写进 stdin 开头：  0='/p/x.sh'
#   B. 用 eval 包一层（失败可降级）：    eval '0='\''/p/x.sh'\''' || :
#   C. 用 -c 把 preamble 放命令行，脚本用 . /dev/stdin 读（$0 天然正确）
#
# 判据有两条，缺一不可：
#   1) 能改掉 $0（脚本里 dirname "$0" 这类写法全靠它）；
#   2) **改不掉时不能把整份脚本弄死** —— 直接写 `0=x` 若不被支持就是**语法错误**，
#      整份脚本一行都不执行，那比不修 $0 糟得多。所以 B 的降级能力是刚需。

BODY='printf "    0=[%s] 1=[%s] n=[%s]\n" "$0" "$1" "$#"'

# A：直接赋值（注意：这是唯一可能致命的写法）
try_direct() {
	sh_bin=$1
	printf '   A 直接赋值  : '
	{ printf "0='/p/x.sh'\n"; printf '%s\n' "$BODY"; } | "$sh_bin" -s -- a 2>&1 | tr '\n' ' '
	printf '\n'
}

# B：eval 包一层 + 失败降级
try_eval() {
	sh_bin=$1
	printf '   B eval 降级 : '
	{ printf "eval '0='\\''/p/x.sh'\\''' 2>/dev/null || :\n"; printf '%s\n' "$BODY"; } \
		| "$sh_bin" -s -- a 2>&1 | tr '\n' ' '
	printf '\n'
}

# C：-c 放 preamble，脚本从 stdin 读（$0 由 -c 的操作数天然给出）
try_dash_c() {
	sh_bin=$1
	printf '   C -c + stdin: '
	printf '%s\n' "$BODY" | "$sh_bin" -c '. /dev/stdin' /p/x.sh a 2>&1 | tr '\n' ' '
	printf '\n'
}

# 路径里带空格/引号/美元符时，B 方案是否还安全（防止注入）
try_weird() {
	sh_bin=$1
	printf '   B 怪路径    : '
	{ printf "eval '0='\\''/p/a b\\\$c'\''' 2>/dev/null || :\n"; printf '%s\n' "$BODY"; } \
		| "$sh_bin" -s -- 2>&1 | tr '\n' ' '
	printf '\n'
}

# PWD：环境变量给的值会不会被 shell 覆盖
try_pwd() {
	sh_bin=$1
	printf '   PWD 传 /opt : '
	printf 'printf "    PWD=[%%s] pwd=[%%s]\\n" "$PWD" "$(pwd)"\n' \
		| PWD=/opt "$sh_bin" -s 2>&1 | tr '\n' ' '
	printf '\n'
}

for s in mksh dash bash; do
	printf '=== %s ===\n' "$s"
	if ! command -v "$s" >/dev/null 2>&1; then
		printf '  （本机没有）\n\n'
		continue
	fi
	try_direct "$s"
	try_eval "$s"
	try_dash_c "$s"
	try_weird "$s"
	try_pwd "$s"
	printf '\n'
done

# busybox 的 sh（ash）—— Android 上它是我们的备选解释器
BB=/home/rouxin/bxbuild/host/busybox
if [ -x "$BB" ]; then
	printf '=== busybox ash（本工程内置的那份）===\n'
	for k in direct eval dash_c weird pwd; do
		case $k in
		direct) try_direct "$BB" ;;
		eval) try_eval "$BB" ;;
		dash_c) try_dash_c "$BB" ;;
		weird) try_weird "$BB" ;;
		pwd) try_pwd "$BB" ;;
		esac
	done
fi
