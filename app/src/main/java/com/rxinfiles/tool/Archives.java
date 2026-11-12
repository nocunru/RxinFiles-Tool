package com.rxinfiles.tool;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.compress.archivers.zip.GeneralPurposeBit;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

/**
 * 归档工具（纯 Java，无 Android 依赖，可用 javac 独立校验）。
 *
 * 支持的读取格式：
 *  - **ZIP 家族**（zip / jar / apk / epub…）—— 用 JDK 的 ZipFile，带 GBK 文件名探测
 *  - **tar / tar.gz / tar.bz2 / tar.xz** —— 手工解析（[TarArchive]），压缩层只是包在外面的流
 *  - **gzip / bzip2 / xz / lzma 单文件** —— 解出来就是一个文件
 *  - **RAR 1.5 ~ 5.0** —— [RarArchive]（junrar，纯 Java）
 *  - **7z** —— [SevenZArchive]（Apache Commons Compress）
 *
 * 写入只出 ZIP 与 7z。tar 系列只读不写：Android 上没有可依赖的 tar 二进制，
 * 而「读别人的包」和「自己造一个 tar」是两个完全不同的需求。
 *
 * 几个刻意的取舍：
 *  - **ZIP 条目名做双编码探测**。国产压缩软件（以及 Windows 上的老版本）经常把
 *    GBK 文件名直接塞进 ZIP，按 UTF-8 解出来就是乱码。这里先用 UTF-8 读，
 *    只要发现替换字符 U+FFFD 就换 GBK 重读。
 *  - **解压统一做逃逸检查**。ZIP / tar / rar / 7z 复用同一套规则
 *    （见 TarArchive.resolve），拒绝 `../`、绝对路径、以及 canonical 落在目标目录之外的条目。
 *  - 符号链接、硬链接、设备节点一律不落地，只计入 skipped。
 *  - **加了三个第三方 jar**（见 app/libs/README.md）：junrar 认 RAR，xz 给 7z 提供
 *    LZMA/LZMA2 解码器，commons-compress 提供 7z 容器与 bzip2/xz 流。这三样都必须
 *    本地平铺进 libs/，构建才保持完全离线。
 */
public final class Archives {

    private static final int BUFFER = 128 * 1024;

    /** 归档类型 */
    public enum Kind {
        ZIP, TAR, TAR_GZ, TAR_BZ2, TAR_XZ, GZIP, BZIP2, XZ, LZMA, RAR, SEVEN_Z, UNKNOWN
    }

    /** 归档里的一个条目 */
    public static final class Item {
        public final String name;
        public final long size;
        public final long packed;
        public final boolean directory;
        public final long time;
        public final boolean unavailable;
        /** 这个条目是不是加密的（ZIP / RAR / 7z 均可逐条目标记） */
        public final boolean encrypted;

        Item(String name, long size, long packed, boolean directory, long time, boolean unavailable) {
            this(name, size, packed, directory, time, unavailable, false);
        }

        Item(String name, long size, long packed, boolean directory, long time, boolean unavailable,
             boolean encrypted) {
            this.name = name;
            this.size = size;
            this.packed = packed;
            this.directory = directory;
            this.time = time;
            this.unavailable = unavailable;
            this.encrypted = encrypted;
        }
    }

    /** 列举结果 */
    public static final class Listing {
        public final Kind kind;
        public final List<Item> items;
        public final long totalSize;
        public final long fileSize;
        public final String charsetName;

        Listing(Kind kind, List<Item> items, long totalSize, long fileSize, String charsetName) {
            this.kind = kind;
            this.items = items;
            this.totalSize = totalSize;
            this.fileSize = fileSize;
            this.charsetName = charsetName;
        }

        public int fileCount() {
            int n = 0;
            for (Item i : items) {
                if (!i.directory) {
                    n++;
                }
            }
            return n;
        }
    }

    public interface Listener {
        void onEntry(String name, long done, long total);

        boolean isCancelled();
    }

    public static final class Cancelled extends IOException {
        public Cancelled() {
            super("cancelled");
        }
    }

    // ------------------------------------------------------------------ 识别

    private static final Set<String> ZIP_EXT = new LinkedHashSet<String>();
    private static final Set<String> TAR_EXT = new LinkedHashSet<String>();
    private static final Set<String> RAR_EXT = new LinkedHashSet<String>();
    private static final Set<String> SEVEN_Z_EXT = new LinkedHashSet<String>();
    private static final Set<String> BZ2_EXT = new LinkedHashSet<String>();
    private static final Set<String> XZ_EXT = new LinkedHashSet<String>();
    private static final Set<String> LZMA_EXT = new LinkedHashSet<String>();

    static {
        Collections.addAll(ZIP_EXT, "zip", "jar", "apk", "xpi", "epub", "war", "aar", "kmz", "vsix", "zipx");
        Collections.addAll(TAR_EXT, "tar");
        Collections.addAll(RAR_EXT, "rar");
        Collections.addAll(SEVEN_Z_EXT, "7z");
        // tbz/tbz2 是「tar + bzip2」，不是单文件 bzip2 —— 认成前者才解得出里面的结构
        Collections.addAll(BZ2_EXT, "bz2", "bzip2", "tbz", "tbz2");
        Collections.addAll(XZ_EXT, "xz", "txz");
        Collections.addAll(LZMA_EXT, "lzma");
    }

