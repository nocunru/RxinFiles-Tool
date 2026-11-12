#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
文本查看器「大文件可编辑」这条链路上的**源码不变量**断言。

为什么要有它：这一轮改的是「判据」——把"能不能编辑"从"文件多大"里摘出来，
改成"一次给控件多少字符"。这种改动最容易出现的情况是**改了一半**：
入口放开了，但保存还是拿整份文本 `toByteArray` 覆盖；或者翻片时忘了把
上一个编辑框里的内容收起来。这些都不会编译报错、也不会立刻崩，
只会在真机上某一次保存之后把文件写坏。所以把契约钉在源码上。

判据全部「从源码读事实」，不是复制一份实现；凡判"不许出现某标识符"的，
一律**先剥注释再比**（在注释里解释"为什么不这么做"不该把断言弄红 —— 踩过）。

  用法：python3 tools/textslice-check/verify-textslice.py
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MAIN = os.path.join(ROOT, "app", "src", "main")

SLICER = os.path.join(MAIN, "java", "com", "rxinfiles", "tool", "TextSlicer.java")
VIEWER = os.path.join(MAIN, "java", "com", "rxinfiles", "tool", "TextViewerActivity.kt")
REPOSITORY = os.path.join(MAIN, "java", "com", "rxinfiles", "tool", "TextFileRepository.kt")
CODEC = os.path.join(MAIN, "java", "com", "rxinfiles", "tool", "TextCodec.kt")
LAYOUT = os.path.join(MAIN, "res", "layout", "activity_text_viewer.xml")
JAVA_CHECK = os.path.join(ROOT, "tools", "textslice-check", "TextSliceCheck.java")

total = 0
failed = 0


def check(name, ok):
    global total, failed
    total += 1
    if ok:
        print("  ok    " + name)
    else:
        failed += 1
        print("  FAIL  " + name)


def section(title):
    print()
    print("== " + title + " ==")


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def strip_comments(src):
    """去掉 // 与 /* */（保留字符串字面量里的内容）。

    判"不许出现某标识符"之前必须先过这一道：源码里解释"为什么**不**写 exit"
    的注释会让断言永远红。
    """
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            j = src.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            j = src.find("\n", i)
            i = n if j < 0 else j
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


def body_of(src, marker):
    """取一个函数/方法的完整函数体。

    必须精确到"这一层结束的那个右花括号"——按固定长度截会截到隔壁函数去：
    例如 `TextCodec.bomLength` 后面紧跟着 `isValidUtf8`，那里面的 `and 0xFF`
    会被当成 BOM 判据的一部分，于是跨文件比对永远对不上（踩过）。
    """
    i = src.find(marker)
    if i < 0:
        return ""
    j = src.find("\n    }", i)
    return src[i:j + 5] if j > 0 else src[i:]


