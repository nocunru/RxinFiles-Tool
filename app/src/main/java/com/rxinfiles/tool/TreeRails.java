package com.rxinfiles.tool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 压缩包条目列表的**树形骨架**计算。
 *
 * 面板拿它画层级引导线；开发机上的断言程序拿它验结构 ——
 * 两边跑的是**同一份代码**（见 `tools/archive-check/OrderCheck.java`）。
 * 所以这里刻意不碰任何 Android API：纯逻辑才测得动。
 *
 * **前提：传入的名字必须是路径序**（父目录紧挨着它自己的后代）。
 * 这个前提由 [Archives] 的排序保证，也是这里全部推断的基础 ——
 * 「下一行不比自己更深」之所以等价于「自己的子树到此为止」，靠的就是它。
 */
public final class TreeRails {

    /**
     * 面板用的默认上限：最多画几级。
     *
     * 放在这里而不是 `ArchiveSheet` 里，是为了让断言程序与面板**引用同一个数** ——
     * 各写一份的话，改了这边忘了那边，断言会对着一个过期的上限继续绿。
     */
    public static final int DEFAULT_MAX_LEVEL = 6;

    private TreeRails() {
    }

    /** 一行算好的层级信息 */
    public static final class Node {
        /** 真实层级：`a/b/c` 是 2 */
        public final int depth;
        /** 实际画几格（按 maxLevel 截断之后） */
        public final int level;
        /**
         * 每一格画不画「通到行底的竖线」，长度 == level。
         *
         * 前 `level - 1` 项说的是**祖先那一层后面还有没有兄弟**；
         * 最后一项说的就是本行自己（`true` = 还有兄弟 → 通到底，`false` = 最后一个 → 只画半行）。
         * 两种语义正好同形，所以共用一个数组。
         */
        public final boolean[] rails;
        /** 直接上级目录（带尾斜杠）；没有上级就是 "" */
        public final String parent;
        /** 上级目录压根不在列表里 —— 有些 zip 不写目录条目，树在这儿断头 */
        public final boolean parentMissing;
        /** 层级超出上限，缩进到头了 */
        public final boolean truncated;

        Node(int depth, int level, boolean[] rails, String parent,
             boolean parentMissing, boolean truncated) {
            this.depth = depth;
            this.level = level;
            this.rails = rails;
            this.parent = parent;
            this.parentMissing = parentMissing;
            this.truncated = truncated;
        }

        /**
         * 光看引导线认不出归属的两种情况：缩进到头了、或者上级目录行根本不在列表里。
         * 这时调用方要改用文字补一句「在 xx/ 里」，否则用户就又回到「不知道在哪个文件夹」。
         */
        public boolean needsParentHint() {
            return parent.length() > 0 && (truncated || parentMissing);
        }
    }

