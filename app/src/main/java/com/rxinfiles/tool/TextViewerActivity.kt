package com.rxinfiles.tool

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.ArrayList
import java.util.HashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 文本浏览器 / 轻量编辑器。
 *
 * 面向「手机上打开 .txt / .c / .js / .conf 看一眼、顺手改两个字」这件事，
 * 所以目标是**够用**而不是完整编辑器：
 *
 *  - 编码自动识别（[TextCodec]）并可手动切换 —— 猜错就是满屏乱码，
 *    与其让用户对着乱码猜，不如给他一个列表；
 *  - 查找做全量扫描 + 视口内高亮 + 上一个/下一个跳转，输入有防抖；
 *  - 编辑按需进入，保存前自动备份 `.bak`；应用身份写不进去时用 root 写
 *    （先落缓存再 `cp`，保留原文件属主与权限位）；
 *  - **文件多大都能改**（见下）。
 *
 * ## 为什么不再有「文件太大，禁止编辑」
 *
 * 早先的实现拿 `MAX_FULL_BYTES`（4MB）当闸门：文件一大就只读前 512KB，
 * 并且把编辑入口一并禁掉。那个判据是错的 ——
 * 控件（TextView / EditText）的开销**只与"一次塞进去多少字符"有关**，
 * 与文件多大无关；于是"7.8MB 的文本一个字节都改不了"，而它其实只需要
 * 让控件一次少拿一点字符就行。
 *
 * 现在文件按**字符数**切成片（[TextSlicer]），浏览与编辑都只把**当前片**
 * 交给控件，保存时逐片拼接（没碰过的片按原始字节原样复制）。
 * 于是"能不能编辑"与"文件体积"彻底脱钩：每片的代价恒定，
 * 文件再大也只是片数变多，靠底部的片导航翻。
 *
 * 单片字符数上限取 [SLICE_CHARS]，与原先"一次最多显示多少字符"
 * （`DISPLAY_LIMIT_NORMAL` = 200 万）同一个量级 —— 也就是
 * **分片之后的每一片，都不会比原来一次显示的那一大坨更吃力**。
 *
 * ## 查找高亮为什么不走 span
 *
 * 高亮有两种常见写法：每次查找重建 SpannableString 再 setText，或者原地增删
 * `BackgroundColorSpan`。前者会因为 `checkForRelayout()` 走 `nullLayouts()` 分支而
 * **每次 setText 都把整段文本重新排版**；后者更隐蔽 —— `TextView.spanChange()` 对
 * `CharacterStyle` 除了 `invalidate()` 还会调 `checkForResize()`，而 `checkForResize()`
 * 只要发现控件宽度是 `WRAP_CONTENT`（不折行模式必须是它，要靠它把外层
 * HorizontalScrollView 撑开）就直接 `requestLayout()`：**每挂一个 span 就等于把整篇
 * 文本重新排版一次**，表现就是"输入完没反应、滚一下才看到底色"。
 *
 * 现在高亮由 [HighlightTextView] 自己画：文本只 setText 一次，底色按当前可见区域
 * 每帧现算 —— 不改 span、不触发重排，也不存在"改完没重绘"的窗口。
 *
 * 分片之后查找只覆盖**当前片**：跨片查找要在磁盘上做字节级搜索，
 * 那是另一件事（[findInput] 的 hint 会直说"本片内"）。
 */
class TextViewerActivity : AppCompatActivity() {

    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var contentText: HighlightTextView
    private lateinit var contentEdit: EditText
    private lateinit var scrollView: ScrollView
    private lateinit var hScrollView: HorizontalScrollView
    private lateinit var progress: View
    private lateinit var errorText: TextView
    private lateinit var findBar: View
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView
    private lateinit var saveBtn: TextView
    private lateinit var wrapBtn: TextView
    private lateinit var fontBtn: TextView
    private lateinit var editBtn: TextView
    private lateinit var sliceBar: View
    private lateinit var sliceInfo: TextView
    private lateinit var slicePrev: TextView
    private lateinit var sliceNext: TextView

    /** 用户要编辑的目标文件（保存写回的就是它） */
    private var file: File? = null

    /**
     * 实际**读取**的文件。
     *
     * 与 [file] 分开是因为 root 才读得到的文本要先借一份到私有缓存
     * （[FileOps.stageToCache]）；读缓存、写原文件。分片保存时"没改过的片"
     * 也是从这个文件按字节复制的，所以它必须与 [file] 内容一致。
     */
    private var source: File? = null
    private var targetVersion: TextFileRepository.Version? = null

    /**
     * 本实例自己「借」出来的缓存目录（root 才读得到的文件先 stage 再看）。
     *
     * [open] 在后台线程登记、[resetSlices] / [onDestroy] 在主线程释放，
     * 所以用线程安全集合。每个目录都是 [FileOps.stageToCache] 给本实例的独占目录，
     * 释放它不会影响其他界面或后台任务的副本。
     */
    private val stagedDirs = CopyOnWriteArrayList<File>()

    /**
     * 调用方（压缩包预览）通过 Intent 交来的暂存目录，里面的副本由本实例接手释放。
     *
     * 旋转重建后的新实例还要读同一份文件，所以只在 [isFinishing] 的销毁里删。
     */
    private var handedStagedDir: File? = null

    /** 分片布局；null 表示还没加载出结果 */
    private var sliced: TextSlicer.Layout? = null

    private var charset = "UTF-8"
    private var hasBom = false

    /** 当前显示 / 编辑的是第几片 */
    private var sliceIndex = 0

    /**
     * 改动过的片：片号 → 编辑后的文本。
     *
     * **这是"未保存改动"的唯一真源**（保存就是把这张表拼回文件）。
     * 用 Map 而不是只留当前 EditText 的内容，是因为用户会翻片：
     * 在第 3 片改两个字、翻到第 5 片再改，翻回去必须还能看到第 3 片的改动。
     */
    private val editedSlices = HashMap<Int, String>()

    /** 跳到指定行时先把目标行挂起，等这一片渲染完再定位 */
    private var pendingLine = -1

    /**
     * 当前片最长行的字符数。
     *
     * 超过 [LONG_LINE_WRAP] 就强制折行 —— 不折行渲染时控件宽度由最长行决定，
     * 一片十万字符如果是一整行，会撑出几十万像素宽，滚动直接废掉。
     */
    private var sliceLongestLine = 0

    /** 进编辑前的浏览位置，退出编辑时恢复（否则每次改完都回到顶部） */
    private var browseScrollY = 0

    private var editing = false
    private var wrapLines = false
    private var fontSize = 13f
    private var dirty = false

    /** 程序化 setText 也会触发 TextWatcher，用它把「误判为用户编辑」挡掉 */
    private var suppressDirtyWatch = false

    private val uiHandler = Handler(Looper.getMainLooper())

    // ---------------------------------------------------------------- 查找状态

    /** 当前显示的纯文本（= contentText 的内容）。查找在它上面做，不再每次从控件里拷贝 */
    private var plainText = ""

    /** 查找输入的防抖：大文件下每敲一个字符就全量扫描会卡 */
    private val highlightTask = Runnable { highlight(findInput.text?.toString().orEmpty()) }

    /**
     * 全部命中的起始偏移，升序。
     *
     * 用 int 数组而不是 `List<IntRange>`：命中可能上万条，逐个装箱既慢又费内存；
     * 而且所有命中的长度都等于关键词长度，存起点就够了。
     * 数组交给 [contentText] 只读，所以扩容后要重新推一次。
     */
    private var matchStarts = IntArray(INITIAL_MATCH_CAPACITY)
    private var matchCount = 0

    /** 是否因为到达 [MAX_MATCHES] 而提前停止扫描 */
    private var matchOverflow = false

    /** 关键词（已逐字符小写化）。长度即命中长度 */
    private var needle = CharArray(0)

    /** 当前跳到第几个命中；-1 表示还没跳 */
    private var matchCursor = -1

    private var pulseStep = 0

