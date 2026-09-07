package com.qiangyuan.emrs.ui

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.qiangyuan.emrs.core.StatusHub

/**
 * 六屏管理器（等价 Delphi Form1..Form6 常驻 + hide/show 切换，架构 §1.3/§6.1）。
 * 单 Activity 内唯一 FrameLayout 容器，同一时刻只挂一个屏的根 View。
 */
class ScreenManager(private val container: FrameLayout) {

    /** 每个屏必须实现的接口 */
    interface Screen {
        val key: String
        val root: View
        /** 屏底部状态条 TextView（ScreenManager 挂到 StatusHub） */
        val statusView: TextView?
        /** 首次/再次显示时调用（等价 Delphi OnShow 的初始化段） */
        fun onShow()
        /** 隐藏时调用 */
        fun onHide()
        /** 系统返回键；true=已处理，false=交给默认（退出确认） */
        fun onBack(): Boolean
    }

    private val screens = LinkedHashMap<String, Screen>()
    private var current: Screen? = null

    fun register(screen: Screen) {
        screens[screen.key] = screen
    }

    fun get(key: String): Screen? = screens[key]

    fun currentKey(): String = current?.key ?: ""

    /** 切屏：等价 form.hide / form.show */
    fun show(key: String) {
        val target = screens[key] ?: return
        val old = current
        if (old === target) {
            target.onShow()
            return
        }
        old?.onHide()
        container.removeAllViews()
        container.addView(target.root)
        current = target
        // 屏内无独立状态条（共用 activity 底部 StatusBarView）时不覆盖已注册视图
        target.statusView?.let { StatusHub.attachStatusView(it) }
        target.onShow()
    }

    /** 返回键：交给当前屏的 onBack */
    fun back(): Boolean = current?.onBack() ?: false
}
