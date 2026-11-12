#!/bin/sh
# 探测各 shell 在「从 stdin 读脚本」时的 $0 / 位置参数语义。
#
# 这是 3.11.0 的「stdin 管道执行」方案的地基：脚本内容不进 sh 的文件参数，
# 但脚本里的 $0、dirname "$0"、$1..$n 仍然要工作。做法是 `sh -s -- <原路径> <args>`。
#
# 必须在**真实 mksh** 上确认 —— Android 的 /system/bin/sh 就是 mksh，
# 而 dash / ash / bash 在这一点的实现未必相同。

SCRIPT='printf "  0=[%s] 1=[%s] 2=[%s] n=[%s]\n" "$0" "$1" "$2" "$#"'

probe() {
	sh_bin=$1
	printf '=== %s ===\n' "$sh_bin"
	if ! command -v "$sh_bin" >/dev/null 2>&1; then
		printf '  （本机没有）\n\n'
		return
	fi
	printf '  %-28s' '-s （无参数）:'
	printf '%s\n' "$SCRIPT" | "$sh_bin" -s
	printf '  %-28s' '-s -- /p/x.sh:'
	printf '%s\n' "$SCRIPT" | "$sh_bin" -s -- /p/x.sh
	printf '  %-28s' '-s -- /p/x.sh a b:'
	printf '%s\n' "$SCRIPT" | "$sh_bin" -s -- /p/x.sh a b
	printf '  %-28s' '- （裸横线）:'
	printf '%s\n' "$SCRIPT" | "$sh_bin" -
	printf '  %-28s' '-s /p/x.sh（无 --）:'
	printf '%s\n' "$SCRIPT" | "$sh_bin" -s /p/x.sh
	printf '\n'
}

# 依次探测：真 mksh 最重要（Android 用它），其余作为对照
for s in mksh dash busybox ash bash; do
	probe "$s"
done