def main():
    slicer_raw = read(SLICER)
    viewer_raw = read(VIEWER)
    codec_raw = read(CODEC)
    layout = read(LAYOUT)
    java_check = read(JAVA_CHECK)

    slicer = strip_comments(slicer_raw)
    viewer = strip_comments(viewer_raw)
    # TextCodec 也要剥：它的 KDoc 里正写着"那套 MAX_FULL_BYTES 已经删掉了"——
    # 判"不许出现某标识符"时不剥注释，就会被这句说明本身判红（又踩一次）
    codec = strip_comments(codec_raw)

    # ---------------------------------------------------------------- 1
    section("1. TextSlicer 必须保持纯 Java（否则离线断言就跑不了）")
    check("TextSlicer 不 import android", "import android" not in slicer)
    check("TextSlicer 不引用 android.* 类型", "android." not in slicer)
    check("TextSlicer 不用 Context", "Context" not in slicer)
    check("TextSlicer 不写日志到 android.util.Log", "Log." not in slicer)
    check("TextSlicer 确实用 RandomAccessFile 按范围读（不是整份读进来）",
          "RandomAccessFile" in slicer)
    check("TextSlicer 提供 scan / readSlice / merge / expectedLines 四件事",
          all(k in slicer for k in
              ("static Layout scan(", "static String readSlice(", "static void merge(",
               "static int expectedLines(")))

    # ---------------------------------------------------------------- 2
    section("2. BOM 判据必须与 TextCodec 逐条一致（两处定义，最怕改一边）")
    def bom_bytes(src, marker):
        """只取「与某个字节常量比较」的那些值。

        Java 那边写成 `(head[0] & 0xFF) == 0xEF` —— 那个 `0xFF` 是掩码、
        不是 BOM 判据的一部分，按「所有 0x.. 字面量」抓会把它一起抓进来
        （踩过：两边序列变成 `FF EF FF BB ...` vs `EF BB BF ...`，怎么都对不上）。
        """
        seg = body_of(src, marker)
        return re.findall(r"==\s*0x([0-9A-Fa-f]{2})", seg)

    codec_bom = bom_bytes(codec_raw, "fun bomLength")
    slicer_bom = bom_bytes(slicer_raw, "static int bomLength")
    check("TextCodec 里取到了 BOM 判据", len(codec_bom) >= 6)
    check("TextSlicer 里取到了 BOM 判据", len(slicer_bom) >= 6)
    check("两边的字节判据序列完全相同：%s" % " ".join(slicer_bom[:7]),
          codec_bom[:7] == slicer_bom[:7])
    check("UTF-8 的 EF BB BF 在两边都是第一条",
          codec_bom[:3] == ["EF", "BB", "BF"] and slicer_bom[:3] == ["EF", "BB", "BF"])

    # ---------------------------------------------------------------- 3
    section("3. 解码策略：坏字节变 U+FFFD，不抛异常")
    check("TextSlicer 解码用 REPLACE（malformed 与 unmappable 各一处）",
          slicer_raw.count("CodingErrorAction.REPLACE") >= 2)
    check("解码整个失败时退回 ISO-8859-1（与旧 read() 的兜底一致，最少丢信息）",
          "ISO_8859_1" in slicer)
    check("TextSlicer 的解码入口带 charset 参数（不写死编码）",
          "static String decode(byte[] bytes, String charsetName)" in slicer)

    # ---------------------------------------------------------------- 4
    section("4. 不再有「文件大就禁编辑」这套错判据")
    for gone in ("编辑已禁用", "result.truncated", "MAX_FULL_BYTES", "PREVIEW_BYTES",
                 "DISPLAY_LIMIT_NORMAL", "DISPLAY_LIMIT_TRUNCATED", "canReadFully"):
        check("TextViewerActivity 里不再出现 %s" % gone, gone not in viewer)
    check("TextCodec 里那套截断式读取也一并删了",
          "MAX_FULL_BYTES" not in codec and "fun read(" not in codec
          and "trimPartialTail" not in codec)
    check("TextCodec 里那套覆盖写也删了（改由 writeBack 负责）",
          "fun write(" not in codec and "writeWithRoot" not in codec)
    check("TextCodec 剩下的都是真被用到的探测/工具",
          all(k in codec for k in ("fun detect(", "fun charsetOf(", "val SUPPORTED",
                                   "fun bomLength(", "fun countLines(")))
    check("改由「单片字符数」把关：SLICE_CHARS 在", "SLICE_CHARS" in viewer)
    check("切片上限就是 SLICE_CHARS（不是别的数）",
          "TextSlicer.scan(src, name, SLICE_CHARS)" in viewer)
    enter = body_of(viewer, "private fun enterEditMode()")
    check("enterEditMode 里没有任何「太大就不给编辑」的分支",
          "truncated" not in enter and "已禁用" not in enter and "return" in enter)
    check("enterEditMode 第一件事是确认已经加载出切片",
          "val layout = sliced ?: return" in enter)

    # ---------------------------------------------------------------- 5
    section("5. 保存 = 冲突检测 + 完整暂存 + 原子替换")
    repository = strip_comments(read(REPOSITORY))
    write_back = body_of(repository, "fun write(")
    direct = body_of(repository, "private fun commitDirect(")
    root = body_of(repository, "private fun commitWithRoot(")
    check("查看器与 HTTP 共用 TextFileRepository 写回",
          "TextFileRepository.write(" in viewer)
    check("用 TextSlicer.merge 生成完整内容", "TextSlicer.merge(" in write_back)
    check("用 TextSlicer.expectedLines 算预期", "TextSlicer.expectedLines(" in write_back)
    check("用 TextSlicer.countLines 核对实际", "TextSlicer.countLines(" in write_back)
    check("核对不过就中止（返回 WRITE_VERIFY_FAILED，不碰原文件）",
          "WRITE_VERIFY_FAILED" in write_back)
    check("合并前与提交前都检查目标版本",
          write_back.count("snapshot(target) != session.targetVersion") >= 1
          and "snapshot(target) != expected" in direct and "snapshot(target) != expected" in root)
    check("同目录暂存并 fsync", "FileOutputStream(staged)" in direct and "output.fd.sync()" in direct)
    check("原文件先改名备份，再原子 rename 暂存文件",
          "target.renameTo(backup)" in direct and "staged.renameTo(target)" in direct)
    check("不再先截断原文件", "FileOutputStream(target, false)" not in repository)
    check("ROOT 也走同目录暂存与原子 rename",
          "RootShell.succeeded(" in root and "if mv " in root)
    check("旋转保存所有编辑片与当前片",
          "onSaveInstanceState" in viewer and "STATE_EDITS" in viewer and "STATE_SLICE" in viewer)

    # ---------------------------------------------------------------- 6
    section("6. 跨片的改动一个都不能丢")
    exit_fn = body_of(viewer, "private fun confirmExit()")
    check("退出确认按「有没有未保存改动」判，不是按编辑框脏没脏",
          "hasUnsavedEdits()" in exit_fn and "editing && dirty" not in exit_fn)
    check("hasUnsavedEdits 把 editedSlices 也算进去",
          "editedSlices.isNotEmpty()" in body_of(viewer, "private fun hasUnsavedEdits()"))
    goto = body_of(viewer, "private fun goToSlice(")
    check("翻片之前先把编辑框里的内容收起来", "stashEditingSlice()" in goto)
    save = body_of(viewer, "private fun save(")
    check("保存之前先把编辑框里的内容收起来", "stashEditingSlice()" in save)
    check("收集起来的是「片号 → 文本」", "editedSlices[sliceIndex] = contentEdit.text.toString()"
          in body_of(viewer, "private fun stashEditingSlice()"))
    discard = body_of(viewer, "private fun discardEdits()")
    check("「放弃」要清掉所有片的改动", "editedSlices.clear()" in discard)

    # ---------------------------------------------------------------- 7
    section("7. 片导航：布局里有、代码里用、单片时不出现")
    for ident in ("sliceBar", "sliceInfo", "slicePrev", "sliceNext"):
        check("布局里有 @+id/%s" % ident, ('@+id/%s"' % ident) in layout)
        check("代码里引用了 R.id.%s" % ident, ("R.id.%s" % ident) in viewer)
    check("片导航条默认 gone（单片文件看不到它）",
          re.search(r'android:id="@\+id/sliceBar"[\s\S]{0,400}?android:visibility="gone"',
                    layout) is not None)
    check("只有真的切成多片才显示",
          "layout.size() <= 1" in body_of(viewer, "private fun updateSliceBar()"))
    check("片被改动过时导航条上有标记",
          "已改" in body_of(viewer, "private fun updateSliceBar()"))

    # ---------------------------------------------------------------- 8
    section("8. 分片之后的几处「视野内」语义必须跟着改")
    check("查找列表报的是全文行号（不是片内行号）",
          "startLine ?: 1" in body_of(viewer, "private fun lineNumbersFor("))
    check("不是多片就仍说「查找内容」，是多片才说「在本片内」",
          "在本片内查找" in viewer and "findInput.hint" in viewer)
    check("多片时提供跨片查找入口", "全文查找…" in viewer and "showWholeFileSearch" in viewer)
    check("多片时「复制全部内容」改称「复制本片内容」，不误导",
          "复制本片内容" in viewer)
    check("跳行会先判超范围并明说，而不是悄悄夹到底部",
          "只有 ${layout.totalLines} 行" in viewer or "这个文件只有" in viewer)

    # ---------------------------------------------------------------- 9
    section("9. 支持的编码都有覆盖（不许有编得出来却没人测的）")
    m = re.search(r"SUPPORTED\s*=\s*listOf\(([^)]*)\)", codec_raw)
    supported = re.findall(r'"([^"]+)"', m.group(1)) if m else []
    check("从 TextCodec 读到了支持编码表（%d 项）" % len(supported), len(supported) >= 4)
    missing = [c for c in supported if ('"%s"' % c) not in java_check]
    check("每一种支持编码都出现在切片断言里：%s" % (missing or "无遗漏"), not missing)

    # ---------------------------------------------------------------- 10
    section("10. 目录说明与跑法")
    readme = os.path.join(ROOT, "tools", "textslice-check", "README.md")
    check("README 在", os.path.isfile(readme))
    runsh = os.path.join(ROOT, "tools", "textslice-check", "run.sh")
    check("run.sh 在", os.path.isfile(runsh))
    if os.path.isfile(runsh):
        sh = read(runsh)
        check("run.sh 同时编 TextSlicer 与断言",
              "TextSlicer.java" in sh and "TextSliceCheck.java" in sh)

    # ---------------------------------------------------------------- 11
    section("11. 片大小与显示侧折行：真机反馈修回来的两条")
    m = re.search(r"SLICE_CHARS\s*=\s*([0-9_]+)", viewer)
    slice_chars = int(m.group(1).replace("_", "")) if m else -1
    check("读到了 SLICE_CHARS = %d" % slice_chars, slice_chars > 0)
    # 3.14.0 取 200 万，真机上的表现是「加载要等 / 滑不动 / ANR / 编辑闪退」。
    # 一屏大约 2000~5000 字符，所以上限必须与「几十屏」同量级，不能与「一整份文件」同量级。
    check("SLICE_CHARS ≤ 200000（一次给控件的字符数要与「一屏」同量级）",
          0 < slice_chars <= 200000)
    check("SLICE_CHARS ≥ 20000（切得太碎会让翻页过于频繁）", slice_chars >= 20000)

    # 基准里抄了一份 SLICE_CHARS（它不能 import Android 类型）。同一个数写两处，
    # 早晚有一处忘记改 —— 所以钉一条跨文件比对。
    bench_path = os.path.join(ROOT, "tools", "perf-check", "SliceBench.java")
    if os.path.isfile(bench_path):
        bench = strip_comments(read(bench_path))
        mb = re.search(r"SLICE_CHARS\s*=\s*([0-9_]+)", bench)
        bench_chars = int(mb.group(1).replace("_", "")) if mb else -1
        check("SliceBench 抄的那份 SLICE_CHARS（%d）与查看器一致" % bench_chars,
              bench_chars == slice_chars)
    else:
        check("SliceBench 在（基准里抄了 SLICE_CHARS，必须能比对）", False)

    m = re.search(r"LONG_LINE_WRAP\s*=\s*([0-9_]+)", viewer)
    long_line = int(m.group(1).replace("_", "")) if m else -1
    check("读到了 LONG_LINE_WRAP = %d" % long_line, long_line > 0)
    check("LONG_LINE_WRAP 远小于一屏字符数（否则等于没设这道闸门）",
          0 < long_line <= 2000)

    check("TextSlicer 提供 longestLine", "static int longestLine(" in slicer)
    longest_body = body_of(slicer_raw, "public static int longestLine(")
    check("longestLine 只把 \\n 当换行（与 scan / countNewlines 同一口径）",
          "'\\n'" in longest_body and "'\\r'" not in longest_body)

    check("折行取「用户偏好 或 超长行」两条路的或", "wrapLines || forceWrap" in viewer)
    check("forceWrap 由本片最长行决定", "sliceLongestLine > LONG_LINE_WRAP" in viewer)
    render = body_of(viewer, "private fun renderSlice(")
    check("渲染时先定折行方式再 setText（否则同一份文本要排两遍）",
          0 <= render.find("applyWrap()") < render.find("contentText.setText("))
    check("浏览侧不再用 SPANNABLE（底色是自绘的，SPANNABLE 只会白复制一整串）",
          "BufferType.SPANNABLE" not in viewer)
    goto = body_of(viewer, "private fun goToSlice(")
    check("最长行在后台线程算，不占主线程",
          0 <= goto.find("TextSlicer.longestLine(") < goto.find("runOnUiThread"))

    # ---------------------------------------------------------------- 12
    section("12. 编辑模式：只遮不毁 + 排版失败必须接得住（3.14.3 修的就是这里）")
    #
    # 3.14.2 在这里做反了：进编辑先 `contentText.setText("")` 把浏览侧**销毁**，
    # 理由是"两套文本并存 = 峰值翻倍"。结果是唯一的失败恢复路径必须重建一份
    # 十万字符 Layout，兜底自己成了崩溃点 —— 真机表现为「点编辑直接闪出阅读页」。
    # 这一节的判据因此全是"只做减法"和"把异常拉回调用栈"两条。
    enter = body_of(viewer, "private fun enterEditMode()")
    check("进编辑只把浏览侧遮起来（INVISIBLE），不销毁文本与 Layout",
          "contentText.visibility = View.INVISIBLE" in enter)
    check('进编辑不再 contentText.setText("")（省内存不能拿恢复路径当代价）',
          'contentText.setText("")' not in enter)
    check("进编辑包了 try/catch（排不下时退回来并说明，而不是让应用消失）",
          "try {" in enter and "catch (t: Throwable)" in enter)
    check("进编辑在本帧强制排一次版（排版崩溃落在下一帧，try 是接不住的）",
          "layOutEditOnce()" in enter)
    layout_once = body_of(viewer, "private fun layOutEditOnce()")
    check("layOutEditOnce 真的走 measure（DynamicLayout 就是在 onMeasure 里建的）",
          ".measure(" in layout_once and "MeasureSpec.makeMeasureSpec" in layout_once)
    check("layOutEditOnce 宽高还是 0 时退回屏幕尺寸",
          "displayMetrics.widthPixels" in layout_once)
    fail_fn = body_of(viewer, "private fun onEditLayoutFailed(")
    check("进编辑失败走统一退路", "onEditLayoutFailed(t)" in enter)
    check("退路里不重建浏览侧（3.14.2 的兜底正是死在这一步）",
          "renderSlice(" not in fail_fn)
    check("退路里也不碰浏览侧文本（只做减法）", "contentText.setText" not in fail_fn)
    check("退路把失败原因摆出来（用户截一张图就是一手证据）",
          "t.javaClass.name" in fail_fn)
    check("退路会把编辑状态摊平（回到浏览模式）", "resetEditState()" in fail_fn)
    exit_body = body_of(viewer, "private fun exitEditMode()")
    check("退出编辑不重建浏览侧内容（它从未被销毁）",
          "renderSlice(" not in exit_body and "resetEditState()" in exit_body)
    edit_wrap = body_of(viewer, "private fun applyEditWrap()")
    check("编辑框永远折行（一整片十万字符的单行会排出几十万像素宽）",
          "contentEdit.setHorizontallyScrolling(false)" in edit_wrap)
    check("编辑框折行不再跟着用户偏好走",
          "contentEdit.setHorizontallyScrolling(!wrap)" not in viewer)
    reset_edit = body_of(viewer, "private fun resetEditState()")
    check("resetEditState 也把编辑侧的文本放掉", 'contentEdit.setText("")' in reset_edit)
    check("load 走 resetEditState（不白排一次马上要被换掉的文本）",
          "resetEditState()" in body_of(viewer, "private fun load("))
    check("本片有超长行时进编辑会先说明（那是 DynamicLayout 按段落 reflow 的代价）",
          "超长行" in enter)
    slice_fn = body_of(viewer, "private fun showSlice(")
    check("编辑中翻片也强制排版（翻片换文本同样是 DynamicLayout 重建）",
          "layOutEditOnce()" in slice_fn)
    check("编辑中翻片排不下就回浏览模式并说一句，不把用户留在死状态",
          "排不进编辑框" in slice_fn and "renderSlice()" in slice_fn)

    print()
    print("结果: %d 项通过, %d 项失败" % (total - failed, failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
