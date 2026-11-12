package com.rxinfiles.tool

import android.content.Intent
import android.graphics.Typeface
import android.text.format.DateFormat
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.util.Locale

/**
 * 压缩包内容预览面板。
 *
 * 「预览压缩文件内容」这件事的价值在于**不落盘就能判断里面有没有想要的东西**，
 * 所以这里把两件事都做了：列出条目（名称/大小/时间/目录结构），
 * 以及直接点开里面的文本或图片 —— 后者会把单个条目解到私有缓存再看。
 * 那份副本目录的所有权随 `EXTRA_STAGED_DIR` 交给查看器，由查看器真正退出时释放，
 * 冷启动另有兜底清理；面板自己不递归删除共享的暂存根目录。
 *
 * 面板本身不管解压，解压交给 Activity（那边有进度对话框和目录刷新逻辑）。
 *
 * 加密包（RAR / 7z）会在这里问一次密码，并在**本次会话内记住** ——
 * 列表、预览、解压共用同一个密码，不会每点一次问一遍。
 *
 * @param onExtractAll   解压全部到指定目录（null 表示与压缩包同名的默认目录）
 * @param onExtractEntry 解压单个条目（第三个参数为 null 表示当前目录）
 *                       两个回调的最后一个参数都是密码（没有则是 null）
 */
