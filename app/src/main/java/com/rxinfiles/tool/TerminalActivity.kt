package com.rxinfiles.tool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

/**
 * 终端界面。
 *
 * 默认会话为**普通用户模式**：直接 exec `/system/bin/sh`，进程身份就是应用自身 uid。
 *
 * 为什么默认不提权：
 *  - 内置沙箱 RXin 落在应用私有目录 `/data/user/0/<pkg>/RXin`，
 *    shell 身份（uid 2000）读不到该目录，PATH / ENV 会整个失效；
 *  - 需要提权时在终端里手动敲 `su` 即可。
 *
 * 唯一的例外是**从文件管理器执行脚本时显式选择「以 ROOT 执行」**：
 * 那时会以 `su` 作为会话的 shell，并把沙箱 PATH / ENV 在 root shell 里重新导出
 * （su 会清掉继承来的环境变量，不重新导出的话内置命令会「消失」）。
 * 这是用户对自己脚本的显式选择，与「应用自身文件操作权限」是两回事 ——
 * 后者始终由 [ShellExecutor.detect] 自动探测，不提供手动开关。
 *
 * ## shell 用哪一个
 *
 * 会话的 shell 固定是 `/system/bin/sh`，也就是 Android 上的 **mksh**，
 * **不换成 busybox 的 ash**：mksh 支持 `[[ ]]`、数组、`$'...'`、`<<<`、
 * `${var/a/b}`、`+=` 这一批扩展，比 ash 更接近 bash，脚本兼容性反而更好。
 * 内置工具箱（[RxinToolbox]）只负责**补命令**，不碰 shell 本体。
 *
 * 脚本里写 `bash` 或 `#!/bin/bash` 的，会命中沙箱 `bin/bash` 垫片，
 * 一样落到 mksh 上 —— 这是 Android 上能做到的最接近 bash 的效果。
 */
class TerminalActivity : AppCompatActivity(), TerminalViewClient, TerminalSessionClient {

    private lateinit var terminalView: TerminalView
    private lateinit var tabLayout: TabLayout
    private lateinit var modeText: TextView

    private lateinit var retained: RetainedSessions
    private val sessions get() = retained.sessions
    private val sessionCwd get() = retained.sessionCwd
    private val sessionAsRoot get() = retained.sessionAsRoot
    private val sessionNames get() = retained.sessionNames
    private val finishedScripts get() = retained.finishedScripts
    private val finishedExitCodes get() = retained.finishedExitCodes

    private var scriptMode
        get() = retained.scriptMode
        set(value) {
            retained.scriptMode = value
        }

    private var scriptAsRoot
        get() = retained.scriptAsRoot
        set(value) {
            retained.scriptAsRoot = value
        }

    private var scriptSession
        get() = retained.scriptSession
        set(value) {
            retained.scriptSession = value
        }

    private var ctrlDown = false
    private var altDown = false
    private var fontSize = 14f
    private var closingForScriptDone = false

    private class RetainedSessions {
        val sessions = mutableListOf<TerminalSession>()
        val sessionCwd = mutableMapOf<TerminalSession, String>()
        val sessionAsRoot = mutableMapOf<TerminalSession, Boolean>()
        val sessionNames = mutableMapOf<TerminalSession, String>()
        val finishedScripts = mutableSetOf<TerminalSession>()
        val finishedExitCodes = mutableMapOf<TerminalSession, Int>()
        var scriptSession: TerminalSession? = null
        var scriptMode = false
        var scriptAsRoot = false
        var selectedIndex = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retained = lastCustomNonConfigurationInstance as? RetainedSessions ?: RetainedSessions()
        setContentView(R.layout.activity_terminal)

        // 终端要用的 PATH / ENV 都指向沙箱，先确保沙箱就绪（幂等，通常只是一次比对）
        RxinSandbox.ensure(this)

        // 内置工具箱：首次会 exec 探测 busybox 并铺出两百多个符号链接（约百毫秒级），
        // 之后靠状态戳短路。MainActivity 启动时已经在后台预热过，正常情况下这里是空转。
        RxinToolbox.ensure(this)

        // 工具链失效的原因**全在进程外面**（SELinux / seccomp / 装机时有没有解压 native lib），
        // App 内部无从补救，只能把它说出来 —— 否则表现成「命令莫名其妙找不到」。
        // 每个进程只说一次，不必每次开标签都弹。
        if (!RxinToolbox.available && !toolboxNoticeShown) {
            toolboxNoticeShown = true
            toast(RxinToolbox.status)
        }

        terminalView = findViewById(R.id.terminalView)
        tabLayout = findViewById(R.id.tabLayout)
        modeText = findViewById(R.id.modeText)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        fontSize = prefs.getFloat("font_size", 14f)
        terminalView.setTextSize(fontSize.toInt())
        terminalView.setTerminalViewClient(this)
        // TerminalView 必须拿到焦点，否则 onCreateInputConnection() 不会被调用，输入法无法附着
        terminalView.requestFocus()

        setupToolbar()
        setupScriptDoneBar()
        updateModeText()

