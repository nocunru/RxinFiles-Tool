package com.rxinfiles.tool

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * RXin 内置工具集。
 *
 * Android 自带的 `/system/bin` 是 toybox，命令缺得厉害而且各 ROM 差异极大
 * （`awk` 根本没有，`sed`/`find`/`xargs`/`tar` 常年是阉割版）。脚本里只要用了
 * 这些，换一台机器就报 `command not found` 或者行为不一致。
 *
 * 所以随包内置一份 busybox，把缺的命令补齐。
 *
 * ## 二进制为什么必须是 Bionic 而不是 musl
 *
 * 最省事的做法是随便抓一份静态 busybox（比如 Alpine 的 `busybox-static`）塞进来。
 * 那条路在本工程试过，**失败**：它是 musl 静态链接的，而 musl 的启动代码
 * （`__init_tp`）会调 `set_robust_list`，Android app 沙盒的 seccomp 把该调用
 * **TRAP 成 SIGSYS**，进程直接在 `exec` 后被杀（真机 strace 可复现：
 * `si_code=SYS_SECCOMP, si_syscall=__NR_set_robust_list`，`killed by SIGSYS`）。
 * 表现就是「二进制存在、权限也对，一执行就死」。
 *
 * 因此这里用的是**用 NDK 交叉编译、链接 Bionic 的 busybox** —— 与 Android
 * 自身、与 App 自己的 native 代码同一条 libc 路径，不碰这个坑。
 * 构建脚本见 `tools/rxinbox/build-busybox.py`。
 *
 * ## 二进制为什么放在 nativeLibraryDir
 *
 * Android 10 起 `untrusted_app` 对 `app_data_file` 没有 `execute_no_trans`
 * （AOSP `app_neverallows.te` 里是 neverallow），所以「复制到 filesDir 再 exec」
 * 会被拒。能执行的位置只有 `nativeLibraryDir`（`/data/app/…/lib/arm64/`，
 * SELinux 类型 `apk_data_file`，系统自己就从这里加载 native 库）。
 * 这也是 `AndroidManifest` 里 `extractNativeLibs` 必须是 `true` 的原因 ——
 * 为 `false` 时系统从 APK 内 mmap 加载，磁盘上没有实体文件，没法 exec。
 *
 * ## 垫片为什么是符号链接，而不是脚本
 *
 * 这一点是**踩过坑才定的**：一开始把垫片写成 `#!/system/bin/sh` 的小脚本放进
 * 沙箱 `bin/`，靠 PATH 调用。它必然失败 —— 因为「执行一个脚本」同样要
 * `app_data_file:file execute_no_trans`，也就是被 neverallow 挡掉的那个权限
 * （校验发生在 execve 的入口，而不是解释器那一步）。而且失败形态很隐蔽：
 * POSIX 规定 shell 在 execve 收到 EACCES 时要**继续搜下一个 PATH 目录**，
 * 于是最终报的是 `xxx: inaccessible or not found`，看起来像「没装」，
 * 完全指不出是权限问题。
 *
 * 符号链接则走内核的对**最终 inode** 判定：链接解析到 `apk_data_file`
 * 就按 `apk_data_file` 放行，链接本身不需要任何执行权限。
 * 所以现在 `bin/` 与 `extra/` 里**全部是符号链接**，没有一个脚本。
 *
 * ## 为什么连「探测」也必须经符号链接（3.10.2 的教训）
 *
 * busybox 是**按 `argv[0]` 的 basename 决定跑哪个 applet** 的多调用二进制，
 * 而它在 APK 里必须叫 `libbusybox.so`（PackageManager 只解压 `lib*.so`）。
 * 于是 `exec("…/nativeLibraryDir/libbusybox.so")` 的 basename 是
 * `libbusybox.so`，busybox 查不到这个 applet，直接报
 * `libbusybox.so: applet not found` 并以 **127** 退出。
 *
 * 3.10.1 就是在这里翻了车：`probeExec` 拿 `.so` 实体路径去探测，于是
 * **exec 通道明明已经打通**（busybox 真的跑起来并打印了那句话，说明 SELinux 放行、
 * seccomp 也没杀它），却被自己的探测方式判成「不可执行」，整个工具箱直接关闭。
 *
 * 所以现在的铁律是：**凡是要 exec busybox 的地方，一律用 `bin/busybox` 这个链接**，
 * 绝不直接用 `.so` 路径 —— 探测、`--list`、取版本号，全都一样。
 * 顺带一个好处：探测走的路径与用户实际敲命令走的路径完全一致，
 * 「探测通过」才真正等价于「命令可用」。
 *
 * ## 目录布局
 *
 * ```
 * RXin/
 *   bin/     覆盖清单 + busybox 本体名 + bash（PATH 前置）
 *   extra/   其余 applet（PATH 后置，只补缺）
 *   tmp/     TMPDIR —— Android 没有 /tmp
 *   etc/profile.sh   交互式 shell 的兼容层（由 ENV 加载）
 * ```
 *
 * ## 降级
 *
 * 找不到 busybox、或它执行不起来（SELinux / seccomp 因 ROM 而异），就
 * **什么都不做**：不生成链接、不碰 PATH，一切照旧走系统命令，并把原因写进
 * [status]（终端里有 `RXIN_STATUS`，界面会弹提示）。
 * 「补不上」可以接受，「弄坏了原有能力」不行。
 */