class ArchiveSheet(
    private val activity: AppCompatActivity,
    private val archive: File,
    private val onExtractAll: (File?, CharArray?) -> Unit,
    private val onExtractEntry: (File, String, File?, CharArray?) -> Unit
) {

    private val sheet = BottomSheetDialog(activity)

    /**
     * 面板内部的「对话框」：密码框 / 条目菜单 / 解压目录输入都画在面板自己身上。
     *
     * 加密包唯一的路就是那个密码框，框弹不出来等于整个加密功能不存在 ——
     * 所以这里坚决不在面板之上再开窗口。见 [SheetDialog] 的说明。
     */
    private lateinit var panel: SheetDialog
    private lateinit var adapter: EntryAdapter
    private lateinit var root: View
    private lateinit var stateBox: View
    private lateinit var stateIcon: View
    private lateinit var progressBar: View
    private lateinit var stateText: TextView
    private lateinit var pathText: TextView
    private lateinit var upButton: TextView
    private lateinit var searchInput: android.widget.EditText

    private var listing: ArchiveRepository.Listing? = null
    private var allEntries: List<ArchiveRepository.Entry> = emptyList()
    private var currentDirectory = ""
    private var searchQuery = ""
    private var passwordProtected = false

    /** 本次会话记住的密码；只有 RAR / 7z 用得上 */
    private var password: CharArray? = null

    /** 是否已经问过一次 —— 用来区分「本来就要密码」和「密码输错了」 */
    private var askedPassword = false

    fun show() {
        root = LayoutInflater.from(activity).inflate(R.layout.dialog_archive, null)
        sheet.setContentView(root)
        panel = SheetDialog(sheet, root)
        stateBox = root.findViewById(R.id.archiveState)
        stateIcon = root.findViewById(R.id.archiveStateIcon)
        progressBar = root.findViewById(R.id.archiveProgress)
        stateText = root.findViewById(R.id.archiveStateText)
        pathText = root.findViewById(R.id.archivePath)
        upButton = root.findViewById(R.id.archiveUp)
        searchInput = root.findViewById(R.id.archiveSearch)
        upButton.setOnClickListener { goUp() }
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString()?.trim().orEmpty()
                renderEntries()
            }
        })
        sheet.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                event.action == android.view.KeyEvent.ACTION_UP
            ) {
                handleBack()
                true
            } else {
                false
            }
        }

        root.findViewById<TextView>(R.id.archiveName).text = archive.name
        root.findViewById<TextView>(R.id.archiveInfo).text = "正在读取…"
        root.findViewById<TextView>(R.id.archiveClose).setOnClickListener { sheet.dismiss() }

        val list = root.findViewById<RecyclerView>(R.id.archiveList)
        adapter = EntryAdapter(
            onClick = { entry -> onEntryClicked(entry) },
            onLongClick = { entry -> showEntryMenu(entry) }
        )
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter

        root.findViewById<TextView>(R.id.extractAllBtn).setOnClickListener {
            if (listing == null) {
                toast("还没读完，稍等一下")
            } else {
                ensurePassword {
                    sheet.dismiss()
                    onExtractAll(null, password)
                }
            }
        }
        root.findViewById<TextView>(R.id.extractHereBtn).setOnClickListener {
            if (listing == null) {
                toast("还没读完，稍等一下")
            } else {
                ensurePassword {
                    askDestination { dir ->
                        sheet.dismiss()
                        onExtractAll(dir, password)
                    }
                }
            }
        }

        // 撑全屏 + 容器透明统一交给 SheetDialog（show 之后才生效，见 expandFullScreen）。
        // 此前这里自己调 glassSurface() + resizeSheet() 撑到 72%：结果是遮罩只盖住上面那 72%，
        // 而容器又是不透明玻璃 —— 底部 28% 的列表亮着，整屏还被涂成一块死色。
        panel.expandFullScreen()
        sheet.show()
        load()
    }

    /**
     * 解压前把密码要到手。
     *
     * ZIP 的加密是**逐条目**的：文件名是明文，列表照样能列出来 ——
     * 所以「要不要密码」不能在打开阶段判断，得等到真要解东西的时候。
     * 已经拿到密码（或包里没有加密条目）就直接放行。
     */
    private fun ensurePassword(entry: ArchiveRepository.Entry? = null, then: () -> Unit) {
        val needed = passwordProtected || entry?.encrypted == true ||
            (entry == null && listing?.entries?.any { it.encrypted } == true)
        if (!needed || password?.isNotEmpty() == true) {
            then()
            return
        }
        panel.input(
            title = "需要密码",
            message = "这个压缩包里有加密条目，解压需要密码。",
            value = "",
            hint = "压缩包密码",
            okText = "确定",
            password = true,
            onOk = { text ->
                if (text.isEmpty()) {
                    toast("没有输入密码")
                } else {
                    password = text.toCharArray()
                    then()
                }
            },
            onCancel = {}
        )
    }

    /** 面板高度固定为屏幕的 72%：条目多时能滚动，条目少时也不至于顶满全屏 */
    private fun load() {
        stateBox.visibility = View.VISIBLE
        stateIcon.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        stateText.text = "正在读取压缩包…"

        val pw = password
        Thread {
            // 内容加密的 RAR/7z 可以正常列出文件名，也必须记住整包密码状态。
            val (result, needsPassword) = ArchiveRepository.inspect(activity, archive, pw)
            activity.runOnUiThread {
                if (sheet.isShowing.not()) return@runOnUiThread
                passwordProtected = needsPassword
                val loaded = result.listing
                if (loaded != null) {
                    listing = loaded
                    render(loaded)
                    return@runOnUiThread
                }
                progressBar.visibility = View.GONE
                if (needsPassword && !askedPassword) {
                    askedPassword = true
                    askPassword()
                } else {
                    showLoadError(result.error)
                }
            }
        }.start()
    }

    /**
     * 询问密码 —— 画在**面板内部**，输错不关面板，允许再输一次。
     *
     * 加密包唯一的出路就是密码，判错一次就把面板关掉、让用户从头点一遍，
     * 属于没必要的惩罚。
     */
    private fun askPassword() {
        panel.input(
            title = "需要密码",
            message = "这是个加密压缩包，密码只在本次打开期间使用，不会保存。",
            value = "",
            hint = "压缩包密码",
            okText = "确定",
            password = true,
            onOk = { text ->
                if (text.isEmpty()) {
                    toast("没有输入密码")
                    showLoadError("加密压缩包需要密码才能读取")
                } else {
                    password = text.toCharArray()
                    askedPassword = true
                    load()
                }
            },
            onCancel = { showLoadError("加密压缩包需要密码才能读取") }
        )
    }

    /** 读不出来的统一出口：把真实原因写清楚，而不是只说「格式不支持」 */
    private fun showLoadError(error: String?) {
        progressBar.visibility = View.GONE
        stateIcon.visibility = View.GONE
        stateBox.visibility = View.VISIBLE
        stateText.text = buildString {
            append(error ?: "无法读取这个压缩包")
            append("\n\n可读格式：ZIP / 7z / RAR / tar(.gz .bz2 .xz) / gzip / bzip2 / xz / lzma")
            append("\n若格式没问题，也可能是当前身份没有读取权限。")
        }
    }

    private fun render(result: ArchiveRepository.Listing) {
        val charsetHint = result.charset?.takeIf { it != "UTF-8" }?.let { "（文件名编码 $it）" } ?: ""
        val info = buildString {
            append(result.kindLabel).append(" · ")
            append(result.fileCount).append(" 个文件")
            if (result.entries.size > result.fileCount) {
                append("、").append(result.entries.size - result.fileCount).append(" 个目录")
            }
            append('\n')
            append("解压后约 ").append(FileRepository.formatSize(result.totalSize))
            append(" · 压缩包 ").append(FileRepository.formatSize(result.fileSize))
            append(charsetHint)
        }
        root.findViewById<TextView>(R.id.archiveInfo)?.text = info
        // ZIP 的加密是逐条目的：列表列得出来、但解压要密码。
        // 单独挂一个标签说清楚，用户就不会觉得密码框是「突然冒出来的」。
        root.findViewById<TextView>(R.id.archiveLockTag)?.visibility =
            if (passwordProtected || result.entries.any { it.encrypted }) View.VISIBLE else View.GONE
        allEntries = result.entries
        renderEntries()
    }

    private fun renderEntries() {
        if (!::adapter.isInitialized) return
        val searching = searchQuery.isNotEmpty()
        val entries = if (searching) {
            allEntries.filter { it.name.contains(searchQuery, ignoreCase = true) }
        } else {
            directChildren()
        }
        pathText.text = if (searching) "搜索：$searchQuery" else "/$currentDirectory"
        val canGoUp = searching || currentDirectory.isNotEmpty()
        upButton.isEnabled = canGoUp
        upButton.alpha = if (canGoUp) 1f else 0.3f
        adapter.submit(entries, searching)

        val empty = entries.isEmpty()
        stateBox.visibility = if (empty) View.VISIBLE else View.GONE
        stateIcon.visibility = if (empty) View.VISIBLE else View.GONE
        progressBar.visibility = View.GONE
        stateText.text = when {
            searching -> "没有找到包含「$searchQuery」的条目"
            allEntries.isEmpty() -> "压缩包是空的"
            currentDirectory.isNotEmpty() -> "这个目录是空的"
            else -> "没有可显示的条目"
        }
    }

    private fun directChildren(): List<ArchiveRepository.Entry> {
        val direct = LinkedHashMap<String, ArchiveRepository.Entry>()
        val implicit = LinkedHashMap<String, ArchiveRepository.Entry>()
        for (entry in allEntries) {
            val name = entry.name.replace('\\', '/')
            if (!name.startsWith(currentDirectory)) continue
            val remainder = name.substring(currentDirectory.length)
            if (remainder.isEmpty()) continue
            val slash = remainder.indexOf('/')
            if (slash < 0 || (entry.isDirectory && slash == remainder.lastIndex)) {
                direct[name] = entry.copy(name = name)
                continue
            }
            val childName = currentDirectory + remainder.substring(0, slash) + "/"
            if (!direct.containsKey(childName)) {
                implicit[childName] = ArchiveRepository.Entry(
                    name = childName,
                    size = 0L,
                    packed = 0L,
                    isDirectory = true,
                    time = 0L,
                    unavailable = false,
                    encrypted = false
                )
            }
        }
        implicit.forEach { (name, entry) -> if (!direct.containsKey(name)) direct[name] = entry }
        return direct.values.sortedWith(
            compareBy<ArchiveRepository.Entry> { !it.isDirectory }
                .thenBy { it.name.lowercase(Locale.US) }
        )
    }

    private fun openDirectory(entry: ArchiveRepository.Entry) {
        currentDirectory = entry.name.replace('\\', '/').trimEnd('/') + "/"
        searchInput.setText("")
        renderEntries()
    }

    private fun goUp() {
        if (searchQuery.isNotEmpty()) {
            searchInput.setText("")
            return
        }
        if (currentDirectory.isEmpty()) return
        val trimmed = currentDirectory.trimEnd('/')
        val slash = trimmed.lastIndexOf('/')
        currentDirectory = if (slash < 0) "" else trimmed.substring(0, slash + 1)
        renderEntries()
    }

    private fun handleBack() {
        when {
            searchQuery.isNotEmpty() -> searchInput.setText("")
            currentDirectory.isNotEmpty() -> goUp()
            else -> sheet.dismiss()
        }
    }

    // ------------------------------------------------------------------ 交互

    private fun onEntryClicked(entry: ArchiveRepository.Entry) {
        when {
            entry.isDirectory -> openDirectory(entry)
            isPreviewable(entry) -> ensurePassword(entry) { preview(entry) }
            else -> showEntryMenu(entry)
        }
    }

    private fun showEntryMenu(entry: ArchiveRepository.Entry) {
        val actions = arrayOf("解压此项到当前目录", "解压此项到指定目录…", "复制条目名")
        panel.menu(
            title = entry.name,
            message = when {
                entry.encrypted -> "加密条目：解压需要密码"
                entry.isDirectory -> "目录：解压时会连同内部文件一起展开"
                else -> "大小 ${FileRepository.formatSize(entry.size)}"
            },
            actions = actions.toList(),
            onPick = { which ->
                when (actions[which]) {
                    "解压此项到当前目录" -> ensurePassword(entry) {
                        sheet.dismiss()
                        onExtractEntry(archive, entry.name, null, password)
                    }
                    "解压此项到指定目录…" -> ensurePassword(entry) {
                        askDestination { dir ->
                            sheet.dismiss()
                            onExtractEntry(archive, entry.name, dir, password)
                        }
                    }
                    "复制条目名" -> {
                        Intents.copyToClipboard(activity, entry.name, "entry")
                        toast("已复制条目名")
                    }
                }
            }
        )
    }

    /** 目录条目没有大小概念，文本类条目过大也不适合直接预览 */
    private fun isPreviewable(entry: ArchiveRepository.Entry): Boolean {
        if (entry.isDirectory) return false
        if (FileRepository.isImageName(entry.name)) return entry.size <= MAX_IMAGE_PREVIEW
        if (FileRepository.isTextName(entry.name)) return entry.size <= MAX_TEXT_PREVIEW
        return false
    }

    private fun preview(entry: ArchiveRepository.Entry) {
        if (listing == null) return
        stateBox.visibility = View.VISIBLE
        stateIcon.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        stateText.text = "正在取出 ${File(entry.name).name} …"
        val pw = password
        Thread {
            val file = ArchiveRepository.previewEntry(activity, archive, entry.name, pw)
            activity.runOnUiThread {
                progressBar.visibility = View.GONE
                stateBox.visibility = View.GONE
                if (file == null) {
                    toast("取出条目失败（可能权限不足）")
                    return@runOnUiThread
                }
                // 预览副本目录的所有权交给查看器：它负责在真正退出界面时释放；
                // 旋转重建时不能删，否则重建出来的实例找不到这个文件
                val stagedDir = file.parentFile?.absolutePath
                val intent = when {
                    FileRepository.isImageName(entry.name) ->
                        Intent(activity, ImageViewerActivity::class.java)
                            .putExtra(ImageViewerActivity.EXTRA_PATH, file.absolutePath)
                            .putExtra(ImageViewerActivity.EXTRA_STAGED_DIR, stagedDir)
                    else ->
                        Intent(activity, TextViewerActivity::class.java)
                            .putExtra(TextViewerActivity.EXTRA_PATH, file.absolutePath)
                            .putExtra(TextViewerActivity.EXTRA_STAGED_DIR, stagedDir)
                }
                try {
                    activity.startActivity(intent)
                } catch (t: Throwable) {
                    Log.w(TAG, "preview launch failed", t)
                    toast("无法打开预览：${file.absolutePath}")
                }
                // 提示用户这是临时文件，别把它当成真的解压结果
                toast("预览副本在应用缓存目录，需要保留请用「解压」")
            }
        }.start()
    }

    private fun askDestination(onPicked: (File) -> Unit) {
        panel.input(
            title = "解压到指定目录",
            message = "目录不存在时会自动创建。",
            value = File(archive.parentFile, ArchiveRepository.baseNameOf(archive.name)).absolutePath,
            hint = "解压到的目录",
            okText = "解压",
            password = false,
            onOk = { text ->
                val path = text.trim()
                if (path.isNotEmpty()) {
                    onPicked(File(path))
                }
            },
            onCancel = {}
        )
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ 列表

    private inner class EntryAdapter(
        private val onClick: (ArchiveRepository.Entry) -> Unit,
        private val onLongClick: (ArchiveRepository.Entry) -> Unit
    ) : RecyclerView.Adapter<EntryAdapter.Holder>() {

        private inner class Row(
            val entry: ArchiveRepository.Entry,
            val showFullPath: Boolean
        )

        private var rows: List<Row> = emptyList()

        private val colorPrimary = ContextCompat.getColor(activity, R.color.primary)
        private val colorWarn = TAG_COLOR_WARN
        private val colorSecondary =
            resolveThemeColor(android.R.attr.textColorSecondary, TAG_COLOR_WARN)

        fun submit(list: List<ArchiveRepository.Entry>, showFullPath: Boolean) {
            rows = list.map { Row(it, showFullPath) }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context)
                .inflate(R.layout.item_archive_entry, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(rows[position])
        }

        override fun getItemCount(): Int = rows.size

        /** 读主题色，保证浅色 / 深色主题下标签文字都不糊（与 MainActivity.themeTextColor 同一套路） */
        private fun resolveThemeColor(attr: Int, fallback: Int): Int {
            val value = android.util.TypedValue()
            val resolved = activity.theme.resolveAttribute(attr, value, true)
            return when {
                resolved && value.resourceId != 0 -> ContextCompat.getColor(activity, value.resourceId)
                resolved -> value.data
                else -> fallback
            }
        }

        /**
         * 压缩收益：压缩后比原大小少了几成。
         *
         * 只在「本来就不小、而且真的省了 ≥10%」时才提 ——
         * 否则满屏「省 1%」纯属噪音。
         */
        private fun savedPercent(entry: ArchiveRepository.Entry): Int? {
            if (entry.unavailable || entry.packed <= 0 || entry.size < 1024) return null
            if (entry.packed >= entry.size) return null
            val saved = ((entry.size - entry.packed) * 100 / entry.size).toInt()
            return if (saved >= 10) saved else null
        }

        /** 扩展名小标签；没有像样的扩展名就返回 null（不给每行硬塞东西） */
        private fun extensionOf(name: String): String? {
            val ext = name.substringAfterLast('.', "")
            if (ext.isEmpty() || ext.length > 5 || ext.contains('/')) return null
            return ext.lowercase(Locale.US)
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val icon: ImageView = view.findViewById(R.id.entryIcon)
            private val name: TextView = view.findViewById(R.id.entryName)
            private val detail: TextView = view.findViewById(R.id.entryDetail)
            private val action: TextView = view.findViewById(R.id.entryAction)

            fun bind(row: Row) {
                val entry = row.entry
                val trimmed = TreeRails.trimTail(entry.name)
                name.text = if (row.showFullPath) {
                    entry.name
                } else {
                    trimmed.substringAfterLast('/').ifEmpty { entry.name }
                }
                name.setTypeface(
                    null,
                    if (entry.isDirectory) Typeface.BOLD else Typeface.NORMAL
                )

                icon.setImageResource(iconFor(entry))

                detail.text = buildString {
                    if (entry.isDirectory) {
                        append("目录")
                    } else {
                        append(
                            if (entry.unavailable) "大小未知"
                            else FileRepository.formatSize(entry.size)
                        )
                        if (entry.time > 0) {
                            append(" · ").append(DateFormat.format("yyyy-MM-dd", entry.time))
                        }
                        savedPercent(entry)?.let { append(" · 省 ").append(it).append('%') }
                    }
                }

                bindTag(entry)

                itemView.setOnClickListener { onClick(entry) }
                itemView.setOnLongClickListener {
                    onLongClick(entry)
                    true
                }
            }

            private fun iconFor(entry: ArchiveRepository.Entry): Int = when {
                entry.isDirectory -> R.drawable.ic_folder
                FileRepository.isImageName(entry.name) -> R.drawable.ic_image
                FileRepository.isTextName(entry.name) -> R.drawable.ic_text
                FileRepository.isArchiveName(entry.name) -> R.drawable.ic_archive
                FileRepository.isStrictScriptName(entry.name) -> R.drawable.ic_script
                else -> R.drawable.ic_file
            }

            /**
             * 右侧那个小胶囊。
             *
             * 顺序是有讲究的：先说「能不能用」（可预览 / 需密码），
             * 再说「这是什么文件」（扩展名）—— 前者决定用户下一步做什么。
             */
            private fun bindTag(entry: ArchiveRepository.Entry) {
                val label: String
                val background: Int
                val color: Int
                when {
                    entry.isDirectory -> {
                        label = "进入"
                        background = R.drawable.bg_tag_soft
                        color = colorPrimary
                    }
                    entry.encrypted -> {
                        label = "需密码"
                        background = R.drawable.bg_tag_warn
                        color = colorWarn
                    }
                    isPreviewable(entry) -> {
                        label = "可预览"
                        background = R.drawable.bg_tag_soft
                        color = colorPrimary
                    }
                    else -> {
                        label = extensionOf(entry.name) ?: run {
                            action.visibility = View.GONE
                            return
                        }
                        background = R.drawable.bg_tag_plain
                        color = colorSecondary
                    }
                }
                action.text = label
                action.setBackgroundResource(background)
                action.setTextColor(color)
                action.visibility = View.VISIBLE
            }
        }
    }

    private companion object {
        const val TAG = "ArchiveSheet"

        /** 预览单个条目的体积上限 */
        const val MAX_TEXT_PREVIEW = 4L * 1024 * 1024
        const val MAX_IMAGE_PREVIEW = 32L * 1024 * 1024

        /** 「需密码」标签的字色，与 bg_tag_warn 的底色配套 */
        val TAG_COLOR_WARN = 0xFFC77800.toInt()
    }
}
