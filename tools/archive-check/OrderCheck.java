package com.rxinfiles.tool;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 「压缩包面板看不看得出层级」的回归断言。
 *
 * 用户反馈原话：**「你这一打开，同一级的都堆一块，你都不知道这个文件是在哪个文件夹里的」**。
 * 根因**不是样式，是排序**：`Archives.sort` 原本把「所有目录排前面、所有文件排后面」，
 * 树被拆散之后，目录行后面的第一个文件看上去就成了那个目录的孩子。实测一个 8 目的 7z：
 *
 * ```
 * zsrc/  zsrc/dirA/  zsrc/dirA/sub/  zsrc/空目录/  zsrc/dirA/a.sh  …
 *                                                 ^^^^^^^^^^^^^^ 缩进一画就在「空目录」底下
 * ```
 *
 * 这里钉死两条不变量：
 *
 * 1. 列举结果必须是**路径序**，且相邻层级只能沿祖先递增 —— 见 [isPathOrdered] 与
 *    [stepsDescendThroughAncestors]；
 * 2. 面板据此算出的**树骨架**必须自洽（[TreeRails]）—— 连接符指的方向不能骗人。
 *
 * 第 2 条能在开发机上验，是因为骨架算法刻意写成了不碰 Android API 的纯 Java。
 * 面板跑的与这里跑的是**同一份代码**，不是抄一份。
 */
public class OrderCheck {

    /** 骨架层数上限，直接取面板用的那个数 */
    private static final int MAX_LEVEL = TreeRails.DEFAULT_MAX_LEVEL;

    /** 覆盖到的夹具：四种容器 + 加密包 + 长路径包，路径序是四家共同的承诺 */
    private static final String[] FIXTURES = {
            "plain.zip",
            "gbk.zip",
            "test.tar",
            "test-longname.tar",
            "test-pax-long.tar",
            "sevenz-lzma2.7z",
            "sevenz-nonsolid.7z",
            "rar5.rar",
            "rar4.rar",
            "zipcrypto.zip",
            "zipaes256.zip",
            "zipmixed.zip",
    };