object RxinToolbox {

    private const val TAG = "RxinToolbox"

    /** busybox 在 APK 里的文件名（`jniLibs/arm64-v8a/` 下）。改名要同步改构建脚本 */
    private const val BUSYBOX_SONAME = "libbusybox.so"

    /** PATH 前置目录（覆盖清单 + 本体名） */
    const val BIN_DIR = "bin"

    /** PATH 后置目录（只补缺，不遮蔽系统命令） */
    const val EXTRA_DIR = "extra"

    const val TMP_DIR = "tmp"
    const val ETC_DIR = "etc"

    /** 交互式 shell 的兼容层文件名，由终端 ENV 加载 */
    const val PROFILE_NAME = "profile.sh"

    /**
     * 生成状态戳。
     *
     * 末尾的数字是**垫片格式版本**：从「脚本垫片」换成「符号链接」时把它从 1 提到 2，
     * 这样老版本铺下的那批脚本必定被重建成链接 —— 否则旧脚本会一直躺在沙箱里，
     * 表现成「升级了但还是不行」。
     *
     * 判据是「**磁盘上的链接内容会不会因此过时**」：只要目标（busybox 实体路径）
     * 与名字集合没变，就不用提。3.10.2 修的是「用哪个路径去 exec」，链接本身
     * 一个字节都没动，所以**不需要**再提一级。
     */
    private const val STAMP_VERSION = 2
    private const val STAMP_NAME = ".toolbox.stamp"

    /** 系统 shell（mksh）。shell 类名字的链接直接指到它 */
    private const val SH_PATH = "/system/bin/sh"

    /** 探测超时 */
    private const val PROBE_TIMEOUT_S = 8L

    /**
     * 覆盖清单：**故意遮蔽系统同名命令**的极少数几个。
     *
     * 只放「Android 自带版本明显残缺、而 sh 脚本又高频依赖」的。
     * 其余全部走 [EXTRA_DIR] 后置补缺 —— 系统没有的照样能命中，
     * 系统有的则保持原样，避免无谓地改变行为。
     *
     * 刻意**不**包含 `ls`/`cp`/`mv`/`rm`/`ps`/`df`：这些 toybox 版本带着
     * Android 自己的语义（SELinux 上下文、`-Z`、`-A` 之类），换掉是倒退。
     */
    val OVERRIDE = listOf(
        "sed", "grep", "egrep", "fgrep", "find", "xargs", "tar", "date", "diff", "cmp"
    )

    /**
     * 一律不生成链接的 applet。
     *
     * 判据是「接管它会不会让事情变得更糟」：
     *  - 需要 root 才有意义（`su` `mount` `chroot` `init` …）—— 接管只会掩盖
     *    真实权限问题，让脚本以为自己能挂载，实际失败点飘到更远的地方；
     *  - 会改动系统状态（`insmod` `sysctl` `reboot` `crond` …）；
     *  - Android 自带版本反而更贴合本机语义（`ps` `df` `du` `ls` `cp` `mv` `rm`
     *    `chmod` `stat` `ip` `ifconfig` `readlink` …）；
     *  - `sh` —— 系统那个是 mksh，兼容性比 busybox ash 好，绝不能换。
     */
    private val BLOCKED: Set<String> = setOf(
        // 提权
        "su", "setpriv", "login", "nologin", "chroot", "unshare", "nsenter",
        // 挂载 / 分区 / 文件系统
        "mount", "umount", "switch_root", "pivot_root", "fsck", "mkfs.vfat",
        "mkdosfs", "mkswap", "swapon", "swapoff", "losetup", "blkdiscard",
        "fdisk", "blockdev", "blkid", "findfs", "nbd-client", "mdev",
        // 账号与密码
        "passwd", "adduser", "addgroup", "deluser", "delgroup", "chpasswd",
        "cryptpw", "mkpasswd", "vlock", "add-shell", "remove-shell",
        // 内核模块 / 系统服务 / 时钟
        "insmod", "rmmod", "modprobe", "depmod", "modinfo", "init", "reboot",
        "halt", "poweroff", "sysctl", "klogd", "syslogd", "crond", "crontab",
        "watchdog", "rdate", "hwclock", "adjtimex", "setconsole", "setfont",
        // 网络配置（Android 的 ip / ifconfig 是 toybox 定制版，更贴合本机）
        "ifconfig", "ifup", "ifdown", "route", "arp", "arping", "ip", "ipaddr",
        "iplink", "iproute", "iprule", "iptunnel", "ipneigh", "vconfig",
        "tunctl", "ifenslave", "nameif", "brctl", "udhcpc", "udhcpc6",
        // 系统状态（toybox 的输出格式才是 Android 用户习惯的）
        "ps", "top", "free", "df", "du", "uptime", "dmesg", "lsof", "lsusb",
        "iostat", "mpstat", "pmap", "pstree", "fuser", "killall5", "lsmod",
        // 控制台 / 键盘 / 设备
        "chvt", "deallocvt", "openvt", "setkeycodes", "setlogcons", "dumpkmap",
        "loadkmap", "loadfont", "showkey", "kbd_mode", "fbset", "fbsplash",
        "fdflush", "setserial", "microcom", "readahead", "fwconsole",
        // Android 语义更合适的文件命令 —— 绝不覆盖
        "ls", "cp", "mv", "rm", "mkdir", "rmdir", "ln", "chmod", "chown",
        "chgrp", "touch", "stat", "readlink", "realpath", "cat", "sh"
    )