    /**
     * @param names       路径序的条目名
     * @param directories 与 names 等长：该条目是不是目录
     * @param maxLevel    最多画几级（超出的行不再缩进，只标记 truncated）
     */
    public static List<Node> compute(List<String> names, boolean[] directories, int maxLevel) {
        final int count = names.size();
        final List<Node> nodes = new ArrayList<Node>(count);
        if (count == 0) {
            return nodes;
        }

        // 列表里真实存在的目录条目 —— 判断某个条目的归属会不会断头
        final Set<String> directoryNames = new HashSet<String>();
        for (int i = 0; i < count; i++) {
            if (directories[i]) {
                directoryNames.add(trimTail(names.get(i)) + "/");
            }
        }

        final int[] depths = new int[count];
        for (int i = 0; i < count; i++) {
            depths[i] = depthOf(names.get(i));
        }

        // 每一行的上级目录与「自己这棵子树的前缀」，后面判兄弟关系要用
        final String[] parents = new String[count];
        final String[] ownPrefix = new String[count];
        for (int i = 0; i < count; i++) {
            parents[i] = parentOf(names.get(i));
            ownPrefix[i] = trimTail(names.get(i)) + "/";
        }

        // 本行是不是同级的最后一个 —— **按名字前缀判，不看深度**。
        //
        // 这里踩过一次：最早用的是「右边第一个不比它更深的行」（单调栈），
        // 在「每个目录条目都齐全」的包上完全正确，可一遇上不写目录条目的 zip
        //（`plain.zip` 就是：只有 `dirA/a.sh`，没有 `dirA/`）就露馅 ——
        //
        //     dirA/a.sh          ← 被判成「dirA 下的最后一个」，画成 └
        //     dirA/sub/deep.bin  ← 却比它更深，看着像 a.sh 的孩子
        //
        // 改成按前缀判：**后面还有没有「同属本行父目录、又不是本行后代」的条目**。
        // a.sh 于是能发现后面还有 `dirA/` 开头的 deep.bin，老实画成 ├，
        // 那条竖线刚好把同属 dirA 的两项连起来。
        final boolean[] lastSibling = new boolean[count];
        for (int i = 0; i < count; i++) {
            boolean hasSibling = false;
            for (int j = i + 1; j < count; j++) {
                if (depths[j] < depths[i]) {
                    // 已经越过本行所在的层，后面不可能再有兄弟
                    break;
                }
                final String later = names.get(j);
                if (later.startsWith(parents[i]) && !later.startsWith(ownPrefix[i])) {
                    hasSibling = true;
                    break;
                }
            }
            lastSibling[i] = !hasSibling;
        }

        // ancestor[k] = 最近一个深度为 k 的行；-1 表示这一层没有对应的行
        final int[] ancestor = new int[count + 1];
        Arrays.fill(ancestor, -1);

        for (int i = 0; i < count; i++) {
            final int depth = depths[i];
            final boolean truncated = depth > maxLevel;
            final int level = truncated ? maxLevel : depth;
            final boolean[] rails = new boolean[level];
            // 缩进已经封顶时线就不再画了：这时候画出来的是「靠近根」的那几层，
            // 跟本行实际所在的位置对不上，还不如干净地只留缩进 + 文字说明。
            if (!truncated) {
                for (int k = 0; k < level; k++) {
                    if (k == level - 1) {
                        rails[k] = !lastSibling[i];
                    } else {
                        // 第 k 列说的是「深度 k+1 的那个祖先还有没有兄弟」，
                        // 也就是本行**上一级**目录的延续。
                        //
                        // 差一个下标不是笔误：连接符自己占掉了最后一列 ——
                        // 照着 `tree` 的输出看一遍就清楚了。
                        // 取不到祖先行就不画，宁缺一格也不画一条指错方向的线。
                        final int a = ancestor[k + 1];
                        rails[k] = a >= 0 && !lastSibling[a];
                    }
                }
            }
            if (depth < ancestor.length) {
                ancestor[depth] = i;
            }

            final String parent = parentOf(names.get(i));
            final boolean parentMissing = parent.length() > 0 && !directoryNames.contains(parent);
            nodes.add(new Node(depth, level, rails, parent, parentMissing, truncated));
        }
        return nodes;
    }

    /** `a/b/c` 与 `a/b/c/` 都要能取到 `a/b/c` */
    public static String trimTail(String name) {
        int end = name.length();
        while (end > 0 && name.charAt(end - 1) == '/') {
            end--;
        }
        return name.substring(0, end);
    }

    /** 相对根目录的层级：`a/b/c` 是 2 */
    public static int depthOf(String name) {
        final String trimmed = trimTail(name);
        int n = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) == '/') {
                n++;
            }
        }
        return n;
    }

    /** 直接上级目录，带尾斜杠；没有上级返回空串 */
    public static String parentOf(String name) {
        final String trimmed = trimTail(name);
        final int slash = trimmed.lastIndexOf('/');
        return slash < 0 ? "" : trimmed.substring(0, slash + 1);
    }
}
