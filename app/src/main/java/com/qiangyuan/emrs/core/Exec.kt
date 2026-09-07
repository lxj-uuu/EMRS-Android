package com.qiangyuan.emrs.core

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

/**
 * 极简后台执行器（架构 core/Exec.kt）。
 * 单后台 HandlerThread 做文件 IO / 长任务，完成后 post 回主线程；
 * 替代协程依赖（架构 §1.2 不引 kotlinx-coroutines）。
 */
object Exec {

    private val ioThread: HandlerThread = HandlerThread("emrs-io").apply { start() }
    private val ioHandler = Handler(ioThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 在后台线程执行 */
    fun background(r: Runnable) {
        ioHandler.post(r)
    }

    /** 在主线程执行 */
    fun main(r: Runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run() else mainHandler.post(r)
    }
}
