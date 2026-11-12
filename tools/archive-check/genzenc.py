# -*- coding: utf-8 -*-
"""生成加密 ZIP 夹具的源树 `zenc/`。

**为什么要单独一棵源树**：加密夹具必须同时覆盖 **Deflate** 与 **Store**
两种形态，而 7-Zip 写入 zip 时会自己挑 —— 它对「小文件」和「压不动的内容」
一律落成 Store（实测 `-mm=Deflate -mx=9` 也会被忽略）。
所以源树里必须有**足够大、又足够可压**的负载，deflate 才会真的被用上。

内容全部可复现（不用 `os.urandom`）：同一个种子生成的确定文本。

    python genzenc.py        # 在 tools/archive-check 下跑，产出 zenc/
"""

import os
import shutil

ROOT = "zenc"

shutil.rmtree(ROOT, ignore_errors=True)
os.makedirs(ROOT + "/dirA/sub", exist_ok=True)
os.makedirs(ROOT + "/空目录", exist_ok=True)


def w(path, data):
    with open(path, "wb") as f:
        f.write(data)


# 小文件：7-Zip 对它们一定落 Store，正好和下面的大文件形成对照
w(ROOT + "/top.txt", b"hello zip\n")
w(ROOT + "/dirA/a.sh", b"#!/system/bin/sh\necho a\n")

# 可压的大文件（约 140 KB → deflate 后约 1 KB）：逼 deflate 真的生效
lines = []
for i in range(4000):
    lines.append("line %04d  the quick brown fox jumps over the lazy dog %04d\n" % (i, i % 97))
w(ROOT + "/big.txt", "".join(lines).encode("utf-8"))

# 子目录里再放一个中等大小、可压的
sub = "".join("row %03d,%03d,%03d\n" % (i, i * 2, i * 3) for i in range(800))
w(ROOT + "/dirA/sub/table.csv", sub.encode("utf-8"))

# 非 ASCII 名（zip 里存的是 UTF-8 + unicode path extra 时，名字不该乱码）
w(ROOT + "/中文文件.txt", "中文内容\n".encode("utf-8"))

total = 0
for root, dirs, files in os.walk(ROOT):
    for f in files:
        total += os.path.getsize(os.path.join(root, f))
print("zenc ok: %d 个文件, 原始共 %d 字节" % (
    sum(len(fs) for _, _, fs in os.walk(ROOT)), total))