    /**
     * busybox 本体要占的名字。
     *
     * 必须**显式生成**：`busybox --list` 列的是 applet，不含它自己，
     * 所以「按 applet 清单建链接」永远建不出 `busybox` 这个命令 ——
     * 而它恰恰是排查问题时第一个要敲的（`busybox | head -1`）。
     * 少了它，正常的工具箱也会看起来像没装。
     */
    private val SELF_NAMES = listOf("busybox")

    /**
     * `busybox --list` 拿不到时的兜底清单。
     *
     * 真机上首轮会实际问一次 busybox（这样换版本能自动同步），
     * 但万一那次 exec 出意外，至少别让功能整个消失 —— 这里放的是
     * sh 脚本里出现频率最高的一批。
     *
     * 内容按**本工程实际编出来的那个 busybox**（`tools/rxinbox/applets.txt`，
     * 253 个 applet）逐条核对过，只列真存在的名字。写进一个并不存在的名字，
     * 只会生成一个必然报 `applet not found` 的链接，那比没有还糟 ——
     * 它把失败点推到一个看起来像「内置工具箱坏了」的地方。
     *
     * 核对时已剔除：`join`、`pr`（busybox 1.36.1 里**没有**这两个 applet）、
     * `unlzop`、`lzopcat`（defconfig 默认就不编）、`who`（已按语义拉黑，
     * 系统 toybox 的版本更贴合本机）。
     */
    private val FALLBACK_APPLETS = listOf(
        "awk", "sed", "grep", "egrep", "fgrep", "find", "xargs", "sort", "uniq",
        "cut", "tr", "head", "tail", "wc", "nl", "tac", "rev", "fold", "expand",
        "unexpand", "paste", "comm", "split", "seq", "shuf",
        "base64", "md5sum", "sha1sum", "sha256sum", "sha512sum", "cksum", "sum",
        "tar", "gzip", "gunzip", "zcat", "bzip2", "bunzip2", "bzcat",
        "unzip", "lzma", "unlzma", "lzcat", "xzcat",
        "diff", "cmp", "expr", "test", "printf", "echo", "env",
        "printenv", "tee", "yes", "true", "false", "timeout", "time", "sleep",
        "usleep", "which", "basename", "dirname", "mktemp",
        "install", "chattr", "lsattr", "truncate", "shred", "mkfifo", "unlink",
        "link", "hexdump", "od", "xxd", "strings", "less", "more", "vi",
        "wget", "nc", "nslookup", "hostname", "whois", "traceroute",
        "netstat", "ping", "pgrep", "pkill", "killall", "pidof", "nohup",
        "setsid", "flock", "watch", "tty", "reset", "clear",
        "cal", "bc", "dc", "factor", "tree", "getopt", "stty", "id", "groups",
        "whoami", "uname", "nproc", "arch", "pwd", "run-parts",
        "dos2unix", "unix2dos", "uuencode", "uudecode", "base32", "ash"
    )

    /**
     * 需要额外生成的 shell 名字链接（不是 busybox applet，但脚本会直接调）。
     *
     * 链接目标是 `/system/bin/sh`（mksh），不是 busybox 的 ash ——
     * mksh 支持 `[[ ]]`、数组、`$'...'`、`<<<`、`${var/a/b}`、`+=`，
     * 比 busybox ash 更接近 bash，往下降级反而更容易跑挂脚本。
     *
     * `bash` 放前置目录：Android 根本没有它，不会遮蔽任何东西，
     * 而 `bash script.sh` / `command -v bash` 太常见，值得保证一定找到。
     * 注意 `#!/bin/bash` 这种**写死绝对路径**的仍然救不了（`/` 是只读 rootfs）。
     */
    private val SHELL_NAMES_IN_BIN = listOf("bash")
    private val SHELL_NAMES_IN_EXTRA = listOf("dash", "zsh", "ksh", "csh", "tcsh")

