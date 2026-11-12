package com.rxinfiles.tool

import android.view.Gravity
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * 把底部面板的容器换成液态玻璃面。
 *
 * **必须在 `setOnShowListener` 里调用。** 两个原因叠在一起：
 *
 * 1. `design_bottom_sheet` 这个容器是 Material 在 show 的过程中才挂上去的，
 *    提前 `findViewById` 只会拿到 null；
 * 2. 它的背景**不是布局里写的那个** —— `BottomSheetBehavior` 会用 `backgroundTint`
 *    当场生成一个 `MaterialShapeDrawable` 盖上去。所以在主题里改
 *    `<item name="android:background">` 会被它盖掉，白改。
 *
 * 本工程用到它的面板：**Android/data 无权限、排序、定位失败**这三个 ——
 * 它们的内容就是面板本身，容器撑内容高度正合适。
 *
 * **书签 / 压缩包 / ⋯ 更多这三个不要用它**，它们走
 * [SheetDialog.expandFullScreen]（撑全屏 + 容器透明）。两者都往
 * `setOnShowListener` 里写，而那是**替换语义**，一起用必然互相覆盖 ——
 * 3.15.7 就是这么翻车的：容器又被设回不透明的玻璃，撑到全屏后把整屏涂成一块死色。
 * 各自在自己已有的 `setOnShowListener` 里调一次就够。
 *
 * **为什么不走「主题里配 bottomSheetStyle 自动生效」那条路**：那条链路的生效条件
 * 随 material 版本变化，一旦不生效，容器的背景会变成 `@null` —— 也就是**整个面板全透明**，
 * 用户看到的是内容浮在桌面壁纸上。而这里最坏的结果只是「保持 Material 默认背景」，
 * 难看而已，点得到、读得清、退得出。
 *
 * 全程 `runCatching`：背景换不上绝不能把面板的 show 流程带崩。
 */
fun BottomSheetDialog.glassSurface() {
    adaptiveWidth()
    runCatching {
        findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(R.drawable.bg_glass_sheet)
    }
}

/** 面板在手机上占满窗口，在平板和桌面窗口中居中并限制阅读宽度。 */
fun BottomSheetDialog.adaptiveWidth() {
    runCatching {
        val bottomSheet = findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?: return@runCatching
        val params = bottomSheet.layoutParams ?: return@runCatching
        val availableWidth = bottomSheet.resources.displayMetrics.widthPixels
        val maxWidth = bottomSheet.resources.getDimensionPixelSize(R.dimen.sheet_max_width)
        val wantedWidth = minOf(availableWidth, maxWidth)
        var changed = false
        if (params.width != wantedWidth) {
            params.width = wantedWidth
            changed = true
        }
        if (params is CoordinatorLayout.LayoutParams) {
            val wantedGravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            if (params.gravity != wantedGravity) {
                params.gravity = wantedGravity
                changed = true
            }
        }
        if (changed) bottomSheet.layoutParams = params
    }
}
