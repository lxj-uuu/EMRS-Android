package com.qiangyuan.emrs.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 自绘电压条（架构 ui/common/VBarMeter.kt，对应 Form2/Form6 的 TrackBar 只读显示 + 0~60V 刻度）。
 * 滑块位置 = trunc(v/60*120)（源码语义）；刻度 0,10,…,60。
 */
class VBarMeter @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var value: Double = 0.0
    private val paintTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#303030")
        style = Paint.Style.FILL
    }
    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00A0E0")
        style = Paint.Style.FILL
    }
    private val paintThumb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val paintTick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        textSize = 26f
    }
    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#808080")
        strokeWidth = 2f
    }

    /** 设定电压值（0~60） */
    fun setValue(v: Double) {
        value = v.coerceIn(0.0, 60.0)
        invalidate()
    }

    fun getValue(): Double = value

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val trackTop = h * 0.35f
        val trackBottom = h * 0.65f

        // 轨道
        canvas.drawRect(0f, trackTop, w, trackBottom, paintTrack)
        // 填充
        val frac = (value / 60.0).toFloat()
        canvas.drawRect(0f, trackTop, w * frac, trackBottom, paintFill)
        // 滑块（trunc(v/60*120) 语义等价）
        val pos = (Math.floor(value / 60.0 * 120.0) / 120.0).toFloat()
        val cx = w * pos
        canvas.drawCircle(cx.coerceIn(0f, w), (trackTop + trackBottom) / 2f, h * 0.28f, paintThumb)

        // 刻度 0,5,...,60（源码 Label4..19 红色刻度）
        for (i in 0..12) {
            val v = i * 5
            val x = w * v / 60f
            canvas.drawLine(x, trackBottom, x, trackBottom + 8f, paintLine)
            if (v % 10 == 0) {
                val label = v.toString()
                val tw = paintTick.measureText(label)
                canvas.drawText(label, (x - tw / 2).coerceIn(0f, w - tw), h - 4f, paintTick)
            }
        }
    }
}
