#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
`BUG日志-20261110.md` 里 7 个 Bug 的**源码不变量**断言。

为什么要有它：这 7 个 Bug 的共同点是「**在普通开发环境下不会报错，也不会
当场崩**」——ROOT 身份失败被普通 Java API 当成「不存在」、持久 `su` 里的
`exit` 悄悄杀掉会话、Activity 销毁顺手清空其他任务的暂存、两个进程各自
整表覆盖 SharedPreferences、`File.exists()` 跟随符号链接漏判悬空链接。
真机 / ROOT 环境不可用，这类问题没法靠运行用例复现；能钉住的只有**源码里
的契约**：只要修复被改回旧形态，这里的断言就必须变红。

判据一律「从源码读事实」，不复制实现：

  * 判「不许出现某标识符」前**先剥注释**（在注释里解释「为什么不这么做」
    不该把断言弄红）；
  * 需要判断「代码里有没有某个判断」时，用函数体 / 窗口片段定位，而不是
    在整份文件里裸找字符串（别处同名标识符会污染判据）；
  * 断言的是**行为/结构不变量**（统一身份的存在性、锁内事务、按所有者释放、
    子 shell 包装……），不是某一句实现细节的逐字复制。

共 7 节，对应 BUG-001 ~ BUG-007。每节断言都应当能因**旧实现**变红，
修复后全绿；任何一条红都说明修复退化或断言过窄 —— 前者修源码，后者修断言，
修断言的原因要写进 README（误报一次，这个套件就没人再看了）。

  用法：python3 tools/bugfix-check/verify-bugfix.py
