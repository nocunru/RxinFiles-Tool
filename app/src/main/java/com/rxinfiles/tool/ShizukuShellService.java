package com.rxinfiles.tool;

import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;
import android.util.SparseArray;

import com.termux.terminal.JNI;

import java.util.concurrent.TimeUnit;

/**
 * Shizuku 用户服务的实现。
 *
 * 该类的实例由 Shizuku 创建，并运行在 shell(uid 2000) 身份的进程中。
 * 因此它调用 JNI.createSubprocess 所派生出的 shell 进程天然具备 adb 权限，
 * 与在应用进程内直接 fork 有本质区别。
 *
 * 会话管理：以 shell 进程 pid 为句柄存放在 SparseArray 中，
 * 支持终端同时打开多个标签页而不互相影响。
 */
public class ShizukuShellService extends IShizukuShellService.Stub {

    private static final String TAG = "RxShizukuShell";

    /** 单条命令的最长执行时间（秒） */
    private static final long EXEC_TIMEOUT_SECONDS = 30L;

    /** 一个 PTY 会话。持有 pfd 是为了避免 ParcelFileDescriptor 被 GC 回收时关闭底层 fd。 */
    private static final class PtySession {
        int fd = -1;
        int pid = -1;
        ParcelFileDescriptor pfd;
    }

    private final SparseArray<PtySession> mSessions = new SparseArray<>();

    public ShizukuShellService() {
        Log.i(TAG, "service created, uid=" + Process.myUid());
    }

    // ------------------------------------------------------------------ PTY

    @Override
    public ParcelFileDescriptor openPty(String shellPath, String cwd, int rows, int cols, int[] outPid) {
        try {
            int[] processId = new int[1];
            // 注意：createSubprocess 的 argv 中不含 argv[0]，native 侧直接 execvp(cmd, argv)，
            // 因此把 shellPath 同时作为 argv[0] 传入，避免出现空 argv 导致 shell 行为异常。
            int fd = JNI.createSubprocess(
                    shellPath,
                    cwd,
                    new String[]{shellPath},
                    buildEnv(cwd),
                    processId,
                    rows,
                    cols
            );
            if (fd < 0) {
                Log.e(TAG, "createSubprocess returned " + fd);
                return null;
            }

            PtySession session = new PtySession();
            session.fd = fd;
            session.pid = processId[0];
            session.pfd = ParcelFileDescriptor.fromFd(fd);

            synchronized (mSessions) {
                mSessions.put(session.pid, session);
            }
            if (outPid != null && outPid.length > 0) {
                outPid[0] = session.pid;
            }
            Log.i(TAG, "pty created pid=" + session.pid + " fd=" + fd
                    + " total=" + mSessions.size());
            return session.pfd;
        } catch (Throwable t) {
            Log.e(TAG, "openPty failed", t);
            return null;
        }
    }

    @Override
    public void closePty(int pid) {
        PtySession session;
        synchronized (mSessions) {
            session = mSessions.get(pid);
            mSessions.remove(pid);
        }
        if (session == null) return;

        // fromFd() 已经接管了原始 fd 的所有权，因此这里必须通过 pfd 关闭，
        // 否则会对同一个 fd 号二次 close，可能误关其它文件。
        try {
            if (session.pfd != null) session.pfd.close();
        } catch (Throwable t) {
            Log.w(TAG, "closePty close failed", t);
        }
        session.pfd = null;
        session.fd = -1;
        Log.i(TAG, "pty closed pid=" + pid + " total=" + mSessions.size());
    }

    // ------------------------------------------------------------- 命令执行

    /**
     * 以 adb 权限执行命令。
     * 该方法在 shell 进程内运行，因此 ProcessBuilder 派生的子进程继承 shell 身份，
     * 可以直接访问 pm / am / settings 等命令以及受保护目录。
     *
     * 说明：
     *  - 合并 stderr 到 stdout，避免双向管道写满造成死锁；
     *  - 增加超时保护，防止 `top` 之类不会自行退出的命令永久占住 binder 调用。
     */
    @Override
    public String exec(String command) {
        // 注意：本文件上方 import 了 android.os.Process，因此这里必须用全限定名
        // java.lang.Process，否则会被解析成 android.os.Process 而编译失败。
        java.lang.Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", command);
            pb.redirectErrorStream(true);
            p = pb.start();
            final java.lang.Process launched = p;

            java.util.concurrent.FutureTask<String> output =
                    new java.util.concurrent.FutureTask<>(() -> readAll(launched.getInputStream()));
            Thread reader = new Thread(output, "RxShizuku-Exec-Reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = p.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                p.waitFor(2, TimeUnit.SECONDS);
            }
            String out;
            try {
                out = output.get(2, TimeUnit.SECONDS);
            } catch (Throwable readFailure) {
                out = "";
            }
            if (!finished) {
                return out + "\n[命令超时，已强制结束]";
            }
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "exec failed: " + command, t);
            return "error: " + t;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String readAll(java.io.InputStream in) throws java.io.IOException {
        final int limit = 2 * 1024 * 1024;
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        long total = 0;
        int n;
        while ((n = in.read(tmp)) != -1) {
            int keep = (int) Math.min(n, Math.max(0L, limit - total));
            if (keep > 0) buf.write(tmp, 0, keep);
            total += n;
        }
        in.close();
        String out = buf.toString("UTF-8");
        return total > limit ? out + "\n[输出过大，已截断]" : out;
    }

    /** 构造 shell 进程所需的最小环境变量集合 */
    private String[] buildEnv(String cwd) {
        String home = (cwd != null && !cwd.isEmpty()) ? cwd : "/";
        return new String[]{
                "TERM=xterm-256color",
                "LANG=en_US.UTF-8",
                "HOME=" + home,
                "PWD=" + home,
                "PATH=/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin"
                        + ":/system_ext/bin:/system/bin:/system/xbin"
                        + ":/odm/bin:/vendor/bin:/vendor/xbin",
                "ANDROID_ROOT=/system",
                "ANDROID_DATA=/data",
                "ANDROID_STORAGE=/storage",
                "EXTERNAL_STORAGE=/sdcard",
                "SHELL=/system/bin/sh"
        };
    }
}
