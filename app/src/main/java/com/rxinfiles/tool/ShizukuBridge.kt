package com.rxinfiles.tool

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Shizuku 桥接层。
 *
 * Shizuku 通过 adb 或 root 启动一个特权服务进程，让本应用可以以 shell(uid 2000) 的身份
 * 执行命令与创建终端 —— 不需要 root，但需要用户手动激活 Shizuku。
 *
 * 注意：Shizuku 的公开 API 不提供直接创建进程的方法，必须以 UserService 的形式
 * 在目标身份下运行代码，因此这里统一通过绑定 [ShizukuShellService] 来获得能力。
 */
object ShizukuBridge {

    private const val TAG = "ShizukuBridge"

    const val REQUEST_CODE = 4001

    /** 已绑定的用户服务实例，未就绪时为 null */
    @Volatile
    var shellService: IShizukuShellService? = null
        private set

    private var connection: ServiceConnection? = null

    /**
     * 绑定时的参数对象。
     * Shizuku 13.x 的 `unbindUserService` 签名是
     * `unbindUserService(UserServiceArgs, ServiceConnection, boolean)`，
     * 解绑时必须把当初绑定时用过的同一个 args 对象传回去，因此这里要缓存。
     */
    private var serviceArgs: Shizuku.UserServiceArgs? = null

    private var binding = false
    private val pendingCallbacks = mutableListOf<(IShizukuShellService?) -> Unit>()

    /** 持有的 Application Context，用于在任意位置按需绑定用户服务 */
    @Volatile
    private var appContext: Context? = null

