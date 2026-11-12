package com.rxinfiles.tool;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 7z 读取与写入，基于 Apache Commons Compress（纯 Java，无 native 依赖）。
 *
 * 为什么能纯 Java：7z 的容器格式是公开的，压缩算法里最常见的那两种
 * （LZMA / LZMA2）由 XZ for Java 提供解码器 —— 也就是 [libs] 里那个 xz jar。
 * 这也是唯一一条必须带上它的原因：**没有它，标准 .7z 一个都解不开**。
 * 其余编码（BCJ/BCJ2/Delta/Deflate/Deflate64/BZip2/Copy）都在 commons-compress 里。
 * AES-256 加密的 7z 也能读，密码走 [SevenZFile] 的 char[] 构造参数。
 *
 * 与 RAR 侧的取舍完全一致：
 *
 *  - **严格顺序解码**。7z 的一个「folder」是若干文件共用的一段压缩流（默认就是
 *    solid），跳过某个文件只能靠**把它读掉**，不能靠 seek。所以过滤掉的条目
 *    也要读进 [NullSink]，否则拿到的字节是错位的。
 *  - **路径逃逸检查复用 [TarArchive.resolve]**，与 ZIP / tar / rar 同一套规则。
 *  - 7z 的 anti item（删除标记）不落地，只计 skipped。
 *  - **符号链接 / 设备节点不落地**，与 tar、RAR 两侧一致。7z 的 unix 模式位藏在
 *    「Windows 属性」这个 32 位字段的高 16 位里（`7zz a -snl` 造出来的链接是
 *    `0xA1FF8020`，高 16 位 `0xA1FF` 就是 `S_IFLNK | 0777`），见 [isSpecialAttr]。
 *    Windows 上建的 7z 这里高 16 位是 0，同一条判据对两种宿主都安全。
 *
 *    > 不判会怎样：条目本身没危险（我们写的是普通文件，不会真建出链接），
 *    > 但落下来的文件内容是**链接目标的路径文本** —— 用户点开看到一行
 *    > `/etc/hostname`，既不是链接也不是原文件，纯属误导。
 *
 * 本文件不依赖任何 Android API，可用 javac + java 在开发机上对真实归档跑校验。
 */
public final class SevenZArchive {

    private SevenZArchive() {
    }

    private static final int BUFFER = 128 * 1024;

    /** 一个归档条目 */
    public static final class Entry {
        public final String name;
        public final long size;
        public final boolean directory;
        /** 流式写入的条目可能没记大小 */
        public final boolean unknownSize;
        /** 7z 的删除标记（anti item），只存在于增量备份里 */
        public final boolean anti;
        /** unix 模式位里的特殊文件（符号链接 / fifo / 设备节点），解压时跳过 */
        public final boolean special;
        public final long time;
        public final boolean encrypted;

        Entry(String name, long size, boolean directory, boolean unknownSize, boolean anti,
              boolean special, long time) {
            this(name, size, directory, unknownSize, anti, special, time, false);
        }

        Entry(String name, long size, boolean directory, boolean unknownSize, boolean anti,
              boolean special, long time, boolean encrypted) {
            this.name = name;
            this.size = size;
            this.directory = directory;
            this.unknownSize = unknownSize;
            this.anti = anti;
            this.special = special;
            this.time = time;
            this.encrypted = encrypted;
        }
    }

    /** 解压 / 列举结果 */
    public static final class Result {
        public final List<Entry> entries = new ArrayList<>();
        public int extracted;
        public int skipped;
        public long totalBytes;
    }

    public interface Listener {
        void onEntry(String name, long done);

        boolean isCancelled();
    }

    /** 条目过滤：返回 false 的条目只读掉、不落地 */
    public interface Filter {
        boolean accept(String name);
    }

    /** 用户取消。继承 IOException 让调用方只需捕获 IOException */
    public static final class Cancelled extends IOException {
        public Cancelled() {
            super("cancelled");
        }
    }

    /** 读掉但不写盘 */
    private static final OutputStream NullSink = new OutputStream() {
        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    };

    // ------------------------------------------------------------------ 列举

