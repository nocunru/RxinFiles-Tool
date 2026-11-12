#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ScriptPipe（脚本管道执行器）的回归断言。

分两层：

  A. **协议层**：用真 shell（mksh / dash / busybox ash）跑一遍
     「脚本内容经 stdin 喂进 `sh -c '<prelude>' <脚本路径> <参数…>`」这套协议，
     验证 `$0` / 位置参数 / 退出码 / 输出分离 / 大脚本不死锁 等行为确实成立。
     喂入写法（prelude）不是写死在这里的 —— **从 ScriptPipe.kt 源码里读**，
     这样改了 Kotlin 而没改断言时不会静默失真。

  B. **源码层**：正则抓 ScriptPipe.kt 里的不变量
     （BOM 三字节、删 `\\r`、`-c` 的位置、共用 RxinEnv、toybox 在候选表里…）。

关于「预处理」：`preprocess` 是个 10 行的纯函数，这里按同样语义复刻一份用于
行为验证，但**它的关键常量同样从 Kotlin 源码里断言**（BOM 三字节、删除的字节值），
所以复刻与实现不会悄悄分叉。真正只存在于 Kotlin 里的东西（UTF-16 分支、
data class 的 equals 覆写）只做源码断言。

用法（在 WSL 里跑，需要 mksh / dash / busybox）：
    python3 tools/script-check/verify-scriptpipe.py
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
APPSRC = os.path.join(ROOT, "app/src/main/java/com/rxinfiles/tool")
SRC = os.path.join(APPSRC, "ScriptPipe.kt")
TERMINAL_SRC = os.path.join(APPSRC, "TerminalActivity.kt")
ENV_SRC = os.path.join(APPSRC, "RxinEnv.kt")
FORMAT_SRC = os.path.join(APPSRC, "ScriptFormat.kt")
RUNNER_SRC = os.path.join(APPSRC, "ScriptRunner.kt")
MAIN_SRC = os.path.join(APPSRC, "MainActivity.kt")
DIAG_SH = os.path.join(HERE, "diagnose-sh.sh")

HOME = os.path.expanduser("~")
SHELLS = [
    ("mksh", [os.path.join(HOME, "shells/usr/bin/mksh")]),
    ("dash", ["/bin/dash"]),
    ("busybox ash", [os.path.join(HOME, "bxbuild/host/busybox"), "sh"]),
]
# 反例：验证「`-s` 修不了 $0」这条判断本身
NEG_SHELL = os.path.join(HOME, "shells/usr/bin/mksh")

# CRLF 测试用的脚本正好 4 行 = 4 个 \r（别再手数错）
CRLF_BODY = b'v=1\r\nif [ "$v" = 1 ]; then\r\necho CRLFOK\r\nfi\r\n'
CRLF_CR_COUNT = CRLF_BODY.count(b"\r")

passed = 0
failed = []
section = [""]


def sec(title):
    section[0] = title
    print()
    print("===== %s =====" % title)


def check(name, ok, detail=""):
    global passed
    if ok:
        passed += 1
        print("  [ok] %s" % name)
    else:
        failed.append("%s / %s : %s" % (section[0], name, detail))
        print("  [FAIL] %s" % name)
        if detail:
            print("         %s" % detail)
    return ok


# ------------------------------------------------------------------ 从源码取事实


def read(path):
    if not os.path.exists(path):
        print("找不到源码: %s" % path)
        sys.exit(2)
    return open(path, encoding="utf-8").read()


def strip_comments(src):
    """去掉注释再断言。

    「函数体里不许出现 `exit`」这类判据要判的是**真正拼进命令的字符串**，
    而不是注释里提了一句 `exit` —— 否则每次在注释里解释「为什么不能用 exit」
    都会把断言弄红（真踩过一次：3.13.1 加注释时当场红）。
    """
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


SRC_TEXT = read(SRC)


def kotlin_feeds():
    """抓 FEEDS 里的 prelude，例如 ". /proc/self/fd/0" / ". /dev/stdin"。"""
    m = re.search(r"private val FEEDS = listOf\((.*?)\n    \)", SRC_TEXT, re.S)
    if not m:
        return []
    return re.findall(r'Feed\(\s*"((?:[^"\\]|\\.)*)"', m.group(1))


def kotlin_string(name):
    m = re.search(r'private (?:const )?val %s\s*=\s*"((?:[^"\\]|\\.)*)"' % name, SRC_TEXT)
    return m.group(1) if m else None


def unescape_kotlin(s):
    return s.replace('\\"', '"').replace("\\$", "$").replace("\\n", "\n")


# ------------------------------------- 从 Java 源码取常量（结束标记那一半是 Java）

DONE_SRC = os.path.join(APPSRC, "ScriptDone.java")


def unescape_java(s):
    out = []
    i = 0
    while i < len(s):
        c = s[i]
        if c != "\\":
            out.append(c)
            i += 1
            continue
        n = s[i + 1] if i + 1 < len(s) else ""
        if n == "n":
            out.append("\n")
            i += 2
        elif n == "t":
            out.append("\t")
            i += 2
        elif n == '"':
            out.append('"')
            i += 2
        elif n == "\\":
            out.append("\\")
            i += 2
        elif n == "u":
            out.append(chr(int(s[i + 2:i + 6], 16)))
            i += 6
        else:
            raise ValueError("没见过的 Java 转义: \\%s" % n)
    return "".join(out)


def split_plus(expr):
    """按顶层 `+` 切开表达式（引号里的 `+` 不算）。切不干净就返回 None。"""
    parts, buf, in_str, i = [], [], False, 0
    while i < len(expr):
        c = expr[i]
        if in_str:
            buf.append(c)
            if c == "\\":
                if i + 1 < len(expr):
                    buf.append(expr[i + 1])
                i += 2
                continue
            if c == '"':
                in_str = False
        elif c == '"':
            in_str = True
            buf.append(c)
        elif c == "+":
            parts.append("".join(buf))
            buf = []
        else:
            buf.append(c)
        i += 1
    if in_str:
        return None
    parts.append("".join(buf))
    return parts


def java_const(text, name):
    """解出 `String NAME = <expr>;` 的值。

    `<expr>` 只允许两种段：`"字面量"` 与**另一个常量名**（递归解析）。
    够用即止 —— 正因为只认这两种，哪天 TAIL 换成更花哨的写法，这里会
    **取不到值而当场 FAIL**，而不是悄悄取错一段。
    """
    m = re.search(r"String\s+%s\s*=" % re.escape(name), text)
    if not m:
        return None
    start = m.end()
    i, in_str = start, False
    while i < len(text):
        c = text[i]
        if in_str:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                in_str = False
        elif c == '"':
            in_str = True
        elif c == ";":
            expr = text[start:i]
            break
        i += 1
    else:
        return None

    parts = split_plus(expr)
    if parts is None:
        return None
    out = []
    for p in parts:
        p = p.strip()
        if len(p) >= 2 and p.startswith('"') and p.endswith('"'):
            out.append(unescape_java(p[1:-1]))
        elif re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", p):
            v = java_const(text, p)
            if v is None:
                return None
            out.append(v)
        else:
            return None
    return "".join(out)