    public static void main(String[] args) throws Exception {
        Check.section("1. 列举顺序必须是路径序（四种容器都要守这条）");
        for (String name : FIXTURES) {
            File f = new File(name);
            if (!f.isFile()) {
                Check.check("夹具在场: " + name, false);
                continue;
            }
            Archives.Listing listing = Archives.list(f, null);
            Check.check(name + " · 整表按路径序（" + listing.items.size() + " 项）",
                    isPathOrdered(listing));
            Check.check(name + " · 深层条目只沿祖先递增",
                    stepsDescendThroughAncestors(listing));
            Check.check(name + " · 同一个目录下的条目连成一段",
                    siblingsStayTogether(listing));
            Check.check(name + " · 连接符方向与事实一致", connectorsAreHonest(listing));
        }

        Check.section("2. 树骨架的精确形状（拿 zsrc 那棵树当标本）");
        Archives.Listing sevenZip = Archives.list(new File("sevenz-lzma2.7z"), null);
        // 这棵树长这样（`tree` 命令的形状）：
        //   zsrc/
        //   ├─ dirA
        //   │  ├─ a.sh
        //   │  └─ sub
        //   │     └─ deep.bin
        //   ├─ top.txt
        //   ├─ 中文文件.txt
        //   └─ 空目录
        checkRails(sevenZip, "zsrc", new boolean[]{});
        checkRails(sevenZip, "zsrc/dirA", new boolean[]{true});
        checkRails(sevenZip, "zsrc/dirA/a.sh", new boolean[]{true, true});
        checkRails(sevenZip, "zsrc/dirA/sub", new boolean[]{true, false});
        checkRails(sevenZip, "zsrc/dirA/sub/deep.bin", new boolean[]{true, false, false});
        checkRails(sevenZip, "zsrc/top.txt", new boolean[]{true});
        checkRails(sevenZip, "zsrc/空目录", new boolean[]{false});

        // 同一棵树换个容器，骨架必须一模一样
        Archives.Listing rar = Archives.list(new File("rar5.rar"), null);
        Check.check("同一棵树在 rar 里骨架完全相同",
                railsOf(rar, "zsrc/dirA/sub/deep.bin") != null
                        && Arrays.equals(railsOf(rar, "zsrc/dirA/sub/deep.bin"),
                        railsOf(sevenZip, "zsrc/dirA/sub/deep.bin")));
        Check.check("同一棵树在 rar 里连接符也一致",
                Arrays.equals(railsOf(rar, "zsrc/空目录"), new boolean[]{false}));

        Check.section("3. 上级目录行不在列表里时要能识别（zip 可以不写目录条目）");
        Archives.Listing plain = Archives.list(new File("plain.zip"), null);
        Check.check("plain.zip 确实没有 dirA/ 这条目录记录",
                !hasDirectory(plain, "dirA"));
        TreeRails.Node orphan = nodeOf(plain, "dirA/a.sh");
        Check.check("dirA/a.sh 被判为「归属断头」: " + describe(orphan),
                orphan != null && orphan.parentMissing && orphan.needsParentHint());
        Check.check("断头时给出的上级路径是 dirA/",
                orphan != null && "dirA/".equals(orphan.parent));
        TreeRails.Node orphanDeep = nodeOf(plain, "dirA/sub/deep.bin");
        Check.check("更深的断头条目同样报出来: " + describe(orphanDeep),
                orphanDeep != null && orphanDeep.parentMissing && orphanDeep.needsParentHint());
        // 「空目录」是这条包里唯一真实存在的目录条目
        TreeRails.Node realDir = nodeOf(plain, "空目录");
        Check.check("真实存在的目录行不算断头",
                realDir != null && !realDir.parentMissing && !realDir.needsParentHint());

        // 断头包里最容易画错的一处：a.sh 后面还跟着 dirA/ 开头的 deep.bin，
        // 所以 a.sh **不是** dirA 的最后一个 —— 判成最后一个的话，
        // 它会画成 └，而 deep.bin 缩进更深，看着就像 deep.bin 是 a.sh 的孩子。
        Check.check("断头时仍看得出 dirA 这棵子树还没走完（a.sh 是 ├ 不是 └）",
                Arrays.equals(railsOf(plain, "dirA/a.sh"), new boolean[]{true}));
        Check.check("那条竖线正好接到后面同属 dirA 的条目上",
                Arrays.equals(railsOf(plain, "dirA/sub/deep.bin"),
                        new boolean[]{true, false}));
        Check.check("根级文件不给连接符",
                Arrays.equals(railsOf(plain, "top.txt"), new boolean[]{}));

        Check.section("4. 层级太深时封顶，但归属信息不能丢");
        Archives.Listing deepTar = Archives.list(new File("test.tar"), null);
        TreeRails.Node deepNode = nodeOf(deepTar,
                "payload/verylongdirectoryname00/verylongdirectoryname01/"
                        + "verylongdirectoryname02/verylongdirectoryname03/"
                        + "verylongdirectoryname04/verylongdirectoryname05/"
                        + "long_filename_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                        + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.txt");
        Check.check("深到第 7 层的条目被判定为截断: " + describe(deepNode),
                deepNode != null && deepNode.truncated);
        Check.check("截断后仍然给出「在 xx/ 里」: "
                        + (deepNode == null ? "(没找到)" : deepNode.parent),
                deepNode != null && deepNode.needsParentHint());
        Check.check("截断的层级只展开到上限（" + MAX_LEVEL + " 格）",
                deepNode != null && deepNode.rails.length == MAX_LEVEL);
        Check.check("截断的行不画线（画出来也对不上位置）",
                deepNode != null && !anyTrue(deepNode.rails));
        Check.check("浅的条目不受封顶影响",
                nodeOf(deepTar, "payload/dirA/a.sh") != null
                        && !nodeOf(deepTar, "payload/dirA/a.sh").truncated);

        Check.section("5. 边界：空表、单条目、根级只有一条目且带子树");
        Check.check("空列表给出空骨架", TreeRails.compute(new ArrayList<String>(),
                new boolean[0], MAX_LEVEL).isEmpty());

        List<String> solo = Arrays.asList("only.txt");
        List<TreeRails.Node> soloNodes = TreeRails.compute(solo, new boolean[]{false}, MAX_LEVEL);
        Check.check("单条目：没有层级、也不需要补上级",
                soloNodes.size() == 1 && soloNodes.get(0).level == 0
                        && !soloNodes.get(0).needsParentHint());

        // 这一条正是单调栈那个判据的靶子：根级只有一个条目，而它带着子树。
        // 用「下一行是否更深」去判会误判成「还有兄弟」，于是整棵树每行都多一条通到底的竖线。
        List<String> single = Arrays.asList("zsrc/", "zsrc/a.txt");
        List<TreeRails.Node> singleNodes = TreeRails.compute(single,
                new boolean[]{true, false}, MAX_LEVEL);
        Check.check("根级唯一且有子树 → 根行是最后一个（不画延续线）",
                Arrays.equals(singleNodes.get(0).rails, new boolean[]{}));
        Check.check("它的子项连接符是 └（最后一个），不是 ├",
                Arrays.equals(singleNodes.get(1).rails, new boolean[]{false}));

        //   a/
        //   ├─ x.txt      ← 同级后面还有 y.txt，竖线通到行底
        //   └─ y.txt      ← 同级最后一个，半行竖线再拐弯
        //   b/
        //   └─ z.txt
        List<String> siblings = Arrays.asList("a/", "a/x.txt", "a/y.txt", "b/", "b/z.txt");
        List<TreeRails.Node> sibNodes = TreeRails.compute(siblings,
                new boolean[]{true, false, false, true, false}, MAX_LEVEL);
        Check.check("同级后面还有兄弟 → 连接符是 ├（竖线通到底）",
                Arrays.equals(sibNodes.get(1).rails, new boolean[]{true}));
        Check.check("同级最后一个 → 连接符是 └（只画半行）",
                Arrays.equals(sibNodes.get(2).rails, new boolean[]{false}));
        Check.check("换一个顶层目录，它的子项同样算得出 └",
                Arrays.equals(sibNodes.get(4).rails, new boolean[]{false}));
        Check.check("根级条目自己不给连接符（没有上级就没有可拐的方向）",
                sibNodes.get(0).rails.length == 0 && sibNodes.get(3).rails.length == 0);

        //   a/
        //      └─ x/
        //         └─ p.txt      ← 上面两层都是各自同级的最后一个，一路不画延续线
        //   b/
        List<String> nested = Arrays.asList("a/", "a/x/", "a/x/p.txt", "b/");
        List<TreeRails.Node> nestedNodes = TreeRails.compute(nested,
                new boolean[]{true, true, false, true}, MAX_LEVEL);
        Check.check("一路都是最后一个时，里面那条轨道不画多余的竖线",
                Arrays.equals(nestedNodes.get(2).rails, new boolean[]{false, false}));

        Check.section("6. 目录名带不带尾斜杠都要归一");
        List<String> noSlash = Arrays.asList("a", "a/b", "a/b/c.txt");
        List<TreeRails.Node> noSlashNodes = TreeRails.compute(noSlash,
                new boolean[]{true, true, false}, MAX_LEVEL);
        Check.check("不带尾斜杠的目录照样算出层级",
                noSlashNodes.get(2).depth == 2 && noSlashNodes.get(2).rails.length == 2);
        Check.check("parentOf 对两种写法都给同一个结果",
                "a/b/".equals(TreeRails.parentOf("a/b/c.txt"))
                        && "a/b/".equals(TreeRails.parentOf("a/b/c.txt/")));

        Check.finish();
    }

