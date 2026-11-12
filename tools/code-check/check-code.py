#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
工程级静态自检 —— 捡现有那些套件都没覆盖的一类问题。

**为什么单独一个套件**：`tools/` 下已有的校验（archive / find / image / incoming /
script / sort / textslice / ui）都是**按功能**组织的，各自盯自己的判据。
但有一类问题不属于任何功能，它是**工程整体**的：

  * 布局/代码引用了一个并不存在的资源（编译期会红，但 `?attr/` 那一类不会）
  * 资源定义了却没人用（改名、删功能之后最容易剩下的东西）
  * 布局定义了 id 却从来没有代码引用它（漏绑定，或者纯装饰 —— 要看一眼才知道）
  * 拿**有状态**的格式化器当共享字段（`SimpleDateFormat` 那类，多线程共用会解析错乱）
  * 断言写了却**不在任何 run 脚本里** —— 等于从来没跑过

前四类是 3.15.10 那次「全方位查 bug」人工扫出来的，这一份把能精确判定的固化下来。

**判据取舍**：机器判不准的一律只「报告」不「判失败」。比如「孤立资源」里
`Theme.RouXin.Base` 是被 `parent="..."` 继承的、`ic_check` 可能只是留着备用 ——
脚本列出来让人看一眼，但不因此报红。**误报一次，这个脚本就没人再看了。**

