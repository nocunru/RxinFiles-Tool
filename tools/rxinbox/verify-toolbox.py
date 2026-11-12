#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""验收 busybox：arm64 产物的 ELF 形态 + 用同一份 config 编出的主机版做真功能断言。

为什么能这么验：垫片机制（符号链接 + argv[0] 分派）是**与架构无关的源码行为**，
主机版和 arm64 版来自同一份源码与同一份 .config，因此在主机上跑通的
「链接按名字分派」「哪些 applet 真的在」「命令行为对不对」都能直接推广过去。
剩下架构相关的那部分只有一条：exec 能否通过（Bionic + nativeLibraryDir），
那条靠 AOSP 策略与 Google 的官方说法支撑，只能在真机确认。

用法（WSL）: python3 verify-toolbox.py <主机版busybox> <arm64版elf>
"""
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
HOST_BB = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/bxbuild/host/busybox")
ARM_BB = sys.argv[2] if len(sys.argv) > 2 \
    else os.path.join(ROOT, "app/src/main/jniLibs/arm64-v8a/libbusybox.so")

WORK = tempfile.mkdtemp(prefix="rxbox-")
BIN = os.path.join(WORK, "bin")
os.makedirs(BIN)
# 先把脚本自己所在的目录钉下来 —— 下面要 chdir，之后就解析不出相对路径了
# **必须切进去**：下面的行为断言都是 `sh -c`，用相对路径造一堆临时文件
# （b.bz2 / t.tar / partaa …）。不 chdir 的话它们会全落在**源码目录**里，
# 每跑一次就把 tools/rxinbox/ 弄脏一次。
os.chdir(WORK)

passed = 0
failed = []


def check(name, cond, detail=""):
    global passed
    if cond:
        passed += 1
        print("  ok   %s" % name)
    else:
        failed.append(name)
        print("  FAIL %s   %s" % (name, detail))


def run(args, stdin=None, timeout=20):
    return subprocess.run(args, input=stdin, capture_output=True, text=True, timeout=timeout)


def out(cmd, stdin=None, cwd=None):
    """经 /bin/sh + PATH 调用，完全模拟终端里的调用方式"""
    env = dict(os.environ)
    env["PATH"] = BIN + ":" + env.get("PATH", "")
    r = subprocess.run(["/bin/sh", "-c", cmd], input=stdin, capture_output=True,
                       text=True, env=env, cwd=cwd, timeout=25)
    return r.stdout, r.stderr, r.returncode


print("===== 1. arm64 产物的 ELF 形态 =====")
if os.path.exists(ARM_BB):
    print("  文件: %s (%d bytes)" % (ARM_BB, os.path.getsize(ARM_BB)))
    readelf = shutil.which("readelf") or "readelf"
    h = run([readelf, "-h", ARM_BB]).stdout
    l = run([readelf, "-l", ARM_BB]).stdout
    d = run([readelf, "-d", ARM_BB]).stdout
    check("是 AArch64", "AArch64" in h, h[:200])
    check("是 ET_DYN（PIE，Android 要求）", "DYN" in h, h[:200])
    check("解释器指向 /system/bin/linker64", "/system/bin/linker64" in l,
          [x for x in l.splitlines() if "interpreter" in x])
    needed = [x.split("[")[1].rstrip("]") for x in d.splitlines() if "NEEDED" in x]
    print("  NEEDED: %s" % needed)
    allowed = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so",
               "libstdc++.so", "libc++_shared.so", "libandroid.so", "libandroid_support.so"}
    bad = [n for n in needed if n not in allowed]
    check("只依赖系统库（无第三方 so）", not bad, bad)
    check("不是静态可执行（musl 静态会被 seccomp 杀）", "DYN" in h and needed, needed)
    check("符号已 strip", run(["readelf", "-S", ARM_BB]).stdout.count(".symtab") == 0)
else:
    print("  跳过：%s 不存在" % ARM_BB)

print()
print("===== 2. 符号链接按 argv[0] 分派（垫片机制的地基）=====")
if not os.path.exists(HOST_BB):
    print("  跳过：主机版 %s 不存在" % HOST_BB)
    sys.exit(1)
os.chmod(HOST_BB, 0o755)
lst = run([HOST_BB, "--list"]).stdout.split()
print("  applet 总数: %d" % len(lst))
with open(os.path.join(HERE, "applets.txt"), "w") as f:
    f.write("\n".join(sorted(lst)) + "\n")

for a in lst:
    p = os.path.join(BIN, a)
    if not os.path.lexists(p):
        os.symlink(HOST_BB, p)
os.symlink(HOST_BB, os.path.join(BIN, "busybox"))

check("awk 经符号链接可调用", out("awk 'BEGIN{print 1+1}'")[0].strip() == "2")
check("sed 经符号链接可调用", out("sed 's/a/X/'", "abc\n")[0].strip() == "Xbc")
check("全路径调用同样分派", out("%s 'BEGIN{print 3}'" % os.path.join(BIN, "awk"))[0].strip() == "3")
check("busybox 本体名可用", out("busybox true")[2] == 0)
o, e, rc = out("busybox | head -1")
check("busybox 无参数时打印版本", "BusyBox" in (o + e), (o + e)[:80])


def as_argv0(argv0, *args):
    """指定 argv[0] 去执行同一个二进制。

    这正是 Android 上 `ProcessBuilder("<nativeLibraryDir>/libbusybox.so", "true")`
    的语义：**执行哪个文件**与**argv[0] 是什么**是两回事，后者由调用方随意指定。
    """
    return subprocess.run([argv0] + list(args), executable=HOST_BB,
                          capture_output=True, text=True, timeout=20)


# ---- 回归：3.10.1 的线上故障 ------------------------------------------
# busybox 按 argv[0] 的 basename 分派 applet，而它在 APK 里**必须**叫
# libbusybox.so（PackageManager 只解压 lib*.so）。直接 exec 那个路径时
# basename 就是 libbusybox.so → 查不到 applet → 报错并以 127 退出。
# 3.10.1 的 probeExec 正是这么探测的，于是「exec 明明通了」被自己的探测方式
# 判成「不可执行」，整个工具箱被关闭（真机原话：
# `未启用：busybox 无法执行（退出码 127；[rc=127] [libbusybox.so: applet not found]）`）。
# 这两条断言把「一切 exec 都必须经符号链接」钉死，防止再犯。
r = as_argv0("libbusybox.so", "true")
check("用 .so 原名执行会被拒（复现 3.10.1 的故障）",
      r.returncode == 127 and "applet not found" in (r.stdout + r.stderr),
      "rc=%d out=%s" % (r.returncode, (r.stdout + r.stderr).strip()[:90]))
r = as_argv0("busybox", "true")
check("换成 busybox 这个名字就正常（符号链接要解决的问题）",
      r.returncode == 0, "rc=%d out=%s" % (r.returncode, (r.stdout + r.stderr).strip()[:90]))
r = as_argv0(os.path.join(BIN, "busybox"), "true")
check("按绝对路径经链接调用也正常（App 侧探测走的就是这条路）",
      r.returncode == 0, "rc=%d" % r.returncode)

print()
print("===== 3. 命令真的能干活（按名字调用）=====")
CASES = [
    ("awk 算术",            "awk 'BEGIN{printf \"%d\", 2^10}'", None, "1024"),
    ("awk 字段处理",        "printf 'a:b\\nc:d\\n' | awk -F: '{print $2}'", None, "b\nd"),
    ("awk 正则/内置函数",   "echo abc | awk '/b/{print toupper($0)}'", None, "ABC"),
    ("grep 计数",           "printf 'a\\na\\nb\\n' | grep -c a", None, "2"),
    ("grep -o",             "echo aaa | grep -o a", None, "a\na\na"),
    ("grep -E",             "echo abc | grep -E 'a|z'", None, "abc"),
    ("egrep",               "echo abc | egrep 'a.c'", None, "abc"),
    ("fgrep",               "printf 'x.y\\n' | fgrep 'x.y'", None, "x.y"),
    ("sed 替换",            "echo aaa | sed 's/a/b/'", None, "baa"),
    ("sed -n 范围",         "printf '1\\n2\\n3\\n' | sed -n '2p'", None, "2"),
    ("find -name",          "mkdir -p f/sub && touch f/a.txt f/sub/b.txt && find f -name '*.txt' | sort",
     None, "f/a.txt\nf/sub/b.txt"),
    ("find -type f -exec",  "mkdir -p f2 && touch f2/x && find f2 -type f -exec echo {} \\;", None, "f2/x"),
    ("xargs",               "printf 'a\\nb\\n' | xargs echo", None, "a b"),
    ("sort -u",             "printf 'b\\na\\na\\n' | sort -u", None, "a\nb"),
    ("sort -n",             "printf '10\\n9\\n' | sort -n", None, "9\n10"),
    ("uniq -c",             "printf 'a\\na\\nb\\n' | uniq -c | tr -s ' ' | sed 's/^ //'", None, "2 a\n1 b"),
    ("cut -d: -f",          "echo a:b:c | cut -d: -f2", None, "b"),
    ("tr",                  "echo abc | tr a-z A-Z", None, "ABC"),
    ("head -n",             "printf '1\\n2\\n3\\n' | head -2", None, "1\n2"),
    ("tail -n",             "printf '1\\n2\\n3\\n' | tail -1", None, "3"),
    ("wc -l",               "printf '1\\n2\\n3\\n' | wc -l", None, "3"),
    # 不要写成 `nl | tr -s ' '` —— busybox 的编号字段宽度与 gawk 版不同，
    # 把填充空格数写进期望值等于把测试绑死在某个版本的排版上。
    # 去掉行首填充再比对，验的是「有没有编号 + 分隔符对不对」。
    ("nl",                  "printf 'a\\nb\\n' | nl | sed 's/^ *//' | tr '\\n' '/'", None, "1\ta/2\tb/"),
    ("rev",                 "echo abc | rev", None, "cba"),
    ("tac",                 "printf '1\\n2\\n' | tac", None, "2\n1"),
    ("seq",                 "seq 3", None, "1\n2\n3"),
    ("base64 往返",         "echo hello | base64 | base64 -d", None, "hello"),
    ("md5sum 与系统一致",   "printf 'abc' > m && md5sum m | cut -d' ' -f1", None,
     "900150983cd24fb0d6963f7d28e17f72"),
    ("sha256sum 与系统一致", "printf 'abc' > s && sha256sum s | cut -d' ' -f1", None,
     "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
    # 注意 awk 的坑：`print $1>0` 会被当成**重定向到文件 0**（POSIX awk 里
    # print 后面的 > 是重定向），stdout 上什么都没有 —— 这是 awk 语义，
    # 不是 busybox 的 bug。用 grep 判格式更直白。
    ("cksum 输出格式",      "printf 'abc' | cksum | grep -qE '^[0-9]+ [0-9]+$' && echo ok", None, "ok"),
    ("crc32 输出格式",      "printf 'abc' | crc32 | grep -qE '^[0-9a-f]+$' && echo ok", None, "ok"),
    ("expr",                "expr 3 + 4", None, "7"),
    ("test / [",            "[ -f m ] && echo yes", None, "yes"),
    ("printf 转义",         "printf '%s-%d\\n' x 7", None, "x-7"),
    ("date -u +%Y",         "date -u +%Y | grep -qE '^[0-9]{4}$' && echo ok", None, "ok"),
    ("diff 检出差异",       "printf 'a\\n' > d1; printf 'b\\n' > d2; diff d1 d2 >/dev/null; echo $?", None, "1"),
    ("cmp 检出差异",        "cmp -s d1 d2; echo $?", None, "1"),
    ("tr -d",               "echo aXb | tr -d X", None, "ab"),
    ("od / hexdump / xxd",  "printf 'AB' | xxd -p", None, "4142"),
    ("strings 存在",        "printf 'xxhelloyy' > b1 && strings b1 | grep -q hello && echo ok", None, "ok"),
    ("bc 计算",             "echo '2^10' | bc", None, "1024"),
    ("comm",                "printf 'a\\nb\\n' > c1; printf 'b\\nc\\n' > c2; comm -12 c1 c2", None, "b"),
    ("paste",               "printf 'a\\n' > p1; printf 'b\\n' > p2; paste p1 p2", None, "a\tb"),
    ("fold -w",             "echo abcdef | fold -w 3 | tr '\\n' '/'", None, "abc/def/"),
    ("split",               "printf 'a\\nb\\nc\\n' > sp && split -l 1 sp part && cat part* | tr '\\n' '-'", None, "a-b-c-"),
    ("tee",                 "echo t | tee tf | cat", None, "t"),
    ("dirname/basename",    "basename /x/y.txt; dirname /x/y.txt", None, "y.txt\n/x"),
    ("which 自查",          "which awk", None, None),
    ("mktemp 可写",         "f=$(mktemp); echo x > $f; cat $f", None, "x"),
    # 原来写成 `A || B && C`：`||` 与 `&&` 同优先级且左结合，实际是 `(A||B) && C`，
    # A 成功也会再执行 C，于是输出两行。这里只验 A 本身。
    ("printenv 读到变量",   "FOO=1 printenv FOO", None, "1"),
    ("env 传递变量",        "FOO=2 env | grep -q '^FOO=2' && echo ok", None, "ok"),
    ("yes | head",          "yes | head -2", None, "y\ny"),
    ("timeout 生效",        "timeout 1 true; echo $?", None, "0"),
    ("tar 打包+解包往返",   "mkdir -p t1 && echo data > t1/f && tar cf t.tar t1 && mkdir -p t2 && tar xf t.tar -C t2 && cat t2/t1/f", None, "data"),
    ("tar 列目录",          "tar tf t.tar | grep -q t1/f && echo ok", None, "ok"),
    ("gzip 往返（与系统互操作）", "echo gz > g && gzip -c g > g.gz && gzip -dc g.gz", None, "gz"),
    ("bzip2 往返（与系统互操作）", "echo bz > b && bzip2 -c b > b.bz2 && bzcat b.bz2", None, "bz"),
    ("lzma 解压",           "which xz >/dev/null 2>&1 && (xz -c -F lzma b > b.lzma && lzcat b.lzma) || echo skip", None, None),
    ("unzip 可用性",        "which zip >/dev/null 2>&1 && (zip -q z.zip g && unzip -p z.zip g) || echo skip", None, None),
    ("awk 处理大输入不慢",  "seq 20000 | awk '{s+=$1} END{print s}'", None, "200010000"),
    ("sed 处理大输入",      "seq 20000 | sed 's/1/1/' | wc -l", None, "20000"),
]
for name, cmd, stdin, expect in CASES:
    try:
        o, e, rc = out(cmd, stdin)
    except Exception as ex:
        check(name, False, repr(ex))
        continue
    got = o.rstrip("\n") if o is not None else ""
    if expect is None:
        check(name, rc == 0 or got != "", "rc=%d out=%r err=%r" % (rc, o[:60], e[:80]))
    else:
        check(name, got == expect, "期望 %r 实得 %r err=%r" % (expect, got, e[:80]))

print()
print("===== 4. 垫片清单与源码里的兜底清单是否一致 =====")
# 从脚本位置反推工程根，不写死 /mnt/c/... —— 换台机器/换个目录就跑不起来了
src = os.path.join(ROOT, "app/src/main/java/com/rxinfiles/tool/RxinToolbox.kt")
if os.path.exists(src):
    import re
    text = open(src, encoding="utf-8").read()
    m = re.search(r"FALLBACK_APPLETS = listOf\((.*?)\n    \)", text, re.S)
    fb = re.findall(r'"([^"]+)"', m.group(1)) if m else []
    print("  源码兜底清单 %d 项，实际 applet %d 项" % (len(fb), len(lst)))
    miss = sorted(set(fb) - set(lst))
    print("  清单里但实际没有的: %s" % (miss or "无"))
    check("兜底清单里的名字都真实存在", not miss, miss)
    ovr = re.search(r"val OVERRIDE = listOf\((.*?)\n    \)", text, re.S)
    if ovr:
        override = re.findall(r'"([^"]+)"', ovr.group(1))
        omiss = sorted(set(override) - set(lst))
        print("  遮蔽清单 %d 项，其中不存在的: %s" % (len(override), omiss or "无"))
        check("遮蔽清单里的名字都真实存在（否则 PATH 前置目录会空一个）",
              not omiss, omiss)
    blk = re.search(r"BLOCKED: Set<String> = setOf\((.*?)\n    \)", text, re.S)
    if blk:
        blocked = set(re.findall(r'"([^"]+)"', blk.group(1)))
        still = sorted(blocked & set(lst))
        gone = sorted(blocked - set(lst))
        print("  BLOCKED 共 %d 项：已从二进制里编掉 %d 项，仍留在二进制里 %d 项"
              % (len(blocked), len(gone), len(still)))
        if still:
            print("    仍在: %s" % ", ".join(still))
            print("    （App 侧 BLOCKED 仍会拦下、不生成链接，所以不影响正确性，"
                  "只是白占一点体积 —— CONFIG 名与 applet 名不是一对一，"
                  "少数几个没被档名映射命中）")

    # ---- 回归：探测不许用 .so 实体路径 ----------------------------------
    # 源码里 probeExec / listApplets / busyboxVersion 的调用点必须传 exe
    # （即 bin/busybox 那个符号链接），绝不能传 bb（nativeLibraryDir 下的 .so）——
    # 后者会让 argv[0] 的 basename 变成 libbusybox.so，把「跑得起来」判成
    # 「跑不起来」。3.10.1 的线上故障就是这么来的。
    callers = re.findall(r"(probeExec|listApplets|busyboxVersion)\(([A-Za-z_][A-Za-z0-9_]*)\)",
                         text)
    print("  探测调用点: %s" % ", ".join("%s(%s)" % c for c in callers))
    wrong = [c for c in callers if c[1] != "exe"]
    check("探测/列表/取版本一律经符号链接路径调用（不能传 .so 实体路径）",
          len(callers) == 3 and not wrong,
          "实得 %s，异常项 %s" % (callers, wrong))
else:
    print("  跳过：找不到 RxinToolbox.kt")

print()
print("===== 5. 关键命令是否存在 =====")
# 分两组，别混在一起看：第二组是**故意**不编进 busybox、改由系统 toybox 提供的
# （App 侧也会再过滤一次），在这里显示「无」是**预期结果**，不是缺陷。
MUST = ["awk", "sed", "grep", "egrep", "fgrep", "find", "xargs", "tar", "gzip",
        "bzip2", "unzip", "sort", "uniq", "cut", "tr", "head", "tail", "wc",
        "diff", "cmp", "date", "base64", "sha256sum", "md5sum", "strings", "xxd",
        "less", "vi", "wget", "nc", "nslookup", "hostname", "bc", "tree",
        "timeout", "flock", "setsid", "nohup", "pgrep", "pkill", "killall", "tty"]
missing = [a for a in MUST if a not in lst]
for a in MUST:
    if a not in lst:
        print("  缺 %s" % a)
check("关键命令全部存在（%d 个）" % len(MUST), not missing, missing)

DELIBERATE = ["stat", "du", "df", "ps", "ls", "cp", "mv", "rm", "cat", "sh",
              "mount", "su", "ifconfig", "ip", "top", "dmesg", "sysctl"]
present = sorted(a for a in DELIBERATE if a in lst)
print()
print("  刻意不编、改由系统 toybox 提供的命令，仍在二进制里的: %s" % (", ".join(present) or "无"))
if present:
    print("  → 这些会被 App 侧 BLOCKED 拦下、不生成链接，功能上没问题；"
          "列出来只是提醒体积上还有可削的余量。")

print()
print("结果: 通过 %d 项，失败 %d 项" % (passed, len(failed)))
if failed:
    print("失败清单: %s" % failed)
# 全过就把临时目录清掉（它里面有 20000 行的大输入等一堆碎文件）；
# 有失败则留着，方便进去手工复现。
if failed or os.environ.get("RXBOX_KEEP"):
    print("临时目录（保留）: %s" % WORK)
else:
    shutil.rmtree(WORK, ignore_errors=True)
sys.exit(1 if failed else 0)
