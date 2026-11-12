package com.rxinfiles.tool;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.zip.GZIPInputStream;

public class TarCheck {
    public static void main(String[] args) throws Exception {
        String[] files = {"test.tar", "test_gnu.tar", "test_pax.tar", "test.tar.gz", "test_gnu.tar.gz"};
        File outRoot = new File("out");
        int failures = 0;
        for (String f : files) {
            File archive = new File(f);
            List<TarArchive.Entry> listed = TarArchive.list(open(archive));
            System.out.println("=== " + f + " : " + listed.size() + " entries");
            for (TarArchive.Entry e : listed) {
                System.out.printf("    %-90s size=%-7d dir=%-5s type=%s%s%n",
                        e.name, e.size, e.directory, e.type,
                        e.linkTarget == null || e.linkTarget.isEmpty() ? "" : " -> " + e.linkTarget);
            }
            File dest = new File(outRoot, f.replace('.', '_'));
            deleteRec(dest);
            TarArchive.Result r = TarArchive.extract(open(archive), dest, null);
            System.out.printf("    extracted=%d skipped=%d bytes=%d%n", r.extracted, r.skipped, r.totalBytes);

            // 逐项核对源文件内容
            for (TarArchive.Entry e : listed) {
                if (!e.directory && (e.type == 0 || e.type == '0')) {
                    File src = new File("src/" + e.name.replaceFirst("^payload/", ""));
                    File dst = new File(dest, e.name);
                    if (!dst.isFile()) {
                        System.out.println("    !! 缺失: " + e.name);
                        failures++;
                        continue;
                    }
                    if (src.isFile() && !same(src, dst)) {
                        System.out.println("    !! 内容不一致: " + e.name);
                        failures++;
                    }
                }
            }
            // 目录结构核对
            File payload = new File(dest, "payload/dirA/sub/deep.bin");
            if (!payload.isFile()) {
                System.out.println("    !! 目录结构缺失");
                failures++;
            }
            // 符号链接不得落地
            if (new File(dest, "payload/link_to_top").exists()) {
                System.out.println("    !! 符号链接被落地（应为跳过）");
                failures++;
            }
        }
        // 逃逸测试：构造一个带 ../ 的恶意归档
        System.out.println("=== 逃逸检查");
        File evil = new File("evil.tar");
        makeEvil(evil);
        File dest = new File(outRoot, "evil");
        deleteRec(dest);
        TarArchive.Result r = TarArchive.extract(open(evil), dest, null);
        System.out.printf("    extracted=%d skipped=%d%n", r.extracted, r.skipped);
        if (new File("escaped.txt").exists()) {
            System.out.println("    !! 逃逸成功，安全检查失败");
            failures++;
        }
        if (r.skipped == 0) {
            System.out.println("    !! 恶意条目未被跳过");
            failures++;
        }
        File global = new File("out/global-pax.tar");
        makeGlobalPax(global);
        List<TarArchive.Entry> globalEntries = TarArchive.list(open(global));
        if (globalEntries.size() != 2 || globalEntries.get(0).size != 3
                || globalEntries.get(1).size != 3) {
            System.out.println("    !! pax 全局 size 没有作用于后续所有条目");
            failures++;
        }

        File overflow = new File("out/overflow.tar");
        byte[] badHeader = header("bad", 0);
        for (int i = 124; i < 136; i++) badHeader[i] = (byte) 0xFF;
        java.nio.file.Files.write(overflow.toPath(), badHeader);
        boolean overflowRejected = false;
        try {
            TarArchive.list(open(overflow));
        } catch (java.io.IOException expected) {
            overflowRejected = true;
        }
        if (!overflowRejected) {
            System.out.println("    !! base-256 溢出没有拒绝");
            failures++;
        }

        File cancelDest = new File(outRoot, "cancel-tar");
        deleteRec(cancelDest);
        boolean tarCancelled = false;
        final boolean[] cancelTarEntry = {false};
        try {
            TarArchive.extract(open(new File("test.tar")), cancelDest, new TarArchive.Listener() {
                @Override public void onEntry(String name, long bytesWritten) { cancelTarEntry[0] = true; }
                @Override public boolean isCancelled() { return cancelTarEntry[0]; }
            });
        } catch (TarArchive.Cancelled expected) {
            tarCancelled = true;
        }
        if (!tarCancelled || new File(cancelDest, "payload/dirA/a.sh").exists()) {
            System.out.println("    !! tar 取消后留下半成品");
            failures++;
        }

        System.out.println(failures == 0 ? "\nALL OK" : "\nFAILURES: " + failures);
        if (failures != 0) System.exit(1);
    }

    private static void makeGlobalPax(File out) throws Exception {
        byte[] record = "9 size=3\n".getBytes("UTF-8");
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] global = header("pax_global", record.length);
        global[156] = 'g';
        bos.write(global);
        bos.write(record);
        bos.write(new byte[512 - record.length]);
        for (String name : new String[]{"one", "two"}) {
            bos.write(header(name, 0));
            bos.write(new byte[]{1, 2, 3});
            bos.write(new byte[509]);
        }
        bos.write(new byte[1024]);
        java.nio.file.Files.write(out.toPath(), bos.toByteArray());
    }

    private static void makeEvil(File out) throws Exception {
        // 手工拼一个 tar：一个正常的文件 + 一个 ../../escaped.txt
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bos.write(header("../escaped.txt", 4));
        bos.write("evil".getBytes("UTF-8"));
        bos.write(new byte[508]);
        bos.write(header("ok.txt", 3));
        bos.write("yes".getBytes("UTF-8"));
        bos.write(new byte[509]);
        bos.write(new byte[1024]);
        java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
        fos.write(bos.toByteArray());
        fos.close();
    }

    private static byte[] header(String name, long size) throws Exception {
        byte[] b = new byte[512];
        byte[] n = name.getBytes("UTF-8");
        System.arraycopy(n, 0, b, 0, n.length);
        put(b, 100, "0000644");
        put(b, 108, "0000000");
        put(b, 116, "0000000");
        put(b, 124, String.format("%011o", size));
        put(b, 136, "00000000000");
        b[156] = '0';
        put(b, 257, "ustar");
        b[263] = '0';
        b[264] = '0';
        for (int i = 148; i < 156; i++) b[i] = ' ';
        long sum = 0;
        for (byte x : b) sum += (x & 0xFF);
        put(b, 148, String.format("%06o", sum));
        b[154] = 0;
        return b;
    }

    private static void put(byte[] b, int off, String s) {
        byte[] x = s.getBytes();
        System.arraycopy(x, 0, b, off, x.length);
    }

    private static InputStream open(File f) throws Exception {
        InputStream in = new BufferedInputStream(new FileInputStream(f));
        if (f.getName().endsWith(".gz")) in = new GZIPInputStream(in);
        return in;
    }

    private static boolean same(File a, File b) throws Exception {
        if (a.length() != b.length()) return false;
        byte[] x = java.nio.file.Files.readAllBytes(a.toPath());
        byte[] y = java.nio.file.Files.readAllBytes(b.toPath());
        return java.util.Arrays.equals(x, y);
    }

    private static void deleteRec(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRec(k);
        f.delete();
    }
}
