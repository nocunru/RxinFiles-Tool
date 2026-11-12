package com.rxinfiles.tool;

import com.github.junrar.Archive;
import com.github.junrar.ArchiveOptions;
import com.github.junrar.RarFormat;
import com.github.junrar.rarfile.FileHeader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * RAR 读取（RAR 1.5 ~ 5.0），基于 junrar（纯 Java，无 native 依赖）。
 *
 * 为什么不是 native：RAR 完全没有公开的压缩格式规范，纯 Java 实现只有 junrar 一家，
 * 而 native 的 unrar 要把「解压器」当命令分发 —— 那套机制（见 RxinToolbox）是为
 * busybox 准备的，为一种压缩格式再走一遍代价太大。junrar 8.x 已经同时支持
 * RAR 4.x 与 RAR 5.0 两种容器，正好覆盖 WinRAR 5 之后的默认格式。
 *
 * 几个刻意的取舍：
 *
 *  - **解压严格按头部顺序走一遍**。RAR 可以是 solid（若干文件共用一段压缩流），
 *    跳过一个文件不是「跳过一段字节」，而是必须把它**解码**掉才能对齐下一个。
 *    所以这里对「被过滤掉」的条目也照样调用一次解压、只是写进 [NullSink]。
 *    这正是 junrar 自己的 `Junrar.extract()` 的做法，行为一致比省 CPU 重要。
 *    代价：从 solid 包里预览最后一个文件要解完前面所有内容 —— 这是格式决定的，
 *    不是实现偷懒。
 *  - **路径逃逸检查复用 [TarArchive.resolve]**。ZIP / tar / rar 共用一套规则，
 *    三处不能各写一套。
 *  - **符号链接 / 设备节点不落地**（与 tar 侧一致）：这类条目解出来会变成
 *    「指向归档外」的链接，属于典型攻击面。RAR 里用 unix 模式位判断。
 *  - **分卷包不假装支持**：junrar 会尝试续读同目录下的下一个分卷，缺卷时抛
 *    MissingNextVolumeException —— 那句话会被翻成人话给用户，而不是报「损坏」。
 */
public final class RarArchive {

    private RarArchive() {
    }

    private static final int BUFFER = 128 * 1024;

    /** 一个归档条目 */
    public static final class Entry {
        public final String name;
        public final long size;
        public final long packed;
        public final boolean directory;
        public final boolean encrypted;
        /** unix 模式位里的特殊文件（符号链接 / fifo / 设备节点），解压时跳过 */
        public final boolean special;
        public final long time;

        Entry(String name, long size, long packed, boolean directory, boolean special,
              boolean encrypted, long time) {
            this.name = name;
            this.size = size;
            this.packed = packed;
            this.directory = directory;
            this.special = special;
            this.encrypted = encrypted;
            this.time = time;
        }
    }

    /** 列举结果 */
    public static final class Listing {
        public final List<Entry> entries;
        public final String format;
        public final boolean encrypted;
        public final int broken;
        public final long fileSize;

        Listing(List<Entry> entries, String format, boolean encrypted, int broken, long fileSize) {
            this.entries = entries;
            this.format = format;
            this.encrypted = encrypted;
            this.broken = broken;
            this.fileSize = fileSize;
        }
    }

    /** 解压结果 */
    public static final class Result {
        public final List<Entry> entries = new ArrayList<>();
        public int extracted;
        public int skipped;
        public long totalBytes;
    }

    /** 解压过程中的回调 */
    public interface Listener {
        void onEntry(String name, long done);

        boolean isCancelled();
    }

    /** 条目过滤：返回 false 的条目只解码、不落地 */
    public interface Filter {
        boolean accept(String name);
    }

    /** 用户取消。继承 IOException 让调用方只需捕获 IOException */
    public static final class Cancelled extends IOException {
        public Cancelled() {
            super("cancelled");
        }
    }

    /** 「解压到空设备」：被过滤掉的条目照样走一遍解码，只是不写盘 */
    private static final OutputStream NullSink = new OutputStream() {
        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    };

    private static final class CancellableOutputStream extends OutputStream {
        private final OutputStream out;
        private final Listener listener;

        CancellableOutputStream(OutputStream out, Listener listener) {
            this.out = out;
            this.listener = listener;
        }