    /** 探测结果：busybox 的绝对路径；null 表示不可用（功能整体关闭） */
    @Volatile
    private var busyboxPath: String? = null

    /**
     * 沙箱的 PATH 前缀，形如 `<bin>:<extra>`（不含尾部分隔符）。
     *
     * 给 [ScriptRunner] 用：探测解释器是否存在时必须带上同一套 PATH，
     * 否则会出现「终端里能跑、探测说没有」的错位。
     */
    @Volatile
    var pathPrefix: String? = null
        private set

    /**
     * 人类可读的状态，注入终端环境变量 `RXIN_STATUS`。
     *
     * 存在的理由很实际：这套东西的失败原因**全在进程外面**（SELinux、seccomp、
     * 装机时有没有解压 native lib），App 内部再怎么容错也看不见。
     * 把它变成一行能 `echo` 出来的文本，排查就不用靠猜。
     */
    @Volatile
    var status: String = "未初始化"
        private set

    @Volatile
    private var ready = false

    /** busybox 是否可用（不可用时一切内置功能关闭，回退系统命令） */
    val available: Boolean get() = busyboxPath != null

    /** busybox 绝对路径；不可用时为 null */
    val busybox: String? get() = busyboxPath

    fun binDir(context: Context): String = RxinSandbox.home(context) + "/" + BIN_DIR

    fun extraDir(context: Context): String = RxinSandbox.home(context) + "/" + EXTRA_DIR

    fun tmpDir(context: Context): String = RxinSandbox.home(context) + "/" + TMP_DIR

    fun profilePath(context: Context): String =
        RxinSandbox.home(context) + "/" + ETC_DIR + "/" + PROFILE_NAME

    /** 终端 PATH 里要拼进去的那两段（前置段、后置段） */
    fun pathSegments(context: Context): Pair<String, String> =
        binDir(context) to extraDir(context)

    // ------------------------------------------------------------------ 装载