    /** 是否是「可解压 / 可预览」的归档（按扩展名） */
    public static boolean isArchiveName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tar.gzip") || lower.endsWith(".tgz")
                || lower.endsWith(".tar.bz2") || lower.endsWith(".tar.xz")
                || lower.endsWith(".tar.lzma")) {
            return true;
        }
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) {
            return false;
        }
        String ext = lower.substring(dot + 1);
        if (ZIP_EXT.contains(ext) || TAR_EXT.contains(ext) || RAR_EXT.contains(ext)
                || SEVEN_Z_EXT.contains(ext) || BZ2_EXT.contains(ext) || XZ_EXT.contains(ext)
                || LZMA_EXT.contains(ext)) {
            return true;
        }
        return ext.equals("gz") || ext.equals("gzip");
    }

    /** 按扩展名判断类型；扩展名不可信时返回 UNKNOWN */
    public static Kind kindOfName(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tar.gzip") || lower.endsWith(".tgz")) {
            return Kind.TAR_GZ;
        }
        if (lower.endsWith(".tar.bz2") || lower.endsWith(".tbz") || lower.endsWith(".tbz2")) {
            return Kind.TAR_BZ2;
        }
        if (lower.endsWith(".tar.xz") || lower.endsWith(".txz")) {
            return Kind.TAR_XZ;
        }
        int dot = lower.lastIndexOf('.');
        String ext = dot < 0 ? "" : lower.substring(dot + 1);
        if (ZIP_EXT.contains(ext)) {
            return Kind.ZIP;
        }
        if (TAR_EXT.contains(ext)) {
            return Kind.TAR;
        }
        if (RAR_EXT.contains(ext)) {
            return Kind.RAR;
        }
        if (SEVEN_Z_EXT.contains(ext)) {
            return Kind.SEVEN_Z;
        }
        if (ext.equals("bz2") || ext.equals("bzip2")) {
            return Kind.BZIP2;
        }
        if (ext.equals("xz")) {
            return Kind.XZ;
        }
        if (LZMA_EXT.contains(ext)) {
            return Kind.LZMA;
        }
        if (ext.equals("gz") || ext.equals("gzip")) {
            return Kind.GZIP;
        }
        return Kind.UNKNOWN;
    }

    /**
     * 类型探测：先看扩展名，扩展名不认识再嗅探魔数。
     *
     * 魔数这一层是为了应付 `.apk.1`、无扩展名的下载包这类情况：
     *  - `PK\x03\x04` / `PK\x05\x06` → ZIP
     *  - `Rar!\x1a\x07\x00` / `Rar!\x1a\x07\x01\x00` → RAR（4.x / 5.0）
     *  - `7z\xbc\xaf\x27\x1c` → 7z
     *  - `BZh` → bzip2；`\xFD7zXZ\x00` → xz
     *  - `\x1F\x8B` → gzip；再解开前 512 字节看 `ustar` 判断里面是不是 tar
     *    （bzip2 / xz 同理：魔数只说明外层是什么，里面是不是 tar 要解开看一眼）
     */
    public static Kind kindOf(File file) {
        Kind byName = kindOfName(file.getName());
        if (byName != Kind.UNKNOWN) {
            return byName;
        }
        byte[] head = readHead(file, 512);
        if (head == null || head.length < 4) {
            return Kind.UNKNOWN;
        }
        int h0 = head[0] & 0xFF;
        int h1 = head[1] & 0xFF;
        if (h0 == 'P' && h1 == 'K') {
            return Kind.ZIP;
        }
        if (h0 == 'R' && h1 == 'a' && head.length > 6
                && head[2] == 'r' && head[3] == '!' && head[4] == 0x1A && head[5] == 0x07) {
            return Kind.RAR;
        }
        if (h0 == '7' && h1 == 'z' && head.length > 5
                && (head[2] & 0xFF) == 0xBC && (head[3] & 0xFF) == 0xAF
                && (head[4] & 0xFF) == 0x27 && (head[5] & 0xFF) == 0x1C) {
            return Kind.SEVEN_Z;
        }
        if (h0 == 'B' && h1 == 'Z' && head.length > 3 && head[2] == 'h') {
            return looksLikeTarIn(file, Kind.BZIP2) ? Kind.TAR_BZ2 : Kind.BZIP2;
        }
        if (h0 == 0xFD && h1 == '7' && head.length > 5
                && head[2] == 'z' && head[3] == 'X' && head[4] == 'Z' && head[5] == 0x00) {
            return looksLikeTarIn(file, Kind.XZ) ? Kind.TAR_XZ : Kind.XZ;
        }
        if (h0 == 0x1F && h1 == 0x8B) {
            return looksLikeTarIn(file, Kind.GZIP) ? Kind.TAR_GZ : Kind.GZIP;
        }
        if (head.length > 262 && head[257] == 'u' && head[258] == 's' && head[259] == 't') {
            return Kind.TAR;
        }
        return Kind.UNKNOWN;
    }

    /**
     * 解开外层压缩，只看前 512 字节里有没有 tar 的 `ustar` 标记。
     *
     * 判断的是「里面是不是 tar」，跟外层是什么压缩无关 —— 这也是为什么
     * `foo.tar.gz` 改名成 `foo.gz` 之后还能被认出来。
     */
    private static boolean looksLikeTarIn(File file, Kind outer) {
        InputStream in = null;
        try {
            in = new BufferedInputStream(openCompressed(file, outer), BUFFER);
            byte[] buf = new byte[512];
            int off = 0;
            while (off < buf.length) {
                int read = in.read(buf, off, buf.length - off);
                if (read < 0) {
                    break;
                }
                off += read;
            }
            return off > 262 && buf[257] == 'u' && buf[258] == 's' && buf[259] == 't';
        } catch (Throwable t) {
            return false;
        } finally {
            close(in);
        }
    }

    // ------------------------------------------------------------------ 列举

    /** 列举归档内容。失败抛 IOException（由调用方转成用户可读提示） */
    public static Listing list(File file) throws IOException {
        return list(file, null);
    }

    /**
     * 列举归档内容。
     *
     * @param password 只有 RAR / 7z 用得上（加密包）；不需要时传 null
     */
    public static Listing list(File file, char[] password) throws IOException {
        Kind kind = kindOf(file);
        switch (kind) {
            case ZIP:
                return listZip(file);
            case TAR:
            case TAR_GZ:
            case TAR_BZ2:
            case TAR_XZ:
                return listTar(file, kind);
            case GZIP:
            case BZIP2:
            case XZ:
            case LZMA:
                return listSingle(file, kind);
            case RAR:
                return listRar(file, password);
            case SEVEN_Z:
                return listSevenZ(file, password);
            default:
                throw new IOException("不认识的归档格式: " + file.getName());
        }
    }

    private static Listing listRar(File file, char[] password) throws IOException {
        RarArchive.Listing raw = RarArchive.list(file, password);
        List<Item> items = new ArrayList<>(raw.entries.size());
        long total = 0L;
        for (RarArchive.Entry e : raw.entries) {
            if (!e.directory) {
                total += e.size;
            }
            items.add(new Item(e.name, e.size, e.packed, e.directory, e.time, false, e.encrypted));
        }
        // RAR 的条目表本来就是按路径存的，但**能排就排** ——
        // 「列表是路径序」是面板画层级线的前提，四家格式必须给出同一个承诺，
        // 不能指望每个容器都恰好按这个顺序写。
        sort(items);
        return new Listing(Kind.RAR, items, total, file.length(), null);
    }

    private static Listing listSevenZ(File file, char[] password) throws IOException {
        List<SevenZArchive.Entry> raw = SevenZArchive.list(file, password);
        List<Item> items = new ArrayList<>(raw.size());
        long total = 0L;
        for (SevenZArchive.Entry e : raw) {
            if (!e.directory) {
                total += e.size;
            }
            items.add(new Item(e.name, e.size, 0L, e.directory, e.time, e.unknownSize, e.encrypted));
        }
        // 同 RAR：统一成路径序，面板的层级线才靠得住
        sort(items);
        return new Listing(Kind.SEVEN_Z, items, total, file.length(), null);
    }

    private static Listing listZip(File file) throws IOException {
        ZipOpen open = openZip(file);
        try {
            List<Item> items = new ArrayList<>();
            long total = 0L;
            Enumeration<ZipArchiveEntry> en = open.zip.getEntries();
            while (en.hasMoreElements()) {
                ZipArchiveEntry e = en.nextElement();
                String name = e.getName();
                boolean dir = e.isDirectory();
                long size = e.getSize();
                boolean unavailable = size < 0; // 以流式写入的条目可能没有 size
                if (unavailable) {
                    size = 0L;
                }
                if (!dir) {
                    total += size;
                }
                // 加密条目：文件名是明文，列表照列 —— 让用户看得见里面有什么，
                // 只是解压时需要密码
                items.add(new Item(name, size,
                        e.getCompressedSize() < 0 ? 0L : e.getCompressedSize(),
                        dir, e.getTime(), unavailable, ZipArchive.isEncrypted(e)));
            }
            sort(items);
            return new Listing(Kind.ZIP, items, total, file.length(),
                    open.charset == null ? "UTF-8" : open.charset.name());
        } finally {
            close(open.zip);
        }
    }

    private static Listing listTar(File file, Kind kind) throws IOException {
        InputStream in = null;
        try {
            in = openTar(file, kind);
            List<TarArchive.Entry> raw = TarArchive.list(in);
            List<Item> items = new ArrayList<>(raw.size());
            long total = 0L;
            for (TarArchive.Entry e : raw) {
                if (!e.directory) {
                    total += e.size;
                }
                items.add(new Item(e.name, e.size, 0L, e.directory, e.mtime, false));
            }
            sort(items);
            return new Listing(kind, items, total, file.length(), null);
        } finally {
            close(in);
        }
    }

    /**
     * 单文件压缩（gzip / bzip2 / xz / lzma）：解出来就是一个文件。
     *
     * 只有 gzip 在尾部记了原始长度（ISIZE），另外三种的记录方式要么没有、
     * 要么得解压才知道 —— 所以除 gzip 外一律标成「大小未知」，
     * 而不是编一个 0 出来骗用户。
     */
    private static Listing listSingle(File file, Kind kind) throws IOException {
        verifyCompressedHead(file, kind);
        String base = stripCompressedSuffix(file.getName(), kind);
        boolean known = kind == Kind.GZIP;
        long size = known ? gzipIsize(file) : 0L;
        Item item = new Item(base, size, file.length(), false, file.lastModified(), !known);
        List<Item> items = new ArrayList<Item>(1);
        items.add(item);
        return new Listing(kind, items, size, file.length(), null);
    }

    /**
     * 单文件流核对魔数，把「这不是 xz 数据」提前到打开面板的那一刻。
     *
     * 单文件流没有任何索引，[listSingle] 本来只是**按文件名推一个条目**出来 ——
     * 于是「一个改名叫 `.xz` 的文本文件」也能列出漂漂亮亮的 1 项，
     * 用户点了「解压」才吃一个错。代价只是读 6 个字节。
     *
     * **lzma 不查**：`alone` 格式没有可靠魔数（首字节是「属性」，合法取值范围
     * 几乎盖住所有字节值），查了只会误伤正常文件。它仍然只能等解压时报错。
     */
    private static void verifyCompressedHead(File file, Kind kind) throws IOException {
        if (kind == Kind.LZMA) {
            return;
        }
        byte[] head = readHead(file, 6);
        if (head == null) {
            throw new IOException("读不了这个文件");
        }
        boolean ok;
        switch (kind) {
            case GZIP:
                ok = head.length >= 2 && (head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B;
                break;
            case BZIP2:
                ok = head.length >= 3 && head[0] == 'B' && head[1] == 'Z' && head[2] == 'h';
                break;
            case XZ:
                ok = head.length >= 6 && (head[0] & 0xFF) == 0xFD && head[1] == '7'
                        && head[2] == 'z' && head[3] == 'X' && head[4] == 'Z' && head[5] == 0x00;
                break;
            default:
                ok = true;
                break;
        }
        if (!ok) {
            throw new IOException(humanizeCompressed(kind, new IOException("文件头不是该格式的魔数")));
        }
    }

    /**
     * 按**路径序（树序）**排：父目录紧挨着它自己的后代，同一目录下按名字忽略大小写。
     *
     * **绝不能用「目录全排前面、文件全排后面」那种全局分组** —— 那会把树拆散：
     * 目录行后面的第一个文件，看上去就成了那个目录的孩子。实测一个 8 条目的 7z
     * 会排成 `zsrc/`、`zsrc/dirA/`、`zsrc/dirA/sub/`、`zsrc/空目录/`、`zsrc/dirA/a.sh`……，
     * 面板里缩进一画，`a.sh` 正好落在「空目录」底下 —— 用户根本认不出它到底在哪个文件夹里。
     *
     * 算法就一句话：**目录名补上尾斜杠后，普通字符串比较就是路径序**。因为 `/`
     * （0x2F）比数字、字母、绝大多数可打印字符都小，所以
     * `a/` < `a/b` < `a/b/` < `a/b/c` < `a/z` —— 父目录恰好落在自己子树的前一位。
     * 缺尾斜杠的目录条目（部分 7z / tar）在这里补齐，好让两种写法归一。
     */
    private static void sort(List<Item> items) {
        Collections.sort(items, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return sortKey(a).compareToIgnoreCase(sortKey(b));
            }
        });
    }

    /** 目录一律带尾斜杠，这样字符串比较就等价于「按路径逐段比较」 */
    private static String sortKey(Item item) {
        if (!item.directory || item.name.endsWith("/")) {
            return item.name;
        }
        return item.name + "/";
    }

    // ------------------------------------------------------------------ 解压

    /**
     * 解压全部内容到 destDir。
     *
     * @return 落地的文件数
     */
    public static int extract(File file, File destDir, Listener listener) throws IOException {
        return extract(file, destDir, listener, null);
    }

    /**
     * 解压全部内容到 destDir。
     *
     * @param password 只有 RAR / 7z 用得上（加密包）；不需要时传 null
     * @return 落地的文件数
     */
    public static int extract(File file, File destDir, Listener listener, char[] password)
            throws IOException {
        Kind kind = kindOf(file);
        if (!destDir.isDirectory() && !destDir.mkdirs()) {
            throw new IOException("无法创建目标目录: " + destDir);
        }
        switch (kind) {
            case ZIP:
                return extractZip(file, destDir, listener, password);
            case TAR:
            case TAR_GZ:
            case TAR_BZ2:
            case TAR_XZ:
                return extractTar(file, kind, destDir, listener, null);
            case GZIP:
            case BZIP2:
            case XZ:
            case LZMA:
                return extractSingle(file, destDir, listener, kind);
            case RAR:
                return RarArchive.extract(file, destDir, rarListener(listener), password, null)
                        .extracted;
            case SEVEN_Z:
                return SevenZArchive.extract(file, destDir, sevenZListener(listener), password, null)
                        .extracted;
            default:
                throw new IOException("不认识的归档格式: " + file.getName());
        }
    }

    /**
     * 只解压「选中的那一个条目」。
     *
     * 目录条目会连同其下所有内容一起解出（用户点的是目录，想要的就是那棵子树）。
     * ZIP 有随机访问表，可以直接挑；tar 只能顺序扫，命中与否由过滤器决定 ——
     * 未命中的条目不落地但会被完整跳过，所以不会拖慢太多，也不会有错位风险。
     *
     * @param selected 与 [Listing] 中给出的条目名完全一致
     * @return 落地的文件数
     */
    public static int extractSelected(File archive, File destDir, String selected, Listener listener)
            throws IOException {
        return extractSelected(archive, destDir, selected, listener, null);
    }

    /**
     * 只解压「选中的那一个条目」。
     *
     * @param password 只有 RAR / 7z 用得上（加密包）；不需要时传 null
     */
    public static int extractSelected(File archive, File destDir, String selected, Listener listener,
                                      char[] password) throws IOException {
        Kind kind = kindOf(archive);
        if (!destDir.isDirectory() && !destDir.mkdirs()) {
            throw new IOException("无法创建目标目录: " + destDir);
        }
        final String target = selected.endsWith("/")
                ? selected.substring(0, selected.length() - 1)
                : selected;

        if (kind == Kind.RAR || kind == Kind.SEVEN_Z) {
            // RAR / 7z 都可能 solid：只能顺序过一遍，命中的落地、其余读掉
            final String want = target;
            if (kind == Kind.RAR) {
                RarArchive.Filter filter = new RarArchive.Filter() {
                    @Override
                    public boolean accept(String name) {
                        return matches(name, want);
                    }
                };
                return RarArchive.extract(archive, destDir, rarListener(listener), password, filter)
                        .extracted;
            }
            SevenZArchive.Filter filter = new SevenZArchive.Filter() {
                @Override
                public boolean accept(String name) {
                    return matches(name, want);
                }
            };
            return SevenZArchive.extract(archive, destDir, sevenZListener(listener), password, filter)
                    .extracted;
        }

        if (kind == Kind.ZIP) {
            ZipOpen open = openZip(archive);
            try {
                requireZipPassword(open.zip, password);
                int count = 0;
                Enumeration<ZipArchiveEntry> en = open.zip.getEntries();
                while (en.hasMoreElements()) {
                    if (listener != null && listener.isCancelled()) {
                        throw new Cancelled();
                    }
                    ZipArchiveEntry e = en.nextElement();
                    if (!matches(e.getName(), target)) {
                        continue;
                    }
                    File destination = TarArchive.resolve(destDir, e.getName());
                    if (destination == null) {
                        continue;
                    }
                    if (e.isDirectory()) {
                        //noinspection ResultOfMethodCallIgnored
                        destination.mkdirs();
                        continue;
                    }
                    if (!ensureParent(destination) || destination.isDirectory()) {
                        continue;
                    }
                    if (listener != null) {
                        listener.onEntry(e.getName(), 0L, e.getSize());
                    }
                    InputStream in = ZipArchive.open(open.zip, e, password);
                    OutputStream out = new FileOutputStream(destination);
                    Cancelled cancelled = null;
                    try {
                        copy(in, out, listener);
                    } catch (Cancelled failure) {
                        cancelled = failure;
                        throw failure;
                    } finally {
                        close(in);
                        close(out);
                        if (cancelled != null) {
                            //noinspection ResultOfMethodCallIgnored
                            destination.delete();
                        }
                    }
                    if (e.getTime() > 0) {
                        //noinspection ResultOfMethodCallIgnored
                        destination.setLastModified(e.getTime());
                    }
                    count++;
                }
                return count;
            } finally {
                close(open.zip);
            }
        }

        if (kind == Kind.GZIP || kind == Kind.BZIP2 || kind == Kind.XZ || kind == Kind.LZMA) {
            String base = stripCompressedSuffix(archive.getName(), kind);
            return matches(base, target) ? extractSingle(archive, destDir, listener, kind) : 0;
        }

        InputStream in = null;
        try {
            in = openTar(archive, kind);
            final String want = target;
            TarArchive.Filter filter = new TarArchive.Filter() {
                @Override
                public boolean accept(String name) {
                    return matches(name, want);
                }
            };
            TarArchive.Listener adapter = null;
            if (listener != null) {
                final Listener outer = listener;
                adapter = new TarArchive.Listener() {
                    @Override
                    public void onEntry(String name, long done) {
                        outer.onEntry(name, done, -1L);
                    }

                    @Override
                    public boolean isCancelled() {
                        return outer.isCancelled();
                    }
                };
            }
            return TarArchive.extract(in, destDir, adapter, filter).extracted;
        } finally {
            close(in);
        }
    }

    /** 条目自身或其子路径命中 */
    private static boolean matches(String name, String target) {
        if (name == null || target == null) {
            return false;
        }
        String normalized = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        return normalized.equals(target) || normalized.startsWith(target + "/");
    }

    private static int extractZip(File file, File destDir, Listener listener, char[] password)
            throws IOException {
        ZipOpen open = openZip(file);
        try {
            // 加密包必须先要到密码：不然后面每个条目都各报一次错，
            // 用户看到的是「解压了一半，剩下的全失败」
            requireZipPassword(open.zip, password);
            int done = 0;
            int skipped = 0;
            long total = 0L;
            Enumeration<ZipArchiveEntry> en = open.zip.getEntries();
            while (en.hasMoreElements()) {
                if (listener != null && listener.isCancelled()) {
                    throw new Cancelled();
                }
                ZipArchiveEntry e = en.nextElement();
                File target = TarArchive.resolve(destDir, e.getName());
                if (target == null) {
                    skipped++;
                    continue;
                }
                if (e.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    target.mkdirs();
                    continue;
                }
                File parent = target.getParentFile();
                if ((parent != null && !parent.isDirectory() && !parent.mkdirs())
                        || target.isDirectory()) {
                    skipped++;
                    continue;
                }
                if (listener != null) {
                    listener.onEntry(e.getName(), total, -1L);
                }
                InputStream in = ZipArchive.open(open.zip, e, password);
                OutputStream out = new FileOutputStream(target);
                Cancelled cancelled = null;
                try {
                    copy(in, out, listener);
                } catch (Cancelled failure) {
                    cancelled = failure;
                    throw failure;
                } finally {
                    close(in);
                    close(out);
                    if (cancelled != null) {
                        //noinspection ResultOfMethodCallIgnored
                        target.delete();
                    }
                }
                if (e.getTime() > 0) {
                    //noinspection ResultOfMethodCallIgnored
                    target.setLastModified(e.getTime());
                }
                total += Math.max(0L, e.getSize());
                done++;
            }
            return done;
        } finally {
            close(open.zip);
        }
    }

    /** 包里有加密条目但没给密码 —— 提前一整包报错，说得清楚一点 */
    private static void requireZipPassword(ZipFile zip, char[] password) throws IOException {
        if (password != null && password.length > 0) {
            return;
        }
        if (ZipArchive.anyEncrypted(zip)) {
            throw new IOException("这个 ZIP 有加密条目，需要密码才能解压");
        }
    }

    /**
     * 单文件压缩（gzip / bzip2 / xz / lzma）：解开写到目标目录。
     *
     * 目标名 = 去掉压缩后缀的原名（`foo.txt.gz` → `foo.txt`），
     * 与命令行的 `gunzip` 行为一致。
     */
    private static int extractSingle(File file, File destDir, Listener listener, Kind kind)
            throws IOException {
        String base = stripCompressedSuffix(file.getName(), kind);
        File target = TarArchive.resolve(destDir, base);
        if (target == null) {
            throw new IOException("非法的条目名: " + base);
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录: " + parent);
        }
        if (listener != null) {
            listener.onEntry(base, 0L, -1L);
        }
        InputStream in = null;
        OutputStream out = null;
        Cancelled cancelled = null;
        try {
            in = openCompressed(file, kind);
            out = new BufferedOutputStream(new FileOutputStream(target), BUFFER);
            copy(in, out, listener);
        } catch (Cancelled failure) {
            cancelled = failure;
            throw failure;
        } finally {
            close(in);
            close(out);
            if (cancelled != null) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
        }
        if (file.lastModified() > 0) {
            //noinspection ResultOfMethodCallIgnored
            target.setLastModified(file.lastModified());
        }
        return 1;
    }

    /** 解 tar（含各种压缩层），filter 为 null 表示全要 */
    private static int extractTar(File file, Kind kind, File destDir, Listener listener,
                                  TarArchive.Filter filter) throws IOException {
        InputStream in = null;
        try {
            in = openTar(file, kind);
            return TarArchive.extract(in, destDir, tarListener(listener), filter).extracted;
        } finally {
            close(in);
        }
    }

    /** 解压一个条目（先看一眼内容） */
    public static boolean extractEntry(File archive, String entryName, File target) throws IOException {
        return extractEntry(archive, entryName, target, null);
    }

    /**
     * 只解压一个条目（用于「先看一眼里面那个文件」）。
     *
     * tar / rar / 7z 都是顺序流，没有随机访问，所以只能从头扫到目标条目为止；
     * 命中后立刻让解析器停下，不为预览一个文件解完整个包。
     *
     * @param entryName 归档内的路径，必须与 [Listing] 里给出的完全一致
     * @param password  只有 RAR / 7z 用得上（加密包）；不需要时传 null
     */
    public static boolean extractEntry(File archive, String entryName, File target, char[] password)
            throws IOException {
        Kind kind = kindOf(archive);
        if (kind == Kind.RAR) {
            return RarArchive.extractEntry(archive, entryName, target, password);
        }
        if (kind == Kind.SEVEN_Z) {
            return SevenZArchive.extractEntry(archive, entryName, target, password);
        }
        if (kind == Kind.ZIP) {
            ZipOpen open = openZip(archive);
            try {
                ZipArchiveEntry e = open.zip.getEntry(entryName);
                if (e == null || e.isDirectory()) {
                    return false;
                }
                if (!ensureParent(target)) {
                    return false;
                }
                // 单条目也要密码：加密包只挑一个文件出来同样解不开
                if (ZipArchive.isEncrypted(e) && (password == null || password.length == 0)) {
                    throw new IOException("这个条目是加密的，需要密码才能解压");
                }
                InputStream in = ZipArchive.open(open.zip, e, password);
                OutputStream out = new FileOutputStream(target);
                try {
                    copy(in, out, null);
                } finally {
                    close(in);
                    close(out);
                }
                return true;
            } finally {
                close(open.zip);
            }
        }

        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            return false;
        }

        // 顺序流（tar / tar.gz / tar.bz2 / tar.xz）：没有索引，只能从头扫，
        // 所以把「只要这一个条目」交给过滤器，让解析器自己按序消费数据段。
        //
        // **这里刻意不用「onEntry 里置取消」来实现「命中即停」。**
        // onEntry 是在打开输出流、写数据**之前**回调的，在那里置取消，
        // transfer 会在写第一个字节前就抛 Cancelled —— 落下一个 0 字节文件，
        // 而调用方（预览）只问「文件在不在」，于是把空白当内容显示出来。
        // 3.12.0 修的就是这个；原来 ZipCheck 只断言「文件存在」，所以一直是绿的。
        final String want = entryName;
        InputStream in = null;
        try {
            in = openTar(archive, kind);
            TarArchive.extract(in, parent, null, new TarArchive.Filter() {
                @Override
                public boolean accept(String name) {
                    return matches(name, want);
                }
            });
        } finally {
            close(in);
        }

        File produced = TarArchive.resolve(parent, want);
        if (produced == null || !produced.isFile()) {
            return false;
        }
        if (produced.getAbsolutePath().equals(target.getAbsolutePath())) {
            return true;
        }
        // 条目名带子目录时会落在更深的位置，搬到调用方要求的地方
        boolean moved = produced.renameTo(target);
        if (!moved) {
            InputStream src = new FileInputStream(produced);
            OutputStream dst = new FileOutputStream(target);
            try {
                copy(src, dst, null);
                moved = true;
            } finally {
                close(src);
                close(dst);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        produced.delete();
        cleanupEmptyParents(produced.getParentFile(), parent);
        return moved;
    }

    private static boolean ensureParent(File target) {
        File parent = target.getParentFile();
        return parent == null || parent.isDirectory() || parent.mkdirs();
    }

    /** 删掉临时解出来的空目录，不留垃圾 */
    private static void cleanupEmptyParents(File start, File stopAt) {
        File current = start;
        String stop = stopAt == null ? null : stopAt.getAbsolutePath();
        while (current != null) {
            if (stop != null && current.getAbsolutePath().equals(stop)) {
                return;
            }
            String[] children = current.list();
            if (children == null || children.length != 0) {
                return;
            }
            if (!current.delete()) {
                return;
            }
            File parent = current.getParentFile();
            if (parent == null || parent.equals(stopAt)) {
                return;
            }
            current = parent;
        }
    }

    // ------------------------------------------------------------------ 压缩

    /**
     * 把若干文件/目录打包成 ZIP。
     *
     * 条目名规则（与原文件管理习惯一致）：
     *  - 文件：直接用文件名
     *  - 目录：以目录名开头，内部结构保留
     *
     * @return 写入的条目数
     */
    public static int zip(List<File> sources, File out, Listener listener) throws IOException {
        // 必须在 FileOutputStream 打开输出之前拒绝源 == 输出，否则源会先被截断。
        final String outPath = out.getCanonicalPath();
        for (File source : sources) {
            if (source.getCanonicalPath().equals(outPath)) {
                throw new IOException("输出压缩包不能与源文件相同: " + out.getName());
            }
        }
        int count = 0;

        ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(out), BUFFER), StandardCharsets.UTF_8);
        try {
            zos.setLevel(Deflater.DEFAULT_COMPRESSION);

            for (File source : sources) {
                if (listener != null && listener.isCancelled()) {
                    throw new Cancelled();
                }
                if (Files.isSymbolicLink(source.toPath()) || !source.exists()) {
                    continue;
                }
                String base = source.getName();
                if (source.isDirectory()) {
                    // 目录本身也要写一个条目，解压后才能保留空目录
                    putDir(zos, base + "/");
                    count += addTree(zos, source, base, outPath, listener, new HashSet<String>());
                } else {
                    if (source.getCanonicalPath().equals(outPath)) {
                        continue;
                    }
                    addFile(zos, source, base, listener);
                    count++;
                }
            }
            return count;
        } finally {
            close(zos);
        }
    }

    private static int addTree(ZipOutputStream zos, File dir, String prefix, String outPath,
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
                putDir(zos, entry + "/");
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

    private static void putDir(ZipOutputStream zos, String name) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(System.currentTimeMillis());
        zos.putNextEntry(e);
        zos.closeEntry();
    }

    private static void addFile(ZipOutputStream zos, File file, String entryName, Listener listener)
            throws IOException {
        if (listener != null) {
            listener.onEntry(entryName, 0L, file.length());
        }
        ZipEntry e = new ZipEntry(entryName);
        e.setTime(file.lastModified() > 0 ? file.lastModified() : System.currentTimeMillis());
        zos.putNextEntry(e);
        InputStream in = new BufferedInputStream(new FileInputStream(file), BUFFER);
        try {
            copy(in, zos, listener);
        } finally {
            close(in);
        }
        zos.closeEntry();
    }

    // ------------------------------------------------------------------ 内部

    private static final class ZipOpen {
        final ZipFile zip;
        final Charset charset;

        ZipOpen(ZipFile zip, Charset charset) {
            this.zip = zip;
            this.charset = charset;
        }
    }

    /**
     * 打开 ZIP，并对文件名做编码探测。
     *
     * 先用 UTF-8 读；只要有条目名里出现替换字符（说明原始字节不是合法 UTF-8），
     * 就改用 GBK 重读一次。ZIP 头里带 UTF-8 标志位的条目不受影响 ——
     * commons-compress 会优先采用标志位指定的 UTF-8。
     *
     * 用 commons-compress 而不是 `java.util.zip`：后者读加密条目只会拿到密文
     * （或者干脆抛异常），而前者能给出加密标志位与 `getRawInputStream()`
     * 的**原始密文** —— 那是自己解密的前提。
     */
    private static ZipOpen openZip(File file) throws IOException {
        try {
            ZipFile zip = new ZipFile(file, "UTF-8", true);
            if (!hasMojibake(zip)) {
                return new ZipOpen(zip, StandardCharsets.UTF_8);
            }
            close(zip);
        } catch (IOException ignored) {
            // 下面用 GBK 再试一次
        }
        try {
            return new ZipOpen(new ZipFile(file, "GBK", true), Charset.forName("GBK"));
        } catch (Throwable t) {
            throw new IOException("无法读取压缩包: " + friendlyZipError(t), t);
        }
    }

    /**
     * commons-compress 的报错对用户几乎没用，翻成人话。
     *
     * 它最常见的那条是 `Error on ZipFile <绝对路径>` —— 只有路径，没有原因。
     * 实际含义是「按中央目录找不到/读不出条目表」，也就是文件被截断、被改坏，
     * 或者根本不是 ZIP。把路径换掉、说清可能的原因。
     */
    private static String friendlyZipError(Throwable t) {
        String message = t.getMessage() == null ? "" : t.getMessage();
        if (message.startsWith("Error on ZipFile")) {
            return "文件不完整或已损坏（可能没下载完），也可能不是 ZIP";
        }
        if (message.isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return message;
    }

    /**
     * 条目名按 UTF-8 到底解不解得开 —— 直接问**原始字节**，不看解出来的字符串。
     *
     * 这里的判据换过一次，值得记一笔：
     *
     * 一开始找的是「解出来的名字里有没有 `U+FFFD`」。那是 `java.util.zip` 的行为 ——
     * 它用严格解码器，非法字节会被换成替换字符。3.12.0 把 ZIP 的读取换成
     * commons-compress（为了拿 `getRawInputStream()` 解加密条目）之后，
     * **这条判据静默失效了**：commons-compress 1.21 对非法 UTF-8 走的是宽松回退，
     * GBK 的 `ce c4 bc fe c3 fb`（「文件名」）会被解成 `?ļ???`，一个 `U+FFFD`
     * 都没有 —— 于是 GBK 包被当成 UTF-8 读，整个包的文件名全是乱码，
     * 而**所有断言照样是绿的**（ZipCheck 里那条 GBK 断言是唯一的哨兵，
     * 换库时它立刻变红，这才没把它漏过去）。
     *
     * 现在的判据只用原始字节，与解析器怎么解码无关：
     * 对 `getRawName()` 做**严格** UTF-8 解码，解不开就说明不是 UTF-8。
     */
    private static boolean hasMojibake(ZipFile zip) {
        CharsetDecoder strict = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        Enumeration<ZipArchiveEntry> en = zip.getEntries();
        while (en.hasMoreElements()) {
            ZipArchiveEntry entry = en.nextElement();
            GeneralPurposeBit bit = entry.getGeneralPurposeBit();
            if (bit != null && bit.usesUTF8ForNames()) {
                continue;   // 头部已经明说是 UTF-8，不用猜
            }
            byte[] raw = entry.getRawName();
            if (raw == null || raw.length == 0) {
                continue;   // 拿不到原始字节（只有本地头的那种条目）—— 判不了，跳过
            }
            try {
                strict.decode(ByteBuffer.wrap(raw));
            } catch (CharacterCodingException e) {
                return true;
            }
        }
        return false;
    }

    private static InputStream openTar(File file, Kind kind) throws IOException {
        switch (kind) {
            case TAR_GZ:
                return openCompressed(file, Kind.GZIP);
            case TAR_BZ2:
                return openCompressed(file, Kind.BZIP2);
            case TAR_XZ:
                return openCompressed(file, Kind.XZ);
            default:
                return new BufferedInputStream(new FileInputStream(file), BUFFER);
        }
    }

    /**
     * 打开「单文件压缩」并剥掉压缩层。
     *
     * bzip2 / xz 的随机访问版（可选参数 true）能显著加快顺序读，
     * 我们只做整包解压，用得上。
     */
    private static InputStream openCompressed(File file, Kind kind) throws IOException {
        if (kind == Kind.TAR_GZ) {
            kind = Kind.GZIP;
        } else if (kind == Kind.TAR_BZ2) {
            kind = Kind.BZIP2;
        } else if (kind == Kind.TAR_XZ) {
            kind = Kind.XZ;
        }
        InputStream raw = new BufferedInputStream(new FileInputStream(file), BUFFER);
        try {
            switch (kind) {
                case GZIP:
                    return new GZIPInputStream(raw, BUFFER);
                case BZIP2:
                    return new BZip2CompressorInputStream(raw, true);
                case XZ:
                    return new XZCompressorInputStream(raw, true);
                case LZMA:
                    return new LZMACompressorInputStream(raw, BUFFER);
                default:
                    return raw;
            }
        } catch (IOException e) {
            close(raw);
            throw new IOException(humanizeCompressed(kind, e), e);
        } catch (Throwable t) {
            close(raw);
            throw new IOException(humanizeCompressed(kind, t), t);
        }
    }

    private static String humanizeCompressed(Kind kind, Throwable t) {
        String name;
        switch (kind) {
            case BZIP2:
                name = "bzip2";
                break;
            case XZ:
                name = "xz";
                break;
            case LZMA:
                name = "lzma";
                break;
            default:
                name = "gzip";
                break;
        }
        String message = t.getMessage() == null ? "" : t.getMessage();
        return "这个文件不是有效的 " + name + " 数据" + (message.isEmpty() ? "" : "（" + message + "）");
    }

    /** 把监听器适配成 tar 侧的形状 */
    private static TarArchive.Listener tarListener(final Listener listener) {
        if (listener == null) {
            return null;
        }
        return new TarArchive.Listener() {
            @Override
            public void onEntry(String name, long done) {
                listener.onEntry(name, done, -1L);
            }

            @Override
            public boolean isCancelled() {
                return listener.isCancelled();
            }
        };
    }

    /** 把监听器适配成 7z 侧的形状 */
    private static SevenZArchive.Listener sevenZListener(final Listener listener) {
        if (listener == null) {
            return null;
        }
        return new SevenZArchive.Listener() {
            @Override
            public void onEntry(String name, long done) {
                listener.onEntry(name, done, -1L);
            }

            @Override
            public boolean isCancelled() {
                return listener.isCancelled();
            }
        };
    }

    /** 把监听器适配成 RAR 侧的形状 */
    private static RarArchive.Listener rarListener(final Listener listener) {
        if (listener == null) {
            return null;
        }
        return new RarArchive.Listener() {
            @Override
            public void onEntry(String name, long done) {
                listener.onEntry(name, done, -1L);
            }

            @Override
            public boolean isCancelled() {
                return listener.isCancelled();
            }
        };
    }

    /**
     * 去掉单文件压缩的后缀。
     *
     * 认不出后缀就退回 `原名 + .out` —— 与命令行的习惯一致，
     * 总比给出一个空名字（解压失败）好。
     */
    private static String stripCompressedSuffix(String name, Kind kind) {
        String lower = name.toLowerCase(Locale.US);
        String[] suffixes;
        switch (kind) {
            case BZIP2:
                suffixes = new String[]{".bzip2", ".bz2"};
                break;
            case XZ:
                suffixes = new String[]{".xz"};
                break;
            case LZMA:
                suffixes = new String[]{".lzma"};
                break;
            default:
                suffixes = new String[]{".gzip", ".gz"};
                break;
        }
        for (String suffix : suffixes) {
            if (lower.endsWith(suffix)) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return name + ".out";
    }

    private static void copy(InputStream in, OutputStream out, Listener listener) throws IOException {
        byte[] buf = new byte[BUFFER];
        int read;
        while ((read = in.read(buf)) > 0) {
            if (listener != null && listener.isCancelled()) {
                throw new Cancelled();
            }
            out.write(buf, 0, read);
        }
    }

    private static byte[] readHead(File file, int max) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            int len = (int) Math.min(max, file.length());
            byte[] buf = new byte[len];
            raf.readFully(buf);
            return buf;
        } catch (Throwable t) {
            return null;
        } finally {
            close(raf);
        }
    }

    /** gzip 尾部 4 字节是原始长度（ISIZE，小端），4GB 以内是准的 */
    private static long gzipIsize(File file) {
        RandomAccessFile raf = null;
        try {
            long len = file.length();
            if (len < 4) {
                return 0L;
            }
            raf = new RandomAccessFile(file, "r");
            raf.seek(len - 4);
            long b0 = raf.readUnsignedByte();
            long b1 = raf.readUnsignedByte();
            long b2 = raf.readUnsignedByte();
            long b3 = raf.readUnsignedByte();
            return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
        } catch (Throwable t) {
            return 0L;
        } finally {
            close(raf);
        }
    }

    // ------------------------------------------------------------------ 外部能力

    /**
     * 打包成 7z。
     *
     * 与 ZIP 并列作为**唯一**两种可创建的格式：它们是跨平台最通用的两种，
     * 而且都有成熟的纯 Java 实现。tar / rar 不提供创建 —— 前者 Android 上没有
     * 可信赖的 tar 二进制，后者根本没有合法的压缩器实现。
     */
    public static int sevenZip(List<File> sources, File out, Listener listener) throws IOException {
        return SevenZArchive.sevenZip(sources, out, sevenZListener(listener));
    }

    /**
     * 这个压缩包是不是要密码。
     *
     * 只有 RAR / 7z 可能加密；其余格式一律 false。判不出来时也返回 false ——
     * 「要不要提示输密码」错了顶多是白问一次，而误判成「加密」会让本来能解的包打不开。
     */
    public static boolean needsPassword(File file) {
        try {
            Kind kind = kindOf(file);
            if (kind == Kind.RAR) {
                return RarArchive.needsPassword(file);
            }
            if (kind == Kind.SEVEN_Z) {
                return SevenZArchive.needsPassword(file);
            }
            if (kind == Kind.ZIP) {
                // ZIP 的加密是**逐条目**的：文件名是明文，列表照样能列出来，
                // 只有解压才要密码 —— 所以这里问的是「有没有加密条目」
                return ZipArchive.needsPassword(file);
            }
        } catch (Throwable ignored) {
            // 探测失败就当作不需要密码，后面的正常流程会给出真正的错误
        }
        return false;
    }

    private static void close(Object c) {
        if (c == null) {
            return;
        }
        try {
            if (c instanceof ZipFile) {
                ((ZipFile) c).close();
            } else if (c instanceof InputStream) {
                ((InputStream) c).close();
            } else if (c instanceof OutputStream) {
                ((OutputStream) c).close();
            } else if (c instanceof RandomAccessFile) {
                ((RandomAccessFile) c).close();
            }
        } catch (Throwable ignored) {
        }
    }
}