# ------------------------------------------------------------------ 协议层


def run_protocol(interp, prelude, body, args=(), cwd=None, env=None, timeout=60):
    argv = list(interp) + ["-c", prelude, "/rxin/x.sh"] + list(args)
    e = dict(os.environ)
    if env:
        e.update(env)
    p = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         stderr=subprocess.PIPE, cwd=cwd, env=e)
    try:
        out, err = p.communicate(body, timeout=timeout)
    except subprocess.TimeoutExpired:
        p.kill()
        out, err = p.communicate()
        return None, out, err
    return p.returncode, out, err


def preprocess(raw):
    """按 ScriptPipe.preprocess 的语义复刻（关键常量由下面的源码断言锁住）。"""
    if len(raw) >= 2 and ((raw[0] == 0xFF and raw[1] == 0xFE) or
                          (raw[0] == 0xFE and raw[1] == 0xFF)):
        enc = "utf-16-le" if raw[0] == 0xFF else "utf-16-be"
        text = raw[2:].decode(enc, errors="replace")
        cr = text.count("\r")
        return text.replace("\r", "").encode("utf-8"), True, cr, True
    start = 0
    bom = False
    if len(raw) >= 3 and raw[0] == 0xEF and raw[1] == 0xBB and raw[2] == 0xBF:
        start, bom = 3, True
    body = raw[start:]
    cr = body.count(b"\r")
    return body.replace(b"\r", b""), bom, cr, False


FEEDS = [unescape_kotlin(f) for f in kotlin_feeds()]

sec("0. 从 ScriptPipe.kt 读到的喂入写法")
print("  FEEDS = %s" % FEEDS)
check("源码里至少有两种喂入写法", len(FEEDS) >= 2, "拿到 %d 个" % len(FEEDS))
check("首选是 /proc/self/fd/0（内核保证存在）",
      any("/proc/self/fd/0" in f for f in FEEDS), str(FEEDS))
check("/dev/stdin 在候选里", any("/dev/stdin" in f for f in FEEDS), str(FEEDS))

available = [(name, argv) for name, argv in SHELLS if os.path.exists(argv[0])]
print("  可用的 shell: %s" % ", ".join(n for n, _ in available))
if not available:
    print("没有可用的 shell，无法跑协议层断言")
    sys.exit(2)

PRIMARY = [f for f in FEEDS if "/proc/self/fd/0" in f][0]

