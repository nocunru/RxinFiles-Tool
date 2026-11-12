package com.rxinfiles.tool;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * bzip2 / xz / lzma 纯压缩流 与 tar.bz2 / tar.xz 的真实夹具校验。
 *
 * 这一组是 3.12.0 新增的格式里最容易被忽略的部分：它们是**流**不是**容器**
 * （单文件压缩没有条目表），所以走的代码路径和 tar.gz 完全不同 ——
 * UI 上表现为「解出来就是一个文件」，而且除 gzip 外都拿不到原始大小。
 */
public class StreamCheck {

    /** 单文件流夹具，内容都等于 zsrc/top.txt，解出来应叫 single.txt */
    static final String[][] STREAMS = {
            {"single.txt.bz2", "BZIP2", "bzip2"},
            {"single.txt.xz", "XZ", "xz"},
            {"single.txt.lzma", "LZMA", "lzma"},
    };

    public static void main(String[] args) throws Exception {
        File top = new File("zsrc/top.txt");
        File src = new File("src");

        Check.section("1. 扩展名与魔数识别");
        Object[][] names = {
                {"a.bz2", Archives.Kind.BZIP2},
                {"a.bzip2", Archives.Kind.BZIP2},
                {"a.tbz", Archives.Kind.TAR_BZ2},
                {"a.tbz2", Archives.Kind.TAR_BZ2},
                {"a.tar.bz2", Archives.Kind.TAR_BZ2},
                {"a.xz", Archives.Kind.XZ},
                {"a.txz", Archives.Kind.TAR_XZ},
                {"a.tar.xz", Archives.Kind.TAR_XZ},
                {"a.lzma", Archives.Kind.LZMA},
                {"a.tgz", Archives.Kind.TAR_GZ},
                {"a.tar.gz", Archives.Kind.TAR_GZ},
        };
        for (Object[] n : names) {
            String file = (String) n[0];
            Archives.Kind want = (Archives.Kind) n[1];
            Check.check("按扩展名识别 " + file + " → " + want,
                    Archives.kindOfName(file) == want);
        }
        Check.check("isArchiveName(\"a.tbz2\")", Archives.isArchiveName("a.tbz2"));
        Check.check("isArchiveName(\"a.txz\")", Archives.isArchiveName("a.txz"));
        Check.check("isArchiveName(\"a.lzma\")", Archives.isArchiveName("a.lzma"));
        Check.check("isArchiveName(\"a.txt\") == false", !Archives.isArchiveName("a.txt"));

        for (String[] s : STREAMS) {
            Check.check(s[0] + " 识别为 " + s[1],
                    Archives.kindOf(new File(s[0])).name().equals(s[1]));
        }
        Check.check("test.tar.bz2 识别为 TAR_BZ2",
                Archives.kindOf(new File("test.tar.bz2")) == Archives.Kind.TAR_BZ2);
        Check.check("test.tar.xz 识别为 TAR_XZ",
                Archives.kindOf(new File("test.tar.xz")) == Archives.Kind.TAR_XZ);
        Check.check("single.txt.gz 识别为 GZIP",
                Archives.kindOf(new File("single.txt.gz")) == Archives.Kind.GZIP);

        Check.section("2. 单文件流：列举");
        for (String[] s : STREAMS) {
            Archives.Listing l = Archives.list(new File(s[0]));
            Check.check(s[0] + " 解出来就是 1 个条目（实得 " + l.items.size() + "）", l.items.size() == 1);
            if (l.items.size() == 1) {
                Archives.Item i = l.items.get(0);
                Check.check(s[0] + " 条目名剥掉后缀 = single.txt（实得 " + i.name + "）",
                        "single.txt".equals(i.name));
                // 除 gzip 外都拿不到原始长度 —— 宁标「未知」也不编个 0 骗人
                Check.check(s[0] + " 大小标为未知", i.unavailable);
            }
            Check.check(s[0] + " 不需要密码", !Archives.needsPassword(new File(s[0])));
        }
        // gzip 尾部有 ISIZE，是唯一能报出真实大小的
        Archives.Listing gz = Archives.list(new File("single.txt.gz"));
        Check.check("single.txt.gz 条目名剥掉后缀", "single.txt".equals(gz.items.get(0).name));
        Check.check("single.txt.gz 大小是已知的（ISIZE）", !gz.items.get(0).unavailable);

        Check.section("3. 单文件流：解压内容逐字节比对");
        for (String[] s : STREAMS) {
            File dest = Check.freshDir("out/" + s[0]);
            int n = Archives.extract(new File(s[0]), dest, null);
            Check.check(s[0] + " 落地 1 个文件（实得 " + n + "）", n == 1);
            Check.fileSameAs(s[0] + " [" + s[2] + "] 解出内容 = zsrc/top.txt",
                    new File(dest, "single.txt"), top);
            Check.check(s[0] + " 没多留别的东西", Check.countFiles(dest) == 1);
        }

        Check.section("4. 单文件流：extractSelected 命中与不命中");
        Archives.Listing l = Archives.list(new File("single.txt.xz"));
        File hit = Check.freshDir("out/sel-hit");
        int nHit = Archives.extractSelected(new File("single.txt.xz"), hit, "single.txt", null);
        Check.check("命中 base 名 → 落地 1 个", nHit == 1);
        Check.fileSameAs("extractSelected 内容正确", new File(hit, "single.txt"), top);
        File miss = Check.freshDir("out/sel-miss");
        int nMiss = Archives.extractSelected(new File("single.txt.xz"), miss, "别的名字", null);
        Check.check("不命中 → 落地 0 个（实得 " + nMiss + "）", nMiss == 0);

        Check.section("5. tar.bz2 / tar.xz：整棵树");
        for (String[] t : new String[][]{{"test.tar.bz2", "TAR_BZ2"}, {"test.tar.xz", "TAR_XZ"}}) {
            String a = t[0];
            Archives.Listing tl = Archives.list(new File(a));
            Check.check(a + " 列举 = src 实际（" + tl.items.size() + " 项）",
                    Check.names(src).equals(Check.listingNames(tl, "src")));
            Check.check(a + " 总大小 = src 文件字节和", tl.totalSize == sizeOf(src));

            File dest = Check.freshDir("out/" + a);
            int n = Archives.extract(new File(a), dest, null);
            Check.check(a + " 落地文件数 = src 里的文件数（实得 " + n + "）",
                    n == Check.countFiles(src));
            Check.sameTree(a + " [" + t[1] + "] 解压", src, new File(dest, "src"));

            String pick = lastFile(src);
            File one = Check.freshDir("out/pick-" + a);
            int m = Archives.extractSelected(new File(a), one, "src/" + pick, null);
            Check.check(a + " extractSelected 只落地 1 个（实得 " + m + "）", m == 1);
            Check.fileSameAs(a + " extractSelected 内容正确: " + pick,
                    new File(one, "src/" + pick), new File(src, pick));

            File single = new File("out/entry-" + a + "/picked.bin");
            Check.deleteRec(new File("out/entry-" + a));
            Check.check(a + " extractEntry 命中",
                    Archives.extractEntry(new File(a), "src/" + pick, single));
            Check.fileSameAs(a + " extractEntry 内容正确", single, new File(src, pick));

            Check.check(a + " 不需要密码", !Archives.needsPassword(new File(a)));
        }

        Check.section("6. 坏输入：坏数据要给中文人话，不能崩");
        byte[] bad = "这根本不是 xz 数据，只是一段普通文本而已。\n".getBytes("UTF-8");
        File badXz = new File("out/bad.xz");
        Check.deleteRec(badXz);
        write(badXz, bad);
        Throwable t = Check.fails(() -> Archives.list(badXz));
        Check.check("坏 xz 列举失败（而不是静默当成空包）", t != null);
        Check.info("坏 xz 的报错文案: " + msg(t));

        File badBz2 = new File("out/bad.bz2");
        Check.deleteRec(badBz2);
        write(badBz2, bad);
        File badBz2Dest = Check.freshDir("out/bad-bz2");
        Throwable t2 = Check.fails(() -> Archives.extract(badBz2, badBz2Dest, null));
        Check.check("坏 bz2 解压失败", t2 != null);
        Check.info("坏 bz2 的报错文案: " + msg(t2));

        Check.section("7. 非归档文件不该被当成归档");
        File txt = new File("out/note.txt");
        Check.deleteRec(txt);
        write(txt, bad);
        Check.check("普通文本识别为 UNKNOWN",
                Archives.kindOf(txt) == Archives.Kind.UNKNOWN);
        Throwable t3 = Check.fails(() -> Archives.list(txt));
        Check.check("对普通文本列举会失败", t3 instanceof IOException);

        Check.finish();
    }

    // ---------------------------------------------------------------- 小工具

    static String msg(Throwable t) {
        return t == null ? "(没有异常)" : t.getMessage();
    }

    static String lastFile(File root) {
        List<String> names = Check.names(root);
        String last = null;
        for (String n : names) {
            if (n.startsWith("f ")) {
                last = n.substring(2);
            }
        }
        return last;
    }

    static long sizeOf(File root) {
        long n = 0L;
        File[] kids = root.listFiles();
        if (kids == null) {
            return 0L;
        }
        for (File k : kids) {
            n += k.isDirectory() ? sizeOf(k) : k.length();
        }
        return n;
    }

    static void write(File f, byte[] data) throws IOException {
        java.nio.file.Files.write(f.toPath(), data);
    }
}