    /**
     * 只读头部、不落地。
     *
     * 7z 的条目表在文件头里（可能是被压缩的），所以这一步不需要解压内容 ——
     * 但**如果连文件名都被加密**，就得给密码，否则这里直接抛异常。
     */
    public static List<Entry> list(File file, char[] password) throws IOException {
        // open() 必须在 try 里面：7z 的条目表在文件头里，**构造函数就会去读它**，
        // 「文件截断」这类错误是从这里抛出来的 —— 放在 try 外面就漏掉不翻译了。
        SevenZFile zf = null;
        try {
            zf = open(file, password);
            List<Entry> entries = new ArrayList<>();
            SevenZArchiveEntry e;
            while ((e = zf.getNextEntry()) != null) {
                long size = e.getSize();
                boolean unknown = size < 0;
                entries.add(new Entry(clean(e.getName()), unknown ? 0L : size, e.isDirectory(),
                        unknown, e.isAntiItem(), isSpecialAttr(e), timeOf(e), hasAes(e)));
            }
            sort(entries);
            return entries;
        } catch (Throwable t) {
            throw wrap(t);
        } finally {
            close(zf);
        }
    }

    // ------------------------------------------------------------------ 解压

    public static Result extract(File file, File destDir, Listener listener, char[] password,
                                 Filter filter) throws IOException {
        if (!destDir.isDirectory() && !destDir.mkdirs()) {
            throw new IOException("无法创建目标目录: " + destDir);
        }
        Result result = new Result();
        // 与 list() 同理：open() 也要在 try 里，构造期的错误同样要翻译
        SevenZFile zf = null;
        try {
            zf = open(file, password);
            SevenZArchiveEntry e;
            long done = 0L;
            while ((e = zf.getNextEntry()) != null) {
                if (listener != null && listener.isCancelled()) {
                    throw new Cancelled();
                }
                String name = clean(e.getName());
                boolean dir = e.isDirectory();
                long size = e.getSize();
                boolean unknown = size < 0;
                boolean special = isSpecialAttr(e);
                result.entries.add(new Entry(name, unknown ? 0L : size, dir, unknown,
                        e.isAntiItem(), special, timeOf(e)));

                boolean wanted = (filter == null || filter.accept(name))
                        && !e.isAntiItem() && !special;
                if (!wanted) {
                    result.skipped++;
                    copyTo(zf, NullSink, listener);
                    continue;
                }
                if (dir) {
                    File target = TarArchive.resolve(destDir, name);
                    if (target != null) {
                        //noinspection ResultOfMethodCallIgnored
                        target.mkdirs();
                    } else {
                        result.skipped++;
                    }
                    continue;
                }
                File target = TarArchive.resolve(destDir, name);
                if (target == null) {
                    result.skipped++;
                    copyTo(zf, NullSink, listener);
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    result.skipped++;
                    copyTo(zf, NullSink, listener);
                    continue;
                }
                if (listener != null) {
                    listener.onEntry(name, done);
                }
                OutputStream out = new FileOutputStream(target);
                Cancelled cancelled = null;
                try {
                    copyTo(zf, out, listener);
                } catch (Cancelled failure) {
                    cancelled = failure;
                    throw failure;
                } finally {
                    close(out);
                    if (cancelled != null) {
                        //noinspection ResultOfMethodCallIgnored
                        target.delete();
                    }
                }
                long modified = timeOf(e);
                if (modified > 0L) {
                    //noinspection ResultOfMethodCallIgnored
                    target.setLastModified(modified);
                }
                result.extracted++;
                done += unknown ? 0L : size;
                result.totalBytes += unknown ? 0L : size;
            }
            return result;
        } catch (Throwable t) {
            throw wrap(t);
        } finally {
            close(zf);
        }
    }

