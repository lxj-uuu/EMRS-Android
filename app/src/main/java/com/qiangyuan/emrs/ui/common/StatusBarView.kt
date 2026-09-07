package com.qiangyuan.emrs.ui.common

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.qiangyuan.emrs.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 底部状态条（架构 ui/common/StatusBarView.kt / §6.4）。
 * 四段：主提示 | 串口状态●+设备名 | 供电次数/总次数 | 日期时间。
 */
class StatusBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val tvText: TextView
    private val tvSerial: TextView
    private val tvCount: TextView
    private val tvClock: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private val clockTick = object : Runnable {
        override fun run() {
            tvClock.text = fmt.format(Date())
            handler.postDelayed(this, 15000)
        }
    }

    init {
        setBackgroundColor(Color.parseColor("#202020"))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pad = (context.resources.displayMetrics.density * 6).toInt()

        fun mk(weight: Float, color: Int, size: Float): TextView {
            return TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
                setTextColor(color)
                textSize = size
                setPadding(pad, 0, pad, 0)
                maxLines = 1
            }
        }
        tvText = mk(1f, Color.WHITE, 14f)
        tvSerial = mk(0f, Color.parseColor("#00CC00"), 13f)
        tvCount = mk(0f, Color.WHITE, 13f)
        tvClock = mk(0f, Color.WHITE, 13f)
        row.addView(tvText)
        row.addView(tvSerial)
        row.addView(tvCount)
        row.addView(tvClock)
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        handler.post(clockTick)
        setSerialStatus("串口未连接", false)
    }

    /** 主提示（tishi） */
    fun setText(text: String) {
        tvText.text = text
    }

    fun getText(): String = tvText.text.toString()

    /** 串口状态灯 */
    fun setSerialStatus(text: String, ok: Boolean) {
        tvSerial.text = if (ok) "●$text" else "●$text"
        tvSerial.setTextColor(if (ok) Color.parseColor("#00CC00") else Color.parseColor("#EE0000"))
    }

    /** 供电次数/总次数 */
    fun setSupplyCount(done: Int, total: Int) {
        tvCount.text = "$done/$total 次"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(clockTick)
        com.qiangyuan.emrs.core.StatusHub.setSerialListener { text ->
            setSerialStatus(text, text.contains("已打开"))
        }
        com.qiangyuan.emrs.core.StatusHub.attachStatusView(tvText)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(clockTick)
    }
}
