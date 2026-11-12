package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Message;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session, consisting of a process coupled to a terminal interface.
 * <p>
 * The subprocess will be executed by the constructor, and when the size is made known by a call to
 * {@link #updateSize(int, int)} terminal emulation will begin and threads will be spawned to handle the subprocess I/O.
 * All terminal emulation and callback methods will be performed on the main thread.
 * <p>
 * The child process may be exited forcefully by using the {@link #finishIfRunning()} method.
 * <p>
 * NOTE: The terminal session may outlive the EmulatorView, so be careful with callbacks!
 */
public final class TerminalSession extends TerminalOutput {

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_PROCESS_EXITED = 4;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /**
     * A queue written to from a separate thread when the process outputs, and read by main thread to process by
     * terminal emulator.
     */
    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(4096);
    /**
     * A queue written to from the main thread due to user interaction, and read by another thread which forwards by
     * writing to the {@link #mTerminalFileDescriptor}.
     */
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
    /** Buffer to write translate code points into utf8 before writing to mTerminalToProcessIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    /** The pid of the shell process. 0 if not started and -1 if finished running. */
    int mShellPid;

    /** The exit status of the shell process. Only valid if ${@link #mShellPid} is -1. */
    int mShellExitStatus;

    /**
     * The file descriptor referencing the master half of a pseudo-terminal pair, resulting from calling
     * {@link JNI#createSubprocess(String, String, String[], String[], int[], int, int)}.
     */
    private int mTerminalFileDescriptor;

    /**
     * 外部注入的 PTY（由 Shizuku 在 shell 权限进程内创建）。
     * 当该值 >= 0 时，initializeEmulator 将直接使用它，而不是调用 JNI.createSubprocess。
     * 这是实现「以 adb 权限运行完整终端」的关键：PTY 必须由具备 shell 身份的进程创建。
     */
    private int mExternalPtyFd = -1;
    private int mExternalPtyPid = -1;

    /**
     * 当前会话是否使用外部注入的 PTY。
     * 外部 PTY 的 shell 进程并不是本进程的子进程，无法通过 waitpid 回收，
     * 因此必须跳过 JNI.waitFor 等待线程，否则会立刻被判定为「进程已退出」。
     * 会话结束改为由输入读取线程感知 pty 关闭（EOF / EIO）来判定。
     */
    private boolean mUsingExternalPty = false;

    /**
     * 外部 PTY 的会话句柄（= shell 进程 pid）。
     * 与 {@link #mExternalPtyPid} 不同，该值在会话生命周期内始终保留，
     * 供上层在销毁会话时通知 Shizuku 服务端释放 master fd 引用。
     */
    private int mPtySessionPid = -1;

    /** 保证「进程已退出」只上报一次 */
    private final java.util.concurrent.atomic.AtomicBoolean mProcessExitNotified =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 进程退出时是否往屏幕上补一行 `[Process completed …]`。
     *
     * 默认 true（等于 Termux 原行为）。**脚本终端**例外：脚本跑完后由 App 主动收掉会话
     * （见 TerminalActivity.markScriptFinished），那时屏幕上已经有一行
     * `[RouXin] 脚本已结束，退出码 N`，再来一句英文的 `[Process completed (signal 9)
     * - press Enter]` 只会让人以为出了事 —— 何况那个「press Enter」在本应用里是错的
     * （按任意键是离开终端，不是继续）。
     */
    private boolean mAppendExitNotice = true;

    /** 见 {@link #mAppendExitNotice} */
    public void setAppendExitNotice(boolean appendExitNotice) {
        mAppendExitNotice = appendExitNotice;
    }

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final String mShellPath;
    private final String mCwd;
    private final String[] mArgs;
    private final String[] mEnv;
    private final Integer mTranscriptRows;


    private static final String LOG_TAG = "TerminalSession";

    public TerminalSession(String shellPath, String cwd, String[] args, String[] env, Integer transcriptRows, TerminalSessionClient client) {
        this.mShellPath = shellPath;
        this.mCwd = cwd;
        this.mArgs = args;
        this.mEnv = env;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the attached pty of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows);
        } else {
            JNI.setPtyWindowSize(mTerminalFileDescriptor, rows, columns);
            mEmulator.resize(columns, rows);
        }
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /**
     * 注入一个外部创建好的 PTY（master fd 与子进程 pid）。
     * 必须在 {@link #updateSize(int, int)} 首次调用之前设置。
     */
    public void attachExternalPty(int fd, int pid) {
        mExternalPtyFd = fd;
        mExternalPtyPid = pid;
        mPtySessionPid = pid;
    }

    /**
     * 外部 PTY 的会话句柄（shell 进程 pid），非外部 PTY 时返回 -1。
     * 上层可据此通知 Shizuku 服务端释放对应的 master fd 引用。
     */
    public int getPtySessionPid() {
        return mPtySessionPid;
    }

    /** 是否为外部注入的 PTY 会话 */
    public boolean isExternalPty() {
        return mUsingExternalPty;
    }

    /**
     * Set the terminal emulator's window size and start terminal emulation.
     *
     * @param columns The number of columns in the terminal window.
     * @param rows    The number of rows in the terminal window.
     */
    public void initializeEmulator(int columns, int rows) {
        mEmulator = new TerminalEmulator(this, columns, rows, mTranscriptRows, mClient);

        if (mExternalPtyFd >= 0) {
            mTerminalFileDescriptor = mExternalPtyFd;
            mShellPid = mExternalPtyPid;
            mExternalPtyFd = -1;
            mExternalPtyPid = -1;
            mUsingExternalPty = true;
        } else {
            int[] processId = new int[1];
            // native 侧是 execvp(cmd, argv)，argv 中并不包含 argv[0]。
            // 当调用方未提供参数时，用 shellPath 作为 argv[0]，避免 execvp 收到 NULL argv。
            String[] argv = (mArgs != null && mArgs.length > 0) ? mArgs : new String[]{mShellPath};
            mTerminalFileDescriptor = JNI.createSubprocess(mShellPath, mCwd, argv, mEnv, processId, rows, columns);
            mShellPid = processId[0];
        }

        final FileDescriptor terminalFileDescriptorWrapped = wrapFileDescriptor(mTerminalFileDescriptor, mClient);

        new Thread("TermSessionInputReader[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                boolean ptyClosed = false;
                try (InputStream termIn = new FileInputStream(terminalFileDescriptorWrapped)) {
                    final byte[] buffer = new byte[4096];
                    while (true) {
                        int read = termIn.read(buffer);
                        if (read == -1) {
                            ptyClosed = true;
                            break;
                        }
                        if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) return;
                        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                    }
                } catch (Exception e) {
                    // Linux 下 PTY 从端全部关闭后，读主端会返回 EIO。
                    // 对本地 PTY 而言这只是「进程已退出」的另一种表现，
                    // 真正的退出通知来自 TermSessionWaiter，此处无需处理。
                    ptyClosed = true;
                }
                if (ptyClosed) notifyExternalPtyExited();
            }
        }.start();

        new Thread("TermSessionOutputWriter[pid=" + mShellPid + "]") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                try (FileOutputStream termOut = new FileOutputStream(terminalFileDescriptorWrapped)) {
                    while (true) {
                        int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                        if (bytesToWrite == -1) return;
                        termOut.write(buffer, 0, bytesToWrite);
                    }
                } catch (IOException e) {
                    // Ignore.
                }
            }
        }.start();

        if (mUsingExternalPty) {
            // 外部 PTY：shell 进程属于 Shizuku 的 shell 进程，不是本进程的子进程，
            // waitpid 会立即以 ECHILD 失败并误报「已退出」，因此不做等待。
            // 会话结束由 closePty / finishIfRunning 负责关闭 fd。
        } else {
            new Thread("TermSessionWaiter[pid=" + mShellPid + "]") {
                @Override
                public void run() {
                    int processExitCode = JNI.waitFor(mShellPid);
                    mMainThreadHandler.sendMessage(mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, processExitCode));
                }
            }.start();
        }

    }

    /** Write data to the shell process. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mShellPid > 0) mTerminalToProcessIOQueue.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Finish this terminal session by sending SIGKILL to the shell. */
    public void finishIfRunning() {
        if (mUsingExternalPty) {
            // 外部 PTY：shell 进程属于 Shizuku 一侧，本进程无权 kill，
            // 直接关闭 master fd，内核会向该 pty 的前台进程组发送 SIGHUP，shell 随之退出。
            // 注意：cleanupResources() 可能已经关过该 fd，重复 close 会误关其它文件，必须先判空。
            if (mTerminalFileDescriptor < 0) return;
            try {
                JNI.close(mTerminalFileDescriptor);
            } catch (Throwable ignored) {
            }
            mTerminalFileDescriptor = -1;
            mShellPid = -1;
            return;
        }
        if (isRunning()) {
            try {
                Os.kill(mShellPid, OsConstants.SIGKILL);
            } catch (ErrnoException e) {
                mClient.logWarn(LOG_TAG, "Failed sending SIGKILL: " + e.getMessage());
            }
        }
    }

    /** Cleanup resources when the process exits. */
    void cleanupResources(int exitStatus) {
        synchronized (this) {
            mShellPid = -1;
            mShellExitStatus = exitStatus;
        }

        // Stop the reader and writer threads, and close the I/O streams
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        if (mTerminalFileDescriptor >= 0) {
            JNI.close(mTerminalFileDescriptor);
            // 置为 -1，避免 finishIfRunning() 对同一个 fd 二次 close
            mTerminalFileDescriptor = -1;
        }
    }

    /**
     * 外部 PTY 的 shell 退出通知。
     *
     * 外部 PTY 的 shell 不是本进程的子进程，既无法 waitpid 也无法 kill，
     * 因此只能由输入读取线程感知 pty 关闭（EOF / EIO）来判定「shell 已退出」。
     * 仅在外部 PTY 模式下生效；本地会话仍由 TermSessionWaiter 负责。
     */
    private void notifyExternalPtyExited() {
        if (!mUsingExternalPty) return;
        // 已被 finishIfRunning() 主动关闭（例如用户点了关闭标签），无需再上报
        if (!isRunning()) return;
        if (!mProcessExitNotified.compareAndSet(false, true)) return;

        Log.i(LOG_TAG, "external pty closed, reporting process exit");
        mMainThreadHandler.sendMessage(
                mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, 0));
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mShellPid != -1;
    }

    /** Only valid if not {@link #isRunning()}. */
    public synchronized int getExitStatus() {
        return mShellExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    public int getPid() {
        return mShellPid;
    }

    /** Returns the shell's working directory or null if it was unavailable. */
    public String getCwd() {
        if (mShellPid < 1) {
            return null;
        }
        try {
            final String cwdSymlink = String.format("/proc/%s/cwd/", mShellPid);
            String outputPath = new File(cwdSymlink).getCanonicalPath();
            String outputPathWithTrailingSlash = outputPath;
            if (!outputPath.endsWith("/")) {
                outputPathWithTrailingSlash += '/';
            }
            if (!cwdSymlink.equals(outputPathWithTrailingSlash)) {
                return outputPath;
            }
        } catch (IOException | SecurityException e) {
            mClient.logStackTraceWithMessage(LOG_TAG, "Error getting current directory", e);
        }
        return null;
    }

    private static FileDescriptor wrapFileDescriptor(int fileDescriptor, TerminalSessionClient client) {
        FileDescriptor result = new FileDescriptor();
        try {
            Field descriptorField;
            try {
                descriptorField = FileDescriptor.class.getDeclaredField("descriptor");
            } catch (NoSuchFieldException e) {
                // For desktop java:
                descriptorField = FileDescriptor.class.getDeclaredField("fd");
            }
            descriptorField.setAccessible(true);
            descriptorField.set(result, fileDescriptor);
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
            client.logStackTraceWithMessage(LOG_TAG, "Error accessing FileDescriptor#descriptor private field", e);
            System.exit(1);
        }
        return result;
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[4 * 1024];

        @Override
        public void handleMessage(Message msg) {
            int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
            }

            if (msg.what == MSG_PROCESS_EXITED) {
                int exitCode = (Integer) msg.obj;
                cleanupResources(exitCode);

                if (mAppendExitNotice) {
                    String exitDescription = "\r\n[Process completed";
                    if (exitCode > 0) {
                        // Non-zero process exit.
                        exitDescription += " (code " + exitCode + ")";
                    } else if (exitCode < 0) {
                        // Negated signal.
                        exitDescription += " (signal " + (-exitCode) + ")";
                    }
                    exitDescription += " - press Enter]";

                    byte[] bytesToWrite = exitDescription.getBytes(StandardCharsets.UTF_8);
                    mEmulator.append(bytesToWrite, bytesToWrite.length);
                    notifyScreenUpdate();
                }

                mClient.onSessionFinished(TerminalSession.this);
            }
        }

    }

}
