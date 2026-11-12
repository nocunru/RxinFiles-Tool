#!/bin/sh
# 「stdin 喂脚本执行器」的行为矩阵实测。
#
# 目标：脚本内容**只经 stdin 进入** sh 进程（不用 `sh <脚本路径>` 让 sh 去开用户的文件），
# 同时让脚本里的 $0 / $1..$n / PWD / read 尽可能正常。
#
# 四组候选：
#   B1  -c '. /dev/stdin'   <path> <args>   依赖 . 能读 /dev/stdin
#   B2  -c 'eval "$(cat)"'  <path> <args>   不依赖 /dev/stdin
#   B3  -s -- <args>  + stdin 首行 0='<path>'   裸写法
#   B4  -s -- <args>  + stdin 首行 eval 包一层  可降级写法
#   A   -s -- <args>                        只作参照（$0 修不了）
#
# 两个必须避开的坑（都踩过了）：
#   * 不要用 `eval "$interp" ...` 来展开「解释器前缀」—— eval 会二次解析，
#     把 `-c '. /dev/stdin'` 的引号吃掉、变成 `-c . /dev/stdin`，
#     于是 mksh 报 `. : missing argument`，看起来像方案本身不可用。
#     用 `set --` + `"$@"` 展开就不会。
#   * 本脚本自身必须是 POSIX 语法：`${x/a/b}` 只有 bash 支持，
#     在 dash/mksh 下报 Bad substitution 并把用例污染成空输入。

BODY_A='printf "      %-4s 0=[%s] 1=[%s] 2=[%s] n=[%s]\n" A "$0" "$1" "$2" "$#"'
BODY_B='printf "      %-4s 0=[%s] 1=[%s] 2=[%s] n=[%s]\n" B1 "$0" "$1" "$2" "$#"'
BODY_F='printf "      %-4s 0=[%s] 1=[%s] 2=[%s] n=[%s]\n" B0 "$0" "$1" "$2" "$#"'
BODY_C='printf "      %-4s 0=[%s] 1=[%s] 2=[%s] n=[%s]\n" B2 "$0" "$1" "$2" "$#"'
BODY_D='printf "      %-4s 0=[%s] 1=[%s] 2=[%s] n=[%s]\n" B3 "$0" "$1" "$2" "$#"'
BODY_E='printf "      %-4s 0=[%s] 1=[%s] 2=[%s] n=[%s]\n" B4 "$0" "$1" "$2" "$#"'

# 执行解释器：$1 是命令前缀（可能两个词，如 "busybox sh"），其余原样传下去。
# 用 set -- 把前缀拆成独立参数，再 "$@" 展开 —— 引号与分词都保持原样。
call() {
	interp=$1
	shift
	case $interp in
	*" "*)
		set -- "${interp%% *}" "${interp#* }" "$@"
		;;
	*)
		set -- "$interp" "$@"
		;;
	esac
	"$@"
}

run() {
	tag=$1
	interp=$2
	printf '  %s\n' "$tag"

	printf '    A  -s -- a b       : '
	printf '%s\n' "$BODY_A" | call "$interp" -s -- a b 2>&1 | tr '\n' ' '
	printf '\n'

	printf '    B0 -c . /proc/self/fd/0: '
	printf '%s\n' "$BODY_F" | call "$interp" -c '. /proc/self/fd/0' /p/x.sh a b 2>&1 | tr '\n' ' '
	printf '\n'

	printf '    B1 -c . /dev/stdin : '
	printf '%s\n' "$BODY_B" | call "$interp" -c '. /dev/stdin' /p/x.sh a b 2>&1 | tr '\n' ' '
	printf '\n'

	printf '    B2 -c eval $(cat)  : '
	printf '%s\n' "$BODY_C" | call "$interp" -c 'eval "$(cat)"' /p/x.sh a b 2>&1 | tr '\n' ' '
	printf '\n'

	printf '    B3 -s + 裸 0=x     : '
	{
		printf "0='/p/x.sh'\n"
		printf '%s\n' "$BODY_D"
	} | call "$interp" -s -- a 2>&1 | tr '\n' ' '
	printf '\n'

	printf '    B4 -s + eval 0=x   : '
	{
		printf "eval '0='\\''/p/x.sh'\\''' 2>/dev/null || :\n"
		printf '%s\n' "$BODY_E"
	} | call "$interp" -s -- a 2>&1 | tr '\n' ' '
	printf '\n'
}

# 脚本里的 read 在 B1 / B2 下读到什么 —— stdin 方案的固有代价
read_case() {
	tag=$1
	interp=$2
	printf '  read/%s\n' "$tag"
	printf '    B0 : '
	printf 'echo A\nread x\necho "got=[$x]"\necho B\n' \
		| call "$interp" -c '. /proc/self/fd/0' 2>&1 | tr '\n' ' '
	printf '\n    B1 : '
	printf 'echo A\nread x\necho "got=[$x]"\necho B\n' \
		| call "$interp" -c '. /dev/stdin' 2>&1 | tr '\n' ' '
	printf '\n    B2 : '
	printf 'echo A\nread x\necho "got=[$x]"\necho B\n' \
		| call "$interp" -c 'eval "$(cat)"' 2>&1 | tr '\n' ' '
	printf '\n    A  : '
	printf 'echo A\nread x\necho "got=[$x]"\necho B\n' \
		| call "$interp" -s 2>&1 | tr '\n' ' '
	printf '\n    对照（-c 传内容、stdin 留给脚本）: '
	printf 'hello-from-stdin\n' \
		| call "$interp" -c 'echo A; read x; echo "got=[$x]"; echo B' 2>&1 | tr '\n' ' '
	printf '\n'
}

# B2 会不会对脚本做二次解析（引号 / 变量会不会被吃掉）
quote_case() {
	tag=$1
	interp=$2
	printf '  quote/%s : ' "$tag"
	printf "printf 'Q1=[%%s]\\n' \"a'b\"\nx=v\nprintf 'Q2=[%%s]\\n' \"\${x}\"\n" \
		| call "$interp" -c 'eval "$(cat)"' 2>&1 | tr '\n' ' '
	printf '\n'
}

# PWD：环境变量给的值会不会被 shell 覆盖
pwd_case() {
	tag=$1
	interp=$2
	printf '  PWD/%s\n' "$tag"
	printf '    B1 : '
	printf 'printf "      PWD=[%%s] pwd=[%%s]\\n" "$PWD" "$(pwd)"\n' \
		| PWD=/opt call "$interp" -c '. /dev/stdin' 2>&1 | tr '\n' ' '
	printf '\n    B2 : '
	printf 'printf "      PWD=[%%s] pwd=[%%s]\\n" "$PWD" "$(pwd)"\n' \
		| PWD=/opt call "$interp" -c 'eval "$(cat)"' 2>&1 | tr '\n' ' '
	printf '\n'
}

for spec in \
	"mksh|$HOME/shells/usr/bin/mksh" \
	"dash|/bin/dash" \
	"busybox ash|$HOME/bxbuild/host/busybox sh" \
	"toybox sh|$HOME/shells/usr/bin/toybox sh"; do

	tag=${spec%%|*}
	interp=${spec#*|}
	first=${interp%% *}
	printf '=== %s  (%s) ===\n' "$tag" "$interp"
	if ! command -v "$first" >/dev/null 2>&1; then
		printf '  （本机没有）\n\n'
		continue
	fi
	run "$tag" "$interp"
	read_case "$tag" "$interp"
	quote_case "$tag" "$interp"
	pwd_case "$tag" "$interp"
	printf '\n'
done
