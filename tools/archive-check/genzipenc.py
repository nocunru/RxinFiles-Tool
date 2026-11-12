# -*- coding: utf-8 -*-
"""造「密码本身是非 ASCII」的加密 ZIP 夹具。

**为什么不能只靠 7-Zip**：7-Zip / Info-ZIP 在 Linux 上把命令行给的密码按 **UTF-8
字节**送进算法，而 Windows 上的 WinZip / WinRAR / 7-Zip 中文版按**本机代码页（GBK）**
送。这两种包在真实世界里都存在，`ZipArchive.passwordBytes()` 就是为了「三种编码
都试一遍」。要用夹具把这条分支钉死，就必须**能指定密码用哪种字节编码**——
命令行工具不给这个自由度。

所以这里自己写一份 ZipCrypto 加密器（PKWARE 传统加密）。它顺带还是一件好事：
夹具来自一套**完全独立的实现**，我们的 Java 读得出来就说明两边对标准理解一致；
Python 的 `zipfile` 也能把它读回去，等于三方交叉验证。

只做 **Store + ZipCrypto**：形态最简单，能把「密码字节」这个变量单独隔出来。

    python genzipenc.py      # 在 tools/archive-check 下跑
"""

import random
import struct
import zipfile
import zlib

PW_TEXT = "密码测试"          # 密码是中文 —— 这正是要考的东西
PAYLOAD = ("这是被密码保护的内容。\n" * 20).encode("utf-8")

# ---------------------------------------------------------------- CRC32 表
CRC_TABLE = []
for _i in range(256):
    _c = _i
    for _k in range(8):
        _c = (0xEDB88320 ^ (_c >> 1)) if (_c & 1) else (_c >> 1)
    CRC_TABLE.append(_c)


def _crc32_step(crc, value):
    return ((crc >> 8) ^ CRC_TABLE[(crc ^ value) & 0xFF]) & 0xFFFFFFFF


class ZipCrypto:
    """传统 PKWARE 流式加密（与 ZipArchive.ZipCrypto 的密钥调度一一对应）"""

    def __init__(self, password_bytes):
        self.k0 = 0x12345678
        self.k1 = 0x23456789
        self.k2 = 0x34567890
        for b in password_bytes:
            self._update(b)

    def _update(self, plain):
        self.k0 = _crc32_step(self.k0, plain)
        self.k1 = ((self.k1 + (self.k0 & 0xFF)) * 134775813 + 1) & 0xFFFFFFFF
        self.k2 = _crc32_step(self.k2, (self.k1 >> 24) & 0xFF)

    def _stream_byte(self):
        temp = (self.k2 | 2) & 0xFFFF
        return ((temp * (temp ^ 1)) >> 8) & 0xFF

    def encrypt(self, data):
        out = bytearray()
        for p in data:
            c = p ^ self._stream_byte()
            self._update(p)
            out.append(c)
        return bytes(out)


def build_zip(path, name, data, password_bytes):
    crc = zlib.crc32(data) & 0xFFFFFFFF
    random.seed(20261007)                      # 可复现
    head = bytearray(random.randrange(256) for _ in range(12))
    # 头最后一字节的**明文**必须是 CRC 的高字节（这是解包方校验密码的方式）
    head[11] = (crc >> 24) & 0xFF

    codec = ZipCrypto(password_bytes)
    enc_head = codec.encrypt(bytes(head))
    enc_data = codec.encrypt(data)
    packed = len(enc_head) + len(enc_data)

    name_bytes = name.encode("ascii")
    # 本地文件头：flags=0x0001 表示加密；不设 bit3（写数据描述符），尺寸直接写死
    local = struct.pack(
        "<IHHHHHIIIHH",
        0x04034B50, 20, 0x0001, 0, 0x5C21, 0x5841,   # 签名/版本/标志/方法/时间/日期
        crc, packed, len(data), len(name_bytes), 0,
    ) + name_bytes + enc_head + enc_data

    # 中央目录
    central = struct.pack(
        "<IHHHHHHIIIHHHHHII",
        0x02014B50, 20, 20, 0x0001, 0, 0x5C21, 0x5841,
        crc, packed, len(data), len(name_bytes), 0, 0,
        0, 0, 0, 0,
    ) + name_bytes
    offset = 0
    eocd = struct.pack(
        "<IHHHHIIH",
        0x06054B50, 0, 0, 1, 1, len(central), len(local), 0,
    )
    with open(path, "wb") as f:
        f.write(local + central + eocd)
    return crc


# ---------------------------------------------------------------- 生成 + 自检
cases = [
    ("zippw-gbk.zip", PW_TEXT.encode("gbk"), PW_TEXT.encode("utf-8")),
    ("zippw-utf8.zip", PW_TEXT.encode("utf-8"), PW_TEXT.encode("gbk")),
]

for fname, pw_bytes, wrong_bytes in cases:
    crc = build_zip(fname, "secret.txt", PAYLOAD, pw_bytes)

    # 自检 1：Python 自己的 zipfile 用同一组密码字节必须能读回来
    with zipfile.ZipFile(fname) as z:
        z.setpassword(pw_bytes)
        got = z.read("secret.txt")
    assert got == PAYLOAD, fname + " 自己都读不回来"

    # 自检 2：用**错**的编码必须读不出来 —— 否则这两条夹具根本区分不了编码，
    # 我们的「三种编码都试一遍」也就失去意义了。
    try:
        with zipfile.ZipFile(fname) as z:
            z.setpassword(wrong_bytes)
            z.read("secret.txt")
        verdict = "!! 用错编码居然也读得出来，夹具无效"
        raise SystemExit(1)
    except SystemExit:
        raise
    except Exception:
        verdict = "用错编码读不出来（符合预期）"

    print("%-16s 密码字节 %-20s crc=%08x  %s"
          % (fname, " ".join("%02x" % b for b in pw_bytes), crc, verdict))
