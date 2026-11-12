# -*- coding: utf-8 -*-
import zipfile, os, io, gzip, shutil, struct

shutil.rmtree("zsrc", ignore_errors=True)
os.makedirs("zsrc/dirA/sub", exist_ok=True)
os.makedirs("zsrc/空目录", exist_ok=True)
def w(p, d):
    open(p, "wb").write(d)
w("zsrc/top.txt", b"hello zip\n")
w("zsrc/dirA/a.sh", b"#!/system/bin/sh\necho a\n")
w("zsrc/dirA/sub/deep.bin", os.urandom(3000))
w("zsrc/中文文件.txt", "中文内容\n".encode())

with zipfile.ZipFile("plain.zip", "w", zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk("zsrc"):
        for f in files:
            p = os.path.join(root, f)
            z.write(p, os.path.relpath(p, "zsrc"))
    z.writestr("空目录/", b"")
print("plain.zip ok", os.path.getsize("plain.zip"))

# 1) 生成带 GBK 文件名的 zip：先写 ASCII 占位名，再按字节替换
placeholder = "a" * 13                       # 与 GBK 编码后的长度一致
gbk_name = "文件名abc.txt".encode("gbk")
assert len(gbk_name) == 13
with zipfile.ZipFile("gbk.zip", "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr(placeholder, "GBK 内容\n".encode())
    z.writestr("bbbbbbbb.txt", b"plain\n")
data = open("gbk.zip", "rb").read()
data = data.replace(placeholder.encode(), gbk_name)
open("gbk.zip", "wb").write(data)
print("gbk.zip ok", os.path.getsize("gbk.zip"))

# 2) zip-slip
with zipfile.ZipFile("evil.zip", "w") as z:
    z.writestr("../escaped2.txt", "evil")
    z.writestr("good.txt", "ok")
print("evil.zip ok")

# 3) 单文件 gzip
with gzip.open("single.txt.gz", "wb") as f:
    f.write("gzip 单文件内容\n".encode() * 100)
print("single.txt.gz ok", os.path.getsize("single.txt.gz"))
