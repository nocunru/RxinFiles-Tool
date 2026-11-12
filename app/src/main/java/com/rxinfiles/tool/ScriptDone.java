package com.rxinfiles.tool;

/**
 * 「脚本跑完了」这条信号的协议：**借终端标题传**。
 *
 * <h2>为什么要一条专门的信号</h2>
 *
 * 脚本跑在交互式 shell 的**子进程**里（见 {@link ScriptRunner#buildCommand}），
 * 脚本结束时外壳并不会退出，所以 App 侧看不到任何「进程结束」的事件 ——
 * 屏幕上多出来的那行「脚本已结束」也只是普通文本，App 看不见。
 * 3.13.1 起终端要在脚本结束的那一刻换成**结束态**（提示 + 任何输入都退回文件管理），
 * 就必须有一条从 shell 到 App 的**带内信号**。
 *
 * <h2>为什么用 OSC 2（窗口标题），而不是去读屏幕</h2>
 *
 * 三条路都试想过，只有这条不依赖屏幕内容：
 *
 * <table>
 *   <tr><th>路子</th><th>问题</th></tr>
 *   <tr><td>扫描终端缓冲区找记号文本</td>
 *       <td>要处理滚屏、折行、清屏；脚本自己也可能打印同样的字</td></tr>
 *   <tr><td>命令尾 `exit`，靠会话结束回调</td>
 *       <td>PTY 一关就有「最后几 KB 输出被丢掉」的竞态（reader 线程还没把数据
 *           塞进队列，cleanupResources 就把队列关了）—— 输出的最后几行正是用户要看的东西</td></tr>
 *   <tr><td><b>OSC 2 写标题</b></td>
 *       <td>不占屏幕、不受滚屏影响、不会与输出内容撞车，且退出码可以顺带带上</td></tr>
 * </table>
 *
 * 终端模拟器解析到 OSC 2 只会回调一次 {@code onTitleChanged}（见
 * {@code com.termux.terminal.TerminalEmulator#doOscSetTextParameters}），
 * **一个字符都不会留在屏幕上**。
 *
 * <h2>标记长什么样</h2>
 *
 * 终端收到的原始字节：
 *
 * <pre>
 * ESC ] 2 ; RXIN-DONE:&lt;pid&gt;:&lt;退出码&gt; BEL
 * </pre>
 *
 * 其中 `pid` 只是为了让同一会话里**第二次**出现同样的标记时标题仍然「变了」
 * （终端只在标题变化时才回调），不参与解析。
 *
 * <h2>为什么整条匹配，而不是「包含即命中」</h2>
 *
 * 用户脚本自己设置标题是很常见的（`printf '\033]2;my build\007'`）。
 * 只要判据是「整条标题形如标记」，脚本哪怕真打印出这段话，也只能是**一模一样**的
 * 一条 —— 那已经不可能再是巧合了。反过来用「包含」判定，任何一个把标记当文本
 * 打印出来的脚本（比如打印自己的命令行）都会误触发结束态。
 *
 * <h2>本类必须保持纯 Java</h2>
 *
 * 一行 Android API 都不要加：{@code tools/script-check/DoneMarkCheck.java} 用它
 * 直接跑 {@code javac}/{@code java} 断言（与归档层的
 * {@code Archives}/{@code TarArchive} 等同一路数）。shell 侧真正打出来的字节由
 * {@code tools/script-check/probe-done-marker.sh} 在 mksh / dash / busybox ash
 * 三家真 shell 上验证。
 */
public final class ScriptDone {

    /** 标记前缀（紧随其后的两个数字段：pid 与退出码） */
    public static final String MARK_PREFIX = "RXIN-DONE:";

    /** OSC 引导的**字节**形态：`ESC ] 2 ;` —— 2 表示「设置窗口标题」 */
    public static final String OSC_INTRO = "\u001b]2;";

    /** OSC 结束符的字节形态 BEL（也可以用 `ESC \`，这里选 BEL，mksh/dash/ash 都能直接打出来） */
    public static final String OSC_END = "\u0007";

