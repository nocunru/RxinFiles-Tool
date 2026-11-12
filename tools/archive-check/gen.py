# -*- coding: utf-8 -*-
import tarfile, os, shutil, stat

root = "src"
shutil.rmtree(root, ignore_errors=True)
os.makedirs(root + "/dirA/sub", exist_ok=True)
os.makedirs(root + "/目录B", exist_ok=True)

def w(p, data):
    with open(p, "wb") as f:
        f.write(data)

w(root + "/top.txt", b"hello top\n")
w(root + "/dirA/a.sh", b"#!/system/bin/sh\necho a\n")
w(root + "/dirA/sub/deep.bin", os.urandom(5000))
w(root + "/目录B/中文文件.txt", "中文内容\n".encode())
longdir = root + "/" + "/".join(["verylongdirectoryname%02d" % i for i in range(6)])
os.makedirs(longdir, exist_ok=True)
w(longdir + "/long_filename_" + "a" * 80 + ".txt", b"long\n")

def build(path, mode):
    with tarfile.open(path, mode) as tf:
        tf.add(root, arcname="payload", recursive=True)
        # 手工插入符号链接与硬链接条目（Windows 上建不了真实符号链接）
        sym = tarfile.TarInfo("payload/link_to_top")
        sym.type = tarfile.SYMTYPE
        sym.linkname = "top.txt"
        sym.mode = 0o777
        tf.addfile(sym)
        hard = tarfile.TarInfo("payload/hard_link")
        hard.type = tarfile.LNKTYPE
        hard.linkname = "payload/top.txt"
        tf.addfile(hard)
    print("wrote %-20s %8d" % (path, os.path.getsize(path)))

build("test.tar", "w")
build("test_gnu.tar", "w")
build("test_pax.tar", "w")
build("test.tar.gz", "w:gz")
build("test_gnu.tar.gz", "w:gz")
