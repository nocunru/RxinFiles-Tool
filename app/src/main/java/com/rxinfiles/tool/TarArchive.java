package com.rxinfiles.tool;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * tar 归档读取器（纯 Java，无 Android 依赖）。
 *
 * Android 平台不带 tar 解析库，而 .tar / .tar.gz 在「shell 工具箱」场景里很常见
 * （工具链、固件包、备份），因此这里自己实现一个只读的解析器：
 *
 *  - USTAR：标准的 512 字节块 + prefix 字段（支持长路径）；
 *  - GNU longname（typeflag 'L'）/ longlink（'K'）；
 *  - pax 扩展头（'x' / 'g'），取其中的 path / size 覆盖；
 *  - 其余类型（符号链接、硬链接、设备节点、卷标）只登记不落地，
 *    解包时跳过 —— 这些条目在 Android 上要么无意义，要么是逃逸风险。
 *
 * 只做读取，不写 tar。压缩输出统一用 ZIP（见 ArchiveRepository）。
 *
 * 之所以用 Java 而不是 Kotlin：本文件不依赖任何 Android API，
 * 可以直接用 javac + java 在开发机上对真实归档跑校验，见 tools/tarcheck。
 */
public final class TarArchive {

    /** 一个归档条目 */
    public static final class Entry {
        public final String name;
        public final long size;
        public final boolean directory;
        public final long mtime;
        public final char type;
        public final String linkTarget;

        Entry(String name, long size, boolean directory, long mtime, char type, String linkTarget) {
            this.name = name;
            this.size = size;
            this.directory = directory;
            this.mtime = mtime;
            this.type = type;
            this.linkTarget = linkTarget;
        }
    }

    /** 解包结果 */
    public static final class Result {
        public final List<Entry> entries = new ArrayList<>();
        public int extracted;
        public int skipped;
        public long totalBytes;
    }

    /** 解包过程中的回调；返回前请检查 isCancelled() */
    public interface Listener {
        void onEntry(String name, long bytesWritten);

        boolean isCancelled();
    }

    /**
     * 条目过滤器：返回 false 的条目会被跳过。
     *
     * 注意「跳过」不等于「不读」—— tar 是顺序流，数据段必须照样消费掉，
     * 否则后面的块全部错位。所以跳过的条目只是不落地、计入 skipped。
     */
    public interface Filter {
        boolean accept(String name);
    }

    /** 用户取消。继承 IOException 是为了让调用方只需捕获 IOException */
    public static final class Cancelled extends IOException {
        public Cancelled() {
            super("cancelled");
        }
    }

    /** 单个 pax/GNU 扩展头允许的最大字节数，避免畸形归档把内存吃光 */
    private static final int MAX_META_BYTES = 16 * 1024 * 1024;

    private TarArchive() {
    }

    // ------------------------------------------------------------------ 列举

    /** 只列举条目，不写入任何文件 */
    public static List<Entry> list(InputStream in) throws IOException {
        Result r = new Result();
        scan(in, null, null, null, r);
        return r.entries;
    }

    // ------------------------------------------------------------------ 解包

    /**
     * 解包到 destDir。
     *
     * 路径会做归一化与逃逸检查（拒绝绝对路径、`..`、以及 canonical 落在目标目录之外的条目），
     * 也就是防 Zip Slip / Tar Slip。
     */
    public static Result extract(InputStream in, File destDir, Listener listener) throws IOException {
        return extract(in, destDir, listener, null);
    }

    /** 带过滤器的解包：只落地 accept() 返回 true 的条目 */
    public static Result extract(InputStream in, File destDir, Listener listener, Filter filter)
            throws IOException {
        Result r = new Result();
        if (!destDir.isDirectory() && !destDir.mkdirs()) {
            throw new IOException("无法创建目标目录: " + destDir);
        }
        scan(in, destDir, listener, filter, r);
        return r;
    }

    // ------------------------------------------------------------------ 核心

