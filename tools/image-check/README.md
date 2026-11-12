# tools/image-check —— 图片解码尺寸策略 / 绘制闸门的断言

```sh
JAVA_HOME="C:/Program Files/Java/jdk-20" sh tools/image-check/run.sh   # 行为：55 项
python3 tools/image-check/verify-imagesampling.py                      # 源码不变量：47 项
```

（两个项数不是估计值，是跑出来的 —— 改断言要同步改这里。）

## 为什么要有它

这条策略唯一的工作是「决定按多大采样率解码」。它**算错了不会当场报错** ——
只有当某个尺寸的图被交给 Canvas 时才会抛：

```
java.lang.RuntimeException: Canvas: trying to draw too large(113032192bytes) bitmap.
    at com.rxinfiles.tool.ZoomImageView.onDraw(ZoomImageView.kt:213)
```

而**绘制路径上没有 try/catch 能兜住** `RecordingCanvas.throwIfCannotDraw` 抛出的异常，
用户看到的就是闪退、连一句提示都没有。

更关键的是：这条策略以前**埋在 `ImageViewerActivity` 里**，一行都断言不了。
所以「要求两条边都超过阈值才肯降采样」那个写法才活到了真机上。

## 那个崩溃是怎么来的（也是本目录存在的理由）

旧判据长这样：

```kotlin
while (width / (sample * 2) >= 3200 && height / (sample * 2) >= 3200) sample *= 2
```

`&&` 意味着**两条边都要超过 6400** 才肯降采样。而 4852×5824 的短边只有 4852，
`4852 / 2 = 2426 < 3200`，于是判定「不用降采样」，按原始尺寸解码：

```
4852 × 5824 = 28,258,048 像素 × 4 字节 = 113,032,192 字节
```

**这个乘法就是崩溃日志里那个数字的指纹**，已经钉进断言（`crashFingerprintChecks`）。

## 断言分两层

| 层 | 管什么 |
| --- | --- |
| `run.sh`（`ImageSamplingCheck.java`） | **算得对不对**：给定尺寸算出什么采样率、解码后多少字节 |
| `verify-imagesampling.py` | **错的写法有没有偷偷回来**：该消失的标识符确实消失、该只有一份的数字只有一份、绘制站点那个早退真的在 |

`ImageSampling` 是**纯 Java、零 Android API**，所以这两层都能脱离开机直接跑。

## 七节行为断言里最要紧的三条

1. **穷举不变量**：`1..4100` 的**全量** `(w,h)` 组合，解码字节一律 ≤ 64MB 硬闸门；
   且 `sample == 1` 的每一例都**真的**满足两道闸门（不是判据没用上）。最坏是 `3250×4000` → 49.6MB。
2. **崩溃指纹**：4852×5824 必须是 `sample = 2`（旧判据给 1）、解码后 `2426×2912` ≈ 26.9MB。
   长宽互换也一样 —— EXIF 旋转过的图就是互换的。
3. **RGBA_F16 会翻倍**：同样 13M 像素，`ARGB_8888` 是 49.6MB，`RGBA_F16`（宽度色域 / HDR 图会拿到）
   就是 99.2MB，**超出闸门**。所以运行时「这张图多少字节」只能问
   `Bitmap.getByteCount()`（= `rowBytes × height`），**不能按「像素数 × 4」猜**。

## 两个必须记住的判据（都在源码里坐实了）

1. **「过大」按位图字节数判，不按像素数判。** 系统判的就是 `bitmap.getByteCount()`
   （`RecordingCanvas.throwIfCannotDraw`），而每像素几字节取决于配置。
2. **不能用 `drawable.intrinsicWidth/Height` 算。** 那是**按 density 缩放过的渲染尺寸**：
   `BitmapDrawable.computeBitmapSize()` → `Bitmap.getScaledWidth()` → `Bitmap.scaleFromDensity()`。
   位图自带的 density 与目标 density 不一致时，这个数跟真实像素数差几倍。
   要尺寸就问 `DrawableSize.pixelsOf()`。

## 判据用「整除」，不用「向上取整」

`fits()` 里是 `width / sample`。若改成向上取整，`8191×6348` 这种「差一个像素就压线」的尺寸
会被**多降一档**（4095 变 2048），白白丢掉一半清晰度。
解码器实际可能多出的那 1 个像素由 `sampledSize()` 的**上界**负责兜 ——
判据按整除、上界按向上取整，两个数不一样是**正常的**（断言里写明了）。

## 踩过的坑

- **「剥完注释去查注释」**：判「不许出现某标识符」必须先 `strip_comments()`；
  但反过来，要查**注释里记着的崩溃指纹**就必须用**原始源码**。
  这次两种都犯了一遍（第 6 节因此红了两条），所以 `strip_comments()` 的用法在脚本里写成了注释。
- **断言阈值别定成「我以为是那样」**：一开始写「绘制硬上限 ≤ 100MB 的一半」，
  而实际是 64MB —— 64% 也是「显著低于」，断言的措辞比数字更重要。
- **同一个数字别写两处**：`ZoomImageView` 一度自己抄了一份 `64MB`，
  现在只引用 `ImageSampling.MAX_DRAW_BYTES`（源码断言里有一条专门守着这件事）。
