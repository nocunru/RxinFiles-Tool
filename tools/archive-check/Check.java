package com.rxinfiles.tool;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 归档校验的公共断言工具。
 *
 * 刻意做得「笨」：所有期望值都**从真实目录树读出来**（[names]），不手抄一份清单。
 * 手抄的清单会在夹具变化时悄悄过期，断言照样绿 —— 那是假绿。
 */
public final class Check {

    private static int total = 0;
    private static int failures = 0;

    private Check() {
    }

    public static void check(String name, boolean ok) {
        total++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  [ok]   " : "  [FAIL] ") + name);
    }

    /** 打一条纯信息（不计入断言） */
    public static void info(String text) {
        System.out.println("  ...    " + text);
    }

    /** 起一个小节 */
    public static void section(String title) {
        System.out.println("\n== " + title);
    }

    public static void note(String text) {
        System.out.println("  !!! " + text);
    }

    /**
     * 相对路径清单，目录以 `d ` 开头、文件以 `f ` 开头，各自带 `/` 分隔的相对路径。
     * 这就是「从源码（真实文件系统）读事实」的那一步。
     */
    public static List<String> names(File root) {
        List<String> out = new ArrayList<>();
        collect(root, "", out);
        Collections.sort(out);
        return out;
    }

    private static void collect(File dir, String prefix, List<String> out) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        List<File> sorted = new ArrayList<>(Arrays.asList(kids));
        Collections.sort(sorted, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (File k : sorted) {
            String rel = prefix + k.getName();
            if (k.isDirectory()) {
                out.add("d " + rel);
                collect(k, rel + "/", out);
            } else {
                out.add("f " + rel);
            }
        }
    }

    /** 递归数一遍普通文件个数（不算目录） */
    public static int countFiles(File dir) {
        int n = 0;
        File[] kids = dir.listFiles();
        if (kids == null) {
            return 0;
        }
        for (File k : kids) {
            n += k.isDirectory() ? countFiles(k) : 1;
        }
        return n;
    }

    /**
     * 整棵树比对：结构 + 逐字节内容。
     *
     * @param src 期望（真实目录）
     * @param dst 实际（解压出来的）
     */
    public static void sameTree(String label, File src, File dst) {
        List<String> want = names(src);
        List<String> got = names(dst);
        if (!want.equals(got)) {
            for (String w : want) {
                if (!got.contains(w)) {
                    System.out.println("      !! 少了 " + w);
                }
            }
            for (String g : got) {
                if (!want.contains(g)) {
                    System.out.println("      !! 多了 " + g);
                }
            }
            check(label + " 结构一致（" + want.size() + " 项）", false);
            return;
        }
        check(label + " 结构一致（" + want.size() + " 项）", true);

        for (String w : want) {
            if (!w.startsWith("f ")) {
                continue;
            }
            String rel = w.substring(2);
            File a = new File(src, rel);
            File b = new File(dst, rel);
            try {
                if (!Arrays.equals(Files.readAllBytes(a.toPath()), Files.readAllBytes(b.toPath()))) {
                    check(label + " 内容一致: " + rel, false);
                    return;
                }
            } catch (Exception e) {
                check(label + " 内容一致: " + rel + " (" + e + ")", false);
                return;
            }
        }
        check(label + " 全部内容逐字节一致", true);
    }

    /** 断言某个文件存在且内容等于给定字节 */
    public static void fileEquals(String label, File f, byte[] want) {
        try {
            check(label, f.isFile() && Arrays.equals(Files.readAllBytes(f.toPath()), want));
        } catch (Exception e) {
            check(label + " (" + e + ")", false);
        }
    }

    /** 断言某个文件存在且内容等于另一个文件 */
    public static void fileSameAs(String label, File f, File ref) {
        try {
            check(label, f.isFile()
                    && Arrays.equals(Files.readAllBytes(f.toPath()), Files.readAllBytes(ref.toPath())));
        } catch (Exception e) {
            check(label + " (" + e + ")", false);
        }
    }

    /** 列举结果里有没有这一项（文件） */
    public static boolean hasFile(Archives.Listing l, String name) {
        for (Archives.Item i : l.items) {
            if (!i.directory && name.equals(i.name)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasDir(Archives.Listing l, String name) {
        for (Archives.Item i : l.items) {
            if (i.directory && name.equals(i.name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把列举出来的名字整理成和 [names] 同形的清单，用于整表比对。
     *
     * 注意剥掉**尾部斜杠**：tar 侧的目录条目名自带 `/`（`src/目录B/`），
     * 而 zip / rar / 7z 不带。这是各家格式的表示差异，不是内容差异 ——
     * 不归一化的话，同一棵树在 tar 上会「比对不过」，把真问题淹掉。
     */
    public static List<String> listingNames(Archives.Listing l, String under) {
        List<String> out = new ArrayList<>();
        String prefix = under.isEmpty() ? "" : under + "/";
        for (Archives.Item i : l.items) {
            if (!i.name.startsWith(prefix)) {
                continue;
            }
            String rel = i.name.substring(prefix.length());
            while (rel.endsWith("/")) {
                rel = rel.substring(0, rel.length() - 1);
            }
            if (rel.isEmpty()) {
                continue;
            }
            out.add((i.directory ? "d " : "f ") + rel);
        }
        Collections.sort(out);
        return out;
    }

    /** 目录下只应有一个文件时用它 */
    public static File onlyFileIn(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null || kids.length != 1) {
            return null;
        }
        return kids[0].isDirectory() ? kids[0] : kids[0];
    }

    public static void deleteRec(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRec(k);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** 清空目录并建好 */
    public static File freshDir(String path) {
        File f = new File(path);
        deleteRec(f);
        //noinspection ResultOfMethodCallIgnored
        f.mkdirs();
        return f;
    }

    /**
     * 跑一段会抛异常的代码，返回异常；不抛就返回 null。
     *
     * 不用 `Runnable`：它的 `run()` 不声明受检异常，而这里要试的恰恰都是
     * `throws IOException` 的调用 —— 用 Runnable 得在每处 lambda 里写 try/catch，
     * 反而把「到底抛没抛」写得看不清楚。
     */
    public interface Action {
        void run() throws Exception;
    }

    public static Throwable fails(Action a) {
        try {
            a.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    public static void finish() {
        System.out.println();
        if (failures == 0) {
            System.out.println("ALL OK —— " + total + " 项断言全过");
        } else {
            System.out.println("FAILURES: " + failures + " / " + total);
            System.exit(1);
        }
    }
}