    private static void scan(InputStream in, File destDir, Listener listener, Filter filter, Result r)
            throws IOException {
        byte[] header = new byte[512];
        String pendingName = null;
        long pendingSize = -1L;
        Map<String, String> globalPax = new HashMap<>();
        long written = 0L;

        while (true) {
            if (listener != null && listener.isCancelled()) {
                throw new Cancelled();
            }
            if (!readFully(in, header)) {
                // 没有结尾零块，按正常结束处理（有些工具会省略）
                return;
            }
            if (isZeroBlock(header)) {
                // 第一个全零块即视为归档结束
                return;
            }

            String name = str(header, 0, 100);
            long size = octal(header, 124, 12);
            long mtime = octal(header, 136, 12) * 1000L;
            int type = header[156] & 0xFF;
            String prefix = str(header, 345, 155);
            String link = str(header, 157, 100);
            if (!name.isEmpty() && !prefix.isEmpty()) {
                name = prefix + "/" + name;
            }

            // GNU / pax 扩展头：只影响紧随其后的那个条目
            if (type == 'L') {
                pendingName = cstr(readData(in, size));
                continue;
            }
            if (type == 'K') {
                readData(in, size);
                continue;
            }
            if (type == 'x' || type == 'g') {
                Map<String, String> kv = parsePax(readData(in, size));
                if (type == 'g') {
                    globalPax.putAll(kv);
                    continue;
                }
                String p = kv.get("path");
                if (p != null && !p.isEmpty()) {
                    pendingName = p;
                }
                String s = kv.get("size");
                if (s != null) {
                    try {
                        pendingSize = Long.parseLong(s.trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                continue;
            }
            if (type == 'V') {
                readData(in, size);
                continue;
            }

            String globalName = globalPax.get("path");
            if (pendingName != null) {
                name = pendingName;
            } else if (globalName != null && !globalName.isEmpty()) {
                name = globalName;
            }
            pendingName = null;
            if (pendingSize >= 0L) {
                size = pendingSize;
            } else if (globalPax.containsKey("size")) {
                try {
                    size = Long.parseLong(globalPax.get("size").trim());
                } catch (NumberFormatException ignored) {
                }
            }
            pendingSize = -1L;

            boolean directory = type == '5' || name.endsWith("/");
            boolean regular = type == 0 || type == '0';

            r.entries.add(new Entry(name, size, directory, mtime, (char) type, link));

            if (destDir == null) {
                consume(in, size);
                continue;
            }

            if (filter != null && !filter.accept(name)) {
                // 不落地，但数据段必须照样消费掉，否则后续块全部错位
                r.skipped++;
                consume(in, size);
                continue;
            }

            if (directory) {
                File target = resolve(destDir, name);
                if (target != null) {
                    //noinspection ResultOfMethodCallIgnored
                    target.mkdirs();
                    if (mtime > 0L) {
                        //noinspection ResultOfMethodCallIgnored
                        target.setLastModified(mtime);
                    }
                } else {
                    r.skipped++;
                }
                consume(in, size);
            } else if (regular) {
                File target = resolve(destDir, name);
                if (target == null) {
                    r.skipped++;
                    consume(in, size);
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    r.skipped++;
                    consume(in, size);
                    continue;
                }
                if (listener != null) {
                    listener.onEntry(name, written);
                }
                OutputStream out = new FileOutputStream(target);
                Cancelled cancelled = null;
                try {
                    transfer(in, out, size, listener);
                } catch (Cancelled failure) {
                    cancelled = failure;
                    throw failure;
                } finally {
                    out.close();
                    if (cancelled != null) {
                        //noinspection ResultOfMethodCallIgnored
                        target.delete();
                    }
                }
                if (mtime > 0L) {
                    //noinspection ResultOfMethodCallIgnored
                    target.setLastModified(mtime);
                }
                written += size;
                r.totalBytes += size;
                r.extracted++;
            } else {
                // 符号链接 / 硬链接 / 设备节点 / fifo：不落地
                r.skipped++;
                consume(in, size);
            }
        }
    }

    /**
     * 把归档里的相对路径解析成目标目录下的文件。
     *
     * 拒绝：空名、绝对路径、包含 `..` 的路径、以及 canonical 化之后跑到目标目录外的路径。
     *
     * 包级可见：ZIP 解压（Archives）复用同一套逃逸检查，两处不能有两套规则。
     *
     * @return 非法时返回 null
     */
    static File resolve(File destDir, String rawName) {
        String name = rawName.replace('\\', '/');
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        if (name.isEmpty()) {
            return null;
        }
        while (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.isEmpty()) {
            return null;
        }
        for (String seg : name.split("/")) {
            if (seg.equals("..")) {
                return null;
            }
        }

        File target = new File(destDir, name);
        try {
            String root = destDir.getCanonicalPath();
            String path = target.getCanonicalPath();
            if (!path.equals(root) && !path.startsWith(root + File.separator)) {
                return null;
            }
        } catch (IOException e) {
            return null;
        }
        return target;
    }

    // ------------------------------------------------------------- 字节层工具

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int read = in.read(buf, off, buf.length - off);
            if (read < 0) {
                return false;
            }
            off += read;
        }
        return true;
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /** 取 NUL 结尾的 ASCII 字符串 */
    private static String str(byte[] block, int off, int len) {
        int end = off;
        int max = off + len;
        while (end < max && block[end] != 0) {
            end++;
        }
        return new String(block, off, end - off, StandardCharsets.UTF_8).trim();
    }

    /**
     * 读数字字段。tar 的数值是 ASCII 八进制，但 GNU 对超过 8GB 的值会用 base-256
     * （首字节最高位为 1，大端补码）。
     */
    private static long octal(byte[] block, int off, int len) throws IOException {
        if ((block[off] & 0x80) != 0) {
            long value = block[off] & 0x7F;
            for (int i = 1; i < len; i++) {
                if (value > (Long.MAX_VALUE >>> 8)) {
                    throw new IOException("tar 数值字段溢出");
                }
                value = (value << 8) | (block[off + i] & 0xFF);
            }
            return value;
        }
        long value = 0L;
        for (int i = 0; i < len; i++) {
            int c = block[off + i] & 0xFF;
            if (c == 0 || c == ' ') {
                if (value == 0L) {
                    continue; // 前导空格/空字段
                }
                break;
            }
            if (c < '0' || c > '7') {
                break;
            }
            value = (value << 3) + (c - '0');
        }
        return value;
    }

    /** 读取 size 字节的数据区（含 512 对齐补齐） */
    private static byte[] readData(InputStream in, long size) throws IOException {
        if (size <= 0L) {
            return new byte[0];
        }
        if (size > MAX_META_BYTES) {
            consume(in, size);
            return new byte[0];
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream((int) size);
        transfer(in, buffer, size, null);
        return buffer.toByteArray();
    }

    /** 把 size 字节从 in 抄到 out；out 为 null 时只跳过 */
    private static void transfer(InputStream in, OutputStream out, long size, Listener listener)
            throws IOException {
        byte[] buf = new byte[64 * 1024];
        long left = size;
        while (left > 0L) {
            if (listener != null && listener.isCancelled()) {
                throw new Cancelled();
            }
            int want = (int) Math.min(buf.length, left);
            int read = in.read(buf, 0, want);
            if (read < 0) {
                throw new IOException("归档数据意外结束");
            }
            if (out != null) {
                out.write(buf, 0, read);
            }
            left -= read;
        }
        long pad = (512 - (size % 512)) % 512;
        long skipped = 0L;
        while (skipped < pad) {
            long got = in.skip(pad - skipped);
            if (got <= 0L) {
                return;
            }
            skipped += got;
        }
    }

    private static void consume(InputStream in, long size) throws IOException {
        transfer(in, null, size, null);
    }

    /**
     * 把 GNU 扩展头的载荷当成 C 字符串读：切掉末尾的 NUL（可能不止一个，后面还有补齐）。
     *
     * **这个 NUL 必须切掉，否则是三条连环失效**：
     *
     *  1. 目录条目的名字变成 `src/dir04/\0`，`name.endsWith("/")` 恒为 false ——
     *     有些 GNU 归档的目录条目 typeflag 是 `0` 而不是 `5`，靠的就是这个尾斜杠判定，
     *     于是目录被当成普通文件处理；
     *  2. 路径里带 NUL 在任何系统上都不是合法文件名，创建必然失败；
     *  3. 最阴的是**失败是静默的**：这一条连同它下面所有条目一起消失，
     *     用户看到的是「解压成功、就是少东西」。
     *
     * 实测来自 `tar -cjf`（GNU tar 是 Linux 默认）造的 .tar.bz2 / .tar.xz：
     * `././@LongLink` 条的 size 是 `strlen(name) + 1`，最后那个字节就是 NUL。
     *
     * pax（`x` / `g`）没这个问题 —— 它的记录是「长度 + 空格 + 值」的文本行，
     * 所以**只测 pax 夹具永远发现不了这条**，`tools/archive-check` 里两种夹具现在都有。
     */
    private static String cstr(byte[] data) {
        int end = data.length;
        while (end > 0 && data[end - 1] == 0) {
            end--;
        }
        return new String(data, 0, end, StandardCharsets.UTF_8);
    }

    /**
     * 解析 pax 扩展头：每条记录形如 `25 path=xxx\n`，其中前缀数字是**整条记录的字节数**。
     *
     * 必须在字节空间里切分，不能先解码成字符串再按下标切 —— 长度前缀计的是字节，
     * 而 Java 字符串的每个下标是一个 UTF-16 码元，遇到中文路径这种多字节内容
     * 就会切歪（把下一条记录的长度前缀吃进 value 里）。
     */
    private static Map<String, String> parsePax(byte[] data) {
        Map<String, String> map = new HashMap<>();
        int index = 0;
        while (index < data.length) {
            int space = index;
            while (space < data.length && data[space] != (byte) ' ') {
                space++;
            }
            if (space >= data.length) {
                break;
            }
            int length;
            try {
                length = Integer.parseInt(
                        new String(data, index, space - index, StandardCharsets.US_ASCII).trim());
            } catch (NumberFormatException e) {
                break;
            }
            if (length <= 0 || index + length > data.length) {
                break;
            }

            int recStart = space + 1;
            int recEnd = index + length;
            if (recEnd > recStart && data[recEnd - 1] == (byte) '\n') {
                recEnd--;
            }

            int eq = recStart;
            while (eq < recEnd && data[eq] != (byte) '=') {
                eq++;
            }
            if (eq < recEnd) {
                String key = new String(data, recStart, eq - recStart, StandardCharsets.US_ASCII).trim();
                String value = new String(data, eq + 1, recEnd - eq - 1, StandardCharsets.UTF_8);
                map.put(key, value);
            }
            index += length;
        }
        return map;
    }

    /** 便于调用方传 null 时不必判空 */
    public static List<Entry> emptyList() {
        return Collections.emptyList();
    }
}
