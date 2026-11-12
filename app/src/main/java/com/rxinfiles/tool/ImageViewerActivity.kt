package com.rxinfiles.tool

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * 图片查看器。
 *
 * 几个关键取向：
 *  - **原地翻页**：同一目录下的图片按名称排序，左右滑动/按钮切换，不用退回文件列表。
 *  - **动图保留动画**：GIF / 动态 WebP 交给 `ImageDecoder.decodeDrawable`，
 *    拿到 [AnimatedImageDrawable] 后直接播放；静止图同样走这条路（回来的是 BitmapDrawable）。
 *  - **EXIF 自动纠正**：ImageDecoder 会按 EXIF 旋转，不需要自己读 orientation。
 *  - **内存有上限**：按需降采样。尺寸策略在 [ImageSampling]（纯 Java，可断言）：
 *    同时限制长边（[ImageSampling.MAX_LONG]）与像素总量（[ImageSampling.MAX_AREA]）。
 *    超大图宁可有损也不闪退 —— 这是整个查看器最重要的一条约束：
 *    系统 Canvas 在绘制过大的位图时会**直接抛 RuntimeException**（`throwIfCannotDraw`），
 *    而用户连一句提示都看不到。
 *  - **「过大」按位图真实字节数判，不按像素数猜**：判据统一走 [DrawableBytes]，
 *    它取的就是系统判定用的 `Bitmap.getByteCount()`。
 *    缓存同时受条数与总字节数约束（[MAX_CACHE] / [MAX_CACHE_BYTES]）。
 *  - 应用身份读不到的文件（root 才能看的系统图）会先 [FileOps.stageToCache] 拷一份再显示。
 *    暂存副本由**本实例**持有：真正退出界面（[isFinishing]）时释放，旋转重建时保留
 *    （新实例还要继续看）；异常遗留的目录由冷启动的兜底清理扫掉。
 */
class ImageViewerActivity : AppCompatActivity() {

    private lateinit var zoomView: ZoomImageView
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var progress: View
    private lateinit var errorText: TextView
    private lateinit var topBar: View
    private lateinit var bottomBar: View

    private val images = mutableListOf<File>()
    private var index = 0

    /**
     * 本实例自己「借」出来的缓存目录（每个目录只属于本实例）。
     *
     * [decode] 在单线程 executor 上跑、[onDestroy] 在主线程读/删，
     * 所以用线程安全集合，避免登记与释放互相看见半截。
     */
    private val stagedDirs = CopyOnWriteArrayList<File>()

    /**
     * 调用方（压缩包预览）通过 Intent 交来的暂存目录。
     *
     * 里面的副本由本实例接手释放；但它也可能是**重建后的新实例**要继续读的文件，
     * 所以只有 [isFinishing] 的销毁才能删。
     */
    private var handedStagedDir: File? = null