    /**
     * 跳转后的闪烁：让"当前是哪一个"在视觉上不可能看错。
     *
     * 底色现在由 [HighlightTextView] 自己画，闪烁只是把一个色值推过去，
     * 不存在"改了颜色但没重绘"的可能 —— 这也是它比 span 方案稳的地方。
     */
    private val pulseTask = object : Runnable {
        override fun run() {
            if (matchCursor !in 0 until matchCount) return
            pulseStep++
            if (pulseStep >= PULSE_STEPS) {
                contentText.setCurrentColor(CURRENT_MATCH_BG)
            } else {
                contentText.setCurrentColor(
                    if (pulseStep % 2 == 1) FLASH_MATCH_BG else CURRENT_MATCH_BG
                )
                uiHandler.postDelayed(this, PULSE_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_viewer)

        titleText = findViewById(R.id.titleText)
        subtitleText = findViewById(R.id.subtitleText)
        contentText = findViewById(R.id.contentText)
        contentEdit = findViewById(R.id.contentEdit)
        scrollView = findViewById(R.id.scrollView)
        hScrollView = findViewById(R.id.hScrollView)
        progress = findViewById(R.id.progress)
        errorText = findViewById(R.id.errorText)
        findBar = findViewById(R.id.findBar)
        findInput = findViewById(R.id.findInput)
        findCount = findViewById(R.id.findCount)
        saveBtn = findViewById(R.id.saveBtn)
        wrapBtn = findViewById(R.id.wrapBtn)
        fontBtn = findViewById(R.id.fontBtn)
        editBtn = findViewById(R.id.editBtn)
        sliceBar = findViewById<View>(R.id.sliceBar)
        sliceInfo = findViewById<TextView>(R.id.sliceInfo)
        slicePrev = findViewById<TextView>(R.id.slicePrev)
        sliceNext = findViewById<TextView>(R.id.sliceNext)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        fontSize = prefs.getFloat("font_size", 13f)
        wrapLines = prefs.getBoolean("wrap", false)
        applyFontSize()
        applyWrap()

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path.isNullOrEmpty()) {
            finish()
            return
        }
        file = File(path)
        titleText.text = File(path).name
        // 压缩包预览会把「取出来的副本目录」一并交进来：退出时由本实例负责归还
        handedStagedDir = intent.getStringExtra(EXTRA_STAGED_DIR)
            ?.takeIf { it.isNotEmpty() }
            ?.let { File(it) }
        setupUi()
        load(null, savedInstanceState)
    }

    // ------------------------------------------------------------------ 加载

    /**
     * 打开文件：探测编码 → 切片 → 读指定片。Activity 重建时同时恢复未保存改动。
     */
    @Suppress("UNCHECKED_CAST")
    private fun load(charsetOverride: String?, state: Bundle? = null) {
        val target = file ?: return
        val restored = state?.getSerializable(STATE_EDITS) as? HashMap<Int, String>
        val edits = restored ?: HashMap()
        val wantedIndex = state?.getInt(STATE_SLICE, 0) ?: 0
        progress.visibility = View.VISIBLE
        errorText.visibility = View.GONE
        resetEditState()
        sliceBar.visibility = View.GONE
        resetSlices()
        editedSlices.putAll(edits)

        Thread {
            val opened = try {
                open(target, charsetOverride, wantedIndex, edits)
            } catch (t: Throwable) {
                Log.w(TAG, "load failed", t)
                null
            }
            runOnUiThread {
                progress.visibility = View.GONE
                if (opened == null) {
                    contentText.text = ""
                    errorText.text = "无法读取该文件\n${target.name}\n\n" +
                        "可能是二进制文件，或者当前身份读不到它"
                    errorText.visibility = View.VISIBLE
                } else {
                    source = opened.source
                    targetVersion = opened.targetVersion
                    sliced = opened.layout
                    charset = opened.charset
                    hasBom = opened.bom
                    showSlice(opened.index, opened.text, opened.longest)
                }
            }
        }.start()
    }

    private class Opened(
        val source: File,
        val targetVersion: TextFileRepository.Version,
        val charset: String,
        val bom: Boolean,
        val layout: TextSlicer.Layout,
        val index: Int,
        val text: String,
        val longest: Int
    )

    private fun open(
        target: File,
        charsetOverride: String?,
        wantedIndex: Int,
        edits: Map<Int, String>
    ): Opened? {
        val version = TextFileRepository.snapshot(target)
        val src = if (target.canRead()) {
            target
        } else {
            FileOps.stageToCache(this, target)?.also { staged ->
                // 登记本实例的暂存目录；打开失败也不在这里删（同一份副本可重试），
                // 统一在真正退出界面时按所有者释放
                staged.parentFile?.let { stagedDirs.add(it) }
            } ?: return null
        }
        val name = charsetOverride ?: TextCodec.detect(readHead(src, DETECT_BYTES))
        val layout = TextSlicer.scan(src, name, SLICE_CHARS)
        val index = wantedIndex.coerceIn(layout.slices.indices)
        val slice = layout.slices[index]
        val text = edits[index] ?: TextSlicer.readSlice(
            src, name, slice, if (slice.startByte == 0L) layout.bomLength else 0
        )
        return Opened(
            src, version, name, layout.bomLength > 0, layout, index, text, TextSlicer.longestLine(text)
        )
    }

    private fun readHead(file: File, max: Int): ByteArray = try {
        val size = minOf(file.length(), max.toLong()).toInt()
        if (size <= 0) {
            ByteArray(0)
        } else {
            val buffer = ByteArray(size)
            java.io.RandomAccessFile(file, "r").use { it.readFully(buffer) }
            buffer
        }
    } catch (t: Throwable) {
        ByteArray(0)
    }

    private fun resetSlices() {
        // 同一实例里重新加载（切编码 / 保存后重载 / 重命名）时，上一份自己 stage 的
        // 副本已经没用了，先释放掉，别让反复加载把缓存越堆越多。
        // 只释放本实例创建的那些：调用方交来的 handedStagedDir 要留到 finish。
        releaseStagedDirs()
        sliced = null
        source = null
        targetVersion = null
        sliceIndex = 0
        editedSlices.clear()
        pendingLine = -1
        plainText = ""
        sliceLongestLine = 0
        resetFind()
        updateSliceBar()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        stashEditingSlice()
        outState.putSerializable(STATE_EDITS, HashMap(editedSlices))
        outState.putInt(STATE_SLICE, sliceIndex)
        super.onSaveInstanceState(outState)
    }

    /** 有未保存的改动（编辑框里刚敲、还没并进 [editedSlices] 的也算） */
    private fun hasUnsavedEdits(): Boolean = editedSlices.isNotEmpty() || (editing && dirty)

    /**
     * 把编辑框里刚敲的内容并进 [editedSlices]。
     *
     * 翻片 / 保存 / 切编码之前都要先调它 —— 否则"在第 3 片改完直接翻到第 5 片"
     * 会把第 3 片的改动丢掉。
     */
    private fun stashEditingSlice() {
        if (editing && dirty) {
            editedSlices[sliceIndex] = contentEdit.text.toString()
            dirty = false
        }
    }

    /**
     * 取第 index 片的文本：**有改动就取改动后的**，否则从文件读。
     *
     * 这是 IO，只能在后台线程调用。
     */
    private fun readSliceText(index: Int): String {
        editedSlices[index]?.let { return it }
        val layout = sliced ?: return ""
        val src = source ?: return ""
        val skip = if (layout.slices[index].startByte == 0L) layout.bomLength else 0
        return TextSlicer.readSlice(src, layout.charset, layout.slices[index], skip)
    }

    /**
     * 把一片摆到界面上（主线程）。
     *
     * `plainText` 始终是**纯文本**，不带任何"以上为前 xx"之类的尾巴 ——
     * 片导航已经把"这是第几片、覆盖第几行"讲清楚了，再把提示塞进正文只会
     * 污染查找和编辑（旧实现就是往正文里拼提示串）。
     */
    private fun showSlice(index: Int, text: String, longestLine: Int) {
        sliceIndex = index
        plainText = text
        sliceLongestLine = longestLine
        if (editing) {
            suppressDirtyWatch = true
            try {
                contentEdit.setText(text)
                layOutEditOnce()
            } catch (t: Throwable) {
                // 翻片换来一片排不进编辑框的文本：退回浏览模式，并把这一片在
                // 浏览侧渲染出来。浏览侧是 StaticLayout、只排一遍，比编辑侧的
                // DynamicLayout 低一档 —— 这条路是"回到能用的状态"，不是在兜底里再赌一次。
                Log.e(TAG, "reflow slice $index in edit mode failed", t)
                resetEditState()
                toast("这一片排不进编辑框，已回到浏览模式")
                renderSlice()
                return
            }
            suppressDirtyWatch = false
            dirty = false
            // 翻片可能换来一份"有超长行"的文本，折行方式要跟着改
            applyWrap()
            updateSubtitle()
            updateSliceBar()
            applyPendingLine()
        } else {
            renderSlice()
        }
    }

    /**
     * 渲染当前片。可以指定渲染完恢复到的纵向位置（[restoreScrollY]）。
     *
     * 全程只在这里 `setText` 一次；之后高亮都靠控件自己按可见区域画，见类注释。
     */
    private fun renderSlice(restoreScrollY: Int = 0) {
        // 旧的 span 跟着旧文本对象一起丢弃，这里只把查找状态清干净
        resetFind()
        findBar.visibility = View.GONE
        // 折行方式取决于本片有没有超长行，先定下来再 setText，免得排两遍
        applyWrap()
        // 用 NORMAL 而不是 SPANNABLE：底色是控件自绘的、不需要 span，
        // 而 SPANNABLE 会让 TextView 内部再复制一整串（10 万字符就是 200KB）
        contentText.setText(plainText)
        updateSubtitle()
        updateSliceBar()
        hScrollView.scrollTo(0, 0)
        scrollView.post { scrollView.scrollTo(0, restoreScrollY.coerceAtLeast(0)) }
        applyPendingLine()
    }

    /**
     * 翻到第 index 片（必要时先定位到目标行）。
     *
     * 读取在后台做：一片最多 [SLICE_CHARS] 个字符，手机上解码这一下虽然只有几十毫秒，
     * 但扛在主线程上就是一次可感知的掉帧，而"翻片"恰恰是最常做的动作。
     */
    private fun goToSlice(index: Int, targetLine: Int = -1) {
        val layout = sliced ?: return
        if (index !in layout.slices.indices) return
        if (index == sliceIndex) {
            pendingLine = targetLine
            applyPendingLine()
            return
        }
        stashEditingSlice()
        pendingLine = targetLine
        progress.visibility = View.VISIBLE
        Thread {
            val text = try {
                readSliceText(index)
            } catch (t: Throwable) {
                Log.w(TAG, "read slice $index failed", t)
                null
            }
            // 最长行也在后台算：它是渲染方式的判据，没必要占主线程
            val longest = text?.let { TextSlicer.longestLine(it) } ?: 0
            runOnUiThread {
                progress.visibility = View.GONE
                if (text == null) {
                    toast("读取这一片失败")
                } else {
                    showSlice(index, text, longest)
                }
            }
        }.start()
    }

    private fun updateSubtitle() {
        val layout = sliced ?: return
        val parts = mutableListOf(
            charset,
            "${layout.totalLines} 行",
            FileRepository.formatSize(layout.totalBytes)
        )
        if (hasBom) parts.add("BOM")
        if (layout.size() > 1) parts.add("第 ${sliceIndex + 1}/${layout.size()} 片")
        // 本片有超长行 → 折行是被强制的（不是用户选的），说清楚，免得困惑
        if (forceWrap) parts.add("超长行 已折行")
        subtitleText.text = parts.joinToString(" · ")
    }

    /**
     * 底部片导航条：只有真的切成多片时才出现。
     *
     * 单片文件（绝大多数）看不到它 —— 这也是"分片对老用户是透明的"那一条：
     * 小文件的行为与分片之前一模一样。
     */
    private fun updateSliceBar() {
        val layout = sliced
        if (layout == null || layout.size() <= 1) {
            sliceBar.visibility = View.GONE
            return
        }
        sliceBar.visibility = View.VISIBLE
        val slice = layout.slices[sliceIndex]
        val head = "第 ${sliceIndex + 1}/${layout.size()} 片 · " +
            "行 ${slice.startLine}–${layout.endLineOf(sliceIndex)}"
        sliceInfo.text = if (editedSlices.isEmpty()) {
            head
        } else {
            "$head · 已改 ${editedSlices.size} 片"
        }
        val hasPrev = sliceIndex > 0
        val hasNext = sliceIndex < layout.size() - 1
        slicePrev.isEnabled = hasPrev
        sliceNext.isEnabled = hasNext
        slicePrev.alpha = if (hasPrev) 1f else 0.3f
        sliceNext.alpha = if (hasNext) 1f else 0.3f
    }

    /** 第 line 行落在哪一片（startLine 升序，倒着找第一个不晚于它的片） */
    private fun sliceContainingLine(line: Int): Int {
        val layout = sliced ?: return 0
        for (i in layout.slices.indices.reversed()) {
            if (layout.slices[i].startLine <= line) return i
        }
        return 0
    }

    /** 片渲染完之后再定位：这一片里还没排版出来时算不出该滚到哪 */
    private fun applyPendingLine() {
        val line = pendingLine
        pendingLine = -1
        if (line <= 0) return
        val layout = sliced ?: return
        val relative = line - layout.slices[sliceIndex].startLine + 1
        if (relative < 1) return
        if (editing) {
            val offset = offsetOfLine(contentEdit.text.toString(), relative)
            contentEdit.setSelection(offset.coerceIn(0, contentEdit.text.length))
        } else {
            contentText.post {
                val layoutOfText = contentText.layout
                val y = if (layoutOfText != null && relative - 1 < layoutOfText.lineCount) {
                    layoutOfText.getLineTop(relative - 1)
                } else {
                    (relative - 1) * contentText.lineHeight
                }
                scrollView.scrollTo(0, y.coerceAtLeast(0))
            }
        }
    }

    // ------------------------------------------------------------------ UI

    private fun setupUi() {
        findViewById<TextView>(R.id.backBtn).setOnClickListener { confirmExit() }
        findViewById<TextView>(R.id.moreBtn).setOnClickListener { showMoreMenu() }

        editBtn.setOnClickListener {
            if (editing) confirmDiscard() else enterEditMode()
        }
        saveBtn.setOnClickListener { save() }

        // 片导航：大文件靠它翻。单片文件时整条是 GONE，与分片之前毫无区别。
        slicePrev.setOnClickListener {
            stashEditingSlice()
            goToSlice(sliceIndex - 1)
        }
        sliceNext.setOnClickListener {
            stashEditingSlice()
            goToSlice(sliceIndex + 1)
        }
        sliceInfo.setOnClickListener { showSlicePicker() }

        findViewById<TextView>(R.id.findBtn).setOnClickListener { openFindBar() }
        findViewById<TextView>(R.id.findClose).setOnClickListener { closeFindBar() }
        findViewById<TextView>(R.id.findNext).setOnClickListener { jumpMatch(1) }
        findViewById<TextView>(R.id.findPrev).setOnClickListener { jumpMatch(-1) }
        findViewById<TextView>(R.id.findList).setOnClickListener { showMatchList() }

        findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                uiHandler.removeCallbacks(highlightTask)
                uiHandler.postDelayed(highlightTask, FIND_DEBOUNCE_MS)
            }
        })

        // 底色由控件按可见区域现画，滚动本身不用重算命中；只有可见行区间真的换了
        // （翻了一页）才让控件重绘一次。这样"底色跟着文字走"，而不是"停下来才补上"。
        val onScroll = View.OnScrollChangeListener { _, _, _, _, _ ->
            contentText.invalidateIfRangeChanged()
        }
        scrollView.setOnScrollChangeListener(onScroll)
        hScrollView.setOnScrollChangeListener(onScroll)

        contentText.setColors(NORMAL_MATCH_BG, CURRENT_MATCH_BG, CURRENT_MATCH_STROKE)

        wrapBtn.setOnClickListener {
            // 本片有超长行时折行是强制的，点了也不会变 —— 先说清楚，别让人以为按钮坏了
            if (forceWrap) toast("本片有超长行，必须折行显示")
            wrapLines = !wrapLines
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("wrap", wrapLines).apply()
            applyWrap()
        }

        fontBtn.setOnClickListener {
            fontSize = when {
                fontSize < 14f -> 16f
                fontSize < 18f -> 20f
                fontSize < 24f -> 24f
                else -> 11f
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat("font_size", fontSize).apply()
            applyFontSize()
        }

        findViewById<TextView>(R.id.jumpBtn).setOnClickListener { showJumpDialog() }
        findViewById<TextView>(R.id.charsetBtn).setOnClickListener { showCharsetDialog() }

        contentEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!suppressDirtyWatch) {
                    dirty = true
                }
            }
        })
    }

    /**
     * 本片是否**必须**折行 —— 这是用户偏好之外的安全要求。
     *
     * 不折行渲染时控件宽度由最长行决定，一行十万字符会撑出几十万像素宽，
     * `StaticLayout` 还得为它整行测宽，结果就是"进得去但滑不动"。
     */
    private val forceWrap: Boolean
        get() = sliceLongestLine > LONG_LINE_WRAP

    /** 实际生效的折行开关：用户开了折行，或者本片有超长行 */
    private val effectiveWrap: Boolean
        get() = wrapLines || forceWrap

    /**
     * 换行开关。
     *
     * 关掉自动换行时，TextView 用 WRAP_CONTENT 把外层 HorizontalScrollView 撑开，
     * 长行就能横向滚动；打开时改成 MATCH_PARENT，交给 TextView 自己折行。
     *
     * 本片有超长行时 [forceWrap] 会压过用户偏好 —— 那是渲染安全的硬要求，
     * 所以按钮文案标成"自动"，免得出现"点了没反应"的困惑。
     */
    private fun applyWrap() {
        wrapBtn.text = when {
            forceWrap -> "换行: 自动"
            wrapLines -> "换行: 开"
            else -> "换行: 关"
        }
        val wrap = effectiveWrap
        contentText.layoutParams = contentText.layoutParams.apply {
            width = if (wrap) {
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        contentText.setHorizontallyScrolling(!wrap)
        contentText.setSingleLine(false)
        applyEditWrap()
        contentText.requestLayout()
        // 折行方式变了，行划分跟着变；按新视口把底色重铺一次
        contentText.post { contentText.invalidate() }
    }

    /**
     * 编辑框的折行方式：**永远折行**。
     *
     * 浏览侧要横向滚动（不折行时靠 `WRAP_CONTENT` 把外层 `HorizontalScrollView`
     * 撑开），所以那边跟着 [effectiveWrap] 走；编辑框不一样 ——
     * 它是 `match_parent` 宽度、自带滚动条，横向滚动在编辑时没有意义，
     * 而"不折行"会让**一整片十万字符的单行**排出几十万像素宽的 `DynamicLayout`：
     * 那既是内存最贵的形态，也是光标 / 选区定位最容易出岔子的形态。
     * 折行之后宽度由控件宽度决定，排版代价与文本内容无关。
     */
    private fun applyEditWrap() {
        contentEdit.setHorizontallyScrolling(false)
        contentEdit.setSingleLine(false)
        contentEdit.requestLayout()
    }

    private fun applyFontSize() {
        fontBtn.text = "字号 ${fontSize.toInt()}"
        contentText.textSize = fontSize
        contentEdit.textSize = fontSize
        contentText.post { contentText.invalidate() }
    }

    // ------------------------------------------------------------------ 查找

    private fun openFindBar() {
        if (editing) {
            toast("查找只在浏览模式可用")
            return
        }
        // 分片之后这个查找只覆盖当前片（高亮、闪烁、滚动这一整套都建立在
        // "视野里就这一片文本"之上）。所以 hint 直说，别让人以为搜不到就是没有；
        // 要跨片找走顶栏 ⋮ 里的「全文查找…」。
        val slices = sliced?.size() ?: 1
        findInput.hint = if (slices > 1) "在本片内查找（共 $slices 片）" else "查找内容"
        findBar.visibility = View.VISIBLE
        findInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
        // 上次搜过的词重新铺一遍，省得再敲一次
        val last = findInput.text?.toString().orEmpty()
        if (last.isNotEmpty()) {
            uiHandler.removeCallbacks(highlightTask)
            highlight(last)
        }
    }

    private fun closeFindBar() {
        findBar.visibility = View.GONE
        resetFind()
    }

    /**
     * 全量扫描 + 跳到第一个命中。
     *
     * 命中**全部记下来**（只存起始偏移，见 [matchStarts]），控件只画当前可见的那一部分
     * —— 这样"命中多少条"和"每帧画多少个矩形"彻底解耦。
     */
    private fun highlight(query: String) {
        resetFind()
        if (query.isEmpty() || plainText.isEmpty()) return

        needle = CharArray(query.length) { Character.toLowerCase(query[it]) }
        matchOverflow = scanMatches(plainText)

        if (matchCount == 0) {
            findCount.text = "0/0"
            toast("没有找到「$query」")
            return
        }
        // 扫描过程中数组可能被扩容过，所以按最终结果重新推一次引用
        contentText.setHighlights(matchStarts, matchCount, needle.size, -1)
        updateFindCount()
        goTo(0)
    }

    // ------------------------------------------------------------ 查找：扫描

    /**
     * 单遍扫描命中位置。
     *
     * 三条硬要求：
     *  1. **零分配**。不再生成整段小写副本（2MB 文本会多出 4MB 临时字符串），
     *     逐字符用 [Character.toLowerCase] 比较即可；
     *  2. **单遍**。命中后跳过整个关键词长度，与旧实现 `indexOf` 的语义一致（不重叠）；
     *  3. **可提前退出**。命中数到 [MAX_MATCHES] 就停，避免极端输入下空转。
     *
     * @return true 表示还有更多命中没被记下
     */
    private fun scanMatches(text: String): Boolean {
        val size = needle.size
        if (size == 0) return false
        var i = 0
        val last = text.length - size
        while (i <= last) {
            if (Character.toLowerCase(text[i]) == needle[0] && tailMatches(text, i + 1)) {
                if (matchCount == MAX_MATCHES) return true
                if (matchCount == matchStarts.size) {
                    matchStarts = matchStarts.copyOf(minOf(matchStarts.size * 2, MAX_MATCHES))
                }
                matchStarts[matchCount++] = i
                i += size
            } else {
                i++
            }
        }
        return false
    }

    /** text 从 start 起是否与 needle 的剩余部分逐字符（忽略大小写）相同 */
    private fun tailMatches(text: String, start: Int): Boolean {
        val size = needle.size
        for (j in 1 until size) {
            if (Character.toLowerCase(text[start + j - 1]) != needle[j]) return false
        }
        return true
    }

    /** 清空查找状态 */
    private fun resetFind() {
        uiHandler.removeCallbacks(pulseTask)
        matchCount = 0
        matchOverflow = false
        needle = CharArray(0)
        matchCursor = -1
        pulseStep = 0
        findCount.text = ""
        contentText.clearHighlights()
    }

    // ------------------------------------------------------------ 查找：跳转

    private fun goTo(index: Int) {
        if (matchCount == 0) return
        matchCursor = ((index % matchCount) + matchCount) % matchCount
        updateFindCount()
        contentText.setCurrent(matchCursor)
        // 顺序不能反：先把视口挪到目标，底色是照可见区域现画的
        scrollToMatch()
        contentText.invalidate()
        startPulse()
    }

    private fun jumpMatch(step: Int) {
        if (matchCount == 0) {
            toast("没有找到「${findInput.text}」")
            return
        }
        val next = if (matchCursor < 0) {
            if (step > 0) 0 else matchCount - 1
        } else {
            matchCursor + step
        }
        goTo(next)
    }

    private fun updateFindCount() {
        findCount.text = when {
            matchCount == 0 -> "0/0"
            matchOverflow -> "${matchCursor + 1}/$matchCount+"
            else -> "${matchCursor + 1}/$matchCount"
        }
    }

    /**
     * 把当前命中移到视口中央。
     *
     * 用 `getLineTop/getLineBottom` 而不是 `行号 × lineHeight`：折行之后各行高度并不相等，
     * 乘法算出来的位置会偏，偏得多了就成了"跳过去了但看不出是哪一条"。
     * 这里用 `scrollTo` 而不是 `smoothScrollTo`：立刻到位，紧随其后的底色铺设和闪烁才有的放矢。
     */
    private fun scrollToMatch() {
        val layout = contentText.layout ?: return
        if (matchCursor !in 0 until matchCount) return
        val offset = matchStarts[matchCursor].coerceIn(0, contentText.length())

        val line = layout.getLineForOffset(offset)
        val top = layout.getLineTop(line)
        val lineHeight = (layout.getLineBottom(line) - top).coerceAtLeast(1)
        val vh = scrollView.height.coerceAtLeast(1)
        scrollView.scrollTo(0, (top - (vh - lineHeight) / 2).coerceAtLeast(0))

        if (wrapLines) {
            hScrollView.scrollTo(0, 0)
        } else {
            // 不折行时长行可能是横向滚动的，不跟着横向挪一下，命中就在屏幕外
            val hw = hScrollView.width.coerceAtLeast(1)
            val x = layout.getPrimaryHorizontal(offset)
            hScrollView.scrollTo((x - hw / 2f).toInt().coerceAtLeast(0), 0)
        }
    }

    /** 闪两下再定住：让"当前是哪一个"在视觉上不可能看错 */
    private fun startPulse() {
        uiHandler.removeCallbacks(pulseTask)
        if (matchCursor !in 0 until matchCount) return
        pulseStep = 0
        contentText.setCurrentColor(CURRENT_MATCH_BG)
        pulseTask.run()
    }

    // ------------------------------------------------------------ 查找：列表

    /**
     * 命中列表。
     *
     * 命中几百上千条时，靠"下一个"一条条翻是找不到东西的；列表把行号和一小截上下文
     * 一起摆出来，点一下就跳过去。列表本身有 [MATCH_LIST_MAX] 上限，再长也不适合扫着看。
     */
    private fun showMatchList() {
        if (matchCount == 0) {
            toast("没有命中")
            return
        }
        val shown = minOf(matchCount, MATCH_LIST_MAX)
        val lineNumbers = lineNumbersFor(shown)
        val items = Array(shown) { i ->
            val mark = if (i == matchCursor) "▸ " else "   "
            "$mark\u884c ${lineNumbers[i]}   ${snippetAt(matchStarts[i])}"
        }
        val title = buildString {
            append("命中 ").append(matchCount)
            if (matchOverflow) append("+")
            append(" 处")
            if (shown < matchCount) append("（列出前 $shown）")
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items) { _, which -> goTo(which) }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 每个命中所在的行号。
     *
     * 命中起点升序，所以一次顺序扫过去就够，是 O(最后一条命中的位置)，不是 O(条数 × 行数)。
     */
    private fun lineNumbersFor(count: Int): IntArray {
        val out = IntArray(count)
        // 报出去的行号必须是**全文行号**：片内行号单独看没有意义
        // （用户在第 5 片里看到"第 3 行"，那其实可能是全文第 8943 行）
        val offset = (sliced?.slices?.getOrNull(sliceIndex)?.startLine ?: 1) - 1
        var line = 1
        var cursor = 0
        val text = plainText
        for (i in 0 until count) {
            val target = matchStarts[i]
            while (cursor < target && cursor < text.length) {
                if (text[cursor] == '\n') line++
                cursor++
            }
            out[i] = line + offset
        }
        return out
    }

    /** 命中处前后各截一小段，压掉连续空白，一眼看清上下文 */
    private fun snippetAt(offset: Int): String {
        val from = (offset - SNIPPET_CONTEXT).coerceAtLeast(0)
        val to = (offset + needle.size + SNIPPET_CONTEXT).coerceAtMost(plainText.length)
        if (from >= to) return ""
        val body = plainText.substring(from, to)
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
            .replace(WHITESPACE_RUN, " ")
            .trim()
        return buildString {
            if (from > 0) append('…')
            append(body)
            if (to < plainText.length) append('…')
        }
    }

    // ------------------------------------------------------------------ 编辑

    /**
     * 进入编辑。
     *
     * 这里**不再有任何"文件太大"的判据**。编辑的对象从来就是"当前这一片"，
     * 而一片的字符数有上限（[SLICE_CHARS]），所以能走到这里就一定改得动 ——
     * 旧实现那句「文件过大已截断显示，编辑已禁用」是把两个不相干的量绑在了一起。
     *
     * ## 3.14.2 在这里错了一次：把「省内存」当成了「防闪退」（3.14.3 订正）
     *
     * 那一版进编辑会先 `contentText.setText("")`，**把浏览侧的文本与 Layout 销毁掉**，
     * 理由是"两套文本对象同时在场就是峰值内存翻倍，而点编辑闪退多半正撞在这里"。
     * 两处都站不住：
     *
     *  1. **"峰值翻倍"是臆测**。十万字符的 `StaticLayout` 不过几 MB；真被量级压垮的
     *     是 3.14.0 的 200 万字符片，那已经由 `SLICE_CHARS` 治了。**"多半撞在这里"
     *     一条证据都没有** —— 与 3.14.0 拿未验证的理论值当基线是同一类错。
     *  2. **代价全落在唯一的失败恢复路径上**：进编辑一旦失败，要退回浏览就必须
     *     **再建一份十万字符的 Layout**，于是"兜底"本身成了崩溃点 ——
     *     真机上的表现就是「点编辑直接闪出阅读页」（兜底刚跑就炸，连提示都来不及弹）。
     *
     * 现在改成**只遮不毁**：`INVISIBLE` 保留布局尺寸（滚动位置不会被夹到 0），
     * 文本与 Layout 原样留着 —— 退回时只改可见性，零分配，兜底不可能再炸。
     *
     * 同时补两条真实的护栏：编辑框**永远折行**（[applyEditWrap]），以及进编辑时
     * **强制在本帧排一次版**（[layOutEditOnce]）—— 后者是把"排版失败"从下一帧
     * 拉回这层 try 里的唯一办法，也是 3.14.2 那条兜底"看着有、其实接不住"的原因。
     */
    private fun enterEditMode() {
        val layout = sliced ?: return
        try {
            editing = true
            dirty = false
            // 编辑走的是另一个控件（EditText），浏览侧的底色留着没有意义
            resetFind()
            // 记下浏览位置：INVISIBLE 不会动滚动位置，但换回来时再摆一次更稳
            browseScrollY = scrollView.scrollY
            // 只遮不毁 —— 理由见上面那段订正
            contentText.visibility = View.INVISIBLE
            applyEditWrap()
            contentEdit.visibility = View.VISIBLE
            suppressDirtyWatch = true
            contentEdit.setText(plainText)
            contentEdit.setSelection(0)
            layOutEditOnce()
        } catch (t: Throwable) {
            onEditLayoutFailed(t)
            return
        }
        suppressDirtyWatch = false
        saveBtn.visibility = View.VISIBLE
        editBtn.text = "取消"
        findBar.visibility = View.GONE
        updateSliceBar()
        toast(
            when {
                // 先讲最要紧的那条：超长行在编辑框里就是"一个段落十万字符"，
                // 每次按键都会重排它（DynamicLayout 是按段落 reflow 的），能改但会有点顿
                forceWrap -> "本片含超长行，输入时可能有轻微延迟"
                layout.size() > 1 -> "编辑本片：可以翻片接着改，最后点「保存」一次写回"
                else -> "编辑模式：改完点「保存」，保存前会自动备份 .bak"
            }
        )
    }

    /**
     * 让编辑框在**本帧**就把版排出来。
     *
     * `TextView.setText()` 只是换文本 + `requestLayout()`；真正的 `DynamicLayout`
     * 是在**下一次 `onMeasure`** 里建的。排版要是在那儿出问题，异常会落到下一帧的
     * Choreographer 回调里，而那时这层 try 早就退栈了 —— **用户看到的就是应用直接消失**。
     * 3.14.2 的兜底"看着有、其实接不住"，根因就在这里。
     *
     * 显式走一次 `measure()`，异常就会在这层 try 里抛出。尺寸取控件当前值，
     * 还没布局过（宽高为 0）就用屏幕尺寸 —— 折行与否只影响排出来是几行，
     * 不影响"排得出来还是排不出来"。
     */
    private fun layOutEditOnce() {
        val w = contentEdit.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val h = contentEdit.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        contentEdit.measure(
            View.MeasureSpec.makeMeasureSpec(w.coerceAtLeast(1), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h.coerceAtLeast(1), View.MeasureSpec.EXACTLY)
        )
    }

    /**
     * 进编辑（或编辑中翻片）排版失败时的统一退路。
     *
     * 两条硬要求：
     *
     *  1. **只做减法**：不重建浏览侧、不重排正文 —— 3.14.2 的兜底正是死在
     *     "退出编辑时再建一份十万字符 Layout"上，用户看到的是「点编辑直接闪出阅读页」；
     *  2. **把原因说出来**：异常类型与消息直接摆进对话框。用户截一张图，
     *     比"闪退"两个字有用得多。
     */
    private fun onEditLayoutFailed(t: Throwable) {
        Log.e(TAG, "enter edit mode failed", t)
        suppressDirtyWatch = false
        resetEditState()
        scrollView.post { scrollView.scrollTo(0, browseScrollY.coerceAtLeast(0)) }
        AlertDialog.Builder(this)
            .setTitle("没法进入编辑")
            .setMessage(
                "这一段文本让编辑控件排不下，内容没有被改动。\n\n" +
                    "本片 ${plainText.length} 字符，最长一行 ${sliceLongestLine} 字符。\n" +
                    "失败原因：${t.javaClass.name}: ${t.message}\n\n" +
                    "可以先用顶栏的「换行」「字号」调整后再试，" +
                    "或者用底部的片导航换一片编辑。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    /**
     * 退出编辑，回到浏览模式。
     *
     * 浏览侧内容**从未被销毁**（进编辑只是把它遮住，见 [enterEditMode]），
     * 所以这里只把可见性换回来、把滚动位置摆回去 —— 不重排、不读盘、零分配。
     * 这一点是"进编辑失败也能全身而退"的前提：退回这条路本身不产生任何大分配。
     */
    private fun exitEditMode() {
        resetEditState()
        scrollView.post { scrollView.scrollTo(0, browseScrollY.coerceAtLeast(0)) }
    }

    /**
     * 只复位编辑相关的状态与按钮，**不碰浏览侧内容**。
     *
     * [load] 紧接着就会整体重建，所以它走这里而不是 [exitEditMode]，
     * 免得白排一次马上就要被换掉的文本。
     */
    private fun resetEditState() {
        suppressDirtyWatch = true
        editing = false
        dirty = false
        contentText.visibility = View.VISIBLE
        contentEdit.visibility = View.GONE
        // 编辑侧的 DynamicLayout 也一起放掉，否则它要等到下次进编辑才被覆盖
        contentEdit.setText("")
        saveBtn.visibility = View.GONE
        editBtn.text = "编辑"
        suppressDirtyWatch = false
        updateSliceBar()
    }

    /**
     * 点「取消」：放弃未保存的改动，回到浏览模式。
     *
     * 分片之后改动可能横跨好几片（在第 3 片改完、翻到第 5 片又改），
     * 所以这里问的是"**所有**未保存的改动"，不是"这一片"。
     */
    private fun confirmDiscard() {
        if (!hasUnsavedEdits()) {
            exitEditMode()
            return
        }
        val changed = editedSlices.size + if (dirty) 1 else 0
        AlertDialog.Builder(this)
            .setTitle("放弃修改？")
            .setMessage("有未保存的改动（$changed 片），返回浏览模式会丢失它们。")
            .setPositiveButton("放弃") { _, _ -> discardEdits() }
            .setNeutralButton("保存并退出") { _, _ -> save() }
            .setNegativeButton("继续编辑", null)
            .show()
    }

    /** 丢掉所有未保存的改动（编辑框里刚敲的也算），把当前片重新读一遍摆回来 */
    private fun discardEdits() {
        editedSlices.clear()
        dirty = false
        // 这里不用 exitEditMode()：它会把**改动后**的 plainText 先渲染一遍，
        // 紧接着被从文件读回的原文覆盖 —— 那一下闪烁纯属自己找的
        resetEditState()
        val index = sliceIndex
        progress.visibility = View.VISIBLE
        Thread {
            val text = try {
                readSliceText(index)
            } catch (t: Throwable) {
                null
            }
            val longest = text?.let { TextSlicer.longestLine(it) } ?: 0
            runOnUiThread {
                progress.visibility = View.GONE
                if (text != null) showSlice(index, text, longest)
            }
        }.start()
    }

    /**
     * 保存：把改动过的片拼回去。
     *
     * 真正干活的是 [writeBack]：**没改过的片是从原文件按字节复制的**，不重新编码 ——
     * 这样即使编码探测猜错了，没碰过的内容出去时也与进来时逐字节相同。
     * 旧实现是拿整份文本 `toByteArray` 覆盖，探测一错就是整篇乱码。
     */
    private fun save(onDone: (() -> Unit)? = null) {
        val target = file ?: return
        val layout = sliced ?: return
        val src = source ?: return
        val version = targetVersion ?: return

        stashEditingSlice()
        if (editedSlices.isEmpty()) {
            toast("没有改动")
            return
        }
        val edits = HashMap(editedSlices)
        val charsetName = charset

        progress.visibility = View.VISIBLE
        Thread {
            val outcome = writeBack(target, src, layout, version, edits)
            runOnUiThread {
                progress.visibility = View.GONE
                when (outcome) {
                    WRITE_OK, WRITE_ROOT -> {
                        toast(
                            if (outcome == WRITE_ROOT) "已以 root 身份保存"
                            else "已保存（备份 ${target.name}.bak）"
                        )
                        load(charsetName)
                        onDone?.invoke()
                    }
                    WRITE_CONFLICT -> {
                        AlertDialog.Builder(this)
                            .setTitle("文件已被其它程序修改")
                            .setMessage("为避免覆盖外部改动，本次没有保存。重新加载会丢弃当前未保存内容。")
                            .setPositiveButton("重新加载") { _, _ -> load(charsetName) }
                            .setNegativeButton("保留当前编辑", null)
                            .show()
                    }
                    WRITE_VERIFY_FAILED -> {
                        AlertDialog.Builder(this)
                            .setTitle("保存已中止")
                            .setMessage(
                                "拼好的内容与预期对不上，原文件没有被改动。\n\n" +
                                    "通常意味着文件在编辑期间被别的程序改过了。\n" +
                                    "重新加载一份再试。"
                            )
                            .setPositiveButton("重新加载") { _, _ -> load(charsetName) }
                            .setNegativeButton("关闭", null)
                            .show()
                    }
                    else -> {
                        AlertDialog.Builder(this)
                            .setTitle("保存失败")
                            .setMessage(
                                "应用身份写不进去，也没有可用的 root。\n\n" +
                                    "可以改用系统的文本编辑器打开，或者先复制一份到可写目录再改。"
                            )
                            .setPositiveButton("用其它应用打开") { _, _ ->
                                if (!Intents.openWith(this, target)) toast("没有找到可用的应用")
                            }
                            .setNegativeButton("关闭", null)
                            .show()
                    }
                }
            }
        }.start()
    }

    private fun writeBack(
        target: File,
        src: File,
        layout: TextSlicer.Layout,
        version: TextFileRepository.Version,
        edits: Map<Int, String>
    ): Int = TextFileRepository.write(
        cacheDir,
        target,
        TextFileRepository.Session(src, layout, version),
        edits
    )

    private fun showJumpDialog() {
        val input = EditText(this).apply {
            hint = "行号"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("跳到指定行")
            .setView(wrapView(input))
            .setPositiveButton("跳转") { _, _ ->
                val line = input.text.toString().trim().toIntOrNull() ?: return@setPositiveButton
                if (line <= 0) return@setPositiveButton
                val layout = sliced ?: return@setPositiveButton
                // 旧实现把超范围的行号悄悄夹到底部（看起来跳过去了、其实没有），
                // 现在直说
                if (line > layout.totalLines) {
                    toast("这个文件只有 ${layout.totalLines} 行")
                    return@setPositiveButton
                }
                stashEditingSlice()
                goToSlice(sliceContainingLine(line), line)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun offsetOfLine(text: String, line: Int): Int {
        if (line <= 1) return 0
        var current = 1
        for (i in text.indices) {
            if (current == line) return i
            if (text[i] == '\n') current++
        }
        return text.length
    }

    // ------------------------------------------------------- 片导航 / 全文查找

    /**
     * 片列表。
     *
     * 片多的时候（几十上百片）靠 ◀ ▶ 一片片翻是不现实的；列表把"每片覆盖哪几行、
     * 哪几片改过"一次摆出来，点一下就过去 —— 与查找的命中列表同一个思路。
     */
    private fun showSlicePicker() {
        val layout = sliced ?: return
        if (layout.size() <= 1) return
        val items = Array(layout.size()) { i ->
            val mark = if (i == sliceIndex) "▸ " else "   "
            val edited = if (editedSlices.containsKey(i)) " · 已改" else ""
            "${mark}行 ${layout.slices[i].startLine}–${layout.endLineOf(i)}$edited"
        }
        AlertDialog.Builder(this)
            .setTitle("跳到哪一片（共 ${layout.size()} 片）")
            .setItems(items) { _, which -> goToSlice(which) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 全文查找的一条命中 */
    private class WholeHit(
        val slice: Int,
        val offset: Int,
        val line: Int,
        val snippet: String
    )

    /**
     * 跨片查找。
     *
     * 为什么和顶栏那个查找分开：那个要负责**高亮 + 闪烁 + 滚动**，
     * 整套机制建立在"视野里就这一片文本"之上（见类注释里关于 span 的取舍），
     * 让它跨片就得把那套东西整个重做 —— 风险远大于收益。
     * 这个只回答"在哪几片、哪几行出现过"，用列表摆出来、点一下跳过去；
     * 对大文件反而更顺手：几十条命中可以扫，几十处闪烁的底色不能扫。
     *
     * 用法就是"先在这里定位，再用本片内查找在落点附近细看"。
     */
    private fun showWholeFileSearch() {
        val layout = sliced ?: return
        if (layout.size() <= 1) return
        val input = EditText(this).apply {
            hint = "要在全文里找什么"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("全文查找（共 ${layout.size()} 片）")
            .setView(wrapView(input))
            .setPositiveButton("查找") { _, _ ->
                val query = input.text.toString()
                if (query.isNotEmpty()) runWholeFileSearch(query)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runWholeFileSearch(query: String) {
        val layout = sliced ?: return
        progress.visibility = View.VISIBLE
        Thread {
            val hits = ArrayList<WholeHit>()
            var overflow = false
            for (i in layout.slices.indices) {
                if (hits.size >= MAX_MATCHES) {
                    overflow = true
                    break
                }
                val text = try {
                    readSliceText(i)
                } catch (t: Throwable) {
                    Log.w(TAG, "whole-file search: slice $i unreadable", t)
                    continue
                }
                if (scanSliceHits(text, query, i, layout.slices[i].startLine, hits)) {
                    overflow = true
                    break
                }
            }
            runOnUiThread {
                progress.visibility = View.GONE
                showWholeHits(query, hits, overflow)
            }
        }.start()
    }

    /**
     * 在一片里扫命中，顺路把行号数出来。
     *
     * 命中起点升序，所以行号跟着同一个游标走一遍就够，不必每个命中都从头数
     * —— 与 [lineNumbersFor] 是同一个理由。
     *
     * @return true 表示命中已经攒满，不用再往下扫了
     */
    private fun scanSliceHits(
        text: String,
        query: String,
        slice: Int,
        baseLine: Int,
        out: MutableList<WholeHit>
    ): Boolean {
        val size = query.length
        if (size == 0 || text.length < size) return false
        var line = baseLine
        var cursor = 0
        var i = 0
        val last = text.length - size
        while (i <= last) {
            while (cursor < i) {
                if (text[cursor] == '\n') line++
                cursor++
            }
            if (text.regionMatches(i, query, 0, size, ignoreCase = true)) {
                if (out.size >= MAX_MATCHES) return true
                out.add(WholeHit(slice, i, line, wholeSnippet(text, i, size)))
                i += size
            } else {
                i++
            }
        }
        return false
    }

    /** 与 [snippetAt] 同一套裁剪口径，只是作用在"跨片查找"的片上 */
    private fun wholeSnippet(text: String, offset: Int, size: Int): String {
        val from = (offset - SNIPPET_CONTEXT).coerceAtLeast(0)
        val to = (offset + size + SNIPPET_CONTEXT).coerceAtMost(text.length)
        if (from >= to) return ""
        val body = text.substring(from, to)
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
            .replace(WHITESPACE_RUN, " ")
            .trim()
        return buildString {
            if (from > 0) append('…')
            append(body)
            if (to < text.length) append('…')
        }
    }

    private fun showWholeHits(query: String, hits: List<WholeHit>, overflow: Boolean) {
        if (hits.isEmpty()) {
            toast("全文里没有找到「$query」")
            return
        }
        val shown = minOf(hits.size, MATCH_LIST_MAX)
        val items = Array(shown) { i ->
            val hit = hits[i]
            "行 ${hit.line} · 第 ${hit.slice + 1} 片   ${hit.snippet}"
        }
        val title = buildString {
            append("命中 ").append(hits.size)
            if (overflow) append("+")
            append(" 处")
            if (shown < hits.size) append("（列出前 $shown）")
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items) { _, which ->
                val hit = hits[which]
                goToSlice(hit.slice, hit.line)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ------------------------------------------------------------------ 菜单

    private fun showCharsetDialog() {
        val current = charset
        val options = TextCodec.SUPPORTED.map { if (it == current) "$it（当前）" else it }
        AlertDialog.Builder(this)
            .setTitle("文本编码")
            .setItems(options.toTypedArray()) { _, which ->
                val picked = TextCodec.SUPPORTED[which]
                if (picked == current) return@setItems
                if (hasUnsavedEdits()) {
                    toast("请先保存或放弃修改")
                } else {
                    load(picked)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showMoreMenu() {
        val target = file ?: return
        val multi = (sliced?.size() ?: 1) > 1
        val actions = mutableListOf<String>()
        // 分了片就说明这份内容是"一部分"，菜单上也直说，别让人以为复制到的是全文
        actions.add(if (multi) "复制本片内容" else "复制全部内容")
        if (multi) actions.add("全文查找…")
        actions.add("用其它应用打开")
        actions.add("分享")
        actions.add("复制路径")
        actions.add("重命名")
        actions.add("删除")
        actions.add("重新加载")
        AlertDialog.Builder(this)
            .setTitle(target.name)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "复制全部内容" -> {
                        Intents.copyToClipboard(this, plainText, "text")
                        toast("已复制全部内容")
                    }
                    "复制本片内容" -> {
                        Intents.copyToClipboard(this, plainText, "text")
                        toast("已复制第 ${sliceIndex + 1} 片")
                    }
                    "全文查找…" -> showWholeFileSearch()
                    "用其它应用打开" -> {
                        if (!Intents.openWith(this, target)) toast("没有找到能打开它的应用")
                    }
                    "分享" -> {
                        if (!Intents.share(this, target)) toast("分享失败")
                    }
                    "复制路径" -> {
                        Intents.copyToClipboard(this, target.absolutePath)
                        toast("已复制")
                    }
                    "重命名" -> showRenameDialog(target)
                    "删除" -> confirmDelete(target)
                    "重新加载" -> load(charset)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRenameDialog(target: File) {
        val input = EditText(this).apply {
            setText(target.name)
            setSelection(text.length)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(wrapView(input))
            .setPositiveButton("确定") { _, _ ->
                val renamed = FileOps.rename(target, input.text.toString())
                if (renamed == null) {
                    toast("重命名失败（可能同名已存在）")
                } else {
                    file = renamed
                    titleText.text = renamed.name
                    markChanged()
                    load(charset)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(target: File) {
        AlertDialog.Builder(this)
            .setTitle("删除")
            .setMessage("确定删除 ${target.name}？此操作不可撤销。")
            .setPositiveButton("删除") { _, _ ->
                Thread {
                    val outcome = try {
                        FileOps.delete(listOf(target), onEntry = { })
                    } catch (t: Throwable) {
                        null
                    }
                    runOnUiThread {
                        if (outcome?.ok == true && !target.exists()) {
                            toast("已删除")
                            markChanged()
                            finish()
                        } else {
                            toast("删除失败：${outcome?.error ?: "权限不足"}")
                        }
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 通知文件管理器「这个目录的内容变了」。
     *
     * 查看器里的重命名 / 删除只动了当前这一个文件，但列表页拿的是进入查看器之前的快照，
     * 不刷新的话回来还会看到已经不存在的条目（点上去必然失败）。
     */
    private fun markChanged() {
        val parent = file?.parentFile ?: return
        setResult(RESULT_OK, Intent().putExtra(EXTRA_CHANGED_DIR, parent.absolutePath))
    }

    // ------------------------------------------------------------------ 返回

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!hasUnsavedEdits() && !editing) {
            super.onBackPressed()
        } else {
            confirmExit()
        }
    }

    private fun confirmExit() {
        // 判据是"有没有未保存的改动"，不是"编辑框里脏没脏"：
        // 分片之后完全可能"在第 3 片改完、翻到第 5 片没动"，这时 dirty 是 false，
        // 但第 3 片的改动还揣在 editedSlices 里 —— 用 dirty 判会把它悄悄丢掉
        if (hasUnsavedEdits()) {
            AlertDialog.Builder(this)
                .setTitle("还有未保存的修改")
                .setMessage("离开会丢失这些修改。")
                .setPositiveButton("保存并退出") { _, _ -> save { finish() } }
                .setNegativeButton("直接退出") { _, _ -> finish() }
                .setNeutralButton("继续编辑", null)
                .show()
            return
        }
        if (editing) {
            exitEditMode()
            return
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(highlightTask)
        uiHandler.removeCallbacks(pulseTask)
        // root 文件是借到缓存里看的，退出时归还 —— 但只在**真正离开界面**时归还：
        // 旋转重建（isFinishing=false）后新实例还要读同一份副本（尤其是调用方交来的），
        // 删了会让新实例打不开文件；这种情况交给冷启动的兜底清理回收。
        if (isFinishing) {
            releaseStagedDirs()
            deleteQuietly(handedStagedDir)
            handedStagedDir = null
        }
    }

    // ------------------------------------------------------------ 暂存副本释放

    /**
     * 释放本实例自己借出来的缓存目录。
     *
     * 只删本实例的独占目录，不碰 cacheDir/rxin_stage 根目录 ——
     * 其他界面 / 后台任务的副本各有各的所有者。
     */
    private fun releaseStagedDirs() {
        for (dir in stagedDirs) deleteQuietly(dir)
        stagedDirs.clear()
    }

    private fun deleteQuietly(dir: File?) {
        if (dir == null) return
        try {
            dir.deleteRecursively()
        } catch (t: Throwable) {
            Log.w(TAG, "stage cleanup failed: ${dir.path}", t)
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun wrapView(inner: View): View {
        val padding = (20 * resources.displayMetrics.density).toInt()
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(padding, padding / 2, padding, 0)
            addView(inner)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_PATH = "text_path"

        /**
         * 调用方交来的暂存副本目录（压缩包预览用）。
         *
         * 写入端：[MainActivity.previewArchiveEntry]、[ArchiveSheet.preview]；
         * 读取端：本 Activity 在 isFinishing 的 onDestroy 里释放。
         */
        const val EXTRA_STAGED_DIR = "staged_dir"


        /** 查看器里改动了目录内容时，回传受影响的目录，供列表页刷新 */
        const val EXTRA_CHANGED_DIR = "changed_dir"

        private const val TAG = "TextViewer"
        private const val PREFS = "text_viewer_prefs"

        /**
         * 最多记录多少条命中。控件只画当前可见的那一部分，所以这个上限只影响
         * 计数精度和命中列表长度，不影响绘制开销。
         */
        private const val MAX_MATCHES = 20_000

        /** 命中列表最多列多少条（再多也不适合扫着看） */
        private const val MATCH_LIST_MAX = 500

        /** 查找输入的防抖间隔 */
        private const val FIND_DEBOUNCE_MS = 250L

        private const val INITIAL_MATCH_CAPACITY = 256

        /** 普通命中：半透明琥珀色 */
        private const val NORMAL_MATCH_BG = 0x66FFC107.toInt()

        /** 当前命中：实心橙，和普通命中一眼就能分开 */
        private const val CURRENT_MATCH_BG = 0xFFFFA000.toInt()

        /** 当前命中的描边：深棕，深浅两套主题下都能把橙底"框"出来 */
        private const val CURRENT_MATCH_STROKE = 0xFF7A3B00.toInt()

        /** 跳转闪烁用的瞬时色 */
        private const val FLASH_MATCH_BG = 0xFFFFF59D.toInt()

        /** 闪烁步数（奇数步是亮色，偶数步回到实心橙；最后一步定住） */
        private const val PULSE_STEPS = 4
        private const val PULSE_INTERVAL_MS = 90L

        /** 命中列表里每条上下文取前后多少个字符 */
        private const val SNIPPET_CONTEXT = 28

        private val WHITESPACE_RUN = Regex("\\s{2,}")

        /**
         * 单片字符数上限 —— **这才是"一次给控件多少字符"的真正闸门**。
         *
         * ## 3.14.0 在这里错了一次，错在取值依据
         *
         * 当时取 200 万，理由是"与旧实现的 `DISPLAY_LIMIT_NORMAL` 对齐，
         * 每一片都不会比原来一次显示的那一大坨更吃力"。**这个基线根本不存在**：
         * 旧实现有 `MAX_FULL_BYTES = 4MB`，超过就只读前 512KB 并禁止编辑 ——
         * 也就是说"一次显示 200 万字符"这条路**从来没被真机走到过**。
         * 把一个没验证过的理论值当成"不更差的底线"，于是真机第一次走到它时，
         * 症状是：加载要等、滑不动、ANR、点编辑闪退。
         *
         * ## 正确的判据是"用户一次要看多少"
         *
         * 控件的代价只跟"一次塞进去多少字符"成正比、跟文件多大无关 ——
         * 这一点 3.14.0 说对了，错的只是拿旧上限当依据。真正该问的是：
         *
         *  - 一屏大约 30~60 显示行、每行 40~80 字符，即 **一屏 2000~5000 字符**；
         *  - 取 10 万 ≈ **20~50 屏**，正常阅读几乎碰不到片边界；
         *  - 10 万字符的 `StaticLayout` / `DynamicLayout` 构建是几十毫秒级，
         *    200 万字符则是**秒级** —— 那正是 ANR 与闪退的来源；
         *  - 每翻一页的临时分配从 ~16.5MB 降到 ~200KB（实测见 `tools/perf-check`）。
         *
         * 片变小只意味着"翻页次数变多"，而片导航（上/下、选择器、按行跳转、
         * 全文查找）3.14.0 就已经做好了，这个代价是已经付过的。
         *
         * 原来那个 `DISPLAY_LIMIT_TRUNCATED`（40 万）连同"大文件只看前 512KB"
         * 一起删掉了 —— 它们的存在本身就是"按文件大小禁功能"这套错判据的一部分。
         */
        private const val SLICE_CHARS = 100_000

        /**
         * 片内最长行超过这个字符数，就**强制折行**渲染。
         *
         * 不折行模式（默认）下 `contentText` 是 `WRAP_CONTENT`，宽度由**最长行**决定：
         * 一行 10 万字符会撑出几十万像素宽的 Layout，`StaticLayout` 还得为它整行测宽，
         * 表现就是"进得去但滑不动"。压缩过的 JSON、base64、单行日志都会命中。
         *
         * 折行之后宽度由控件宽度决定，超长行被排成多个**显示行**，滚动恢复正常。
         * 它只影响显示：`\n` 的个数没变，所以行号、查找、写回全都不受影响。
         */
        private const val LONG_LINE_WRAP = 500

        /** 探测编码时读文件头这么多字节就够（判据是先看 BOM、再看是不是合法 UTF-8） */
        private const val DETECT_BYTES = 256 * 1024

        private const val STATE_EDITS = "edited_slices"
        private const val STATE_SLICE = "slice_index"

        /** 写回失败：应用身份写不进、也没有 root */
        private const val WRITE_FAILED = 0

        /** 写回成功：直接覆盖 */
        private const val WRITE_OK = 1

        /** 写回成功：借 root 覆盖 */
        private const val WRITE_ROOT = 2

        /** 拼接结果没通过行数核对 —— **原文件未动**，需要重新加载 */
        private const val WRITE_VERIFY_FAILED = 3

        private const val WRITE_CONFLICT = 4
    }
}
