#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ImageSampling / 图片绘制闸门的**源码不变量**断言。

`tools/image-check/run.sh` 跑的是**行为**（给定尺寸算出什么采样率）；
这一支查的是**源码本身**：该消失的写法确实消失了、该只有一份的数字只有一份、
绘制站点上那个早退真的在。两者互补 —— 行为断言管「算得对不对」，
源码断言管「错的那套写法有没有偷偷回来」。

用法（工程根目录下）：
    python tools/image-check/verify-imagesampling.py
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "rxinfiles", "tool")

total = 0
fails = []


def check(ok, msg):
    global total
    total += 1
    if ok:
        print("  ok    " + msg)
    else:
        fails.append(msg)
        print("  FAIL  " + msg)


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def strip_comments(src):
    """去掉 // 与 /* */ 注释，但保留字符串字面量里的内容。

    判「不许出现某标识符」之前**必须**先过这一道 —— 否则自己在注释里解释
    「旧的 xxx 已经删掉了」就会把断言判红。（这个坑在 verify-scriptpipe.py
    里踩过一次，verify-textslice.py 里又踩过一次，这次直接写进工具函数。）
    """
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if c == '"':
            out.append(c)
            i += 1
            while i < n:
                out.append(src[i])
                if src[i] == "\\":
                    i += 1
                    if i < n:
                        out.append(src[i])
                elif src[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if c == "'":
            out.append(c)
            i += 1
            while i < n:
                out.append(src[i])
                if src[i] == "\\":
                    i += 1
                    if i < n:
                        out.append(src[i])
                elif src[i] == "'":
                    i += 1
                    break
                i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            i += 2
            while i + 1 < n and not (src[i] == "*" and src[i + 1] == "/"):
                i += 1
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def body_of(src, marker):
    """截出 marker 所在的那一段到其后的第一个行首 '    }'。

    按固定长度截会截到隔壁函数（verify-textslice.py 里踩过），所以按结束标记截。
    """
    at = src.find(marker)
    if at < 0:
        return ""
    end = src.find("\n    }", at)
    return src[at:] if end < 0 else src[at:end + 6]


IMG = read(os.path.join(SRC, "ImageViewerActivity.kt"))
ZOOM = read(os.path.join(SRC, "ZoomImageView.kt"))
SAMPLING = read(os.path.join(SRC, "ImageSampling.java"))
SIZE = read(os.path.join(SRC, "DrawableSize.kt"))

IMG_C = strip_comments(IMG)
ZOOM_C = strip_comments(ZOOM)
SAMPLING_C = strip_comments(SAMPLING)
SIZE_C = strip_comments(SIZE)

print("== 1. ImageSampling 的常数（唯一的真源）==")
check(re.search(r"MAX_LONG\s*=\s*4096\s*;", SAMPLING_C) is not None,
      "长边上限 4096")
check(re.search(r"MAX_AREA\s*=\s*13_000_000L\s*;", SAMPLING_C) is not None,
      "像素总量上限 13_000_000")
check(re.search(r"MAX_DRAW_BYTES\s*=\s*64L\s*\*\s*1024\s*\*\s*1024\s*;", SAMPLING_C) is not None,
      "绘制硬上限 64MB")
check(re.search(r"MAX_SAMPLE\s*=\s*1\s*<<\s*16\s*;", SAMPLING_C) is not None,
      "采样率上限 1<<16")
check("BYTES_ARGB_8888" in SAMPLING_C and "BYTES_RGBA_F16" in SAMPLING_C,
      "每像素字节数是具名常数（ARGB_8888=4 / RGBA_F16=8），不是散落的魔数")
check(SAMPLING_C.count("4096") >= 1 and "Integer.MAX" not in SAMPLING_C,
      "极值运算走 long，不靠 int 上限兜")

print("== 2. 判据必须是「整除」，且不可能是旧写法的形状 ==")
fits = body_of(SAMPLING_C, "private static boolean fits(")
check(fits != "", "找得到 fits()")
check("width / sample" in fits and "height / sample" in fits,
      "按整除取解码尺寸（与旧实现一致，避免多降一档白丢清晰度）")
check(not re.search(r"sample\s*\*\s*2", fits) and "sample * 2" not in fits
      and "sample*2" not in fits,
      "fits() 里不出现 (sample * 2) —— 那正是「要求两条边都超阈值」的形状")
check("Math.max(w, h) <= MAX_LONG" in fits and "<= MAX_AREA" in fits,
      "两道闸门是「与」关系，不是「或」")
# 全源码都不该再出现那个错误形状
check(not re.search(r"sample\s*\*\s*2\s*\)\s*>=", SAMPLING_C),
      "全文件都没有旧写法 `w / (sample * 2) >= ...`")

print("== 3. ImageViewerActivity：策略只有一份，旧的估算已消失 ==")
check("ImageSampling.sampleSize" in IMG_C, "降采样走 ImageSampling.sampleSize")
check("ImageSampling.MAX_DRAW_BYTES" in IMG_C, "绘制上限引用 ImageSampling.MAX_DRAW_BYTES")
check(not re.search(r"private\s+fun\s+sampleSize\s*\(", IMG_C),
      "Activity 里不再有第二份 sampleSize 实现")
check(not re.search(r"\bfun\s+estimatedBytes\b", IMG_C),
      "estimatedBytes（按像素数 × 4 猜的）已删掉")
check("不出现 MAX_DECODE_LONG" not in IMG_C and "MAX_DECODE_AREA" not in IMG_C,
      "MAX_DECODE_LONG / MAX_DECODE_AREA 这两个本地副本已删（改引用 ImageSampling）")
check(not re.search(r"4096", IMG_C), "Activity 里不再散落 4096 这个魔数")
check(not re.search(r"13_000_000", IMG_C), "Activity 里不再散落 13_000_000 这个魔数")
check("DrawableSize.bytesOf" in IMG_C, "字节数一律问 DrawableSize.bytesOf")
check("DrawableSize.shrinkToLimit" in IMG_C, "进缓存前先过 shrinkToLimit")
check(re.search(r"MAX_CACHE_BYTES\s*=\s*64L\s*\*\s*1024\s*\*\s*1024", IMG_C) is not None,
      "缓存预算 64MB（按真实字节数算，所以这个数就是内存里实际占用上限）")
check("values.sumOf { DrawableSize.bytesOf(it) }" in IMG_C,
      "缓存淘汰按真实字节数记账")
check("BuildConfig.VERSION_NAME" in IMG_C,
      "信息框里报出版本号（以后再出现「你装的是哪个包」直接看这里）")
decode_result = body_of(IMG_C, "val drawable = decode(file)")
check("isDestroyed" in decode_result,
      "旋转销毁后不再把旧解码结果写回 Activity")
check("drawable?.let { recycleQuietly(it) }" in decode_result,
      "翻页或销毁后的过期位图/动图会释放，而不是直接丢弃")

print("== 4. DrawableSize：只认系统那个数 ==")
check("getByteCount()" in SIZE_C, "用 Bitmap.getByteCount()（= Canvas 判的那个数）")
bitmap_branch = body_of(SIZE_C, "fun bytesOf(")
check(bitmap_branch != "", "找得到 bytesOf()")
# BitmapDrawable 分支只能用 getByteCount；intrinsic 只能出现在动图那一段
bitmap_part = bitmap_branch.split("is AnimatedImageDrawable")[0]
check("intrinsicWidth" not in bitmap_part and "intrinsicHeight" not in bitmap_part,
      "BitmapDrawable 分支不用 density 缩放过的 intrinsicWidth/Height")
check("getByteCount" in bitmap_part, "BitmapDrawable 分支走 getByteCount")
check("AnimatedImageDrawable" in bitmap_branch,
      "动图单独一条分支（拿不到帧位图，只能估上界）")
check("fun shrinkToLimit(" in SIZE_C, "提供 shrinkToLimit()")
shrink = body_of(SIZE_C, "fun shrinkToLimit(")
check("createScaledBitmap" in shrink, "缩小的手段是 createScaledBitmap（逐次减半）")
check("isRecycled" in shrink, "动已回收的位图有防守")
check("pageOfShrinkGuard" not in SIZE_C and "guard" in shrink,
      "缩小循环有次数上限（不依赖位图一定缩得动）")

print("== 5. ZoomImageView：绘制站点上的最后一道闸门 ==")
check("imageBlocked" in ZOOM_C, "有 imageBlocked 标记")
ondraw = body_of(ZOOM_C, "override fun onDraw(")
check("if (imageBlocked) return" in ondraw,
      "onDraw 见到标记直接返回 —— 不能把 RuntimeException 抛给用户")
setimage = body_of(ZOOM_C, "fun setImage(")
check("DrawableSize.shrinkToLimit" in setimage, "setImage 先过闸门")
check("ImageSampling.MAX_DRAW_BYTES" in setimage,
      "上限引用 ImageSampling.MAX_DRAW_BYTES（不再抄一份 64MB）")
check("onImageBlocked" in ZOOM_C, "拦下时回调，好给用户一句人话")
check("drawable.draw(canvas)" in ondraw, "真正绘制的那一行还在")
check(not re.search(r"64L\s*\*\s*1024\s*\*\s*1024", ZOOM_C),
      "ZoomImageView 里没有第二份 64MB 魔数")

print("== 6. 那个崩溃的指纹，就写在本工程里 ==")
# 注意：这两条查的是**注释里的内容**，所以必须用原始源码（SAMPLING），
# 不能用 strip_comments 之后的 SAMPLING_C —— 又把「剥完注释去查注释」这个坑踩了一次。
check("113032192" in SAMPLING or "113_032_192" in SAMPLING,
      "ImageSampling 的注释记着 113032192 这个崩溃指纹")
check("4852" in SAMPLING and "5824" in SAMPLING,
      "并记着产生它的尺寸 4852×5824（下次有人想改回旧判据，注释先说清代价）")

print("== 7. 脚手架齐全 ==")
check(os.path.isfile(os.path.join(HERE, "run.sh")), "run.sh 在")
check(os.path.isfile(os.path.join(HERE, "README.md")), "README 在")
run_sh = read(os.path.join(HERE, "run.sh"))
check("ImageSampling.java" in run_sh and "ImageSamplingCheck.java" in run_sh,
      "run.sh 同时编策略与断言")
check("MAX_AREA" in read(os.path.join(HERE, "ImageSamplingCheck.java")),
      "行为断言里引用了策略常数（不是抄数字）")

print()
print("结果: %d 项通过, %d 项失败" % (total - len(fails), len(fails)))
sys.exit(1 if fails else 0)
