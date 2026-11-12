package com.rxinfiles.tool

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File

/**
 * RXin 内置沙箱 —— 即原版那个「小小的内置 PATH 脚本」。
 *
 * 原版会在应用私有目录下铺一份小沙箱：
 *
 * ```
 * <dataDir>/RXin/
 *   bin/     engine fortune hello nyan rr rxe snow Xie   <- 内置命令，带执行位
 *   lib/ etc/ share/                                     <- 占位目录
 *   .shinit                                              <- 函数定义，由终端 ENV 加载
 * ```
 *
 * 终端启动时把 `bin` 并入 PATH、把函数定义文件写进 ENV，于是
 * `rxe` / `hello` / `engine` / `snow` / `rr` / `nyan` / `fortune` 在终端里直接可用。
 *
 * `bin` 在 PATH 里的位置由 [TerminalActivity.buildEnv] 决定：
 *  - 内置工具箱（[RxinToolbox]）可用时它被**前置**（为了让覆盖清单抢到系统命令前面）；
 *  - 不可用时退回**后置**（维持原版行为，只补不遮蔽）。
 * 那批内置命令名与系统命令不冲突，两种顺序下都能正常命中。
 *
 * 两个关键约束：
 *  1. 沙箱必须落在**应用私有目录**（/data，真实 ext4），不能放 /sdcard。
 *     /storage/emulated/0 是 FUSE 挂载，权限位是虚拟的，`chmod +x` 不生效，
 *     命令无法被 PATH 直接调起。
 *  2. 因此终端必须运行在**应用自身 uid（普通用户模式）**下：
 *     shell 身份（uid 2000）读不到 /data/user/0/<pkg>，沙箱会整个失效。
 *
 * 脚本内容与原版逐字节一致。用 Base64 保存而不是字面量，是因为这些脚本里
 * 大量出现 `$COLS` / `${#TEXT}` / `$$` 这类写法，直接写进 Kotlin 字符串
 * 会被当成模板插值 —— 这同样也是原版选择 Base64 的原因。
 */
object RxinSandbox {

    private const val TAG = "RxinSandbox"

    /** 沙箱根目录名，与原版一致 */
    private const val DIR_NAME = "RXin"

    /** 函数定义文件名，与原版一致（终端 ENV 指向它） */
    const val SHINIT_NAME = ".shinit"

    /** 临时目录名。Android 没有 /tmp，脚本要写临时文件时得有地方落 */
    const val TMP_NAME = "tmp"

    /** 原版写死的沙箱根路径：.shinit 内的绝对路径以此为前缀，装载时按本次安装改写 */
    private const val LEGACY_HOME = "/data/data/com.Rouxin.ShellTool/RXin"

    /** bin/ 下的内置命令，终端里可直接调用 */
    val COMMANDS = listOf("engine", "fortune", "hello", "nyan", "rr", "rxe", "snow", "Xie")

    @Volatile
    private var ready = false

    /** 沙箱根目录（应用私有目录下，普通用户身份可读） */
    fun home(context: Context): String = File(context.dataDir, DIR_NAME).absolutePath

    /** 内置命令所在目录，需并入终端 PATH */
    fun binDir(context: Context): String = home(context) + "/bin"

    /** .shinit 绝对路径，需写入终端 ENV */
    fun shinitPath(context: Context): String = home(context) + "/" + SHINIT_NAME

    /**
     * 临时目录绝对路径，作为 TMPDIR 注入。
     *
     * Android 上 `/tmp` 并不存在（根文件系统只读，即便 root 也建不了），
     * 而大量脚本会往 `/tmp` 写中间文件。给一个真实可写的目录至少能让
     * 「用 $TMPDIR」的脚本跑通；写死 `/tmp` 的只能靠用户自己改。
     *
     * 这个目录由 [ensure] 创建，**不依赖内置工具箱是否可用**。
     */
    fun tmpDir(context: Context): String = home(context) + "/" + TMP_NAME