    /**
     * 同上的**命令文本**形态：`printf` 要的是八进制转义，不是真的控制字符。
     *
     * 命令是**文本**，要一路经过 PTY 的行规程；真塞一个 ESC 进去，壳层的行编辑
     * 会把它当成方向键序列的开头，命令本身就废了。所以命令里写 `\033`，
     * 由 printf 解释成 ESC —— 两种形态一一对应（`033` = 27 = ESC、`007` = 7 = BEL），
     * 这个对应关系由 {@code DoneMarkCheck} 逐字节核对，实际出字节由
     * {@code verify-scriptpipe.py} 在 mksh / dash / busybox ash 三家真 shell 上验证。
     */
    public static final String OSC_INTRO_PRINTF = "\\033]2;";

    /** 见 {@link #OSC_INTRO_PRINTF} */
    public static final String OSC_END_PRINTF = "\\007";

    /**
     * 「解析不出退出码」的哨兵值。
     *
     * 用哨兵而不是 null：调用方（Kotlin）拿到的是 {@code Int}，不必到处拆包；
     * 而 shell 的 `$?` 只可能是 0~255，永远不会撞上这个值。
     */
    public static final int NO_CODE = Integer.MIN_VALUE;

    /**
     * 挂在命令**最末尾**的结束标记（是「命令片段」，带前导 `"; "`）。
     *
     * <pre>
     * printf '\033]2;RXIN-DONE:%s:%s\007' "$$" "$__rxin_rc"
     * </pre>
     *
     * 必须**最后**追加：脚本的输出、退出码回显、126/127 的解释都要排在它前面 ——
     * App 收到标记就会把脚本会话收掉（连同 PTY），晚于它的输出没人看得到。
     * 这条约束由 {@code verify-scriptpipe.py} 第 5 节从源码侧钉住。
     *
     * `\033` / `\007` 交给 printf 解释成字节（POSIX 的八进制转义），不是在 Kotlin
     * 里拼控制字符 —— 命令是**文本**，一路要经过 PTY 的行规程，塞真正的 ESC
     * 只会让 shell 的行编辑把它当成方向键序列。
     */
    public static final String TAIL =
            "; printf '" + OSC_INTRO_PRINTF + MARK_PREFIX + "%s:%s" + OSC_END_PRINTF
                    + "' \"$$\" \"$__rxin_rc\"";

    private ScriptDone() {
    }

    /** 标题是不是一条**完整**的结束标记（见类注释：不做「包含」判定） */
    public static boolean isMarker(String title) {
        return parseExitCode(title) != NO_CODE;
    }

    /**
     * 从标题里取退出码；不是标记（或格式不对）返回 {@link #NO_CODE}。
     *
     * 严格匹配 `RXIN-DONE:<pid>:<退出码>`，两段都只允许十进制数字：
     * 多一个字符、少一段、带上脚本自己的文字，都判为「不是标记」。
     */
    public static int parseExitCode(String title) {
        if (title == null) return NO_CODE;
        if (!title.startsWith(MARK_PREFIX)) return NO_CODE;
        int pidStart = MARK_PREFIX.length();
        int pidEnd = title.indexOf(':', pidStart);
        if (pidEnd < 0) return NO_CODE;
        // pid 段非空且全数字（不许出现 "RXIN-DONE::0" 这种）
        if (pidEnd == pidStart) return NO_CODE;
        if (!allDigits(title, pidStart, pidEnd)) return NO_CODE;
        String code = title.substring(pidEnd + 1);
        if (code.isEmpty() || !allDigits(code, 0, code.length())) return NO_CODE;
        try {
            return Integer.parseInt(code);
        } catch (NumberFormatException e) {
            // 理论上到不了（只含数字、且长度可控），保险起见按「不是标记」处理
            return NO_CODE;
        }
    }

    /** 退出码算不算「失败」（提示条据此换颜色） */
    public static boolean isFailure(int exitCode) {
        return exitCode != NO_CODE && exitCode != 0;
    }

    private static boolean allDigits(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
}
