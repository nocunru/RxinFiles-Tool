#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""下载并解包 Linux 版 NDK（WSL 侧没有 sudo，所以装在 ~ 下）。

为什么必须用 Linux 版：Windows 版只有 windows-x86_64 工具链，
而 WSL 里跑不了 .exe，busybox 这类 POSIX 构建系统也没法喂给 Windows 侧的 make。
"""
import os
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile

HOME = os.path.expanduser("~")
URL = "https://dl.google.com/android/repository/android-ndk-r26d-linux.zip"
ZIP = os.path.join(HOME, "ndk-r26d-linux.zip")
DEST = os.path.join(HOME, "ndk")
MARK = os.path.join(DEST, "toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang")


def extract_zip(zip_path, dest):
    """解包，**保留权限位与符号链接**。

    这一条踩过坑：Python 的 zipfile.extract() 不还原 unix 模式，
    也不还原符号链接 —— NDK 的 `bin/clang` 其实是指向 `clang-17` 的软链，
    被写成内容为 "clang-17" 的文本文件后，执行时报
    `clang: line 1: clang-17: command not found`，看起来像缺组件。
    """
    unzip = shutil.which("unzip")
    if unzip:
        print("  用 unzip 解包（会保留权限与软链）")
        subprocess.run([unzip, "-q", "-o", zip_path, "-d", dest], check=True)
        return
    print("  用内置解包器（unzip 不在，手工还原权限与软链）")
    with zipfile.ZipFile(zip_path) as z:
        links = 0
        for info in z.infolist():
            z.extract(info, dest)
            p = os.path.join(dest, info.filename)
            mode = (info.external_attr >> 16) & 0xFFFF
            if mode & 0xF000 == 0xA000:  # S_IFLNK
                target = z.read(info).decode("utf-8", "replace")
                if os.path.lexists(p):
                    os.remove(p)
                os.symlink(target, p)
                links += 1
            elif mode:
                os.chmod(p, mode & 0o7777)
        print("  还原符号链接 %d 个" % links)


def healthy():
    """可用性自检。

    只看 MARK 存在是不够的：如果解包时丢了软链，`bin/clang` 会是个内容为
    "clang-17" 的文本文件，跑起来报 `clang-17: command not found`，
    症状很像「NDK 缺组件」。所以这里顺手验一下 clang 到底是不是软链
    （NDK 里 `clang -> clang-17`）。
    """
    if not os.path.exists(MARK):
        return False
    clang = os.path.join(os.path.dirname(MARK), "clang")
    if not os.path.exists(clang):
        return False
    if not os.path.islink(clang):
        print("自检失败: %s 不是符号链接（解包时丢了软链），强制重新解包" % clang)
        return False
    return True


def dl():
    if healthy():
        print("已就绪: %s" % MARK)
        return True
    if not os.path.exists(ZIP) or os.path.getsize(ZIP) < 100 * 1024 * 1024:
        print("下载 %s" % URL)
        t0 = time.time()
        req = urllib.request.Request(URL, headers={"User-Agent": "curl/8"})
        tmp = ZIP + ".part"
        with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
            total = int(r.headers.get("Content-Length") or 0)
            done = 0
            while True:
                chunk = r.read(1 << 20)
                if not chunk:
                    break
                f.write(chunk)
                done += len(chunk)
                if done % (64 << 20) < (1 << 20):
                    print("  %.0f%% (%.0f MB, %.1f MB/s)" % (
                        100.0 * done / total if total else 0, done / 1048576.0,
                        done / 1048576.0 / max(time.time() - t0, 0.01)), flush=True)
        os.replace(tmp, ZIP)
        print("下载完成: %d bytes, %.1fs" % (os.path.getsize(ZIP), time.time() - t0))
    print("解包（约 4GB，稍等）…")
    t0 = time.time()
    if os.path.isdir(DEST):
        shutil.rmtree(DEST)
    os.makedirs(DEST, exist_ok=True)
    with zipfile.ZipFile(ZIP) as z:
        root = z.namelist()[0].split("/")[0]
    extract_zip(ZIP, DEST)
    inner = os.path.join(DEST, root)
    if os.path.isdir(inner) and inner != DEST:
        for n in os.listdir(inner):
            shutil.move(os.path.join(inner, n), os.path.join(DEST, n))
        os.rmdir(inner)
        if os.path.isdir(os.path.join(DEST, root)):
            shutil.rmtree(os.path.join(DEST, root), ignore_errors=True)
    print("解包完成 %.1fs" % (time.time() - t0))
    return os.path.exists(MARK)


ok = dl()
print("MARK 存在: %s" % os.path.exists(MARK))
if os.path.exists(MARK):
    os.chmod(MARK, 0o755)
    print(subprocess.run([MARK, "--version"], capture_output=True, text=True).stdout.strip()[:200])
    for c in ("aarch64-linux-android-ar", "aarch64-linux-android28-clang++", "llvm-ar", "llvm-readelf",
              "llvm-strip", "ld.lld", "clang"):
        p = os.path.join(os.path.dirname(MARK), c)
        print("  %-32s %s" % (c, "有" if os.path.exists(p) else "无"))
    print("sysroot: %s" % os.path.join(DEST, "toolchains/llvm/prebuilt/linux-x86_64/sysroot"))
else:
    print("失败")
    sys.exit(1)
