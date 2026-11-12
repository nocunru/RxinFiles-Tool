package com.rxinfiles.tool;

/**
 * {@link ScriptDone}（脚本结束标记协议）的断言。
 *
 * 为什么要有它：结束标记是**跨进程、跨语言**的一小段协议 —— 一端是 shell 里的
 * `printf`，另一端是 Kotlin 里的解析。两端各错一点、连起来还能「看起来能用」，
 * 真要命的是错的那半边正好是没人测的那半边。所以这里把 Java 这一半钉死，
 * shell 那一半（真出什么字节）由 {@code verify-scriptpipe.py} 第 9 节在
 * mksh / dash / busybox ash 三家真 shell 上跑出来，两边共同认定同一个格式串。
 *
 * 纯 Java、无 Android API：`tools/script-check/run-java-check.sh` 直接编了就跑。
 * （与归档层的 {@code tools/archive-check/*Check.java} 同一路数。）
 */
public final class DoneMarkCheck {

    private static int total = 0;
    private static int failures = 0;

    private DoneMarkCheck() {
    }

    public static void main(String[] args) {
        section("1. 命令尾片段：发给 shell 的那段文本");
        tailChecks();

        section("2. 两种形态对得上（命令文本 ↔ 真正要发出去的字节）");
        formChecks();

        section("3. 解析：整条匹配才算标记");
        parseChecks();

        section("4. 退出码语义");
        codeChecks();

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL OK —— " + total + " 项断言全过");
        } else {
            System.out.println("FAILURES: " + failures + " / " + total);
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ 各节

    private static void tailChecks() {
        String tail = ScriptDone.TAIL;

        check("TAIL 由四个常量拼出来（OSC 引导 / 标记前缀 / 结束符都在里面）",
                tail.contains(ScriptDone.OSC_INTRO_PRINTF)
                        && tail.contains(ScriptDone.MARK_PREFIX)
                        && tail.contains(ScriptDone.OSC_END_PRINTF));

        // 命令是**文本**：真塞一个 ESC 进命令行，壳层的行编辑会把它当方向键序列，
        // 整条命令作废。所以 TAIL 里只许有可打印字符 + printf 的转义写法。
        boolean hasControl = false;
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                hasControl = true;
                break;
            }
        }
        check("TAIL 里不含真的控制字符（ESC 只能以 \\033 的形式出现）", !hasControl);

