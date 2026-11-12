package com.rxinfiles.tool;

import org.apache.commons.compress.archivers.zip.GeneralPurposeBit;
import org.apache.commons.compress.archivers.zip.UnsupportedZipFeatureException;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 加密 ZIP 的解密支持 —— 传统 PKWARE（ZipCrypto）与 WinZip AES（AE-1 / AE-2）。
 *
 * **为什么要自己写**：ZIP 加密不在任何标准库里 —— `java.util.zip` 完全没有，
 * commons-compress 只做容器（读加密条目直接抛 `UnsupportedZipFeatureException`），
 * Android 上也没有能直接用的实现。两种算法都很短，自己实现反而更可控。
 *
 * 形态：
 *
 *  - **ZipCrypto**（`zip -e`、7-Zip 默认）：数据前有 **12 字节**随机头，密钥由密码
 *    初始化后用 CRC32 表滚动更新、流式异或。最后一字节要和 CRC 高字节
 *    （设了数据描述符标志时是 DOS 时间高字节）对上 —— 用来挡错密码。
 *  - **AES（WinZip）**：`salt` | `2 字节校验值` | 密文 | `10 字节认证码`。
 *    密钥 = PBKDF2-HMAC-SHA1(密码, salt, 1000 轮, 2×密钥长 + 2)，前段是 AES 密钥、
 *    中段是 HMAC 密钥、末 2 字节是校验值。密文用 AES-CTR（计数器**小端**、从 1 开始），
 *    认证码是 HMAC-SHA1 前 10 字节。AES 条目的 method 是 99，真实压缩方法藏在
 *    extra field `0x9901` 里。
 *
 * 密码按**字节**参与运算，而不同工具编码不同（Windows 用本机代码页，Linux 用 UTF-8），
 * 所以 UTF-8 / GBK / 原始 8 位三种都试一遍，谁能通过校验用谁。
 *
 * 本文件不依赖任何 Android API —— 可以用 javac + java 在开发机上对真实夹具跑校验。
 */
public final class ZipArchive {

    public static final int METHOD_STORED = 0;
    public static final int METHOD_DEFLATED = 8;

    private static final int BUFFER = 32 * 1024;
    private static final int AES_EXTRA_ID = 0x9901;
    private static final int ZIPCRYPTO_HEADER = 12;
    private static final int AES_AUTH_LEN = 10;
    private static final int AES_ITERATIONS = 1000;
    private static final int AES_METHOD = 99;