    /**
     * 铺开沙箱。
     *
     * 幂等：内容一致时只做一次比对、不重写。首次调用写 ~4KB 文件，
     * 之后基本是空转，可以在主线程直接调用。
     */
    fun ensure(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val home = home(context)
            try {
                // tmp 一并建好：TMPDIR 要指向它，且不应该因为「内置工具箱不可用」而消失
                listOf("lib", "etc", "share", TMP_NAME).forEach { File(home, it).mkdirs() }
                val bin = File(home, "bin").apply { mkdirs() }

                COMMANDS.forEachIndexed { index, name ->
                    writeIfChanged(File(bin, name), decode(B64[index]))
                }
                writeIfChanged(
                    File(home, SHINIT_NAME),
                    decode(B64_SHINIT).replace(LEGACY_HOME, home)
                )

                // 执行位只在真实文件系统上有意义。
                // 只给**原版那批脚本**加，不碰 [RxinToolbox] 铺的符号链接 ——
                // 那些链接的目标在只读的 nativeLibraryDir 里，chmod 只会白费（而且会跟读）
                COMMANDS.forEach { name ->
                    File(bin, name).takeIf { it.isFile }?.setExecutable(true, false)
                }

                Log.i(TAG, "sandbox ready: $home (${COMMANDS.size} commands)")
                ready = true
            } catch (t: Throwable) {
                ready = false
                Log.w(TAG, "create sandbox failed", t)
            }
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun decode(base64: String): String =
        String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8)

