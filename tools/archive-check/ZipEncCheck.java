package com.rxinfiles.tool;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;

/**
 * 加密 ZIP 的回归校验（ZipCrypto / WinZip AES）。
 *
 * 这一层测的**全是「不依赖 Android API」的那部分**：`Archives` + `ZipArchive` 是纯 Java，
 * 所以能对真实夹具直接跑，不需要模拟器。UI（`ArchiveSheet` 的密码框）不在这一层。
 *
 * 夹具分三组来源，互相独立：
 *
 *  1. **7-Zip 25.01 造的**（`zipcrypto*.zip` / `zipaes*.zip` / `zipmixed.zip`）——
 *     这是真实世界里最常见的两种加密实现；
 *  2. **本仓库自己写的 Python 加密器造的**（`zippw-gbk.zip` / `zippw-utf8.zip`）——
 *     命令行工具没法指定「密码用哪种字节编码」，只有自己造才能把
 *     `passwordBytes()` 的三路回退钉死；它同时也是一套独立实现，读得出来说明
 *     两边对标准的理解一致；
 *  3. **损坏的包**（现场截断）—— 保证报错是人话而不是栈。
 */
public class ZipEncCheck {

    /** 夹具密码（见 mkfixtures.sh 的 PW= / genzipenc.py 的 PW_TEXT） */
    static final String PW = "rxpass";

    /** Python 造的中文密码夹具用的密码 */
    static final String CN_PW = "密码测试";

    /** 两棵源树：加密夹具全部来自 zenc（见 genzenc.py 为什么不能用 zsrc） */
    static final String TREE = "zenc";

