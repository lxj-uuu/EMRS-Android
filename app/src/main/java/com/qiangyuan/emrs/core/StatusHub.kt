package com.qiangyuan.emrs.core

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.widget.TextView

/**
 * 底部状态条消息总线（架构 core/StatusHub.kt）。
 * 各屏显示时把自己的 tishi TextView 挂进来；post() 把文本发到主线程写入当前激活屏的状态条。
 */
object StatusHub {

    private val main = Handler(Looper.getMainLooper())

    /** 当前激活屏的状态 TextView（ScreenManager 切屏时更新） */
    private var activeStatus: TextView? = null

    /** App 上下文（Beep 用），MainActivity.onCreate 注入 */
    @Volatile
    var appContext: Context? = null

    /** 串口状态文本（StatusBarView 显示） */
    @Volatile
    var serialText: String = "串口未连接"
        set(value) {
            field = value
            main.post { serialListener?.invoke(value) }
        }

    private var serialListener: ((String) -> Unit)? = null

    fun attachStatusView(view: TextView?) {
        activeStatus = view
    }

    fun setSerialListener(l: ((String) -> Unit)?) {
        serialListener = l
        l?.invoke(serialText)
    }

    /** 发布主提示文本（等价 Delphi 各窗体 tishi.simpletext := ...） */
    fun post(text: String) {
        main.post {
            activeStatus?.text = text
        }
    }

    /** 发布主提示文本（保证在主线程调用时立即生效） */
    fun postNow(text: String) {
        activeStatus?.text = text
    }

    /** 提示音（等价 Delphi beep） */
    fun beep() {
        main.post {
            try {
                val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
                tg.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                main.postDelayed({ tg.release() }, 300)
            } catch (e: Exception) {
                // 无音频设备等场景忽略
            }
        }
    }
}