"""
import os
import re
import sys

# 默认校验当前工作区。设 BUGFIX_ROOT 可把判据指向「修复前的源码快照」做自检 ——
# 一套「修复前红、修复后绿」的断言，必须能证明它真的会红（见 README 的「自检」）。
ROOT = os.path.abspath(os.environ.get("BUGFIX_ROOT")
                       or os.path.dirname(os.path.dirname(
                           os.path.dirname(os.path.abspath(__file__)))))
MAIN = os.path.join(ROOT, "app", "src", "main")
PKG = os.path.join(MAIN, "java", "com", "rxinfiles", "tool")

FILES = {
    "fileops": os.path.join(PKG, "FileOps.kt"),
    "shell": os.path.join(PKG, "ShellExecutor.kt"),
    "textrepo": os.path.join(PKG, "TextFileRepository.kt"),
    "bookmark": os.path.join(PKG, "BookmarkStore.kt"),
    "main": os.path.join(PKG, "MainActivity.kt"),
    "image": os.path.join(PKG, "ImageViewerActivity.kt"),
    "textviewer": os.path.join(PKG, "TextViewerActivity.kt"),
    "archive": os.path.join(PKG, "ArchiveRepository.kt"),
    "archivesheet": os.path.join(PKG, "ArchiveSheet.kt"),
    "rxinapp": os.path.join(PKG, "RxinApp.kt"),
}

total = 0
failed = 0
skipped = 0


def check(name, ok):
    global total, failed
    total += 1
    if ok:
        print("  ok    " + name)
    else:
        failed += 1
        print("  FAIL  " + name)


def skip(name):
    """开源版不含 HTTP 模块：相关断言跳过并显式说明（不算通过、也不算失败）。"""
    global skipped
    skipped += 1
    print("  skip  " + name)


def section(title):
    print()
    print("== " + title + " ==")


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read().replace("\r\n", "\n").replace("\r", "\n")


def strip_comments(src):
    """去掉 // 与 /* */（保留字符串字面量里的内容，含 Kotlin 的 \"\"\"）。

    判「不许出现某标识符」之前必须先过这一道：源码里解释「为什么**不**这么写」
    的注释会让断言永远红（踩过）。
    """
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if c == "/" and src.startswith("/*", i):
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == "/" and src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue
        if c == '"' and src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n - 3 if j < 0 else j
            out.append(src[i:j + 3])
            i = j + 3
            continue
        if c == '"' or c == "'":
            quote = c
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == quote:
                    break
                j += 1
            out.append(src[i:j + 1])
            i = j + 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def strip_strings(src):
    """把字符串字面量的**内容**抹掉（引号留着）。

    需要区分「代码里真的比较了某个数字」和「命令字符串里恰好有那个数字」时用：
    BUG-001 的 `exit 4` 会出现在 shell 命令字符串里，不能把它当成退出码映射。
    """
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if c == '"' and src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n - 3 if j < 0 else j
            out.append('""')
            i = j + 3
            continue
        if c == '"' or c == "'":
            quote = c
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == quote:
                    break
                j += 1
            out.append('""' if quote == '"' else "''")
            i = j + 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def body_of(src, marker):
    """取一个函数/方法的完整函数体（到本层结束的那个右花括号）。

    必须精确到「这一层结束」，按固定长度截会截到隔壁函数去：例如
    `FileOps.rename` 后面紧跟着 `uniqueTarget`，后者的 `exists()` 会被当成
    前者的判据（踩过）。
    """
    i = src.find(marker)
    if i < 0:
        return ""
    j = src.find("\n    }", i)
    return src[i:j + 5] if j > 0 else src[i:]


def window_of(src, marker, size=700):
    """从 marker 开始取一段窗口（用于表达式函数 / 简短函数）。"""
    i = src.find(marker)
    return "" if i < 0 else src[i:i + size]


def shell_test_present(text):
    """shell 层「同一身份」存在性复核的两种等价写法。

    `test -e PATH` 与 `[ -e PATH ]` 是同一条判据的两种写法；判据要认结果，
    不能只认其中一种写法，否则实现合法重构就会被误判成退化（踩过）。
    """
    return (re.search(r"test\s+-[eLd]\b", text) is not None
            or re.search(r"\[\s+-[eLd]\s", text) is not None)


def identity_exists(text):
    """源码里有没有「统一身份存在性」判据：entryExists / rootEntryExists / shell test。"""
    return re.search(r"[Ee]ntryExists\(", text) is not None or shell_test_present(text)


def has_subshell_wrap(text):
    """持久 shell 的命令是否被包进子 shell：`(\n$cmd\n)`（含转义换行写法）。"""
    return re.search(r"\(\s*(?:\\[nrt]\s*)?\$\{?cmd", text) is not None



def read_kt_tree():
    """递归读出 java 包下所有 .kt（跨文件判据用）。"""
    out = {}
    for root, dirs, files in os.walk(os.path.join(MAIN, "java")):
        dirs[:] = [d for d in dirs if d not in ("build", ".gradle", "__pycache__")]
        for f in files:
            if f.endswith(".kt"):
                path = os.path.join(root, f)
                out[path] = read(path)
    return out


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    raw = {name: read(path) for name, path in FILES.items()}
    src = {name: strip_comments(text) for name, text in raw.items()}
    code = {name: strip_strings(text) for name, text in src.items()}
    tree = read_kt_tree()

    shell = src["shell"]
    fileops = src["fileops"]
    repo = src["textrepo"]
    bookmark = src["bookmark"]
    http = src.get("httpapi", "")

    # =================================================================== 1
    section("BUG-001：ROOT 文本保存的退出协议（命令里的 exit 不能再杀掉持久 su）")
    #
    # 旧实现：`{ cp ... || exit 1; ...; exit 0; }` 放进持久 su 的**花括号命令组**。
    # 花括号不建子 shell，任意 exit 直接终止持久 su，`printf '[RXEXIT]'` 与
    # ShellExecutor 的 marker 都不会打印；提交已经发生，上层却收到 false。
    commit_root = body_of(repo, "private fun commitWithRoot(")
    write_body = body_of(repo, "fun write(")

    check("ShellExecutor 提供带退出码的执行入口（execWithStatus 或等价的 Status/Once 接口）",
          re.search(r"fun\s+(?:exec\w*Status\w*|\w*WithStatus\w*|execOnce|runOnce|execRootOnce)\s*\(",
                    shell) is not None)
    check("持久 shell 的命令包装使用子 shell（括号里的 exit 不会终止持久 su）",
          has_subshell_wrap(shell))
    check("持久 shell 的命令组里即使命令含 exit，尾部 marker 也仍然打得出来",
          has_subshell_wrap(shell) and re.search(r"printf\s+'%s", shell) is not None)
    check("退出码由外层 shell 在子 shell 结束后打印（$?），命令内 exit 只结束子 shell",
          re.search(r"\$\?", shell) is not None
          and re.search(r"fun\s+\w*[Ss]tatus\w*\s*\(", shell) is not None)
    check("持久 su 不再把命令裸放进 { ... } 命令组执行（exit 会杀掉会话）",
          re.search(r"\{\s*\$cmd", shell) is None)
    check("ShellExecutor 里不再只有「读不到 marker 就当失败」的单一协议",
          "readUntilMarker" in shell and re.search(r"fun\s+\w*[Ss]tatus\w*\s*\(", shell) is not None)

    # commitWithRoot 里如果还保留 `exit N`：必须确认它跑在子 shell / 一次性进程里；
    # 若已经不再用 exit，则这条契约天然成立。
    subshell_anywhere = (has_subshell_wrap(shell)
                         or "execWithStatus" in shell
                         or re.search(r"fun\s+\w*[Ss]tatus\w*\s*\(", shell) is not None)
    if re.search(r"\bexit\s+[0-9]", commit_root):
        check("commitWithRoot 仍在命令里用 exit，因此必须确认整段提交跑在子 shell/一次性进程里",
              subshell_anywhere)
    else:
        check("commitWithRoot 不再把 exit 当持久 shell 里的控制流（不依赖 exit 后还能打印 marker）",
              True)
    check("commitWithRoot 通过带退出码的接口拿结果（RootShell.run / execWithStatus），而不是只靠 succeeded 布尔",
          re.search(r"RootShell\.(?:run|exec\w*)\(", commit_root) is not None
          or "execWithStatus" in commit_root)
    # 提交后复核：marker 缺失不等于没提交，必须重新读目标（版本/大小/摘要）确认。
    post_commit_verify = (
        commit_root.count("snapshot(") >= 2
        or "contentMatches(" in commit_root
        or "sha256" in commit_root.lower()
        or "checksum" in commit_root.lower()
        or re.search(r"\bcmp\s+-", commit_root) is not None
    )
    check("commitWithRoot 在提交动作之后重新复核目标（快照/内容比对/摘要），marker 缺失不再简单当未提交",
          post_commit_verify)
    # exit code 4 的语义是「目标已被改动」——必须映射为 WRITE_CONFLICT，而不是普通失败。
    # 只认**代码**里的比较；命令字符串里的 `exit 4` 不算（所以要先 strip_strings）。
    cr_code = strip_strings(commit_root)
    conflict_mapping = (
        re.search(r"==\s*\w*CONFLICT\w*", cr_code) is not None
        or re.search(r"==\s*4\b", cr_code) is not None
        or re.search(r"\b4\b\s*->\s*WRITE_CONFLICT", cr_code) is not None
        or (re.search(r"[\w.]*CONFLICT\w*\s*=\s*4\b", cr_code) is not None
            and "WRITE_CONFLICT" in cr_code)
    )
    check("文本保存的 exit code 4（目标已变化）映射为 WRITE_CONFLICT",
          conflict_mapping and "WRITE_CONFLICT" in cr_code)
    check("RootShell 的退出码协议保留（Res.code），不是只看输出有没有内容",
          re.search(r"data class Res\(", fileops) is not None
          and re.search(r"\.code\b", fileops) is not None)

    # =================================================================== 2
    section("BUG-002：ROOT-only 删除不能静默变成空操作")
    #
    # 旧实现：`if (!file.exists() && !symlink) return true`。普通身份 stat 失败
    # （父目录无权限）被当成「文件不存在」，ROOT rm 永远不会执行，却返回成功。
    delete_rec = body_of(fileops, "private fun deleteRecursive(")
    delete_all = body_of(fileops, "fun delete(")
    check("deleteRecursive 不再把「普通 exists() 为 false」直接当成已清理",
          re.search(r"if\s*\(\s*!\s*file\.exists\(\)\s*&&\s*!symlink\s*\)\s*return\s+true",
                    delete_rec) is None)
    check("deleteRecursive 在普通身份看不到条目时用 ROOT test -e / test -L 复核",
          identity_exists(delete_rec))
    check("delete() 的成功统计来自 deleteRecursive 的同一身份复核结果（不再看普通 exists）",
          re.search(r"if\s*\(\s*deleteRecursive\(", delete_all) is not None)
    check("删除后的成功判定用同一身份复核（不再 java exists 一次就算删掉）",
          re.search(r"rootRemove\(|root\w*[Ee]ntryExists\(|entryExists\(", delete_rec) is not None)
    root_remove = body_of(fileops, "private fun rootRemove(")
    check("ROOT 删除助手在 rm 之后用同一身份复核条目确实消失",
          root_remove != "" and "rm -rf" in root_remove
          and re.search(r"!\s*root\w*[Ee]ntryExists\(", root_remove) is not None)
    check("FileOps 提供统一身份的存在性判据 entryExists",
          "fun entryExists(" in fileops)
    check("entryExists 把符号链接 / ROOT-only 条目也算「存在」",
          re.search(r"fun entryExists\([\s\S]{0,700}?(?:isSymbolicLink|test -L)", fileops)
          is not None
          and re.search(r"fun entryExists\([\s\S]{0,700}?(?:exists\(\)|test -e)", fileops)
          is not None)

    # =================================================================== 3
    section("BUG-003：ROOT-only 重命名不许覆盖已有目标、也不许误报失败")
    rename_file = body_of(fileops, "fun rename(file: File, newName: String)")
    http_rename = body_of(http, "private fun rename(")
    check("FileOps.rename 不再用 mv -f（无条件覆盖目标）",
          "mv -f" not in rename_file)
    check("FileOps.rename 执行前用统一身份拒绝已存在的目标",
          identity_exists(rename_file))
    check("FileOps.rename 的 ROOT 命令在执行 mv 前先以 target 存在性为条件（不覆盖语义）",
          re.search(r"if\s*\[\s+-e\s", rename_file) is not None
          and "mv -f" not in rename_file)
    check("FileOps.rename 不再只靠普通 target.exists() 做同名检查",
          re.search(r"if\s*\(\s*target\.exists\(\)\s*\)", rename_file) is None
          and "target.exists()" not in rename_file)
    mv_index = rename_file.find("mv ")
    mv_tail = rename_file[mv_index:] if mv_index >= 0 else rename_file
    check("FileOps.rename 成功后用 ROOT test 复核目标（不再只信 java exists）",
          mv_index >= 0
          and (shell_test_present(mv_tail)
               or re.search(r"[Ee]ntryExists\(", mv_tail) is not None))
    check("FileOps.rename 的 ROOT 分支源/目标都交给同一身份判断",
          "RootShell" in rename_file and identity_exists(rename_file))
    check("FileOps.rename 返回新文件前不再要求普通 target.exists() 为 true",
          re.search(r"if\s*\(\s*target\.exists\(\)\s*\)\s*target\s+else\s+null", rename_file)
          is None)
    if http:
        check("HttpApi.rename 的源/目标预检使用统一身份 entryExists",
              "entryExists" in http_rename)
        check("HttpApi.rename 不再用裸 source.exists() / target.exists() 预检",
              "source.exists()" not in http_rename and "target.exists()" not in http_rename)
    else:
        skip("HttpApi.rename 的源/目标统一身份预检（开源版不含 HTTP 模块，本断言跳过）")

    # =================================================================== 4
    section("BUG-004：ROOT 复制/移动不能被普通权限预检提前阻断")
    copy_body = body_of(fileops, "fun copy(")
    move_body = body_of(fileops, "fun move(")
    paste_body = body_of(src["main"], "private fun pasteInto(")
    transfer_body = body_of(http, "private fun transfer(")
    check("FileOps 提供统一身份的 isDirectory",
          "fun isDirectory(" in fileops)
    check("isDirectory 在普通身份失败时用 ROOT test -d 复核",
          re.search(r"fun isDirectory\([\s\S]{0,700}?(?:test -d|RootShell)", fileops) is not None)
    check("FileOps 提供 ensureDirectory 统一入口（目录预检不再各自裸判断）",
          "fun ensureDirectory(" in fileops)
    check("FileOps.copy 的目标目录判断改走统一身份（不只信 destDir.isDirectory/mkdirs）",
          re.search(r"fun copy\([\s\S]{0,1200}?(?:ensureDirectory|isDirectory\(destDir\)|entryExists\(destDir\)|mkdir -p)",
                    fileops) is not None)
    check("FileOps.move 的目标目录判断改走统一身份（不只信 destDir.isDirectory/mkdirs）",
          re.search(r"fun move\([\s\S]{0,1200}?(?:ensureDirectory|isDirectory\(destDir\)|entryExists\(destDir\)|mkdir -p)",
                    fileops) is not None)
    check("MainActivity.pasteInto 不再用裸 File.exists() 过滤来源",
          re.search(r"\.filter\s*\{\s*it\.exists\(\)\s*\}", paste_body) is None
          and "entryExists" in paste_body)
    check("MainActivity.pasteInto 的目录判断改走 FileOps.isDirectory",
          "source.isDirectory" not in paste_body and "FileOps.isDirectory(" in paste_body)
    check("MainActivity.pasteInto 的冲突判断改走统一身份",
          "it.name).exists()" not in paste_body
          and re.search(r"entryExists\(\s*File\(destDir", paste_body) is not None)
    if http:
        check("HttpApi.transfer 的目标目录判断改走统一身份",
              "destination.isDirectory" not in transfer_body
              and re.search(r"(?:FileOps\.)?isDirectory\(\s*destination\s*\)",
                            transfer_body) is not None)
        check("HttpApi 自己的 isDirectory 复核同样按 ROOT 身份（不是裸 File.isDirectory）",
              re.search(r"fun isDirectory\(file: File\)[\s\S]{0,300}?"
                        r"(?:rootTest|RootShell|FileOps\.isDirectory)", http) is not None)
    else:
        skip("HttpApi.transfer 与 isDirectory 的开源版对应断言（开源版不含 HTTP 模块，跳过）")

    # =================================================================== 5
    section("BUG-005：暂存目录按所有者释放，禁止任何界面清空全局 rxin_stage")
    clear_calls = []
    root_stage_deletes = []
    for path, text in tree.items():
        clean = strip_comments(text)
        if re.search(r"FileOps\.clearStage\s*\(", clean):
            clear_calls.append(os.path.basename(path))
        if re.search(r'"rxin_stage"\s*\)\s*\.deleteRecursively', clean):
            root_stage_deletes.append(os.path.basename(path))
    sweep = body_of(fileops, "fun sweepStaleStage(")
    on_destroy_image = body_of(src["image"], "override fun onDestroy()")
    on_destroy_text = body_of(src["textviewer"], "override fun onDestroy()")
    main_create = body_of(src["main"], "override fun onCreate(savedInstanceState: Bundle?)")
    compress = body_of(src["archive"], "fun compress(")
    stage_to_cache = body_of(fileops, "fun stageToCache(")

    check("全工程不再调用 FileOps.clearStage（%s）" % (clear_calls or "无调用点"),
          not clear_calls)
    check("FileOps 已删除 clearStage（全局递归清理入口不存在）",
          re.search(r"fun\s+clearStage\s*\(", fileops) is None)
    check("FileOps 提供冷启动兜底清理 sweepStaleStage",
          "fun sweepStaleStage(" in fileops)
    check("sweepStaleStage 不再无条件递归删 rxin_stage 根目录",
          sweep != ""
          and re.search(r'"rxin_stage"\s*\)\s*\.deleteRecursively', sweep) is None)
    check("sweepStaleStage 按遗留判定（时间/锁/pid/租约）挑条目，而不是见谁删谁",
          any(k in sweep for k in ("lastModified", "currentTimeMillis", "nanoTime", "pid",
                                   "lock", "Lock", "lease", "stale", "active", "running")))
    check("RxinApp 冷启动在后台线程调用 sweepStaleStage（不阻塞主线程）",
          "sweepStaleStage" in src["rxinapp"]
          and re.search(r"Thread\s*\(|daemon|Runnable", src["rxinapp"]) is not None)
    check("MainActivity.onCreate 不再清空全局暂存目录",
          "clearStage" not in main_create and "rxin_stage" not in main_create)
    check("ImageViewerActivity.onDestroy 不再清空全局暂存根目录",
          "clearStage" not in on_destroy_image and '"rxin_stage"' not in on_destroy_image)
    check("TextViewerActivity.onDestroy 不再清空全局暂存根目录",
          "clearStage" not in on_destroy_text and '"rxin_stage"' not in on_destroy_text)
    check("ImageViewerActivity 仅在本实例真正退出（isFinishing）时释放自有暂存",
          "isFinishing" in on_destroy_image
          and re.search(r"(?:release|cleanup|delete|discard)\w*\s*\(", on_destroy_image) is not None)
    check("TextViewerActivity 仅在本实例真正退出（isFinishing）时释放自有暂存",
          "isFinishing" in on_destroy_text
          and re.search(r"(?:release|cleanup|delete|discard)\w*\s*\(", on_destroy_text) is not None)
    check("ImageViewerActivity 支持 EXTRA_STAGED_DIR 接收调用方移交的暂存目录",
          "const val EXTRA_STAGED_DIR" in src["image"]
          and "getStringExtra(EXTRA_STAGED_DIR)" in src["image"])
    check("TextViewerActivity 支持 EXTRA_STAGED_DIR 接收调用方移交的暂存目录",
          "const val EXTRA_STAGED_DIR" in src["textviewer"]
          and "getStringExtra(EXTRA_STAGED_DIR)" in src["textviewer"])
    check("查看器真正退出时同时释放本实例暂存与调用方交来的目录",
          "releaseStagedDirs()" in on_destroy_image and "handedStagedDir" in on_destroy_image
          and "releaseStagedDirs()" in on_destroy_text and "handedStagedDir" in on_destroy_text)
    check("MainActivity 启动预览时把暂存目录所有权通过 EXTRA_STAGED_DIR 移交",
          re.search(r"putExtra\((?:ImageViewerActivity|TextViewerActivity)\.EXTRA_STAGED_DIR",
                    src["main"]) is not None)
    check("ArchiveSheet 启动预览时把暂存目录所有权通过 EXTRA_STAGED_DIR 移交",
          re.search(r"putExtra\((?:ImageViewerActivity|TextViewerActivity)\.EXTRA_STAGED_DIR",
                    src["archivesheet"]) is not None)
    check("ArchiveRepository.compress 的 ROOT 落地暂存路径每次唯一",
          re.search(r"\bnewStageDir\s*\(", compress) is not None
          or (re.search(r'"rxin_stage/[^"]*"', compress) is not None
              and re.search(r"(nanoTime|currentTimeMillis|UUID|randomUUID)",
                            compress) is not None))
    check("暂存目录新建入口把进程身份与时间写进目录名（跨进程清理能判断创建者是否存活）",
          re.search(r"fun newStageDir\([\s\S]{0,1200}?"
                    r"(?:myPid|Process\.myPid|nanoTime|currentTimeMillis|pid)",
                    fileops) is not None)
    check("ArchiveRepository.compress 不再写固定的 rxin_stage/<输出名>（并发同名会互相覆盖）",
          re.search(r'rxin_stage/\$\{output\.name\}', compress) is None)
    check("ArchiveRepository.compress 的暂存目录在 finally 里整体清理",
          "finally" in compress and "deleteRecursively" in compress)
    check("stageToCache 返回「每次操作独占的唯一目录」下的副本",
          stage_to_cache != ""
          and re.search(r"\bnewStageDir\s*\(|(?:nanoTime|currentTimeMillis|UUID|randomUUID)",
                        stage_to_cache) is not None)
    check("全工程没有任何地方把 rxin_stage 根目录整体递归删除（%s）"
          % (root_stage_deletes or "无"),
          not root_stage_deletes)

    # =================================================================== 6
    section("BUG-006：书签改为跨进程文件锁内的读改写事务（不再整表覆盖 SharedPreferences）")
    #
    # 旧实现：两个进程各自 SharedPreferences 缓存 load→改→save 整表，异步 apply，
    # 没有任何跨进程串行化。修复必须是：文件持久化 + FileChannel.lock + tmp/fsync/
    # renameTo 原子替换 + 旧 SP 一次性迁移。
    check("BookmarkStore 不再用 SharedPreferences 写书签（无 putString）",
          "putString" not in bookmark)
    check("书签数据落到 filesDir 下的 rouxin_bookmarks.json",
          "filesDir" in bookmark and "rouxin_bookmarks.json" in raw["bookmark"])
    check("存在跨进程文件锁（FileChannel / FileOutputStream.channel 的 lock()）",
          (("FileChannel" in bookmark) or (".channel" in bookmark))
          and ".lock()" in bookmark)
    check("存在锁包住读改写的 withLock / transaction 助手",
          re.search(r"\b(?:withLock|transaction)\s*\(", bookmark) is not None)
    op_attr = {
        "add": ("fun add(", r"\b(?:withLock|transaction|modify|update|mutate|writeLocked)\s*\("),
        "take": ("fun take(", r"\b(?:withLock|transaction|modify|update|mutate|writeLocked)\s*\("),
        "insert": ("fun insert(", r"\b(?:withLock|transaction|modify|update|mutate|writeLocked)\s*\("),
        "rename": ("fun rename(", r"\b(?:withLock|transaction|modify|update|mutate|writeLocked)\s*\("),
    }
    op_bodies = {}
    for op, (marker, locked) in op_attr.items():
        body = body_of(bookmark, marker)
        op_bodies[op] = body
        check("%s 的读改写在同一个锁事务内完成" % op,
              body != "" and re.search(locked, body) is not None)
    check("add/take/insert/rename 不再各自 load + save（整表覆盖）",
          all(body != "" and "save(context" not in body for body in op_bodies.values()))
    check("写入路径是 tmp 文件 + fsync + renameTo 原子替换",
          ".tmp" in raw["bookmark"]
          and ("fd.sync()" in bookmark or ".force(true)" in bookmark)
          and ("renameTo" in bookmark or "ATOMIC_MOVE" in bookmark))
    check("同进程多线程有 @Synchronized 保护（避免 FileLock 重叠异常）",
          "@Synchronized" in bookmark)
    load_body = body_of(bookmark, "fun load(")
    check("读也在锁内完成（load 不会读到写了一半的文件）",
          load_body != "" and re.search(r"\b(?:withLock|transaction)\s*\(", load_body) is not None)
    save_body = body_of(bookmark, "fun save(")
    check("save(context, list) 保留且同样走锁内原子写（公开 API 兼容）",
          save_body != ""
          and re.search(r"\b(?:withLock|transaction|writeUnlocked|renameTo)\b", save_body) is not None)
    migration_guard = (
        re.search(r"exists\(\)[\s\S]{0,400}?getSharedPreferences", bookmark) is not None
        or re.search(r"(?i)migrat", bookmark) is not None
        or re.search(r"(?i)legacy", bookmark) is not None
    )
    check("旧 SharedPreferences 只做一次性迁移（数据文件不存在时才读旧值）",
          "getSharedPreferences" in bookmark and migration_guard)
    check("读入口 load 不再直接读 SharedPreferences（迁移只发生在数据文件缺失时）",
          "getSharedPreferences" not in body_of(bookmark, "fun load("))

    # =================================================================== 7
    section("BUG-007：冲突/唯一命名判据必须识别悬空符号链接")
    #
    # File.exists() 跟随符号链接：目标是悬空链接时返回 false，于是 SKIP 不跳、
    # RENAME 不换名、uniqueTarget 把被占用的路径当成空位；普通文件写入还会
    # 沿链接写穿到链接指向的位置。
    copy_one = body_of(fileops, "private fun copyOne(")
    targets_safe = body_of(fileops, "private fun targetsAreSafe(")
    unique_target = body_of(fileops, "fun uniqueTarget(")
    unique_dir = body_of(fileops, "fun uniqueDir(")
    copy_entry = body_of(fileops, "private fun copyEntry(")
    check("copyOne 的冲突判断改走 entryExists（不再只调 finalTarget.exists()）",
          "entryExists(finalTarget)" in copy_one
          and re.search(r"if\s*\(\s*finalTarget\.exists\(\)\s*\)", copy_one) is None)
    check("copyOne 的 RENAME 选名同样走 entryExists（悬空链接也算被占用）",
          copy_one.count("entryExists(finalTarget)") >= 1
          and re.search(r"Conflict\.RENAME[\s\S]{0,300}?entryExists", copy_one) is not None)
    check("targetsAreSafe 预检同样走 entryExists",
          "entryExists(finalTarget)" in targets_safe)
    check("move() 的目标冲突判断改走 entryExists",
          "entryExists(finalTarget)" in move_body
          and re.search(r"if\s*\(\s*finalTarget\.exists\(\)\s*\)", move_body) is None)
    check("uniqueTarget 用 entryExists（悬空链接视为已占用）",
          unique_target != "" and "entryExists(" in unique_target
          and re.search(r"\.exists\(\)", unique_target) is None)
    check("uniqueDir 用 entryExists（悬空链接视为已占用）",
          unique_dir != "" and "entryExists(" in unique_dir)
    # 只看**普通文件**写入那一段（最后一段）：源是符号链接的分支里也会提到
    # target 链接，拿整份函数体判会误绿（踩过）。
    normal_marker = copy_entry.rfind("onEntry(source.absolutePath)")
    normal_segment = copy_entry[normal_marker:] if normal_marker >= 0 else copy_entry
    symlink_target = re.search(r"isSymbolicLink\(\s*target\.toPath\(\)\s*\)", normal_segment)
    stream_index = normal_segment.find("FileOutputStream(target)")
    check("普通文件写入前处理目标符号链接（不跟随目标链接写穿）",
          symlink_target is not None and stream_index >= 0
          and symlink_target.start() < stream_index)
    check("FileOps 的复制链路里不再出现裸 finalTarget.exists()",
          "finalTarget.exists()" not in fileops)
    check("entryExists 本身把符号链接计入「已占用」",
          re.search(r"fun entryExists\([\s\S]{0,700}?isSymbolicLink", fileops) is not None)

    print()
    print("结果: %d 项通过, %d 项失败" % (total - failed, failed))
    if skipped:
        print("      %d 项因开源版不含 HTTP 模块而跳过（完整版会执行）。" % skipped)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
