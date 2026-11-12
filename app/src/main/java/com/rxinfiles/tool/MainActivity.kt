package com.rxinfiles.tool

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 文件管理主界面。
 *
 * 相比最初的重建版本，这里承担的事情多了不少，按职责分成几块：
 *
 *  - **打开分派**：按类型决定用哪个内置查看器（图片 / 视频 / 文本 / 压缩包 / 脚本），
 *    类型两可的（.js、.py）不替用户决定，弹「打开方式」；
 *  - **文件操作**：复制 / 剪切 / 粘贴 / 移动 / 重命名 / 删除 / 压缩 / 解压，
 *    全部有进度与取消，权限不足时由 [FileOps] 自动降级到 root；
 *  - **多选**：长按进多选，底部换成批量操作条；
 *  - **权限**：自动探测（ROOT → ADB → 普通），不提供手动切换；
 *    唯一的例外是**执行脚本时的提权勾选框** —— 每次执行脚本前都会弹一个确认面板，
 *    问的是「要不要执行」，里面带一个「以 ROOT 身份执行（su）」的勾选框
 *    （只在探测到 root 时出现），勾选状态会被记住，作为下次的预勾选。
 *    身份影响的是用户自己的脚本能碰到哪些文件，不该由应用默默替他决定。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var pathText: TextView
    private lateinit var loadingText: TextView
    private lateinit var emptyView: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FileListAdapter
    private lateinit var normalBar: View
    private lateinit var selectionBar: View
    private lateinit var selCount: TextView
    private lateinit var pasteBtn: TextView

    private var currentPath: String = ""
    private var fileItems: List<FileItem> = emptyList()
    private val mutedIconColor by lazy { ContextCompat.getColor(this, R.color.text_secondary) }

    /**
     * 待高亮的绝对路径（来自外部「用其他应用打开」入口）。
     *
     * 目录是异步加载的，所以先记下来，等 [loadDir] 把列表装好之后再消费。
     * **它与 [selectedPaths] 完全无关** —— 那是多选态的状态，绝不能被「定位」污染：
     * 否则用户一进多选就会看到莫名其妙的选中项（见 [FileListAdapter.setReveal]）。
     */
    private var pendingReveal: String? = null

    /**
     * 「已打开所在目录但没找到该文件」这句提示是否已经说过。
     *
     * [pendingReveal] 未命中时会被**保留**（见 [applyPendingReveal] 的时序说明），
     * 于是每次 onResume / 刷新都会再调一次 [applyPendingReveal]；没有这个开关的话，
     * 一个确实已消失的文件会让那句提示被反复重放。新来一次定位（[revealFile]）时复位。
     */
    private var revealMissNotified = false

    /**
     * 「这个目录需要 Shizuku」这张提醒，[currentPath] 这一轮已经说过了。
     *
     * 按**路径**记（不是全局布尔）：[onResume] / 刷新 / 书签跳转都会重新 [loadDir] 同一个目录，
     * 没有它面板会反复自己弹；而换到别的目录再回来等于重新问了一次「这里能不能打开」，
     * 那时该再提醒一次。详见 [maybeWarnAndroidData]。
     */
    private var noAccessWarnedFor: String? = null

    /**
     * 当前正在跑的目录加载。
     *
     * [loadDir] 每次开新加载前先 [Job.cancel] 掉旧的：否则「旧目录的 IO 续体」可能在
     * 「新目录」之后才回到主线程，把列表改写回旧目录（冷启动时 A/B/C 三处并发加载
     * 尤其容易撞上，见 [revealFile] 的时序图）。
     */
    private var loadJob: Job? = null
    private var operationDialog: OperationProgressDialog? = null
    private var archiveJob: Job? = null
    private var archiveSession: ArchiveSession? = null

    private class ArchiveSession(
        val archive: File,
        val listing: ArchiveRepository.Listing,
        val passwordProtected: Boolean
    ) {
        var directory: String = ""
        var password: CharArray? = null
    }

    /** 高亮自动消退用的定时器 */
    private val revealHandler = Handler(Looper.getMainLooper())
    private var revealRunnable: Runnable? = null

    /** 多选模式下的已选路径（按选择顺序保存，便于提示） */
    private var selectionMode = false
    private val selectedPaths = LinkedHashSet<String>()

    /**
     * 正在跑的管道执行（[ScriptPipe]）。
     *
     * 只在 Activity 里持有：脚本的存活**不依赖**日志窗口是否打开 —— 用户点「后台运行」
     * 收起窗口后它照跑。但 Activity 真正结束（isFinishing）时要收掉，否则会留下
     * 一个父进程已死的孤儿 sh。
     */
    private var pipeHandle: ScriptPipe.Handle? = null
    private var pipeDialog: ScriptLogDialog? = null

    private val customTextKey = "loading_text"

    /**
     * 上一次执行脚本时用户选的身份（true = ROOT）。
     *
     * 这个键是「记住用户习惯」用的：每次执行前仍会弹一次身份选择，
     * 但预选项就是这里的值 —— 习惯固定的话连点两下即可，换身份也只多点一下。
     * 权限边界这种东西不该由应用替用户默默决定，所以只做预选、不做静默执行。
     */
    private val scriptRootKey = "script_as_root"

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 结果由 onResume 统一刷新 */ }

    private val shizukuPermissionListener =
        rikka.shizuku.Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == ShizukuBridge.REQUEST_CODE) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                toast(if (granted) "Shizuku 权限已授予" else "Shizuku 权限被拒绝")
                detectShellLevel()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ShizukuBridge 的初始化已在 RxinApp.onCreate 完成（3.15.2）——
        // 放在这里会漏掉 IncomingActivity 那条外部打开入口，见 RxinApp 的成文说明。
        ShizukuBridge.addPermissionListener(shizukuPermissionListener)

        // 不再在界面创建/重建时清全局暂存：暂存目录按「谁创建谁释放」管理，
        // 冷启动的兜底清理已由 RxinApp.onCreate 在后台线程调用 sweepStaleStage 完成。
        // 在这里递归删 cacheDir/rxin_stage 会连带删掉其他界面或后台任务正在用的副本（BUG-005）。

        bindViews()
        setupList()

        currentPath = intent.getStringExtra(EXTRA_START_PATH) ?: defaultStartPath()
        initWorkDir()
        detectShellLevel()

        // 从外部应用「用其他应用打开」进来的文件：入口只负责反解路径，定位 + 高亮在这里。
        // 放在最后处理 —— 目录初始化、shell 权限探测都跑完了，列表才装得下内容。
        intent.getStringExtra(EXTRA_REVEAL_PATH)?.let { revealFile(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 复用已有实例时（singleTop/FLAG_ACTIVITY_SINGLE_TOP 等）也要能定位
        setIntent(intent)
        intent.getStringExtra(EXTRA_REVEAL_PATH)?.let { revealFile(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        ShizukuBridge.removePermissionListener(shizukuPermissionListener)
        // 高亮定时器挂在主线程 Handler 上：不定时收掉的话，Activity 没了它还会醒来回调 adapter
        revealRunnable?.let { revealHandler.removeCallbacks(it) }
        revealRunnable = null
        operationDialog?.dismiss()
        operationDialog = null
        // 日志窗口挂在 Activity 的 window token 上：旋转重建时**必须先收掉**，
        // 否则会留下一个泄漏的窗口。收掉之后这一轮的日志界面就没了（脚本还在跑），
        // 结果回调里的 `pipeDialog === dialog` 守卫会因此不再落地 —— 正合预期。
        pipeDialog?.dismiss()
        pipeDialog = null
        // 真正离开界面（不是旋转）时连进程一起收掉 —— 父进程没了，子 shell 会变成孤儿
        if (isFinishing) {
            pipeHandle?.cancel()
            pipeHandle = null
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (archiveSession != null) archiveUp() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (archiveSession != null) {
            renderArchiveEntries()
        } else if (currentPath.isNotEmpty()) {
            loadDir(currentPath)
        }
        updateLoadingText()
        updatePasteButton()
    }

    // ------------------------------------------------------------- 初始化

    private fun bindViews() {
        pathText = findViewById(R.id.pathText)
        loadingText = findViewById(R.id.loadingText)
        emptyView = findViewById(R.id.emptyView)
        recyclerView = findViewById(R.id.recyclerView)
        normalBar = findViewById(R.id.normalBar)
        selectionBar = findViewById(R.id.selectionBar)
        selCount = findViewById(R.id.selCount)
        // 排序偏好必须在第一次列目录**之前**读进来：排序由 FileRepository 在列目录时应用，
        // 晚一步的话首屏会用默认顺序，随后又被重列刷新 —— 用户看到的是列表「闪一下」才归位。
        loadSortPrefs()

        pasteBtn = findViewById(R.id.pasteBtn)

        findViewById<ImageButton>(R.id.upBtn).setOnClickListener { navigateUp() }
        findViewById<ImageButton>(R.id.refreshBtn).setOnClickListener {
            archiveSession?.let { openArchive(it.archive, it.password) } ?: loadDir(currentPath)
        }
        findViewById<TextView>(R.id.terminalBtn).setOnClickListener { openTerminal(currentPath) }
        findViewById<ImageButton>(R.id.moreMenuBtn).setOnClickListener { showMoreMenu() }
        findViewById<TextView>(R.id.quickBookmarkBtn).setOnClickListener { showBookmarkDialog() }
        findViewById<TextView>(R.id.quickSortBtn).setOnClickListener { showSortSheet() }

        pathText.setOnClickListener { showPathDialog() }
        pathText.setOnLongClickListener {
            showPathActionDialog()
            true
        }

        loadingText.setOnClickListener { showShellStatusDialog() }

        findViewById<TextView>(R.id.navHome).setOnClickListener { loadDir(defaultStartPath()) }
        findViewById<TextView>(R.id.navSdcard).setOnClickListener {
            loadDir(Environment.getExternalStorageDirectory().absolutePath)
        }
        findViewById<TextView>(R.id.navRoot).setOnClickListener { loadDir("/") }

        pasteBtn.setOnClickListener { pasteInto(currentPath) }
        pasteBtn.setOnLongClickListener {
            showClipboardMenu()
            true
        }

        findViewById<TextView>(R.id.selCancel).setOnClickListener { exitSelection() }
        findViewById<TextView>(R.id.selAll).setOnClickListener { selectAll() }
        findViewById<TextView>(R.id.selCopy).setOnClickListener {
            selectionToClipboard(FileClipboard.Mode.COPY)
        }
        findViewById<TextView>(R.id.selCut).setOnClickListener {
            selectionToClipboard(FileClipboard.Mode.MOVE)
        }
        findViewById<TextView>(R.id.selZip).setOnClickListener { compressSelection() }
        findViewById<TextView>(R.id.selDelete).setOnClickListener { deleteSelection() }
        findViewById<TextView>(R.id.selMore).setOnClickListener { showSelectionMore() }
    }

    private fun setupList() {
        adapter = FileListAdapter(
            onClick = { onFileClicked(it) },
            onLongClick = { onFileLongPressed(it) }
        )
        // 列数按当前窗口宽度取值：手机 1 列，w600dp 起 2 列，w900dp 起 3 列。
        // 用窗口宽度而不是机型，横屏手机与分屏都会得到合适的列数。
        val columns = resources.getInteger(R.integer.file_list_columns).coerceAtLeast(1)
        recyclerView.layoutManager = GridLayoutManager(this, columns)
        recyclerView.adapter = adapter
    }

    private fun defaultStartPath(): String {
        val work = File(Environment.getExternalStorageDirectory(), "RouXin")
        return if (work.exists()) work.absolutePath else "/"
    }

    /** 创建默认工作目录 /storage/emulated/0/RouXin，并铺开内置沙箱 */
    private fun initWorkDir() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    File(Environment.getExternalStorageDirectory(), "RouXin").mkdirs()
                } catch (_: Throwable) {
                }
                // 内置 PATH 沙箱：bin/ 下的命令 + .shinit 函数定义
                RxinSandbox.ensure(this@MainActivity)
                // 内置工具箱：铺开 busybox 垫片（首次约百毫秒级，之后靠状态戳短路）。
                // 放在这里预热，是为了让用户点进终端时不必等 —— 也为了让
                // ScriptRunner 的探测一上来就能看见沙箱里的 bash 等命令。
                RxinToolbox.ensure(this@MainActivity)
            }
        }
    }

    // --------------------------------------------------------------- 权限链

    /**
     * 执行权限探测并把结果写入 [ShellExecutor]，同时刷新状态栏文字。
     * 会执行外部进程，必须在协程中调用。
     */
    private suspend fun refreshShellLevel() {
        val level = withContext(Dispatchers.IO) { ShellExecutor.detect() }
        if (level == ShellLevel.ADB) {
            ShizukuBridge.bindService(this@MainActivity) { }
        }
        shellLevelReady = true
        updateLoadingText()
    }

    /** 只有还没探测过时才探测 */
    private suspend fun ensureShellLevel() {
        if (!shellLevelReady) refreshShellLevel()
    }

    private fun detectShellLevel() {
        lifecycleScope.launch {
            refreshShellLevel()
            if (ShellExecutor.level != ShellLevel.NORMAL) {
                loadDir(currentPath)
            } else {
                requestStoragePermission()
            }
        }
    }

    /**
     * 权限探测是否已经有结果。
     *
     * [ShellExecutor.level] 的初值是 NORMAL，冷启动时探测（可能要弹 su 授权框，
     * 慢起来是几秒）还没回来。这时候如果点脚本就直接按「不能 root → 普通用户」
     * 跑掉，等于绕过了身份询问 —— 所以结果没回来之前要先等一次。
     */
    @Volatile
    private var shellLevelReady = false

    /** 正在等权限探测结果，避免连点弹出多个身份对话框 */
    private var awaitingShellLevel = false

    private fun updateLoadingText() {
        // 空白文字回退默认值，避免状态栏只剩 "[权限] " 前缀
        val custom = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(customTextKey, DEFAULT_TEXT)?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: DEFAULT_TEXT
        loadingText.text = "[${ShellExecutor.level.label}] $custom"
        // 跑马灯只有在 selected 状态才会滚起来；不设这一句，
        // 超长自定义文字会被静默裁切（横屏窄列下尤其明显）。
        loadingText.isSelected = true
    }

    /**
     * 状态栏点击：查看自动探测结果。
     *
     * 不提供「强制 ROOT / 强制 ADB / 仅普通」这类开关 —— 选错级别只会带来
     * 难排查的问题，而级别本身完全可以可靠地探测出来。两个动作都无副作用。
     * 唯一的例外是下面的「脚本默认身份」，它只在执行脚本时影响确认面板里那个
     * 提权勾选框的**预勾选状态**，不影响应用自身，也不影响「每次执行都要过一遍面板」。
     */
    private fun showShellStatusDialog() {
        val rootAvailable = ShellExecutor.level == ShellLevel.ROOT
        val options = mutableListOf("重新探测权限", "编辑状态栏文字")
        if (rootAvailable) {
            options.add("脚本默认身份：${if (lastScriptAsRoot()) "预勾选 ROOT" else "预勾选普通用户"}")
        }
        AlertDialog.Builder(this)
            .setTitle("权限模式（自动探测） · 当前 ${ShellExecutor.level.label}")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "重新探测权限" -> detectShellLevel()
                    "编辑状态栏文字" -> showEditLoadingTextDialog()
                    else -> showScriptIdentityDialog()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showEditLoadingTextDialog() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val current = prefs.getString(customTextKey, DEFAULT_TEXT)?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: DEFAULT_TEXT
        val input = EditText(this).apply {
            setText(current)
            setSelection(text.length)
            hint = "留空恢复默认：$DEFAULT_TEXT"
            maxLines = 1
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle("状态栏文字")
            .setMessage("显示格式：[权限] 自定义文字（长文字会滚动展示）")
            .setView(padded(input))
            .setPositiveButton("保存") { _, _ ->
                val saved = input.text.toString().trim()
                    .takeUnless { it.isEmpty() } ?: DEFAULT_TEXT
                prefs.edit().putString(customTextKey, saved).apply()
                updateLoadingText()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } catch (_: Throwable) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else {
            val needed = listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                permissionLauncher.launch(needed.toTypedArray())
            }
        }
    }

    // ------------------------------------------------------------- 目录加载

    private fun loadDir(path: String) {
        archiveJob?.cancel()
        archiveSession = null
        emptyView.text = "这里空空如也\n当前目录没有可显示的项目"
        updatePasteButton()
        val pathChanged = path != currentPath
        // 串行化：新加载取消旧加载。否则两次加载的 IO 续体可能交错回来 —— 先发起的那次
        // 若在「后发起的」之后才回来，就会用它那份已经过期的列表把界面改写回去。
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            // 一次 IO 同时拿回列表与「是否真的读不出来」（见 FileRepository.listDirChecked）。
            // 分开两次调用会在两个线程时刻之间给目录状态留出变化窗口，结论可能自相矛盾。
            val checked = withContext(Dispatchers.IO) { FileRepository.listDirChecked(path) }
            val items = checked.items
            currentPath = path
            fileItems = items
            // 换目录后原来的选择没有意义了
            if (pathChanged && selectionMode) {
                exitSelection()
            }
            pathText.text = if (selectionMode) {
                "已选 ${selectedPaths.size} 项 · $currentPath"
            } else {
                currentPath
            }
            adapter.submit(items)
            adapter.setSelection(selectionMode, selectedPaths)
            emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            recyclerView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            // 列表装好之后再看有没有「定位过来」的文件要亮一下
            applyPendingReveal()
            maybeWarnAndroidData(path, checked.noAccess)
        }
    }

    /**
     * 浏览到 `Android/data` 且**确实读不出来**时，给一次「这里需要 Shizuku」的提醒。
     *
     * <h2>判据在纯 Java 层，这里只负责「弹不弹」</h2>
     *
     * 是否该提醒由 [IncomingFile.shouldWarnNoAccess] 决定（路径在 Android/data 之下 +
     * 普通身份 + 探针也列不出，三者同时成立）。**这里不再重复判断**，只加一个「这一轮
     * 说过了没有」的闸门。
     *
     * <h2>为什么需要状态开关</h2>
     *
     * [onResume] 每次回到前台都会 [loadDir] 当前目录，刷新键、书签跳转同理。没有开关的话，
     * 用户停在 `Android/data` 里切出去再切回来，这张面板就会**反复自己弹出来** ——
     * 那不是提醒，是骚扰。（同 [revealMissNotified] 的思路。）
     *
     * 开关按**路径**记：用户换到别的目录再回来，等于重新问了一次「这里能不能打开」，
     * 那时再提醒一次是合理的。
     */
    private fun maybeWarnAndroidData(path: String, noAccess: Boolean) {
        if (!noAccess) {
            // 这次读得出来（或压根不在 Android/data）→ 允许下次重新提醒
            noAccessWarnedFor = null
            return
        }
        if (noAccessWarnedFor == path) {
            return
        }
        noAccessWarnedFor = path
        showNoAccessSheet()
    }

    /**
     * 「这个目录需要额外权限」提醒面板。
     *
     * 内容全部画在**面板自己的窗口**里（[R.layout.sheet_no_access] 直接 `setContentView`）
     * —— **绝不在面板之上再开 AlertDialog 之类的第二个窗口**：`BottomSheetDialog` 本身就是
     * 一个窗口，叠加的层级顺序在各 ROM 上不一致，真机表现是「点了没反应、什么都看不到」
     * （教训见 [SheetDialog]）。这一张只有说明 + 三个动作，没有要问的输入/确认，
     * 所以不需要 `sheet_dialog_layer` 那套面板内遮罩。
     *
     * 文案与链接取自 [IncomingFile]，与「外部打开」那张失败面板**共用同一份真源**，
     * 不为这一处再抄一遍。
     */
    private fun showNoAccessSheet() {
        val sheet = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_no_access, null)
        sheet.setContentView(content)
        sheet.setOnShowListener { sheet.glassSurface() }

        content.findViewById<TextView>(R.id.noAccessTitle).text = IncomingFile.NO_ACCESS_TITLE
        content.findViewById<TextView>(R.id.noAccessPath).text = currentPath
        content.findViewById<TextView>(R.id.noAccessText).text = IncomingFile.NO_ACCESS_BODY
        content.findViewById<TextView>(R.id.noAccessUrl).text = IncomingFile.SHIZUKU_DOWNLOAD_URL

        // 「复制下载地址」：没装浏览器 / 想丢进电脑装 Shizuku 时都有用，
        // 也保证那句提示里的可点出路不止一条。
        content.findViewById<TextView>(R.id.noAccessCopy).setOnClickListener {
            Intents.copyToClipboard(this, IncomingFile.SHIZUKU_DOWNLOAD_URL)
            toast("下载地址已复制")
        }
        content.findViewById<TextView>(R.id.noAccessOpen).setOnClickListener {
            openShizukuDownload(sheet)
        }
        content.findViewById<TextView>(R.id.noAccessClose).setOnClickListener { sheet.dismiss() }
        sheet.show()
    }

    /**
     * 打开 Shizuku 的下载页。
     *
     * 用 `ACTION_VIEW` 交给系统浏览器，**不硬编码任何浏览器包名** —— 用户装什么浏览器是他
     * 自己的事，指定包名一旦没装就是 `ActivityNotFoundException`。
     *
     * 没有可用浏览器时**明确说一句**（并把地址带出来），而不是静默什么都不发生 ——
     * 点了没反应是最糟的失败形态。与 `IncomingActivity.openShizukuDownload` 同一套做法。
     */
    private fun openShizukuDownload(sheet: BottomSheetDialog) {
        val url = IncomingFile.SHIZUKU_DOWNLOAD_URL
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            sheet.dismiss()
        } catch (t: Throwable) {
            Log.w(TAG, "无法打开 Shizuku 下载页: $url", t)
            toast("没有可用的浏览器，地址已可见：$url")
        }
    }

    /**
     * 导航到 [path] 所在目录并高亮它。
     *
     * 只在**原位置确实可见**时才会被调用（判据在 IncomingActivity），所以这里零拷贝。
     *
     * <h2>为什么这里必须【同步】写 [currentPath]（时序，改之前先读完）</h2>
     *
     * `lifecycleScope` 用的是 `Dispatchers.Main.immediate`，`launch` 的协程体会**立即**
     * 在当前主线程执行到第一个挂起点（`withContext(Dispatchers.IO)`）为止，之后的续体要等
     * 这条主线程消息跑完才回来。冷启动时这条消息里发生的事按顺序是：
     *
     * <pre>
     * onCreate:
     *   currentPath = defaultStartPath()          // 例如 /storage/emulated/0/RouXin
     *   detectShellLevel()                        // 协程 C：先探测权限，其后 loadDir(currentPath)
     *   revealFile(parent)                        // 协程 A：loadDir(parent)
     * onStart → onResume:
     *   loadDir(currentPath)                      // 协程 B
     * —— 这条消息结束，A/B/C 的 IO 续体才开始陆续回到主线程 ——
     * </pre>
     *
     * `loadDir` 里 `currentPath = path` 是在**续体里**才赋值的。所以如果 [revealFile] 不在
     * 这里**同步**把 [currentPath] 落成 parent，B（以及 C）捕获到的就是**默认目录**：
     *
     * <ul>
     *   <li>B 晚于 A 回来 → 把列表切回默认目录，**盖掉**定位结果，用户看不到高亮；</li>
     *   <li>B 早于 A 回来 → 用默认目录去 [applyPendingReveal]，找不到 → 还会误清待高亮，
     *       于是 A 之后到了正确目录也没有可高亮的了。</li>
     * </ul>
     *
     * 同步赋值后，A/B/C 捕获到的都是**同一个 parent**，最多重复加载一次，结果一致 ——
     * 不可能再互相覆盖。**这一行不是可有可无的赋值，删掉竞态立刻回来。**
     */
    private fun revealFile(path: String) {
        val parent = File(path).parent
        if (parent == null) {
            toast("无法定位该文件所在目录")
            return
        }
        // 先落定当前目录，再排队加载：让随后（同一条主线程消息里）的 onResume / detectShellLevel
        // 拿到的 currentPath 也已经是 parent，不会用默认目录去覆盖 / 误消费待高亮。
        val switchingDir = parent != currentPath
        currentPath = parent
        pendingReveal = path
        revealMissNotified = false
        // 上面那行同步赋值会让 loadDir 里的 `pathChanged`（= path != currentPath）恒为 false，
        // 于是「换目录就退出多选」这条既有规则在定位这条路上会漏掉。补回来：
        // 否则「在多选状态下从外部打开另一个目录的文件」会把上一批选中项带进新目录。
        if (switchingDir && selectionMode) {
            exitSelection()
        }
        loadDir(parent)
    }

    /**
     * 目录装好后消费待高亮的路径：滚动到那一行、让它亮一下。
     *
     * 只做「滚到可见 + 加一层底色」，**完全不碰多选状态**（[selectedPaths] 是另一回事）。
     *
     * <h2>未命中时为什么要【保留】pendingReveal（时序）</h2>
     *
     * [loadDir] 是异步的，[pendingReveal] 有可能被一个「先加载完、却不是目标父目录」的目录
     * 消费到 —— 那种情况下 `indexOfFirst` 必然落空。若在此**无条件**清掉 [pendingReveal]，
     * 等真正正确的目录加载完时就再也亮不起来了（冷启动必有的一次交错）。
     * 所以这里**只有命中才消费**；未命中一律保留，留给下一次正确的目录。
     *
     * 同理，未命中时默认**不提示** —— 「没找到」在竞态里是误报。只有当当前目录**正是**
     * 目标的父目录（确实站在正确位置、却真的没有这一项）才说明一句，且只说一次
     * （[revealMissNotified]），免得每次 onResume / 刷新都重放。
     */
    private fun applyPendingReveal() {
        val target = pendingReveal ?: return
        val index = fileItems.indexOfFirst { it.path == target }
        if (index >= 0) {
            // 命中才消费 —— 见上面的时序说明
            pendingReveal = null
            adapter.setReveal(target)
            recyclerView.scrollToPosition(index)
            scheduleRevealFade()
            return
        }
        // 未命中：保留 pendingReveal。只有「确实站在目标父目录里」才提示，且只说一次。
        if (File(target).parent == currentPath && !revealMissNotified) {
            revealMissNotified = true
            toast("已打开所在目录，但没找到该文件")
        }
    }

    /** 亮一下就够了，别一直杵在那里 */
    private fun scheduleRevealFade() {
        revealRunnable?.let { revealHandler.removeCallbacks(it) }
        val runnable = Runnable {
            adapter.setReveal(null)
            revealRunnable = null
        }
        revealRunnable = runnable
        revealHandler.postDelayed(runnable, REVEAL_MS)
    }

    private fun navigateUp() {
        if (archiveSession != null) {
            archiveUp()
            return
        }
        val parent = File(currentPath).parent
        if (parent != null) {
            loadDir(parent)
        } else {
            toast("已经是根目录")
        }
    }

    // ------------------------------------------------------------- 打开分派

    /**
     * 点击条目：按类型分派到对应的内置查看器。
     *
     * 判定顺序：目录 → 图片 → 视频 → 压缩包 → shell 脚本（先问执行身份）
     * → 两可脚本（弹选择）→ 文本 → 未知（内容嗅探后给建议）。
     */
    private fun navigateTo(item: FileItem) {
        val file = File(item.path)
        val name = item.name
        when {
            item.isDirectory -> loadDir(item.path)

            FileRepository.isImageName(name) -> startActivity(
                Intent(this, ImageViewerActivity::class.java)
                    .putExtra(ImageViewerActivity.EXTRA_PATH, item.path)
            )

            FileRepository.isVideoName(name) -> startActivity(
                Intent(this, VideoViewerActivity::class.java)
                    .putExtra(VideoViewerActivity.EXTRA_PATH, item.path)
            )

            FileRepository.isArchiveName(name) -> openArchive(file)

            // 不直接跑掉：先弹确认面板（是否执行 + 要不要提权勾选框）
            FileRepository.isStrictScriptName(name) -> confirmScriptRun(item.path)

            FileRepository.isAmbiguousScriptName(name) -> showAmbiguousOpenDialog(item)

            FileRepository.isTextName(name) -> startActivity(
                Intent(this, TextViewerActivity::class.java)
                    .putExtra(TextViewerActivity.EXTRA_PATH, item.path)
            )

            else -> lifecycleScope.launch {
                val looksText = withContext(Dispatchers.IO) { FileRepository.looksLikeText(file) }
                showUnknownFileDialog(item, looksText)
            }
        }
    }

    /**
     * .py / .js / .pl / .rb / .lua —— 既能执行也能当文本看，让用户自己选。
     *
     * 这里只决定「执行还是浏览」；选定执行后再由 [confirmScriptRun] 走确认面板，
     * 不在同一个菜单里并列「普通执行 / ROOT 执行」—— 身份是面板里的一个勾选框，
     * 混在类型菜单里会让人以为它们是两种不同的打开方式。
     */
    private fun showAmbiguousOpenDialog(item: FileItem) {
        val actions = mutableListOf("文本浏览", "在终端执行")
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "文本浏览" -> startActivity(
                        Intent(this, TextViewerActivity::class.java)
                            .putExtra(TextViewerActivity.EXTRA_PATH, item.path)
                    )
                    else -> confirmScriptRun(item.path)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showUnknownFileDialog(item: FileItem, looksLikeText: Boolean) {
        val actions = mutableListOf<String>()
        if (looksLikeText) actions.add("作为文本打开")
        actions.add("用其它应用打开")
        actions.add("分享")
        actions.add("复制路径")
        actions.add("文件信息")

        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage(
                "没有匹配到内置查看器（扩展名未收录）。" +
                    if (looksLikeText) "\n\n内容看起来是纯文本，可以直接打开看看。" else ""
            )
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "作为文本打开" -> startActivity(
                        Intent(this, TextViewerActivity::class.java)
                            .putExtra(TextViewerActivity.EXTRA_PATH, item.path)
                    )
                    "用其它应用打开" -> {
                        if (!Intents.openWith(this, File(item.path))) toast("没有找到能打开它的应用")
                    }
                    "分享" -> if (!Intents.share(this, File(item.path))) toast("分享失败")
                    "复制路径" -> copyToClipboard(item.path)
                    "文件信息" -> showFileInfo(item)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openTerminal(path: String) {
        startActivity(
            Intent(this, TerminalActivity::class.java)
                .putExtra(TerminalActivity.EXTRA_WORK_DIR, path)
        )
    }

    // ------------------------------------------------------------- 点击/长按

    private fun onFileClicked(item: FileItem) {
        if (archiveSession != null) {
            onArchiveEntryClicked(item)
            return
        }
        if (item.name == "..") {
            navigateUp()
            return
        }
        if (selectionMode) {
            toggleSelection(item)
            return
        }
        navigateTo(item)
    }

    private fun onFileLongPressed(item: FileItem) {
        val session = archiveSession
        if (session != null) {
            if (item.name == "..") return
            val entry = session.listing.entries.firstOrNull {
                it.name.replace('\\', '/') == item.path
            } ?: ArchiveRepository.Entry(item.path, item.size, 0L, item.isDirectory,
                item.lastModified, false, false)
            showArchiveEntryMenu(entry)
            return
        }
        if (item.name == "..") {
            showPathActionDialog()
            return
        }
        if (selectionMode) {
            toggleSelection(item)
            return
        }
        showFileMenu(item)
    }

    /**
     * 长按菜单。
     *
     * 条目较多，所以按「查看 → 类型专属 → 文件操作 → 对外」分组排列，
     * 类型不适用的动作直接不出现（而不是灰掉），列表才不会长得没边。
     */
    private fun showFileMenu(item: FileItem) {
        val file = File(item.path)
        val name = item.name
        val actions = mutableListOf<String>()

        actions.add("文件信息")
        actions.add("复制路径")
        if (item.isDirectory) actions.add("添加到书签")

        val typeActions = mutableListOf<String>()
        if (!item.isDirectory) {
            if (FileRepository.isImageName(name)) typeActions.add("图片查看")
            if (FileRepository.isVideoName(name)) typeActions.add("视频播放")
            if (FileRepository.isTextName(name)) typeActions.add("文本浏览")
            if (FileRepository.isArchiveName(name)) {
                typeActions.add("查看压缩包内容")
                typeActions.add("解压到当前目录")
            }
            if (FileRepository.isStrictScriptName(name) || FileRepository.isAmbiguousScriptName(name)) {
                // 身份不在这里分两条摆开：它只是一次执行的可选项（要不要提权），
                // 提出去执行时才在确认面板里勾。菜单里并列两个身份，等于把
                // 「用哪个身份」提前变成了两种打开方式，既长了菜单也多一层误导。
                typeActions.add("在终端执行…")
                typeActions.add("带参数执行…")
                // 与终端互为补充的第二条通道：非交互、内容走 stdin、日志在对话框里回吐。
                // 脚本里的 read 在这条路上拿不到输入，所以它**不是**终端的替代品。
                typeActions.add("后台执行（管道）…")
            }
            if (typeActions.isEmpty() && FileRepository.looksLikeText(file)) {
                typeActions.add("文本浏览")
            }
        }
        actions.addAll(typeActions)

        actions.add("复制")
        actions.add("剪切")
        actions.add("重命名")
        actions.add("压缩…")
        actions.add("删除")
        actions.add("分享")
        actions.add("用其它应用打开")
        actions.add("多选")

        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "文件信息" -> showFileInfo(item)
                    "复制路径" -> copyToClipboard(item.path)
                    "添加到书签" -> addBookmark(item.path)
                    "图片查看" -> startActivity(
                        Intent(this, ImageViewerActivity::class.java)
                            .putExtra(ImageViewerActivity.EXTRA_PATH, item.path)
                    )
                    "视频播放" -> startActivity(
                        Intent(this, VideoViewerActivity::class.java)
                            .putExtra(VideoViewerActivity.EXTRA_PATH, item.path)
                    )
                    "文本浏览" -> startActivity(
                        Intent(this, TextViewerActivity::class.java)
                            .putExtra(TextViewerActivity.EXTRA_PATH, item.path)
                    )
                    "查看压缩包内容" -> openArchive(file)
                    "解压到当前目录" -> extractArchive(file, null)
                    "在终端执行…" -> confirmScriptRun(item.path)
                    "带参数执行…" -> confirmScriptRun(item.path, editArgs = true)
                    "后台执行（管道）…" -> confirmScriptRun(item.path, pipe = true)
                    "复制" -> putToClipboard(FileClipboard.Mode.COPY, listOf(item.path))
                    "剪切" -> putToClipboard(FileClipboard.Mode.MOVE, listOf(item.path))
                    "重命名" -> showRenameDialog(file) { loadDir(currentPath) }
                    "压缩…" -> askZipName(listOf(file))
                    "删除" -> confirmDelete(listOf(file)) { loadDir(currentPath) }
                    "分享" -> if (!Intents.share(this, file)) toast("分享失败")
                    "用其它应用打开" -> {
                        if (!Intents.openWith(this, file)) toast("没有找到能打开它的应用")
                    }
                    "多选" -> enterSelection(item.path)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showFileInfo(item: FileItem) {
        val type = FileRepository.describeType(item.name, item.isDirectory)
        val message = buildString {
            append("名称: ").append(item.name).append('\n')
            append("路径: ").append(item.path).append('\n')
            append("类型: ").append(type).append('\n')
            if (!item.isDirectory) {
                append("大小: ").append(FileRepository.formatSize(item.size)).append('\n')
            }
            if (item.lastModified > 0) {
                append("修改: ").append(FileRepository.formatTime(item.lastModified))
            }
        }
        AlertDialog.Builder(this)
            .setTitle("文件信息")
            .setMessage(message)
            .setPositiveButton("复制路径") { _, _ -> copyToClipboard(item.path) }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ------------------------------------------------------------- 路径栏

    private fun showPathDialog() {
        val input = EditText(this).apply {
            setText(currentPath)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("跳转到路径")
            .setView(padded(input))
            .setPositiveButton("跳转") { _, _ ->
                val target = input.text.toString().trim()
                if (target.isNotEmpty()) loadDir(target)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 长按路径栏：书签 + 新建 + 粘贴到当前目录 */
    private fun showPathActionDialog() {
        val exists = BookmarkStore.contains(this, currentPath)
        val actions = mutableListOf(
            if (exists) "移除书签" else "添加书签",
            "复制路径",
            "新建文件夹",
            "新建文件",
            "跳转到路径…",
            "在这里粘贴"
        )
        AlertDialog.Builder(this)
            .setTitle(currentPath)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "添加书签" -> addBookmark(currentPath)
                    "移除书签" -> {
                        // 这里没有面板可挂撤销条，所以只做确认；删除逻辑仍走同一个入口
                        val target = BookmarkStore.find(this, currentPath)
                            ?: Bookmark(File(currentPath).name.ifEmpty { currentPath }, currentPath)
                        confirmRemoveBookmark(target, hintUndo = false) {
                            toast("已移除书签")
                        }
                    }
                    "复制路径" -> copyToClipboard(currentPath)
                    "新建文件夹" -> askNewEntry(isDirectory = true)
                    "新建文件" -> askNewEntry(isDirectory = false)
                    "跳转到路径…" -> showPathDialog()
                    "在这里粘贴" -> pasteInto(currentPath)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun askNewEntry(isDirectory: Boolean) {
        val input = EditText(this).apply {
            hint = if (isDirectory) "文件夹名" else "文件名（含扩展名，如 note.txt）"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(if (isDirectory) "新建文件夹" else "新建文件")
            .setView(padded(input))
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name.contains('/')) {
                    toast("名称无效")
                    return@setPositiveButton
                }
                val target = File(currentPath, name)
                if (target.exists()) {
                    toast("同名已存在")
                    return@setPositiveButton
                }
                val ok = if (isDirectory) {
                    target.mkdirs()
                } else {
                    try {
                        target.createNewFile()
                    } catch (t: Throwable) {
                        // 只读目录：让 root 建一个空文件
                        RootShell.succeeded("touch ${RootShell.quote(target.absolutePath)}") &&
                            target.exists()
                    }
                }
                toast(if (ok) "已创建 $name" else "创建失败（权限不足）")
                loadDir(currentPath)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------- 复制粘贴

    private fun putToClipboard(mode: FileClipboard.Mode, paths: List<String>) {
        if (paths.isEmpty()) return
        FileClipboard.put(this, mode, paths)
        updatePasteButton()
        toast("已${mode.label} ${paths.size} 项，切到目标目录点底部「粘贴」")
    }

    private fun updatePasteButton() {
        val snapshot = FileClipboard.peek(this)
        pasteBtn.text = if (snapshot == null) "粘贴" else "粘贴 ${snapshot.size}"
        pasteBtn.isEnabled = archiveSession == null
        pasteBtn.alpha = if (archiveSession == null) 1f else 0.4f
    }

    /**
     * 规范化路径，用于「谁是谁的祖先」这类包含关系判断。
     *
     * canonicalPath 会解析符号链接（/sdcard → /storage/emulated/0），
     * 不解析的话 `/sdcard/A` 与 `/storage/emulated/0/A` 会被当成两个不同目录，
     * 包含检查就漏了。失败时退回绝对路径。
     */
    private fun canonicalOf(file: File): String = try {
        file.canonicalPath
    } catch (t: Throwable) {
        file.absolutePath
    }

    private fun showClipboardMenu() {
        val snapshot = FileClipboard.peek(this)
        if (snapshot == null) {
            toast("剪贴板是空的")
            return
        }
        val message = buildString {
            append("待${snapshot.label} ").append(snapshot.size).append(" 项：\n")
            snapshot.paths.take(5).forEach { append("· ").append(File(it).name).append('\n') }
            if (snapshot.size > 5) append("… 等 ").append(snapshot.size).append(" 项")
        }
        AlertDialog.Builder(this)
            .setTitle("文件剪贴板")
            .setMessage(message)
            .setPositiveButton("粘贴到这里") { _, _ -> pasteInto(currentPath) }
            .setNeutralButton("清空") { _, _ ->
                FileClipboard.clear(this)
                updatePasteButton()
                toast("已清空剪贴板")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 粘贴到指定目录。
     *
     * 有同名文件时先问策略再动手 —— 直接覆盖是文件管理器最容易造成数据损失的地方，
     * 所以默认策略是「自动重命名」，覆盖必须由用户显式选择。
     */
    private fun pasteInto(destPath: String) {
        val snapshot = FileClipboard.peek(this) ?: run {
            toast("剪贴板是空的：先长按文件选「复制」或「剪切」")
            return
        }
        val destDir = File(destPath)
        val sources = snapshot.paths.map { File(it) }
        // 用统一身份判断来源是否还在：ROOT-only 的文件普通身份 exists() 为 false，
        // 以前会被当成“不存在”从而直接清空剪贴板
        val alive = sources.filter { FileOps.entryExists(it) }
        if (alive.isEmpty()) {
            FileClipboard.clear(this)
            updatePasteButton()
            toast("剪贴板里的文件都不存在了")
            return
        }

        // 目录不能粘进它自己或它的后代目录：FileOps.copy 会一路递归下去，永不结束
        val destCanonical = canonicalOf(destDir)
        val usable = alive.filterNot { source ->
            FileOps.isDirectory(source) &&
                (destCanonical == canonicalOf(source) || destCanonical.startsWith(canonicalOf(source) + "/"))
        }
        if (usable.isEmpty()) {
            toast("不能把目录粘贴到它自己里面")
            return
        }
        if (usable.size < alive.size) {
            toast("已跳过 ${alive.size - usable.size} 个目录（目标在它内部）")
        }

        // 同名冲突也要用统一身份判断：ROOT-only 目标目录里的条目普通 exists() 看不见，
        // 以前会当作「不冲突」直接按自动重命名策略执行
        val conflicts = usable.filter { FileOps.entryExists(File(destDir, it.name)) }
        val proceed: (FileOps.Conflict) -> Unit = { conflict ->
            val moving = snapshot.mode == FileClipboard.Mode.MOVE
            runOp(if (moving) "正在移动" else "正在复制") { report, cancelled ->
                val outcome = if (moving) {
                    FileOps.move(usable, destDir, conflict, report, cancelled)
                } else {
                    FileOps.copy(usable, destDir, conflict, report, cancelled)
                }
                if (outcome.ok && moving) {
                    FileClipboard.clear(this)
                    runOnUiThread { updatePasteButton() }
                }
                outcome.summary(if (moving) "移动" else "复制")
            }
        }

        if (conflicts.isEmpty()) {
            proceed(FileOps.Conflict.RENAME)
        } else {
            val names = conflicts.take(3).joinToString("、") { it.name } +
                if (conflicts.size > 3) " 等 ${conflicts.size} 个" else ""
            AlertDialog.Builder(this)
                .setTitle("有同名文件")
                .setMessage("目标目录已存在：$names\n\n要如何处理？")
                .setItems(FileOps.Conflict.entries.map { it.label }.toTypedArray()) { _, which ->
                    proceed(FileOps.Conflict.entries[which])
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    // ------------------------------------------------------------- 多选

    private fun enterSelection(path: String) {
        selectionMode = true
        selectedPaths.clear()
        selectedPaths.add(path)
        updateSelectionUi()
    }

    private fun exitSelection() {
        selectionMode = false
        selectedPaths.clear()
        updateSelectionUi()
    }

    private fun toggleSelection(item: FileItem) {
        if (item.name == "..") return
        if (!selectedPaths.remove(item.path)) {
            selectedPaths.add(item.path)
        }
        if (selectedPaths.isEmpty()) {
            exitSelection()
        } else {
            updateSelectionUi()
        }
    }

    private fun selectAll() {
        selectedPaths.clear()
        fileItems.filter { it.name != ".." }.forEach { selectedPaths.add(it.path) }
        if (selectedPaths.isEmpty()) {
            toast("当前目录没有可选的条目")
        }
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        normalBar.visibility = if (selectionMode) View.GONE else View.VISIBLE
        selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        selCount.text = "已选 ${selectedPaths.size} 项"
        adapter.setSelection(selectionMode, selectedPaths)
        // 多选时把选中数量也显示在路径栏，避免底部文字被截断看不到
        pathText.text = if (selectionMode) {
            "已选 ${selectedPaths.size} 项 · $currentPath"
        } else {
            currentPath
        }
    }

    private fun selectedFiles(): List<File> = selectedPaths.map { File(it) }

    private fun selectionToClipboard(mode: FileClipboard.Mode) {
        if (selectedPaths.isEmpty()) {
            toast("没有选中任何条目")
            return
        }
        putToClipboard(mode, selectedPaths.toList())
        exitSelection()
    }

    private fun compressSelection() {
        val files = selectedFiles()
        if (files.isEmpty()) {
            toast("没有选中任何条目")
            return
        }
        askZipName(files)
    }

    private fun deleteSelection() {
        val files = selectedFiles()
        if (files.isEmpty()) return
        confirmDelete(files) {
            exitSelection()
            loadDir(currentPath)
        }
    }

    private fun showSelectionMore() {
        val actions = arrayOf("移动到指定目录…", "复制已选路径", "分享第一个", "取消选择")
        AlertDialog.Builder(this)
            .setTitle("已选 ${selectedPaths.size} 项")
            .setItems(actions) { _, which ->
                when (actions[which]) {
                    "移动到指定目录…" -> askDestDir { dir ->
                        val files = selectedFiles()
                        runOp("正在移动 ${files.size} 项", onDone = { exitSelection() }) { report, cancelled ->
                            FileOps.move(files, dir, FileOps.Conflict.RENAME, report, cancelled)
                                .summary("移动")
                        }
                    }
                    "复制已选路径" -> copyToClipboard(selectedPaths.joinToString("\n"))
                    "分享第一个" -> {
                        val first = selectedFiles().firstOrNull()
                        if (first == null || !Intents.share(this, first)) toast("分享失败")
                    }
                    "取消选择" -> exitSelection()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ------------------------------------------------------------- 文件操作

    private fun showRenameDialog(file: File, onDone: () -> Unit) {
        val input = EditText(this).apply {
            setText(file.name)
            setSelection(text.length)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(padded(input))
            .setPositiveButton("确定") { _, _ ->
                val renamed = FileOps.rename(file, input.text.toString())
                if (renamed == null) {
                    toast("重命名失败（名称无效或同名已存在）")
                } else {
                    onDone()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(files: List<File>, onDone: () -> Unit) {
        val label = if (files.size == 1) files[0].name else "${files.size} 个条目"
        AlertDialog.Builder(this)
            .setTitle("删除")
            .setMessage("确定删除 $label？此操作不可撤销。")
            .setPositiveButton("删除") { _, _ ->
                runOp("正在删除 $label") { report, cancelled ->
                    FileOps.delete(files, report, cancelled).summary("删除")
                }
                onDone()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 解压入口：目标目录为 null 时用「与压缩包同名」的默认目录 */
    private fun extractArchive(archive: File, destDir: File?, password: CharArray? = null) {
        val parent = archive.parentFile ?: File(currentPath)
        val target = destDir
            ?: FileOps.uniqueDir(parent, ArchiveRepository.baseNameOf(archive.name))
        runOp("正在解压 ${archive.name}") { report, cancelled ->
            val outcome = ArchiveRepository.extract(
                this, archive, target, report, cancelled, password
            )
            val where = if (outcome.staged) "${target.name}（以 root 写入）" else target.name
            "解压完成：${outcome.count} 个文件 → $where"
        }
    }

    private fun extractArchiveEntry(
        archive: File,
        entryName: String,
        destDir: File?,
        password: CharArray? = null
    ) {
        val dir = destDir ?: File(currentPath)
        runOp("正在解压 ${File(entryName).name}") { report, cancelled ->
            val count = ArchiveRepository.extractSelected(
                this, archive, entryName, dir, report, cancelled, password
            )
            "已解压 $count 个文件到 ${dir.name}"
        }
    }

    private fun openArchive(archive: File, password: CharArray? = null) {
        archiveJob?.cancel()
        pathText.text = "/${archive.name}/"
        emptyView.text = "正在读取压缩包…"
        emptyView.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE
        archiveJob = lifecycleScope.launch {
            val (result, protected) = withContext(Dispatchers.IO) {
                ArchiveRepository.inspect(this@MainActivity, archive, password)
            }
            val listing = result.listing
            if (listing == null) {
                if (protected) {
                    askArchivePassword(archive, password)
                } else {
                    toast(result.error ?: "无法读取压缩包")
                    loadDir(currentPath)
                }
                return@launch
            }
            archiveSession = ArchiveSession(archive, listing, protected).also {
                it.password = password
            }
            renderArchiveEntries()
        }
    }

    private fun askArchivePassword(archive: File, previous: CharArray?) {
        val input = EditText(this).apply {
            hint = "压缩包密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("需要密码")
            .setMessage(if (previous == null) "这个压缩包已加密" else "密码不对，请重试")
            .setView(padded(input))
            .setPositiveButton("确定") { _, _ ->
                val text = input.text.toString()
                if (text.isEmpty()) toast("没有输入密码") else openArchive(archive, text.toCharArray())
            }
            .setNegativeButton("取消") { _, _ -> loadDir(currentPath) }
            .show()
    }

    private fun renderArchiveEntries() {
        val session = archiveSession ?: return
        val entries = archiveDirectChildren(session)
        val items = mutableListOf(
            FileItem("..", currentPath, true, false, 0L, 0L)
        )
        entries.forEach { entry ->
            items += FileItem(
                name = entry.name.trimEnd('/').substringAfterLast('/'),
                path = entry.name,
                isDirectory = entry.isDirectory,
                isScript = false,
                size = entry.size,
                lastModified = entry.time
            )
        }
        fileItems = items
        pathText.text = "/${session.archive.name}/${session.directory}"
        adapter.submit(items)
        adapter.setSelection(false, emptySet())
        emptyView.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        updatePasteButton()
    }

    private fun archiveDirectChildren(session: ArchiveSession): List<ArchiveRepository.Entry> {
        val directory = session.directory
        val direct = java.util.LinkedHashMap<String, ArchiveRepository.Entry>()
        val implicit = java.util.LinkedHashMap<String, ArchiveRepository.Entry>()
        for (entry in session.listing.entries) {
            val name = entry.name.replace('\\', '/')
            if (!name.startsWith(directory)) continue
            val remainder = name.substring(directory.length)
            if (remainder.isEmpty()) continue
            val slash = remainder.indexOf('/')
            if (slash < 0 || (entry.isDirectory && slash == remainder.lastIndex)) {
                direct[name] = entry.copy(name = name)
                continue
            }
            val childName = directory + remainder.substring(0, slash) + "/"
            if (!direct.containsKey(childName)) {
                implicit[childName] = ArchiveRepository.Entry(
                    childName, 0L, 0L, true, 0L, false, false
                )
            }
        }
        implicit.forEach { (name, entry) -> if (!direct.containsKey(name)) direct[name] = entry }
        return direct.values.sortedWith { a, b ->
            if (a.isDirectory != b.isDirectory) {
                if (a.isDirectory) -1 else 1
            } else {
                FileSort.naturalCompare(
                    a.name.trimEnd('/').substringAfterLast('/'),
                    b.name.trimEnd('/').substringAfterLast('/')
                )
            }
        }
    }

    private fun onArchiveEntryClicked(item: FileItem) {
        val session = archiveSession ?: return
        if (item.name == "..") {
            archiveUp()
            return
        }
        val entry = session.listing.entries.firstOrNull {
            it.name.replace('\\', '/') == item.path
        } ?: ArchiveRepository.Entry(item.path, item.size, 0L, item.isDirectory,
            item.lastModified, false, false)
        if (entry.isDirectory) {
            session.directory = entry.name.replace('\\', '/').trimEnd('/') + "/"
            renderArchiveEntries()
        } else if (isArchivePreviewable(entry)) {
            ensureArchivePassword(entry) { previewArchiveEntry(entry) }
        } else {
            showArchiveEntryMenu(entry)
        }
    }

    private fun isArchivePreviewable(entry: ArchiveRepository.Entry): Boolean =
        FileRepository.isTextName(entry.name) || FileRepository.isImageName(entry.name)

    private fun ensureArchivePassword(entry: ArchiveRepository.Entry, action: () -> Unit) {
        val session = archiveSession ?: return
        if ((!entry.encrypted && !session.passwordProtected) || session.password?.isNotEmpty() == true) {
            action()
            return
        }
        val input = EditText(this).apply {
            hint = "压缩包密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("需要密码")
            .setView(padded(input))
            .setPositiveButton("确定") { _, _ ->
                val text = input.text.toString()
                if (text.isNotEmpty()) {
                    session.password = text.toCharArray()
                    action()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun previewArchiveEntry(entry: ArchiveRepository.Entry) {
        val session = archiveSession ?: return
        toast("正在取出 ${File(entry.name).name}…")
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                ArchiveRepository.previewEntry(
                    this@MainActivity, session.archive, entry.name, session.password
                )
            }
            if (file == null) {
                toast("读取条目失败")
                return@launch
            }
            // 预览副本目录的所有权交给查看器：它在 isFinishing 的 onDestroy 里释放；
            // 界面重建（旋转）时不能删，否则重建出来的实例就找不到这个文件了
            val stagedDir = file.parentFile?.absolutePath
            val intent = if (FileRepository.isImageName(entry.name)) {
                Intent(this@MainActivity, ImageViewerActivity::class.java)
                    .putExtra(ImageViewerActivity.EXTRA_PATH, file.absolutePath)
                    .putExtra(ImageViewerActivity.EXTRA_STAGED_DIR, stagedDir)
            } else {
                Intent(this@MainActivity, TextViewerActivity::class.java)
                    .putExtra(TextViewerActivity.EXTRA_PATH, file.absolutePath)
                    .putExtra(TextViewerActivity.EXTRA_STAGED_DIR, stagedDir)
            }
            startActivity(intent)
        }
    }

    private fun showArchiveEntryMenu(entry: ArchiveRepository.Entry) {
        val actions = mutableListOf<String>()
        if (isArchivePreviewable(entry)) actions += "预览内容"
        actions += "解压到当前目录"
        actions += "解压到指定目录…"
        actions += "复制条目名"
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "预览内容" -> ensureArchivePassword(entry) { previewArchiveEntry(entry) }
                    "解压到当前目录" -> ensureArchivePassword(entry) {
                        extractArchiveEntry(archiveSession?.archive ?: return@ensureArchivePassword,
                            entry.name, null, archiveSession?.password)
                    }
                    "解压到指定目录…" -> ensureArchivePassword(entry) {
                        askDestDir { dir ->
                            extractArchiveEntry(archiveSession?.archive ?: return@askDestDir,
                                entry.name, dir, archiveSession?.password)
                        }
                    }
                    "复制条目名" -> copyToClipboard(entry.name)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun archiveUp() {
        val session = archiveSession ?: return
        if (session.directory.isEmpty()) {
            loadDir(currentPath)
            return
        }
        val trimmed = session.directory.trimEnd('/')
        val slash = trimmed.lastIndexOf('/')
        session.directory = if (slash < 0) "" else trimmed.substring(0, slash + 1)
        renderArchiveEntries()
    }

    private fun exitArchive() {
        if (archiveSession != null) loadDir(currentPath)
    }

    private fun askZipName(files: List<File>) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val input = EditText(this).apply {
            setText(ArchiveRepository.defaultArchiveName(files, stamp))
            setSelection(0, text.length)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("压缩")
            .setMessage(
                "将打包 ${files.size} 个条目到当前目录。\n" +
                    "文件名以 .7z 结尾就出 7z，否则出 ZIP（只有这两种能创建）。"
            )
            .setView(padded(input))
            .setPositiveButton("开始压缩") { _, _ ->
                var name = input.text.toString().trim()
                if (name.isEmpty()) name = "压缩包.zip"
                // 没有可识别的后缀就按 ZIP —— 与输入框里的默认名一致
                if (!name.lowercase(Locale.US).endsWith(".7z") &&
                    !name.lowercase(Locale.US).endsWith(".zip")
                ) {
                    name += ".zip"
                }
                val output = FileOps.uniqueTarget(File(currentPath), name)
                runOp("正在压缩") { report, cancelled ->
                    val outcome = ArchiveRepository.compress(this, files, output, report, cancelled)
                    "已生成 ${outcome.target.name}（${outcome.count} 个条目，${FileRepository.formatSize(outcome.target.length())}）"
                }
                exitSelection()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun askDestDir(onPicked: (File) -> Unit) {
        val input = EditText(this).apply {
            setText(currentPath)
            setSelection(text.length)
            setSingleLine(true)
            hint = "目标目录"
        }
        AlertDialog.Builder(this)
            .setTitle("选择目标目录")
            .setView(padded(input))
            .setPositiveButton("确定") { _, _ ->
                val path = input.text.toString().trim()
                if (path.isNotEmpty()) onPicked(File(path))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 统一的「带进度的长任务」执行器。
     *
     * 结果由 body 返回一句人话，成功失败都走同一条路 —— 这样每个操作
     * 只需要关心自己的逻辑，不用重复写 try/catch/进度条/刷新。
     */
    private fun runOp(
        title: String,
        onDone: () -> Unit = {},
        body: (report: (String) -> Unit, cancelled: () -> Boolean) -> String
    ) {
        val progress = OperationProgressDialog(this, title)
        operationDialog = progress
        val cancelFlag = AtomicBoolean(false)
        progress.onCancel = {
            cancelFlag.set(true)
            progress.markCancelling()
        }
        progress.show()

        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    body({ path -> progress.report(path) }, { cancelFlag.get() })
                } catch (t: Throwable) {
                    when (t) {
                        is FileOps.Cancelled, is Archives.Cancelled -> "已取消"
                        else -> "失败：${t.message ?: t.javaClass.simpleName}"
                    }
                }
            }
            progress.dismiss()
            if (operationDialog === progress) operationDialog = null
            toast(message)
            loadDir(currentPath)
            updatePasteButton()
            onDone()
        }
    }

    // ------------------------------------------------------------- 脚本执行

    private fun canRunAsRoot(): Boolean = ShellExecutor.level == ShellLevel.ROOT

    /** 上一次执行脚本时选的身份。只表示「记住的选择」，不代表当前能不能用 root。 */
    private fun lastScriptAsRoot(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(scriptRootKey, false)

    private fun rememberScriptIdentity(asRoot: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(scriptRootKey, asRoot)
            .apply()
    }

    /**
     * 执行脚本前的确认面板 —— 四条入口（点击脚本文件、长按「在终端执行」、
     * 「带参数执行」、「后台执行（管道）」）共用这一个。
     *
     * **这个框问的是「要不要执行」，不是「用哪个身份」。** 身份只有两级，
     * 而普通用户（应用自身身份）就是默认值，所以它退化成面板里的一个勾选框：
     * `以 ROOT 身份执行（su）` —— 想提权就自己勾，不勾就照普通用户跑。
     * 预勾选状态取自上一次的选择（[scriptRootKey]），习惯固定的人不必每次重勾。
     *
     * 没探测到 root 时，勾选行**整行不出现**（不是给一个点了没用的灰勾选框），
     * 面板上只留一句「为什么没有这个选项」。注意这里也不再像以前那样「只有一个
     * 选项就直接静默跑掉」：无论有没有 root，都要经过面板，用户点的「执行」才是放行。
     *
     * @param extraArgs 预填进参数框的内容（仅 [editArgs] 为 true 时有意义）
     * @param editArgs 是否显示参数输入框（长按菜单的「带参数执行」）
     * @param pipe 是否走管道（后台）通道 —— 面板会多一段该通道独有的限制说明
     */
    private fun confirmScriptRun(
        scriptPath: String,
        extraArgs: String? = null,
        editArgs: Boolean = false,
        pipe: Boolean = false
    ) {
        if (!shellLevelReady) {
            // 冷启动刚点进来：此刻 canRunAsRoot() 必然是 false，这时候弹面板等于
            // 告诉用户「你没有 root」—— 先把探测（可能要等 su 授权框）等回来。
            if (awaitingShellLevel) return
            awaitingShellLevel = true
            toast("正在探测权限…")
            lifecycleScope.launch {
                try {
                    ensureShellLevel()
                } catch (t: Throwable) {
                    Log.w(TAG, "ensureShellLevel failed", t)
                } finally {
                    awaitingShellLevel = false
                }
                confirmScriptRun(scriptPath, extraArgs, editArgs, pipe)
            }
            return
        }

        val canRoot = canRunAsRoot()
        val rootColor = ContextCompat.getColor(this, R.color.warn)
        val view = layoutInflater.inflate(R.layout.dialog_script_run, null)
        val nameText = view.findViewById<TextView>(R.id.scriptRunName)
        val pathText = view.findViewById<TextView>(R.id.scriptRunPath)
        val tag = view.findViewById<TextView>(R.id.scriptRunTag)
        val desc = view.findViewById<TextView>(R.id.scriptRunDesc)
        val rootRow = view.findViewById<View>(R.id.scriptRunRootRow)
        val rootCheck = view.findViewById<CheckBox>(R.id.scriptRunRootCheck)
        val rootHint = view.findViewById<TextView>(R.id.scriptRunRootHint)
        val noRoot = view.findViewById<TextView>(R.id.scriptRunNoRoot)
        val argsInput = view.findViewById<EditText>(R.id.scriptRunArgs)
        val channelHint = view.findViewById<TextView>(R.id.scriptRunChannelHint)

        nameText.text = File(scriptPath).name
        pathText.text = scriptPath
        // 主题色先留底：勾选来回切换时要能还原，不能只改不收
        val tagColor = tag.textColors
        val descColor = desc.textColors

        if (editArgs && !pipe) {
            argsInput.visibility = View.VISIBLE
            argsInput.setText(extraArgs.orEmpty())
            argsInput.setSelection(argsInput.text.length)
        }

        if (pipe) {
            channelHint.visibility = View.VISIBLE
            channelHint.text = "这条通道是非交互的：脚本内容整份读入内存、去掉 BOM 与 \\r 之后" +
                "从 stdin 交给 sh，不把脚本路径当文件交给它打开，因此脚本里的 read 会立刻读到 EOF。" +
                "加密或编译过的脚本（ELF、或首行 #! 指向解密器的）走不了这条通道。"
        }

        rootRow.visibility = if (canRoot) View.VISIBLE else View.GONE
        noRoot.visibility = if (canRoot) View.GONE else View.VISIBLE
        noRoot.text = "当前未获取到 root 权限，脚本只能以普通用户（应用自身身份）执行，" +
            "能碰到的只有本应用有权访问的文件。"
        rootHint.text = "su 提权：脚本可以读写系统分区和其它应用的数据"
        rootCheck.isChecked = canRoot && lastScriptAsRoot()

        // 勾选状态一变，标签与说明一起跟着变 —— 三处讲的是同一件事，
        // 不能出现「标签写着 ROOT、说明还在讲普通用户」这种自相矛盾的画面
        fun renderIdentity() {
            if (canRoot && rootCheck.isChecked) {
                tag.text = "ROOT"
                tag.setBackgroundResource(R.drawable.bg_tag_warn)
                tag.setTextColor(rootColor)
                tag.alpha = 1f
                desc.text = "脚本将以 root 身份运行，可以看到、也可以改动系统分区与其它应用的数据 —— " +
                    "请只在这份脚本确实需要提权、且你确认它可信时才勾选。"
                desc.setTextColor(rootColor)
                desc.alpha = 1f
            } else {
                tag.text = "普通用户"
                tag.setBackgroundResource(R.drawable.bg_tag_plain)
                tag.setTextColor(tagColor)
                tag.alpha = 0.7f
                desc.text = "以应用自身的身份运行。脚本能看到、能改动的，只有本应用有权访问的文件。"
                desc.setTextColor(descColor)
                desc.alpha = 0.7f
            }
        }

        // 整行可点：勾选框本身在手机上是个很难精准点中的小目标
        rootRow.setOnClickListener {
            rootCheck.isChecked = !rootCheck.isChecked
            renderIdentity()
        }
        renderIdentity()

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (pipe) "后台执行脚本" else "执行脚本")
            .setView(view)
            .setPositiveButton(if (pipe) "后台执行" else "执行") { _, _ ->
                val asRoot = canRoot && rootCheck.isChecked
                rememberScriptIdentity(asRoot)
                val args = if (editArgs && !pipe) {
                    argsInput.text.toString().trim().takeIf { it.isNotEmpty() }
                } else {
                    extraArgs
                }
                if (pipe) runScriptPipe(scriptPath, asRoot) else runScript(scriptPath, asRoot, args)
            }
            .setNegativeButton("取消", null)
            .create()
        if (editArgs && !pipe) {
            // 「带参数执行」进来就是要打字的，别让用户再点一次输入框
            dialog.setOnShowListener { argsInput.requestFocus() }
        }
        dialog.show()
    }

    /**
     * 状态栏里的「脚本默认身份」设置项。
     *
     * 改的只是确认面板里那个勾选框的**预勾选状态**，不影响「每次执行都要过一遍面板」
     * 这件事 —— 留着它，是为了在不想跑任何脚本的情况下也能把默认身份拨回去。
     */
    private fun showScriptIdentityDialog() {
        val current = if (lastScriptAsRoot()) 1 else 0
        val options = arrayOf("默认不勾选（普通用户）", "默认勾选「以 ROOT 执行」")
        AlertDialog.Builder(this)
            .setTitle("脚本执行 · 默认身份")
            .setMessage(
                "每次执行脚本时仍会弹确认面板，这里改的只是面板里勾选框的预勾选状态。\n" +
                    "预勾选为 ROOT 时，仍然要你点一下「执行」才会真正提权运行。"
            )
            .setSingleChoiceItems(options, current) { dialog, which ->
                rememberScriptIdentity(which == 1)
                dialog.dismiss()
                toast("下次执行将预勾选：${options[which]}")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 执行 sh 脚本。
     *
     * 这里只管执行，不管身份该不该问 —— 一律由 [confirmScriptRun] 那个确认面板
     * 收口。唯一在这里做的是**把这次身份的选择记下来**，作为下次面板里勾选框的
     * 预勾选状态。
     *
     * @param asRoot 以 root 身份执行（仅在探测到 root 时可选）
     * @param extraArgs 追加到命令行的参数
     */
    private fun runScript(scriptPath: String, asRoot: Boolean, extraArgs: String? = null) {
        if (asRoot && !canRunAsRoot()) {
            toast("未探测到 root，无法以 ROOT 身份执行")
            return
        }
        rememberScriptIdentity(asRoot)
        lifecycleScope.launch {
            // 预检/探测都按**将要采用的身份**进行：root 会话下解释器与工作目录
            // 的可见性都和普通会话不同，不能拿普通身份的结果去决定 root 的执行方式
            val prepared = withContext(Dispatchers.IO) {
                // ensure 是幂等的（就绪后零开销）：这里再来一次，是为了保证
                // 「沙箱里有哪些命令」在探测之前已经确定，否则冷启动时
                // 探测可能跑在 initWorkDir 的铺开动作之前，看不到沙箱里的 bash 等垫片
                RxinToolbox.ensure(this@MainActivity)
                runCatching { ScriptRunner.prepare(scriptPath, asRoot) }.getOrNull()
            }
            if (prepared == null) {
                toast("无法读取该脚本：${File(scriptPath).name}")
                return@launch
            }
            if (prepared.preflight.hasEncodingIssue) {
                showScriptIssueDialog(prepared, asRoot, extraArgs)
            } else {
                launchScriptTerminal(prepared, asRoot, extraArgs)
            }
        }
    }

    /**
     * 编码问题确认。
     *
     * CRLF / BOM 是 Android 上脚本「一执行就报错」最常见的两个原因，
     * 这里给出明确的处理入口，而不是让用户对着 `syntax error` 猜。
     */
    private fun showScriptIssueDialog(
        prepared: ScriptRunner.Prepared,
        asRoot: Boolean,
        extraArgs: String?
    ) {
        AlertDialog.Builder(this)
            .setTitle("脚本编码需要处理")
            .setMessage(
                "${File(prepared.scriptPath).name}\n\n" +
                    prepared.preflight.issueText() +
                    "\n直接执行很可能会报错。\n" +
                    "修复会先备份原文件为 .bak，再转换为 Unix 换行（LF）并去掉 BOM。"
            )
            .setPositiveButton("修复并执行") { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { ScriptRunner.fixEncoding(prepared.scriptPath) }.getOrDefault(false)
                    }
                    if (!ok) {
                        toast("修复失败，将按原样执行")
                        launchScriptTerminal(prepared, asRoot, extraArgs)
                        return@launch
                    }
                    // 修复后重新预检，避免把过期的问题状态带进终端
                    val refreshed = withContext(Dispatchers.IO) {
                        runCatching { ScriptRunner.prepare(prepared.scriptPath, asRoot) }.getOrNull()
                    } ?: prepared
                    launchScriptTerminal(refreshed, asRoot, extraArgs)
                }
            }
            .setNegativeButton("直接执行") { _, _ -> launchScriptTerminal(prepared, asRoot, extraArgs) }
            .show()
    }

    /** 打开终端页，并把执行命令交给它注入到会话中 */
    private fun launchScriptTerminal(
        prepared: ScriptRunner.Prepared,
        asRoot: Boolean,
        extraArgs: String?
    ) {
        val name = File(prepared.scriptPath).name
        startActivity(
            Intent(this, TerminalActivity::class.java)
                .putExtra(TerminalActivity.EXTRA_SCRIPT_PATH, prepared.scriptPath)
                .putExtra(TerminalActivity.EXTRA_SCRIPT_NAME, name)
                .putExtra(TerminalActivity.EXTRA_SCRIPT_COMMAND, ScriptRunner.buildCommand(prepared, extraArgs))
                .putExtra(TerminalActivity.EXTRA_SCRIPT_NOTICE, ScriptRunner.notice(prepared))
                .putExtra(TerminalActivity.EXTRA_WORK_DIR, prepared.workDir)
                .putExtra(TerminalActivity.EXTRA_RUN_AS_ROOT, asRoot)
        )
    }

    // ------------------------------------------------------------- 管道执行通道

    /**
     * 以管道方式跑脚本，并把日志显示在对话框里。
     *
     * 与终端那条通道的分工：
     *  - 终端：交互式、PTY、支持 `read`、脚本结束即关闭界面；
     *  - 这里：非交互、日志结构化回吐、可以在界面上边跑边看。
     *
     * 同一时刻只允许一个：再点一次会先收掉上一个，否则两个日志窗口互相打架。
     */
    private fun runScriptPipe(scriptPath: String, asRoot: Boolean) {
        if (asRoot && !canRunAsRoot()) {
            toast("未探测到 root，无法以 ROOT 身份执行")
            return
        }
        pipeHandle?.cancel()
        pipeDialog?.dismiss()

        val name = File(scriptPath).name
        val dialog = ScriptLogDialog(this, "后台执行 · $name")
        pipeDialog = dialog
        dialog.onCancel = {
            pipeHandle?.cancel()
            toast("已请求终止")
        }
        dialog.show()

        val handle = ScriptPipe.run(
            this,
            ScriptPipe.Request(scriptPath = scriptPath, asRoot = asRoot),
            sink = object : ScriptPipe.Sink {
                override fun onStdout(line: String) = dialog.append(line, false)
                override fun onStderr(line: String) = dialog.append(line, true)
            }
        ) { result ->
            // 回调保证在主线程。只在「当前窗口还是我这一个」时才落结果 ——
            // 用户又点了一次别的脚本时，新的那轮已经接管了。
            if (pipeDialog === dialog) {
                pipeHandle = null
                dialog.finish(result)
            }
        }
        // 脚本可能在 run() 返回之前就跑完了 —— 那时回调已经把句柄清掉，
        // 这里再存一次就会留下一个「指向已结束进程」的句柄
        if (pipeDialog === dialog && handle.isRunning) pipeHandle = handle
    }

    // --------------------------------------------------------------- 书签

    /**
     * 顶部按钮：弹出书签面板。
     *
     * 底部面板 + 自定义条目：图标、名称/路径双行排版、当前所在目录高亮、
     * 行尾「更多」菜单（重命名 / 复制路径 / 删除）、删除后 6 秒内可撤销。
     *
     * **删除为什么这么绕**：书签是「用一次、扔一次」的成本极低、但**丢了就想不起来**
     * 的东西 —— 路径没法凭记忆重建。所以行内不再放一键删除（那是误触来源），
     * 删除要经过「菜单 → 确认」，并且面板底部还留一个撤销窗口。
     */
    private fun showBookmarkDialog() {
        val sheet = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.dialog_bookmarks, null)
        sheet.setContentView(content)
        content.setOnClickListener { sheet.dismiss() }
        content.findViewById<View>(R.id.bookmarkPanel).setOnClickListener { }
        content.findViewById<TextView>(R.id.bookmarkCloseBtn).setOnClickListener { sheet.dismiss() }

        val scroll = content.findViewById<NestedScrollView>(R.id.bookmarkScroll)
        val listContainer = content.findViewById<LinearLayout>(R.id.bookmarkList)
        val emptyView = content.findViewById<View>(R.id.bookmarkEmpty)
        val countText = content.findViewById<TextView>(R.id.bookmarkCount)
        val hintView = content.findViewById<TextView>(R.id.bookmarkHint)
        val undoBar = content.findViewById<View>(R.id.bookmarkUndo)
        val undoText = content.findViewById<TextView>(R.id.bookmarkUndoText)

        // 面板内部的「对话框」：菜单 / 重命名 / 删除确认全部画在面板自己身上。
        // **绝不在面板之上再开窗口** —— 真机上那样弹出来的菜单用户根本看不到。
        val panel = SheetDialog(sheet, content)
        // 撑全屏 + 容器透明。**不要**再调 glassSurface()：两者互相覆盖（见 showMoreMenu）
        panel.expandFullScreen()

        // 撤销窗口的状态：pending 非空 = 底部那条「已删除 xxx」正在显示
        val handler = Handler(Looper.getMainLooper())
        var pending: BookmarkStore.Removed? = null
        var pendingTimeout: Runnable? = null

        fun cancelTimeout() {
            pendingTimeout?.let { handler.removeCallbacks(it) }
            pendingTimeout = null
        }

        // 这两个互相调用 —— 列表条目里的「删除」要刷新列表，刷新又要把菜单挂上去。
        // Kotlin 的局部函数**不能前向引用**（不像类成员），所以声明成变量再赋值。
        var render: () -> Unit = {}
        var removeBookmark: (Bookmark) -> Unit = {}

        render = {
            val bookmarks = BookmarkStore.load(this)
            listContainer.removeAllViews()
            countText.text = if (bookmarks.isEmpty()) "" else "${bookmarks.size} 个"
            emptyView.visibility = if (bookmarks.isEmpty()) View.VISIBLE else View.GONE
            scroll.visibility = if (bookmarks.isEmpty()) View.GONE else View.VISIBLE
            hintView.visibility = if (bookmarks.isEmpty()) View.GONE else View.VISIBLE

            bookmarks.forEach { bookmark ->
                val row = layoutInflater.inflate(R.layout.item_bookmark, listContainer, false)
                bindBookmarkRow(
                    row = row,
                    bookmark = bookmark,
                    onOpen = {
                        sheet.dismiss()
                        loadDir(bookmark.path)
                    },
                    onMenu = {
                        showBookmarkItemMenu(
                            panel = panel,
                            bookmark = bookmark,
                            onChanged = { render() },
                            onDelete = { removeBookmark(bookmark) }
                        )
                    }
                )
                listContainer.addView(row)
            }

            // 撤销条：只在刚删完的那几秒出现
            val undone = pending
            if (undone != null) {
                undoText.text = "已删除「${undone.bookmark.name}」"
                undoBar.visibility = View.VISIBLE
            } else {
                undoBar.visibility = View.GONE
            }

            // 条目较多时限制面板高度，避免把标题顶出屏幕；
            // 内容变少时恢复 wrap_content，比例按窗口取（横屏更低）。
            if (bookmarks.isNotEmpty()) {
                scroll.post {
                    val percent = resources.getInteger(R.integer.bookmark_list_height_percent)
                    val maxHeight = (resources.displayMetrics.heightPixels * percent / 100f).toInt()
                    val measured = listContainer.height
                    val wanted = if (measured > maxHeight) maxHeight else
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    if (scroll.layoutParams.height != wanted) {
                        scroll.layoutParams = scroll.layoutParams.apply { height = wanted }
                    }
                }
            }
        }

        // 删除 + 开一个撤销窗口；窗口过期后这次删除就算落定
        removeBookmark = { bookmark ->
            panel.confirm(
                title = "删除书签",
                message = buildString {
                    append("确定删除「").append(bookmark.name).append("」？\n\n")
                    append(bookmark.path)
                    append("\n\n删错了不要紧：面板底部会留一个「撤销」。")
                },
                okText = "删除",
                onOk = {
                    val removed = BookmarkStore.take(this, bookmark.path)
                    if (removed == null) {
                        toast("这个书签已经不在了")
                    } else {
                        pending = removed
                        toast("已删除「${bookmark.name}」")
                        render()
                        cancelTimeout()
                        val job = Runnable {
                            pending = null
                            pendingTimeout = null
                            render()
                        }
                        pendingTimeout = job
                        handler.postDelayed(job, UNDO_WINDOW_MS)
                    }
                },
                onCancel = {}
            )
        }

        content.findViewById<TextView>(R.id.bookmarkUndoBtn).setOnClickListener {
            val removed = pending ?: return@setOnClickListener
            cancelTimeout()
            pending = null
            val restored = BookmarkStore.insert(this, removed)
            render()
            toast(if (restored) "已恢复「${removed.bookmark.name}」" else "该路径已在书签中")
        }

        content.findViewById<TextView>(R.id.addCurrentBtn).setOnClickListener {
            addBookmark(currentPath)
            render()
        }

        // 面板关掉就代表「不要了」：撤销窗口跟着结束，别让 handler 泄漏
        sheet.setOnDismissListener {
            panel.dismiss()
            cancelTimeout()
            pending = null
        }

        sheet.show()
        render()
    }

    /**
     * 绑定单个书签条目。
     *
     * 行内点击 = 跳到那个目录；行尾按钮与长按都只打开菜单 —— 行内没有破坏性操作。
     */
    private fun bindBookmarkRow(
        row: View,
        bookmark: Bookmark,
        onOpen: () -> Unit,
        onMenu: () -> Unit
    ) {
        val isCurrent = bookmark.path == currentPath
        val nameView = row.findViewById<TextView>(R.id.bookmarkName)
        val pathView = row.findViewById<TextView>(R.id.bookmarkPath)
        val iconView = row.findViewById<ImageView>(R.id.bookmarkIcon)
        val currentBar = row.findViewById<View>(R.id.currentBar)
        val currentBadge = row.findViewById<TextView>(R.id.currentBadge)

        nameView.text = bookmark.name
        pathView.text = bookmark.path

        if (isCurrent) {
            // 当前所在目录：主色名称 + 左侧指示条 + 「当前」徽章
            val accent = ContextCompat.getColor(this, R.color.primary)
            nameView.setTextColor(accent)
            iconView.imageTintList = ColorStateList.valueOf(accent)
            currentBar.visibility = View.VISIBLE
            currentBadge.visibility = View.VISIBLE
        } else {
            nameView.setTextColor(themeTextColor())
            iconView.imageTintList = ColorStateList.valueOf(mutedIconColor)
            currentBar.visibility = View.INVISIBLE
            currentBadge.visibility = View.GONE
        }

        row.setOnClickListener { onOpen() }
        row.setOnLongClickListener {
            onMenu()
            true
        }

        row.findViewById<ImageButton>(R.id.moreBtn).setOnClickListener { onMenu() }
    }

    /**
     * 删除书签统一入口：先确认，再删除。
     *
     * 所有删除路径（条目菜单、长按路径栏的「移除书签」）都从这里走，
     * 不允许任何地方直接调 [BookmarkStore.remove]。
     *
     * @param hintUndo 是否在确认框里承诺「可以撤销」（只有面板内删除才有撤销条）
     * @param onRemoved 删除成功回调，参数为原位信息（供撤销使用）；未删除则为 null
     */
    private fun confirmRemoveBookmark(
        bookmark: Bookmark,
        hintUndo: Boolean,
        onRemoved: (BookmarkStore.Removed?) -> Unit
    ) {
        val message = buildString {
            append("确定删除「").append(bookmark.name).append("」？\n\n")
            append(bookmark.path)
            if (hintUndo) append("\n\n删错了不要紧：面板底部会留一个「撤销」。")
        }
        AlertDialog.Builder(this)
            .setTitle("删除书签")
            .setMessage(message)
            .setPositiveButton("删除") { _, _ ->
                val removed = BookmarkStore.take(this, bookmark.path)
                if (removed == null) {
                    toast("这个书签已经不在了")
                } else {
                    onRemoved(removed)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 书签条目的操作菜单 —— 画在**面板内部**（[SheetDialog]），不开新窗口。
     *
     * 3.12.0 这里用的是 AlertDialog，真机上点「⋮」什么都看不到：面板之上再开
     * 一个窗口（BottomSheetDialog 已经是 Dialog）在部分机型上会被面板整个盖住。
     *
     * @param onDelete 删除不走这里直接执行 —— 它只负责再问一次
     */
    private fun showBookmarkItemMenu(
        panel: SheetDialog,
        bookmark: Bookmark,
        onChanged: () -> Unit,
        onDelete: () -> Unit
    ) {
        panel.menu(
            title = bookmark.name,
            message = bookmark.path,
            actions = listOf("重命名", "复制路径", "删除"),
            onPick = { which ->
                when (which) {
                    0 -> showBookmarkRenameDialog(panel, bookmark, onChanged)
                    1 -> copyToClipboard(bookmark.path)
                    2 -> onDelete()
                }
            }
        )
    }

    /** 重命名：同样画在面板内部，输入框在面板顶部，不会被软键盘盖住 */
    private fun showBookmarkRenameDialog(
        panel: SheetDialog,
        bookmark: Bookmark,
        onChanged: () -> Unit
    ) {
        panel.input(
            title = "重命名书签",
            message = bookmark.path,
            value = bookmark.name,
            hint = "书签名称",
            okText = "保存",
            password = false,
            onOk = { text ->
                if (BookmarkStore.rename(this, bookmark.path, text)) {
                    onChanged()
                } else {
                    toast("名称不能为空")
                }
            },
            onCancel = {}
        )
    }

    private fun addBookmark(path: String) {
        val name = File(path).name.ifEmpty { path.ifEmpty { "/" } }
        if (BookmarkStore.add(this, Bookmark(name, path))) {
            toast("已添加书签: $name")
        } else {
            toast("该路径已在书签中")
        }
    }

    // --------------------------------------------------------------- 杂项

    private fun copyToClipboard(text: String) {
        Intents.copyToClipboard(this, text)
        toast("已复制")
    }

    private fun toggleTheme() {
        val dark = ThemeStore.toggle(this)
        toast(if (dark) "已切换到深色" else "已切换到浅色")
    }

    /** 给对话框里的输入框套一层水平留白，避免贴边 */
    private fun padded(input: EditText): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (20 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        addView(input)
    }

    // ------------------------------------------------------------------ 排序

    /**
     * 读回排序偏好并写进 [FileRepository]。
     *
     * 排序在**仓库层**统一应用（两条通道共用一份，见 `FileRepository.applySort`），
     * 所以这里只是把偏好搬过去，不自己排。读一次就够：偏好只在排序面板里改，
     * 改完立刻同步给仓库，不必每次列目录都读一遍盘。
     */
    private fun loadSortPrefs() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        FileRepository.sortKey = FileSort.keyOf(prefs.getString(KEY_SORT_KEY, null))
        FileRepository.sortOrder = FileSort.orderOf(prefs.getString(KEY_SORT_ORDER, null))
        FileRepository.sortDirsFirst =
            prefs.getBoolean(KEY_SORT_DIRS_FIRST, FileSort.DEFAULT_DIRS_FIRST)
    }

    private fun saveSortPrefs() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_SORT_KEY, FileRepository.sortKey.name)
            .putString(KEY_SORT_ORDER, FileRepository.sortOrder.name)
            .putBoolean(KEY_SORT_DIRS_FIRST, FileRepository.sortDirsFirst)
            .apply()
    }

    /**
     * 顶栏「⋯」：书签 / 排序方式 / 切换主题 / 局域网文件服务。
     *
     * 顶栏此前是 6 个元素并列，360dp 的屏上路径胶囊只剩 128dp ——
     * 而 `/storage/emulated/0` 在 13sp 下要 133dp，**路径从来就没完整显示过**。
     * 把频率中低的动作收进这里之后，顶栏回到「位置 + 3 个动作」的规模。
     *
     * 原有动作仍走 [showBookmarkDialog] / [showSortSheet] / [toggleTheme]。
     */
    private fun showMoreMenu() {
        val sheet = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_more, null)
        sheet.setContentView(content)
        // 用 SheetDialog 的面板**不要**再调 glassSurface()：它和 expandFullScreen()
        // 都往 setOnShowListener 里写，而那是**替换语义**（谁后注册谁生效）——
        // 两个一起用会让容器又变回不透明的玻璃，整屏糊成一块死色（3.15.7 的翻车点）。
        val panel = SheetDialog(sheet, content)
        // 这张面板的本体只有一个拖拽条、**不承载内容** —— 菜单全部画在 SheetDialog 的卡片里。
        // 所以收起卡片时必须连面板一起关掉：面板已被 expandFullScreen() 撑满全屏，
        // 「点面板外部关闭」这条路不存在，留着空面板就只剩一层 dim（用户看到的是
        // 「点了一次没消失，要再点一次」）。
        panel.cardIsOnlyContent()
        panel.expandFullScreen()
        val actions = mutableListOf("书签", "排序方式", "切换主题")
        if (archiveSession != null) actions.add(0, "退出压缩包")
        panel.menu("更多", null, actions) { index ->
            // 收掉这一层再开下一个面板：两个 BottomSheetDialog 叠在一起会互相遮挡。
            sheet.dismiss()
            when (actions[index]) {
                "退出压缩包" -> exitArchive()
                "书签" -> showBookmarkDialog()
                "排序方式" -> showSortSheet()
                "切换主题" -> toggleTheme()
            }
        }
        sheet.show()
    }

    /**
     * 排序面板。
     *
     * 与其它面板不同，这张承载的是**持续状态**而不是一次确认：点一行只改状态、
     * 就地刷新文字，**面板不关**。用户的典型动作是「换个维度看看，不对再换回来」，
     * 每点一次都要重开面板的话这个来回会非常烦。
     */
    private fun showSortSheet() {
        val sheet = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_sort, null)
        sheet.setContentView(content)
        sheet.setOnShowListener { sheet.glassSurface() }

        val summary = content.findViewById<TextView>(R.id.sortSummary)
        val rows = mapOf(
            content.findViewById<TextView>(R.id.sortByName) to FileSort.Key.NAME,
            content.findViewById<TextView>(R.id.sortBySize) to FileSort.Key.SIZE,
            content.findViewById<TextView>(R.id.sortByTime) to FileSort.Key.TIME,
            content.findViewById<TextView>(R.id.sortByType) to FileSort.Key.TYPE
        )
        val orderRow = content.findViewById<TextView>(R.id.sortOrder)
        val dirsRow = content.findViewById<TextView>(R.id.sortDirsFirst)

        /**
         * 把面板这一屏按当前状态重画。
         *
         * 每次改动都先落盘、再重画、最后重列目录 —— 关掉面板时列表已经是新的了，
         * 不需要「确定」再走一遍。
         */
        fun commit() {
            saveSortPrefs()
            summary.text = "当前：${FileSort.labelOf(FileRepository.sortKey)}" +
                " ${FileSort.orderLabelOf(FileRepository.sortOrder)}" +
                if (FileRepository.sortDirsFirst) " · 目录优先" else ""
            for ((row, key) in rows) {
                val on = FileRepository.sortKey == key
                // 选中态用行首的勾 + 主色，不用第二套背景：同一行既有选中又有未选中，
                // 文字标记在任何主题下都不会看错，也不会和按下去的波纹打架
                row.text = if (on) "✓  ${FileSort.labelOf(key)}" else "      ${FileSort.labelOf(key)}"
                row.alpha = if (on) 1f else 0.75f
                row.setTextColor(
                    ContextCompat.getColor(this, if (on) R.color.primary else R.color.text_primary)
                )
            }
            orderRow.text = "      排序方向：${FileSort.orderLabelOf(FileRepository.sortOrder)}"
            dirsRow.text = "      目录优先：${if (FileRepository.sortDirsFirst) "开" else "关"}"
        }

        for ((row, key) in rows) {
            row.setOnClickListener {
                FileRepository.sortKey = key
                commit()
                loadDir(currentPath)
            }
        }
        orderRow.setOnClickListener {
            FileRepository.sortOrder =
                if (FileRepository.sortOrder == FileSort.Order.ASC) {
                    FileSort.Order.DESC
                } else {
                    FileSort.Order.ASC
                }
            commit()
            loadDir(currentPath)
        }
        dirsRow.setOnClickListener {
            FileRepository.sortDirsFirst = !FileRepository.sortDirsFirst
            commit()
            loadDir(currentPath)
        }
        content.findViewById<TextView>(R.id.sortClose).setOnClickListener { sheet.dismiss() }

        commit()
        sheet.show()
    }

    /** 读取主题正文色，保证浅色/深色主题下条目文字都清晰 */
    private fun themeTextColor(): Int {
        val value = android.util.TypedValue()
        val resolved = theme.resolveAttribute(android.R.attr.textColorPrimary, value, true)
        return if (resolved && value.resourceId != 0) {
            ContextCompat.getColor(this, value.resourceId)
        } else if (resolved) {
            value.data
        } else {
            mutedIconColor
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_START_PATH = "start_path"

        /**
         * 由 IncomingActivity（外部「用其他应用打开」入口）塞进来的**已反解的真实路径**。
         * 本界面收到后导航到它所在目录并高亮那一行 —— 入口只做反解，定位与高亮在这里。
         */
        const val EXTRA_REVEAL_PATH = "reveal_path"

        /** 高亮停留时长；够看清，又不至于一直杵着 */
        private const val REVEAL_MS = 2600L

        private const val TAG = "MainActivity"
        private const val PREFS = "shell_prefs"
        private const val DEFAULT_TEXT = "RxinFiles-Tool"

        /** 书签删除后的撤销窗口：够把误删看回来，又不至于一直杵在那里 */
        private const val UNDO_WINDOW_MS = 6000L

        /** 排序偏好（3.15.5）：维度 / 升降序 / 目录优先。缺省时由 FileSort 回落到默认值 */
        private const val KEY_SORT_KEY = "sort_key"
        private const val KEY_SORT_ORDER = "sort_order"
        private const val KEY_SORT_DIRS_FIRST = "sort_dirs_first"
    }
}
