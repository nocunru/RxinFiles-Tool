package com.rxinfiles.tool;

import java.util.Locale;

/**
 * 文件排序判据（纯 Java，零 Android API —— 可被 {@code javac}/{@code java} 直接断言）。
 *
 * <h2>为什么要单独抽出来</h2>
 *
 * 排序此前**只写在 {@code FileRepository.listWithJava} 里一处**：
 *
 * <pre>{@code
 * .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
 * }</pre>
 *
 * 于是有两个问题：
 *
 * <ol>
 *   <li><b>shell 通道完全没排序</b> —— {@code listWithShell} 直接按 {@code ls -la} 的输出
 *       顺序建列表。也就是说「普通身份」与「ROOT/ADB 身份」看到的**排列不一样**，
 *       而这条路只有在真机上换身份才看得出来；</li>
 *   <li><b>字符串排序不是人眼排序</b> —— {@code file10} 排在 {@code file2} 前面，
 *       而用户看到「10 &lt; 2」只会觉得是乱的。名字里的数字要按**数值**比。</li>
 * </ol>
 *
 * 所以把判据归拢到这里，两条通道**共用同一份**，并且能被离线断言钉住。
 */
public final class FileSort {

    /** 排序维度。 */
    public enum Key { NAME, SIZE, TIME, TYPE }

    /** 升降序。 */
    public enum Order { ASC, DESC }

    /** 默认：名称、升序、目录优先 —— 与绝大多数文件管理器一致。 */
    public static final Key DEFAULT_KEY = Key.NAME;
    public static final Order DEFAULT_ORDER = Order.ASC;
    public static final boolean DEFAULT_DIRS_FIRST = true;

    private FileSort() {
    }

    // ------------------------------------------------------------------ 自然排序