        check("printf 的格式串恰好两个 %s", countOf(tail, "%s") == 2);
        check("两个实参是 $$ 与 $__rxin_rc（pid 只为让标题「变了」，退出码才是要报的）",
                tail.contains("\"$$\"") && tail.contains("\"$__rxin_rc\""));
        check("以分号开头：TAIL 是直接接在命令尾上的片段",
                tail.startsWith("; "));
        // 退出码必须**先存变量**：printf 自己会覆盖 $?（3.9.2 的教训）
        check("引用的是 __rxin_rc 而不是 $?",
                !tail.contains("$?") && tail.contains("$__rxin_rc"));
    }

    private static void formChecks() {
        // 把两半钉在一起的那一条：命令文本里的八进制转义，解出来必须**逐字节**
        // 等于我们要发出去的字节。任何一边改了写法而另一边没跟，这里当场失败。
        check("OSC 引导：\\033(=27=ESC) + ]2; 与字节形态一致",
                octalOf(ScriptDone.OSC_INTRO_PRINTF) == (int) ScriptDone.OSC_INTRO.charAt(0)
                        && ScriptDone.OSC_INTRO.substring(1).equals("]2;"));
        check("OSC 结束符：\\007(=7=BEL) 与字节形态一致",
                octalOf(ScriptDone.OSC_END_PRINTF) == (int) ScriptDone.OSC_END.charAt(0));
        check("ESC 就是 0x1b、BEL 就是 0x07（不是别的控制字符）",
                ScriptDone.OSC_INTRO.charAt(0) == 0x1b && ScriptDone.OSC_END.charAt(0) == 0x07);
        check("标记前缀以冒号结尾（后面紧跟 pid 段，格式 RXIN-DONE:<pid>:<rc>）",
                ScriptDone.MARK_PREFIX.endsWith(":"));
    }

    private static void parseChecks() {
        check("正常标记解析出退出码",
                ScriptDone.parseExitCode(ScriptDone.MARK_PREFIX + "12345:0") == 0
                        && ScriptDone.parseExitCode(ScriptDone.MARK_PREFIX + "987:7") == 7);

        // 下面这些都必须**判成「不是标记」**。凡是放宽一处，用户脚本自己打印的
        // 一行字就可能把终端推进结束态（真正的触发器只能是 shell 写的那条 OSC）。
        String[] notMarkers = {
                null,
                "",
                ScriptDone.MARK_PREFIX,
                ScriptDone.MARK_PREFIX + "12345",          // 少一段
                ScriptDone.MARK_PREFIX + ":0",             // pid 段是空的
                ScriptDone.MARK_PREFIX + "abc:0",          // pid 不是数字
                ScriptDone.MARK_PREFIX + "12a:0",
                "x" + ScriptDone.MARK_PREFIX + "12:0",     // 前面多了字符就不认
                " build " + ScriptDone.MARK_PREFIX + "12:0",
                ScriptDone.MARK_PREFIX + "12:",            // 退出码是空的
                ScriptDone.MARK_PREFIX + "12:0 ",          // 尾随空格
                ScriptDone.MARK_PREFIX + "12:+1",
                ScriptDone.MARK_PREFIX + "12:-1",
                "shell", "t1", "root@localhost",
                ScriptDone.OSC_INTRO + ScriptDone.MARK_PREFIX + "12:0",  // 带 ESC 也不认
        };
        for (String s : notMarkers) {
            check("不认作标记：" + describe(s),
                    !ScriptDone.isMarker(s) && ScriptDone.parseExitCode(s) == ScriptDone.NO_CODE);
        }

        // pid 有多长都不影响解析（解析的是「第二段冒号后面的那段数字」）
        check("超长 pid 不影响解析",
                ScriptDone.parseExitCode(ScriptDone.MARK_PREFIX + "2147483647:255") == 255);
        check("解析出来的是**退出码**，不是 pid（两段顺序不能反）",
                ScriptDone.parseExitCode(ScriptDone.MARK_PREFIX + "1:2") == 2);
    }

    private static void codeChecks() {
        check("0 算成功", !ScriptDone.isFailure(0));
        check("非 0 算失败（126 / 127 这类环境问题也在内）",
                ScriptDone.isFailure(1) && ScriptDone.isFailure(126)
                        && ScriptDone.isFailure(127) && ScriptDone.isFailure(255));
        check("「没有退出码」不被当成失败（提示条不该因为兜底路径变红）",
                !ScriptDone.isFailure(ScriptDone.NO_CODE));
        // shell 的 $? 只可能是 0~255，哨兵必须落在外面，否则会把某个真实退出码
        // 当成「没有退出码」
        check("哨兵值落在 0~255 之外（与真实退出码不可能撞车）",
                ScriptDone.NO_CODE < 0 || ScriptDone.NO_CODE > 255);
    }

    // ------------------------------------------------------------------ 小工具

    private static int octalOf(String printfForm) {
        // "\033]2;" → 27；只取 "\0" 后面连续的数字
        int i = 1;
        int value = 0;
        while (i < printfForm.length() && printfForm.charAt(i) >= '0' && printfForm.charAt(i) <= '7') {
            value = value * 8 + (printfForm.charAt(i) - '0');
            i++;
        }
        return i > 1 ? value : -1;
    }

    private static int countOf(String haystack, String needle) {
        int n = 0, from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) return n;
            n++;
            from = at + needle.length();
        }
    }

    private static String describe(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                sb.append(String.format("\\x%02x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static void section(String title) {
        System.out.println("\n== " + title);
    }

    private static void check(String name, boolean ok) {
        total++;
        if (!ok) failures++;
        System.out.println((ok ? "  [ok]   " : "  [FAIL] ") + name);
    }
}