    // ------------------------------------------------------------ 列表级不变量

    /**
     * 整表是不是按路径序：父目录紧挨着它自己的后代。
     *
     * 判据是**字典序**（目录补尾斜杠）—— 与实现的排序规则独立无关，
     * 它是「路径序」这个要求的定义。
     */
    private static boolean isPathOrdered(Archives.Listing listing) {
        for (int i = 0; i + 1 < listing.items.size(); i++) {
            String a = sortKey(listing.items.get(i));
            String b = sortKey(listing.items.get(i + 1));
            if (a.compareToIgnoreCase(b) > 0) {
                Check.info("逆序: [" + listing.items.get(i).name
                        + "] 排在了 [" + listing.items.get(i + 1).name + "] 前面");
                return false;
            }
        }
        return true;
    }

    /**
     * 相邻两行里，**上一行是目录**且**层级变深**时，下一行必须是它的后代。
     *
     * 这条能抓住旧的「目录在前 / 文件在后」分组：那种顺序里，
     * `zsrc/空目录`（深度 1，目录）后面直接跟 `zsrc/dirA/a.sh`（深度 2），
     * 层级涨了一级，可后者根本不是前者的孩子。
     *
     * 反过来，上一行是**文件**时不做要求：`dirA/a.sh` 后面跟
     * `dirA/sub/deep.bin` 是合法的（深度从 1 到 2），它们只是共享目录前缀 ——
     * 而目录条目缺失时列表本来就是这样（see plain.zip），不该被误判成乱序。
     */
    private static boolean stepsDescendThroughAncestors(Archives.Listing listing) {
        List<Archives.Item> items = listing.items;
        for (int i = 0; i + 1 < items.size(); i++) {
            if (!items.get(i).directory) {
                continue;
            }
            int here = TreeRails.depthOf(items.get(i).name);
            int next = TreeRails.depthOf(items.get(i + 1).name);
            if (next <= here) {
                continue;
            }
            if (!items.get(i + 1).name.startsWith(sortKey(items.get(i)))) {
                Check.info("[" + items.get(i + 1).name + "] 比上一行深，却不是 ["
                        + items.get(i).name + "] 的后代");
                return false;
            }
        }
        return true;
    }

