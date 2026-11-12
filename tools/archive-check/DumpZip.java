package com.rxinfiles.tool;

import org.apache.commons.compress.archivers.zip.GeneralPurposeBit;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.File;
import java.io.InputStream;
import java.util.Enumeration;

/**
 * 一次性探针：把加密 ZIP 条目的「元数据口径」打出来（不参与 run-checks）。
 *
 * 要回答的问题只有一个：**中央目录里写的 compressedSize 含不含
 * salt / 2 字节校验值 / 10 字节认证码**。
 *
 * `ZipArchive.openAes()` 里 `payload = compressed - saltLength - 2 - 10` 这一行
 * 对不对，全看这个。靠猜没用，必须量出来：
 * 把 `getRawInputStream()` 抽干，数一数真实能读到多少字节，再和字段值比。
 *
 *   用法：javac 之后 java com.rxinfiles.tool.DumpZip <x.zip> [密码]
 */
public class DumpZip {

    public static void main(String[] args) throws Exception {
        File f = new File(args[0]);
        char[] pw = args.length > 1 ? args[1].toCharArray() : null;
        System.out.println("=== " + f.getName() + "  " + f.length() + " bytes");
        try (ZipFile zip = new ZipFile(f, "UTF-8", true)) {
            for (Enumeration<ZipArchiveEntry> en = zip.getEntries(); en.hasMoreElements(); ) {
                ZipArchiveEntry e = en.nextElement();
                GeneralPurposeBit bit = e.getGeneralPurposeBit();
                System.out.println("  --- " + e.getName());
                System.out.println("      method=" + e.getMethod()
                        + " size=" + e.getSize()
                        + " compressed=" + e.getCompressedSize()
                        + " crc=0x" + Long.toHexString(e.getCrc())
                        + " time=" + (e.getTime() > 0 ? e.getTime() : -1)
                        + " dir=" + e.isDirectory());
                System.out.println("      bit(" + bit
                        + ") enc=" + bit.usesEncryption()
                        + " strong=" + bit.usesStrongEncryption()
                        + " descriptor=" + bit.usesDataDescriptor());
                System.out.println("      centralExtra=" + hex(e.getCentralDirectoryExtra()));
                System.out.println("      localExtra  =" + hex(e.getLocalFileDataExtra()));

                InputStream raw = zip.getRawInputStream(e);
                long counted = 0;
                if (raw != null) {
                    byte[] buf = new byte[8192];
                    int read;
                    while ((read = raw.read(buf)) > 0) {
                        counted += read;
                    }
                    raw.close();
                }
                System.out.println("      真实可读原始字节 = " + counted
                        + "   （字段值 " + e.getCompressedSize() + "）");
            }
        }
    }

    private static String hex(byte[] data) {
        if (data == null || data.length == 0) {
            return "(空)";
        }
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