用法：python3 tools/code-check/check-code.py     （在工程根目录或任意目录都行）
"""
import collections
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
APP = os.path.join(ROOT, "app", "src", "main")
RES = os.path.join(APP, "res")
PKG = os.path.join(ROOT, "app", "src", "main", "java", "com", "rxinfiles", "tool")
TOOLS = os.path.join(ROOT, "tools")

KINDS = ("drawable", "color", "string", "dimen", "style", "layout")


def rel(p):
    return os.path.relpath(p, ROOT).replace("\\", "/")


def strip_block_comments(s):
    """去掉代码注释 —— 判「不许出现某标识符」前**必须先剥注释**。

    注释里解释「为什么不用 X」是常态，不剥的话红的是注释本身。
    （反面是「查注释里写了什么」——那要用原始源码。两个方向正好相反。）
    """
    s = re.sub(r"/\*[\s\S]*?\*/", "", s)
    s = re.sub(r"//[^\n]*", "", s)
    return s


def strip_xml_comments(s):
    return re.sub(r"<!--[\s\S]*?-->", "", s)


def collect_sources():
    """(路径, 是否代码) 列表 —— 只收会被编译/打包的那部分。"""
    out = []
    for base in (RES, os.path.join(APP, "java"), TOOLS,
                 os.path.join(APP, "AndroidManifest.xml"),
                 os.path.join(ROOT, "app", "build.gradle.kts")):
        if os.path.isfile(base):
            out.append((base, not base.endswith((".xml", ".kts"))))
            continue
        for root, dirs, fs in os.walk(base):
            dirs[:] = [d for d in dirs if d not in ("build", ".gradle", "__pycache__")]
            for f in fs:
                if f.endswith((".xml", ".kt", ".java", ".kts", ".py")):
                    out.append((os.path.join(root, f), f.endswith((".kt", ".java"))))
    return out


# ===========================================================================
# 断言一：不许有悬空的资源引用
# ===========================================================================
def defined_resources():
    d = collections.defaultdict(set)
    for folder in glob.glob(os.path.join(RES, "drawable*")):
        for f in os.listdir(folder):
            d["drawable"].add(os.path.splitext(f)[0])
    for folder in glob.glob(os.path.join(RES, "values*")):
        for f in glob.glob(os.path.join(folder, "*.xml")):
            src = open(f, encoding="utf-8").read()
            for m in re.finditer(
                    r'<(color|string|dimen|style|attr|bool|integer)\s+name="([^"]+)"', src):
                d[m.group(1)].add(m.group(2))
    for f in os.listdir(os.path.join(RES, "layout")):
        d["layout"].add(os.path.splitext(f)[0])
    return d


def referenced_resources(sources):
    """返回 (直接引用, 仅 parent 引用)。

    两个集合分开的原因：`parent="Theme.Material3.DayNight.NoActionBar"` 里的名字
    **来自 Material 库、不在本工程定义** —— 拿它去判「悬空引用」会误报两条。
    但它确实是「这个 style 在被用」的证据，所以判「孤立资源」时又要算上。
    """
    ref = collections.defaultdict(lambda: collections.defaultdict(set))
    parent_ref = collections.defaultdict(set)
    pat_xml = re.compile(r'@(android:)?(drawable|color|string|dimen|style|layout)/([\w.]+)')
    pat_code = re.compile(r'(?<!android\.)\bR\.(drawable|color|string|dimen|style|layout)\.(\w+)')
    pat_parent = re.compile(r'parent="([\w.]+)"')
    for path, is_code in sources:
        if path.endswith(".py"):
            continue
        src = open(path, encoding="utf-8", errors="replace").read()
        src = strip_xml_comments(src)
        if is_code:
            src = strip_block_comments(src)
        for m in pat_xml.finditer(src):
            if not m.group(1):
                ref[m.group(2)][m.group(3)].add(path)
        for m in pat_code.finditer(src):
            ref[m.group(1)][m.group(2)].add(path)
        for m in pat_parent.finditer(src):
            parent_ref[m.group(1)].add(path)
    return ref, parent_ref


def check_dangling(sources):
    defined, (ref, _) = defined_resources(), referenced_resources(sources)
    bad = []
    for kind in KINDS:
        for name in sorted(ref[kind]):
            if name not in defined[kind]:
                where = "、".join(sorted(os.path.basename(w) for w in ref[kind][name]))
                bad.append(f"[{kind}] {name}  ← {where}")
    return bad


# ===========================================================================
# 断言二：不许拿有状态的格式化器当共享字段
#
# `SimpleDateFormat` / `DateFormat` 内部有一个可变 Calendar，是**有状态**的。
# 放它们的地方通常是 `object` 单例（`FileRepository` 就是），而调用点跑在
# `Dispatchers.IO` 上 —— 快速连点目录时，上一个协程被 cancel() 后**不会立刻停**
# （协作式取消，这条链路上没有挂起点），两个线程就同时用同一个实例。
# **症状是解析结果错乱、而不是抛异常**，try/catch 兜不住，非常隐蔽
# （3.15.10 实测发现：`FileRepository` 的 `ls -la` 时间列可能串成别的文件的时间）。
#
# 判据只看「4 个空格缩进的 `val ... = SimpleDateFormat(...)`」—— 也就是类 / object
# 的直接成员。函数体里的局部变量（缩进 ≥ 8）是安全的，**绝不能误报**。
# 假阴性（companion 里的字段）可以接受，假阳性不行。
# ===========================================================================
SHARED_KT = re.compile(
    r"^ {4}(?:private |internal |public )?val \w+\s*=\s*(?:SimpleDateFormat|DateFormat)\b")
SHARED_JAVA = re.compile(
    r"^ {4}(?:private |public |protected )?static .*=\s*new (?:SimpleDateFormat|DateFormat)\b")


def check_shared_formatters():
    bad = []
    for f in sorted(os.listdir(PKG)):
        if not f.endswith((".kt", ".java")):
            continue
        path = os.path.join(PKG, f)
        for i, line in enumerate(open(path, encoding="utf-8"), 1):
            if SHARED_KT.match(line) or SHARED_JAVA.match(line):
                bad.append(f"{rel(path)}:{i}  {line.strip()}")
    return bad


# ===========================================================================
# 断言三：写了 `*Check.java` 就必须真的在跑
#
# `TarCheck` / `SelectCheck`（archive-check）和 `FindCheck`（find-check）写完就
# **没进任何 run 脚本** —— 它们从来被执行过，直到 3.15.10 人工发现。
# 「断言要能真的失败才算数」，而一个不会跑的断言连失败都做不到。
# ===========================================================================
CHECK_CLASS = re.compile(r"Check\.java$")
HAS_MAIN = re.compile(r"static void main\s*\(")
JAVA_PATH_REF = re.compile(r"[\w./$-]*\w+\.java")


def strip_sh_comments(s):
    """去掉整行 shell 注释。

    只剥「整行都是注释」的那种，不碰行尾的 `#`（那可能是字符串里的一部分）。
    """
    return "\n".join(l for l in s.splitlines() if not l.lstrip().startswith("#"))


def check_orphan_checks():
    """有 `main` 的 `*Check.java` 必须真的被执行。

    <h2>这个断言改了三次才对 —— 三次都是「假绿」，全是变异测试逮出来的</h2>
    1. 连 `.py` 一起扫 —— **这个文件自己的注释**里提到的类名被当成了「在跑」的证据。
       改成只扫 `.sh`（「跑」这件事只在 shell 脚本里发生）。
    2. 还是绿 —— `SelectCheck` 仍以 **`xxx.java` 编译路径**的形式出现。
       **被编译进去 ≠ 被执行**（有 `javac ... SelectCheck.java` 不等于有 `java ... SelectCheck`）。
       改成先把 `.java` 形式的引用剥掉。
    3. 还是绿 —— `run-checks.sh` 里**我新加的那段注释**又写了 `SelectCheck`。
       再加一步：剥掉整行 shell 注释。

    **教训：凡是「在一个大文本里找名字」的判据，注释一定会污染它。**
    写这类判据必须**先剥注释**，而且剥完要用变异测试确认它真的会红。

    另外只要求**有 `main` 的**类：`Check.java` 是各校验程序共用的工具类（没有 main），
    它被编译进去就够了，不该要求它「被执行」。
    """
    scripts = ""
    for root, dirs, fs in os.walk(TOOLS):
        dirs[:] = [d for d in dirs if d not in ("build", "out", "__pycache__")]
        for f in fs:
            if f.endswith(".sh"):
                scripts += open(os.path.join(root, f), encoding="utf-8",
                                errors="replace").read()
    executable = JAVA_PATH_REF.sub("", strip_sh_comments(scripts))

    bad = []
    for folder in sorted(os.listdir(TOOLS)):
        d = os.path.join(TOOLS, folder)
        if not os.path.isdir(d):
            continue
        for f in sorted(os.listdir(d)):
            if not CHECK_CLASS.search(f):
                continue
            path = os.path.join(d, f)
            src = open(path, encoding="utf-8", errors="replace").read()
            if not HAS_MAIN.search(src):
                continue  # 共享工具类，不要求被单独执行
            if os.path.splitext(f)[0] not in executable:
                bad.append(f"{rel(path)} 有 main 却不在任何 run 脚本里执行 —— 从来没跑过")
    return bad


# ===========================================================================
# 报告（不判失败）：孤立资源 / 定义了却没人引用的布局 id
# ===========================================================================
def report_unused(sources):
    defined, (ref, parent_ref) = defined_resources(), referenced_resources(sources)
    lines = []
    for kind in KINDS:
        for name in sorted(defined[kind]):
            if name in ref[kind] or name in parent_ref:
                continue
            lines.append(f"[{kind}] {name}")
    return lines


def report_unreferenced_ids(sources):
    ids = collections.defaultdict(list)
    for f in sorted(os.listdir(os.path.join(RES, "layout"))):
        if not f.endswith(".xml"):
            continue
        src = strip_xml_comments(
            open(os.path.join(RES, "layout", f), encoding="utf-8").read())
        for m in re.finditer(
                r'<([A-Za-z][\w.]*)\b[^>]*?android:id="@\+id/(\w+)"', src, re.S):
            ids[m.group(2)].append((f, m.group(1).split(".")[-1]))
    code = ""
    for path, is_code in sources:
        if is_code and "tools" not in path:
            code += strip_block_comments(open(path, encoding="utf-8", errors="replace").read())
    return [f"{i:26s} ← " + "、".join(f"{f} 的 <{t}>" for f, t in spots)
            for i, spots in sorted(ids.items()) if f"R.id.{i}" not in code]


# ===========================================================================
def main():
    sources = collect_sources()
    problems = []

    dangling = check_dangling(sources)
    if dangling:
        problems.append(("悬空的资源引用（引用了但没定义）", dangling))

    formatters = check_shared_formatters()
    if formatters:
        problems.append(("有状态的格式化器被当成共享字段", formatters))

    orphans = check_orphan_checks()
    if orphans:
        problems.append(("写了却没在跑的 *Check.java", orphans))

    print(f"扫描了 {len(sources)} 个文件（布局 / 资源 / 代码 / 清单）")

    if problems:
        print()
        for title, items in problems:
            print(f"发现 {len(items)} 处问题 —— {title}：")
            for it in items:
                print("  ✗ " + it)
            print()
        return 1

    print("三条断言全过（无悬空引用 / 无共享格式化器 / 无孤儿断言）")

    unused = report_unused(sources)
    print(f"\n-- 仅供参考：定义了但没人引用的资源（{len(unused)} 个）--")
    print("\n".join("  " + x for x in unused) if unused else "  （无）")

    ids = report_unreferenced_ids(sources)
    print(f"\n-- 仅供参考：布局定义了却没被代码引用的 id（{len(ids)} 个）--")
    print("\n".join("  " + x for x in ids) if ids else "  （无）")

    print("\n上面两节只是列出来给人看，不计入成败。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
