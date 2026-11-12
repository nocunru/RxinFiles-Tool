package com.rxinfiles.tool;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

/** 校验「只解压选中条目」的过滤逻辑：命中正确、不命中不落地、流不错位、逃逸仍被拦。 */
public class SelectCheck {

    static int failures = 0;

    public static void main(String[] args) throws Exception {
        // 1. ZIP 单文件
        File dest = new File("out/sel1");
        deleteRec(dest);
        int n = Archives.extractSelected(new File("plain.zip"), dest, "dirA/sub/deep.bin", null);
        check("ZIP 单文件命中 1 项", n == 1);
        check("ZIP 单文件内容一致", Arrays.equals(
                Files.readAllBytes(new File(dest, "dirA/sub/deep.bin").toPath()),
                Files.readAllBytes(new File("zsrc/dirA/sub/deep.bin").toPath())));
        check("ZIP 单文件未夹带其它条目", new File(dest, "top.txt").exists() == false);

        // 2. ZIP 目录（应带出整棵子树）
        dest = new File("out/sel2");
        deleteRec(dest);
        n = Archives.extractSelected(new File("plain.zip"), dest, "dirA", null);
        check("ZIP 目录带出子树（2 个文件）", n == 2
                && new File(dest, "dirA/a.sh").isFile()
                && new File(dest, "dirA/sub/deep.bin").isFile());

        // 3. TAR 单文件（顺序流过滤，最容易出错的地方：跳过时必须消费数据段）
        dest = new File("out/sel3");
        deleteRec(dest);
        n = Archives.extractSelected(new File("test.tar"), dest, "payload/top.txt", null);
        check("TAR 单文件命中 1 项", n == 1);
        check("TAR 单文件内容一致", Arrays.equals(
                Files.readAllBytes(new File(dest, "payload/top.txt").toPath()),
                Files.readAllBytes(new File("src/top.txt").toPath())));

        // 4. TAR.GZ 目录
        dest = new File("out/sel4");
        deleteRec(dest);
        n = Archives.extractSelected(new File("test.tar.gz"), dest, "payload/dirA", null);
        check("TAR.GZ 目录带出子树（2 个文件）", n == 2
                && new File(dest, "payload/dirA/a.sh").isFile()
                && new File(dest, "payload/dirA/sub/deep.bin").isFile());

        // 5. 长路径条目（pax 名字覆盖）也要能被选中
        dest = new File("out/sel5");
        deleteRec(dest);
        String longName = "payload/verylongdirectoryname00/verylongdirectoryname01/"
                + "verylongdirectoryname02/verylongdirectoryname03/verylongdirectoryname04/"
                + "verylongdirectoryname05/long_filename_"
                + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.txt";
        n = Archives.extractSelected(new File("test_pax.tar"), dest, longName, null);
        check("pax 长路径条目可被选中", n == 1 && new File(dest, longName).isFile());

        // 6. 逃逸名仍然被拦
        deleteRec(new File("escaped2.txt"));
        dest = new File("out/sel6");
        deleteRec(dest);
        n = Archives.extractSelected(new File("evil.zip"), dest, "../../escaped2.txt", null);
        check("逃逸条目不可落地", n == 0 && !new File("escaped2.txt").exists());

        // 7. gzip 单文件条目名匹配
        dest = new File("out/sel7");
        deleteRec(dest);
        n = Archives.extractSelected(new File("single.txt.gz"), dest, "single.txt", null);
        check("gzip 条目名匹配即可解出", n == 1 && new File(dest, "single.txt").isFile());

        System.out.println(failures == 0 ? "\nALL OK" : "\nFAILURES: " + failures);
        if (failures != 0) {
            System.exit(1);
        }
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  [ok] " : "  [FAIL] ") + name);
        if (!ok) {
            failures++;
        }
    }

    static void deleteRec(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRec(k);
                }
            }
        }
        f.delete();
    }
}
