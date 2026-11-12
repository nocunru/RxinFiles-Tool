#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用 Linux 版 NDK 交叉编译 busybox（Bionic 链接，给 Android App 用）。

为什么不能用现成的静态 busybox（Alpine 的 busybox-static 之类）：
它是 musl 静态链接，musl 启动时（__init_tp）会调 set_robust_list，
而 Android app 沙盒的 seccomp 把这个调用 TRAP 成 SIGSYS —— 进程 exec 后即被杀。
真机 strace：si_code=SYS_SECCOMP / si_syscall=__NR_set_robust_list / killed by SIGSYS。

所以自己编一份 Bionic 版：
  - 动态链接（PIE + /system/bin/linker64），只依赖 /system/lib64 里的 libc/libm，
    与 App 自己的 native 代码走同一条 libc 路径；
  - 输出单个文件，直接放进 jniLibs/arm64-v8a/libbusybox.so；
  - 同时用同一份 .config 编一个 x86_64 主机版，用来在本机做**真功能断言**
    （符号链接分派、applet 清单），这是无真机条件下唯一能实证的部分。

配置策略：defconfig 起步，先按「反正会被 App 屏蔽」的清单关掉一批需要
SysV IPC / MTD / 网络配置 / 挂载的 applet（省体积也省编译风险），
余下的编译错误由自动收敛循环处理：从报错对象反查 `lib-$(CONFIG_X) += x.o`
得到 CONFIG 名并关掉，重编，最多若干轮。

## 三个非踩不可的坑（都在这里处理掉了，改脚本时不要删）

1. **`-lresolv` 要按目标平台分别装/拆**。busybox 的 Makefile.flags 只在
   `CONFIG_FEATURE_NSLOOKUP_BIG=y` 时，按「目标三元组里有没有 linux」来决定
   `LDLIBS += resolv` —— `aarch64-linux-android28` 含 linux，于是加了。
   但 **Android 根本没有 libresolv**：Bionic 把它并进了 libc
   （`res_init`/`res_mkquery`/`dn_expand`/`ns_initparse`/`ns_parserr`/
   `ns_get16`/`ns_get32` 全在 libc.so 里）。而桌面 glibc 相反，`ns_*` 系列
   在 `libresolv.so.2` 里，去掉就成 undefined reference。
   所以：交叉树拆掉这行，主机树留着。见 [patch_flags]。

2. **没有 `olddefconfig` 这个目标**。busybox 1.36.1 的
   `scripts/kconfig/Makefile` 只提供 `oldconfig` / `silentoldconfig`，
   写 `make olddefconfig` 只会得到 `No rule to make target`（而且是嵌套 make
   里的报错，外层只看到 Error 2，很容易被当成「配置失败但无所谓」忽略掉）。
   用它重算依赖时必须换成 `oldconfig` + stdin=/dev/null。见 [kconfig_refresh]。

3. **对象→CONFIG 的映射表在 `Kbuild` 文件里**，不是 `Makefile*`
   （`editors/Kbuild` 里写着 `lib-$(CONFIG_AWK) += awk.o`）。只扫 Makefile
   会一条都抓不到，收敛循环就彻底空转。见 [object_to_config]。
