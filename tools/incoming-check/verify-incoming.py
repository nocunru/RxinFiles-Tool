#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
「用其他应用打开」这条链路的**源码不变量**断言。

为什么要有它：这条链路吃的是对方 App 可控的输入，而它又必须 `exported="true"`
才能出现在「用其他应用打开」里 —— 等于对设备上任何 App 开放。一旦有人在后面
「顺手」加一句 `File(uri.path)`、或者把流式复制改成 `readBytes()`、或者把
scheme 校验删掉、或者把「定位高亮」写进多选集合，**都不会编译报错**，
而且要在真机上被恶意输入踩中才会暴露。所以把契约钉在源码上。

判据全部「从源码读事实」，不是复制一份实现；凡判「不许出现某标识符」的，
一律**先剥注释再比**（在注释里解释「为什么不这么做」不该把断言弄红 —— 踩过）。

  用法：python3 tools/incoming-check/verify-incoming.py
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MAIN = os.path.join(ROOT, "app", "src", "main")
PKG = os.path.join(MAIN, "java", "com", "rxinfiles", "tool")

INCOMING_JAVA = os.path.join(PKG, "IncomingFile.java")
ACTIVITY = os.path.join(PKG, "IncomingActivity.kt")
SCRIPT_RUNNER = os.path.join(PKG, "ScriptRunner.kt")
MAIN_ACTIVITY = os.path.join(PKG, "MainActivity.kt")
FILEREPO = os.path.join(PKG, "FileRepository.kt")
FILELISTADAPTER = os.path.join(PKG, "FileListAdapter.kt")
ARCHIVES = os.path.join(PKG, "Archives.java")
MANIFEST = os.path.join(MAIN, "AndroidManifest.xml")
LAYOUT = os.path.join(MAIN, "res", "layout", "activity_incoming.xml")
FAIL_LAYOUT = os.path.join(MAIN, "res", "layout", "sheet_incoming_failure.xml")
GRADLE = os.path.join(ROOT, "app", "build.gradle.kts")
JAVA_CHECK = os.path.join(ROOT, "tools", "incoming-check", "IncomingCheck.java")

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

    判「不许出现某标识符」之前必须先过这一道：源码里解释「为什么**不**这么做」
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


def body_of(src, marker, end="\n    }"):
    i = src.find(marker)
    if i < 0:
        return ""
    j = src.find(end, i)
    return src[i:j + len(end)] if j > 0 else src[i:]


def strip_xml_comments(src):
    """去掉 XML 的 <!-- --> 注释。

    与 strip_comments 同一个道理、却常被忘掉的一半：布局头部注释里解释
    「文案为什么不写死在 XML 里」本身就是含 Shizuku / Android/data 的句子 ——
    不剥注释去判「布局里没写死文案」，红的是注释，不是问题。
    """
    return re.sub(r"<!--[\s\S]*?-->", "", src)


def set_literals(src, name):
    m = re.search(re.escape(name) + r"\s*=\s*set\(([\s\S]*?)\);", src)
    return re.findall(r'"([^"]*)"', m.group(1)) if m else []