        private void check() throws Cancelled {
            if (listener != null && listener.isCancelled()) throw new Cancelled();
        }

        @Override
        public void write(int b) throws IOException {
            check();
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            check();
            out.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    private static Cancelled cancellationOf(Throwable t) {
        for (Throwable current = t; current != null; current = current.getCause()) {
            if (current instanceof Cancelled) return (Cancelled) current;
        }
        return null;
    }

    // ------------------------------------------------------------------ 打开

    private static Archive open(File file, char[] password) throws IOException {
        try {
            if (password == null || password.length == 0) {
                return new Archive(file);
            }
            return new Archive(file, ArchiveOptions.builder().password(password).build());
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(humanize(t), t);
        }
    }

    /**
     * 这个包是否需要密码。
     *
     * 两种「加密」都要认：内容加密（文件名看得见，解压才要密码）与头部加密
     * （连文件名都读不出来）。后者连 `new Archive()` 都可能直接失败，
     * 所以异常也要当作「可能要密码」—— 密码错与需要密码在用户看来是同一件事。
     */
    public static boolean needsPassword(File file) {
        Archive archive = null;
        try {
            archive = new Archive(file);
            return archive.isEncrypted() || archive.isPasswordProtected();
        } catch (Throwable t) {
            return looksLikePasswordProblem(t);
        } finally {
            close(archive);
        }
    }

    // ------------------------------------------------------------------ 列举

    public static Listing list(File file, char[] password) throws IOException {
        Archive archive = open(file, password);
        try {
            List<FileHeader> heads = archive.getFileHeaders();
            List<Entry> entries = new ArrayList<>(heads.size());
            for (FileHeader h : heads) {
                if (h == null || h.isBrokenHeader() || !h.isFileHeader()) {
                    continue;
                }
                boolean dir = h.isDirectory();
                int attr = h.getFileAttr();
                boolean special = !dir && isSpecialAttr(attr);
                long time = h.getMTime() == null ? 0L : h.getMTime().getTime();
                entries.add(new Entry(
                        normalize(h.getFileName()),
                        dir ? 0L : Math.max(0L, h.getFullUnpackSize()),
                        Math.max(0L, h.getFullPackSize()),
                        dir, special, h.isEncrypted(), time));
            }
            sort(entries);
            return new Listing(entries, formatLabel(archive.getFormat()),
                    archive.isEncrypted() || archive.isPasswordProtected(),
                    archive.getHeaderFailures() == null ? 0 : archive.getHeaderFailures().size(),
                    file.length());
        } catch (Throwable t) {
            throw new IOException(humanize(t), t);
        } finally {
            close(archive);
        }
    }

    // ------------------------------------------------------------------ 解压

    /** 解压全部（filter 为 null 表示全要） */
    public static Result extract(File file, File destDir, Listener listener, char[] password,
                                 Filter filter) throws IOException {
        if (!destDir.isDirectory() && !destDir.mkdirs()) {
            throw new IOException("无法创建目标目录: " + destDir);
        }
        Result result = new Result();
        Archive archive = open(file, password);
        try {
            requirePasswordIfEncrypted(archive, password);
            List<FileHeader> heads = new ArrayList<>(archive.getFileHeaders());
            long done = 0L;
            for (FileHeader h : heads) {
                if (listener != null && listener.isCancelled()) {
                    throw new Cancelled();
                }
                if (h == null || h.isBrokenHeader() || !h.isFileHeader()) {
                    continue;
                }
                String name = normalize(h.getFileName());
                boolean dir = h.isDirectory();
                long size = dir ? 0L : Math.max(0L, h.getFullUnpackSize());
                int attr = h.getFileAttr();
                boolean special = !dir && isSpecialAttr(attr);
                result.entries.add(new Entry(name, size, Math.max(0L, h.getFullPackSize()),
                        dir, special, h.isEncrypted(),
                        h.getMTime() == null ? 0L : h.getMTime().getTime()));

                boolean wanted = filter == null || filter.accept(name);
                if (!wanted) {
                    result.skipped++;
                    // 不落地，但**必须**解码掉：solid 包里下一个文件的起点由它决定
                    drain(archive, h, listener);
                    continue;
                }
                if (dir) {
                    File target = TarArchive.resolve(destDir, name);
                    if (target != null) {
                        //noinspection ResultOfMethodCallIgnored
                        target.mkdirs();
                        setTime(target, h);
                    } else {
                        result.skipped++;
                    }
                    continue;
                }
                if (special) {
                    result.skipped++;
                    drain(archive, h, listener);
                    continue;
                }
                File target = TarArchive.resolve(destDir, name);
                if (target == null) {
                    result.skipped++;
                    drain(archive, h, listener);
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    result.skipped++;
                    drain(archive, h, listener);
                    continue;
                }
                if (listener != null) {
                    listener.onEntry(name, done);
                }
                OutputStream out = new CancellableOutputStream(new FileOutputStream(target), listener);
                boolean ok = true;
                try {
                    archive.extractFile(h, out);
                } catch (Throwable t) {
                    close(out);
                    Cancelled cancelled = cancellationOf(t);
                    if (cancelled != null) {
                        //noinspection ResultOfMethodCallIgnored
                        target.delete();
                        throw cancelled;
                    }
                    if (looksLikePasswordProblem(t)) {
                        // 「需要密码」是整包级别的，不能混在「这个条目坏了」里吞掉：
                        // 吞掉的后果是**解压「成功」返回、一个文件都没落地**，
                        // 用户只看到「解压了 0 个文件」，连要输密码都不知道。
                        throw new IOException(humanize(t), t);
                    }
                    // 其余情况（单个条目 CRC 坏了、数据缺一块）才是「跳过这个、继续下一个」
                    ok = false;
                } finally {
                    close(out);
                }
                if (ok) {
                    setTime(target, h);
                    result.extracted++;
                    done += size;
                    result.totalBytes += size;
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    target.delete();
                    result.skipped++;
                }
            }
            return result;
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(humanize(t), t);
        } finally {
            close(archive);
        }
    }

    /**
     * 只解压一个条目（预览用）。
     *
     * 命中后立刻停 —— 不再往下解后面的内容。
     */
    public static boolean extractEntry(File file, String entryName, File target, char[] password)
            throws IOException {
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            return false;
        }
        final String want = normalize(entryName);
        Archive archive = open(file, password);
        boolean landed = false;
        try {
            requirePasswordIfEncrypted(archive, password);
            for (FileHeader h : new ArrayList<>(archive.getFileHeaders())) {
                if (h == null || h.isBrokenHeader() || !h.isFileHeader() || h.isDirectory()) {
                    continue;
                }
                String name = normalize(h.getFileName());
                if (name.equals(want)) {
                    OutputStream out = new FileOutputStream(target);
                    try {
                        archive.extractFile(h, out);
                        landed = true;
                    } finally {
                        close(out);
                    }
                    setTime(target, h);
                    break;
                }
                // 目标之前的条目必须解码掉（solid 包），否则读到的是错的字节
                drain(archive, h);
            }
            return landed;
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException(humanize(t), t);
        } finally {
            close(archive);
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 加密包没给密码就早点报错，别让解压「成功」返回 0 个文件。
     *
     * **列举**故意不做这个检查：`-p` 只加密内容，文件名是明文的，
     * 让用户先看到里面有什么、再决定要不要输密码，比直接拒绝友好。
     * 只有真正要读字节的路径（解压 / 预览）才拦。
     */
    private static void requirePasswordIfEncrypted(Archive archive, char[] password)
            throws IOException {
        if (password != null && password.length > 0) {
            return;
        }
        boolean encrypted;
        try {
            // junrar 这两个方法声明的是 RarException（不是 IOException），得单独兜
            encrypted = archive.isEncrypted() || archive.isPasswordProtected();
        } catch (Throwable t) {
            encrypted = looksLikePasswordProblem(t);
        }
        if (encrypted) {
            throw new IOException("这个 RAR 需要密码（文件名或内容被加密）");
        }
    }

    /** 解压到空设备，只为把流对齐到下一个条目 */
    private static void drain(Archive archive, FileHeader h) throws IOException {
        drain(archive, h, null);
    }

    private static void drain(Archive archive, FileHeader h, Listener listener) throws IOException {
        try {
            archive.extractFile(h, new CancellableOutputStream(NullSink, listener));
        } catch (Throwable t) {
            Cancelled cancelled = cancellationOf(t);
            if (cancelled != null) throw cancelled;
            if (looksLikePasswordProblem(t)) {
                throw new IOException(humanize(t), t);
            }
            // 读不出来的条目没什么可对齐的，交给后面的条目自己报错
            if (t instanceof IOException) {
                throw (IOException) t;
            }
        }
    }

    private static void setTime(File target, FileHeader h) {
        if (h.getMTime() != null && h.getMTime().getTime() > 0L) {
            //noinspection ResultOfMethodCallIgnored
            target.setLastModified(h.getMTime().getTime());
        }
    }

    /**
     * unix 模式位判断特殊文件。
     *
     * 0xA000 符号链接、0x1000 fifo、0x2000 字符设备、0x6000 块设备、0xC000 socket。
     * Windows 建的 RAR 这里是 DOS 属性（0x01/0x10/0x20…），高位永远为 0，
     * 所以同一个表达式对两种宿主都安全。
     */
    private static boolean isSpecialAttr(int attr) {
        int type = attr & 0xF000;
        return type == 0xA000 || type == 0x1000 || type == 0x2000
                || type == 0x6000 || type == 0xC000;
    }

    private static String normalize(String name) {
        if (name == null) {
            return "";
        }
        return name.replace('\\', '/');
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

    private static String formatLabel(RarFormat format) {
        if (format == null) {
            return "RAR 压缩包";
        }
        switch (format) {
            case RAR50:
                return "RAR 5.0 压缩包";
            case RAR14:
                return "RAR 1.4 压缩包";
            default:
                return "RAR 4.x 压缩包";
        }
    }

    // ------------------------------------------------------------------ 异常

    /** 把 junrar 的英文异常翻成用户能读懂的一句话 */
    static String humanize(Throwable t) {
        String raw = wholeMessage(t);
        String lower = raw.toLowerCase();
        if (looksLikePasswordProblem(t)) {
            return "这个 RAR 需要密码（文件名或内容被加密）";
        }
        if (lower.contains("missingnextvolume") || lower.contains("next volume")) {
            return "这是分卷压缩包，且缺少后续分卷（请把所有分卷放在同一目录）";
        }
        if (lower.contains("missingpreviousvolume") || lower.contains("previous volume")) {
            return "这是分卷压缩包，当前不是第一个分卷（请从第一卷打开）";
        }
        if (lower.contains("unsupportedrarversion")) {
            return "junrar 认不出这个 RAR 版本（可能过新）";
        }
        if (lower.contains("unsupportedrarmethod")) {
            return "这个 RAR 用了暂不支持的压缩算法（多为加密或新版本特性）";
        }
        if (lower.contains("unsafeblink") || lower.contains("unsafelink")) {
            return "压缩包里有指向包外的链接，已拒绝解压";
        }
        if (lower.contains("badrararchive") || lower.contains("notrararchive")
                || lower.contains("corruptheader") || lower.contains("mainheadernull")) {
            return "文件损坏，或根本不是 RAR";
        }
        if (lower.contains("crc")) {
            return "校验失败（CRC 不匹配，文件可能损坏）";
        }
        return raw;
    }

    static boolean looksLikePasswordProblem(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String n = c.getClass().getSimpleName().toLowerCase();
            String m = c.getMessage() == null ? "" : c.getMessage().toLowerCase();
            if (n.contains("password") || n.contains("encrypt")
                    || m.contains("password") || m.contains("wrong password")
                    || m.contains("encrypted")) {
                return true;
            }
        }
        return false;
    }

    private static String wholeMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            String m = c.getMessage();
            if (m != null && !m.isEmpty() && sb.indexOf(m) < 0) {
                if (sb.length() > 0) {
                    sb.append(" / ");
                }
                sb.append(m);
            }
            if (sb.length() > 300) {
                break;
            }
        }
        return sb.length() == 0 ? t.getClass().getSimpleName() : sb.toString();
    }

    private static void close(Object c) {
        if (c instanceof Archive) {
            try {
                ((Archive) c).close();
            } catch (Throwable ignored) {
            }
        } else if (c instanceof OutputStream) {
            try {
                ((OutputStream) c).close();
            } catch (Throwable ignored) {
            }
        }
    }
}