    /** 解码是单线程串行执行的：连翻十张也不会同时开十个解码任务把内存顶爆 */
    private val decoder = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RxImageDecode")
    }

    /**
     * 已经加载出来的图片，翻回去时不重复解码。
     *
     * 淘汰同时看**条数**和**字节数**：一张 4096×3174 的图就是 52MB，
     * 只按 6 条算最多能压住 300MB 位图，低内存设备上直接 OOM。
     *
     * 字节数走 [DrawableSize]（= `Bitmap.getByteCount()`）。**不能用「像素数 × 4」猜**：
     * 猜小了预算就形同虚设，缓存会悄悄吃满堆，表现就是「特别卡」（GC 风暴），
     * 严重时直接 OOM。
     */
    private val cache = object : LinkedHashMap<String, Drawable>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Drawable>?): Boolean =
            size > MAX_CACHE || values.sumOf { DrawableSize.bytesOf(it) } > MAX_CACHE_BYTES
    }

    private var chromeVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_viewer)
        // 看图时不希望屏幕自己黑掉
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        zoomView = findViewById(R.id.zoomView)
        titleText = findViewById(R.id.titleText)
        subtitleText = findViewById(R.id.subtitleText)
        progress = findViewById(R.id.progress)
        errorText = findViewById(R.id.errorText)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)

        // 压缩包预览会把「取出来的副本目录」一并交进来：退出时由本实例负责归还
        handedStagedDir = intent.getStringExtra(EXTRA_STAGED_DIR)
            ?.takeIf { it.isNotEmpty() }
            ?.let { File(it) }

        val startPath = intent.getStringExtra(EXTRA_PATH)
        if (startPath.isNullOrEmpty()) {
            finish()
            return
        }

        loadSiblings(startPath)
        if (images.isEmpty()) {
            errorText.text = "该目录下没有可显示的图片"
            errorText.visibility = View.VISIBLE
        } else {
            val initial = images.indexOfFirst { it.absolutePath == startPath }.coerceAtLeast(0)
            showAt(savedInstanceState?.getInt(STATE_INDEX, initial)?.coerceIn(images.indices) ?: initial)
        }

        setupUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_INDEX, index)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        (zoomView.currentDrawable() as? AnimatedImageDrawable)?.stop()
        decoder.shutdownNow()
        cache.clear()
        // root 文件是「借」到缓存里看的，退出时归还 —— 但只在**真正离开界面**时归还：
        // 旋转重建（isFinishing=false）后新实例还要用这份副本，删了会让新实例白屏；
        // 这种情况由冷启动的兜底清理回收，优先保证正在看的内容不被自己删掉。
        if (isFinishing) {
            releaseStagedDirs()
            deleteQuietly(handedStagedDir)
            handedStagedDir = null
        }
    }

    // ------------------------------------------------------------------ UI

    private fun setupUi() {
        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<TextView>(R.id.rotateBtn).setOnClickListener { zoomView.rotate() }
        findViewById<TextView>(R.id.fitBtn).setOnClickListener { zoomView.fitToView() }
        findViewById<TextView>(R.id.actualBtn).setOnClickListener { zoomView.setScale(1f) }
        findViewById<TextView>(R.id.moreBtn).setOnClickListener { showMoreMenu() }
        findViewById<TextView>(R.id.prevBtn).setOnClickListener { page(-1) }
        findViewById<TextView>(R.id.nextBtn).setOnClickListener { page(1) }
        findViewById<TextView>(R.id.infoBtn).setOnClickListener { showInfo() }

        zoomView.onPageRequest = { direction -> page(direction) }
        zoomView.onSingleTap = { toggleChrome() }
        zoomView.onScaleChanged = { updateSubtitle() }
        // 最后一道闸门拦下了图：给用户一句人话，而不是让他对着空白屏猜
        zoomView.onImageBlocked = {
            errorText.text = "这张图超出本机可绘制的上限，已跳过显示\n" +
                "（继续显示会让应用闪退）"
            errorText.visibility = View.VISIBLE
        }
    }

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        val visibility = if (chromeVisible) View.VISIBLE else View.GONE
        topBar.visibility = visibility
        bottomBar.visibility = visibility
    }

    private fun loadSiblings(startPath: String) {
        val file = File(startPath)
        val parent = file.parentFile
        images.clear()
        if (parent != null) {
            images.addAll(FileRepository.listImages(parent.absolutePath))
        }
        if (images.none { it.absolutePath == file.absolutePath }) {
            // 单文件打开（例如从压缩包里预览出来的临时文件）
            images.add(file)
        }
    }

    private fun page(direction: Int) {
        if (images.size <= 1) {
            toast(if (direction > 0) "已是最后一张" else "已是第一张")
            return
        }
        val next = (index + direction + images.size) % images.size
        showAt(next)
    }

    private fun showAt(target: Int) {
        if (target !in images.indices) return
        index = target
        val file = images[index]
        titleText.text = file.name
        updateSubtitle()

        val cached = cache[file.absolutePath]
        if (cached != null) {
            attach(cached)
            return
        }

        errorText.visibility = View.GONE
        progress.visibility = View.VISIBLE
        val token = file.absolutePath
        decoder.execute {
            val drawable = decode(file)
            runOnUiThread {
                // 过期结果不能进缓存；动图在解码时已经启动，也必须在这里停下。
                if (isFinishing || isDestroyed || images.getOrNull(index)?.absolutePath != token) {
                    drawable?.let { recycleQuietly(it) }
                    return@runOnUiThread
                }
                progress.visibility = View.GONE
                if (drawable == null) {
                    zoomView.setImage(null)
                    errorText.text = "无法显示该图片\n${file.name}\n\n" +
                        "可能是格式不受支持、图片过大，或应用没有读取权限"
                    errorText.visibility = View.VISIBLE
                } else {
                    // 进缓存之前先归一化：万一哪天解码路径漏出一张超限位图，
                    // 也不能带着它进缓存、更不能交给 Canvas
                    val ready = DrawableSize.shrinkToLimit(drawable, ImageSampling.MAX_DRAW_BYTES)
                    if (ready == null) {
                        recycleQuietly(drawable)
                        zoomView.setImage(null)
                        errorText.text = "无法显示该图片\n${file.name}\n\n" +
                            "图片尺寸超出本机可绘制的上限"
                        errorText.visibility = View.VISIBLE
                    } else {
                        if (ready !== drawable) {
                            recycleQuietly(drawable)
                            Log.w(TAG, "shrunk to draw limit: ${file.name}")
                        }
                        cache[token] = ready
                        attach(ready)
                    }
                }
            }
        }
    }

    private fun attach(drawable: Drawable) {
        (zoomView.currentDrawable() as? AnimatedImageDrawable)?.stop()
        zoomView.setImage(drawable)
        (drawable as? AnimatedImageDrawable)?.start()
        errorText.visibility = View.GONE
        progress.visibility = View.GONE
        updateSubtitle()
    }

    private fun updateSubtitle() {
        val file = images.getOrNull(index) ?: return
        val drawable = zoomView.currentDrawable()
        val dimensions = if (drawable != null && drawable.intrinsicWidth > 0) {
            "${drawable.intrinsicWidth}×${drawable.intrinsicHeight}"
        } else {
            ""
        }
        val zoom = if (zoomView.fitScaleValue() > 0f) {
            val percent = (zoomView.currentScale() / zoomView.fitScaleValue() * 100f).toInt()
            if (percent >= 100) "${percent}%" else "适应"
        } else {
            ""
        }
        subtitleText.text = listOf(
            "${index + 1}/${images.size}",
            dimensions,
            FileRepository.formatSize(file.length()),
            zoom
        ).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    // ------------------------------------------------------------------ 解码

    /**
     * @return 可绘制的图片；失败返回 null（调用方给提示）
     */
    private fun decode(file: File): Drawable? {
        var working = file
        if (!file.canRead()) {
            // root 才能看的图：先借一份到私有缓存
            val staged = FileOps.stageToCache(this, file)
            if (staged == null) {
                Log.w(TAG, "unreadable and cannot stage: ${file.path}")
                return null
            }
            // 登记本实例的暂存目录：解码失败也**不在这里删**（同一份副本可能用于重试），
            // 统一在 isFinishing 的 onDestroy 里按所有者释放
            staged.parentFile?.let { stagedDirs.add(it) }
            working = staged
        }

        // 采样已按长边设限，正常一次就够。循环兜的是「解码器没按采样率给」的极端情况：
        // 宁可不显示，也绝不能把超大位图交给 Canvas —— 那会抛
        // `Canvas: trying to draw too large(...) bitmap` 直接闪退，用户连提示都看不到。
        var extraSample = 1
        repeat(MAX_DECODE_ATTEMPTS) {
            val drawable = decodeViaImageDecoder(working, extraSample)
                ?: decodeViaBitmapFactory(working, extraSample)
                ?: return null

            val bytes = DrawableSize.bytesOf(drawable)
            if (bytes <= ImageSampling.MAX_DRAW_BYTES) return drawable

            Log.w(
                TAG,
                "decoded bitmap too large: ${bytes}B, extraSample=$extraSample, ${file.name}"
            )
            recycleQuietly(drawable)
            extraSample *= 2
        }
        Log.e(TAG, "give up decoding (bitmap never fits): ${file.name}")
        return null
    }

    /** 被丢弃的重试结果要及时释放，否则几轮下来反而把堆撑满 */
    private fun recycleQuietly(drawable: Drawable) {
        try {
            when (drawable) {
                is BitmapDrawable -> drawable.bitmap?.recycle()
                is AnimatedImageDrawable -> drawable.stop()
                else -> Unit
            }
        } catch (t: Throwable) {
            Log.w(TAG, "recycle failed", t)
        }
    }

    // ------------------------------------------------------------ 暂存副本释放

    /**
     * 释放本实例自己借出来的缓存目录。
     *
     * 只删 [stageToCache][FileOps.stageToCache] 给本实例创建的**独占**目录，
     * 不碰共享根目录 —— 其他界面 / 后台任务的副本各有各的所有者。
     */
    private fun releaseStagedDirs() {
        for (dir in stagedDirs) deleteQuietly(dir)
        stagedDirs.clear()
    }

    private fun deleteQuietly(dir: File?) {
        if (dir == null) return
        try {
            dir.deleteRecursively()
        } catch (t: Throwable) {
            Log.w(TAG, "stage cleanup failed: ${dir.path}", t)
        }
    }

    /**
     * @param extraSample 额外的降采样倍数（安全重试用，正常为 1）
     */
    private fun decodeViaImageDecoder(file: File, extraSample: Int): Drawable? = try {
        val source = ImageDecoder.createSource(file)
        val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            val sample = ImageSampling.sampleSize(info.size.width, info.size.height) * extraSample
            if (sample > 1) {
                decoder.setTargetSampleSize(sample)
            }
            // 软件位图：硬件位图在部分设备的 Canvas 上不能直接绘制/缩放
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            // 渐进式 JPEG：允许先画半成品，避免大图长时间白屏
            decoder.setOnPartialImageListener { true }
        }
        if (drawable is AnimatedImageDrawable) {
            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            drawable.start()
        }
        drawable
    } catch (t: Throwable) {
        Log.w(TAG, "ImageDecoder failed: ${file.name}", t)
        null
    }

    /**
     * 兜底路径：部分 BMP/TIFF 变体 ImageDecoder 不认，BitmapFactory 反而能读。
     *
     * @param extraSample 额外的降采样倍数（安全重试用，正常为 1）
     */
    private fun decodeViaBitmapFactory(file: File, extraSample: Int): Drawable? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val options = BitmapFactory.Options().apply {
                inSampleSize = ImageSampling.sampleSize(bounds.outWidth, bounds.outHeight) *
                    extraSample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, options)?.let { BitmapDrawable(resources, it) }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "BitmapFactory failed: ${file.name}", t)
        null
    }

    // 降采样策略已搬到纯 Java 的 ImageSampling（唯一实现，见上方的 sampleSize 转发）。
    // 历史：这里原本写着 `while (w / (s*2) >= req && h / (s*2) >= req)` ——
    // 要求**两条边都超过阈值**才肯降采样，于是 4852×5824 这种「只有一条边大」的图
    // 按原始尺寸解码成 28,258,048 像素 × 4 = 113,032,192 字节，交给 Canvas 直接闪退。
    // 策略搬走后由 tools/image-check 的断言钉住，这类写法不可能再回来。

    // ------------------------------------------------------------------ 菜单

    private fun showInfo() {
        val file = images.getOrNull(index) ?: return
        val drawable = zoomView.currentDrawable()
        // 报**真实像素**，不报 intrinsic —— 后者是按 density 缩放过的渲染尺寸
        val pixels = drawable?.let { DrawableSize.pixelsOf(it) }
        val message = buildString {
            append("名称: ").append(file.name).append('\n')
            append("路径: ").append(file.absolutePath).append('\n')
            append("大小: ").append(FileRepository.formatSize(file.length())).append('\n')
            if (pixels != null) {
                append("显示尺寸: ").append(pixels[0]).append('×').append(pixels[1])
                    .append("（").append(FileRepository.formatSize(DrawableSize.bytesOf(drawable)))
                    .append("）").append('\n')
            }
            append("修改: ").append(FileRepository.formatTime(file.lastModified())).append('\n')
            append("版本: ").append(BuildConfig.VERSION_NAME)
        }
        AlertDialog.Builder(this)
            .setTitle("图片信息")
            .setMessage(message)
            .setPositiveButton("复制路径") { _, _ ->
                Intents.copyToClipboard(this, file.absolutePath)
                toast("已复制")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showMoreMenu() {
        val file = images.getOrNull(index) ?: return
        val actions = mutableListOf("用其它应用打开", "分享", "复制路径", "重命名", "删除")
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "用其它应用打开" -> {
                        if (!Intents.openWith(this, file)) toast("没有找到能打开它的应用")
                    }
                    "分享" -> {
                        if (!Intents.share(this, file)) toast("分享失败")
                    }
                    "复制路径" -> {
                        Intents.copyToClipboard(this, file.absolutePath)
                        toast("已复制")
                    }
                    "重命名" -> showRenameDialog(file)
                    "删除" -> confirmDelete(file)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRenameDialog(file: File) {
        val input = EditText(this).apply {
            setText(file.name)
            setSelection(text.length)
            setSingleLine(true)
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val wrapper = android.widget.LinearLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(wrapper)
            .setPositiveButton("确定") { _, _ ->
                val renamed = FileOps.rename(file, input.text.toString())
                if (renamed == null) {
                    toast("重命名失败（可能同名已存在）")
                } else {
                    loadSiblings(renamed.absolutePath)
                    val target = images.indexOfFirst { it.absolutePath == renamed.absolutePath }
                    if (target >= 0) {
                        cache.clear()
                        showAt(target)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle("删除")
            .setMessage("确定删除 ${file.name}？此操作不可撤销。")
            .setPositiveButton("删除") { _, _ ->
                Thread {
                    val outcome = try {
                        FileOps.delete(listOf(file), onEntry = { })
                    } catch (t: Throwable) {
                        null
                    }
                    runOnUiThread {
                        val ok = outcome?.ok == true && !file.exists()
                        toast(if (ok) "已删除" else "删除失败：${outcome?.error ?: "权限不足"}")
                        if (ok) {
                            cache.remove(file.absolutePath)
                            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_CHANGED_DIR, file.parent))
                            images.removeAll { it.absolutePath == file.absolutePath }
                            val parent = file.parentFile
                            if (parent != null) {
                                val remaining = FileRepository.listImages(parent.absolutePath)
                                images.clear()
                                images.addAll(remaining)
                            }
                            if (images.isEmpty()) {
                                finish()
                            } else {
                                showAt(min(index, images.size - 1))
                            }
                        }
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_PATH = "image_path"
        const val EXTRA_CHANGED_DIR = "changed_dir"

        /**
         * 调用方交来的暂存副本目录（压缩包预览用）。
         *
         * 写入端：[MainActivity.previewArchiveEntry]、[ArchiveSheet.preview]；
         * 读取端：本 Activity 在 isFinishing 的 onDestroy 里释放。
         */
        const val EXTRA_STAGED_DIR = "staged_dir"

        private const val TAG = "ImageViewer"
        private const val STATE_INDEX = "image_index"

        // 尺寸与绘制限额全部在 ImageSampling 里（纯 Java、有断言），这里不再各留一份 ——
        // 同一个数字写两处，早晚有一处忘记改。
        //   ImageSampling.MAX_LONG         长边上限 4096
        //   ImageSampling.MAX_AREA         像素总量上限 13M（≈49.6MB @ ARGB_8888）
        //   ImageSampling.MAX_DRAW_BYTES   交给 Canvas 的硬上限 64MB

        /** 解码结果仍然过大时最多重试几次（每次把采样率翻倍） */
        private const val MAX_DECODE_ATTEMPTS = 3

        private const val MAX_CACHE = 6

        /**
         * 缓存里所有位图的总字节预算；超出即淘汰最久未用的。
         *
         * 按**真实字节数**（[DrawableBytes]）算，所以这个数就是内存里的实际占用上限：
         * 6 条的上限是 300MB，这里压到 64MB —— 一张 4096×3174 的图就 52MB，
         * 预算给太大只会在低内存设备上变成 GC 风暴（表现是「特别卡」）。
         */
        private const val MAX_CACHE_BYTES = 64L * 1024 * 1024
    }
}
