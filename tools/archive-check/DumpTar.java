package com.rxinfiles.tool;

import java.io.File;

/** 一次性探测：test.tar.bz2 解到不同长度的目标目录，看深层条目是不是还在 */
public class DumpTar {
    public static void main(String[] args) throws Exception {
        for (String dest : new String[]{"o/t", "out/tbz", "out/test.tar.bz2"}) {
            Check.deleteRec(new File(dest));
            int n = Archives.extract(new File("test.tar.bz2"), new File(dest), null);
            File deep = new File(dest,
                    "src/verylongdirectoryname00/verylongdirectoryname01/verylongdirectoryname02/"
                            + "verylongdirectoryname03/verylongdirectoryname04/verylongdirectoryname05");
            System.out.printf("dest=%-20s 落地=%d  深层目录存在=%s  绝对长度=%d%n",
                    dest, n, deep.isDirectory(), deep.getAbsolutePath().length());
            File f = new File(deep, "long_filename_"
                    + repeat('a', 90) + ".txt");
            System.out.printf("    深文件名存在=%s  长度=%d%n", f.isFile(), f.getAbsolutePath().length());
        }
    }

    static String repeat(char c, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(c);
        }
        return b.toString();
    }
}