"""
import os
import re
import shutil
import subprocess
import sys
import tarfile

HOME = os.path.expanduser("~")
NDK = os.path.join(HOME, "ndk")
TOOLBIN = os.path.join(NDK, "toolchains/llvm/prebuilt/linux-x86_64/bin")
CC = os.path.join(TOOLBIN, "aarch64-linux-android28-clang")
PROJ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RB = os.path.join(PROJ, "tools/rxinbox")
JNI = os.path.join(PROJ, "app/src/main/jniLibs/arm64-v8a")
VER = "1.36.1"
WORK = os.path.join(HOME, "bxbuild")
MAX_ROUNDS = 8

# 反正会被 App 屏蔽（见 RxinToolbox.BLOCKED），关掉能省体积、也少一堆
# 需要内核头文件才能编译的 applet
BLOCK_APPLETS = """
su setpriv login nologin chroot unshare nsenter
mount umount switch_root pivot_root fsck mkfs.vfat mkdosfs mkswap swapon swapoff
losetup blkdiscard fdisk blockdev blkid findfs nbd-client mdev
passwd adduser addgroup deluser delgroup chpasswd cryptpw mkpasswd vlock
add-shell remove-shell
insmod rmmod modprobe depmod modinfo init reboot halt poweroff sysctl klogd syslogd
crond crontab watchdog rdate hwclock adjtimex setconsole setfont
ifconfig ifup ifdown route arp arping ip ipaddr iplink iproute iprule iptunnel
ipneigh vconfig tunctl ifenslave nameif brctl udhcpc udhcpc6
ps top free df du uptime dmesg lsof lsusb iostat mpstat pmap pstree fuser killall5 lsmod
chvt deallocvt openvt setkeycodes setlogcons dumpkmap loadkmap loadfont showkey
kbd_mode fbset fbsplash fdflush setserial microcom readahead fwconsole
ls cp mv rm mkdir rmdir ln chmod chown chgrp touch stat readlink realpath cat sh
who users last w wall mesg utmpdump
ipcs ipcrm
tc
logger hush
udhcpd dhcprelay dumpleases
nandwrite nanddump flash_eraseall flashcp flash_lock flash_unlock
ubiattach ubidetach ubimkvol ubirmvol ubiupdatevol ubiformat ubirename
devfsd
"""

# 与 applet 无关、但在这套环境里没意义或会引入缺失头文件的开关
BLOCK_CONFIGS = """
CONFIG_FEATURE_SUID
CONFIG_FEATURE_SUID_CONFIG
CONFIG_FEATURE_SUID_CONFIG_QUIET
CONFIG_FEATURE_MOUNT_NFS
CONFIG_FEATURE_MOUNT_CIFS
CONFIG_FEATURE_IPC_SYSLOG
CONFIG_FEATURE_IFUPDOWN_IP
CONFIG_FEATURE_IFUPDOWN_IPV4
CONFIG_FEATURE_IFUPDOWN_IPV6
CONFIG_FEATURE_IFUPDOWN_IP_BUILTIN
CONFIG_FEATURE_IFUPDOWN_IFCONFIG_BUILTIN
CONFIG_FEATURE_WTMP
CONFIG_FEATURE_UTMP
CONFIG_FEATURE_STATICALLY_LINKED_UBI
CONFIG_STATIC
CONFIG_FEATURE_INETD_RPC
"""


def run(cmd, cwd=None, timeout=1800):
    return subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, timeout=timeout)


def cfg_name(applet):
    """applet 名 -> 猜测的 CONFIG 名（busybox 基本就是大写加下划线）"""
    return "CONFIG_" + re.sub(r"[^A-Za-z0-9]", "_", applet).upper()


def set_config(srcdir, names, value=False):
    """把 .config 里的开关改成 y / 关掉。返回真正改动的条目数。

    只在内容真的变了才落盘：`make` 会因为 .config 的 mtime 变化重新生成
    `include/autoconf.h`，进而**全量重编**。无条件重写会让每次跑脚本
    都白等一遍完整编译。
    """
    path = os.path.join(srcdir, ".config")
    with open(path, "r", errors="replace") as f:
        original = f.read()
    lines = original.splitlines()
    on = set()
    off = set()
    for i, line in enumerate(lines):
        m = re.match(r"(?:# )?(CONFIG_[A-Z0-9_]+)(?: is not set|=y|=.*)?$", line.strip())
        if not m:
            continue
        name = m.group(1)
        if name in names:
            if value:
                lines[i] = name + "=y"
                on.add(name)
            else:
                lines[i] = "# " + name + " is not set"
                off.add(name)
    updated = "\n".join(lines) + "\n"
    if updated == original:
        return set()
    with open(path, "w") as f:
        f.write(updated)
    return on | off


def existing_configs(srcdir):
    path = os.path.join(srcdir, ".config")
    with open(path, "r", errors="replace") as f:
        return set(re.findall(r"CONFIG_[A-Z0-9_]+", f.read()))


def object_to_config(srcdir):
    """扫描 Makefile / Kbuild，建 obj -> CONFIG 映射。

    注意 busybox 用的是内核风格构建：对象清单在 **`Kbuild`** 里
    （`editors/Kbuild` 里写着 `lib-$(CONFIG_AWK) += awk.o`），
    只扫 `Makefile*` 会一条都抓不到。
    """
    m = {}
    for root, _d, files in os.walk(srcdir):
        for f in files:
            if not (f.startswith("Makefile") or f.startswith("Kbuild") or f.endswith(".mk")):
                continue
            p = os.path.join(root, f)
            try:
                with open(p, "r", errors="replace") as fh:
                    text = fh.read()
            except OSError:
                continue
            # 允许一条语句跨行续行
            text = re.sub(r"\\\n\s*", " ", text)
            for cfg, objs in re.findall(
                    r"\$\((CONFIG_[A-Z0-9_]+)\)\s*\+=\s*([^\n#]*)", text):
                for o in objs.split():
                    if o.endswith(".o"):
                        m[o] = cfg
    return m


def extract():
    src = os.path.join(WORK, "busybox-%s" % VER)
    if os.path.isdir(src) and os.path.exists(os.path.join(src, "Makefile")):
        return src
    os.makedirs(WORK, exist_ok=True)
    tb = os.path.join(RB, "src", "busybox-%s.tar.bz2" % VER)
    if not os.path.exists(tb):
        print("缺源码包: %s" % tb)
        sys.exit(1)
    print("解包 %s" % tb)
    with tarfile.open(tb, "r:bz2") as t:
        t.extractall(WORK)
    return src


def kconfig_refresh(srcdir):
    """按 .config 重算依赖关系（关掉一个开关后，它的子开关要跟着掉）。

    **不能用 `make olddefconfig`** —— busybox 1.36.1 没有这个目标，
    `scripts/kconfig/Makefile` 里只有 `oldconfig` / `silentoldconfig`，
    调用它得到的是嵌套 make 的 `No rule to make target 'olddefconfig'`，
    外层只显示 `Makefile:444: olddefconfig Error 2`，很容易被误当成
    「配置重算失败但无所谓」而忽略 —— 实际上依赖就一直没重算过。

    `oldconfig` 配合 stdin=/dev/null：EOF 时全部取默认值，等价于非交互。
    """
    with open(os.devnull) as devnull:
        r = subprocess.run(["make", "oldconfig"], cwd=srcdir, stdin=devnull,
                           capture_output=True, text=True, timeout=600)
    return r.returncode == 0


# busybox 里被无条件追加 -lresolv 的那两行（各对应一个三元组判据分支）
_RESOLV_RE = re.compile(r"^#?\s*LDLIBS \+= resolv\b")


def patch_flags(srcdir, drop_resolv):
    """按目标平台装/拆 `-lresolv`。返回处理的函数行数。

    为什么必须分平台（这是实测出来的，不是猜的）：
      - **Android / Bionic**：libresolv 已经并进 libc。实测 Bionic
        `libc.so` 的导出符号里有 `res_init` / `res_mkquery` / `dn_expand` /
        `ns_initparse` / `ns_parserr` / `ns_get16` / `ns_get32`，
        而 sysroot 里**没有** libresolv.so → 留着这行就是
        `ld.lld: error: unable to find library -lresolv`。
        更狠的是 trylink 的**首链失败会直接 exit 1**，走不到它「逐个剔除
        用不上的库」那一步，所以这个错误没有任何自愈可能。
      - **桌面 glibc**：`ns_*` 系列在 `libresolv.so.2` 里，libc 里没有 →
        拆掉就成 `undefined reference to ns_initparse`。

    busybox 自己的判据只看三元组里有没有 `linux`，识别不出这个差别，所以由我们改。
    """
    path = os.path.join(srcdir, "Makefile.flags")
    if not os.path.exists(path):
        print("补丁失败：没有 %s" % path)
        return 0
    with open(path, "r", errors="replace") as f:
        lines = f.read().splitlines()
    n = 0
    for i, line in enumerate(lines):
        if _RESOLV_RE.match(line.strip()):
            lines[i] = ("# LDLIBS += resolv   # [RouXin] Bionic 无 libresolv："
                        "res_*/ns_* 都在 libc.so 里" if drop_resolv
                        else "LDLIBS += resolv")
            n += 1
    with open(path, "w") as f:
        f.write("\n".join(lines) + "\n")
    return n


def configure(srcdir):
    if not os.path.exists(os.path.join(srcdir, ".config")):
        print("make defconfig")
        r = run(["make", "defconfig"], cwd=srcdir)
        if r.returncode != 0:
            print(r.stdout[-3000:], r.stderr[-3000:])
            sys.exit(1)
    have = existing_configs(srcdir)
    want = set()
    for a in BLOCK_APPLETS.split():
        c = cfg_name(a)
        if c in have:
            want.add(c)
    want |= {c for c in BLOCK_CONFIGS.split() if c in have}
    unknown = sorted(want - have)
    if unknown:
        print("注意：这些名字在 .config 里不存在（写了也没用）: %s" % ", ".join(unknown))
    changed = set_config(srcdir, want & have)
    print("关掉 %d 个开关" % len(changed))
    # 收敛依赖：被关掉的东西可能有子开关
    return kconfig_refresh(srcdir)


def build(srcdir, jobs="4", extra_env=None):
    env = dict(os.environ)
    if extra_env:
        env.update(extra_env)
    # 两个坑都在这里：
    #  1) busybox 的 Makefile 让 HOSTCC 跟着 CC 走，不显式给主机编译器就会
    #     「用交叉编译器编主机工具」→ 生成的 applets/usage 是 aarch64 的，
    #     宿主上跑不了（Exec format error）。
    #  2) 不能把 NDK 的 bin 前置进 PATH：宿主的 gcc 会通过 PATH 找到 NDK 的
    #     ld/ar/nm/strip，链接主机程序时崩在 collect2（posix_spawnp: Exec format error）。
    #     CC 用绝对路径就够了，clang 包装脚本自己知道去哪里找兄弟文件。
    cmd = ["make", "-k", "-j" + jobs,
           "CC=" + CC,
           "HOSTCC=gcc",
           "HOSTCXX=g++",
           "AR=" + os.path.join(TOOLBIN, "llvm-ar"),
           "RANLIB=" + os.path.join(TOOLBIN, "llvm-ranlib"),
           "NM=" + os.path.join(TOOLBIN, "llvm-nm"),
           "STRIP=" + os.path.join(TOOLBIN, "llvm-strip")]
    return subprocess.run(cmd, cwd=srcdir, capture_output=True, text=True,
                          timeout=3600, env=env)


def main():
    for p in (CC, os.path.join(TOOLBIN, "llvm-ar")):
        if not os.path.exists(p):
            print("缺工具链: %s" % p)
            sys.exit(1)
    os.chdir(WORK if os.path.isdir(WORK) else HOME)
    srcdir = extract()
    configure(srcdir)
    # 交叉树（Android/Bionic）：拆掉 -lresolv，否则首链就死在
    # ld.lld: unable to find library -lresolv
    n = patch_flags(srcdir, drop_resolv=True)
    print("Makefile.flags: 拆掉 %d 行 -lresolv（Android 无 libresolv）" % n)
    obj2cfg = object_to_config(srcdir)
    print("obj->CONFIG 映射: %d 条" % len(obj2cfg))
    last_cfgs = None

    for rnd in range(1, MAX_ROUNDS + 1):
        print("\n===== 第 %d 轮编译 =====" % rnd)
        r = build(srcdir)
        bb = os.path.join(srcdir, "busybox")
        ok = r.returncode == 0 and os.path.exists(bb)
        if ok:
            print("编译成功")
            break
        log = (r.stdout or "") + (r.stderr or "")
        objs = set(re.findall(r"make(?:\[\d+\])?: \*\*\* \[[^\]]*?: ([\w./+-]+\.o)\] Error", log))
        objs |= set(re.findall(r"make(?:\[\d+\])?: \*\*\* \[[^\]]*?: ([\w./+-]+\.o)\]", log))

        # 打印真实报错：同一个 .o 可能挂在多个 CONFIG 上，光看映射名会误杀
        errs = []
        for line in log.splitlines():
            if ("error:" in line or "fatal error" in line) and "warning:" not in line:
                line = line.strip()
                if line not in errs:
                    errs.append(line)
        print("  --- 编译错误（去重，最多 20 条）---")
        for e in errs[:20]:
            print("   ", e[:170])

        cfgs = set()
        for o in objs:
            base = os.path.basename(o)
            if base in obj2cfg:
                cfgs.add(obj2cfg[base])
            if base[:-2] in obj2cfg:
                cfgs.add(obj2cfg[base[:-2]])
        print("  失败对象 %d 个，映射到 %d 个 CONFIG" % (len(objs), len(cfgs)))
        if not cfgs:
            # 链接期失败（没有失败的 .o）—— 最典型的就是缺某个 -lXXX。
            # 这里单独判一下，否则现场只剩一句「无法自动映射」，看不出要改什么。
            miss = re.findall(r"unable to find library (-l\w+)", log)
            if miss:
                print("  这是**链接期**缺库（不是某个 applet 编不过）：%s" % ", ".join(sorted(set(miss))))
                print("  先查 Makefile.flags 里这个库是按什么条件追加的，")
                print("  再决定是拆掉那行、还是关掉触发它的 applet。")
            print("  无法自动映射，最后 4000 字日志：")
            print(log[-4000:])
            print("  --- 失败对象 ---")
            for o in sorted(objs)[:40]:
                print("   ", o)
            return 2
        if cfgs == last_cfgs:
            # 同一批开关再来一轮说明它们**不是**真正的原因
            # （典型：名字拼错不存在，或者失败的 .o 与 CONFIG 是多对多关系）。
            # 继续空转没有意义，直接把现场打出来。
            print("  与上一轮完全相同 -> 停：这些开关不是根因")
            print(log[-3000:])
            return 4
        last_cfgs = cfgs
        real = cfgs & existing_configs(srcdir)
        if not real:
            print("  映射到的名字在 .config 里都不存在 -> 停")
            print(log[-3000:])
            return 4
        print("  关闭: %s" % ", ".join(sorted(real)))
        set_config(srcdir, real)
        kconfig_refresh(srcdir)
    else:
        print("轮次用尽仍未成功")
        return 3

    # 产物信息
    bb = os.path.join(srcdir, "busybox")
    size = os.path.getsize(bb)
    print("\n产物: %s (%d bytes, %.2f MB)" % (bb, size, size / 1048576.0))
    elf = (run([os.path.join(TOOLBIN, "llvm-readelf"), "-h", "-l", "-d", bb]).stdout or "")
    for line in elf.splitlines():
        if any(k in line for k in ("Type:", "Machine:", "interpreter", "NEEDED", "SONAME")):
            print("   " + line.strip())

    # 硬闸门：形态不对就**不许**进 jniLibs。
    # 上一版把 musl 静态的 busybox 塞了进去，结果「文件在、权限对、一执行就被
    # seccomp 杀」，而且失败点飘到真机上才发现。这几条断言就是防这个复发。
    problems = []
    if "AArch64" not in elf:
        problems.append("不是 AArch64")
    if "DYN (Shared object file)" not in elf:
        problems.append("不是 PIE（ET_DYN）")
    if "/system/bin/linker64" not in elf:
        problems.append("没有 /system/bin/linker64 解释器（静态链接？musl 静态会被 seccomp 杀）")
    needed = set(re.findall(r"Shared library: \[([^\]]+)\]", elf))
    allowed = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libstdc++.so", "libandroid.so"}
    extra = needed - allowed
    if extra:
        problems.append("依赖了非系统库 %s（真机上找不到 → 启动即失败）" % ", ".join(sorted(extra)))
    if not needed:
        problems.append("没有任何 NEEDED（疑似静态链接）")
    print("   NEEDED = %s" % (", ".join(sorted(needed)) or "<空>"))
    if problems:
        print("\n产物形态不合格，拒绝放入 jniLibs：")
        for p in problems:
            print("   ×", p)
        return 5
    print("   形态检查通过：Bionic 动态链接，依赖全在 /system/lib64")

    r = run([os.path.join(TOOLBIN, "llvm-strip"), "--strip-all",
             "-o", os.path.join(WORK, "busybox-arm64-stripped"), bb])
    if r.returncode == 0:
        print("   strip: %d bytes -> %d bytes" % (
            size, os.path.getsize(os.path.join(WORK, "busybox-arm64-stripped"))))

    # 打包位置
    os.makedirs(JNI, exist_ok=True)
    dst = os.path.join(JNI, "libbusybox.so")
    src_put = os.path.join(WORK, "busybox-arm64-stripped")
    if not os.path.exists(src_put):
        src_put = bb
    shutil.copy2(src_put, dst)
    os.chmod(dst, 0o644)
    print("已放入: %s (%d bytes)" % (dst, os.path.getsize(dst)))

    # 主机版：同一份 .config 编 x86_64，用来做真功能断言
    print("\n===== 主机 x86_64 版（用于本机功能断言）=====")
    host = os.path.join(WORK, "host")
    if os.path.isdir(host):
        shutil.rmtree(host)
    shutil.copytree(srcdir, host, symlinks=True,
                    ignore=shutil.ignore_patterns("*.o", "*.a", "busybox", "busybox_unstripped"))
    shutil.copy2(os.path.join(srcdir, ".config"), os.path.join(host, ".config"))
    # 主机是 glibc：ns_* 在 libresolv.so.2 里，必须把 -lresolv 装回去
    n = patch_flags(host, drop_resolv=False)
    print("Makefile.flags: 装回 %d 行 -lresolv（glibc 的 ns_* 在 libresolv 里）" % n)
    kconfig_refresh(host)
    r = run(["make", "-j4"], cwd=host, timeout=1800)
    hbb = os.path.join(host, "busybox")
    if os.path.exists(hbb):
        print("主机版编译成功: %s" % hbb)
        lst = run([hbb, "--list"])
        applets = [x.strip() for x in lst.stdout.split() if x.strip()]
        with open(os.path.join(RB, "applets.txt"), "w") as f:
            f.write("\n".join(applets) + "\n")
        print("applet 数: %d（已写入 tools/rxinbox/applets.txt）" % len(applets))
        v = run([hbb])
        print("版本行: %s" % (v.stdout or v.stderr).strip().splitlines()[0][:80])
    else:
        print("主机版编译失败（不影响 arm64 产物），尾部日志：")
        print(((r.stdout or "") + (r.stderr or ""))[-1500:])
    return 0


if __name__ == "__main__":
    sys.exit(main())
