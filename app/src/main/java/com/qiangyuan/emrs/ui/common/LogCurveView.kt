package com.qiangyuan.emrs.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 对数坐标曲线 View（架构 ui/common/LogCurveView.kt，Form5 数据页核心）。
 * - 网格：绿纵轴/横轴 + 9 分格横线 + 道位置刻度（对应 LX5.Timer1Timer 绘制段）；
 * - 曲线：log10(v*1e4)/9 映射 1e-4..1e5，正=红、负=蓝（测量/实测曲线），
 *   正演曲线 正=黑、负=亮绿（clLime）；
 * - 双缓冲：数据变化时重画离屏 Bitmap，onDraw 仅贴图；
 * - 无效值钳位：|v|≥1e6 → 1e6；|v|<1e-4 → 1e-4（log 域保护，不改变 1:1 映射区间内的结果）。
 */
class LogCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 一条曲线的数据（1 基：values[1..points]） */
    class Curve(
        val values: DoubleArray,
        val points: Int,
        val positiveColor: Int,      // 正值颜色
        val negativeColor: Int,      // 负值颜色
        val positive: BooleanArray   // 每点正负（决定分段颜色）
    )

    private val gridPaint = Paint().apply { color = Color.parseColor("#007700"); strokeWidth = 2f }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.5f; style = Paint.Style.STROKE
    }

    private var ruler = IntArray(1)
    private var points = 0
    private var curves: List<Curve> = emptyList()

    private var buffer: Bitmap? = null

    /** 更新道位置表并重绘网格（等价 Form5.Timer1Timer 绘制段） */
    fun setRuler(rulerH: IntArray, points: Int) {
        this.ruler = rulerH
        this.points = points
        rebuildBuffer()
        invalidate()
    }

    /** 替换曲线集合 */
    fun setCurves(curves: List<Curve>) {
        this.curves = curves
        rebuildBuffer()
        invalidate()
    }

    /** 清空曲线 */
    fun clearCurves() {
        curves = emptyList()
        rebuildBuffer()
        invalidate()
    }

    private fun rebuildBuffer() {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val bmp = buffer?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buffer = bmp
        val c = Canvas(bmp)
        drawContent(c)
    }

    private fun drawContent(canvas: Canvas) {
        val w = width
        val h = height
        canvas.drawColor(Color.BLACK)

        // 坐标轴（绿）：左缘 + 底缘 + 9 分格 + 道刻度（LX5 L559-576）
        canvas.drawLine(0f, (h - 1).toFloat(), 0f, 0f, gridPaint)
        canvas.drawLine(0f, 1f, 3f, 1f, gridPaint)
        for (i in 1..8) {
            val y = h.toFloat() * i / 9f
            canvas.drawLine(0f, y, 3f, y, gridPaint)
        }
        canvas.drawLine(0f, (h - 1).toFloat(), (w - 1).toFloat(), (h - 1).toFloat(), gridPaint)
        if (points > 0) {
            for (i in 1..points) {
                if (i < ruler.size) {
                    val x = ruler[i].toFloat()
                    canvas.drawLine(x, h - 4f, x, h - 1f, gridPaint)
                }
            }
        }

        // 曲线：逐点连线，按正负切换颜色（源码逐点 lineto）
        for (curve in curves) {
            var prevX = 0f
            var prevY = 0f
            var started = false
            for (i in 1..curve.points) {
                if (i >= ruler.size) continue
                var v = curve.values[i]
                if (v.isNaN() || v.isInfinite()) continue
                if (v >= 1000000.0) v = 1000000.0        // 源码 if v2idot>=1e6
                if (v < 0.0001) v = 0.0001               // log 域下限保护
                val x = ruler[i].toFloat()
                val y = (h - h * (Math.log10(v * 10000.0) / 9.0)).toFloat()
                val pos = if (i < curve.positive.size) curve.positive[i] else true
                linePaint.color = if (pos) curve.positiveColor else curve.negativeColor
                if (!started) {
                    canvas.drawPoint(x, y, linePaint)
                    started = true
                } else {
                    canvas.drawLine(prevX, prevY, x, y, linePaint)
                }
                prevX = x
                prevY = y
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildBuffer()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = buffer
        if (bmp != null) {
            canvas.drawBitmap(bmp, 0f, 0f, null)
        } else {
            drawContent(canvas)
        }
    }

    companion object {
        /** 曲线 y 像素（LX5 L1066/1163）：y=trunc(imgH-imgH*log10(v*1e4)/9) */
        fun logY(v: Double, imgH: Int): Int {
            return (imgH - imgH * (Math.log10(v * 10000.0) / 9.0)).toInt()
        }
    }
}
