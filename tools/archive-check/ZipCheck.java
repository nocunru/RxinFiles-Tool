package com.rxinfiles.tool;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ZipCheck {
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        // ---------- 1. 列举 ----------
        String[] samples = {"plain.zip", "gbk.zip", "test.tar", "test.tar.gz", "single.txt.gz", "evil.zip"};
        for (String s : samples) {
            File f = new File(s);
            Archives.Kind k = Archives.kindOf(f);
            Archives.Listing l = Archives.list(f);
            System.out.println("=== " + s + " kind=" + k + " items=" + l.items.size()
                    + " files=" + l.fileCount() + " total=" + l.totalSize + " charset=" + l.charsetName);
            for (Archives.Item i : l.items) {
                System.out.println("      " + (i.directory ? "[D] " : "    ") + i.name + "  " + i.size);
            }
        }

        // ---------- 2. GBK 文件名必须解对 ----------
        Archives.Listing gbk = Archives.list(new File("gbk.zip"));
        boolean found = false;
        for (Archives.Item i : gbk.items) if ("文件名abc.txt".equals(i.name)) found = true;
        check("GBK 文件名解码", found);

        // ---------- 3. 解压并核对内容 ----------
        extractAndVerify("plain.zip", "zsrc", "out/plain", null);
        extractAndVerify("test.tar.gz", "src", "out/targz", "payload");

        // ---------- 4. zip-slip 必须被拦 ----------
        deleteRec(new File("escaped2.txt"));
        File dest = new File("out/evil");
        deleteRec(dest);
        Archives.Listing evilList = Archives.list(new File("evil.zip"));
        Archives.extract(new File("evil.zip"), dest, null);
        check("zip-slip 未逃逸", !new File("escaped2.txt").exists());
        check("zip-slip 条目仍列出（仅列举不落地）", evilList.items.size() == 2);
        check("正常条目已解出", new File(dest, "good.txt").isFile());

        // ---------- 5. 单文件 gzip ----------
        File gzDest = new File("out/gz");
        deleteRec(gzDest);
        gzDest.mkdirs();
        Archives.extract(new File("single.txt.gz"), gzDest, null);
        File out = new File(gzDest, "single.txt");
        check("gzip 解出单文件", out.isFile() && out.length() == "gzip 单文件内容\n".getBytes("UTF-8").length * 100L);

        // ---------- 6. 压缩：自己打包再解开，逐字节比对 ----------
        File outZip = new File("out/mine.zip");
        deleteRec(outZip);
        List<File> sources = new ArrayList<>();
        sources.add(new File("zsrc"));
        int n = Archives.zip(sources, outZip, null);
        System.out.println("=== 自家 zip: 写入 " + n + " 个条目, " + outZip.length() + " bytes");
        Archives.Listing mine = Archives.list(outZip);
        System.out.println("    重新列举: " + mine.items.size() + " 项, 压缩后 " + mine.fileSize);
        File round = new File("out/round");
        deleteRec(round);
        Archives.extract(outZip, round, null);
        check("压缩→解压 往返一致", sameTree(new File("zsrc"), new File(round, "zsrc")));

        // ---------- 7. 单条目提取 ----------
        File single = new File("out/single/deep.bin");
        deleteRec(new File("out/single"));
        check("ZIP 单条目提取", Archives.extractEntry(new File("plain.zip"), "dirA/sub/deep.bin", single)
                && single.isFile()
                && Arrays.equals(Files.readAllBytes(single.toPath()), Files.readAllBytes(new File("zsrc/dirA/sub/deep.bin").toPath())));
        File singleTar = new File("out/single2/a.sh");
        deleteRec(new File("out/single2"));
        boolean tarEntryOk = Archives.extractEntry(new File("test.tar.gz"), "payload/dirA/a.sh", singleTar);
        check("TAR 单条目提取（带子目录时落点正确）", tarEntryOk && singleTar.isFile());
        // 只断言「文件在」是不够的：曾经真的出现过「文件在、内容是 0 字节」，
        // 原因是「命中即停」靠在 onEntry 里置取消，而 onEntry 是写数据之前回调的。
        check("TAR 单条目提取的内容不是空的、且与源文件一致",
                singleTar.length() > 0
                        && Arrays.equals(Files.readAllBytes(singleTar.toPath()),
                        Files.readAllBytes(new File("src/dirA/a.sh").toPath())));
        check("TAR 单条目提取后不留空目录", !new File("out/single2/payload").exists());

        // ---------- 8. 魔数探测（无扩展名） ----------
        Files.copy(new File("plain.zip").toPath(), new File("out/noext").toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        check("无扩展名靠魔数识别", Archives.kindOf(new File("out/noext")) == Archives.Kind.ZIP);
        Files.copy(new File("test.tar.gz").toPath(), new File("out/noext2").toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        check("无扩展名识别 tar.gz", Archives.kindOf(new File("out/noext2")) == Archives.Kind.TAR_GZ);

        // ---------- 9. tar 的长名字：GNU('L') 与 pax('x') 两种扩展头都要认 ----------
        //
        // 这一段是 3.12.0 补的，起因：原有夹具 test.tar / test.tar.gz 全是 **pax** 格式，
        // 于是 GNU 的 'L' 分支虽然写着「支持」，实际上一次都没被执行过。
        // GNU tar 是 Linux 的默认 tar，它写的 'L' 头载荷是「名字 + NUL」——
        // 末尾这个 NUL 不切掉，目录条目名就成了 `.../dir04/\0`：
        // `name.endsWith("/")` 判假的 → 目录被当普通文件 → 路径含 NUL 写不出去，
        // 而且**静默**：那条连同它下面所有条目一起消失，解压还报成功。
        for (String a : new String[]{"test-longname.tar", "test-pax-long.tar",
                "test.tar.bz2", "test.tar.xz"}) {
            File longDest = new File("out/long-" + a);
            deleteRec(longDest);
            int longCount = Archives.extract(new File(a), longDest, null);
            System.out.println("=== " + a + " -> " + longCount + " 个文件");
            Check.sameTree(a + " 长名条目全部落地", new File("src"), new File(longDest, "src"));
        }

        // ---------- 10. 数据安全边界 ----------
        File self = new File("out/self.zip");
        byte[] original = "do not truncate".getBytes("UTF-8");
        Files.write(self.toPath(), original);
        Throwable selfFailure = null;
        try {
            Archives.zip(java.util.Collections.singletonList(self), self, null);
        } catch (Throwable expected) {
            selfFailure = expected;
        }
        check("源与输出相同会在打开输出前拒绝", selfFailure instanceof java.io.IOException);
        check("源与输出相同时源内容不变", Arrays.equals(original, Files.readAllBytes(self.toPath())));

        File conflictZip = new File("out/conflict.zip");
        java.util.zip.ZipOutputStream conflictOut = new java.util.zip.ZipOutputStream(
                new java.io.FileOutputStream(conflictZip));
        conflictOut.putNextEntry(new java.util.zip.ZipEntry("foo/"));
        conflictOut.closeEntry();
        conflictOut.putNextEntry(new java.util.zip.ZipEntry("foo"));
        conflictOut.write(1);
        conflictOut.closeEntry();
        conflictOut.close();
        File conflictDest = new File("out/conflict");
        deleteRec(conflictDest);
        check("目录/文件冲突夹具创建", new File(conflictDest, "foo").mkdirs());
        Files.write(new File(conflictDest, "foo/keep.txt").toPath(), new byte[]{7});
        Archives.extract(conflictZip, conflictDest, null);
        check("目录/文件冲突跳过文件且保留目录", new File(conflictDest, "foo/keep.txt").isFile());

        File cancelDest = new File("out/cancel-zip");
        deleteRec(cancelDest);
        Throwable cancelled = null;
        final boolean[] cancelZipEntry = {false};
        try {
            Archives.extract(new File("plain.zip"), cancelDest, new Archives.Listener() {
                @Override public void onEntry(String name, long done, long total) { cancelZipEntry[0] = true; }
                @Override public boolean isCancelled() { return cancelZipEntry[0]; }
            }, null);
        } catch (Throwable expected) {
            cancelled = expected;
        }
        check("ZIP 取消会中止", cancelled instanceof Archives.Cancelled);
        check("ZIP 取消不留半成品", !new File(cancelDest, "dirA/a.sh").exists());

        File gbkTail = new File("out/gbk-tail.zip");
        java.util.zip.ZipOutputStream gbkOut = new java.util.zip.ZipOutputStream(
                new java.io.FileOutputStream(gbkTail), java.nio.charset.Charset.forName("GBK"));
        for (int i = 0; i < 201; i++) {
            gbkOut.putNextEntry(new java.util.zip.ZipEntry(String.format("a%03d.txt", i)));
            gbkOut.closeEntry();
        }
        gbkOut.putNextEntry(new java.util.zip.ZipEntry("文件名abc.txt"));
        gbkOut.closeEntry();
        gbkOut.close();
        Archives.Listing tailList = Archives.list(gbkTail);
        check("200 条之后的 GBK 名仍触发重读", "GBK".equals(tailList.charsetName));
        boolean tailFound = false;
        for (Archives.Item item : tailList.items) if (item.name.equals("文件名abc.txt")) tailFound = true;
        check("200 条之后的 GBK 名解码正确", tailFound);

        File loopRoot = new File("out/loop-zip-src");
        deleteRec(loopRoot);
        check("ZIP 链接环夹具目录创建", loopRoot.mkdirs());
        Files.write(new File(loopRoot, "payload.txt").toPath(), "payload".getBytes("UTF-8"));
        File loop = new File(loopRoot, "loop");
        boolean loopCreated = false;
        try {
            Files.createSymbolicLink(loop.toPath(), loopRoot.getAbsoluteFile().toPath());
            loopCreated = true;
        } catch (IOException | SecurityException | UnsupportedOperationException e) {
            System.out.println("  [skip] 当前系统不允许创建符号链接: " + e);
        }
        if (loopCreated) {
            File loopZip = new File("out/loop.zip");
            deleteRec(loopZip);
            Archives.zip(java.util.Collections.singletonList(loopRoot), loopZip, null);
            Archives.Listing loopList = Archives.list(loopZip);
            boolean hasLoop = false;
            for (Archives.Item item : loopList.items) {
                if (item.name.contains("loop")) hasLoop = true;
            }
            check("ZIP 压缩跳过符号链接环", !hasLoop);
            check("ZIP 链接环旁的普通文件仍被压缩", Check.hasFile(loopList, "loop-zip-src/payload.txt"));
            Files.deleteIfExists(loop.toPath());
        }
        deleteRec(loopRoot);

        System.out.println(failures == 0 ? "\nALL OK" : "\nFAILURES: " + failures);
        if (failures != 0) System.exit(1);
        // 第 9 段的计数记在 Check 里（它带目录树逐字节比对），收个尾
        Check.finish();
    }

    static void extractAndVerify(String archive, String sourceRoot, String destPath, String innerRoot)
            throws Exception {
        File dest = new File(destPath);
        deleteRec(dest);
        int count = Archives.extract(new File(archive), dest, null);
        System.out.println("=== 解压 " + archive + " -> " + count + " 个文件");
        File compare = innerRoot == null ? dest : new File(dest, innerRoot);
        check(archive + " 目录树一致", sameTree(new File(sourceRoot), compare));
    }

    /** 源树里的每个文件都要在目标里找到同名同内容的副本（反方向不做，允许有空目录条目） */
    static boolean sameTree(File src, File dst) throws Exception {
        File[] kids = src.listFiles();
        if (kids == null) return false;
        for (File k : kids) {
            File t = new File(dst, k.getName());
            if (k.isDirectory()) {
                if (!t.isDirectory() && !sameTree(k, t)) return false;
                if (t.isDirectory() && !sameTree(k, t)) return false;
            } else {
                if (!t.isFile()) {
                    System.out.println("    !! 缺失 " + t.getPath());
                    return false;
                }
                if (!Arrays.equals(Files.readAllBytes(k.toPath()), Files.readAllBytes(t.toPath()))) {
                    System.out.println("    !! 内容不同 " + t.getPath());
                    return false;
                }
            }
        }
        return true;
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  [ok] " : "  [FAIL] ") + name);
        if (!ok) failures++;
    }

    static void deleteRec(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRec(k);
        }
        f.delete();
    }
}