    /** 在 Application/Activity 启动时调用一次即可 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ------------------------------------------------------------------ 状态

    /** Shizuku 服务是否在线（用户已激活 Shizuku 且服务存活） */
    fun isBinderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        Log.w(TAG, "pingBinder failed", t)
        false
    }

    /** Shizuku 版本是否过低（低于 v11 不支持当前 API） */
    fun isTooOld(): Boolean = try {
        Shizuku.isPreV11()
    } catch (t: Throwable) {
        true
    }

    /** 本应用是否已被授予 Shizuku 权限 */
    fun isGranted(): Boolean = try {
        if (!isBinderAlive()) false
        else !isTooOld() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        Log.w(TAG, "checkSelfPermission failed", t)
        false
    }

    /** 申请 Shizuku 权限 */
    fun requestPermission() {
        try {
            if (isBinderAlive() && !isTooOld()) {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "requestPermission failed", t)
        }
    }

    /** 是否需要向用户解释申请理由 */
    fun shouldShowRationale(): Boolean = try {
        isBinderAlive() && !isTooOld() && Shizuku.shouldShowRequestPermissionRationale()
    } catch (t: Throwable) {
        false
    }

    fun addPermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        try {
            Shizuku.addRequestPermissionResultListener(listener)
        } catch (t: Throwable) {
            Log.w(TAG, "addPermissionListener failed", t)
        }
    }

    fun removePermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        try {
            Shizuku.removeRequestPermissionResultListener(listener)
        } catch (t: Throwable) {
            Log.w(TAG, "removePermissionListener failed", t)
        }
    }

    // ------------------------------------------------------------- 服务绑定

    /**
     * 绑定 Shizuku 用户服务。
     * 若已绑定则立即回调；并发调用会排队，绑定完成后统一回调。
     */
    fun bindService(context: Context, onResult: (IShizukuShellService?) -> Unit) {
        shellService?.let {
            onResult(it)
            return
        }
        if (!isGranted()) {
            onResult(null)
            return
        }

        synchronized(this) {
            pendingCallbacks.add(onResult)
            if (binding) return
            binding = true
        }

        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, ShizukuShellService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("shell")
                .debuggable(BuildConfig.DEBUG)
                .version(1)

            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val svc = IShizukuShellService.Stub.asInterface(binder)
                    shellService = svc
                    Log.i(TAG, "user service connected")
                    val callbacks = synchronized(this@ShizukuBridge) {
                        binding = false
                        val pending = pendingCallbacks.toList()
                        pendingCallbacks.clear()
                        pending
                    }
                    callbacks.forEach { cb -> cb(svc) }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    Log.w(TAG, "user service disconnected")
                    shellService = null
                }
            }
            connection = conn
            serviceArgs = args
            Shizuku.bindUserService(args, conn)
        } catch (t: Throwable) {
            Log.w(TAG, "bindUserService failed", t)
            val callbacks = synchronized(this) {
                binding = false
                val pending = pendingCallbacks.toList()
                pendingCallbacks.clear()
                pending
            }
            callbacks.forEach { it(null) }
        }
    }

    /**
     * 解绑 Shizuku 用户服务。
     * 需要把当初绑定时使用的 [Shizuku.UserServiceArgs] 与 [ServiceConnection] 一起回传。
     */
    fun unbindService() {
        val args = serviceArgs
        val conn = connection
        if (args != null && conn != null) {
            try {
                Shizuku.unbindUserService(args, conn, true)
            } catch (t: Throwable) {
                Log.w(TAG, "unbindUserService failed", t)
            }
        }
        serviceArgs = null
        connection = null
        shellService = null
    }

    /**
     * 同步等待用户服务就绪。
     *
     * 绑定回调由 Shizuku 通过主线程 Handler 派发，因此 **绝对不能在主线程调用本方法**，
     * 否则会自锁。调用方（文件列表、终端）都在后台线程执行，满足该约束。
     */
    fun awaitService(timeoutMs: Long = 8000): IShizukuShellService? {
        shellService?.let { return it }
        val ctx = appContext ?: return null
        if (!isGranted()) return null

        val latch = CountDownLatch(1)
        val holder = AtomicReference<IShizukuShellService?>(null)
        bindService(ctx) {
            holder.set(it)
            latch.countDown()
        }
        return try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "awaitService timeout")
                null
            } else {
                holder.get()
            }
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    /** 以 adb 权限执行命令；服务未就绪时尝试按需绑定 */
    fun exec(command: String): String? {
        val svc = shellService ?: awaitService() ?: return null
        return try {
            svc.exec(command)
        } catch (t: Throwable) {
            Log.w(TAG, "exec failed", t)
            null
        }
    }

    // ------------------------------------------------------------------ PTY

    /**
     * 在 shell 身份下创建一个 PTY。
     *
     * 当前版本**不再使用**：终端已固定为普通用户模式（应用自身 uid），
     * 走本地 PTY 即可。保留此能力是因为它在服务侧已经完整可用
     * （见 [ShizukuShellService] 的 openPty/closePty 与 IShizukuShellService.aidl），
     * 将来若要恢复「以 adb 身份开终端」不必重写一遍。
     *
     * @return `first` = 本进程可用的 master fd（已从 ParcelFileDescriptor 中摘出并接管所有权），
     *         `second` = shell 进程 pid，同时作为会话句柄；失败返回 null
     */
    fun openPty(shellPath: String, cwd: String, rows: Int, cols: Int): Pair<Int, Int>? {
        val svc = shellService ?: awaitService() ?: run {
            Log.w(TAG, "openPty: service unavailable")
            return null
        }
        return try {
            val outPid = intArrayOf(0)
            val pfd = svc.openPty(shellPath, cwd, rows, cols, outPid) ?: return null
            // 取出裸 fd 并接管所有权，交给 TerminalSession 使用
            val fd = pfd.detachFd()
            if (fd < 0 || outPid[0] <= 0) {
                Log.w(TAG, "openPty: bad fd=$fd pid=${outPid[0]}")
                if (fd >= 0) {
                    runCatching { android.os.ParcelFileDescriptor.adoptFd(fd).close() }
                }
                if (outPid[0] > 0) runCatching { svc.closePty(outPid[0]) }
                return null
            }
            fd to outPid[0]
        } catch (t: Throwable) {
            Log.w(TAG, "openPty failed", t)
            null
        }
    }

    /**
     * 释放服务端持有的 master fd 引用。
     * 客户端关闭自己的 fd 后，若服务端也已释放，内核才会向该 PTY 的前台进程组发送 SIGHUP。
     */
    fun closePty(pid: Int) {
        if (pid <= 0) return
        val svc = shellService ?: return
        try {
            svc.closePty(pid)
        } catch (t: Throwable) {
            Log.w(TAG, "closePty failed", t)
        }
    }
}