    public static void main(String[] args) throws Exception {
        Check.section("0. 夹具在场（少了就直接停，免得后面的 FAIL 全是噪音）");
        String[] need = {
                "zenc/big.txt",
                "zipcrypto.zip", "zipcrypto-store.zip",
                "zipaes128.zip", "zipaes192.zip", "zipaes256.zip", "zipaes256-store.zip",
                "zipmixed.zip", "zippw-gbk.zip", "zippw-utf8.zip",
                "plain.zip", "test.tar.gz",
        };
        boolean allThere = true;
        for (String n : need) {
            if (!new File(n).exists()) {
                System.out.println("      !! 缺 " + n);
                allThere = false;
            }
        }
        Check.check("夹具齐全", allThere);
        if (!allThere) {
            System.out.println("\n夹具不齐，先跑 mkfixtures.sh / genzipenc.py");
            System.exit(2);
        }

        // ------------------------------------------------------------ 1
        Check.section("1. 「要不要密码」的探测（ZIP 是逐条目加密，这里问的是有没有加密条目）");
        String[] encrypted = {
                "zipcrypto.zip", "zipcrypto-store.zip",
                "zipaes128.zip", "zipaes192.zip", "zipaes256.zip", "zipaes256-store.zip",
                "zipmixed.zip", "zippw-gbk.zip", "zippw-utf8.zip",
        };
        for (String f : encrypted) {
            Check.check("needsPassword(" + f + ")", Archives.needsPassword(new File(f)));
        }
        Check.check("needsPassword(plain.zip) 为假", !Archives.needsPassword(new File("plain.zip")));
        Check.check("needsPassword(test.tar.gz) 为假", !Archives.needsPassword(new File("test.tar.gz")));

        // ------------------------------------------------------------ 2
        Check.section("2. 列举不需要密码（文件名是明文，列表照样列得出来）");
        for (String f : new String[]{"zipcrypto.zip", "zipaes128.zip", "zipaes256.zip", "zipcrypto-store.zip"}) {
            Archives.Listing l = Archives.list(new File(f));
            java.util.List<String> want = Check.names(new File(TREE));
            java.util.List<String> got = Check.listingNames(l, TREE);
            Check.check(f + " 条目表与真实目录树一致（" + got.size() + " 项）", want.equals(got));
        }

        // ------------------------------------------------------------ 3
        Check.section("3. 逐条目的加密标记");
        Archives.Listing mixed = Archives.list(new File("zipmixed.zip"));
        Check.check("混合包列出 2 项", mixed.items.size() == 2);
        Check.check("混合包：明文条目 encrypted=false", flagOf(mixed, "plain-here.txt") == Boolean.FALSE);
        Check.check("混合包：加密条目 encrypted=true", flagOf(mixed, "secret-here.txt") == Boolean.TRUE);

        Archives.Listing cr = Archives.list(new File("zipcrypto.zip"));
        Check.check("加密包：目录条目不加密", flagOf(cr, TREE) == Boolean.FALSE);
        Check.check("加密包：文件条目加密", flagOf(cr, TREE + "/big.txt") == Boolean.TRUE);
        Check.check("加密包：非 ASCII 名的条目也标成加密",
                flagOf(cr, TREE + "/中文文件.txt") == Boolean.TRUE);

        // ------------------------------------------------------------ 4
        Check.section("4. 加密形态识别（ZipCrypto / AES-128 / AES-192 / AES-256）");
        labelOf("zipcrypto.zip", "ZipCrypto");
        labelOf("zipcrypto-store.zip", "ZipCrypto");
        labelOf("zipaes128.zip", "AES-128");
        labelOf("zipaes192.zip", "AES-192");
        labelOf("zipaes256.zip", "AES-256");
        labelOf("zipaes256-store.zip", "AES-256");

        // ------------------------------------------------------------ 5
        Check.section("5. 不给密码时必须是「一句人话」，而不是英文异常/静默成功");
        for (String f : new String[]{"zipcrypto.zip", "zipaes128.zip", "zipaes256.zip", "zipcrypto-store.zip"}) {
            File dest = Check.freshDir("out/enc-nopw-" + f);
            Throwable t = Check.fails(() -> Archives.extract(new File(f), dest, null));
            Check.check(f + " 无密码解压抛错", t instanceof IOException);
            Check.check(f + " 报错里说清了是「密码」问题: " + msg(t), msg(t).contains("密码"));
            Check.check(f + " 无密码时没有落下半个文件", Check.countFiles(dest) == 0);
        }

        // ------------------------------------------------------------ 6
        Check.section("6. 密码正确 → 整棵树逐字节一致");
        for (String f : new String[]{
                "zipcrypto.zip", "zipcrypto-store.zip",
                "zipaes128.zip", "zipaes192.zip", "zipaes256.zip", "zipaes256-store.zip"}) {
            File dest = Check.freshDir("out/enc-" + f);
            int n = Archives.extract(new File(f), dest, null, PW.toCharArray());
            Check.info(f + " 落地 " + n + " 个文件");
            Check.sameTree(f, new File(TREE), new File(dest, TREE));
        }

        // ------------------------------------------------------------ 7
        Check.section("7. 密码错误 → 必须报错，且不留下 0 字节文件");
        for (String f : new String[]{"zipcrypto.zip", "zipaes128.zip", "zipaes256.zip", "zipaes192.zip"}) {
            File dest = Check.freshDir("out/enc-badpw-" + f);
            Throwable t = Check.fails(() -> Archives.extract(new File(f), dest, null, "definitely-wrong".toCharArray()));
            Check.check(f + " 错密码抛错", t instanceof IOException);
            Check.check(f + " 错密码的提示是中文且指向密码: " + msg(t), msg(t).contains("密码"));
            Check.check(f + " 错密码没留下半截文件", Check.countFiles(dest) == 0);
        }

        // ------------------------------------------------------------ 8
        Check.section("8. 单条目提取");
        File big = new File(TREE + "/big.txt");
        File[] targets = {
                new File("out/enc-one/zipcrypto.bin"),
                new File("out/enc-one/zipaes256.bin"),
                new File("out/enc-one/zipaes128.bin"),
        };
        String[] srcs = {"zipcrypto.zip", "zipaes256.zip", "zipaes128.zip"};
        for (int i = 0; i < srcs.length; i++) {
            Check.deleteRec(targets[i]);
            boolean ok = Archives.extractEntry(new File(srcs[i]), TREE + "/big.txt", targets[i], PW.toCharArray());
            Check.check(srcs[i] + " 单条目提取成功且内容一致", ok);
            Check.fileSameAs(srcs[i] + " 单条目内容与源文件一致", targets[i], big);
        }
        // 有序流也要能提（同一个包里的第二个条目，验证不是「碰巧第一个就对」）
        File csv = new File("out/enc-one/table.csv");
        Check.deleteRec(csv);
        Check.check("zipaes256.zip 提出非首个条目",
                Archives.extractEntry(new File("zipaes256.zip"), TREE + "/dirA/sub/table.csv", csv, PW.toCharArray())
                        && sameBytes(csv, new File(TREE + "/dirA/sub/table.csv")));

        for (String f : new String[]{"zipcrypto.zip", "zipaes256.zip"}) {
            File t = new File("out/enc-one-nopw.bin");
            Check.deleteRec(t);
            Throwable th = Check.fails(() -> Archives.extractEntry(new File(f), TREE + "/big.txt", t, null));
            Check.check(f + " 单条目无密码报「密码」: " + msg(th), msg(th).contains("密码"));
            Check.check(f + " 单条目无密码时不落文件", !t.exists());
        }

        // ------------------------------------------------------------ 9
        Check.section("9. 明文 / 加密混装的包");
        File mdest = Check.freshDir("out/enc-mixed");
        Check.check("混合包里明文条目不给密码也能提出",
                Archives.extractEntry(new File("zipmixed.zip"), "plain-here.txt",
                        new File(mdest, "plain-here.txt"), null)
                        && sameBytes(new File(mdest, "plain-here.txt"),
                        new File("zmixed/plain-here.txt")));
        File sec = new File(mdest, "secret-here.txt");
        Throwable t9 = Check.fails(() -> Archives.extractEntry(new File("zipmixed.zip"), "secret-here.txt", sec, null));
        Check.check("混合包里加密条目没密码要报「密码」: " + msg(t9), msg(t9).contains("密码"));
        Check.check("混合包里加密条目带密码能提出",
                Archives.extractEntry(new File("zipmixed.zip"), "secret-here.txt", sec, PW.toCharArray())
                        && sameBytes(sec, new File("zmixed/secret-here.txt")));
        // 整包解压：混装也要密码（有一半是加密的），但给了密码必须两样都对
        File mfull = Check.freshDir("out/enc-mixed-full");
        Check.check("混合包整包解压（带密码）落地 2 个文件",
                Archives.extract(new File("zipmixed.zip"), mfull, null, PW.toCharArray()) == 2);
        Check.sameTree("混合包整包", new File("zmixed"), mfull);

        // ------------------------------------------------------------ 10
        Check.section("10. 密码的字节编码：GBK / UTF-8 / 原始 8 位都要试");
        //
        // ZIP 的密码是按**字节**参与密钥运算的。中文系统上的 WinZip / WinRAR / 7-Zip
        // 用本机代码页（GBK），Linux/手机上的工具用 UTF-8 —— 同一个「密码测试」，
        // 两种包里的字节完全不同。命令行工具没法定编码，所以这两条夹具是
        // genzipenc.py 用自己写的 ZipCrypto 加密器造的。
        byte[] payload = repeat("这是被密码保护的内容。\n", 20).getBytes("UTF-8");
        String[] cn = {"zippw-gbk.zip", "zippw-utf8.zip"};
        for (String f : cn) {
            File dest = Check.freshDir("out/enc-cn-" + f);
            Check.check(f + " 列举看得到条目名", Archives.list(new File(f)).items.size() == 1);
            Throwable bad = Check.fails(() -> Archives.extractEntry(new File(f), "secret.txt",
                    new File(dest, "secret.txt"), "wrong".toCharArray()));
            Check.check(f + " 错密码报错", bad != null);
            Check.check(f + " 中文密码解得开（编码回退生效）",
                    Archives.extractEntry(new File(f), "secret.txt", new File(dest, "secret.txt"),
                            CN_PW.toCharArray())
                            && sameBytes(new File(dest, "secret.txt"), payload));
        }

        // ------------------------------------------------------------ 11
        Check.section("11. 损坏 / 截断的加密包");
        File broken = new File("out/enc-broken.zip");
        byte[] whole = Files.readAllBytes(new File("zipcrypto.zip").toPath());
        Files.write(broken.toPath(), java.util.Arrays.copyOf(whole, whole.length / 2));
        Throwable t11 = Check.fails(() -> Archives.list(broken));
        Check.check("截断一半：列举抛错而不是崩: " + msg(t11), t11 != null);
        Check.check("截断一半：报错是人话、不带绝对路径: " + msg(t11),
                isHumanMessage(msg(t11)));
        Throwable t12 = Check.fails(() -> Archives.extract(broken,
                Check.freshDir("out/enc-broken"), null, PW.toCharArray()));
        Check.check("截断一半：解压抛错而不是崩: " + msg(t12), t12 != null);

        // 只把尾巴上的加密数据砍掉几字节（中央目录还在，能开出来）
        File tail = new File("out/enc-tail.zip");
        Files.copy(new File("zipcrypto.zip").toPath(), tail.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
        Files.write(tail.toPath(), java.util.Arrays.copyOf(whole, whole.length - 4096));
        Throwable t13 = Check.fails(() -> Archives.extract(tail,
                Check.freshDir("out/enc-tail"), null, PW.toCharArray()));
        Check.check("砍掉尾部数据：解压抛错而不是崩: " + msg(t13), t13 != null);

        Check.finish();
    }

    /**
     * 用户能看懂的一条提示：不出现绝对路径（对他没用），也不出现 commons-compress
     * 的英文原文（`Error on ZipFile ...`）。
     */
    private static boolean isHumanMessage(String message) {
        return !message.isEmpty()
                && !message.contains("Error on ZipFile")
                && !message.contains(":\\")
                && !message.contains("Exception");
    }

    // ------------------------------------------------------------------ 小工具

    /**
     * 按条目名找加密标记。
     *
     * **目录条目名自带尾斜杠**（zip / tar / rar / 7z 都一样，见 README 的口径差异），
     * 所以查目录时要归一化 —— 这里踩过一次：拿 `zenc` 去查，查不到，
     * 断言假红。
     */
    private static Boolean flagOf(Archives.Listing l, String name) {
        for (Archives.Item i : l.items) {
            String n = i.name;
            while (n.endsWith("/")) {
                n = n.substring(0, n.length() - 1);
            }
            if (name.equals(n)) {
                return i.encrypted;
            }
        }
        System.out.println("      !! 列表里没有 " + name);
        return null;
    }

    /** 打出包里第一个「加密文件条目」被判成了什么形态，并断言标签 */
    private static void labelOf(String archive, String expect) throws IOException {
        try (ZipFile zip = new ZipFile(new File(archive), "UTF-8", true)) {
            for (Enumeration<ZipArchiveEntry> en = zip.getEntries(); en.hasMoreElements(); ) {
                ZipArchiveEntry e = en.nextElement();
                if (!ZipArchive.isEncrypted(e) || e.isDirectory()) {
                    continue;
                }
                ZipArchive.Crypt c = ZipArchive.cryptOf(e);
                String label = c == null ? "(认不出来)" : c.label();
                Check.check(archive + " 加密形态 = " + expect + "（" + e.getName() + " 实为 " + label + "）",
                        expect.equals(label));
                return;
            }
        }
        Check.check(archive + " 里找得到加密文件条目", false);
    }

    private static boolean sameBytes(File a, File b) throws IOException {
        return a.isFile() && b.isFile()
                && java.util.Arrays.equals(Files.readAllBytes(a.toPath()), Files.readAllBytes(b.toPath()));
    }

    private static boolean sameBytes(File a, byte[] want) throws IOException {
        return a.isFile() && java.util.Arrays.equals(Files.readAllBytes(a.toPath()), want);
    }

    private static String msg(Throwable t) {
        if (t == null) {
            return "(没有抛异常)";
        }
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    /** Java 11 才有 String.repeat，这里不依赖它（工程 target 是 Java 8 风格） */
    private static String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
}
