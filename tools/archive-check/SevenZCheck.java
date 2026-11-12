package com.rxinfiles.tool;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 7z 支持的真实夹具校验。
 *
 * 夹具由 `mkfixtures.sh` 用 7-Zip 25.01 生成，覆盖：
 * 五种压缩方法（LZMA2 / LZMA / BZip2 / Deflate / Copy）、solid 与 non-solid、
 * 内容加密、头部加密、路径逃逸、符号链接，以及**我们自己写的 7z** 的往返。
 *
 * 为什么方法矩阵要全覆盖：7z 的「容器」是 commons-compress 的事，
 * 而 LZMA/LZMA2 的解码器来自 xz jar —— 缺了它，标准 .7z 一个都解不开。
 * 只测一个默认包，是测不出这层依赖的。
 */
public class SevenZCheck {

    static final char[] PW = "rxpass".toCharArray();
    static final char[] BAD_PW = "not-my-password".toCharArray();

    /** 各压缩方法对应的夹具（都装 zsrc 这棵树） */
    static final String[][] CODECS = {
            {"sevenz-lzma2.7z", "LZMA2（默认）"},
            {"sevenz-lzma.7z", "LZMA（LZMA1）"},
            {"sevenz-bzip2.7z", "BZip2"},
            {"sevenz-deflate.7z", "Deflate"},
            {"sevenz-copy.7z", "Copy（不压缩）"},
    };