    /** 内容一致就不写盘，避免每次启动都刷新文件时间 */
    private fun writeIfChanged(file: File, content: String) {
        val same = file.isFile &&
            runCatching { file.readText(Charsets.UTF_8) }.getOrNull() == content
        if (same) return
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ 脚本
    // 以下 Base64 与原版 APK 中 com.Rouxin.ShellTool.RxinSandbox 的常量完全一致。

    private const val B64_ENGINE =
        "IyEvc3lzdGVtL2Jpbi9zaApwcmludGYgJ1xuJwpwcmludGYgJyAgXDAzM1sxOzM2bSstLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tK1wwMzNbMG1cbicKcHJpbnRmICcgIFwwMzNbMTszNm18ICAgICAgICAgUlhpbiBFbmdpbmUgU3RhdHVzIFJlcG9ydCAgICAgICB8XDAzM1swbVxuJwpwcmludGYgJyAgXDAzM1sxOzM2bSstLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tK1wwMzNbMG1cbicKcHJpbnRmICdcbicKcHJpbnRmICcgIFwwMzNbMTszMm0gIFtPS11cMDMzWzBtICBTYW5kYm94IGluaXRpYWxpemVkXG4nCnByaW50ZiAnICBcMDMzWzE7MzJtICBbT0tdXDAzM1swbSAgQmluYXJ5IG1vZHVsZXMgbG9hZGVkXG4nCnByaW50ZiAnICBcMDMzWzE7MzJtICBbT0tdXDAzM1swbSAgUEFUSCBpbnRlZ3JhdGlvbiBhY3RpdmVcbicKcHJpbnRmICcgIFwwMzNbMTszMm0gIFtPS11cMDMzWzBtICBSb290IHNoZWxsIHJlbGF5IG9ubGluZVxuJwpwcmludGYgJyAgXDAzM1sxOzMzbSAgWy0tXVwwMzNbMG0gIE5ldXJhbCBpbnRlcmZhY2U6IG9mZmxpbmVcbicKcHJpbnRmICcgIFwwMzNbMTszM20gIFstLV1cMDMzWzBtICBRdWFudHVtIHByb2Nlc3Nvcjogb2ZmbGluZVxuJwpwcmludGYgJ1xuJwpwcmludGYgJyAgXDAzM1sybSAgU3lzdGVtIHJlYWR5LlwwMzNbMG1cbicKcHJpbnRmICdcbicK"

    private const val B64_FORTUNE =
        "IyEvc3lzdGVtL2Jpbi9zaApjYXNlICQoKCAkJCAlIDYgKyAxICkpIGluCiAgMSkgcHJpbnRmICdcbiAgXDAzM1sxOzMzbSJEZWJ1Z2dpbmcgaXMgdHdpY2UgYXMgaGFyZCBhcyB3cml0aW5nIHRoZSBjb2RlLiJcMDMzWzBtXG4gIFwwMzNbMm0tIEJyaWFuIEtlcm5pZ2hhblwwMzNbMG1cblxuJyA7OwogIDIpIHByaW50ZiAnXG4gIFwwMzNbMTszM20iRmlyc3QsIHNvbHZlIHRoZSBwcm9ibGVtLiBUaGVuLCB3cml0ZSB0aGUgY29kZS4iXDAzM1swbVxuICBcMDMzWzJtLSBKb2huIEpvaG5zb25cMDMzWzBtXG5cbicgOzsKICAzKSBwcmludGYgJ1xuICBcMDMzWzE7MzNtIkl0IHdvcmtzIG9uIG15IG1hY2hpbmUuIlwwMzNbMG1cbiAgXDAzM1sybS0gRXZlcnkgRGV2ZWxvcGVyIEV2ZXJcMDMzWzBtXG5cbicgOzsKICA0KSBwcmludGYgJ1xuICBcMDMzWzE7MzNtIkNhY2hlIGludmFsaWRhdGlvbiBhbmQgbmFtaW5nIHRoaW5ncy4iXDAzM1swbVxuICBcMDMzWzJtLSBQaGlsIEthcmx0b25cMDMzWzBtXG5cbicgOzsKICA1KSBwcmludGYgJ1xuICBcMDMzWzE7MzVtInN1ZG8gISEiXDAzM1swbVxuICBcMDMzWzJtLSBUaGF0IG9uZSBmaXhcMDMzWzBtXG5cbicgOzsKICA2KSBwcmludGYgJ1xuICBcMDMzWzE7MzZtIlRlc3QgaW4gcHJvZHVjdGlvbiB0aGV5IHNhaWQuIlwwMzNbMG1cbiAgXDAzM1sybS0gRGV2T3BzXDAzM1swbVxuXG4nIDs7CmVzYWMK"

    private const val B64_HELLO =
        "IyEvc3lzdGVtL2Jpbi9zaApwcmludGYgJ1xuJwpwcmludGYgJyAgXDAzM1sxOzM1bSstLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tK1wwMzNbMG1cbicKcHJpbnRmICcgIFwwMzNbMTszNW18ICAgICAgICAgICAgICAgICAgICAgICAgICAgIHxcMDMzWzBtXG4nCnByaW50ZiAnICBcMDMzWzE7MzVtfCAgIFwwMzNbMTszM21XZWxjb21lIHRvIFJYaW4hXDAzM1sxOzM1bSAgICAgICAgfFwwMzNbMG1cbicKcHJpbnRmICcgIFwwMzNbMTszNW18ICAgICAgICAgICAgICAgICAgICAgICAgICAgIHxcMDMzWzBtXG4nCnByaW50ZiAnICBcMDMzWzE7MzVtKy0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0rXDAzM1swbVxuJwpwcmludGYgJ1xuJwpwcmludGYgJyAgXDAzM1sybVR5cGUgcnhlIHRvIGV4cGxvcmUuXDAzM1swbVxuJwpwcmludGYgJ1xuJwo="

    private const val B64_NYAN =
        "IyEvc3lzdGVtL2Jpbi9zaApwcmludGYgJ1xyICBcMDMzWzM1bSAgX19fXDAzM1swbSAgICAgJwpzbGVlcCAwLjEKcHJpbnRmICdcciAgXDAzM1szM20gIHx8X3x8XDAzM1swbSAgICAnCnNsZWVwIDAuMQpwcmludGYgJ1xyICBcMDMzWzMybSAgfCggKXxcMDMzWzBtICAgICcKc2xlZXAgMC4xCnByaW50ZiAnXHIgIFwwMzNbMzZtIF98IHx8IHxfXDAzM1swbSAgJwpzbGVlcCAwLjEKcHJpbnRmICdcciAgXDAzM1szNG18LCAgLiciJyInICAsfFwwMzNbMG0gJwpzbGVlcCAwLjEKcHJpbnRmICdcciAgXDAzM1szNW18ICAnIiciJy0tJyInIicgIHxcMDMzWzBtICcKc2xlZXAgMC4xCnByaW50ZiAnXHIgIFwwMzNbMzFtICciJyInLnx8Xy4nIiciJyBcMDMzWzBtICcKc2xlZXAgMC4yCnByaW50ZiAnXG4gIFwwMzNbMzVtTnlhbiFcMDMzWzBtXG4nCg=="

    private const val B64_RR =
        "IyEvc3lzdGVtL2Jpbi9zaApDT0xTPSQoc3R0eSBzaXplIDI+L2Rldi9udWxsIHwgYXdrICd7cHJpbnQgJDJ9JykKWyAteiAiJENPTFMiIF0gJiYgQ09MUz00MApbICIkQ09MUyIgLWx0IDEwIF0gJiYgQ09MUz00MApURVhUPScgIFJYaW4gU2hlbGxUb29sQm94ICAnCmZvciBjIGluIDMxIDMzIDMyIDM2IDM0IDM1OyBkbwogIExFTj0keyNURVhUfQogIFBBRD0kKCggKENPTFMgLSBMRU4pIC8gMiApKQogIFsgIiRQQUQiIC1sdCAwIF0gJiYgUEFEPTAKICBTUEFDRVM9JycKICBqPTAKICB3aGlsZSBbICRqIC1sdCAkUEFEIF07IGRvCiAgICBTUEFDRVM9IiRTUEFDRVMgIgogICAgaj0kKCggaiArIDEgKSkKICBkb25lCiAgcHJpbnRmICdcMDMzWyVkbSVzJXNcMDMzWzBtXG4nICIkYyIgIiRTUEFDRVMiICIkVEVYVCIKICBzbGVlcCAwLjE1CmRvbmUK"

    private const val B64_RXE =
        "IyEvc3lzdGVtL2Jpbi9zaApwcmludGYgJ1xuJwpwcmludGYgJyAgXDAzM1sxOzM2bSstLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLStcMDMzWzBtXG4nCnByaW50ZiAnICBcMDMzWzE7MzZtfCAgICAgICAgIFJYaW4gRW5naW5lIHYxLjAgICAgICAgICAgICAgfFwwMzNbMG1cbicKcHJpbnRmICcgIFwwMzNbMTszNm18ICAgICAgIFByaXZhdGUgU2FuZGJveCBTeXN0ZW0gICAgICAgICAgfFwwMzNbMG1cbicKcHJpbnRmICcgIFwwMzNbMTszNm0rLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0rXDAzM1swbVxuJwpwcmludGYgJ1xuJwpwcmludGYgJyAgXDAzM1sxOzMzbUF2YWlsYWJsZSBDb21tYW5kczpcMDMzWzBtXG4nCnByaW50ZiAnICBcMDMzWzE7MzJtICBoZWxsb1wwMzNbMG0gICAgLSBXZWxjb21lIG1lc3NhZ2VcbicKcHJpbnRmICcgIFwwMzNbMTszMm0gIG55YW5cMDMzWzBtICAgICAtIE55YW4gY2F0IG1pbmlcbicKcHJpbnRmICcgIFwwMzNbMTszMm0gIGZvcnR1bmVcMDMzWzBtICAtIFJhbmRvbSBxdW90ZVxuJwpwcmludGYgJyAgXDAzM1sxOzMybSAgZW5naW5lXDAzM1swbSAgIC0gU3lzdGVtIHN0YXR1c1xuJwpwcmludGYgJyAgXDAzM1sxOzMybSAgcnhlXDAzM1swbSAgICAgIC0gVGhpcyBoZWxwIG1lbnVcbicKcHJpbnRmICcgIFwwMzNbMTszMm0gIHNub3dcMDMzWzBtICAgICAtIFRlcm1pbmFsIHNub3dmYWxsXG4nCnByaW50ZiAnICBcMDMzWzE7MzJtICByclwwMzNbMG0gICAgICAgLSBSYWluYm93IHNjcm9sbFxuJwpwcmludGYgJ1xuJwpwcmludGYgJyAgXDAzM1sybVR5cGUgYW55IGNvbW1hbmQgdG8gZXhwbG9yZS5cMDMzWzBtXG4nCnByaW50ZiAnXG4nCg=="

    private const val B64_SNOW =
        "IyEvc3lzdGVtL2Jpbi9zaApXPSQoc3R0eSBzaXplIDI+L2Rldi9udWxsIHwgYXdrICd7cHJpbnQgJDJ9JykKWyAteiAiJFciIF0gJiYgVz02MApbICIkVyIgLWx0IDEwIF0gJiYgVz02MAp0cmFwICd0cHV0IGNub3JtOyBleGl0JyBJTlQgVEVSTQp0cHV0IGNpdmlzIDI+L2Rldi9udWxsCmk9MAp3aGlsZSBbICRpIC1sdCAzMCBdOyBkbwogIFg9JCgoIChpICogNyArICQkKSAlIFcgKSkKICBSPSQoKCBpICUgMTAgKyAxICkpCiAgcHJpbnRmICdcMDMzWyVkOyVkSFwwMzNbMTszN20qXDAzM1swbScgIiRSIiAiJFgiCiAgaT0kKCggaSArIDEgKSkKZG9uZQpwcmludGYgJ1wwMzNbMTE7MUhcMDMzWzJtTWVycnkgUlhpbiFcMDMzWzBtJwp0cHV0IGNub3JtIDI+L2Rldi9udWxsCnByaW50ZiAnXG4nCg=="

    private const val B64_XIE =
        "IyEvc3lzdGVtL2Jpbi9zaAojIFhpZSBaaSBYaW4gLSBCaWcgZm9udCBXaGl0ZQoKIyBDbGVhcgpwcmludGYgJ1wwMzNbMkpcMDMzW0gnCgpwcmludGYgJ1wwMzNbMTszN21fXyAgIF9fX19fX18gX19fX19fICAgX19fX19fX19fX18gIF9fICAgX19fX19fXyBfICAgX1wwMzNbMG1cbicKcHJpbnRmICdcMDMzWzE7MzdtXCBcIC8gL18gICBffCAgX19fX3wgfF9fXyAgL18gICBffCBcIFwgLyAvXyAgIF98IFwgfCB8XDAzM1swbVxuJwpwcmludGYgJ1wwMzNbMTszN20gXCBWIC8gIHwgfCB8IHxfXyAgICAgICAvIC8gIHwgfCAgICBcIFYgLyAgfCB8IHwgIFx8IHxcMDMzWzBtXG4nCnByaW50ZiAnXDAzM1sxOzM3bSAgPiA8ICAgfCB8IHwgIF9ffCAgICAgLyAvICAgfCB8ICAgICA+IDwgICB8IHwgfCAuIGAgfFwwMzNbMG1cbicKcHJpbnRmICdcMDMzWzE7MzdtIC8gLiBcIF98IHxffCB8X19fXyAgIC8gL19fIF98IHxfICAgLyAuIFwgX3wgfF98IHxcICB8XDAzM1swbVxuJwpwcmludGYgJ1wwMzNbMTszN20vXy8gXF9cX19fX198X19fX19ffCAvX19fX198X19fX198IC9fLyBcX1xfX19fX3xffCBcX3xcMDMzWzBtXG4nCnByaW50ZiAnXDAzM1sxOzM3bVwwMzNbMG1cbicKcHJpbnRmICdcMDMzWzE7MzdtXDAzM1swbVxuJwoKIyA9PT0gU25vd2ZsYWtlcyA9PT0KVz0kKHN0dHkgc2l6ZSAyPi9kZXYvbnVsbCB8IGF3ayAne3ByaW50ICQyfScpClc9JHtXOi02MH0KWyAiJFciIC1sdCAxMCBdICYmIFc9NjAKdHJhcCAidHB1dCBjbm9ybTsgZXhpdCIgSU5UIFRFUk0KdHB1dCBjaXZpcyAyPi9kZXYvbnVsbAppPTAKd2hpbGUgWyAkaSAtbHQgMjAgXTsgZG8KICBYPSQoKCAoaSAqIDEzICsgJCQpICUgVyApKQogIFI9JCgoIGkgJSAxMiArIDEwICkpCiAgcHJpbnRmICdcMDMzWyVkOyVkSFwwMzNbMTszN20qXDAzM1swbScgIiRSIiAiJFgiCiAgaT0kKCggaSArIDEgKSkKICBzbGVlcCAwLjEwCmRvbmUKCiMgRm9vdGVyCnByaW50ZiAnXDAzM1syNDsxSCcKcHJpbnRmICdcMDMzWzE7MzBtICB+fiBYaWUgWmkgWGluIH5+IFwwMzNbMG1cbicKCnRwdXQgY25vcm0gMj4vZGV2L251bGwKcHJpbnRmICdcbicK"

    private const val B64_SHINIT =
        "ZW5naW5lKCkgeyBjb21tYW5kIHNoICcvZGF0YS9kYXRhL2NvbS5Sb3V4aW4uU2hlbGxUb29sL1JYaW4vYmluL2VuZ2luZScgIiRAIjsgfQpmb3J0dW5lKCkgeyBjb21tYW5kIHNoICcvZGF0YS9kYXRhL2NvbS5Sb3V4aW4uU2hlbGxUb29sL1JYaW4vYmluL2ZvcnR1bmUnICIkQCI7IH0KaGVsbG8oKSB7IGNvbW1hbmQgc2ggJy9kYXRhL2RhdGEvY29tLlJvdXhpbi5TaGVsbFRvb2wvUlhpbi9iaW4vaGVsbG8nICIkQCI7IH0KbnlhbigpIHsgY29tbWFuZCBzaCAnL2RhdGEvZGF0YS9jb20uUm91eGluLlNoZWxsVG9vbC9SWGluL2Jpbi9ueWFuJyAiJEAiOyB9CnJyKCkgeyBjb21tYW5kIHNoICcvZGF0YS9kYXRhL2NvbS5Sb3V4aW4uU2hlbGxUb29sL1JYaW4vYmluL3JyJyAiJEAiOyB9CnJ4ZSgpIHsgY29tbWFuZCBzaCAnL2RhdGEvZGF0YS9jb20uUm91eGluLlNoZWxsVG9vbC9SWGluL2Jpbi9yeGUnICIkQCI7IH0Kc25vdygpIHsgY29tbWFuZCBzaCAnL2RhdGEvZGF0YS9jb20uUm91eGluLlNoZWxsVG9vbC9SWGluL2Jpbi9zbm93JyAiJEAiOyB9ClhpZSgpIHsgY29tbWFuZCBzaCAnL2RhdGEvZGF0YS9jb20uUm91eGluLlNoZWxsVG9vbC9SWGluL2Jpbi9YaWUnICIkQCI7IH0K"

    private val B64 = listOf(
        B64_ENGINE, B64_FORTUNE, B64_HELLO, B64_NYAN, B64_RR, B64_RXE, B64_SNOW, B64_XIE
    )
}