    /**
     * 铺开内置工具箱。
     *
     * 幂等：状态戳一致时只做一次比对。内部会做 exec 探测（几十毫秒），
     * 因此**首次**调用建议放在 IO 线程；后续调用因为 [ready] 短路，
     * 在主线程调用也无所谓。
     */
    fun ensure(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            try {
                doEnsure(context)
            } catch (t: Throwable) {
                Log.w(TAG, "ensure failed, built-in toolbox disabled", t)
                busyboxPath = null
                pathPrefix = null
                status = "未启用：初始化异常 ${t.javaClass.simpleName}"
            }
            ready = true
            Log.i(TAG, "toolbox status: $status")
        }
    }

    private fun doEnsure(context: Context) {
        val home = RxinSandbox.home(context)
        val etcDir = File(home, ETC_DIR)
        val tmpDir = File(home, TMP_DIR)
        val binDir = File(home, BIN_DIR)
        val extraDir = File(home, EXTRA_DIR)
        listOf(etcDir, tmpDir, binDir, extraDir).forEach { it.mkdirs() }

        // busybox 在沙箱里的「命令名」。**所有 exec 一律经它走**，原因见下面那段注释。
        val selfLink = File(binDir, SELF_NAMES.first())

        // profile 与「工具箱是否可用」无关，**先写**：
        //  - 它要 source 原版 .shinit（内置命令全靠它），不能让工具链的成败牵动；
        //  - 工具箱没起来时，它负责把原因打印出来（失效原因全在进程外面，看不到就只能猜）。
        // RXINBOX 刻意指向**符号链接**而不是那个 .so：指向 .so 的话
        // `$RXINBOX --list` 这种用法会直接报 applet not found（见下面的说明）。
        val shinit = RxinSandbox.shinitPath(context)
        writeIfChanged(
            File(etcDir, PROFILE_NAME),
            profileScript(home, selfLink.absolutePath, shinit)
        )

        val bb = locateBusybox(context)
        if (bb == null) {
            status = "未启用：APK 里没有找到 busybox（nativeLibraryDir=" +
                "${context.applicationInfo.nativeLibraryDir}）"
            Log.w(TAG, status)
            return
        }

        // ---- 关键一步：先建「本体名」符号链接，之后一切 exec 都走它 ----
        //
        // busybox 是**按 argv[0] 的 basename 决定跑哪个 applet** 的多调用二进制。
        // 而它在 APK 里的文件名必须叫 libbusybox.so —— PackageManager 只解压
        // `lib*.so`，改成别的名字就根本不会出现在 nativeLibraryDir 里。
        // 于是直接 exec 那个路径时 basename 是 `libbusybox.so`，busybox 查不到这个
        // applet，报 `libbusybox.so: applet not found` 并以 127 退出。
        //
        // 3.10.1 就是这么栽的 —— 而且它的失败信息恰好证明了一件事：
        // **exec 通道本身完全通**（SELinux 放行了 apk_data_file、seccomp 也没杀它，
        // busybox 真的跑起来并打印了那句错误），只是名字不对。
        // 符号链接把 basename 变成 `busybox` / applet 名，问题就消失了；
        // 而这个链接同时也是我们要给用户的那批垫片之一，不是额外的负担。
        if (!symlink(bb, selfLink)) {
            status = "未启用：无法在沙箱里创建符号链接（$binDir）"
            Log.w(TAG, status)
            return
        }
        val exe = selfLink.absolutePath

        val failure = probeExec(exe)
        if (failure != null) {
            // SELinux / seccomp 因 ROM 而异，这里只能整体放弃 ——
            // 绝不能拿一个跑不起来的东西去占 PATH。
            // 只回滚**我们自己刚建的那一个链接**：bin/ 里还躺着原版那 8 个内置命令
            // （engine/fortune/rxe/…），那是绝不能碰的。
            runCatching { selfLink.delete() }
            status = "未启用：busybox 无法执行（$failure）"
            Log.w(TAG, status)
            return
        }

        val applets = listApplets(exe)
        if (applets == null) Log.w(TAG, "busybox --list failed, falling back to built-in list")
        val useApplets = applets ?: FALLBACK_APPLETS

        // ---- 状态戳：内容一致就跳过整轮重建 ----
        val stampFile = File(home, STAMP_NAME)
        val stamp = buildString {
            append("v").append(STAMP_VERSION).append('\n')
            append(bb).append('\n')
            append(useApplets.size).append('\n')
            append(OVERRIDE.joinToString(",")).append('\n')
        }
        val stampSame = runCatching { stampFile.readText() }.getOrNull() == stamp &&
            linksIntactIn(binDir, plannedBinNames(useApplets), bb) &&
            linksIntactExclusive(extraDir, bb)

        if (!stampSame) {
            val links = rebuild(binDir, extraDir, bb, useApplets)
            // 建链接失败说明这个 ROM 的文件系统不接受 symlink —— 那样功能是残的，
            // 与其留一堆空壳占着 PATH，不如整个撤掉
            if (links < 0) {
                status = "未启用：无法在沙箱里创建符号链接"
                Log.w(TAG, status)
                clearManaged(extraDir)
                clearBinManaged(binDir)
                return
            }
            runCatching { stampFile.writeText(stamp) }
            Log.i(TAG, "toolbox ready: $links links, applets=${useApplets.size}")
        }

        // 重建之后**再确认一次本体链接还在**。它是唯一能用的 exec 入口
        // （单条命令挂掉只是少一个命令，它挂掉是整个工具箱变成空壳，
        // 而 PATH 已经指过来了 —— 那种状态比「没装」更难排查）。
        if (!Files.isSymbolicLink(selfLink.toPath())) {
            status = "未启用：符号链接被清理掉了（$exe）"
            Log.w(TAG, status)
            clearManaged(extraDir)
            clearBinManaged(binDir)
            return
        }

        busyboxPath = exe
        pathPrefix = binDir.absolutePath + ":" + extraDir.absolutePath
        status = buildString {
            append("已就绪：")
            append(applets?.size ?: useApplets.size).append(" 个命令")
            busyboxVersion(exe)?.let { append("（").append(it).append("）") }
        }
    }

    // ------------------------------------------------------------------ 链接

    /**
     * 重建两个目录里的链接。
     *
     * @return 建成的链接数；**返回 -1 表示出现了硬失败**（symlink 建不出来）
     */
    private fun rebuild(
        binDir: File, extraDir: File, bb: String, applets: List<String>
    ): Int {
        // applet 清单可能变了（换了 busybox 版本），整目录重建最省心。
        // extra/ 整个清空 —— 里面只有我们放的东西
        clearManaged(extraDir)

        // 前置目录里只清「我们生成的」那批
        clearBinManaged(binDir)

        val appletSet = applets.toHashSet()
        var ok = 0
        var failed = 0

        fun put(dir: File, name: String, target: String) {
            if (!SAFE_NAME.matches(name)) return
            if (symlink(target, File(dir, name))) ok++ else failed++
        }

        for (name in plannedBinNames(appletSet)) {
            put(binDir, name, if (name in SHELL_NAMES_IN_BIN) SH_PATH else bb)
        }

        applets.asSequence()
            .filter { it !in BLOCKED }
            .filter { it !in OVERRIDE }
            .forEach { put(extraDir, it, bb) }
        for (name in SHELL_NAMES_IN_EXTRA) put(extraDir, name, SH_PATH)

        if (failed > 0) Log.w(TAG, "symlink failed: $failed, ok: $ok")
        // 个别失败（比如重名目录）可以忍；全军覆没说明这条路走不通
        if (ok == 0) return -1
        return ok
    }

    /**
     * `bin/` 里该由我们占的名字。
     *
     * 抽出来是为了让「建」与「查」用同一份定义 —— 两边各写一遍迟早会漂移，
     * 那样要么漏查（链接坏了不自知），要么误查（把原版文件名也算进来）。
     */
    private fun plannedBinNames(applets: Collection<String>): List<String> =
        OVERRIDE.filter { it in applets } + SELF_NAMES + SHELL_NAMES_IN_BIN

    /** 只清我们生成的东西：普通文件与符号链接，目录留着（用户可能自己建了同名目录） */
    private fun clearManaged(dir: File) {
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory && !Files.isSymbolicLink(f.toPath())) return@forEach
            runCatching { f.delete() }
        }
    }

    /**
     * 清理 `bin/` —— **只清我们摆进去的那几个名字**。
     *
     * 与 [clearManaged] 的区别很关键：`bin/` 不是我们的私有目录，原版那 8 个内置命令
     * （engine/fortune/hello/nyan/rr/rxe/snow/Xie）和用户自己丢进去的东西都住在这里。
     * 用「清空整个目录」的写法一旦碰上「symlink 建不出来 → 整体回滚」的分支，
     * 就会把原版命令一起删掉，那正是本模块最不能干的事（补不上可以，弄坏原有能力不行）。
     */
    private fun clearBinManaged(binDir: File) {
        (OVERRIDE + SELF_NAMES + SHELL_NAMES_IN_BIN).forEach { name ->
            runCatching { File(binDir, name).delete() }
        }
    }

    /**
     * `extra/` 的完整性检查：**这是我的私有目录**，所以可以要求
     * 「里面每一个条目都是我们铺的、且目标正确」。
     *
     * 目标要一起比是因为 APK 更新后 `nativeLibraryDir` 里的哈希会变 ——
     * 老链接会变成指向不存在路径的悬空链接，命令直接失效。
     */
    private fun linksIntactExclusive(dir: File, bb: String): Boolean {
        val files = dir.listFiles() ?: return false
        if (files.isEmpty()) return false
        for (f in files) {
            if (!Files.isSymbolicLink(f.toPath())) return false
            val target = runCatching { Os.readlink(f.absolutePath) }.getOrNull() ?: return false
            if (target != bb && target != SH_PATH) return false
        }
        return true
    }

    /**
     * `bin/` 的完整性检查：**只查我们计划内的那批名字**。
     *
     * 这里不能像 [linksIntactExclusive] 那样要求「全部都是符号链接」——
     * `bin/` 是共享目录，原版那 8 个内置命令（普通文件）就住在里面，
     * 全量遍历会永远返回 false，于是状态戳形同虚设：每次启动都白重建一遍
     * 两百多个链接。这个 bug 在 3.10.2 之前一直存在。
     */
    private fun linksIntactIn(dir: File, names: Collection<String>, bb: String): Boolean {
        if (names.isEmpty()) return false
        for (name in names) {
            val f = File(dir, name)
            if (!Files.isSymbolicLink(f.toPath())) return false
            val target = runCatching { Os.readlink(f.absolutePath) }.getOrNull() ?: return false
            if (target != bb && target != SH_PATH) return false
        }
        return true
    }

    /** 建符号链接。已存在的同名项先删掉（悬空链接也能删） */
    private fun symlink(target: String, link: File): Boolean = try {
        link.parentFile?.mkdirs()
        if (!link.delete() && link.exists()) {
            Log.w(TAG, "cannot replace ${link.absolutePath}")
            false
        } else {
            Os.symlink(target, link.absolutePath)
            true
        }
    } catch (t: Throwable) {
        Log.w(TAG, "symlink ${link.name} -> $target failed", t)
        false
    }

    // ------------------------------------------------------------------ 定位与探测

    /** 在所有可能的位置里找 busybox；找不到返回 null */
    private fun locateBusybox(context: Context): String? {
        val info = context.applicationInfo
        val candidates = ArrayList<File>(6)
        // 正常路径：extractNativeLibs=true 时 lib 会被解压到这里
        info.nativeLibraryDir?.let { candidates.add(File(it, BUSYBOX_SONAME)) }
        // 少数安装器 / 老式布局的兜底
        candidates.add(File("/data/app/${BuildConfig.APPLICATION_ID}-1/lib/arm64/$BUSYBOX_SONAME"))
        for (n in 2..3) {
            candidates.add(File("/data/app/${BuildConfig.APPLICATION_ID}-$n/lib/arm64/$BUSYBOX_SONAME"))
        }
        val found = candidates.firstOrNull { it.isFile && it.length() > 0 }
        if (found == null) Log.w(TAG, "no busybox under ${info.nativeLibraryDir}")
        return found?.absolutePath
    }

    /**
     * 真的执行一次，确认内核 + SELinux + seccomp 都放行。
     *
     * @param exe **必须是沙箱里的符号链接路径**（`bin/busybox`），不能是
     *   `nativeLibraryDir` 下的 `libbusybox.so` 实体路径 —— 后者会让
     *   `argv[0]` 的 basename 变成 `libbusybox.so`，busybox 直接报
     *   `applet not found` 退出 127，把「跑得起来」误判成「跑不起来」。
     *   3.10.1 的线上故障就是这个：exec 明明通了，却被自己的探测方式判了死刑。
     * @return null 表示成功；否则返回**给人看的失败原因**（含信号名）
     */
    private data class ProbeOutput(val text: String, val timedOut: Boolean)

    private fun readProbe(p: Process): ProbeOutput {
        val task = java.util.concurrent.FutureTask {
            p.inputStream.bufferedReader().use { it.readText() }
        }
        Thread(task, "RxToolbox-Probe").apply {
            isDaemon = true
            start()
        }
        val finished = try {
            p.waitFor(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            false
        }
        if (!finished) {
            p.destroy()
            if (p.isAlive) p.destroyForcibly()
        }
        val out = try {
            task.get(2, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            ""
        }
        return ProbeOutput(out, !finished)
    }

    private fun probeExec(exe: String): String? = try {
        val p = ProcessBuilder(exe, "true").redirectErrorStream(true).start()
        val result = readProbe(p)
        val out = result.text.trim()
        if (result.timedOut) {
            "执行超时（${PROBE_TIMEOUT_S}s 未返回）"
        } else if (p.exitValue() == 0) {
            null
        } else {
            describeExit(p.exitValue()) + "；" + shellProbe(exe) + out.takeIf { it.isNotEmpty() }
                ?.let { " [$it]" }.orEmpty()
        }
    } catch (t: Throwable) {
        "无法启动（${t.javaClass.simpleName}: ${t.message}）"
    }

    /** 经 `/system/bin/sh` 再跑一次，把退出码抓回来。 */
    private fun shellProbe(exe: String): String = try {
        val cmd = "\"$exe\" true 2>&1; echo \"[rc=$?]\""
        val p = ProcessBuilder(SH_PATH, "-c", cmd).redirectErrorStream(true).start()
        val result = readProbe(p)
        if (result.timedOut) {
            "执行超时（${PROBE_TIMEOUT_S}s 未返回）"
        } else {
            result.text.trim().lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
        }
    } catch (t: Throwable) {
        "sh -c 探测失败（${t.javaClass.simpleName}）"
    }

    /** 把退出码翻译成人话（信号名对 arm64 的 Linux 是对的） */
    private fun describeExit(code: Int): String = when {
        code == 127 -> "退出码 127（命令 / applet 未找到）"
        code == 126 -> "退出码 126（文件在，但不可执行）"
        code > 128 -> "被动终止：信号 ${code - 128}（${signalName(code - 128)}）"
        else -> "退出码 $code"
    }

    private fun signalName(sig: Int): String = when (sig) {
        4 -> "SIGILL"; 6 -> "SIGABRT"; 7 -> "SIGBUS"; 8 -> "SIGFPE"; 9 -> "SIGKILL"
        11 -> "SIGSEGV"; 13 -> "SIGPIPE"; 15 -> "SIGTERM"
        31 -> "SIGSYS(seccomp 拦下的系统调用)"
        else -> "SIG$sig"
    }

    /** 问 busybox 自己有哪些 applet；失败返回 null。参数语义同 [probeExec] */
    private fun listApplets(exe: String): List<String>? = try {
        val p = ProcessBuilder(exe, "--list").redirectErrorStream(true).start()
        val result = readProbe(p)
        if (result.timedOut) {
            null
        } else {
            result.text.split('\n', '\r')
                .map { it.trim() }
                .filter { it.isNotEmpty() && SAFE_NAME.matches(it) }
                .distinct()
                .takeIf { it.size > 20 }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "list applets failed", t)
        null
    }

    /** 取版本号用于状态显示（失败就不显示，不影响功能）。参数语义同 [probeExec] */
    private fun busyboxVersion(exe: String): String? = try {
        val p = ProcessBuilder(exe).redirectErrorStream(true).start()
        val result = readProbe(p)
        if (result.timedOut) null
        else Regex("BusyBox v([0-9][^ ,)]*)").find(result.text)?.groupValues?.get(1)
    } catch (t: Throwable) {
        null
    }

    // ------------------------------------------------------------------ profile

    /**
     * 交互式 shell 的兼容层。
     *
     * **只在交互式 shell 生效**：mksh 通过 ENV 加载它。
     * 执行脚本时（非交互）不会加载 —— 这是刻意的，往别人的脚本里塞环境变量
     * 和函数会改变它的语义，「补兼容」补出行为差异比不补更糟。
     *
     * 它同时承担一件事：**把原版内置命令接回来**（source `.shinit`，
     * 那里面是 `name() { command sh '<path>' "$@"; }` 形式的函数）。
     * 原版就是这么做的 —— 函数调用只需要读权限，绕开了「脚本不可执行」。
     */
    private fun profileScript(home: String, busybox: String, shinit: String): String =
        buildString {
            append("# RXin 终端环境（由 ENV 加载，仅交互式 shell）\n")
            append("# 由 RxinToolbox 生成，请勿手改 —— 下次启动会被覆盖\n")
            append('\n')
            append("RXIN_HOME=").append(quote(home)).append('\n')
            append("RXINBOX=").append(quote(busybox)).append('\n')
            append("export RXIN_HOME RXINBOX\n")
            append('\n')
            append("# 原版内置命令（rxe / hello / engine / snow / rr / nyan / fortune / Xie）\n")
            append("# 它们是脚本，靠这里的函数以 `sh <路径>` 形式调用 ——\n")
            append("# 直接 exec 沙箱里的脚本在 targetSdk>=29 下会被内核拒掉（W^X）。\n")
            append("[ -f ").append(quote(shinit)).append(" ] && . ")
                .append(quote(shinit)).append('\n')
            append('\n')
            append("# ---------------------------------------------------------------- bash 兼容\n")
            append("# Android 上没有 bash。mksh 已经覆盖了 [[ ]]、数组、$'...'、<<<、\n")
            append("# ${'$'}{var/a/b}、+= 这些常用扩展，缺的只是 bash 特有的几个内建命令。\n")
            append("#\n")
            append("# 这里**只**补「不补就 command not found」的那几个，而且尽量做最小可用\n")
            append("# 实现而不是空壳：空壳会让脚本静默走进错误的分支，比直接报错更难查。\n")
            append("# 明确不模拟的：关联数组（declare -A）、${'$'}{var^^}、** globstar ——\n")
            append("# mksh 没有对应机制，假装支持只会把错误推迟到更难定位的地方。\n")
            append("BASH_VERSION='0'; export BASH_VERSION\n")
            append('\n')
            append("shopt() { return 0; }\n")
            append("complete() { return 0; }\n")
            append("enable() { return 0; }\n")
            append("readarray() { return 0; }\n")
            append("mapfile() { return 0; }\n")
            append('\n')
            append("# declare name=value / declare name 是最常见的两种写法，按语义走；\n")
            append("# 带选项的形态（declare -A / -i / -x）无法在 mksh 上还原，安静忽略。\n")
            append("declare() {\n")
            append("  for __a in \"${'$'}@\"; do\n")
            append("    case \"${'$'}__a\" in\n")
            append("      -*) ;;\n")
            append("      *=*) eval \"${'$'}__a\" ;;\n")
            // 目标一行：eval "$__a=\"\${$__a:-}\""
            // 必须写 ${'$'} 而不是裸 $ —— 反斜杠后面紧跟 { 时 Kotlin 会把 ${
            // 当成模板表达式的开头，直接报 "Expecting an expression"。
            append("      *)   eval \"${'$'}__a=\\\"\\${'$'}{${'$'}__a:-}\\\"\" ;;\n")
            append("    esac\n")
            append("  done\n")
            append("}\n")
            append('\n')
            append("# 刻意**不**定义 source：\n")
            append("# mksh 自带 source，用不着补；而没带的 shell（busybox ash）把它当保留字，\n")
            append("# `source() { ... }` 会直接报 bad function name —— 那是**语法错误**，\n")
            append("# 会让整份 profile 加载失败，连原版内置命令一起丢掉。\n")
            append("# 少一个便利，远好过毁掉整份环境。（实测：busybox ash 上就是这样挂的）\n")
            append('\n')
            append("# 临时目录：Android 没有 /tmp，把它指到沙箱里\n")
            append("if [ -z \"${'$'}TMPDIR\" ]; then\n")
            append("  TMPDIR=").append(quote(home + "/" + TMP_DIR)).append("\n")
            append("  export TMPDIR\n")
            append("fi\n")
            append('\n')
            append("# 内置工具箱没起来时，在回滚区留一行原因 —— 失效原因全在进程外面\n")
            append("# （SELinux / seccomp / 装机时有没有解压 native lib），不写出来就只能靠猜。\n")
            append("if [ \"${'$'}{RXIN_TOOLBOX_OK:-0}\" != 1 ] && [ -n \"${'$'}{RXIN_STATUS:-}\" ]; then\n")
            append("  printf '\\n\\033[1;33m[RouXin]\\033[0m %s\\n' \"${'$'}RXIN_STATUS\"\n")
            append("  printf '\\033[2m         可执行 echo \\\\${'$'}RXIN_STATUS 复查\\033[0m\\n\\n'\n")
            append("fi\n")
        }

    // ------------------------------------------------------------------ 工具

    /** 内容一致就不写盘，避免每次启动刷新上百个文件的时间戳 */
    private fun writeIfChanged(file: File, content: String) {
        val same = file.isFile &&
            runCatching { file.readText(Charsets.UTF_8) }.getOrNull() == content
        if (same) return
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }

    /** POSIX 单引号转义（profile 里要写进 shell 的那些值） */
    private fun quote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

    /** applet 名只允许这些字符，防止奇怪的清单把沙箱搞乱 */
    private val SAFE_NAME = Regex("[A-Za-z0-9_.+-]+")
}