    /**
     * 只解压一个条目（预览用）。
     *
     * 命中后立刻停，不再读后面的内容。目标之前的条目仍要读掉（solid 决定）。
     */
    public static boolean extractEntry(File file, String entryName, File target, char[] password)
            throws IOException {
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            return false;
        }
        final String want = clean(entryName);
        SevenZFile zf = null;
        try {
            zf = open(file, password);
            SevenZArchiveEntry e;
            while ((e = zf.getNextEntry()) != null) {
                if (e.isDirectory() || e.isAntiItem() || isSpecialAttr(e)) {
                    continue;
                }
                if (clean(e.getName()).equals(want)) {
                    OutputStream out = new FileOutputStream(target);
                    try {
                        copyTo(zf, out, null);
                    } finally {
                        close(out);
                    }
                    long modified = timeOf(e);
                    if (modified > 0L) {
                        //noinspection ResultOfMethodCallIgnored
                        target.setLastModified(modified);
                    }
                    return true;
                }
                copyTo(zf, NullSink, null);
            }
            return false;
        } catch (Throwable t) {
            throw wrap(t);
        } finally {
            close(zf);
        }
    }

    // ------------------------------------------------------------------ 打包

    /**
     * 打包成 7z（LZMA2）。
     *
     * 条目名规则与 ZIP 侧一致：文件用文件名，目录保留内部结构。
     *
     * @return 写入的条目数
     */
    public static int sevenZip(List<File> sources, File out, Listener listener) throws IOException {
        final String outPath = out.getCanonicalPath();
        for (File source : sources) {
            if (source.getCanonicalPath().equals(outPath)) {
                throw new IOException("输出压缩包不能与源文件相同: " + out.getName());
            }
        }
        SevenZOutputFile zos = new SevenZOutputFile(out);
        try {
            zos.setContentCompression(SevenZMethod.LZMA2);
            int count = 0;
            for (File source : sources) {
                if (listener != null && listener.isCancelled()) {
                    throw new Cancelled();
                }
                if (Files.isSymbolicLink(source.toPath()) || !source.exists()) {
                    continue;
                }
                String base = source.getName();
                if (source.isDirectory()) {
                    SevenZArchiveEntry dir = new SevenZArchiveEntry();
                    dir.setName(base);
                    dir.setDirectory(true);
                    stamp(dir, source);
                    zos.putArchiveEntry(dir);
                    zos.closeArchiveEntry();
                    count += addTree(zos, source, base, outPath, listener, new HashSet<String>());
                } else {
                    if (source.getCanonicalPath().equals(outPath)) {
                        continue;
                    }
                    addFile(zos, source, base, listener);
                    count++;
                }
            }
            zos.finish();
            return count;
        } catch (Throwable t) {
            throw wrap(t);
        } finally {
            close(zos);
        }
    }

    private static int addTree(SevenZOutputFile zos, File dir, String prefix, String outPath,
                               Listener listener, Set<String> visited) throws IOException {
        if (!visited.add(dir.getCanonicalPath())) {
            return 0;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        java.util.Arrays.sort(children, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        int count = 0;
        for (File child : children) {
            if (listener != null && listener.isCancelled()) {
                throw new Cancelled();
            }
            if (Files.isSymbolicLink(child.toPath())) {
                continue;
            }
            String entry = prefix + "/" + child.getName();
            if (child.isDirectory()) {
                if (visited.contains(child.getCanonicalPath())) {
                    continue;
                }
                SevenZArchiveEntry dirEntry = new SevenZArchiveEntry();
                dirEntry.setName(entry);
                dirEntry.setDirectory(true);
                stamp(dirEntry, child);
                zos.putArchiveEntry(dirEntry);
                zos.closeArchiveEntry();
                count += addTree(zos, child, entry, outPath, listener, visited);
            } else {
                try {
                    if (child.getCanonicalPath().equals(outPath)) {
                        continue;
                    }
                } catch (IOException ignored) {
                    continue;
                }
                addFile(zos, child, entry, listener);
                count++;
            }
        }
        return count;
    }

    /**
     * 给条目盖上源文件的修改时间。
     *
     * **目录条目也要盖**：不盖的后果不只是「目录没时间」—— 读回来的时候
     * [timeOf] 会因为「没有这个时间戳」而抛异常（见那里的注释）。
     */
    private static void stamp(SevenZArchiveEntry e, File source) {
        long t = source.lastModified();
        if (t > 0L) {
            e.setLastModifiedDate(t);
        }
    }

    private static void addFile(SevenZOutputFile zos, File file, String entryName, Listener listener)
            throws IOException {
        if (listener != null) {
            listener.onEntry(entryName, 0L);
        }
        SevenZArchiveEntry e = new SevenZArchiveEntry();
        e.setName(entryName);
        e.setSize(file.length());
        // 必须显式置 true：不设的话 commons-compress 认为条目「没有数据流」，
        // 写进去的字节会被丢掉，解出来是 0 字节文件
        e.setHasStream(true);
        stamp(e, file);
        zos.putArchiveEntry(e);
        InputStream in = new BufferedInputStream(new FileInputStream(file), BUFFER);
        try {
            byte[] buf = new byte[BUFFER];
            int read;
            while ((read = in.read(buf)) > 0) {
                if (listener != null && listener.isCancelled()) {
                    throw new Cancelled();
                }
                zos.write(buf, 0, read);
            }
        } finally {
            close(in);
        }
        zos.closeArchiveEntry();
    }

    // ------------------------------------------------------------------ 内部

    private static SevenZFile open(File file, char[] password) throws IOException {
        if (password == null || password.length == 0) {
            return new SevenZFile(file);
        }
        return new SevenZFile(file, password);
    }

    /** 把当前条目的数据分块读出，并响应取消。 */
    private static void copyTo(SevenZFile zf, OutputStream out, Listener listener) throws IOException {
        byte[] buf = new byte[BUFFER];
        int read;
        while ((read = zf.read(buf, 0, buf.length)) > 0) {
            if (listener != null && listener.isCancelled()) throw new Cancelled();
            out.write(buf, 0, read);
        }
    }

    /**
     * 这个包是不是要密码（内容加密或头部加密都算）。
     *
     * **「头部能读出来」不等于「不要密码」。** `7zz a -p<pw>` 只加密内容，
     * 文件名和大小照样看得见 —— 只靠「不带密码打不开」来判，这种包会判成 false，
     * 于是界面不问密码，用户点解压才吃一个错。
     *
     * 第二道判据是条目的内容方法：实测不带密码也能读出
     * `[LZMA2 AES256SHA256]`，见到 AES 就是内容加密。
     */
    public static boolean needsPassword(File file) {
        SevenZFile zf = null;
        try {
            zf = open(file, null);
            SevenZArchiveEntry e;
            while ((e = zf.getNextEntry()) != null) {
                if (hasAes(e)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return looksLikePasswordProblem(t);
        } finally {
            close(zf);
        }
    }

    /** 条目的内容方法里带不带 AES-256 */
    private static boolean hasAes(SevenZArchiveEntry e) {
        Iterable<? extends SevenZMethodConfiguration> methods = e.getContentMethods();
        if (methods == null) {
            return false;
        }
        for (SevenZMethodConfiguration c : methods) {
            if (c.getMethod() == SevenZMethod.AES256SHA256) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把底层异常翻成人话再抛出。
     *
     * **这个包装以前漏了**：`humanize` 写好了却一次都没被调用，于是
     * commons-compress / xz 的英文原文（`nextHeaderOffset is out of bounds`、
     * `Compressed data is corrupt`…）就直接摊到用户面前。RAR 侧是包了的，两侧要对齐。
     *
     * [Cancelled] 必须原样透出：它是「用户主动取消」而不是错误，
     * 包一层会让调用方 `catch (SevenZArchive.Cancelled)` 抓不到。
     */
    private static IOException wrap(Throwable t) {
        if (t instanceof Cancelled) {
            return (Cancelled) t;
        }
        if (t instanceof IOException && t.getMessage() != null
                && isHumanMessageAlready(t.getMessage())) {
            return (IOException) t;
        }
        return new IOException(humanize(t), t);
    }

    /** 已经是中文人话就不再套一层，避免出现「（文件不完整（可能下载没下全））」 */
    private static boolean isHumanMessageAlready(String message) {
        return message.startsWith("这个 7z") || message.startsWith("文件不完整")
                || message.startsWith("这不是一个") || message.startsWith("这个文件")
                || message.startsWith("无法创建") || message.startsWith("非法的条目名");
    }

    static boolean looksLikePasswordProblem(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String n = c.getClass().getSimpleName().toLowerCase();
            String m = c.getMessage() == null ? "" : c.getMessage().toLowerCase();
            if (n.contains("password") || m.contains("password") || m.contains("encrypt")) {
                return true;
            }
        }
        return false;
    }

    static String humanize(Throwable t) {
        if (looksLikePasswordProblem(t)) {
            return "这个 7z 需要密码（文件名或内容被加密）";
        }
        String message = t.getMessage() == null ? "" : t.getMessage();
        String lower = message.toLowerCase();
        // 「头部读到了、内容没跟上来」这一类：commons-compress 原话是
        // `nextHeaderOffset is out of bounds`（条目表的位置超出文件末尾）。
        // 不翻译的话用户就看到这么一句英文，完全不知道下一步该干什么。
        if (lower.contains("unexpected end") || lower.contains("truncated")
                || lower.contains("out of bounds") || lower.contains("cannot read")) {
            return "文件不完整或已损坏（可能下载没下全）";
        }
        if (lower.contains("not a 7z") || lower.contains("signature")) {
            return "这不是一个 7z 文件";
        }
        if (lower.contains("unsupported") || lower.contains("no coder")) {
            return "这个 7z 用了暂不支持的压缩方法：" + message;
        }
        return message.isEmpty() ? t.getClass().getSimpleName() : message;
    }

    /**
     * 条目的修改时间，没记就返回 0。
     *
     * **`getLastModifiedDate()` 在条目没带时间戳时是「抛异常」，不是「返回 null」**
     * —— 抛的是 `UnsupportedOperationException`。自己用 [SevenZOutputFile] 写出来的包
     * 只要有一个条目没设时间（目录条目就很容易漏），读回来就会在这里崩，
     * 而且是崩在「把刚压好的包重新打开」这一步，非常难看。所以必须先问 `getHas...`。
     */
    private static long timeOf(SevenZArchiveEntry e) {
        if (!e.getHasLastModifiedDate()) {
            return 0L;
        }
        java.util.Date d = e.getLastModifiedDate();
        return d == null ? 0L : d.getTime();
    }

    private static String clean(String name) {
        return name == null ? "" : name.replace('\\', '/');
    }

    /**
     * unix 模式位判断特殊文件。
     *
     * 7z 把属性按宿主分成两种写法：Unix 侧是 `(unixMode << 16) | 0x8000`，
     * Windows 侧只有低 16 位的 DOS 属性。所以取高 16 位就得到模式位，
     * 而 Windows 建的包高 16 位恒为 0 —— 同一个表达式两边都安全：
     *
     * <pre>
     *   linkdir（目录）    0x41ED8010 → 高 16 位 0x41ED = S_IFDIR | 0755
     *   rel.txt（符号链接） 0xA1FF8020 → 高 16 位 0xA1FF = S_IFLNK | 0777
     *   target.txt（普通）  0x81A48020 → 高 16 位 0x81A4 = S_IFREG | 0644
     * </pre>
     *
     * 判据与 [RarArchive] 的 `isSpecialAttr(int)` 完全一致，不要各写一套。
     */
    private static boolean isSpecialAttr(SevenZArchiveEntry e) {
        if (!e.getHasWindowsAttributes()) {
            return false;
        }
        int type = (e.getWindowsAttributes() >>> 16) & 0xF000;
        return type == 0xA000 || type == 0x1000 || type == 0x2000
                || type == 0x6000 || type == 0xC000;
    }

    private static void sort(List<Entry> entries) {
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.directory != b.directory) {
                    return a.directory ? -1 : 1;
                }
                return a.name.compareToIgnoreCase(b.name);
            }
        });
    }

    private static void close(Object c) {
        if (c == null) {
            return;
        }
        try {
            if (c instanceof SevenZFile) {
                ((SevenZFile) c).close();
            } else if (c instanceof SevenZOutputFile) {
                ((SevenZOutputFile) c).close();
            } else if (c instanceof InputStream) {
                ((InputStream) c).close();
            } else if (c instanceof OutputStream) {
                ((OutputStream) c).close();
            }
        } catch (Throwable ignored) {
        }
    }
}
