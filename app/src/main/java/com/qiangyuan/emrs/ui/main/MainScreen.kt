package com.qiangyuan.emrs.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.qiangyuan.emrs.R
import com.qiangyuan.emrs.core.AppState
import com.qiangyuan.emrs.core.Exec
import com.qiangyuan.emrs.core.StatusHub
import com.qiangyuan.emrs.data.CalibrationStore
import com.qiangyuan.emrs.protocol.FrameCodec
import com.qiangyuan.emrs.ui.ScreenHost
import com.qiangyuan.emrs.ui.ScreenManager

/**
 * Form1 主界面屏（架构 ui/main/MainScreen.kt / 规格书 §2.1、§3.2）。
 * 六大按钮：自检/充电/设置/标定/测量/结束；附 P2 调试屏入口“显示波形”。
 */
class MainScreen(
    private val host: ScreenHost,
    container: ViewGroup
) : ScreenManager.Screen {

    override val key = ScreenHost.KEY_MAIN
    override val statusView: TextView? = null

    override val root: View =
        LayoutInflater.from(container.context).inflate(R.layout.screen_main, container, false)

    init {
        val ctx = root.context
        root.findViewById<Button>(R.id.btn_selftest).setOnClickListener {
            // ① 自检（Form1.Button1Click）：组帧 6B → 发送
            StatusHub.post(ctx.getString(R.string.status_sending))
            host.sendCommand(FrameCodec.EmrsCommand.SELF_TEST, FrameCodec.selfTestFrame())
        }
        root.findViewById<Button>(R.id.btn_charge).setOnClickListener {
            // ② 充电（Form1.Button8Click）：A0 A0 04 00 04 AF AF（设定 0V 进入充电监控）
            StatusHub.post(ctx.getString(R.string.status_sending))
            host.sendCommand(FrameCodec.EmrsCommand.CHARGE_ENTER, FrameCodec.chargeEnterFrame())
        }
        root.findViewById<Button>(R.id.btn_setup).setOnClickListener {
            // Form1.Button2Click → Form3.show
            host.showScreen(ScreenHost.KEY_SETUP)
        }
        root.findViewById<Button>(R.id.btn_calib).setOnClickListener {
            // ③ 标定（Form1.Button3Click）：bdcl='bd'；隐藏线/点号；切页0
            enterSupply(AppState.MODE_CALIBRATION)
        }
        root.findViewById<Button>(R.id.btn_measure).setOnClickListener {
            // ④ 测量（Form1.Button4Click）：读 bdxs.txt → biaoding[]；失败提示不进入
            enterMeasure()
        }
        root.findViewById<Button>(R.id.btn_exit).setOnClickListener {
            // ⑥ 结束（Form1.Button6Click）：close → 安卓为退出确认
            host.confirmExit()
        }
        root.findViewById<Button>(R.id.btn_show_wave).setOnClickListener {
            // ⑤ 显示波形（Form1.Button5Click，P2 调试屏）
            host.showScreen(ScreenHost.KEY_WAVE)
        }
    }

    private fun enterSupply(mode: String) {
        AppState.mode = mode
        AppState.wjljv = AppState.work.dataDir
        // 源码：测量入口将“是否标定”硬编码为 true（未标定也可测量，架构已核对）
        if (mode == AppState.MODE_MEASURE) AppState.yibiaoding = true
        AppState.applyPrg(true)
        host.showScreen(ScreenHost.KEY_SUPPLY)
    }

    /**
     * 测量入口：后台读 bdxs.txt（第 1 行 points，随后 points 行 biaoding[]）。
     * 读失败 → 状态“读取标定文件失败，无法进行测量！”（不进入 Form5）。
     */
    private fun enterMeasure() {
        val ctx = root.context
        StatusHub.post(ctx.getString(R.string.status_sending))
        Exec.background {
            val read = CalibrationStore.read(ctx)
            if (read == null) {
                Exec.main {
                    StatusHub.post(ctx.getString(R.string.status_read_bd_fail))
                    StatusHub.beep()
                }
                return@background
            }
            val (points, arr) = read
            for (i in 1..points) AppState.biaoding[i] = arr[i]
            Exec.main { enterSupply(AppState.MODE_MEASURE) }
        }
    }

    override fun onShow() {
        StatusHub.postNow(root.context.getString(R.string.status_please_click))
    }

    override fun onHide() {
        // 无动作
    }

    override fun onBack(): Boolean = false   // 主屏返回键交 Activity（退出确认）
}