    /** CRC32 表：ZipCrypto 的密钥更新用的就是它 */
    private static final int[] CRC_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) {
                c = ((c & 1) != 0) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
            }
            CRC_TABLE[i] = c;
        }
    }

    private ZipArchive() {
    }

    // ------------------------------------------------------------------ 对外

    /** 条目有没有加密（通用标志位 bit 0） */
    public static boolean isEncrypted(ZipArchiveEntry entry) {
        GeneralPurposeBit bit = entry.getGeneralPurposeBit();
        return bit != null && bit.usesEncryption();
    }

    /** 包里有没有任何加密条目 */
    public static boolean anyEncrypted(ZipFile zip) {
        for (Enumeration<ZipArchiveEntry> en = zip.getEntries(); en.hasMoreElements(); ) {
            if (isEncrypted(en.nextElement())) {
                return true;
            }
        }
        return false;
    }

    /** 这个 ZIP 要不要密码（读不了就当不用密码，交给上层报别的错） */
    public static boolean needsPassword(File file) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(file, "UTF-8", true);
            return anyEncrypted(zip);
        } catch (Throwable t) {
            return false;
        } finally {
            close(zip);
        }
    }

    /**
     * 打开一个条目，拿到**解密并解压后**的明文流。
     *
     * 明文条目走 commons-compress 自己的路径；加密条目在这里解密。密码不对会在
     * 打开时（头部校验）或读完时（CRC / 认证码）抛 [IOException]，消息给人看。
     */
    public static InputStream open(ZipFile zip, ZipArchiveEntry entry, char[] password)
            throws IOException {
        if (!isEncrypted(entry)) {
            try {
                return zip.getInputStream(entry);
            } catch (UnsupportedZipFeatureException e) {
                throw new IOException("不支持这个 ZIP 的压缩方法（" + e.getFeature() + "）", e);
            } catch (IOException e) {
                throw new IOException("读不出条目内容: " + e.getMessage(), e);
            }
        }

        Crypt crypt = cryptOf(entry);
        if (crypt == null) {
            throw new IOException("不支持这个 ZIP 的加密方式（可能是 PKWARE 强加密）");
        }
        if (password == null || password.length == 0) {
            throw new IOException("这个 ZIP 有加密条目，需要密码（例如：" + entry.getName() + "）");
        }

        InputStream raw = zip.getRawInputStream(entry);
        if (raw == null) {
            throw new IOException("读不出加密条目的原始数据: " + entry.getName());
        }
        return crypt.aes ? openAes(raw, entry, crypt, password) : openZipCrypto(raw, entry, crypt, password);
    }

    // ------------------------------------------------------------------ 条目形态

    /** 加密参数 */
    public static final class Crypt {
        final boolean aes;
        final int aesVersion;
        final int aesStrength;
        final int method;

        Crypt(boolean aes, int aesVersion, int aesStrength, int method) {
            this.aes = aes;
            this.aesVersion = aesVersion;
            this.aesStrength = aesStrength;
            this.method = method;
        }

        int keyLength() {
            switch (aesStrength) {
                case 1:
                    return 16;
                case 2:
                    return 24;
                default:
                    return 32;
            }
        }

        /** 给断言程序看的一句话 */
        public String label() {
            if (!aes) {
                return "ZipCrypto";
            }
            switch (aesStrength) {
                case 1:
                    return "AES-128";
                case 2:
                    return "AES-192";
                default:
                    return "AES-256";
            }
        }
    }

    /**
     * 判断条目的加密形态。
     *
     * AES 条目的 method 是 99，真实压缩方法在 extra field `0x9901` 末尾两字节；
     * 拿不到该字段、method 又不是 99 的，就是传统 ZipCrypto。
     * 返回 null 表示「认不出来」，调用方据此报错。
     */
    public static Crypt cryptOf(ZipArchiveEntry entry) {
        GeneralPurposeBit bit = entry.getGeneralPurposeBit();
        if (bit == null || !bit.usesEncryption()) {
            return null;
        }
        int[] aes = parseAesExtra(entry.getCentralDirectoryExtra());
        if (aes == null) {
            aes = parseAesExtra(entry.getLocalFileDataExtra());
        }
        if (aes != null) {
            return new Crypt(true, aes[0], aes[1], aes[2]);
        }
        if (entry.getMethod() == AES_METHOD || bit.usesStrongEncryption()) {
            // 99 却没有 AES 字段 / 标记了强加密：PKWARE 强加密是另一套东西，不做
            return null;
        }
        return new Crypt(false, 0, 0, entry.getMethod());
    }

    /** extra field 0x9901：version(2) vendor(2) strength(1) 真实 method(2) */
    private static int[] parseAesExtra(byte[] extra) {
        if (extra == null) {
            return null;
        }
        int p = 0;
        while (p + 4 <= extra.length) {
            int id = (extra[p] & 0xff) | ((extra[p + 1] & 0xff) << 8);
            int size = (extra[p + 2] & 0xff) | ((extra[p + 3] & 0xff) << 8);
            int start = p + 4;
            if (start + size > extra.length) {
                return null;
            }
            if (id == AES_EXTRA_ID && size >= 7) {
                int version = (extra[start] & 0xff) | ((extra[start + 1] & 0xff) << 8);
                boolean vendor = ((char) extra[start + 2]) == 'A' && ((char) extra[start + 3]) == 'E';
                int strength = extra[start + 4] & 0xff;
                int method = (extra[start + 5] & 0xff) | ((extra[start + 6] & 0xff) << 8);
                if (vendor && strength >= 1 && strength <= 3) {
                    return new int[]{version, strength, method};
                }
            }
            p = start + size;
        }
        return null;
    }

    // ------------------------------------------------------------------ ZipCrypto

    /** 传统 PKWARE：12 字节头 + 流式异或；密码靠头部末字节自证 */
    private static InputStream openZipCrypto(InputStream raw, ZipArchiveEntry entry, Crypt crypt,
                                             char[] password) throws IOException {
        long compressed = entry.getCompressedSize();
        if (compressed <= ZIPCRYPTO_HEADER) {
            throw new IOException("加密条目的长度不对（文件不完整？）");
        }
        BufferedInputStream in = new BufferedInputStream(raw, BUFFER);
        byte[] head = new byte[ZIPCRYPTO_HEADER];
        if (readFully(in, head) < ZIPCRYPTO_HEADER) {
            throw new IOException("加密数据不完整（文件不完整？）");
        }

        IOException last = null;
        for (byte[] candidate : passwordBytes(password)) {
            byte[] probe = head.clone();
            new ZipCrypto(candidate).decode(probe, 0, ZIPCRYPTO_HEADER);
            if (!headerMatches(probe, entry)) {
                last = new IOException("ZIP 密码不对");
                continue;
            }
            // 命中。**那 12 字节随机头必须先从流里摘掉**，不能交给解压器：
            // 它是加密层自己的头，明文形态是一堆随机字节，不是压缩数据的一部分。
            // （实测 7-Zip 写的 ZipCrypto 条目，`compressedSize` 正好 = 12 + 明文长度，
            //   忘了跳过就会让 inflate 读到 12 个前导垃圾字节 —— Store 条目会多出
            //   12 字节、Deflate 条目直接报格式错。）
            //
            // 头里的密文同样参与密钥推进，所以要用同一个 codec 把它解一遍。
            ZipCrypto codec = new ZipCrypto(candidate);
            codec.decode(head, 0, ZIPCRYPTO_HEADER);
            InputStream body = new CodecStream(in, codec, compressed - ZIPCRYPTO_HEADER);
            return new VerifiedStream(inflate(body, crypt.method, entry), entry, null, null, null,
                    "ZIP 密码不对或文件已损坏");
        }
        throw last == null ? new IOException("ZIP 密码不对") : last;
    }

    /** 12 字节头最后一字节要能和 CRC 高字节（或 DOS 时间高字节）对上 */
    private static boolean headerMatches(byte[] head, ZipArchiveEntry entry) {
        int last = head[ZIPCRYPTO_HEADER - 1] & 0xff;
        long crc = entry.getCrc();
        if (crc >= 0 && ((crc >>> 24) & 0xff) == last) {
            return true;
        }
        return ((dosTime(entry.getTime()) >>> 8) & 0xff) == last;
    }

    private static int dosTime(long millis) {
        if (millis <= 0) {
            return 0;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        return (calendar.get(Calendar.HOUR_OF_DAY) << 11)
                | (calendar.get(Calendar.MINUTE) << 5)
                | (calendar.get(Calendar.SECOND) >> 1);
    }

    // ------------------------------------------------------------------ AES

    /** WinZip AES：salt | 校验值 | 密文 | 认证码 */
    private static InputStream openAes(InputStream raw, ZipArchiveEntry entry, Crypt crypt,
                                       char[] password) throws IOException {
        int keyLength = crypt.keyLength();
        int saltLength = keyLength / 2;
        long compressed = entry.getCompressedSize();
        if (compressed <= saltLength + 2L + AES_AUTH_LEN) {
            throw new IOException("加密条目的长度不对（文件不完整？）");
        }

        BufferedInputStream in = new BufferedInputStream(raw, BUFFER);
        byte[] head = new byte[saltLength + 2];
        if (readFully(in, head) < head.length) {
            throw new IOException("加密数据不完整（文件不完整？）");
        }
        byte[] salt = Arrays.copyOfRange(head, 0, saltLength);
        int verifier = (head[saltLength] & 0xff) | ((head[saltLength + 1] & 0xff) << 8);

        // 密文长度 = 总长度 - salt - 校验值 - 认证码
        long payload = compressed - saltLength - 2 - AES_AUTH_LEN;

        IOException last = null;
        for (byte[] candidate : passwordBytes(password)) {
            byte[] material = pbkdf2(candidate, salt, AES_ITERATIONS, 2 * keyLength + 2);
            int expect = (material[2 * keyLength] & 0xff) | ((material[2 * keyLength + 1] & 0xff) << 8);
            if (expect != verifier) {
                last = new IOException("ZIP 密码不对");
                continue;
            }
            byte[] aesKey = Arrays.copyOfRange(material, 0, keyLength);
            byte[] macKey = Arrays.copyOfRange(material, keyLength, 2 * keyLength);
            AesCtr codec = new AesCtr(aesKey, macKey);
            InputStream body = new CodecStream(in, codec, payload);
            return new VerifiedStream(inflate(body, crypt.method, entry), entry, codec, in, body,
                    "ZIP 密码不对或文件已损坏");
        }
        throw last == null ? new IOException("ZIP 密码不对") : last;
    }

    /**
     * PBKDF2-HMAC-SHA1（RFC 2898）自己实现，而不是用 JCE 的 `PBKDF2WithHmacSHA1`。
     *
     * 因为 JCE 只收 `char[]`，内部固定按 UTF-8 转字节 —— 而 ZIP 的密码是**字节**，
     * Windows 工具存的是本机代码页。自己实现才能精确控制密码字节。
     */
    private static byte[] pbkdf2(byte[] password, byte[] salt, int iterations, int length)
            throws IOException {
        Mac mac;
        try {
            mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(password, "HmacSHA1"));
        } catch (GeneralSecurityException e) {
            throw new IOException("设备不支持 HMAC-SHA1: " + e.getMessage(), e);
        }
        int blocks = (length + 19) / 20;
        byte[] out = new byte[blocks * 20];
        for (int block = 1; block <= blocks; block++) {
            mac.update(salt);
            mac.update((byte) (block >>> 24));
            mac.update((byte) (block >>> 16));
            mac.update((byte) (block >>> 8));
            mac.update((byte) block);
            byte[] u = mac.doFinal();
            byte[] t = u.clone();
            for (int round = 1; round < iterations; round++) {
                u = mac.doFinal(u);
                for (int i = 0; i < 20; i++) {
                    t[i] ^= u[i];
                }
            }
            System.arraycopy(t, 0, out, (block - 1) * 20, 20);
        }
        return Arrays.copyOf(out, length);
    }

    // ------------------------------------------------------------------ 密码字节

    /**
     * 密码 → 候选字节序列。
     *
     * ZIP 密码按字节参与运算，不同工具编码不同：Windows 的 WinZip / 7-Zip 用本机
     * 代码页（中文系统是 GBK），Linux 工具基本用 UTF-8。都试一遍，谁能通过自证用谁。
     */
    private static List<byte[]> passwordBytes(char[] password) {
        List<byte[]> out = new ArrayList<>(3);
        addDistinct(out, new String(password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        addDistinct(out, charset(password, "GBK"));
        byte[] raw = new byte[password.length];
        for (int i = 0; i < password.length; i++) {
            raw[i] = (byte) password[i];
        }
        addDistinct(out, raw);
        return out;
    }

    private static void addDistinct(List<byte[]> out, byte[] candidate) {
        if (candidate == null || candidate.length == 0) {
            return;
        }
        for (byte[] existing : out) {
            if (Arrays.equals(existing, candidate)) {
                return;
            }
        }
        out.add(candidate);
    }

    private static byte[] charset(char[] password, String name) {
        try {
            return new String(password).getBytes(name);
        } catch (UnsupportedEncodingException e) {
            return null;
        }
    }

    private static InputStream inflate(InputStream in, int method, ZipArchiveEntry entry)
            throws IOException {
        if (method == METHOD_STORED) {
            if (entry.getSize() < 0) throw new IOException("Stored 加密条目缺少明文长度");
            return new PlaintextLimit(in, entry.getSize());
        }
        if (method == METHOD_DEFLATED) {
            return new InflaterInputStream(in, new Inflater(true), BUFFER);
        }
        throw new IOException("加密条目用了暂不支持的压缩方法（method=" + method + "）");
    }

    /** Stored 后可能跟着 AES 块填充；只允许调用方读到声明的明文长度。 */
    private static final class PlaintextLimit extends FilterInputStream {
        private long left;

        PlaintextLimit(InputStream in, long size) {
            super(in);
            this.left = size;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int value = super.read();
            if (value >= 0) left--;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (left <= 0) return -1;
            int read = super.read(buffer, offset, (int) Math.min(length, left));
            if (read > 0) left -= read;
            return read;
        }
    }

    // ------------------------------------------------------------------ 编解码器

    private interface Codec {
        void decode(byte[] buffer, int offset, int length) throws IOException;
    }

    /** ZipCrypto 的流式解密 */
    private static final class ZipCrypto implements Codec {
        private int key0;
        private int key1;
        private int key2;

        ZipCrypto(byte[] password) {
            key0 = 0x12345678;
            key1 = 0x23456789;
            key2 = 0x34567890;
            for (byte b : password) {
                update(b);
            }
        }

        @Override
        public void decode(byte[] buffer, int offset, int length) {
            for (int i = 0; i < length; i++) {
                byte plain = (byte) (buffer[offset + i] ^ decryptByte());
                update(plain);
                buffer[offset + i] = plain;
            }
        }

        private void update(byte plain) {
            key0 = crc32(key0, plain);
            key1 = (key1 + (key0 & 0xff)) * 134775813 + 1;
            key2 = crc32(key2, (byte) (key1 >>> 24));
        }

        private int decryptByte() {
            int temp = (key2 | 2) & 0xffff;
            return (temp * (temp ^ 1)) >>> 8;
        }

        private static int crc32(int crc, byte value) {
            return (crc >>> 8) ^ CRC_TABLE[(crc ^ value) & 0xff];
        }
    }

    /**
     * AES-CTR + HMAC-SHA1。
     *
     * 计数器是 **16 字节小端、从 1 开始**、每块 +1 —— 和 JCE 的
     * `AES/CTR/NoPadding`（大端计数）不是一回事，所以自己按块来：
     * 用 AES/ECB 加密计数器块，再与数据异或。
     */
    private static final class AesCtr implements Codec {
        private final Cipher ecb;
        private final Mac mac;
        private final byte[] counter = new byte[16];
        private final byte[] stream = new byte[16];
        private int position = 16;

        AesCtr(byte[] key, byte[] macKey) throws IOException {
            try {
                ecb = Cipher.getInstance("AES/ECB/NoPadding");
                ecb.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
                mac = Mac.getInstance("HmacSHA1");
                mac.init(new SecretKeySpec(macKey, "HmacSHA1"));
            } catch (GeneralSecurityException e) {
                throw new IOException("设备不支持 AES 解密: " + e.getMessage(), e);
            }
            counter[0] = 1;
        }

        @Override
        public void decode(byte[] buffer, int offset, int length) throws IOException {
            mac.update(buffer, offset, length);
            for (int i = 0; i < length; i++) {
                if (position == 16) {
                    nextBlock();
                }
                buffer[offset + i] ^= stream[position++];
            }
        }

        private void nextBlock() throws IOException {
            try {
                System.arraycopy(ecb.doFinal(counter), 0, stream, 0, 16);
            } catch (GeneralSecurityException e) {
                throw new IOException("AES 解密失败: " + e.getMessage(), e);
            }
            position = 0;
            for (int i = 0; i < 16; i++) {
                counter[i]++;
                if (counter[i] != 0) {
                    break;
                }
            }
        }

        boolean matches(byte[] stored) {
            byte[] digest = mac.doFinal();
            for (int i = 0; i < AES_AUTH_LEN; i++) {
                if (digest[i] != stored[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ 流

    /**
     * 只允许读 [left] 个字节。
     *
     * 加密载荷后面还跟着 AES 的 10 字节认证码，解压器不能越界把它一起吞掉。
     */
    private static final class CodecStream extends InputStream {
        private final InputStream in;
        private final Codec codec;
        private long left;

        CodecStream(InputStream in, Codec codec, long left) {
            this.in = in;
            this.codec = codec;
            this.left = left;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) {
                return -1;
            }
            int value = in.read();
            if (value < 0) {
                throw new IOException("加密数据提前结束（文件不完整？）");
            }
            byte[] one = new byte[]{(byte) value};
            codec.decode(one, 0, 1);
            left--;
            return one[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (left <= 0) {
                return -1;
            }
            int want = (int) Math.min(length, left);
            int read = in.read(buffer, offset, want);
            if (read <= 0) {
                throw new IOException("加密数据提前结束（文件不完整？）");
            }
            codec.decode(buffer, offset, read);
            left -= read;
            return read;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(left, in.available());
        }
    }

    /**
     * 读完时校验完整性：明文 CRC（ZipCrypto / AE-1）与 AES 认证码。
     *
     * 校验点放在**流的尽头**，因为解压方本来就是一路读到 EOF ——
     * 密码对不对、文件有没有被动过，都在那一刻见分晓。
     */
    private static final class VerifiedStream extends FilterInputStream {
        private final ZipArchiveEntry entry;
        private final AesCtr mac;
        private final InputStream authStream;
        /** 密文流（[CodecStream]）—— 收尾时要把它抽干，见 [verify] */
        private final InputStream body;
        private final String failMessage;
        private final CRC32 crc = new CRC32();
        private boolean verified;

        VerifiedStream(InputStream in, ZipArchiveEntry entry, AesCtr mac, InputStream authStream,
                       InputStream body, String failMessage) {
            super(in);
            this.entry = entry;
            this.mac = mac;
            this.authStream = authStream;
            this.body = body;
            this.failMessage = failMessage;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value < 0) {
                verify();
            } else {
                crc.update(value);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read < 0) {
                verify();
            } else {
                crc.update(buffer, offset, read);
            }
            return read;
        }

        private void verify() throws IOException {
            if (verified) {
                return;
            }
            verified = true;
            // AE-2 不存 CRC（写 0），ZipCrypto 与 AE-1 存，能查就查
            long expected = entry.getCrc();
            if (expected > 0 && expected != crc.getValue()) {
                throw new IOException(failMessage);
            }
            if (mac != null) {
                // 认证码算在**整段密文**上，而 WinZip 写的包会在压缩数据后面补零
                // 补齐到 AES 块边界 —— 解压器读到 deflate 结束就停了，剩下的填充
                // 还没进过 HMAC。抽干再算，否则认证码必然对不上（7-Zip 不补零，
                // 但别人造的包会），这里的抽干对「已经抽干」的流是空操作。
                byte[] scratch = new byte[BUFFER];
                while (body.read(scratch) > 0) {
                    // 只是为了推进 HMAC，内容丢掉
                }
                byte[] stored = new byte[AES_AUTH_LEN];
                if (readFully(authStream, stored) < AES_AUTH_LEN) {
                    throw new IOException("加密数据不完整（文件不完整？）");
                }
                if (!mac.matches(stored)) {
                    throw new IOException(failMessage);
                }
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int done = 0;
        while (done < buffer.length) {
            int read = in.read(buffer, done, buffer.length - done);
            if (read < 0) {
                break;
            }
            done += read;
        }
        return done;
    }

    private static void close(ZipFile zip) {
        if (zip != null) {
            try {
                zip.close();
            } catch (IOException ignored) {
                // 关不上就算了
            }
        }
    }
}
