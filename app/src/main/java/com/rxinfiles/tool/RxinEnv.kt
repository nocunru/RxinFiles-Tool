package com.rxinfiles.tool

import android.content.Context

/**
 * 终端与脚本执行**共用**的环境变量构造。
 *
 * 抽出来只为一个理由：**终端里敲命令的环境，和模块里跑脚本的环境，必须是同一份**。
 * 两处各写一遍迟早会漂移，而漂移的后果很难查 —— 表现成
 * 「终端里 `command -v x` 找得到、脚本里却 command not found」。
 * 这是 3.10.2 那条教训的推广：**探测路径必须与实际执行路径完全一致**。
 *
 * 环境变量的逐条说明见 [build] 的 KDoc。
 */
object RxinEnv {

    /** 本机 shell。Android 上 `/system/bin/sh` 是 **mksh** —— 不是 bash，也不是 toybox。 */
    const val SHELL_PATH = "/system/bin/sh"

    /** 提权命令名。各 ROM 安装位置不同（/system/xbin、/system/bin、/sbin…），只能走 PATH。 */
    const val SU_PATH = "su"

    /**
     * 系统 PATH，**显式写全**。
     *
     * 不继承应用进程的环境：应用进程的 PATH 只有 `/product/bin:/system/bin` 这种残缺值，
     * 缺了 `/apex/...` 会让 shell 里一大批命令找不到。
     */
    private const val SYSTEM_PATH =
        "/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin" +
            ":/system_ext/bin:/system/bin:/system/xbin:/sbin:/system/sbin" +
            ":/odm/bin:/vendor/bin:/vendor/xbin"

    /**
     * 构造执行环境。
     *
     * @param cwd 工作目录绝对路径。会同时写进 `HOME` 与 `PWD`。
     *
     * 几个刻意的选择：
     *
     * **1. `PWD` 写在这里，但真正生效的手段是「真的 chdir」。**
     * 实测：shell 启动时**一定**会用 `getcwd()` 覆盖 `PWD`，传 `PWD=/opt` 而进程 cwd
     * 在别处，脚本里 `$PWD` 拿到的仍是真实 cwd。所以 [ScriptPipe] 必须用
     * `ProcessBuilder.directory()` 真换目录；这里写一份是为了让 `env` / `printenv`
     * 这类**不经 shell** 的子进程也能看到正确的值。
     *
     * **2. `PATH` 分两段。** 内置工具箱可用时把 `RXin/bin` 前置（覆盖清单要抢在系统命令
     * 前面）、`RXin/extra` 后置（只补缺、不遮蔽）；不可用时退回原版行为（`bin` 后置）。
     *
     * **3. `TMPDIR` 与工具箱无关，始终给。** Android 没有 `/tmp`，而大量脚本往那儿写临时文件。
     *
     * **4. `RXIN_STATUS` 两种情况都给。** 工具箱没起来时它是唯一能说明原因的出口
     *    （失败原因全在进程外面 —— SELinux / seccomp / 装机时没解压 native lib）。
     *
     * **5. `ENV` 恒定指向 `profile.sh`，不随工具箱成败切换。** 它要 source 原版 `.shinit`
     *    把内置命令（rxe / hello / …）接回来。注意 `ENV` 只对**交互式** shell 生效 ——
     *    非交互的 `sh -c` 不会加载它，所以给脚本执行通道设它是有意为之的「无害」。
     */
    fun build(context: Context, cwd: String): Array<String> {
        val (shimBin, shimExtra) = RxinToolbox.pathSegments(context)
        val toolboxOn = RxinToolbox.available

        val path = if (toolboxOn) "$shimBin:$SYSTEM_PATH:$shimExtra" else "$SYSTEM_PATH:$shimBin"

        val env = mutableListOf(
            "TERM=xterm-256color",
            "LANG=en_US.UTF-8",
            "HOME=$cwd",
            "PWD=$cwd",
            "PATH=$path",
            "ANDROID_ROOT=/system",
            "ANDROID_DATA=/data",
            "ANDROID_STORAGE=/storage",
            "EXTERNAL_STORAGE=/sdcard",
            "SHELL=$SHELL_PATH"
        )

        val tmp = RxinSandbox.tmpDir(context)
        env += "TMPDIR=$tmp"
        env += "TMP=$tmp"
        env += "TEMP=$tmp"

        env += "RXIN_HOME=${RxinSandbox.home(context)}"
        env += "RXIN_TOOLBOX_OK=" + if (toolboxOn) "1" else "0"
        env += "RXIN_STATUS=${RxinToolbox.status}"
        RxinToolbox.busybox?.let { env += "RXINBOX=$it" }

        env += "ENV=${RxinToolbox.profilePath(context)}"

        return env.toTypedArray()
    }
}