        if (sessions.isNotEmpty()) {
            restoreSessions()
            return
        }

        val scriptPath = intent.getStringExtra(EXTRA_SCRIPT_PATH)
        val scriptCommand = intent.getStringExtra(EXTRA_SCRIPT_COMMAND)
        val workDir = intent.getStringExtra(EXTRA_WORK_DIR)?.takeIf { it.isNotEmpty() }
        // 仅「执行脚本」入口可能要求 root 身份（由用户在文件管理器里显式选择）
        val asRoot = intent.getBooleanExtra(EXTRA_RUN_AS_ROOT, false)

        if (!scriptPath.isNullOrEmpty() && !scriptCommand.isNullOrEmpty()) {
            scriptMode = true
            scriptAsRoot = asRoot
            val name = intent.getStringExtra(EXTRA_SCRIPT_NAME) ?: File(scriptPath).name
            // 脚本所在目录直接作为工作目录，脚本内的相对路径引用才不会错位
            val cwd = workDir ?: File(scriptPath).parent ?: defaultDir()
            openSession(cwd, scriptCommand, name, intent.getStringExtra(EXTRA_SCRIPT_NOTICE), asRoot)
        } else {
            openSession(workDir ?: defaultDir())
        }
    }

    override fun onRetainCustomNonConfigurationInstance(): Any = retained

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            sessions.forEach { it.finishIfRunning() }
            sessions.clear()
            sessionCwd.clear()
            sessionAsRoot.clear()
            sessionNames.clear()
            finishedScripts.clear()
            finishedExitCodes.clear()
            scriptSession = null
        }
        super.onDestroy()
    }

    /**
     * 返回键 = 退出终端界面（与原版行为一致：原版未重写 onBackPressed）。
     * 关闭单个标签请用工具栏的 × ；关闭全部会话请用工具栏的"退出"。
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
    }

    // ------------------------------------------------------------- UI 初始化

    private fun setupToolbar() {
        findViewById<TextView>(R.id.newTabBtn).setOnClickListener {
            // 结束态下「+」本来就是隐藏的；这里是第二道闸门，免得以后谁把它放出来
            if (currentFinished()) return@setOnClickListener
            val current = currentSession()
            // 新标签继承当前会话的身份：在 root 会话里开新标签，用户期待的仍是 root
            val asRoot = current != null && sessionAsRoot[current] == true
            openSession(
                if (current != null) currentCwd() else defaultDir(),
                asRoot = asRoot
            )
        }
        findViewById<TextView>(R.id.closeTabBtn).setOnClickListener { closeCurrentSession() }
        findViewById<TextView>(R.id.settingsBtn).setOnClickListener { showSettingsDialog() }
        findViewById<TextView>(R.id.exitBtn).setOnClickListener { confirmExit() }

        bindKey(R.id.keyEsc, "\u001b")
        bindKey(R.id.keyTab, "\t")
        bindKey(R.id.keyLeft, "\u001b[D")
        bindKey(R.id.keyDown, "\u001b[B")
        bindKey(R.id.keyUp, "\u001b[A")
        bindKey(R.id.keyRight, "\u001b[C")
        bindKey(R.id.keyHome, "\u0001")
        bindKey(R.id.keyEnd, "\u0005")

        findViewById<TextView>(R.id.keyCtrl).setOnClickListener {
            // 结束态下按下去就退出，而不是「点了没反应」—— 没反应会让人以为界面卡死
            if (currentFinished()) {
                exitBecauseScriptDone()
                return@setOnClickListener
            }
            ctrlDown = !ctrlDown
            updateKeyHighlight()
        }
        findViewById<TextView>(R.id.keyAlt).setOnClickListener {
            if (currentFinished()) {
                exitBecauseScriptDone()
                return@setOnClickListener
            }
            altDown = !altDown
            updateKeyHighlight()
        }

        // 初值也刷一次：ctrlDown / altDown 初值都是 false，而 alpha 只在点击时才更新 ——
        // 不刷的话一进终端 CTRL / ALT 就是全亮 1.0，看着像已经按下了。
        updateKeyHighlight()

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val index = tab.position
                if (index in sessions.indices) {
                    retained.selectedIndex = index
                    terminalView.attachSession(sessions[index])
                    // 身份标签跟着当前会话走，否则从普通标签切到 root 标签会显示错
                    updateModeText()
                    // 提示条与「+」也跟着当前标签走（脚本结束态是「每个会话」的）
                    // 注意**不动键盘**：切到结束态标签时键盘照样留着 —— 敲一下正好退出
                    applyFinishedUi()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    /**
     * 屏幕上的功能键（ESC / Tab / 方向键…）。
     *
     * 结束态下这一行**仍然可见**（它是手机上最顺手的那个「任意键」，见 [applyFinishedUi]），
     * 所以这里的判断是「按下去就退出」，而不是「按下去没反应」——
     * 后者会让用户以为界面卡死，而且把「按任意键返回」的出口堵死。
     */
    private fun bindKey(id: Int, sequence: String) {
        findViewById<TextView>(id).setOnClickListener {
            if (currentFinished()) {
                exitBecauseScriptDone()
                return@setOnClickListener
            }
            currentSession()?.write(sequence)
        }
    }

    /** 结束提示条：点一下也算「任意键」—— 条上写着「按任意键返回」，能点最省事 */
    private fun setupScriptDoneBar() {
        findViewById<View>(R.id.scriptDoneBar).setOnClickListener { exitBecauseScriptDone() }
    }

    private fun updateKeyHighlight() {
        findViewById<TextView>(R.id.keyCtrl).alpha = if (ctrlDown) 1f else 0.5f
        findViewById<TextView>(R.id.keyAlt).alpha = if (altDown) 1f else 0.5f
    }

    /**
     * 身份标签。
     *
     * 终端**默认固定为普通用户**（应用自身 uid），没有全局的模式切换开关；
     * 唯一的例外是当前会话本身就是 root 会话（由文件管理器里「以 ROOT 执行」显式打开）。
     * 因此这里跟着**当前标签**走，而不是跟着某个全局设置走。
     */
    private fun updateModeText() {
        val current = currentSession()
        val root = when {
            current != null -> sessionAsRoot[current] == true
            scriptMode -> scriptAsRoot
            else -> false
        }
        modeText.text = getString(
            if (root) R.string.terminal_mode_root else R.string.terminal_mode_text
        )
    }

    private fun defaultDir(): String {
        val sdcard = Environment.getExternalStorageDirectory()
        val work = File(sdcard, "RouXin")
        return try {
            if (work.exists()) work.canonicalPath else sdcard.absolutePath
        } catch (t: Throwable) {
            sdcard.absolutePath
        }
    }

    private fun currentCwd(): String =
        currentSession()?.let { sessionCwd[it] } ?: defaultDir()

    // --------------------------------------------------------------- 脚本结束态

    /** 当前标签是不是那个已经收尾的脚本会话 */
    private fun currentFinished(): Boolean = currentSession()?.let { it in finishedScripts } ?: false

    /**
     * 结束态下收到任何输入 —— 退回文件管理。
     *
     * 这是 3.13.1 的核心动作：脚本跑完之后，这一屏**不再是一个终端**，
     * 而是一张「输出已经打印完了，看完就走」的结果页。按任意键（或点提示条）离开。
     *
     * 只关一次：一次按键会先后经过按下与抬起两条回调，`closingForScriptDone`
     * 负责挡住第二次（否则会连 toast 都弹两遍）。
     */
    private fun exitBecauseScriptDone() {
        if (closingForScriptDone) return
        closingForScriptDone = true
        // toast 挂的是系统窗口，比这个界面活得久，正好用来回答「为什么它自己关了」
        toast("脚本已结束，返回文件管理")
        finish()
    }

    /**
     * 脚本跑完了，把这个会话换成**结束态**。
     *
     * 两个入口，缺一不可：
     *  - 正常路径：[onTitleChanged] 收到命令尾写的 [ScriptDone] 标记（带退出码）；
     *  - 兜底路径：[onSessionFinished] —— 脚本把 shell 一起带走了（`exec` 换了进程、
     *    被 OOM 杀掉、用户中途敲了 `exit`），压根没人来写标记。
     *
     * 进来之后做三件事：把会话收掉、把界面换成结束态、**留着键盘**（它是出口，见下）。
     *
     * **为什么是「现在」才关会话**：这条通知是被脚本自己的最后一行输出带上来的，
     * 也就是说脚本的全部输出**都已经渲染到屏幕上**了。此时关掉 PTY 不会截掉任何东西。
     * 反过来，若把 `exit` 写在命令尾部，PTY 会在 reader 线程还没把输出送进队列时就被
     * 清理掉，最后几 KB 会**静默丢失** —— 那恰好是用户最想看的几行，
     * 也正是 3.11.2 把 `exit` 拿掉的真正原因。
     *
     * 关掉会话还有一层兜底：万一有哪条输入路径没被本活动的回调拦下，
     * 写进去的字节也会在 `TerminalSession.write` 里被丢弃（`mShellPid <= 0` 时直接 return）
     * —— 到不了 shell，更不会执行成半条命令。
     *
     * 但**真正负责「敲一下就走」的是回调**，不是关会话：输入法的字符经
     * `TerminalView.sendTextToTerminal` → `inputCodePoint` → `mClient.onCodePoint`
     * 落到 [onCodePoint] 上（TerminalView.java 里的原话是
     * `if (mClient.onCodePoint(...)) return;`）。所以键盘必须留着 —— 它是出口。
     */
    private fun markScriptFinished(session: TerminalSession, exitCode: Int) {
        if (session !== scriptSession) return
        if (!finishedScripts.add(session)) return
        if (exitCode != ScriptDone.NO_CODE) finishedExitCodes[session] = exitCode

        // 屏幕上已经有中文的「[RouXin] 脚本已结束，退出码 N」，
        // 不必再补一句英文的 `[Process completed (signal 9) - press Enter]`
        session.setAppendExitNotice(false)
        session.finishIfRunning()
        // 光标收起来：屏幕上那枚还在闪的光标，看着就像还在等输入。
        // 直接喂一条 `CSI ? 25 l`（任何程序隐藏光标都是这么写的）——
        // 调 setCursorBlinkingEnabled(false) 只是「不闪」，光标还杵在那儿。
        session.emulator?.let { emulator ->
            val hideCursor = "\u001b[?25l".toByteArray(Charsets.UTF_8)
            emulator.append(hideCursor, hideCursor.size)
        }

        if (session === currentSession()) {
            applyFinishedUi()
            // **故意不关软键盘**（3.13.2 的真机反馈）。
            //
            // 3.13.1 初版在这里调了 hideSoftKeyboard()，同时把屏幕按键行也收起，
            // 结果是三条输入路径全断 —— 而这一屏的规则偏偏是「按任意键返回文件管理」，
            // 用户当场就问：「键盘都被关了，你让我怎么测试任意键退出」。
            //
            // 「终端不再可用」跟键盘开不开**毫无关系**：上面那句 finishIfRunning() 之后
            // mShellPid <= 0，TerminalSession.write 会把一切写入直接丢弃 —— 键盘敲出来的
            // 字符到不了任何地方，只会触发「退出」这一个动作。所以键盘要留着 ——
            // 它和底部那条可点的提示条一起，是这一屏仅有的两个出口。
        }
    }

    /**
     * 结束态的界面：提示条与「+」。
     *
     * 按**当前标签**算，而不是按「这个活动里有没有脚本结束过」算：脚本跑着的时候用户
     * 完全可以点「+」再开一个普通标签，那个标签不该被牵连（切回去照样能用）。
     *
     * **屏幕按键行不参与切换，全程可见**（3.13.2 改）。3.13.1 初版把它跟着收起来，
     * 加上同时关掉的软键盘，等于把「按任意键返回」的两条出口一起堵死 ——
     * 剩用户对着「按任意键」的提示却无键可按。它是手机上最顺手的那个「任意键」。
     */
    private fun applyFinishedUi() {
        val session = currentSession()
        val finished = session != null && session in finishedScripts

        findViewById<View>(R.id.scriptDoneBar).visibility =
            if (finished) View.VISIBLE else View.GONE
        findViewById<View>(R.id.scriptDoneDivider).visibility =
            if (finished) View.VISIBLE else View.GONE

        // 显式写出来（而不是留着不管）：这一行在结束态下**必须**可见。
        // 谁把它改成 GONE，verify-scriptpipe.py 第 5 节会当场红。
        findViewById<View>(R.id.keyBar).visibility = View.VISIBLE

        // 「+」是「另开一个标签继续用」，属于「再次使用终端」，结束态下才真正要收起
        findViewById<View>(R.id.newTabBtn).visibility =
            if (finished) View.GONE else View.VISIBLE

        if (finished && session != null) updateScriptDoneBar(session)
    }

    /** 提示条上的那两行字；退出码非 0 时整条转成红色（与工具栏的「退出」同一支色） */
    private fun updateScriptDoneBar(session: TerminalSession) {
        val code = finishedExitCodes[session]
        val title = findViewById<TextView>(R.id.scriptDoneTitle)
        title.text = if (code == null) {
            getString(R.string.terminal_script_done)
        } else {
            getString(R.string.terminal_script_done_code, code.toString())
        }
        title.setTextColor(
            if (code != null && ScriptDone.isFailure(code)) COLOR_FAIL else COLOR_OK
        )
        findViewById<TextView>(R.id.scriptDoneHint).setText(R.string.terminal_script_done_hint)
    }

    // 3.13.2 从这里删掉了一个「收起软键盘」的工具函数。
    // 结束态改回「留着键盘当出口」之后它就没有调用点了 —— 而「脚本跑完要挡住输入」
    // 这条需求已经由会话层独立保证（finishIfRunning 之后写入直接被丢弃）。
    // 不留着：一个闲置的收键盘工具只会误导后来的人再去把出口堵上。

    // --------------------------------------------------------------- 会话管理

    private fun currentSession(): TerminalSession? = terminalView.currentSession

    /**
     * 新建会话。
     *
     * 一律走本地 `/system/bin/sh`（应用自身 uid）。Termux 原生 PTY + JNI
     * createSubprocess 直接把 shell 挂到本进程下，没有跨进程 fd 传递，
     * 因此退出信号、窗口尺寸（TIOCSWINSZ）、输入输出都是最直接的一条路径。
     *
     * @param asRoot true 时把一个交互式 root shell 作为会话主进程（见 [rootArgs]）
     */
    private fun openSession(
        cwd: String,
        scriptCommand: String? = null,
        tabName: String? = null,
        notice: String? = null,
        asRoot: Boolean = false
    ) {
        val session = TerminalSession(
            SHELL_PATH,
            cwd,
            // 注意：native 侧是 execvp(cmd, argv)，argv[0] 直接取自这里。
            // 所以传 `-c` 时必须自己补一个 argv[0]，否则 mksh 会把命令当成脚本文件名。
            if (asRoot) rootArgs(cwd) else emptyArray(),
            buildEnv(cwd),
            TRANSCRIPT_ROWS,
            this
        )
        addSession(session, cwd, tabName, scriptCommand, notice, asRoot)
    }

    private fun addSession(
        session: TerminalSession,
        cwd: String,
        tabName: String? = null,
        scriptCommand: String? = null,
        notice: String? = null,
        asRoot: Boolean = false
    ) {
        sessions.add(session)
        sessionCwd[session] = cwd
        sessionAsRoot[session] = asRoot
        val name = tabName ?: "t${sessions.size}"
        sessionNames[session] = name
        val tab = tabLayout.newTab().setText(name)
        tabLayout.addTab(tab)
        tabLayout.selectTab(tab)
        terminalView.attachSession(session)
        terminalView.requestFocus()
        updateModeText()

        if (scriptCommand != null) {
            // 脚本模式：把命令排队，等会话真正就绪后再注入（见 runScriptWhenReady）。
            // 同时记下「哪个会话是脚本会话」—— 只有它会进入结束态。
            scriptSession = session
            runScriptWhenReady(session, notice, scriptCommand)
        } else {
            // onCreate / 新建标签时视图可能尚未完成测量与窗口附着，
            // 此时 showSoftInput 会静默失败，因此延后到下一帧执行
            terminalView.post { showSoftKeyboard() }
        }
    }

    private fun restoreSessions() {
        sessions.forEachIndexed { index, session ->
            session.updateTerminalSessionClient(this)
            val tab = tabLayout.newTab().setText(sessionNames[session] ?: "t${index + 1}")
            tabLayout.addTab(tab, false)
        }
        val index = retained.selectedIndex.coerceIn(sessions.indices)
        retained.selectedIndex = index
        tabLayout.selectTab(tabLayout.getTabAt(index))
        terminalView.attachSession(sessions[index])
        terminalView.requestFocus()
        updateModeText()
        applyFinishedUi()
    }

    /**
     * 等待会话就绪后写入脚本命令。
     *
     * 会话的 PTY 与终端模拟器是在 TerminalView 完成测量后
     * （onSizeChanged → updateSize → initializeEmulator）才建立的；
     * 在那之前 `TerminalSession.write()` 会因为 `mShellPid <= 0` 而**静默丢弃**输入。
     * 因此这里轮询确认视图已测量且会话已在运行，而不是盲等一个固定时长。
     */
    private fun runScriptWhenReady(
        session: TerminalSession,
        notice: String?,
        command: String,
        retry: Int = 0
    ) {
        if (!sessions.contains(session)) return

        val ready = terminalView.width > 0 && terminalView.height > 0 && session.isRunning
        if (!ready) {
            if (retry >= READY_MAX_RETRY) {
                toast("终端未就绪，脚本未能启动")
                return
            }
            terminalView.postDelayed({ runScriptWhenReady(session, notice, command, retry + 1) }, 50)
            return
        }

        // 解释器回退的说明先打印，再执行脚本本体
        if (!notice.isNullOrBlank()) {
            session.write(notice + "\n")
        }
        session.write(command + "\n")
    }

    /** 关闭当前标签；若这是最后一个标签则退出终端界面 */
    private fun closeCurrentSession() {
        val session = currentSession() ?: return
        removeSession(session, closeActivityWhenEmpty = true)
    }

    /**
     * 从界面移除一个会话。
     *
     * @param closeActivityWhenEmpty 关闭最后一个会话时是否直接结束 Activity
     */
    private fun removeSession(session: TerminalSession, closeActivityWhenEmpty: Boolean) {
        val index = sessions.indexOf(session)
        if (index < 0) return

        session.finishIfRunning()

        sessionCwd.remove(session)
        sessionAsRoot.remove(session)
        sessionNames.remove(session)
        // 结束态的登记也要跟着会话一起走：会话对象没了，这两个映射就是泄漏
        finishedScripts.remove(session)
        finishedExitCodes.remove(session)
        if (session === scriptSession) scriptSession = null

        sessions.removeAt(index)
        if (index < tabLayout.tabCount) {
            tabLayout.removeTabAt(index)
        }

        if (sessions.isEmpty()) {
            if (closeActivityWhenEmpty) {
                finish()
            }
        } else {
            val next = sessions.getOrNull(index.coerceAtMost(sessions.size - 1))
            if (next != null) {
                terminalView.attachSession(next)
                terminalView.requestFocus()
            }
            updateModeText()
            applyFinishedUi()
        }
    }

    /** 退出终端界面（会结束所有会话） */
    private fun confirmExit() {
        if (sessions.isEmpty()) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("退出终端")
            .setMessage("将结束全部 ${sessions.size} 个会话并返回文件管理。")
            .setPositiveButton("退出") { _, _ -> finish() }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 终端环境变量。
     *
     * 这里承担的是「让 sh 脚本尽量跑得起来」的接线工作，共四层：
     *
     * **1. PATH —— 补命令**
     *  - `RXin/bin` **前置**：原版内置命令（rxe / hello / …）加上内置工具箱的
     *    覆盖清单（sed / grep / egrep / fgrep / find / xargs / tar / date / diff / cmp）
     *    与 `busybox` 本体名。那批是 Android 自带版本明显残缺、脚本又高频依赖的，
     *    必须抢在前面。
     *  - `RXin/extra` **后置**：busybox 其余 applet。放最后是为了「只补缺、不遮蔽」——
     *    Android 没有的（awk / less / vi / wget / bc …）自然命中，
     *    Android 有的仍走系统版本，行为不变。
     *    这也是 `awk` 不在覆盖清单里的原因：它本来就缺，后置同样能命中。
     *  - 两个目录里**全是符号链接**（指向 nativeLibraryDir 的 busybox），不是脚本。
     *    沙箱在 `app_data_file` 上，targetSdk>=29 起内核禁止执行那里的文件；
     *    符号链接解析到 `apk_data_file` 就绕过了这条限制。详见 [RxinToolbox]。
     *
     * **2. TMPDIR —— 补临时目录**
     * Android 没有 `/tmp`（根文件系统只读，root 也建不了），`RXin/tmp` 顶上。
     *
     * **3. ENV —— 交互式 shell 的兼容层**
     * 指向 `etc/profile.sh`：它 source 原版 `.shinit`（内置命令靠它接回来），
     * 并补上 bash 特有内建的最小实现（`declare` / `shopt` …）。
     * **只对交互式生效**：脚本执行时不加载，往别人的脚本里塞函数会改变它的语义。
     *
     * **4. RXINBOX / RXIN_HOME / RXIN_STATUS** —— 定位沙箱，以及**报告工具链状态**。
     *
     * 内置工具箱不可用时（busybox 缺失、SELinux 不让执行、被 seccomp 杀掉），
     * **整个回退成原样**：PATH 里只有系统目录 + 沙箱 bin，命令照旧走系统版本，
     * 而原因落在 `RXIN_STATUS` 里、并由 profile 打印出来。
     *
     * **实现已抽到 [RxinEnv.build]** —— 脚本执行的管道通道（[ScriptPipe]）用的是同一份，
     * 否则「终端里找得到、脚本里找不到」这类错位迟早会出现。
     */
    private fun buildEnv(cwd: String): Array<String> = RxinEnv.build(this, cwd)

    /**
     * root 会话的 argv。
     *
     * 为什么不直接把 shell 设成 `su`：
     *  - `su` 拿到的是「一张白纸」的环境，沙箱的 PATH / ENV 会整个丢掉，
     *    内置命令（rxe / hello / …）就消失了；
     *  - 也没法指定带引号的工作目录（有些目录只有 root 进得去）。
     *
     * 所以走 `sh -c "exec su -c '<bootstrap>'"`：先由本地 sh 把 bootstrap
     * 作为一个**完整的单参数**交给 su，su 在 root 身份下执行它 —— bootstrap
     * 重新导出沙箱 PATH / ENV、切到目标目录，最后 `exec /system/bin/sh`
     * 把交互式 root shell 顶替成会话主进程（exec 不换 pid，
     * 关标签时的 finishIfRunning 依然能杀掉它）。
     *
     * argv[0] 必须自己补：native 侧是 `execvp(cmd, argv)`，
     * 传 `["-c", cmd]` 会被 mksh 当成「脚本文件名叫 -c」。
     */
    private fun rootArgs(cwd: String): Array<String> =
        arrayOf(
            "sh",
            "-c",
            "exec $SU_PATH -c " + ScriptRunner.quote(rootBootstrap(cwd))
        )

    /**
     * root shell 的启动脚本：重新导出环境 + 切目录 + 换成交互式 sh。
     *
     * 每个值都单独做单引号转义 —— 路径里出现空格、`$`、单引号都不会散架。
     */
    private fun rootBootstrap(cwd: String): String {
        val sb = StringBuilder()
        for (entry in buildEnv(cwd)) {
            val eq = entry.indexOf('=')
            if (eq <= 0) continue
            sb.append("export ")
                .append(entry.substring(0, eq))
                .append('=')
                .append(ScriptRunner.quote(entry.substring(eq + 1)))
                .append("; ")
        }
        // 目录进不去也要继续把 shell 交出去，否则会话直接死掉、终端一片空白
        sb.append("cd ").append(ScriptRunner.quote(cwd)).append(" 2>/dev/null; ")
        sb.append("exec ").append(SHELL_PATH)
        return sb.toString()
    }

    // ------------------------------------------------------------------ 设置

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val options = arrayOf(
            "字体大小: ${fontSize.toInt()}",
            "增大字体",
            "减小字体",
            "切换深色/浅色主题"
        )
        AlertDialog.Builder(this)
            .setTitle("终端设置")
            .setItems(options) { _, which ->
                when (which) {
                    1 -> {
                        fontSize = (fontSize + 2).coerceAtMost(32f)
                        applyFontSize(prefs)
                    }
                    2 -> {
                        fontSize = (fontSize - 2).coerceAtLeast(8f)
                        applyFontSize(prefs)
                    }
                    3 -> ThemeStore.toggle(this)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun applyFontSize(prefs: android.content.SharedPreferences) {
        prefs.edit().putFloat("font_size", fontSize).apply()
        terminalView.setTextSize(fontSize.toInt())
        terminalView.updateSize()
        toast("字体大小: ${fontSize.toInt()}")
    }

    /**
     * 弹出输入法。
     * 必须先 requestFocus()：TerminalView 只有在持有焦点时才会被系统当作可输入视图，
     * 否则 showSoftInput 会因为找不到可附着的目标而静默失败。
     */
    private fun showSoftKeyboard() {
        terminalView.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------- TerminalViewClient 实现

    override fun onScale(scale: Float): Float {
        fontSize = (fontSize * scale).coerceIn(8f, 32f)
        terminalView.setTextSize(fontSize.toInt())
        terminalView.updateSize()
        return 1f
    }

    /**
     * 点一下终端 = 弹键盘。
     *
     * 结束态下**一样弹**（3.13.2 改）：提示条写着「按任意键返回文件管理」，
     * 那就得让用户有键可按 —— 键盘若关着，点一下正好把它叫出来，敲任意键即退出。
     * 3.13.1 初版在结束态下 `return` 掉这次点击，等于把最后一条出口也堵了。
     *
     * 注意**只有手指点**被这样处理：上下滑动查看、长按选中复制都照旧，
     * 用户得先把输出看完 / 复制走（脚本结束后终端不再接受输入，但输出仍然可读可抄）。
     */
    override fun onSingleTapUp(e: MotionEvent) {
        showSoftKeyboard()
    }

    /**
     * 返回键不映射为 ESC：屏幕底部有独立的 ESC 键，
     * 而返回键在 Activity.onBackPressed() 中承担「关标签 / 退出界面」的职责。
     */
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    /**
     * false → 使用 InputType.TYPE_NULL。
     *
     * 必须与原版保持一致（原版返回 false）。若返回 true，onCreateInputConnection() 会把
     * inputType 设成 TYPE_TEXT_VARIATION_VISIBLE_PASSWORD，部分输入法（尤其是中文输入法）
     * 会把它当成密码框处理，从而抑制候选词条、无法正常输入。
     */
    override fun shouldEnforceCharBasedInput(): Boolean = false

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {}

    /**
     * 结束态下**任何按键都退出界面**（本方法返回 true 表示事件已被消费）。
     *
     * 硬键盘走这里；输入法的回车 / 退格也是 —— 它们经
     * `BaseInputConnection.sendKeyEvent` → `dispatchKeyEvent` 转成按键，一样落到这条回调上。
     *
     * 输入法的**字符**（commitText / finishComposingText）走的是另一条路：
     * `TerminalView.sendTextToTerminal` → `inputCodePoint` → `mClient.onCodePoint`
     * （TerminalView.java 里 `if (mClient.onCodePoint(...)) return;` 那句），
     * 本活动在 [onCodePoint] 里同样拦掉。两条路都通到 [exitBecauseScriptDone]。
     *
     * 之所以能「拦得住」而不必先把键盘藏起来：字符确实会先到 [onCodePoint] 被消费掉；
     * 万一哪条路径漏过了，`TerminalSession.write` 也会在 `mShellPid <= 0` 时把它丢掉
     * —— 敲出来的东西到不了 shell，只会离开这一屏。
     */
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        if (session in finishedScripts) {
            exitBecauseScriptDone()
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        if (currentFinished()) {
            exitBecauseScriptDone()
            return true
        }
        return false
    }

    /** 长按=进入选择模式（复制输出用），结束态下同样保留，不在这里退出 */
    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = ctrlDown

    override fun readAltKey(): Boolean = altDown

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    /** 字符级的输入（组合键 / 部分输入法走这条）—— 结束态下一样算「输入」 */
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        if (session in finishedScripts) {
            exitBecauseScriptDone()
            return true
        }
        return false
    }

    override fun onEmulatorSet() {
        terminalView.updateSize()
    }

    // ---------------------------------------------- TerminalSessionClient 实现

    override fun onTextChanged(changedSession: TerminalSession) {
        if (changedSession == currentSession()) {
            terminalView.onScreenUpdated()
        }
    }

    /**
     * 标题变了 —— 这里同时是**脚本结束信号**的接收点（3.13.1）。
     *
     * 命令尾部会往标题里写一条 `OSC 2`（见 [ScriptDone]）：屏幕上不留任何字符，
     * 终端模拟器只会回调到这里一次，退出码就夹在里面。之所以借标题而不是去读屏幕
     * 文本，是为了不受滚屏、折行、清屏影响，也不会与脚本自己打印的内容撞车。
     *
     * 判据是**整条匹配**（`ScriptDone.isMarker`），所以脚本哪怕真打印出标记原文，
     * 也只会是「标题恰好等于这一条」这种不可能再巧合的情况。
     */
    override fun onTitleChanged(changedSession: TerminalSession) {
        // 已经收尾的会话不再改标签名：它的标题里可能还留着刚才那条标记
        if (changedSession in finishedScripts) return

        val title = changedSession.title
        if (changedSession === scriptSession && title != null && ScriptDone.isMarker(title)) {
            // 标记是写给 App 看的，不该变成标签名
            markScriptFinished(changedSession, ScriptDone.parseExitCode(title))
            return
        }

        val index = sessions.indexOf(changedSession)
        if (index >= 0) {
            val label = title?.takeIf { it.isNotBlank() } ?: "t${index + 1}"
            sessionNames[changedSession] = label
            tabLayout.getTabAt(index)?.setText(label)
        }
    }

    /**
     * 会话进程退出。
     *
     * 普通会话照旧：toast 一句、关掉这个标签、若是最后一个就回到文件管理。
     *
     * **脚本会话例外**：它结束时要停在屏幕上，而不是被关掉 —— 这正是 3.9.2 那套
     * 「脚本结束 = 终端关闭」被反过来做的部分。正常路径是 App 收到结束标记后
     * 自己收的尾（[markScriptFinished]），而这里兜住没有标记的那种：脚本把 shell
     * 一起带走了（`exec` 换了进程、被 OOM 杀掉、用户中途敲了 `exit`）。
     * 两条路都停在结束态，等用户按键再走。
     */
    override fun onSessionFinished(finishedSession: TerminalSession) {
        if (!sessions.contains(finishedSession)) return

        if (finishedSession === scriptSession) {
            // 没有退出码可报：命令尾那个 printf 根本没轮到执行
            markScriptFinished(finishedSession, ScriptDone.NO_CODE)
            return
        }

        toast("会话已退出")
        // shell 退出（例如输入 exit）后自动关闭对应标签；
        // 若这是最后一个标签，则退出终端界面回到文件管理
        removeSession(finishedSession, closeActivityWhenEmpty = true)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        toast("已复制")
    }

    /**
     * 粘贴（长按选中后的浮动工具栏里那个「粘贴」会走到这里）。
     *
     * 结束态下与按键同等对待：这一屏已经不吃输入了，粘贴也是一次「输入」。
     * 注意**复制**不受影响 —— 它在 `TextSelectionCursorController` 里直接读
     * 终端缓冲区，不经过会话，所以输出仍然可以选中抄走。
     */
    override fun onPasteTextFromClipboard(session: TerminalSession) {
        if (session in finishedScripts) {
            exitBecauseScriptDone()
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (!text.isNullOrEmpty()) {
            session.write(text)
        }
    }

    override fun onBell(session: TerminalSession) {}

    override fun onColorsChanged(session: TerminalSession) {}

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun getTerminalCursorStyle(): Int = 0

    // ------------------------------------------------------------------ 日志

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "stackTrace", e)
    }

    companion object {
        /**
         * 「内置工具箱未启用」的提示是否已经弹过。
         *
         * 放在 companion 上而不是实例字段：用户会反复开关终端标签，
         * 每次新建实例都弹一次就变成骚扰了；而失效原因在同一个进程里不会变。
         */
        private var toolboxNoticeShown = false

        /** 终端的初始工作目录 */
        const val EXTRA_WORK_DIR = "work_dir"

        /** 要执行的脚本路径 */
        const val EXTRA_SCRIPT_PATH = "script_path"

        /** 脚本显示名，用作标签标题 */
        const val EXTRA_SCRIPT_NAME = "script_name"

        /** 已构造好的执行命令（由 ScriptRunner 生成） */
        const val EXTRA_SCRIPT_COMMAND = "script_command"

        /** 解释器回退提示，非空时会在执行前打印一行说明 */
        const val EXTRA_SCRIPT_NOTICE = "script_notice"

        /**
         * 是否以 ROOT 身份打开会话。
         *
         * 只有文件管理器的「以 ROOT 执行」会带上它 —— 它不是权限开关，
         * 而是用户对**这一个脚本**的显式选择。
         */
        const val EXTRA_RUN_AS_ROOT = "run_as_root"

        /**
         * 脚本结束提示条的颜色（终端这一屏是全手写的深色配色，见 activity_terminal.xml）。
         *
         * 正常退出取顶部身份标签那支绿、非 0 取工具栏「退出」那支红 ——
         * 同一屏里不引入新的颜色。
         */
        private val COLOR_OK = 0xFF7EE787.toInt()
        private val COLOR_FAIL = 0xFFF85149.toInt()

        private const val TAG = "TerminalActivity"
        private const val PREFS = "terminal_prefs"
        private const val TRANSCRIPT_ROWS = 2000

        /** 终端固定使用的 shell（普通用户身份 / 应用自身 uid；root 会话里用 su 提权） */
        private const val SHELL_PATH = RxinEnv.SHELL_PATH

        /** 提权入口 */
        private const val SU_PATH = RxinEnv.SU_PATH

        /** 等待会话就绪的最大重试次数（每次间隔 50ms，合计约 5 秒） */
        private const val READY_MAX_RETRY = 100
    }
}
