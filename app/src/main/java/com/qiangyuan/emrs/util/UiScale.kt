package com.qiangyuan.emrs.util

import android.content.Context

/**
 * Delphi 设计坐标(792×567 @96dpi) → 实际 dp/sp 缩放助手（架构 util/UiScale.kt / §6.2）。
 * 提供 x/y 像素 → dp 换算与字号放大系数，供各屏按 7~10 寸横屏微调。
 */
object UiScale {

    private var density: Float = 1.5f
    private var fontScale: Float = 1.15f

    fun init(context: Context) {
        val dm = context.resources.displayMetrics
        density = if (dm.density > 0f) dm.density else 1.5f
    }

    /** Delphi 横向像素 → dp */
    fun dx(px: Int): Int = (px / 1.5f * density).toInt().coerceAtLeast(1)

    /** Delphi 纵向像素 → dp */
    fun dy(px: Int): Int = (px / 1.5f * density).toInt().coerceAtLeast(1)

    /** 字号放大系数（触屏可读性，架构建议 1.1~1.25） */
    fun fsp(sp: Int): Int = (sp * fontScale).toInt()

    /** 触屏最小按钮高度（dp） */
    const val MIN_BUTTON_DP = 44
}