    public static void main(String[] args) throws Exception {
        File zsrc = new File("zsrc");
        File src = new File("src");

        Check.section("1. 类型识别");
        String[] all = {"sevenz-lzma2.7z", "sevenz-lzma.7z", "sevenz-bzip2.7z",
                "sevenz-deflate.7z", "sevenz-copy.7z", "sevenz-solid.7z", "sevenz-nonsolid.7z",
                "sevenz-enc.7z", "sevenz-enchdr.7z", "evil.7z", "sevenz-link.7z"};
        boolean every7z = true;
        for (String a : all) {
            if (Archives.kindOf(new File(a)) != Archives.Kind.SEVEN_Z) {
                every7z = false;
                Check.note(a + " 没识别成 7z，实得 " + Archives.kindOf(new File(a)));
            }
        }
        Check.check("十一个 .7z 全部识别为 SEVEN_Z", every7z);
        Check.check("按扩展名识别 .7z", Archives.kindOfName("x.7z") == Archives.Kind.SEVEN_Z);
        Check.check("isArchiveName(\"a.7z\")", Archives.isArchiveName("a.7z"));

        // 无扩展名靠魔数 37 7A BC AF 27 1C
        File noExt = new File("out/noext-7z");
        Check.deleteRec(noExt);
        noExt.getParentFile().mkdirs();
        copy(new File("sevenz-lzma2.7z"), noExt);
        Check.check("无扩展名靠魔数识别 7z", Archives.kindOf(noExt) == Archives.Kind.SEVEN_Z);

        Check.section("2. 各种压缩方法：列举 + 解压 + 逐字节比对");
        for (String[] c : CODECS) {
            String a = c[0];
            Archives.Listing l = Archives.list(new File(a));
            Check.check(a + " [" + c[1] + "] 列举 = zsrc 实际（" + l.items.size() + " 项）",
                    Check.names(zsrc).equals(Check.listingNames(l, "zsrc")));
            Check.check(a + " 中文文件名解码正确", Check.hasFile(l, "zsrc/中文文件.txt"));
            Check.check(a + " 空目录作为目录条目出现", Check.hasDir(l, "zsrc/空目录"));

            File dest = Check.freshDir("out/" + a);
            int n = Archives.extract(new File(a), dest, null);
            Check.sameTree(a + " [" + c[1] + "] 解压", zsrc, new File(dest, "zsrc"));
            Check.check(a + " 落地文件数 = zsrc 里的文件数（实得 " + n + "）",
                    n == Check.countFiles(zsrc));
        }

        Check.section("3. solid / non-solid：跳过条目必须靠读掉，不能 seek");
        for (String a : new String[]{"sevenz-solid.7z", "sevenz-nonsolid.7z"}) {
            Archives.Listing l = Archives.list(new File(a));
            Check.check(a + " 列举 = src 实际", Check.names(src).equals(Check.listingNames(l, "src")));

            File whole = Check.freshDir("out/full-" + a);
            Archives.extract(new File(a), whole, null);
            Check.sameTree(a + " 整体解压", src, new File(whole, "src"));

            String pick = lastFile(src);
            File one = Check.freshDir("out/pick-" + a);
            int n = Archives.extractSelected(new File(a), one, "src/" + pick, null);
            Check.check(a + " extractSelected 只落地 1 个（实得 " + n + "）", n == 1);
            Check.fileSameAs(a + " extractSelected 内容正确: " + pick,
                    new File(one, "src/" + pick), new File(src, pick));
            Check.check(a + " extractSelected 没带出别的东西", Check.countFiles(one) == 1);

            File single = new File("out/entry-" + a + "/picked.bin");
            Check.deleteRec(new File("out/entry-" + a));
            Check.check(a + " extractEntry 命中",
                    Archives.extractEntry(new File(a), "src/" + pick, single));
            Check.fileSameAs(a + " extractEntry 内容正确", single, new File(src, pick));
        }

        Check.section("4. 加密：内容加密与头部加密都要判出来");
        Check.check("sevenz-lzma2.7z 不需要密码", !Archives.needsPassword(new File("sevenz-lzma2.7z")));
        // 这一条是上一版会漏的：-p 只加密内容，文件名照样看得见，
        // 只靠「不带密码打不开」判断会得出 false，界面就不问密码了。
        Check.check("只加密内容（-p）也要判出要密码", Archives.needsPassword(new File("sevenz-enc.7z")));
        Check.check("头部加密（-mhe）判出要密码", Archives.needsPassword(new File("sevenz-enchdr.7z")));

        Archives.Listing contentEnc = Archives.list(new File("sevenz-enc.7z"));
        Check.check("内容加密包不带密码也能列出文件名（" + contentEnc.items.size() + " 项）",
                !contentEnc.items.isEmpty());
        Check.check("7z 内容加密条目透传 encrypted 标记",
                contentEnc.items.stream().anyMatch(item -> !item.directory && item.encrypted));
        File noPwDest = Check.freshDir("out/enc-nopw-7z");
        Throwable extractNoPw = Check.fails(
                () -> Archives.extract(new File("sevenz-enc.7z"), noPwDest, null));
        Check.check("内容加密包不带密码解压失败", extractNoPw instanceof IOException);
        Check.info("不带密码解压的报错文案: " + text(extractNoPw));

        Throwable hdrNoPw = Check.fails(() -> Archives.list(new File("sevenz-enchdr.7z")));
        Check.check("头部加密包不带密码列举失败", hdrNoPw instanceof IOException);
        Check.info("头部加密不带密码的报错文案: " + text(hdrNoPw));

        Throwable badPw = Check.fails(() -> Archives.list(new File("sevenz-enchdr.7z"), BAD_PW));
        Check.check("密码错时列举失败", badPw != null);
        Check.info("密码错的报错文案: " + text(badPw));

        for (String a : new String[]{"sevenz-enc.7z", "sevenz-enchdr.7z"}) {
            File dest = Check.freshDir("out/pw-" + a);
            Archives.extract(new File(a), dest, null, PW);
            Check.sameTree(a + " 带密码解压", zsrc, new File(dest, "zsrc"));
        }

        Check.section("5. 路径逃逸：夹具里的条目名真的写着 ../");
        File evilDest = Check.freshDir("out/evil-7z");
        Check.deleteRec(new File("escaped4.txt"));
        Check.deleteRec(new File("out/escaped4.txt"));
        Archives.Listing evil = Archives.list(new File("evil.7z"));
        Check.check("逃逸条目照样被列举出来", Check.hasFile(evil, "../escaped4.txt"));
        Archives.extract(new File("evil.7z"), evilDest, null);
        Check.check("上一级目录没被写出 escaped4.txt",
                !new File("out/escaped4.txt").exists() && !new File("escaped4.txt").exists());
        Check.fileEquals("同一个包里的正常条目 good3.txt 已解出",
                new File(evilDest, "good3.txt"), bytes("good payload\n"));

        Check.section("6. 符号链接条目不落地");
        File linkDest = Check.freshDir("out/link-7z");
        Archives.extract(new File("sevenz-link.7z"), linkDest, null);
        Check.check("rel.txt（链接）没落地", !new File(linkDest, "linkdir/rel.txt").exists());
        Check.check("abs.txt（链接到 /etc/hostname）没落地",
                !new File(linkDest, "linkdir/abs.txt").exists());
        Check.fileEquals("同目录的真正文件 target.txt 正常解出",
                new File(linkDest, "linkdir/target.txt"), bytes("link target content\n"));

        List<SevenZArchive.Entry> linkList = SevenZArchive.list(new File("sevenz-link.7z"), null);
        int special = 0;
        for (SevenZArchive.Entry e : linkList) {
            if (e.special) {
                special++;
            }
        }
        Check.check("列举把两条链接标成特殊条目（实得 " + special + "）", special == 2);

        Check.section("7. 自己写的 7z（SevenZOutputFile）能读回来");
        File mine = new File("out/mine.7z");
        Check.deleteRec(mine);
        List<File> sources = new ArrayList<>();
        sources.add(src);
        int written = Archives.sevenZip(sources, mine, null);
        Check.info("写入 " + written + " 个条目，" + mine.length() + " 字节");
        Check.check("写出的 7z 被识别为 SEVEN_Z", Archives.kindOf(mine) == Archives.Kind.SEVEN_Z);

        Archives.Listing mineList = Archives.list(mine);
        Check.check("写出的包列举 = src 实际（" + mineList.items.size() + " 项）",
                Check.names(src).equals(Check.listingNames(mineList, "src")));

        File round = Check.freshDir("out/round-7z");
        int back = Archives.extract(mine, round, null);
        Check.check("写出的包落地文件数 = src 里的文件数（实得 " + back + "）",
                back == Check.countFiles(src));
        // 这一条是 setHasStream(true) 的守门人：漏了它条目会被当成「没有数据流」，
        // 解出来的全是 0 字节 —— 结构比对会过、内容比对必挂。
        Check.sameTree("自有 7z 往返", src, new File(round, "src"));

        Check.section("8. 坏输入：给一句人话");
        File broken = new File("out/broken.7z");
        Check.deleteRec(broken);
        byte[] head = new byte[64];
        try (java.io.InputStream in = new java.io.FileInputStream("sevenz-lzma2.7z")) {
            //noinspection ResultOfMethodCallIgnored
            in.read(head);
        }
        for (int i = 40; i < head.length; i++) {
            head[i] = 0; // 掐掉后半截，做出一个「头部还在、内容没了」的包
        }
        write(broken, head);
        Throwable t = Check.fails(() -> Archives.list(broken));
        Check.check("截断的 7z 列举会失败而不是静默成功", t != null);
        if (t != null) {
            Check.check("失败信息是中文人话（不是裸英文栈）", isChineseHumanMessage(t.getMessage()));
            Check.info("截断包的报错文案: " + t.getMessage());
        }

        File self = new File("out/self.7z");
        byte[] original = bytes("do not truncate");
        java.nio.file.Files.write(self.toPath(), original);
        Throwable selfFailure = Check.fails(() -> Archives.sevenZip(
                java.util.Collections.singletonList(self), self, null));
        Check.check("7z 源与输出相同会拒绝", selfFailure instanceof IOException);
        Check.check("7z 源与输出相同时源内容不变",
                java.util.Arrays.equals(original, java.nio.file.Files.readAllBytes(self.toPath())));

        Check.section("9. 压缩源中的符号链接环");
        File loopRoot = new File("out/loop-7z-src");
        Check.deleteRec(loopRoot);
        Check.check("7z 链接环夹具目录创建", loopRoot.mkdirs());
        write(new File(loopRoot, "payload.txt"), bytes("payload"));
        File loop = new File(loopRoot, "loop");
        boolean loopCreated = false;
        try {
            java.nio.file.Files.createSymbolicLink(loop.toPath(), loopRoot.getAbsoluteFile().toPath());
            loopCreated = true;
        } catch (IOException | SecurityException | UnsupportedOperationException e) {
            Check.note("当前系统不允许创建符号链接，跳过: " + e);
        }
        if (loopCreated) {
            File loop7z = new File("out/loop.7z");
            Check.deleteRec(loop7z);
            Archives.sevenZip(java.util.Collections.singletonList(loopRoot), loop7z, null);
            Archives.Listing loopList = Archives.list(loop7z);
            boolean hasLoop = false;
            for (Archives.Item item : loopList.items) {
                if (item.name.contains("loop")) hasLoop = true;
            }
            Check.check("7z 压缩跳过符号链接环", !hasLoop);
            Check.check("7z 链接环旁的普通文件仍被压缩",
                    Check.hasFile(loopList, "loop-7z-src/payload.txt"));
            java.nio.file.Files.deleteIfExists(loop.toPath());
        }
        Check.deleteRec(loopRoot);

        Check.finish();
    }

    // ---------------------------------------------------------------- 小工具

    static byte[] bytes(String s) throws Exception {
        return s.getBytes("UTF-8");
    }

    static String text(Throwable t) {
        return t == null ? "(没有异常)" : t.getMessage();
    }

    /** 人话判据：带上「包 / 文件 / 密码 / 损坏 / 不完整 / 不支持」这类词，且没有 Java 类名残留 */
    static boolean isChineseHumanMessage(String m) {
        if (m == null || m.isEmpty()) {
            return false;
        }
        String[] words = {"包", "文件", "密码", "完整", "损坏", "支持", "格式", "认识", "读取"};
        for (String w : words) {
            if (m.contains(w)) {
                return true;
            }
        }
        return false;
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

    static void copy(File from, File to) throws IOException {
        java.nio.file.Files.copy(from.toPath(), to.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    static void write(File f, byte[] data) throws IOException {
        java.nio.file.Files.write(f.toPath(), data);
    }
}
