package com.rxinfiles.tool;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * RAR 支持的真实夹具校验。
 *
 * 夹具由 `mkfixtures.sh` 用官方 rar 6.24 生成（见该脚本开头为什么不是 7.x），
 * 覆盖：RAR4 / RAR5 / solid / 内容加密 / 头部加密 / 路径逃逸 / 符号链接。
 *
 * 跑法：`run-checks.sh`，或在本目录下手动
 * `java -cp build:../../app/libs/* com.rxinfiles.tool.RarCheck`
 */
public class RarCheck {

    static final char[] PW = "rxpass".toCharArray();
    static final char[] BAD_PW = "not-my-password".toCharArray();

    public static void main(String[] args) throws Exception {
        File zsrc = new File("zsrc");
        File src = new File("src");

        Check.section("1. 类型识别");
        String[] all = {"rar4.rar", "rar5.rar", "rar4-solid.rar", "rar5-solid.rar",
                "rar5-enc.rar", "rar4-enc.rar", "rar5-enchdr.rar", "evil.rar", "rar5-link.rar"};
        boolean everyRar = true;
        for (String a : all) {
            if (Archives.kindOf(new File(a)) != Archives.Kind.RAR) {
                everyRar = false;
                Check.note(a + " 没识别成 RAR，实得 " + Archives.kindOf(new File(a)));
            }
        }
        Check.check("九个 .rar 全部识别为 RAR", everyRar);
        Check.check("按扩展名识别 .rar", Archives.kindOfName("x.rar") == Archives.Kind.RAR);
        Check.check("isArchiveName(\"a.rar\")", Archives.isArchiveName("a.rar"));

        // 无扩展名靠魔数（Rar!\x1a\x07）
        File noExt = new File("out/noext-rar");
        Check.deleteRec(noExt);
        noExt.getParentFile().mkdirs();
        copy(new File("rar5.rar"), noExt);
        Check.check("无扩展名靠魔数识别 RAR", Archives.kindOf(noExt) == Archives.Kind.RAR);

        Check.section("2. 列举：与真实目录树逐项比对（不抄清单）");
        for (String a : new String[]{"rar5.rar", "rar4.rar"}) {
            Archives.Listing l = Archives.list(new File(a));
            List<String> want = Check.names(zsrc);
            List<String> got = Check.listingNames(l, "zsrc");
            Check.check(a + " 列举项 = zsrc 实际（" + want.size() + " 项）", want.equals(got));
            if (!want.equals(got)) {
                diff(want, got);
            }
            Check.check(a + " 总大小 = zsrc 文件字节和（" + l.totalSize + "）",
                    l.totalSize == sizeOf(zsrc));
            Check.check(a + " 中文文件名解码正确", Check.hasFile(l, "zsrc/中文文件.txt"));
            Check.check(a + " 空目录作为目录条目出现", Check.hasDir(l, "zsrc/空目录"));
            Check.info("格式标签: " + RarArchive.list(new File(a), null).format);
        }

        Check.section("3. 解压：整棵树逐字节比对");
        for (String a : new String[]{"rar5.rar", "rar4.rar"}) {
            File dest = Check.freshDir("out/" + a);
            int n = Archives.extract(new File(a), dest, null);
            Check.info(a + " 落地 " + n + " 个文件");
            Check.sameTree(a, zsrc, new File(dest, "zsrc"));
            Check.check(a + " 落地文件数 = zsrc 里的文件数", n == Check.countFiles(zsrc));
        }

        Check.section("4. solid 包：跳过前面的文件只能靠「读掉」，这是最容易错的地方");
        for (String a : new String[]{"rar5-solid.rar", "rar4-solid.rar"}) {
            Archives.Listing l = Archives.list(new File(a));
            Check.check(a + " 列举 = src 实际",
                    Check.names(src).equals(Check.listingNames(l, "src")));

            File whole = Check.freshDir("out/solid-" + a);
            Archives.extract(new File(a), whole, null);
            Check.sameTree(a + " 整体解压", src, new File(whole, "src"));

            // 取排序上最后一个文件 —— 在 solid 流里它就是「前面全得解码掉」的最狠用例
            String pick = lastFile(src);
            File one = Check.freshDir("out/pick-" + a);
            int n = Archives.extractSelected(new File(a), one, "src/" + pick, null);
            Check.check(a + " extractSelected 只落地 1 个（实得 " + n + "）", n == 1);
            Check.fileSameAs(a + " extractSelected 内容正确: " + pick,
                    new File(one, "src/" + pick), new File(src, pick));
            Check.check(a + " extractSelected 没带出别的东西", Check.countFiles(one) == 1);

            File single = new File("out/entry-" + a + "/picked.bin");
            Check.deleteRec(new File("out/entry-" + a));
            Check.check(a + " extractEntry 命中", Archives.extractEntry(new File(a), "src/" + pick, single));
            Check.fileSameAs(a + " extractEntry 内容正确", single, new File(src, pick));
        }

        Check.section("5. 加密");
        Check.check("rar5.rar 不需要密码", !Archives.needsPassword(new File("rar5.rar")));
        Check.check("rar4.rar 不需要密码", !Archives.needsPassword(new File("rar4.rar")));
        Check.check("内容加密（-p）判出要密码：rar5", Archives.needsPassword(new File("rar5-enc.rar")));
        Check.check("内容加密（-p）判出要密码：rar4", Archives.needsPassword(new File("rar4-enc.rar")));
        Check.check("头部加密（-hp）判出要密码", Archives.needsPassword(new File("rar5-enchdr.rar")));

        Throwable noPw = Check.fails(() -> Archives.list(new File("rar5-enchdr.rar")));
        Check.check("头部加密包不带密码列举失败", noPw instanceof IOException);
        Check.info("不带密码的报错文案: " + text(noPw));

        Archives.Listing hdr = Archives.list(new File("rar5-enchdr.rar"), PW);
        Check.check("头部加密包带密码列举成功（" + hdr.items.size() + " 项）", !hdr.items.isEmpty());

        Throwable badPw = Check.fails(() -> Archives.list(new File("rar5-enchdr.rar"), BAD_PW));
        Check.check("密码错时列举失败", badPw != null);
        Check.info("密码错的报错文案: " + text(badPw));

        Archives.Listing contentEnc = Archives.list(new File("rar5-enc.rar"));
        Check.check("内容加密包不带密码也能列出文件名（" + contentEnc.items.size() + " 项）",
                !contentEnc.items.isEmpty());
        Check.check("RAR 内容加密条目透传 encrypted 标记",
                contentEnc.items.stream().anyMatch(item -> !item.directory && item.encrypted));
        File noPwDest = Check.freshDir("out/enc-nopw");
        Throwable extractNoPw = Check.fails(
                () -> Archives.extract(new File("rar5-enc.rar"), noPwDest, null));
        Check.check("内容加密包不带密码解压失败", extractNoPw instanceof IOException);
        Check.info("不带密码解压的报错文案: " + text(extractNoPw));
        // 关键：不能是「静默跳过所有条目、然后报成功 0 个文件」
        Check.check("失败时一个文件都没落地（不是静默跳过）",
                Check.countFiles(noPwDest) == 0);
        Throwable entryNoPw = Check.fails(() -> Archives.extractEntry(
                new File("rar5-enc.rar"), "zsrc/top.txt", new File("out/enc-entry/top.txt")));
        Check.check("内容加密包不带密码预览单个条目也失败", entryNoPw instanceof IOException);

        for (String a : new String[]{"rar5-enc.rar", "rar4-enc.rar", "rar5-enchdr.rar"}) {
            File dest = Check.freshDir("out/pw-" + a);
            Archives.extract(new File(a), dest, null, PW);
            Check.sameTree(a + " 带密码解压", zsrc, new File(dest, "zsrc"));
        }

        Check.section("6. 路径逃逸：夹具里的条目名真的写着 ../");
        File evilDest = Check.freshDir("out/evil-rar");
        Check.deleteRec(new File("escaped3.txt"));
        Check.deleteRec(new File("out/escaped3.txt"));
        Archives.Listing evil = Archives.list(new File("evil.rar"));
        Check.check("逃逸条目照样被列举出来（列举不落地，所以只是显示）",
                Check.hasFile(evil, "../escaped3.txt"));
        Archives.extract(new File("evil.rar"), evilDest, null);
        Check.check("上一级目录没被写出 escaped3.txt",
                !new File("out/escaped3.txt").exists() && !new File("escaped3.txt").exists());
        Check.fileEquals("同一个包里的正常条目 good3.txt 已解出",
                new File(evilDest, "good3.txt"), bytes("good payload\n"));

        Check.section("7. 符号链接条目不落地");
        File linkDest = Check.freshDir("out/link-rar");
        Archives.extract(new File("rar5-link.rar"), linkDest, null);
        Check.check("rel.txt（链接）没落地", !new File(linkDest, "linkdir/rel.txt").exists());
        Check.check("abs.txt（链接到 /etc/hostname）没落地",
                !new File(linkDest, "linkdir/abs.txt").exists());
        Check.fileEquals("同目录的真正文件 target.txt 正常解出",
                new File(linkDest, "linkdir/target.txt"), bytes("link target content\n"));

        RarArchive.Listing linkList = RarArchive.list(new File("rar5-link.rar"), null);
        int special = 0;
        for (RarArchive.Entry e : linkList.entries) {
            if (e.special) {
                special++;
            }
        }
        Check.check("列举把两条链接标成特殊条目（实得 " + special + "）", special == 2);

        Check.section("8. 坏输入：给一句人话，别只是抛英文栈");
        File notRar = new File("out/fake.rar");
        Check.deleteRec(notRar);
        write(notRar, "这不是一个 RAR 文件，只是一段普通文本。\n".getBytes("UTF-8"));
        Check.check("冒充 .rar 的文本被认出不是 RAR 包结构",
                Archives.kindOf(notRar) == Archives.Kind.RAR); // 扩展名先认，魔数兜底
        Throwable fake = Check.fails(() -> Archives.list(notRar));
        Check.check("列举冒充包失败但给出中文提示", fake instanceof IOException
                && text(fake) != null && !text(fake).isEmpty());
        Check.info("冒充包的报错文案: " + text(fake));

        Check.finish();
    }

    // ---------------------------------------------------------------- 小工具

    static byte[] bytes(String s) throws Exception {
        return s.getBytes("UTF-8");
    }

    static String text(Throwable t) {
        return t == null ? "(没有异常)" : t.getMessage();
    }

    /** 排序上最后一个文件（relative path） */
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

    static void diff(List<String> want, List<String> got) {
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
    }

    static void copy(File from, File to) throws IOException {
        java.nio.file.Files.copy(from.toPath(), to.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    static void write(File f, byte[] data) throws IOException {
        java.nio.file.Files.write(f.toPath(), data);
    }
}
