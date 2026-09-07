package com.qiangyuan.emrs.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 250 点线性波形 View（架构 ui/common/WaveView.kt，Form4 调试屏 P2）。
 * 黑底白边框、黄中轴；正值红、负值品红（对应 LX4.Button4Click 绘制）。
 */
class WaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var data: DoubleArray = DoubleArray(0)   // 1 基 [1..250]
    private var max: Double = 10.0

    private val paintFrame = Paint().apply { color = Color.WHITE; strokeWidth = 2f }
    private val paintMid = Paint().apply { color = Color.YELLOW; strokeWidth = 1.5f }
    private val paintPos = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; strokeWidth = 2f }
    private val paintNeg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.MAGENTA; strokeWidth = 2f }

    /** 设置 250 点数据（1 基 [1..250]）并自适应量程（源码 hz50_max<10 → 10） */
    fun setData(values: DoubleArray, count: Int) {
        data = values
        max = 10.0
        for (i in 1..count) {
            val a = Math.abs(values[i])
            if (a > max) max = a
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 上下边框 + 左边框（白）
        canvas.drawLine(0f, 0f, w, 0f, paintFrame)
        canvas.drawLine(0f, h - 1f, w, h - 1f, paintFrame)
        canvas.drawLine(0f, 0f, 0f, h - 1f, paintFrame)
        // 中轴（黄）
        canvas.drawLine(0f, h / 2f, w, h / 2f, paintMid)

        if (data.isEmpty()) return
        val n = 250
        var prevX = 0f
        var prevY = 0f
        var started = false
        for (i in 1..n) {
            if (i >= data.size) break
            val v = data[i]
            val x = (i.toDouble() / 250.0 * w).toFloat()
            val y = (h / 2f - (v / max * (h / 2f)).toFloat() - 1f)
            val p = if (v > 0) paintPos else paintNeg
            if (!started) {
                canvas.drawPoint(x, y, p)
                started = true
            } else {
                canvas.drawLine(prevX, prevY, x, y, p)
            }
            prevX = x
            prevY = y
        }
    }
}