    /**
     * 同一个父目录下的条目必须在列表里**连成一段**，中间不能夹进别的分支。
     *
     * 这正是用户看到的问题的反面：旧排序把「所有目录」和「所有文件」分成两大块，
     * 于是 `zsrc/dirA/a.sh` 和 `zsrc/top.txt` 这种不同父目录的东西挨在一起、
     * 缩进还一样 ——「你都不知道这个文件是在哪个文件夹里的」。
     *
     * 根级不查：`a/`、`b.txt`、`c/` 这几个根级条目本来就会被 `a/` 的子树隔开。
     */
    private static boolean siblingsStayTogether(Archives.Listing listing) {
        List<Archives.Item> items = listing.items;
        java.util.Map<String, Integer> lastSeen = new java.util.HashMap<String, Integer>();
        for (int i = 0; i < items.size(); i++) {
            String parent = TreeRails.parentOf(items.get(i).name);
            if (parent.isEmpty()) {
                continue;
            }
            Integer prev = lastSeen.get(parent);
            if (prev != null) {
                for (int k = prev + 1; k < i; k++) {
                    if (!items.get(k).name.startsWith(parent)) {
                        Check.info("父目录 [" + parent + "] 的子项被拆开了："
                                + "[" + items.get(k).name + "] 夹在中间");
                        return false;
                    }
                }
            }
            lastSeen.put(parent, i);
        }
        return true;
    }

    /**
     * 连接符指的方向必须和事实一致：`rails` 最后一项为 true 表示「后面还有兄弟」。
     *
     * 用最笨的线性扫描验，不碰骨架里那套结构 —— 万一将来换成别的算法，
     * 这条还能独立地继续盯着。
     */
    private static boolean connectorsAreHonest(Archives.Listing listing) {
        List<String> names = namesOf(listing);
        List<TreeRails.Node> nodes = TreeRails.compute(names, dirsOf(listing), MAX_LEVEL);
        for (int i = 0; i < names.size(); i++) {
            TreeRails.Node node = nodes.get(i);
            if (node.truncated || node.level == 0) {
                continue;
            }
            final String parent = TreeRails.parentOf(names.get(i));
            final String own = TreeRails.trimTail(names.get(i)) + "/";
            boolean hasSibling = false;
            for (int j = i + 1; j < names.size(); j++) {
                if (TreeRails.depthOf(names.get(j)) < node.depth) {
                    break;
                }
                String later = names.get(j);
                if (later.startsWith(parent) && !later.startsWith(own)) {
                    hasSibling = true;
                    break;
                }
            }
            if (node.rails[node.level - 1] != hasSibling) {
                Check.info(names.get(i) + " 的连接符说"
                        + (node.rails[node.level - 1] ? "「还有兄弟」" : "「是最后一个」")
                        + "，但实际" + (hasSibling ? "还有" : "没有"));
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------ 小工具

    private static String sortKey(Archives.Item item) {
        if (!item.directory || item.name.endsWith("/")) {
            return item.name;
        }
        return item.name + "/";
    }

    private static List<String> namesOf(Archives.Listing listing) {
        List<String> names = new ArrayList<>(listing.items.size());
        for (Archives.Item item : listing.items) {
            names.add(item.name);
        }
        return names;
    }

    private static boolean[] dirsOf(Archives.Listing listing) {
        boolean[] dirs = new boolean[listing.items.size()];
        for (int i = 0; i < dirs.length; i++) {
            dirs[i] = listing.items.get(i).directory;
        }
        return dirs;
    }

    private static boolean hasDirectory(Archives.Listing listing, String name) {
        for (Archives.Item item : listing.items) {
            if (item.directory && TreeRails.trimTail(item.name).equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static TreeRails.Node nodeOf(Archives.Listing listing, String name) {
        List<String> names = namesOf(listing);
        List<TreeRails.Node> nodes = TreeRails.compute(names, dirsOf(listing), MAX_LEVEL);
        for (int i = 0; i < names.size(); i++) {
            if (TreeRails.trimTail(names.get(i)).equals(name)) {
                return nodes.get(i);
            }
        }
        return null;
    }

    private static boolean[] railsOf(Archives.Listing listing, String name) {
        TreeRails.Node node = nodeOf(listing, name);
        return node == null ? null : node.rails;
    }

    private static void checkRails(Archives.Listing listing, String name, boolean[] want) {
        boolean[] got = railsOf(listing, name);
        boolean ok = got != null && Arrays.equals(got, want);
        Check.check("骨架 · " + name + " → " + Arrays.toString(want), ok);
        if (!ok) {
            Check.info("实测 " + (got == null ? "(列表里没有这条)" : Arrays.toString(got)));
        }
    }

    private static String describe(TreeRails.Node node) {
        if (node == null) {
            return "(没找到)";
        }
        return "depth=" + node.depth + " level=" + node.level
                + " rails=" + Arrays.toString(node.rails)
                + " parent=" + node.parent
                + (node.parentMissing ? " [断头]" : "")
                + (node.truncated ? " [截断]" : "");
    }

    private static boolean anyTrue(boolean[] values) {
        for (boolean v : values) {
            if (v) {
                return true;
            }
        }
        return false;
    }
}