def main():
    incoming_raw = read(INCOMING_JAVA)
    activity_raw = read(ACTIVITY)
    main_activity_raw = read(MAIN_ACTIVITY)
    filerepo_raw = read(FILEREPO)
    filelist_raw = read(FILELISTADAPTER)
    archives_raw = read(ARCHIVES)
    manifest = read(MANIFEST)
    layout = read(LAYOUT)
    fail_layout = read(FAIL_LAYOUT)
    gradle = read(GRADLE)

    incoming = strip_comments(incoming_raw)
    activity = strip_comments(activity_raw)
    filerepo = strip_comments(filerepo_raw)
    filelist = strip_comments(filelist_raw)
    main_activity = strip_comments(main_activity_raw)

    # ---------------------------------------------------------------- 1
    section("1. IncomingFile 必须保持纯 Java，且判据件与「定位+高亮」一致")
    # 先摘掉可能出现的 apk MIME 字面量：它自己就写作
    # `application/vnd.android.package-archive`，直接比 `android.` 会被它自己判红。
    incoming_no_mime = incoming.replace("application/vnd.android.package-archive", "")
    check("IncomingFile 不 import android", "import android" not in incoming)
    check("IncomingFile 不引用 android.* 类型", "android." not in incoming_no_mime)
    check("IncomingFile 不用 Context", "Context" not in incoming)
    check("IncomingFile 不写日志到 android.util.Log", "Log." not in incoming)
    check("IncomingFile 提供判据件（反解可信度 + 消毒 + scheme + 嗅探）",
          all(k in incoming for k in
              ("static boolean matchesDisplayName(", "static String lastSegment(",
               "static String safeName(", "static boolean isHandledScheme(",
               "static boolean looksLikeText(")))
    check("IncomingFile 提供兜底复制的去重命名 uniqueName（纯 Java，可离线断言）",
          "static String uniqueName(File " in incoming)

    # 改造为「定位 + 高亮」后，按类型分派查看器的那一套已经删除 —— 不许再复活
    for gone in ("TARGET_IMAGE", "TARGET_TEXT", "TARGET_ARCHIVE", "TARGET_ASK",
                 "static int decide(", "static int targetFromMime("):
        check("IncomingFile 不再有已删的分派件 %s" % gone, gone not in incoming)

    # ---------------------------------------------------------------- 2
    section("2. 分类表只有一份：FileRepository 全部转发，不再内联")
    for gone in ("IMAGE_EXT = setOf(", "TEXT_EXT = setOf(", "TEXT_NAMES = setOf(",
                 "VIDEO_EXT = setOf(", "MEDIA_EXT = setOf(", "private fun extOf(",
                 "Archives.isArchiveName("):
        check("FileRepository 里不再有 %s" % gone, gone not in filerepo)
    for deleg in ("IncomingFile.isImageName(", "IncomingFile.isTextName(",
                  "IncomingFile.isVideoName(", "IncomingFile.isMediaName(",
                  "IncomingFile.isArchiveName(",
                  "IncomingFile.isStrictScriptName(", "IncomingFile.isAmbiguousScriptName(",
                  "IncomingFile.isScriptName(", "IncomingFile.looksLikeText("):
        check("FileRepository 转发到 %s" % deleg, deleg in filerepo)
    check("VIDEO_EXT 与受支持的视频扩展名完全一致",
          set(set_literals(incoming_raw, "VIDEO_EXT")) ==
          {"mp4", "mkv", "webm", "avi", "mov", "3gp", "flv", "m4v"})
    check("视频分类独立于媒体分类", "VIDEO_EXT = set(" in incoming
          and "mp4" not in set_literals(incoming_raw, "MEDIA_EXT"))
    check(".ts 保留 TypeScript 文本分类，不进入视频或媒体表",
          "ts" in set_literals(incoming_raw, "TEXT_EXT")
          and "ts" not in set_literals(incoming_raw, "VIDEO_EXT")
          and "ts" not in set_literals(incoming_raw, "MEDIA_EXT"))
    check("FileRepository 描述视频为「视频」", 'isVideoName(name) -> "视频"' in filerepo)
    classify_java_check = read(JAVA_CHECK)
    check("IncomingCheck 覆盖全部视频扩展名与 .ts 边界",
          all(('"%s"' % ext) in classify_java_check for ext in
              ("mp4", "mkv", "webm", "avi", "mov", "3gp", "flv", "m4v"))
          and 'IncomingFile.isTextName("a.ts")' in classify_java_check
          and '!IncomingFile.isVideoName("a.ts")' in classify_java_check)

    # ---------------------------------------------------------------- 3
    section("3. 归档识别必须委托 Archives（唯一真源），且断言不再有盲区")
    check("IncomingFile.isArchiveName 直接委托 Archives.isArchiveName",
          "return Archives.isArchiveName(name);" in incoming)
    check("IncomingFile 不再自带归档扩展名表（ARCHIVE_EXT 已删）",
          "ARCHIVE_EXT = set(" not in incoming)

    # 复合后缀仍由本类保留一份（clamp 截断时要用），钉死等于 Archives 的那几个
    suffix_incoming = set(set_literals(incoming_raw, "ARCHIVE_SUFFIX"))
    suffix_archives = set(re.findall(r'"(\.tar\.[a-z0-9]+|\.tgz)"',
                                     body_of(archives_raw, "public static boolean isArchiveName")))
    check("从 Archives 读到了复合后缀：%s" % " ".join(sorted(suffix_archives)),
          len(suffix_archives) >= 3)
    missing_suffix = sorted(suffix_archives - suffix_incoming)
    check("Archives 的复合后缀都在 IncomingFile.ARCHIVE_SUFFIX 里：%s"
          % (missing_suffix or "无遗漏"), not missing_suffix)

    # 断言盲区补丁：上一轮回归之所以漏网，是因为归档断言只用了 `a.zip` 这类名字。
    # 这里强制 IncomingCheck 覆盖「点开头单扩展名」样本，并直接与 Archives 比对。
    java_check = read(JAVA_CHECK)
    for sample in (".zip", ".tar", ".gz", ".rar", ".7z", ".bz2", ".xz", ".lzma"):
        check("IncomingCheck 覆盖点开头单扩展名样本 %s" % sample,
              ('"%s"' % sample) in java_check)
    check("IncomingCheck 直接与 Archives 做逐条比对（口径漂回会变红）",
          "Archives.isArchiveName(" in java_check)
    check("IncomingCheck 覆盖反解路径可信度（matchesDisplayName）",
          "matchesDisplayName(" in java_check)
    check("IncomingCheck 覆盖兜底复制的去重命名（uniqueName）",
          "IncomingFile.uniqueName(" in java_check)

    # ---------------------------------------------------------------- 4
    section("4. IncomingActivity：只做胶水，反解路径，绝不拿 uri 当文件路径")
    check("注册了 activity_incoming 布局", "R.layout.activity_incoming" in activity)
    check("用 ContentResolver 打开输入流（而不是 File(uri)）",
          "contentResolver.openInputStream" in activity)
    check("用 OpenableColumns 取显示名", "OpenableColumns.DISPLAY_NAME" in activity)
    check("用 OpenableColumns 取大小", "OpenableColumns.SIZE" in activity)
    check("文件名经 IncomingFile.safeName 消毒", "IncomingFile.safeName(" in activity)
    check("**没有** File(uri.path) 这种经典漏洞", "File(uri.path" not in activity)

    # 反解路径必须过「名字相符」这道闸（宁可判失败，也不猜一个像的路径）
    check("反解出的候选路径经 IncomingFile.matchesDisplayName 核对",
          "IncomingFile.matchesDisplayName(" in activity)
    check("content:// 反解尝试 _data 列", '"_data"' in activity)

    # 这个入口**不再分派查看器**：只定位，不打开
    for forbidden in ("ImageViewerActivity", "VideoViewerActivity",
                      "TextViewerActivity", "ArchiveSheet"):
        check("IncomingActivity 不再分派查看器 %s" % forbidden, forbidden not in activity)

    # 复制兜底落在 Download，而不是内部 cacheDir（用户要能自己找到）
    check("复制兜底落在 Download 目录", "Environment.DIRECTORY_DOWNLOADS" in activity)
    # 3.15.2：再下沉一层到专属子目录，别和用户自己的下载物混在一起。
    # 目录名只在 IncomingFile.COPY_DIR_NAME 里定义一次，Activity 引用它而不是写死字符串。
    check("复制兜底用 IncomingFile.COPY_DIR_NAME 取子目录名",
          "IncomingFile.COPY_DIR_NAME" in activity)
    # 普通身份定位失败时必须给「装 Shizuku」的出路（3.15.2）。判据按身份分流：
    # 只有 needsShellHint 为真才显示 —— 已有 ROOT/ADB 的用户不该被劝着装 Shizuku。
    check("失败面板按 needsShellHint 决定是否给 Shizuku 提示",
          "IncomingFile.needsShellHint(" in activity)
    check("Shizuku 提示的链接取自 IncomingFile.SHIZUKU_DOWNLOAD_URL（不写死）",
          "IncomingFile.SHIZUKU_DOWNLOAD_URL" in activity)
    check("Shizuku 下载页用 ACTION_VIEW 交给系统（不硬编码浏览器包名）",
          "Intent.ACTION_VIEW" in activity and "setPackage" not in activity)
    check("没有浏览器时明确告知，不静默",
          "没有可用的浏览器" in activity)
    check("**不再**落在 cacheDir/rxin_stage（那是旧的暂存方案）",
          "rxin_stage" not in activity)

    # 去重命名必须待在纯 Java 可断言层，不能留在 Activity 私有方法里（那样等于没测）
    check("去重命名已下沉：Activity 不再自带 uniqueTarget", "uniqueTarget" not in activity)
    check("Activity 改用 IncomingFile.uniqueName 取名",
          "IncomingFile.uniqueName(" in activity)

    # 可见性必须按「真正去读它的身份」判，不能只信 File.canRead()
    check("可见性用 FileRepository.listDir 判（shell/Java 双通道）",
          "FileRepository.listDir(" in activity)
    check("**没有**用 File.canRead() 判可见性（会漏掉 ROOT 才进得去的目录）",
          ".canRead(" not in activity)

    # 流式复制：8KB 缓冲 + 循环 read，不能整份读进内存
    check("用 8KB 缓冲流式复制（ByteArray(8192)）", "ByteArray(8192)" in activity)
    check("**没有**把整份内容读进内存（readBytes）",
          "readBytes(" not in activity and ".readBytes" not in activity)
    check("复制循环用的是 read/write（分块）",
          "from.read(buffer)" in activity and "to.write(buffer" in activity)
    check("输入/输出流都 use{} 关闭（异常路径也关得掉）",
          "input.use {" in activity and "FileOutputStream(created, true).use {" in activity)
    check("目标用 createNewFile 原子占用，避免命名与写入之间撞名",
          "candidate.createNewFile()" in activity)
    check("Cursor 用 use{} 关闭", "?.use { cursor ->" in activity)

    # ---------------------------------------------------------------- 5
    section("5. 可信边界：只认 content/file，且不信任任何 extra")
    check("用 IncomingFile.isHandledScheme 白名单校验",
          "IncomingFile.isHandledScheme(" in activity)
    check("**不读任何 String extra**（getStringExtra 不出现）",
          "getStringExtra" not in activity)
    check("**不读任何 Parcelable/Serializable extra**",
          "getParcelableExtra" not in activity and "getSerializableExtra" not in activity)
    check("file:// 分支把 uri.path 当权威路径取用",
          '"file".equals(uri.scheme' in activity and "uri.path" in activity)
    check("兼容 ClipData（多选分享只塞 clipData，至少不崩）",
          "clipData" in activity and "getItemAt" in activity)

    # ---------------------------------------------------------------- 6
    section("6. 这个入口绝不执行脚本，也不打开文件")
    for forbidden in ("TerminalActivity", "ScriptRunner", "ScriptPipe", "confirmScriptRun",
                      "ShellExecutor"):
        check("IncomingActivity 不引用执行链路 %s" % forbidden, forbidden not in activity)
    # 判据层也不能有「执行」这个目标
    for forbidden in ("TARGET_EXECUTE", "TARGET_RUN", "execute("):
        check("IncomingFile 里没有 %s" % forbidden, forbidden not in incoming)

    # ---------------------------------------------------------------- 7
    section("7. Manifest：ACTION_VIEW + DEFAULT + mimeType */*，且 exported")
    m = re.search(r"<activity[^>]*IncomingActivity[\s\S]*?</activity>", manifest)
    block = m.group(0) if m else ""
    check("找到了 IncomingActivity 的声明块", bool(block))
    check("exported=\"true\"", 'android:exported="true"' in block)
    check("注册了 ACTION_VIEW", 'android:name="android.intent.action.VIEW"' in block)
    check("注册了 CATEGORY_DEFAULT", 'android:name="android.intent.category.DEFAULT"' in block)
    check("data mimeType = */*", 'android:mimeType="*/*"' in block)
    check("**没有** ACTION_SEND（只做 VIEW，不做分享）",
          "android.intent.action.SEND" not in block)
    check("**没有** BROWSABLE（不做网页链接入口）",
          "android.intent.category.BROWSABLE" not in block)
    # 布局里的 id 代码要真的用到
    check("布局里有 incomingStatus", '@+id/incomingStatus"' in layout)
    check("代码里引用了 R.id.incomingStatus", "R.id.incomingStatus" in activity)

    # ---------------------------------------------------------------- 8
    section("8. MainActivity：在 onCreate 与 onNewIntent 都支持「定位 + 高亮」")
    check("MainActivity 定义了 EXTRA_REVEAL_PATH",
          'EXTRA_REVEAL_PATH = "reveal_path"' in main_activity_raw)
    check("onCreate 消费 EXTRA_REVEAL_PATH",
          "getStringExtra(EXTRA_REVEAL_PATH)" in main_activity_raw)
    check("重写了 onNewIntent（复用实例时也要能定位）",
          "override fun onNewIntent(" in main_activity_raw)
    check("onNewIntent 也消费 EXTRA_REVEAL_PATH",
          main_activity_raw.count("getStringExtra(EXTRA_REVEAL_PATH)") >= 2)
    # 光有 onNewIntent 还不够：主界面是标准 launchMode，若不请求「复用实例」，
    # 系统每次都新开一个实例，onNewIntent 永远不被调到 —— 那半句要求就是空话。
    check("转交主界面时请求复用实例（SINGLE_TOP / CLEAR_TOP）",
          "FLAG_ACTIVITY_SINGLE_TOP" in activity and "FLAG_ACTIVITY_CLEAR_TOP" in activity)
    check("转交主界面时回到本应用任务（NEW_TASK）",
          "FLAG_ACTIVITY_NEW_TASK" in activity)
    check("定位后滚动到目标行", "scrollToPosition(" in main_activity_raw)
    check("定位经 adapter.setReveal（独立通道）", "setReveal(" in main_activity_raw)

    # 冷启动竞态（QA 报的缺陷）：onCreate→onStart→onResume 跑在同一条主线程消息里，
    # 而 loadDir 里 `currentPath = path` 要到 IO 续体回来才赋值。若 revealFile 不同步落定
    # currentPath，onResume/detectShellLevel 的 loadDir 就会拿默认目录去覆盖/误消费待高亮。
    reveal_body = body_of(main_activity, "private fun revealFile(")
    check("revealFile 里【同步】写 currentPath（否则 onResume 会盖掉定位结果）",
          "currentPath = parent" in reveal_body)
    check("revealFile 到别的目录时补上退出多选（同步改 currentPath 会让 loadDir 的 pathChanged 恒假）",
          "exitSelection()" in reveal_body)
    # 判据本身也要钉住：只判「含 exitSelection()」挡不住「把换目录判据改成恒 true / 恒 false」——
    # 那样要么每次都退多选（同目录也退），要么永远不退（把选中项带进新目录）。
    check("revealFile 的换目录判据是 parent != currentPath（不是恒真/恒假）",
          "parent != currentPath" in reveal_body)
    check("退出多选以 switchingDir 为条件（不是无条件 exitSelection）",
          "switchingDir && selectionMode" in reveal_body)
    # ↑ 这两条只钉「子串在不在」。真正能把修4 打回原形的变异是【换位置】：
    #   把 `val switchingDir = parent != currentPath` 挪到 `currentPath = parent` 之后 ——
    #   两条子串都还在，断言却全绿，而 switchingDir 恒为 false、多选永不退出（正是修4 要防的 bug）。
    #   所以判据本身还要钉两件事：(1) 它确实是一次 `val switchingDir = parent != currentPath` 声明；
    #   (2) 它排在写 currentPath 之前。
    m_sw = re.search(r"val\s+switchingDir\s*=\s*parent\s*!=\s*currentPath", reveal_body)
    i_cp = reveal_body.find("currentPath = parent")
    check("revealFile 以 val switchingDir = parent != currentPath 声明换目录判据（不是随手一句比较）",
          bool(m_sw))
    check("switchingDir 判据在【写 currentPath 之前】计算（放到之后即恒假、多选永不退出）",
          bool(m_sw) and i_cp >= 0 and m_sw.start() < i_cp)

    apply_body = body_of(main_activity, "private fun applyPendingReveal(")
    check("applyPendingReveal 只在命中后才清 pendingReveal（未命中保留，留给正确目录）",
          apply_body != ""
          and "if (index >= 0)" in apply_body
          and "pendingReveal = null" in apply_body
          and apply_body.index("if (index >= 0)") < apply_body.index("pendingReveal = null"))
    check("未命中的「没找到」提示受「当前目录 == 目标父目录」闸门约束（竞态里不误报）",
          "File(target).parent == currentPath" in apply_body)
    check("未命中提示只说一次（revealMissNotified 开关，防 onResume/刷新重放）",
          "revealMissNotified" in main_activity_raw)
    check("loadDir 串行化：新加载取消旧加载（loadJob?.cancel()）",
          "loadJob?.cancel()" in main_activity_raw)

    check("旧的 EXTRA_OPEN_ARCHIVE 已移除（不再按类型弹归档面板）",
          "EXTRA_OPEN_ARCHIVE" not in main_activity_raw)
    # BUG-005（2026-11-10 修复）：界面创建/销毁不再全局清空 cacheDir/rxin_stage ——
    # 那会删掉其他界面甚至 :http 进程正在使用的暂存副本。暂存改为「所有者创建、所有者释放」，
    # 冷启动兜底清理统一收在 RxinApp 的 sweepStaleStage（只在后台线程跑一次）。
    # 判「不许出现」必须用**已剥注释**的源码（否则注释里写一句 clearStage 就误红）。
    rxin_app = strip_comments(
        read(os.path.join(MAIN, "java", "com", "rxinfiles", "tool", "RxinApp.kt")))
    image_viewer = strip_comments(
        read(os.path.join(MAIN, "java", "com", "rxinfiles", "tool", "ImageViewerActivity.kt")))
    text_viewer = strip_comments(
        read(os.path.join(MAIN, "java", "com", "rxinfiles", "tool", "TextViewerActivity.kt")))
    check("界面不再全局清空暂存目录（Main/Incoming/ImageViewer/TextViewer 均无 clearStage）",
          "FileOps.clearStage" not in main_activity
          and "FileOps.clearStage" not in activity
          and "FileOps.clearStage" not in image_viewer
          and "FileOps.clearStage" not in text_viewer)
    check("冷启动兜底清理改由 RxinApp 在后台线程执行 sweepStaleStage",
          "FileOps.sweepStaleStage(this" in rxin_app
          and re.search(r"Thread\s*\(", rxin_app) is not None
          and "isDaemon = true" in rxin_app)

    # ---------------------------------------------------------------- 9
    section("9. 「定位高亮」必须与「多选」彻底分离（不许污染 selected）")
    # 多选集合与定位路径是**两个独立字段**
    check("FileListAdapter 有独立的多选集合 selected",
          "private var selected: Set<String>" in filelist)
    check("FileListAdapter 有独立的定位字段 revealPath",
          "private var revealPath: String?" in filelist)
    check("FileListAdapter 有独立的 setReveal 入口",
          "fun setReveal(" in filelist)

    # setReveal 只许动 revealPath，绝不许碰多选集合
    reveal_body = body_of(filelist, "fun setReveal(")
    check("setReveal 只改 revealPath，不碰 selected",
          reveal_body != "" and "selected" not in reveal_body
          and "revealPath" in reveal_body)

    # 渲染时两套状态分别判定：多选看 selected.contains，定位看 item.path == revealPath
    check("多选态按 selected.contains(item.path) 判定",
          "selected.contains(item.path)" in filelist)
    check("定位态按 item.path == revealPath 判定（与多选无关）",
          "item.path == revealPath" in filelist)
    # 定位底色与选中底色必须来自**两个独立资源**，且都有日夜两套颜色。
    # 3.16 起两种底色收敛成各自独立的 drawable（bg_file_row_revealed / bg_file_row_selected），
    # 颜色仍分开定义在 colors.xml 里 —— 判据看「资源是否分开」，不钉具体实现层级。
    light_colors = read(os.path.join(MAIN, "res", "values", "colors.xml"))
    night_colors = read(os.path.join(MAIN, "res", "values-night", "colors.xml"))
    revealed_drawable = read(os.path.join(MAIN, "res", "drawable", "bg_file_row_revealed.xml"))
    selected_drawable = read(os.path.join(MAIN, "res", "drawable", "bg_file_row_selected.xml"))
    check("定位底色使用独立资源（bg_file_row_revealed 与 bg_file_row_selected 分开）",
          "R.drawable.bg_file_row_revealed" in filelist
          and "R.drawable.bg_file_row_selected" in filelist
          and "@color/reveal_highlight" in revealed_drawable
          and "@color/selection_highlight" in selected_drawable)
    selected_color = re.search(r'<color name="selection_highlight">(#[0-9A-Fa-f]+)</color>', light_colors)
    reveal_color = re.search(r'<color name="reveal_highlight">(#[0-9A-Fa-f]+)</color>', light_colors)
    check("定位与选中使用不同颜色且都有日夜资源",
          selected_color is not None and reveal_color is not None
          and selected_color.group(1) != reveal_color.group(1)
          and 'name="selection_highlight"' in night_colors
          and 'name="reveal_highlight"' in night_colors)

    # reveal 只在「不在多选」时显示：多选是用户正在做的事，优先级更高
    check("多选命中时定位高亮让位（revealed 判定含 !checkable）",
          "!checkable" in filelist)

    # MainActivity 侧：定位路径也不能进多选集合
    check("MainActivity 用独立的 pendingReveal 承接定位",
          "pendingReveal" in main_activity_raw)
    check("revealFile 不把定位路径写进 selectedPaths",
          "selectedPaths" not in body_of(main_activity_raw, "private fun revealFile(",
                                         end="\n    }"))

    # ---------------------------------------------------------------- 10
    section("10. 版本号")
    m = re.search(r"versionCode\s*=\s*(\d+)", gradle)
    code = int(m.group(1)) if m else -1
    m2 = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
    name = m2.group(1) if m2 else ""

    # 刻意**不写死**具体版本号 —— 那种断言每发一版就过期一次，红得毫无信息量
    # （本工程已经为它同步过 3151 → 3152 → 3153 → 3155 四轮）。
    # 真正值得钉住的是这两件：格式在，且**拼接规则**对得上。
    parts = re.match(r"^(\d+)\.(\d+)\.(\d+)$", name)
    expect = int(f"{parts.group(1)}{parts.group(2)}{parts.group(3)}") if parts else -1
    check(f"versionName 形如 major.minor.patch（读到 {name!r}）", parts is not None)
    check(f"versionCode 是**拼接**不是算术（{name} → {expect}，实际 {code}）", code == expect)
    check(f"版本为当前发布版 4.02.00：{name}", name == "4.02.00")

    # ---------------------------------------------------------------- 11
    section("11. 目录说明与跑法")
    readme = os.path.join(ROOT, "tools", "incoming-check", "README.md")
    check("README 在", os.path.isfile(readme))
    runsh = os.path.join(ROOT, "tools", "incoming-check", "run.sh")
    check("run.sh 在", os.path.isfile(runsh))
    if os.path.isfile(runsh):
        sh = read(runsh)
        check("run.sh 同时编 IncomingFile 与断言",
              "IncomingFile.java" in sh and "IncomingCheck.java" in sh)

    # ---------------------------------------------------------------- 12
    section("12. 定位失败的诊断 + 按名+大小搜索（3.15.0 修复）")
    # 诊断：反解失败时到底有没有把事实带出来、有没有落日志
    check("IncomingActivity 有诊断日志（Log.i）", "Log.i(" in activity)
    check("IncomingActivity 用 IncomingFile.formatDiagnostics 组织诊断",
          "IncomingFile.formatDiagnostics(" in activity)
    check("IncomingActivity 收集逐条反解候选（attempts）",
          "attempts" in activity and "candidates" in activity)
    check("诊断信息含 provider 元信息（contentResolver.getType）",
          "contentResolver.getType(" in activity)
    check("诊断信息含当前 shell 身份（经 FileRepository 转发，不直接碰 ShellExecutor）",
          "FileRepository.detectShellLevel(" in activity
          or "FileRepository.shellLevelLabel(" in activity)

    # 惰性身份探测：反解成功的路径上**一次权限探测都不许发生**
    # （否则每次外部打开都白付一次 su/Shizuku，有 root 的机器还可能弹授权框）
    check("有惰性探测入口 ensureShellLevel（探测一次后缓存）",
          "private fun ensureShellLevel(" in activity and "detectedLevel" in activity)
    # 变异测试发现：光查 "detectedLevel" 这个字符串没用 —— 字段还在、缓存被摘掉也能过。
    # 必须把「命中缓存就直接返回」这个行为本身钉住。
    check("ensureShellLevel 真的用了缓存（detectedLevel 非空就直接返回，不重复探测）",
          "detectedLevel?.let { return it }" in activity)
    on_create = body_of(activity, "override fun onCreate(")
    check("onCreate **不**探测身份（否则每次打开都付一次探测成本）",
          on_create != "" and "detectShellLevel(" not in on_create
          and "ensureShellLevel(" not in on_create)
    locate_original = body_of(activity, "private fun locateOriginal(")
    check("反解路径（locateOriginal）**不**探测身份",
          locate_original != "" and "ensureShellLevel(" not in locate_original
          and "detectShellLevel(" not in locate_original)
    locate_body = body_of(activity, "private fun locate(")
    m_orig = locate_body.find("locateOriginal(")
    m_lvl = locate_body.find("ensureShellLevel(")
    check("locate 里探测排在反解之后（反解成功提前 return，一次都不探）",
          m_orig >= 0 and m_lvl >= 0 and m_orig < m_lvl)
    search_body = body_of(activity, "private fun searchOriginal(")
    check("搜索前先探测身份（搜索通道按身份分流）",
          search_body != "" and "ensureShellLevel(" in search_body
          and search_body.index("ensureShellLevel(") < search_body.index("searchByNameAndSize("))

    # 展示：必须画在面板自己身上，不许叠第二个窗口
    check("失败面板用底部面板（BottomSheetDialog）", "BottomSheetDialog" in activity)
    check("**没有**用 AlertDialog 叠窗口（层级顺序各 ROM 不一致）",
          "AlertDialog" not in activity)
    check("诊断面板布局被引用", "R.layout.sheet_incoming_failure" in activity)
    check("有「复制诊断信息」到剪贴板的手段",
          "ClipboardManager" in activity and "setPrimaryClip" in activity)

    # 搜索策略：判据与命令构造在纯 Java 层，Activity 只做执行胶水
    check("Activity 走 FileRepository.searchByNameAndSize 搜文件",
          "FileRepository.searchByNameAndSize(" in activity)
    check("搜索命中仍需 filename+size 复核（IncomingFile.matchesSearchHit）",
          "IncomingFile.matchesSearchHit(" in activity)
    check("搜索先搜已知目录（IncomingFile.knownSearchDirs）",
          "IncomingFile.knownSearchDirs(" in activity)
    check("IncomingFile 提供 shell 转义 quoteShell（防命令注入）",
          "static String quoteShell(" in incoming)
    check("IncomingFile 构造 find 命令 buildSearchCommand", "static String buildSearchCommand(" in incoming)
    check("搜索命令**限时**（timeout 前缀）", "timeout " in incoming)
    check("搜索大小固定两个超时常量（已知目录 / 全盘）",
          "SEARCH_TIMEOUT_KNOWN_SECONDS" in incoming and "SEARCH_TIMEOUT_FULL_SECONDS" in incoming)
    check("IncomingFile 解析 find 输出 parseSearchOutput",
          "static List<String> parseSearchOutput(" in incoming)
    check("IncomingFile 搜索命中判据 matchesSearchHit（名 + 大小）",
          "static boolean matchesSearchHit(" in incoming)
    check("FileRepository 用 IncomingFile.buildSearchCommand 构造命令",
          "IncomingFile.buildSearchCommand(" in filerepo)
    check("FileRepository 用 IncomingFile.parseSearchOutput 解析",
          "IncomingFile.parseSearchOutput(" in filerepo)
    check("搜索按身份分流（有 shell 用 find，无 shell 才 Java 兜底）",
          "ShellExecutor.hasShell" in filerepo)

    # 不许放宽既有校验：白名单、名字核对、可见性判据都还在
    check("scheme 白名单仍在（未为搜索放宽）", "IncomingFile.isHandledScheme(" in activity)
    check("反解路径仍过 matchesDisplayName（未为搜索放宽）",
          "IncomingFile.matchesDisplayName(" in activity)
    check("可见性仍用 FileRepository.listDir（未改用 File.canRead）",
          "FileRepository.listDir(" in activity and ".canRead(" not in activity)

    # 布局与断言脚手架
    check("诊断面板布局在", os.path.isfile(FAIL_LAYOUT))
    for ident in ("incomingFailTitle", "incomingFailScroll", "incomingFailText",
                  "incomingFailCopy", "incomingFailClose"):
        check("诊断面板布局定义了 %s" % ident, '@+id/%s"' % ident in fail_layout)
    check("IncomingCheck 覆盖 quoteShell", "quoteShell(" in java_check)
    check("IncomingCheck 覆盖 searchByNameAndSize 的判据 matchesSearchHit",
          "matchesSearchHit(" in java_check)
    check("IncomingCheck 覆盖 buildSearchCommand", "buildSearchCommand(" in java_check)
    check("IncomingCheck 覆盖 formatDiagnostics", "formatDiagnostics(" in java_check)

    # quoteShell 是自己复刻的一份（纯 Java 调不了 Kotlin 的 ScriptRunner）；
    # 参照实现必须还在，否则「同口径」就无从谈起。
    script_runner = read(SCRIPT_RUNNER)
    check("ScriptRunner.quote 仍在（quoteShell 与之同口径的参照）",
          "fun quote(" in script_runner and "\\''" in script_runner)

    # ---------------------------------------------------------------- 13
    section("13. 目标目录可访问性实测 + 内置 busybox timeout + 搜索失败不静默（本轮修复）")

    # 任务 A：诊断要「自动实测」关键目录 —— 判据在纯 Java 层，双通道执行在 Android 侧
    check("IncomingFile 定义实测目录计划 PROBE_SUBDIRS", "PROBE_SUBDIRS" in incoming)
    check("IncomingFile 提供 probeDirs（拼到外部存储根）",
          "static List<String> probeDirs(" in incoming)
    check("IncomingFile 提供 formatAccessReport（渲染实测报告）",
          "static String formatAccessReport(" in incoming)
    check("IncomingFile 定义双通道测试结果 ListProbe / DirProbe",
          "class ListProbe" in incoming and "class DirProbe" in incoming)

    # 实测目录必须覆盖用户反馈里的那 5 个（含 Android/data 与 QQ 子目录、对照组 Download）
    for probe in ("Android/data",
                  "Android/data/com.tencent.mobileqq",
                  "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv",
                  "Tencent", "Download"):
        check("PROBE_SUBDIRS 覆盖 %s" % probe, '"%s"' % probe in incoming)

    # Activity：定位失败时实测双通道，且实测用 FileRepository 的两条通道
    check("IncomingActivity 有 probeTargetDirs（逐个目录实测）",
          "private fun probeTargetDirs(" in activity)
    check("实测走 Java 通道（FileRepository.probeListJava）",
          "FileRepository.probeListJava(" in activity)
    check("实测走 shell 通道（FileRepository.probeListShell）",
          "FileRepository.probeListShell(" in activity)
    check("实测结果经 IncomingFile.formatAccessReport 渲染",
          "IncomingFile.formatAccessReport(" in activity)
    # 实测的 shell 通道按身份分流，因此必须在探到身份之后才测（否则通道判错）
    locate_body13 = body_of(activity, "private fun locate(")
    check("locate 里实测排在身份探测之后（shell 通道需先有身份）",
          locate_body13 != ""
          and locate_body13.find("ensureShellLevel(") >= 0
          and locate_body13.find("probeTargetDirs(") >= 0
          and locate_body13.find("ensureShellLevel(") < locate_body13.find("probeTargetDirs("))

    # 任务 B：内置 busybox 的 timeout 优先，系统 timeout 次选
    check("FileRepository 提供 busyboxTimeoutPath（内置 busybox 作首选 timeout）",
          "fun busyboxTimeoutPath(" in filerepo and "RxinToolbox" in filerepo)
    check("Activity 搜索时取 busybox 路径传给搜索",
          "FileRepository.busyboxTimeoutPath(" in activity)
    check("buildSearchCommand 带 busybox 参数（首选 timeout）",
          "busyboxPath" in incoming and "command -v timeout" in incoming)
    check("搜索命令含内置 busybox 优先逻辑（$__rxin_to）", "$__rxin_to" in incoming)

    # 任务 B：搜索失败必须记进诊断（三种失败分得清，不能静默）
    check("IncomingFile 定义搜索状态标记", "MARK_NO_TIMEOUT" in incoming and "MARK_TIMEOUT" in incoming)
    check("IncomingFile 定义 SearchStatus 枚举（三种失败）", "enum SearchStatus" in incoming)
    check("IncomingFile 提供 parseSearchStatus", "static SearchStatus parseSearchStatus(" in incoming)
    check("FileRepository 搜索用 parseSearchStatus 判结局",
          "parseSearchStatus(" in filerepo)
    check("FileRepository.searchByNameAndSize 返回 SearchOutcome（结局 + 路径一起带出）",
          "IncomingFile.SearchOutcome" in filerepo
          and "): IncomingFile.SearchOutcome {" in filerepo)
    check("Activity 把搜索结局写进诊断（notes 记录 describe，不静默）",
          "describe(" in activity and "searchNotes" in activity)

    # 诊断里必须带出「可访问性实测」这一节
    check("Diagnostics 有 accessReport 字段（实测结果进诊断）", "accessReport" in incoming)
    check("formatDiagnostics 渲染可访问性实测小节",
          "目标目录可访问性实测" in incoming)

    # 任务 C 的底线：本轮**不改访问策略**（不动 targetSdk / 不引 SAF / 反解判据照旧）
    m_t = re.search(r"targetSdk\s*=\s*(\d+)", gradle)
    check("targetSdk 未被本轮改动（仍为 34）", bool(m_t) and m_t.group(1) == "34")
    for forbidden in ("ACTION_OPEN_DOCUMENT_TREE", "OpenDocumentTree", "DocumentFile",
                      "takePersistableUriPermission"):
        check("IncomingActivity 未引入 SAF 取权限方案 %s" % forbidden, forbidden not in activity)
    check("反解判据照旧（matchesDisplayName 仍在使用，未被放宽）",
          "IncomingFile.matchesDisplayName(" in activity)

    # 断言脚手架覆盖本轮新增判据
    check("IncomingCheck 覆盖 probeDirs", "probeDirs(" in java_check)
    check("IncomingCheck 覆盖 formatAccessReport", "formatAccessReport(" in java_check)
    check("IncomingCheck 覆盖 parseSearchStatus", "parseSearchStatus(" in java_check)
    check("IncomingCheck 覆盖 buildSearchCommand 的 busybox 首选", "busybox" in java_check)

    # ---------------------------------------------------------------- 14
    section("14. 定向目录查找：已知 QQ/微信落点按名+大小（本轮新增，优先于全盘搜索）")

    # 判据在纯 Java 层：目录计划 + 命中判据
    check("IncomingFile 定义定向目录计划 DIRECTED_LOOKUP_SUBDIRS",
          "DIRECTED_LOOKUP_SUBDIRS" in incoming)
    check("IncomingFile 提供 directedLookupDirs（拼到外部存储根）",
          "static List<String> directedLookupDirs(" in incoming)
    check("IncomingFile 提供定向命中判据 matchesDirectedHit（名 + 大小）",
          "static boolean matchesDirectedHit(" in incoming)
    # 定向目录必须覆盖用户真机确认的那个 QQ 接收目录
    check("定向目录覆盖 QQfile_recv（用户真机确认）",
          "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv" in incoming)

    # Activity：有定向查找这一路，且走 listDir（与可见性核对同一条通道），不依赖 find/timeout
    check("IncomingActivity 有 directedLookup（定向查找这一路）",
          "private fun directedLookup(" in activity)
    check("定向查找用 FileRepository.directedLookupDirs 拿目录",
          "IncomingFile.directedLookupDirs(" in activity)
    check("定向查找用 FileRepository.listDir 列目录（按身份自动选通道）",
          "FileRepository.listDir(" in activity)
    check("定向查找用 matchesDirectedHit 判命中",
          "IncomingFile.matchesDirectedHit(" in activity)

    # 顺序：反解 → 定向查找 → 全盘搜索 → 兜底（定向必须排在全盘搜索之前，否则没意义）
    locate_body14 = body_of(activity, "private fun locate(")
    i_rev = locate_body14.find("locateOriginal(")
    i_dir = locate_body14.find("directedLookup(")
    i_sch = locate_body14.find("searchOriginal(")
    check("locate 里顺序为：反解 < 定向查找 < 全盘搜索（定向优先于全盘 find）",
          locate_body14 != "" and i_rev >= 0 and i_dir >= 0 and i_sch >= 0
          and i_rev < i_dir < i_sch)
    # 定向查找的通道按身份分流，必须先探身份
    directed_body = body_of(activity, "private fun directedLookup(")
    check("directedLookup 里先探身份再列目录（通道按身份分流）",
          directed_body != "" and "ensureShellLevel(" in directed_body
          and directed_body.index("ensureShellLevel(") < directed_body.index("listDir("))

    # 定向结果进诊断（notes），过程不静默
    check("定向查找过程记进 notes（进诊断面板，不静默）",
          "定向查找" in activity)

    # 断言脚手架覆盖本轮新增判据
    check("IncomingCheck 覆盖 directedLookupDirs", "directedLookupDirs(" in java_check)
    check("IncomingCheck 覆盖 matchesDirectedHit", "matchesDirectedHit(" in java_check)

    # ---------------------------------------------------------------- 15
    section("15. 浏览 Android/data 的「无权限」提醒：三条件同时成立 + 空目录不许误报（本轮新增）")

    # 判据在纯 Java 层（可离线断言），三条一起构成「真的读不出来」
    check("IncomingFile 提供 isUnderAndroidData", "static boolean isUnderAndroidData(" in incoming)
    check("IncomingFile 提供 normalizeStoragePath", "static String normalizeStoragePath(" in incoming)
    check("IncomingFile 提供 shouldWarnNoAccess", "static boolean shouldWarnNoAccess(" in incoming)
    check("IncomingFile 提供提醒文案 NO_ACCESS_TITLE / NO_ACCESS_BODY",
          "NO_ACCESS_TITLE" in incoming and "NO_ACCESS_BODY" in incoming)
    # 判据必须是「三个条件同时成立」：正文里能看到三个 return false 的短路分支
    warn_body = body_of(incoming, "public static boolean shouldWarnNoAccess(")
    check("shouldWarnNoAccess 依次判：位置 → 身份 → 探针（三条件同时成立）",
          warn_body != ""
          and warn_body.count("return false") >= 2
          and warn_body.count("return false") <= 3
          and "isUnderAndroidData(" in warn_body
          and "needsShellHint(" in warn_body
          and "return !javaProbeOk" in warn_body)
    # 归一化必须在 isUnderAndroidData 里被真的调用（否则 /sdcard 前缀判不出来）
    under_body = body_of(incoming, "public static boolean isUnderAndroidData(")
    check("isUnderAndroidData 先归一化再按段比对（否则 /sdcard 前缀会漏）",
          under_body != "" and "normalizeStoragePath(" in under_body
          and "split(\"/\")" in under_body and "contains(" not in under_body)
    # 归一化要覆盖三种前缀
    for alias in ("/sdcard", "/storage/self/primary"):
        check("normalizeStoragePath 认识别名 %s" % alias, '"%s"' % alias in incoming)
    check("归一化落点是 EMULATED_ROOT 常量（不散落写死）",
          'EMULATED_ROOT = "/storage/emulated/0"' in incoming)

    # FileRepository：一次 IO 同时拿回列表与「真的读不出来」的判定
    check("FileRepository 提供 listDirChecked（列表 + 无权限判定一起返回）",
          "fun listDirChecked(" in filerepo)
    checked_body = body_of(filerepo, "    fun listDirChecked(")
    check("listDirChecked 复用 probeListJava 探针（不自己重写一份列目录）",
          checked_body != "" and "probeListJava(" in checked_body)
    check("listDirChecked 先判廉价前置条件（不在 Android/data 或已有 shell 就不探）",
          checked_body != "" and "isUnderAndroidData(" in checked_body
          and "ShellExecutor.hasShell" in checked_body)
    check("listDirChecked 把判定交给 IncomingFile.shouldWarnNoAccess（不自己下结论）",
          checked_body != "" and "IncomingFile.shouldWarnNoAccess(" in checked_body)
    check("listDirChecked 用 ShellExecutor.level.label 取身份（与 needsShellHint 同口径）",
          checked_body != "" and "ShellExecutor.level.label" in checked_body)

    # MainActivity：loadDir 走 listDirChecked，且提醒有「说过了」的开关
    load_body = body_of(main_activity, "    private fun loadDir(")
    check("loadDir 用 FileRepository.listDirChecked（不再直接 listDir）",
          load_body != "" and "FileRepository.listDirChecked(" in load_body)
    check("loadDir 把判定结果交给 maybeWarnAndroidData",
          load_body != "" and "maybeWarnAndroidData(" in load_body)
    check("提醒有状态开关字段 noAccessWarnedFor（防 onResume 反复弹）",
          "private var noAccessWarnedFor" in main_activity)
    warn_method = body_of(main_activity, "    private fun maybeWarnAndroidData(")
    check("maybeWarnAndroidData 按路径去重（同一目录只说一次）",
          warn_method != ""
          and "noAccessWarnedFor == path" in warn_method
          and "noAccessWarnedFor = path" in warn_method)
    check("读得出来时复位开关（换目录再回来能重新提醒）",
          warn_method != "" and "noAccessWarnedFor = null" in warn_method)
    check("maybeWarnAndroidData 不重复判据（判在纯 Java 层，这里只决定弹不弹）",
          warn_method != "" and "isUnderAndroidData(" not in warn_method)

    # 展示：画在面板自己身上，不许叠第二个窗口
    no_access_layout = os.path.join(MAIN, "res", "layout", "sheet_no_access.xml")
    check("提醒面板布局 sheet_no_access.xml 在", os.path.isfile(no_access_layout))
    if os.path.isfile(no_access_layout):
        nl_raw = read(no_access_layout)
        nl = strip_xml_comments(nl_raw)
        for ident in ("noAccessTitle", "noAccessPath", "noAccessText",
                      "noAccessUrl", "noAccessCopy", "noAccessOpen", "noAccessClose"):
            check("提醒面板布局定义了 %s" % ident, '@+id/%s"' % ident in nl)
        # 剥注释再判：注释里解释「为什么不写死文案」本身就会含 Android/data
        check("提醒面板不在布局里写死**说明正文**（正文由 Activity 从 IncomingFile 填）",
              "Android/data" not in nl)
    check("提醒面板用 BottomSheetDialog", "BottomSheetDialog(this)" in main_activity)
    check("提醒面板用 setContentView（不是叠窗口）",
          "sheet.setContentView(content)" in main_activity)
    sheet_method = body_of(main_activity, "    private fun showNoAccessSheet(")
    check("提醒面板的文案取自 IncomingFile（真源唯一）",
          sheet_method != "" and "IncomingFile.NO_ACCESS_TITLE" in sheet_method
          and "IncomingFile.NO_ACCESS_BODY" in sheet_method
          and "IncomingFile.SHIZUKU_DOWNLOAD_URL" in sheet_method)
    check("提醒面板用 ACTION_VIEW 打开下载页（不硬编码浏览器包名）",
          sheet_method != "" and "Intent.ACTION_VIEW" in main_activity)
    check("提醒面板没有浏览器时明确告知，不静默",
          "没有可用的浏览器" in main_activity)
    check("提醒面板提供「复制地址」这条出路",
          "Intents.copyToClipboard(" in main_activity
          and "下载地址已复制" in main_activity)
    # 两个面板里的下载按钮文案必须一致（同一件事两个说法会让人以为是两个功能）
    check("两个面板的下载按钮文案一致（都是「去下载 Shizuku」）",
          'android:text="去下载 Shizuku"' in nl
          and 'android:text="去下载 Shizuku"' in fail_layout)

    # 底线：没有为这一处引入新依赖 / 新权限 / SAF
    check("MainActivity 未引入 SAF 取权限方案（与 IncomingActivity 同底线）",
          all(f not in main_activity for f in ("ACTION_OPEN_DOCUMENT_TREE", "DocumentFile")))
    check("MainActivity 没有用 File.canRead() 判可访问性（判据走探针）",
          ".canRead(" not in main_activity)

    # 断言脚手架覆盖本轮新增 Java 判据
    check("IncomingCheck 覆盖 isUnderAndroidData", "isUnderAndroidData(" in java_check)
    check("IncomingCheck 覆盖 shouldWarnNoAccess", "shouldWarnNoAccess(" in java_check)
    check("IncomingCheck 覆盖 normalizeStoragePath", "normalizeStoragePath(" in java_check)

    print()
    print("结果: %d 项通过, %d 项失败" % (total - failed, failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
