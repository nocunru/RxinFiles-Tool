package com.rxinfiles.tool

import android.app.Application
import android.util.Log

/**
 * 应用级初始化入口。
 *
 * <h2>为什么必须有这个类（3.15.2 修正，一个真机 bug 的直接产物）</h2>
 *
 * `ShizukuBridge.init()` 原本只在 [MainActivity.onCreate] 里调过一次。但本应用有一个
 * **不经过 MainActivity 的入口**：[IncomingActivity]（`exported="true"` + `ACTION_VIEW`）。
 * 用户从 QQ / 微信里点一个文件、选「用其他应用打开」时，系统直接拉起 IncomingActivity，
 * **MainActivity 从未被创建**，于是 `ShizukuBridge.appContext` 一直是 null。
 *
 * 后果是一条完全静默的链路 —— 每一环都「正常返回」，但结果全是错的：
 *
 * ```
 * ShizukuBridge.awaitService()   // appContext == null -> 立即 return null（连绑定都不试）
 *   -> ShizukuBridge.exec()      // 拿到 null -> return null
 *   -> ShellExecutor.exec()      // 转发 null
 *   -> FileRepository.listWithShell()      // null -> 降级 Java
 *   -> FileRepository.probeListShell()     // null -> "命令执行失败（无输出）"
 *   -> IncomingFile.parseSearchStatus()    // 空 -> SearchStatus.NO_OUTPUT("shell 无输出")
 * ```
 *
 * 真机上的表现就是「Shell 身份明确显示 ADB，但连 `Download` 这种必然可列的目录，
 * shell 通道也列不出」—— 因为问题根本不在访问权限，而在**通道从未真正建立**。
 *
 * 修法：把初始化提到 Application 层。Application 一定先于任何 Activity 创建，
 * 因此**所有入口（Main / Incoming / Terminal / 两个 Viewer）天然覆盖**，
 * 且各处不再需要各自记得调用一次。
 *
 * 与 `ShellExecutor` 的「按身份分流」原则一致：这里的职责只是**提供上下文**，
 * 不做任何探测（探测仍然是惰性的，理由见 `IncomingActivity.ensureShellLevel`）。
 */
class RxinApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ThemeStore.apply(this)
        // 只存 applicationContext，不执行任何外部进程 —— 探测保持惰性。
        ShizukuBridge.init(this)
        sweepStaleStageInBackground()
        Log.i(TAG, "RxinApp onCreate, ShizukuBridge 已初始化")
    }

    /**
     * 冷启动兜底清理 cacheDir/rxin_stage 的遗留条目。
     *
     * 暂存目录现在按「谁创建谁释放」管理：查看器 / 归档操作自己用完后删除各自的唯一目录，
     * 正常退出不会留下东西。这里只兜底进程被杀、崩溃、旋转重建等来不及释放的残留；
     * [FileOps.sweepStaleStage] 只清确认没有进程在用的条目，**不会**递归删共享根目录，
     * 因此不会破坏其他进程正在使用的副本（BUG-005）。
     *
     * 清理要遍历并删除文件，放后台 daemon 线程，不拖慢冷启动的主线程。
     */
    private fun sweepStaleStageInBackground() {
        Thread({
            try {
                FileOps.sweepStaleStage(this@RxinApp)
            } catch (t: Throwable) {
                Log.w(TAG, "sweepStaleStage failed", t)
            }
        }, "RxinStageSweep").apply {
            isDaemon = true
            start()
        }
    }

    private companion object {
        const val TAG = "RxinApp"
    }
}