    /** ASCII 数字判定。刻意不用 {@code Character.isDigit} —— 它会把全角与阿拉伯数字也算进来。 */
    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * 数字感知的自然比较。
     *
     * <p>逐字符走，遇到**连续数字段**就整体按数值比：先比「去掉前导零后的位数」，位数相同再逐位比。
     * 这样 {@code file2} &lt; {@code file10}（字符串比较会得出相反结论），
     * 而 {@code img007} 与 {@code img7} 数值相等 —— 此时不急着判相等，而是**继续往下比**，
     * 让名字后面真正的差异说话。
     *
     * <p>非数字字符按 {@link Character#toLowerCase} 比较（它是 **locale 无关**的；
     * 原来的 {@code String.lowercase()} 走默认 locale，土耳其语环境下 {@code I} 会变成 {@code ı}，
     * 排序结果随系统语言变化）。
     *
     * <p>两边都走完时，**长的排后面**（{@code a} &lt; {@code ab}）。
     */
    public static int naturalCompare(String a, String b) {
        if (a == null) {
            a = "";
        }
        if (b == null) {
            b = "";
        }
        int i = 0;
        int j = 0;
        int la = a.length();
        int lb = b.length();

        while (i < la && j < lb) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);

            if (isAsciiDigit(ca) && isAsciiDigit(cb)) {
                int si = i;
                int sj = j;
                while (si < la && a.charAt(si) == '0') {
                    si++;
                }
                while (sj < lb && b.charAt(sj) == '0') {
                    sj++;
                }
                int ei = si;
                int ej = sj;
                while (ei < la && isAsciiDigit(a.charAt(ei))) {
                    ei++;
                }
                while (ej < lb && isAsciiDigit(b.charAt(ej))) {
                    ej++;
                }

                int na = ei - si;
                int nb = ej - sj;
                if (na != nb) {
                    // 位数多的那个数更大；全是零时 na = nb = 0，直接落到下面继续比
                    return na < nb ? -1 : 1;
                }
                for (int k = 0; k < na; k++) {
                    char x = a.charAt(si + k);
                    char y = b.charAt(sj + k);
                    if (x != y) {
                        return x < y ? -1 : 1;
                    }
                }
                // 数值相等（"007" vs "7"）→ 继续比后面，不在这里定胜负
                i = ei;
                j = ej;
            } else {
                char x = Character.toLowerCase(ca);
                char y = Character.toLowerCase(cb);
                if (x != y) {
                    return x < y ? -1 : 1;
                }
                i++;
                j++;
            }
        }
        return (la - i) - (lb - j);
    }

    // ------------------------------------------------------------------ 类型键

    /**
     * 「按类型」排序用的键：扩展名的小写形式；没有扩展名时返回空串。
     *
     * <p>口径与 {@code FileRepository.extOf} 一致：**点开头**（{@code .gitignore}）
     * 与**末尾是点**（{@code foo.}）都视为没有扩展名。空串在升序里排最前
     * （无扩展名的文件打头），这与「按类型归类」的直觉一致。
     *
     * <p>{@link Locale#ROOT} 是刻意的：{@code toLowerCase()} 不带 locale 时，
     * 土耳其语环境会把 {@code I} 变成 {@code ı}，于是同样的文件在不同手机上排序不同。
     */
    public static String typeKey(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ 主判据

    /**
     * 比较两个条目。
     *
     * <p>判据顺序：**目录优先** → **主键** → **升降序** → **名称兜底**。
     *
     * <p>最后那步兜底是必要的：按大小或时间排序时，大量文件的 size 都是 0（空文件）
     * 或 modified 相同（同一次解压出来的），没有兜底的话它们的相对顺序**每次刷新都可能变**，
     * 看着像列表在自己跳动。
     *
     * @param aDir 是否目录；{@code dirsFirst} 为真时目录整体排前
     * @param aSize 字节数；目录请传 0（目录没有有意义的「大小」）
     * @param aTime 最后修改时间（毫秒）
     */
    public static int compare(String aName, boolean aDir, long aSize, long aTime,
                              String bName, boolean bDir, long bSize, long bTime,
                              Key key, Order order, boolean dirsFirst) {
        if (dirsFirst && aDir != bDir) {
            return aDir ? -1 : 1;
        }

        int c;
        switch (key == null ? Key.NAME : key) {
            case SIZE:
                c = Long.compare(aSize, bSize);
                break;
            case TIME:
                c = Long.compare(aTime, bTime);
                break;
            case TYPE:
                c = naturalCompare(typeKey(aName), typeKey(bName));
                break;
            case NAME:
            default:
                c = naturalCompare(aName, bName);
                break;
        }

        if (order == Order.DESC) {
            c = -c;
        }

        // 主键打平 → 用名称兜底，保证顺序稳定（同名不可能，所以这里一定分得出胜负）
        if (c == 0 && key != Key.NAME && key != null) {
            c = naturalCompare(aName, bName);
            if (order == Order.DESC) {
                c = -c;
            }
        }
        return c;
    }

    // ------------------------------------------------------------------ 持久化 / 展示

    /** 从持久化的字符串读回维度；不认识（含 null）一律回默认。 */
    public static Key keyOf(String name) {
        if (name != null) {
            for (Key k : Key.values()) {
                if (k.name().equals(name)) {
                    return k;
                }
            }
        }
        return DEFAULT_KEY;
    }

    /** 从持久化的字符串读回升降序；不认识（含 null）一律回默认。 */
    public static Order orderOf(String name) {
        if (name != null) {
            for (Order o : Order.values()) {
                if (o.name().equals(name)) {
                    return o;
                }
            }
        }
        return DEFAULT_ORDER;
    }

    /** 维度的中文名（面板里显示）。 */
    public static String labelOf(Key key) {
        switch (key == null ? Key.NAME : key) {
            case SIZE:
                return "大小";
            case TIME:
                return "修改时间";
            case TYPE:
                return "类型";
            case NAME:
            default:
                return "名称";
        }
    }

    /** 升 / 降的中文名。 */
    public static String orderLabelOf(Order order) {
        return order == Order.DESC ? "降序" : "升序";
    }
}