for name, argv in available:
    print()
    print("---- %s ----" % name)

    # $0 与位置参数
    rc, out, err = run_protocol(
        argv, PRIMARY,
        b'printf "%s|%s|%s|%s\\n" "$0" "$1" "$2" "$#"\n', args=("a", "b"))
    check("[%s] $0 = 传入的脚本路径" % name,
          out.strip() == b"/rxin/x.sh|a|b|2", "rc=%s out=%r err=%r" % (rc, out, err))

    # 退出码原样透出
    rc, out, err = run_protocol(argv, PRIMARY, b"exit 42\n")
    check("[%s] 退出码 42 原样透出" % name, rc == 42, "rc=%s" % rc)

    # stdout / stderr 可分离
    rc, out, err = run_protocol(argv, PRIMARY, b"echo OUT\necho ERR >&2\n")
    check("[%s] stdout 单独拿到" % name, b"OUT" in out and b"OUT" not in err, repr((out, err)))
    check("[%s] stderr 单独拿到" % name, b"ERR" in err and b"ERR" not in out, repr((out, err)))

    # 带特殊字符的参数不做二次解析
    weird = "a b'c$d\"e"
    rc, out, err = run_protocol(argv, PRIMARY, b'printf "[%s]\\n" "$1"\n', args=(weird,))
    check("[%s] 含空格/引号/美元符的参数原样到达" % name,
          out.strip() == ("[%s]" % weird).encode(), "out=%r" % out)

    # 大脚本经管道不死锁（远超 64KB 管道缓冲）
    big = b":\n" * (1024 * 1024) + b"echo BIG_DONE\n"
    t0 = time.time()
    rc, out, err = run_protocol(argv, PRIMARY, big, timeout=90)
    cost = time.time() - t0
    check("[%s] %d KB 脚本经管道跑完（无死锁）" % (name, len(big) // 1024),
          rc == 0 and b"BIG_DONE" in out, "rc=%s cost=%.1fs err=%r" % (rc, cost, err[:120]))

    # 脚本里的 read 拿到 EOF —— 这是纯 stdin 方案的固有代价，必须如实记录
    rc, out, err = run_protocol(argv, PRIMARY, b'echo A\nread x\necho "got=[$x]"\n')
    check("[%s] read 拿到 EOF（不是等输入）" % name,
          b"got=[]" in out, "out=%r" % out)

    # BOM：不剥离就必然出问题；剥离后正常
    bom_body = b"\xef\xbb\xbfecho BOMOK\n"
    rc_raw, out_raw, _ = run_protocol(argv, PRIMARY, bom_body)
    raw_bad = rc_raw != 0 or b"BOMOK" not in out_raw
    check("[%s] 带 BOM 原样喂进去 = 跑不起来" % name, raw_bad,
          "rc=%s out=%r" % (rc_raw, out_raw))
    fixed, bom, cr, re_enc = preprocess(bom_body)
    rc2, out2, _ = run_protocol(argv, PRIMARY, fixed)
    check("[%s] 去掉 BOM 后正常" % name, rc2 == 0 and b"BOMOK" in out2, "out=%r" % out2)

    # UTF-16（Windows 记事本的「Unicode」）：不先解码再删 \r，只会把 0D 00 拆成半个字符
    u16 = b"\xff\xfe" + "echo U16OK\r\n".encode("utf-16-le")
    rc_raw, out_raw, _ = run_protocol(argv, PRIMARY, u16)
    check("[%s] UTF-16 原样喂进去 = 跑不起来" % name,
          rc_raw != 0 or b"U16OK" not in out_raw, "rc=%s out=%r" % (rc_raw, out_raw))
    fixed, _, cr, re_enc = preprocess(u16)
    check("[%s] UTF-16 被识别并重编码" % name, re_enc and bom, "reencoded=%s bom=%s" % (re_enc, bom))
    check("[%s] UTF-16 里的 CR 也被删掉" % name, cr == 1, "cr=%d" % cr)
    rc2, out2, _ = run_protocol(argv, PRIMARY, fixed)
    check("[%s] 转成 UTF-8 后正常" % name, rc2 == 0 and b"U16OK" in out2, "out=%r" % out2)

    # CRLF：不剥离就必然出问题；剥离后正常
    crlf = CRLF_BODY
    rc_raw, out_raw, err_raw = run_protocol(argv, PRIMARY, crlf)
    raw_bad = rc_raw != 0 or b"CRLFOK" not in out_raw
    check("[%s] 带 CR 原样喂进去 = 跑不起来" % name, raw_bad,
          "rc=%s out=%r err=%r" % (rc_raw, out_raw, err_raw[:120]))
    fixed, _, cr, _ = preprocess(crlf)
    check("[%s] 预处理删掉了 CR" % name, cr == CRLF_CR_COUNT, "cr=%d" % cr)
    rc2, out2, _ = run_protocol(argv, PRIMARY, fixed)
    check("[%s] 删掉 CR 后正常" % name, rc2 == 0 and b"CRLFOK" in out2, "out=%r" % out2)

    # shebang 行当注释处理，不影响执行
    rc, out, err = run_protocol(argv, PRIMARY, b"#!/system/bin/sh\necho SHEBANG_OK\n")
    check("[%s] 首行 shebang 不影响执行" % name,
          rc == 0 and b"SHEBANG_OK" in out, "rc=%s err=%r" % (rc, err))

    # PWD：环境变量传值是**没用的**，只有真 chdir 才算数
    rc, out, err = run_protocol(
        argv, PRIMARY, b'printf "PWD=[%s] pwd=[%s]\\n" "$PWD" "$(pwd)"\n',
        env={"PWD": "/definitely/not/here"})
    joined = out.decode(errors="replace")
    check("[%s] 环境变量里的 PWD 被 shell 覆盖掉" % name,
          "/definitely/not/here" not in joined, joined)

    rc, out, err = run_protocol(
        argv, PRIMARY, b'printf "PWD=[%s] pwd=[%s]\\n" "$PWD" "$(pwd)"\n', cwd="/tmp")
    joined = out.decode(errors="replace")
    check("[%s] 真 chdir 之后 $PWD 才是对的" % name,
          "PWD=[/tmp]" in joined and "pwd=[/tmp]" in joined, joined)

    # 所有喂入写法都要能给出正确的 $0
    for feed in FEEDS:
        rc, out, _ = run_protocol(argv, feed, b'printf "[%s]\\n" "$0"\n')
        check("[%s] 写法 %r 下 $0 正确" % (name, feed),
              out.strip() == b"[/rxin/x.sh]", "rc=%s out=%r" % (rc, out))


# ------------------------------------------------------------------ 反例：-s 修不了 $0

sec("1. 反例：sh -s 修不了 $0（这正是选 -c 的理由）")
if os.path.exists(NEG_SHELL):
    p = subprocess.run([NEG_SHELL, "-s", "--", "/rxin/x.sh", "a"],
                       input=b'printf "%s|%s|%s\\n" "$0" "$1" "$2"\n',
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    parts = p.stdout.strip().split(b"|")
    check("sh -s 下 $0 是 shell 自己的名字，不是脚本路径",
          parts[0] != b"/rxin/x.sh" and b"mksh" in parts[0], "out=%r" % p.stdout)
    check("sh -s 把脚本路径塞进了 $1（所以它修不了 $0）",
          parts[1] == b"/rxin/x.sh", "out=%r" % p.stdout)
    print("    实测：$0=%r  $1=%r  $2=%r" % tuple(parts))

    # 偏方一：在 stdin 开头写一行 `0='/p/x.sh'`。
    # 实测它是被当**命令**执行的 —— 报 not found（进 stderr），$0 一点没变，
    # 后续行照常执行。（带 set -e 的脚本会因此中止，所以这条不能要。）
    p = subprocess.run([NEG_SHELL, "-s", "--", "a"],
                       input=b"0='/p/x.sh'\necho REACHED\nprintf '0=[%s]\\n' \"$0\"\n",
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    check("stdin 里写 0=… 改不掉 $0", b"0=[/p/x.sh]" not in p.stdout, "out=%r" % p.stdout)
    check("stdin 里写 0=… 会多出一条 not found 报错",
          b"not found" in p.stderr or b"inaccessible" in p.stderr, "err=%r" % p.stderr)

    # 偏方二：`-s` + eval 包一层。不报错，但 $0 同样改不掉（静默降级 = 白写）
    p = subprocess.run([NEG_SHELL, "-s", "--"],
                       input=b"eval '0='\\''/p/x.sh'\\''' 2>/dev/null || :\n"
                             b"printf '0=[%s]\\n' \"$0\"\n",
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    check("eval 包一层也改不掉 $0（静默降级）",
          b"0=[/p/x.sh]" not in p.stdout, "out=%r err=%r" % (p.stdout, p.stderr))
else:
    print("  跳过：%s 不存在" % NEG_SHELL)


# ------------------------------------------------------------------ 源码层断言

sec("2. ScriptPipe.kt 源码不变量")

check("preprocess 判的是 UTF-8 BOM 三字节 EF BB BF",
      "0xEF" in SRC_TEXT and "0xBB" in SRC_TEXT and "0xBF" in SRC_TEXT)
check("preprocess 删的是 0x0D（'\\r'）",
      "'\\r'.code.toByte()" in SRC_TEXT or "0x0D" in SRC_TEXT)
check("有 UTF-16 BOM 分支（否则双字节文件会被删成乱码）",
      "UTF_16LE" in SRC_TEXT and "UTF_16BE" in SRC_TEXT and "reencoded" in SRC_TEXT)

check("用 -c 而不是 -s（-s 的操作数是 $1）",
      '"-c"' in SRC_TEXT and '"-s"' not in SRC_TEXT)

# 命令行形状：-c <prelude> <scriptPath> <args>
m = re.search(r'inner \+= listOf\("-c", ch\.feed\.prelude, scriptPath\)', SRC_TEXT)
check("argv 形状是 -c <prelude> <脚本路径>（脚本路径成为 $0）", bool(m),
      "没找到 `inner += listOf(\"-c\", ch.feed.prelude, scriptPath)`")

check("PWD/环境走共用的 RxinEnv.build",
      "RxinEnv.build(context, workDir)" in SRC_TEXT,
      "ScriptPipe 里没有 RxinEnv.build(context, workDir)")

check("工作目录用真 chdir（ProcessBuilder.directory）",
      ".directory(File(workDir))" in SRC_TEXT)
check("stderr 没有被并进 stdout（两条流要分开收）",
      "redirectErrorStream(false)" in SRC_TEXT)

check("stdin 写完即关闭（不关则脚本跑完进程不退出）",
      "process.outputStream.use" in SRC_TEXT)

check("探测走的是与真实执行相同的 argv 形状",
      'candidate.argv + listOf("-c", feed.prelude, PROBE_S0)' in SRC_TEXT)
check("探测同时校验 $0 与退出码",
      "PROBE_S0" in SRC_TEXT and "exitValue() == 42" in SRC_TEXT)

check("备选解释器里有内置 busybox 的 sh",
      'listOf(it, "sh"), "内置 busybox sh"' in SRC_TEXT)
check("toybox 在候选表里（上游没有 sh，会优雅跳过）",
      '"toybox sh"' in SRC_TEXT)
check("解释器探测结果有缓存",
      "channel?.let { return it }" in SRC_TEXT)

check("root 身份走 su -c，且整串做了单引号转义",
      "RxinEnv.SU_PATH" in SRC_TEXT and "ScriptRunner.quote(it)" in SRC_TEXT)
check("日志总量有上限（防止疯狂打日志把内存吃光）",
      "MAX_CAPTURE_CHARS" in SRC_TEXT and "truncated" in SRC_TEXT)
check("root 读取按进程退出码区分成功与失败",
      "process.exitValue() == 0" in SRC_TEXT)
check("root 成功读取的 0 字节脚本不再被当成读取失败",
      "bytes.takeIf { it.isNotEmpty() }" not in SRC_TEXT)
check("root 读取输出与进程超时分开处理",
      "output.get(1, TimeUnit.SECONDS)" in SRC_TEXT)

sec("3. 环境构造只此一份（防两处漂移）")
term = read(TERMINAL_SRC)
check("TerminalActivity.buildEnv 已委托给 RxinEnv",
      "private fun buildEnv(cwd: String): Array<String> = RxinEnv.build(this, cwd)" in term)
check("TerminalActivity 里不再自己拼 SYSTEM PATH",
      "/apex/com.android.runtime/bin" not in term)
envs = read(ENV_SRC)
check("RxinEnv 提供 SHELL_PATH 与 SU_PATH",
      'const val SHELL_PATH = "/system/bin/sh"' in envs and 'const val SU_PATH = "su"' in envs)

# ---------------------------------------------------------------------------
# 4. 加密 / 编译过的脚本：形态判定 + 直接执行
#
# 这一组断言的存在理由：3.11.1 之前，终端那条通道**一律** `sh '<路径>'`，
# 对「加密/编译过的脚本」必然失败（见 升级说明.md 3.11.1）。这条规则很容易被
# 后来的改动悄悄改回去，所以从源码里把事实读出来钉住。
# ---------------------------------------------------------------------------
sec("4. 脚本形态判定 / 直接执行（3.11.1）")

fmt = read(FORMAT_SRC)
runner = read(RUNNER_SRC)
diag = read(DIAG_SH)

check("ScriptFormat 存在且以字节判定形态",
      "object ScriptFormat" in fmt and "fun binaryKind(" in fmt and
      "fun magicKind(" in fmt and "fun looksExecutableImage(" in fmt)
check("判定是 SNIFF_BYTES 级别的头部嗅探",
      "const val SNIFF_BYTES" in fmt and "binaryKind(head: ByteArray)" in fmt)
check("UTF-16 的文件不算二进制（否则中文脚本会被误判）",
      "isUtf16Bom" in fmt and "0xFF.toByte()" in fmt and "0xFE.toByte()" in fmt)
check("魔数与 NUL 是两条判据（力度完全不同，见下面的 3.11.6 组）",
      "fun magicKind(head: ByteArray): String?" in fmt and
      "fun hasNul(head: ByteArray): Boolean" in fmt and
      "const val NUL_DESC" in fmt)
check("binaryKind（改字节的闸门）仍把 NUL 算进来",
      "return if (hasNul(head)) NUL_DESC else null" in fmt)

# 魔数清单必须与设备端诊断脚本**逐条一致** —— 两处漂移就没法互相对照了
m = re.search(r"private val MAGICS: List<Pair<String, String>> = listOf\((.*?)\n    \)", fmt, re.S)
kt_magics = set(re.findall(r'"([0-9a-f]{4,})"\s+to\s+"', m.group(1))) if m else set()
sh_case = re.findall(r"^\s*([0-9a-f]+(?:\*)?(?:\|[0-9a-f]+\*)*)\)\s+BINKIND=", diag, re.M)
sh_magics = set()
for line in sh_case:
    for alt in line.split("|"):
        sh_magics.add(alt.rstrip("*"))
check("ScriptFormat.MAGICS 抓出来了", len(kt_magics) >= 15, "%d 条" % len(kt_magics))
check("diagnose-sh.sh 的魔数抓出来了", len(sh_magics) >= 15, "%d 条" % len(sh_magics))
check("两处魔数清单完全一致（改一处必须改另一处）",
      kt_magics == sh_magics,
      "Kotlin 独有 %s / shell 独有 %s" % (sorted(kt_magics - sh_magics), sorted(sh_magics - kt_magics)))
check("ELF 魔数在两处都在（这是最常见的一种「脚本加密」产物）",
      "7f454c46" in kt_magics and "7f454c46" in sh_magics)

check("ScriptRunner 用 ScriptFormat 的字节判据",
      "ScriptFormat.magicKind(head)" in runner and "ScriptFormat.hasNul(head)" in runner)
check("魔数命中才走直接执行（含 NUL 不再一律判死）",
      "directExec = true" in runner and "pf.magicKind?.let" in runner)
check("首行 #! 指向非解释器时不回退 sh（回退等于把密文喂给 shell）",
      "KNOWN_INTERPRETERS" in runner and
      "requested.lowercase() in KNOWN_INTERPRETERS ->" in runner and
      "Prepared(pf, \"sh\", true, requested" in runner and
      "首行 #! 指向 $requested，它不是 shell 解释器" in runner)
check("buildCommand 里有直接执行分支（且文本脚本那条路没被改动）",
      "if (prepared.directExec)" in runner and
      'sb.append(runnerCommand(prepared)).append(\' \').append(quote(prepared.scriptPath))' in runner)
check("直接执行前用 [ -x ] 判断执行位（内核装载必须有执行位）",
      'sb.append("if [ -x ")' in runner)
check("没有执行位时给出可读原因，而不是 Permission denied",
      "没有执行位，无法执行" in runner and "/sdcard）不支持执行位" in runner)
check("说明文案三条分支：读不到 > 直接执行 > 解释器回退",
      "fun notice(prepared: Prepared): String? = when {" in runner and
      "prepared.readNotice != null -> readNoticeOf(prepared)" in runner and
      "prepared.directExec -> directExecNotice(prepared)" in runner and
      "else -> fallbackNotice(prepared)" in runner)
check("MainActivity 用的是 notice() 而不是只看 fallbackNotice",
      "ScriptRunner.notice(prepared)" in read(MAIN_SRC))

# --- 3.11.6：「含 NUL」与「魔数命中」力度完全不同 ---------------------------
# 用户反馈：「有些本来就可以直接 sh 执行的非纯文本脚本，sh 又执行不了了」。
# 3.11.1 把「含 NUL」和「魔数」当成同一件事（都判成「sh 读不了」→ 直接执行），
# 于是「#!/bin/sh + 一段二进制载荷」的脚本（自解压脚本、把数据塞进引号 heredoc）
# 在共享存储上只剩一句「没有执行位，无法执行」。
# 实测三家 shell 都能跑完它们（probe-nul-script.sh：rc=0、载荷不会被当命令）。
# 这一组锁住「判据分档」这件事，谁再合并回去都应当场失败。

flat = re.sub(r"\s+", " ", runner)
check("Preflight 把三种事实拆成三个字段（不再一个 binaryKind 打天下）",
      "val magicKind: String? = null" in runner and
      "val ambiguousPayload: Boolean = false" in runner and
      "val binaryContent: Boolean = false" in runner)
check("含 NUL 的文件照样解析 shebang（只有魔数命中才跳过）",
      "val nul = magic == null && ScriptFormat.hasNul(head)" in runner and
      "val parsed = if (magic == null) parseShebang(firstLine(head, hasBom)) else null" in runner)
check("「读者声明」= 首行 #! 或已知脚本扩展名",
      "internal fun interpreterByExtension(path: String): String?" in runner and
      "val declared = parsed != null || interpreterByExtension(path) != null" in runner and
      "ambiguousPayload = nul && !declared" in runner)
check("含 NUL 但有声明 → 照旧交给 sh（本次修正的核心）",
      "pf.shebangInterpreter ?: interpreterByExtension(path)" in flat)
check("含 NUL 又没声明 → 交 shell 现判，而不是判成编译产物",
      "if (pf.ambiguousPayload) {" in runner and
      "readNotice = readNotice ?: payloadNotice(path)" in runner)
check("提醒文案承认「判不出」，不替用户下结论",
      "判不出" in runner and "有执行位就直接跑它本身" in runner)
check("shebang 那一行遇 NUL 即止（否则解释器名尾随 NUL 就认不出来）",
      r"c == '\u0000') break" in runner)

# ---------------------------------------------------------------------------
sec("5. 脚本结束态：结束标记 + 任何输入退出（3.13.1 改、3.13.2 修出口）")

# 这条规矩改过三次，每次都是真机反馈驱动的：
#
#   3.9.2   脚本结束 = 终端结束（命令尾 `exit` + 3 秒后自动关界面）
#   3.11.2  撤销 ——「输出还没复制走，终端自己就关了」：去掉 `exit` 与定时器，
#           脚本结束后停在交互提示符上，想关自己按返回键
#   3.13.1  用户反馈：「终端提示脚本结束，终端也不提供再次使用，输入任何内容都会
#           自动退出终端页」→ 改成**结束态**：输出留在屏幕上、脚本会话被收掉、
#           任何输入（键盘 / 输入法 / 屏幕功能键 / 粘贴）退回文件管理，
#           而手指点、上下滑动、长按选中复制仍然可用（先看完 / 抄走再走）
#   3.13.2  用户反馈：「键盘都被关了，你让我怎么测试任意键退出」——
#           3.13.1 把输入入口本身当成了「终端可用性」一起收掉：关软键盘、
#           屏幕按键行 setGone、点击 return，结果「按任意键返回」无键可按。
#           改成：**键盘与按键行都留着**，它们是这一屏的出口。
#
# **3.11.2 的两条底线没有变**，谁改回去都应当场失败：
#   ① 命令尾不许 `exit` 外层 shell（PTY 早关会截掉 reader 还没送出去的最后几 KB 输出）
#   ② 不许出现任何「定时器自动关界面」——关的时机只由用户按键决定
# **3.13.2 补的第三条底线**：
#   ③ 「脚本跑完挡住输入」只许由**会话层**保证，不许靠藏输入入口 ——
#      藏掉之后用户就出不去了（finishIfRunning 之后写入本来就会被丢弃）。

term = read(TERMINAL_SRC)
done = read(DONE_SRC)
layout = read(os.path.join(ROOT, "app/src/main/res/layout/activity_terminal.xml"))
strings = read(os.path.join(ROOT, "app/src/main/res/values/strings.xml"))

bc = re.search(r"fun buildCommand\(.*?\n    \}\n", runner, re.S)
bc_body = bc.group(0) if bc else ""
check("buildCommand 的函数体抓出来了", len(bc_body) > 200, "%d 字符" % len(bc_body))
check("buildCommand 尾部仍然没有 exit 外层 shell（PTY 早关会丢最后几 KB 输出）",
      "exit" not in strip_comments(bc_body))
check("退出码仍照旧回显（__rxin_rc + 「脚本已结束，退出码」）",
      "; __rxin_rc=\\$?; printf" in bc_body and "脚本已结束，退出码 %s" in bc_body)
check("结束标记是命令尾**最后**追加的一条（排在 126/127 的解释之后）",
      "sb.append(ScriptDone.TAIL)" in bc_body and
      bc_body.find("case \\$__rxin_rc in") < bc_body.find("sb.append(ScriptDone.TAIL)") <
      bc_body.find("return sb.toString()"))
check("标记取自 ScriptDone（不在这里手写格式串）",
      "ScriptDone.TAIL" in bc_body and "RXIN-DONE" not in runner)
check("TerminalActivity 里已无自动关闭机制（关的时机只由用户按键决定）",
      "scheduleScriptExit" not in term and "cancelScriptExit" not in term and
      "SCRIPT_EXIT_DELAY_MS" not in term and "exitRunnable" not in term)
check("scriptMode 仍在（会话建立前要靠它显示身份标签）",
      "var scriptMode = false" in term and "scriptMode -> scriptAsRoot" in term)

# 只有「脚本会话」进结束态：终端里手工敲出来的会话、以及用户中途另开的标签都不该被牵连
check("记下了哪个会话是脚本会话",
      "var scriptSession: TerminalSession? = null" in term and
      "scriptSession = session" in term)
check("结束态按**会话**登记（不是全局布尔）",
      "val finishedScripts = mutableSetOf<TerminalSession>()" in term and
      "private fun currentFinished(): Boolean = currentSession()?.let { it in finishedScripts } ?: false"
      in term)
check("配置变化保留并重新附着原终端会话",
      "onRetainCustomNonConfigurationInstance" in term and
      "lastCustomNonConfigurationInstance" in term and
      "session.updateTerminalSessionClient(this)" in term)
check("只有真正退出才终止会话，旋转不会重新执行脚本",
      "if (!isChangingConfigurations)" in term and
      "if (sessions.isNotEmpty()) {\n            restoreSessions()\n            return\n        }" in term)

# 信号接收：借 OSC 2 写终端标题，屏幕上不留字符，终端模拟器只回调一次
i_title = term.find("override fun onTitleChanged")
title_body = term[i_title:i_title + 1200] if i_title >= 0 else ""
check("onTitleChanged 里认结束标记（整条匹配，不是 contains）",
      "ScriptDone.isMarker(title)" in title_body and
      "markScriptFinished(changedSession, ScriptDone.parseExitCode(title))" in title_body)
check("标记不会被当成标签名",
      "标记是写给 App 看的，不该变成标签名" in title_body and
      title_body.find("markScriptFinished") < title_body.find("tabLayout.getTabAt(index)?.setText"))
check("已收尾的会话不再改标签名（标题里可能还留着那条标记）",
      "if (changedSession in finishedScripts) return" in title_body)

# 收拾干净：真的把会话收掉 + 不让 Termux 补一行英文的「Process completed」
i_mark = term.find("private fun markScriptFinished")
mark_body = term[i_mark:i_mark + 3200] if i_mark >= 0 else ""
check("只对脚本会话生效、只收一次",
      "if (session !== scriptSession) return" in mark_body and
      "if (!finishedScripts.add(session)) return" in mark_body)
check("关掉会话（PTY 随之关闭，输入法那条绕过回调的路也一起堵上）",
      "session.finishIfRunning()" in mark_body)
check("不让 Termux 再补一行英文退出说明",
      "session.setAppendExitNotice(false)" in mark_body and
      "mAppendExitNotice" in read(os.path.join(
          ROOT, "app/src/main/java/com/termux/terminal/TerminalSession.java")))
check("光标收起来（喂 CSI ? 25 l；只是「不闪」的话光标还杵在那儿像在等输入）",
      r'"\u001b[?25l"' in mark_body and "emulator.append(" in mark_body and
      "setCursorBlinkingEnabled(false)" not in strip_comments(mark_body))

# 兜底路径：脚本把 shell 一起带走时（exec / 被 OOM 杀 / 中途 exit）也要停在结束态
i_fin = term.find("override fun onSessionFinished")
fin_body = term[i_fin:i_fin + 1400] if i_fin >= 0 else ""
check("onSessionFinished 对脚本会话走结束态，而不是关标签",
      "if (finishedSession === scriptSession)" in fin_body and
      "markScriptFinished(finishedSession, ScriptDone.NO_CODE)" in fin_body and
      fin_body.find("markScriptFinished(finishedSession") < fin_body.find('toast("会话已退出")'))

# 输入拦截：三条终端输入回调 + 粘贴；屏幕功能键与「+」各另有一道闸门。
# 有会话参数的用 `session in finishedScripts`（多标签时按会话判），
# 没有会话参数的只能按当前标签判 —— 这条区别本身也写进断言，
# 免得以后有人图省事把 onKeyDown 的 session 判断改成 currentFinished()。
for hook, name, has_session in (
        ("override fun onKeyDown", "硬键盘 / 输入法回车", True),
        ("override fun onKeyUp", "按键抬起", False),
        ("override fun onCodePoint", "字符输入", True),
        ("override fun onPasteTextFromClipboard", "粘贴", True)):
    i = term.find(hook)
    body = term[i:i + 900] if i >= 0 else ""
    gate = "session in finishedScripts" if has_session else "currentFinished()"
    check("[%s] 结束态下按「输入」处理：消费事件并退出界面" % name,
          gate in body and "exitBecauseScriptDone()" in body and "return" in body)

i_tap = term.find("override fun onSingleTapUp")
tap_body = term[i_tap:i_tap + 700] if i_tap >= 0 else ""
check("手指点**不**退出（先看完 / 抄走输出再走）",
      "exitBecauseScriptDone" not in tap_body)
check("结束态下点一下**还是**弹键盘（那是出口：没键盘就没法「敲任意键」）",
      "if (currentFinished()) return" not in tap_body and
      "showSoftKeyboard()" in tap_body)
check("只关一次（按下与抬起各来一次回调）",
      "private var closingForScriptDone = false" in term and
      "if (closingForScriptDone) return" in term)
check("顶栏「+」在结束态下有闸门（另开标签 = 再次使用终端）",
      "if (currentFinished()) return@setOnClickListener" in term)
check("退出时给一句人话（否则像崩了/误触）",
      'toast("脚本已结束，返回文件管理")' in term)

# ---------------------------------------------------------------- 3.13.2 的修正
#
# 3.13.1 初版把三条输入路径一起堵了：markScriptFinished 里关软键盘、
# applyFinishedUi 里把屏幕按键行 setGone、onSingleTapUp 在结束态直接 return。
# 而这一屏的规则偏偏是「按任意键返回文件管理」—— 真机上一装上用户就问：
# 「键盘都被关了，你让我怎么测试任意键退出」。
#
# 根子上的误会：以为「终端不再可用」要靠「藏掉输入入口」来实现。
# 其实它已经由**会话层**独立保证了（finishIfRunning 之后 TerminalSession.write
# 在 mShellPid <= 0 时丢弃一切写入），键盘开不开跟它毫无关系。
# 这几条断言把 3.13.2 的结论钉死：键盘留着、按键行留着、点了要弹。
check("TerminalActivity 里不再有「收起软键盘」这个动作",
      "hideSoftKeyboard" not in strip_comments(term))

# 这条是 3.13.2 整个修正的**承重点**：输入法的「字符」确实会经过 onCodePoint，
# 所以「留着键盘」不会退化成「敲了半天没反应」。读的是 TerminalView.java 本体，
# 不是复制一份实现 —— 上游哪天真把这条路径改了，这里要红。
tview = read(os.path.join(ROOT, "app/src/main/java/com/termux/view/TerminalView.java"))
check("输入法的字符经 sendTextToTerminal → inputCodePoint → onCodePoint 落到活动回调上",
      "if (mClient.onCodePoint(codePoint, controlDown, mTermSession)) return;" in tview and
      "sendTextToTerminal(getEditable());" in tview and
      "inputCodePoint(codePoint, ctrlHeld, false);" in tview)
check("输入法的退格走 sendKeyEvent（因此落到 onKeyDown）",
      "for (int i = 0; i < leftLength; i++) sendKeyEvent(deleteKey);" in tview)

# 屏幕按键行必须留着 —— 它是手机上最顺手的那个「任意键」。
# 判据取那一行**本身**：恰好是无条件的 `= View.VISIBLE`（连条件都不许有）。
i_ui = term.find("private fun applyFinishedUi")
ui_body = term[i_ui:i_ui + 1400] if i_ui >= 0 else ""
i_kb = ui_body.find("R.id.keyBar")
kb_line = ""
if i_kb >= 0:
    # 从 `R.id.keyBar` 往左退到行首再取整行（否则截出来的是半行，判据永远差一截）
    kb_line = ui_body[ui_body.rfind("\n", 0, i_kb) + 1:ui_body.find("\n", i_kb)].strip()
check("屏幕按键行在结束态下仍然可见（那一行就是无条件的 VISIBLE）",
      kb_line == "findViewById<View>(R.id.keyBar).visibility = View.VISIBLE",
      "实际: %r" % kb_line)
check("（对照）顶栏「+」才是那个要按结束态收起的",
      "findViewById<View>(R.id.newTabBtn).visibility" in ui_body and
      "if (finished) View.GONE else View.VISIBLE" in ui_body)

# 屏幕功能键（ESC / Tab / 方向键…）在结束态下按下去就退出
i_bk = term.find("private fun bindKey(id: Int")
bk_body = term[i_bk:i_bk + 500] if i_bk >= 0 else ""
check("屏幕功能键结束态下「按下即退出」，不是「点了没反应」",
      "if (currentFinished()) {" in bk_body and "exitBecauseScriptDone()" in bk_body)

# Ctrl / Alt 也是「按键」，结束态下同样要走退出
for key in ("keyCtrl", "keyAlt"):
    i_k = term.find("R.id.%s).setOnClickListener" % key)
    kb = term[i_k:i_k + 400] if i_k >= 0 else ""
    check("[%s] 结束态下也走退出（它同样是「按键」）" % key,
          "if (currentFinished()) {" in kb and "exitBecauseScriptDone()" in kb)

# 界面：提示条（结束态）与「+」（非结束态）
check("提示条、「+」、屏幕按键行在 applyFinishedUi 里都有交代",
      "R.id.scriptDoneBar" in ui_body and "R.id.keyBar" in ui_body and
      "R.id.newTabBtn" in ui_body)
check("提示条布局与文案都在（脚本结束时必须有话说明）",
      'android:id="@+id/scriptDoneBar"' in layout and
      'android:id="@+id/keyBar"' in layout and
      "terminal_script_done_code" in strings and "terminal_script_done_hint" in strings and
      "敲任意键或点此条返回文件管理" in strings)
check("提示条可点（条上写着「点此条返回」，点不动就说不过去）",
      "R.id.scriptDoneBar).setOnClickListener { exitBecauseScriptDone() }" in term and
      'android:foreground="?android:attr/selectableItemBackground"' in layout)
check("退出码非 0 时提示条变色（复用终端那支红）",
      "ScriptDone.isFailure(code)" in term)

# ---------------------------------------------------------------------------
sec("6. 二进制内容不参与「编码修复」（3.11.3；判据 3.11.6 收紧）")

# 这一组锁的是一个**会改坏用户文件**的组合：
# ELF 前 8KB 里恰好出现 `0D 0A` 时（随机二进制里约一成概率），旧代码会把
# 「使用了 Windows 换行符」报给用户；用户一点「修复并执行」，fixEncoding 就会
# 把二进制里**所有** 0x0D 删掉 —— 那是在损坏文件。
# 三道防线缺一不可，任何一条被删掉都应当场失败。
#
# 3.11.6 起闸门用 binaryContent（魔数 或 NUL），**故意不看 shebang**：
# 「#!/bin/sh + 二进制载荷」的脚本能跑，但同样经不起删 0x0D —— 能跑 ≠ 能改。

check("hasEncodingIssue 以「不是二进制内容」为前提",
      "get() = !binaryContent && !sniffedViaShell && (hasBom || hasCrlf)" in runner)
check("fixEncoding 里有二进制闸门（防别处直接调用）",
      "if (isBinaryFile(file))" in runner and "refuse to modify binary file" in runner)
check("isBinaryFile 用不看 shebang 的 ScriptFormat.binaryKind",
      "private fun isBinaryFile(file: File): Boolean" in runner and
      "return ScriptFormat.binaryKind(head) != null" in runner)
check("编码修复使用同目录临时文件、fsync 与原子重命名",
      "FileOutputStream(temporary, false)" in runner and
      "output.fd.sync()" in runner and
      "StandardCopyOption.ATOMIC_MOVE" in runner and
      "file.writeBytes(fixed)" not in runner)
check("编码修复保留当前原文并清理未提交临时文件",
      "Files.copy(\n                file.toPath(), backup.toPath()," in runner and
      "finally {\n            temporary.delete()\n        }" in runner)

i_kind = runner.find("val magic = ScriptFormat.magicKind(head)")
i_crlf = runner.find("hasCrlf = true")
check("analyze 里形态判定与 CRLF 扫描都找得到", i_kind >= 0 and i_crlf >= 0,
      "kind=%d crlf=%d" % (i_kind, i_crlf))
check("analyze 先判形态、再扫 CRLF（顺序不能反）", 0 <= i_kind < i_crlf,
      "kind=%d crlf=%d" % (i_kind, i_crlf))
check("BOM / CRLF 只在「纯文本」时才算（载荷里的 0D 0A 不是 Windows 换行）",
      "val hasBom = !binaryContent && head.size >= 3" in runner and
      "if (!binaryContent) {" in runner)

check("管道通道只对魔数命中 / 外来 shebang 提前报错（含 NUL 不再一律拦）",
      "ScriptFormat.magicKind(head)?.let { kind ->" in SRC_TEXT and
      "ScriptFormat.binaryKind(head) != null" in SRC_TEXT and
      "ScriptRunner.interpreterByExtension(scriptFile0) == null" in SRC_TEXT and
      "ScriptRunner.isKnownInterpreter(it)" in SRC_TEXT)
check("这个提前报错发生在起进程之前",
      SRC_TEXT.find("这不是文本脚本") < SRC_TEXT.find("val process = pb.start()"),
      "报错位置 %d，起进程位置 %d" % (SRC_TEXT.find("这不是文本脚本"),
                                 SRC_TEXT.find("val process = pb.start()")))
check("ScriptRunner 暴露了 isKnownInterpreter / interpreterByExtension 给管道通道用",
      "internal fun isKnownInterpreter(name: String): Boolean" in runner and
      "internal fun interpreterByExtension(path: String): String?" in runner)

# ---------------------------------------------------------------------------
sec("7. 读不到文件的脚本：借执行身份去读（3.11.4 打标，3.11.5 换成真读）")

# 这一组锁的是一条**很容易漏、后果又正好抵消 3.11.1 修正**的岔路：
# App 用普通身份读不到 /data 下 root 建的脚本时，preflight 会跳过全部嗅探，
# binaryKind 必然是 null。若就这么往下走，判断就退化成「当它是文本脚本」→
# 又回到 `sh <路径>`，把编译/加密过的脚本交给 shell 解析（一屏 unexpected ')'）。
# 根子在「没嗅到」和「嗅出来是文本」被当成了同一件事。
#
# 3.11.4 拿 Java 的 File.canExecute() 兜底 —— **那是错的**，它判的是 App 自己的
# 身份，而真正执行脚本的可能是 su 里的 root。真机案例：/data/1/驱动.sh
# （root 建的 shc 产物），root 下 ./驱动.sh 正常，App 里却只报 unexpected ')'，
# 因为 App 判「没有执行位」又走回了 sh。
# 3.11.5 改成**借执行身份把那 8KB 读出来**，读到了就走与 App 可读时完全相同的
# analyze()，读不到才退到「让 shell 现判」。

check("Preflight 有 unreadable 字段（把「没嗅到」与「嗅出来是文本」分开）",
      "val unreadable: Boolean = false" in runner)
check("读不到时 preflight 打标记（而不是静默返回一个空 Preflight）",
      "unreadable = true" in runner)
check("形态判定抽成了 analyze()，两条路共用",
      "private fun analyze(" in runner and
      "return analyze(path, workDir, head, viaShell = false)" in runner)
check("prepare 对读不到的文件借执行身份去读（而不是猜）",
      "if (pf.unreadable)" in runner and
      "val probe = probeAsIdentity(path, asRoot)" in runner and
      "pf = analyze(path, pf.workDir, sniffed, viaShell = true)" in runner)
check("不再用 Java 侧的 File.canExecute() 判「能不能跑」（它判的是 App 自己）",
      "File(path).canExecute()" not in runner and "getOrDefault(false)" not in runner)
check("探测走的是与执行身份一致的通道（ROOT 用 exec，普通用 execLocal）",
      "if (asRoot) ShellExecutor.exec(cmd) else ShellExecutor.execLocal(cmd)" in runner)
check("探测命令取回「读得到吗 / 有执行位吗 / 头部十六进制」三样",
      r"[ -r $q ] && printf '##r1\\n' || printf '##r0\\n'" in runner and
      r"[ -x $q ] && printf '##x1\\n' || printf '##x0\\n'" in runner and
      "head -c $SNIFF_BYTES $q" in runner and "od -An -v -tx1" in runner)
check("探测输出只认十六进制行（错误提示不会被当成文件头）",
      'private const val HEX_CHARS = "0123456789abcdefABCDEF"' in runner and
      "if (!line.all { it in HEX_CHARS || it == ' ' }) continue" in runner)
# 第二道门槛：su 的持久会话可能把命令回显出来，而命令里就带 `##r1` 这些字面量。
# 只靠「整行像十六进制」挡不住回显，必须再要求「先见标记行、后收十六进制」。
check("十六进制行必须出现在标记行之后（su 回显挡得住）",
      "if (!seenMarker) continue" in runner and
      "seenMarker = true" in runner)
check("探不到头部时把判断权交给 shell（deferToShell），而不是默认当文本",
      "val deferToShell: Boolean = false" in runner and
      "deferToShell = true" in runner and
      "if (prepared.deferToShell)" in runner)
check("deferToShell 的命令形态是 `if [ -x ] 直接跑，否则 sh`",
      'sb.append("; else sh ")' in runner and "sb.append(\"; fi\")" in runner)
check("借身份读来的头部不参与「编码修复」（App 写不了它）",
      "!binaryContent && !sniffedViaShell && (hasBom || hasCrlf)" in runner and
      "val sniffedViaShell: Boolean = false" in runner)
check("读不到时给出的提醒包含「改用 ROOT 身份」这条出路",
      "请改用 ROOT 身份再试" in runner)
check("readNotice 经 notice() 才会写进终端（readNoticeOf 单独成函数）",
      "fun readNoticeOf(prepared: Prepared): String?" in runner)

# ---------------------------------------------------------------------------
sec("8. 126 / 127 退出码给出人话解释（3.11.4）")

# 用户拿到的原始输出只有一个「退出码 126」，而它几乎总是**环境问题**
# （SELinux 不让当前身份执行这个文件），不是脚本问题。
# 判据必须放在 shell 里 —— 「能不能执行」取决于**真正执行它的那个身份**，
# Java 侧的 File.canExecute() 判的是 App 自己的身份，两者可以不一致
# （本项目一直并存 ROOT 会话与普通会话）。

check("buildCommand 里对 126 / 127 各有一句解释",
      r"case \$__rxin_rc in" in bc_body and
      "126 = 文件在" in bc_body and "127 = 找不到命令" in bc_body)
check("126 的解释指向「换 ROOT 身份再试」",
      "改用 ROOT 身份再试一次" in bc_body)
check("解释写在退出码回显之后（否则会先于退出码出现）",
      0 <= bc_body.find("脚本已结束，退出码") < bc_body.find("126 = 文件在"))
# 命令形状本身（含两个分支互斥、退出码原样透出）由 probe-direct-exec-cmd.sh 在
# mksh / busybox ash / dash 三家真 shell 上验证。

# ---------------------------------------------------------------------------
sec("9. 结束标记的字节：OSC 2 在真 shell 上真打出来（3.13.1）")

# 命令尾那一段是**从 ScriptDone.java 读出来**的（TAIL），不是手抄的 ——
# 改了 Java 而没改断言不会静默失真。
#
# 验四件事：
#   ① printf 真把 `\033` / `\007` 解释成了 ESC / BEL（而不是把反斜杠原样打出去）
#   ② 标记里带的是**退出码**（夹具退 7，标记里就得是 7）
#   ③ 标记排在**最后** —— 它后面还有输出的话，App 收会话时会把它一并截掉
#   ④ 三家 shell 行为一致（Android 的 /system/bin/sh 就是 mksh）
#
# 「屏幕上不留字符」这一条测不了（要终端模拟器）：OSC 是被模拟器解析掉的转义序列，
# 判据在 com.termux.terminal.TerminalEmulator#doOscSetTextParameters（case 2 → setTitle）。

tail = java_const(done, "TAIL")
mark_prefix = java_const(done, "MARK_PREFIX")
intro = java_const(done, "OSC_INTRO")
end = java_const(done, "OSC_END")
check("ScriptDone.TAIL 解出来了（改了 Java 的拼接写法这里就取不到值）",
      bool(tail) and bool(mark_prefix) and bool(intro) and bool(end), repr(tail))
check("标记前缀是从源码读的（不是这里又抄一份）",
      mark_prefix == "RXIN-DONE:" and tail.count(mark_prefix) == 1, repr(mark_prefix))

if tail and mark_prefix and intro and end:
    MARK_RE = re.compile(
        re.escape(intro.encode("utf-8")) + re.escape(mark_prefix.encode("utf-8")) +
        rb"(\d+):(\d+)" + re.escape(end.encode("utf-8")))

    FIX = tempfile.mkdtemp(prefix="rxin-done-")
    try:
        for code in (0, 7, 126):
            fixture = os.path.join(FIX, "x%d.sh" % code)
            with open(fixture, "w", encoding="utf-8") as fh:
                fh.write("exit %d\n" % code)

            # 与 buildCommand 的尾部同形：先回显退出码，再挂结束标记
            cmd = ("sh '%s' ; __rxin_rc=$?; "
                   "printf '\\n[RouXin] 脚本已结束，退出码 %%s\\n' \"$__rxin_rc\"" % fixture) + tail

            for name, argv in available:
                p = subprocess.run(list(argv) + ["-c", cmd], stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, timeout=30)
                out = p.stdout
                m = MARK_RE.search(out)
                check("[%s] 退出码 %d：标记里的码就是它" % (name, code),
                      m is not None and m.group(2) == str(code).encode(),
                      "rc=%s out=%r" % (p.returncode, out[-160:]))
                check("[%s] 退出码 %d：用真 ESC / BEL，不是把 \\033 原样打出来" % (name, code),
                      b"\\033]" not in out and b"\\007" not in out,
                      repr(out[-160:]))
                check("[%s] 退出码 %d：标记就在输出末尾（后面再有输出就会被截掉）" % (name, code),
                      out.endswith(end.encode("utf-8")), repr(out[-80:]))
                check("[%s] 退出码 %d：一条命令里只打一次标记" % (name, code),
                      out.count(intro.encode("utf-8")) == 1, repr(out[-160:]))
                check("[%s] 退出码 %d：pid 段也带上了（标题得真「变」才会回调）" % (name, code),
                      m is not None and m.group(1).isdigit(), repr(out[-160:]))
                check("[%s] 退出码 %d：人看的那行排在标记前面" % (name, code),
                      out.find("脚本已结束".encode("utf-8")) < out.find(intro.encode("utf-8")),
                      repr(out[-200:]))
                # 「命令尾不许 exit」这条底线：标记之后外壳还得活着，$__rxin_rc 还查得到
                p2 = subprocess.run(
                    list(argv) + ["-c", cmd + " ; printf '\\nSTILL_ALIVE rc=%s\\n' \"$__rxin_rc\""],
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
                check("[%s] 退出码 %d：标记之后外层 shell 仍然活着（没有 exit）" % (name, code),
                      ("STILL_ALIVE rc=%d" % code).encode() in p2.stdout, repr(p2.stdout[-120:]))
    finally:
        shutil.rmtree(FIX, ignore_errors=True)

# ------------------------------------------------------------------ 结果

print()
print("结果: 通过 %d 项，失败 %d 项" % (passed, len(failed)))
if failed:
    print("失败清单:")
    for f in failed:
        print("  - %s" % f)
sys.exit(1 if failed else 0)
