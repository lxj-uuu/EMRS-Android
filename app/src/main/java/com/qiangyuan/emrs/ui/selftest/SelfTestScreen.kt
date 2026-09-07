package com.qiangyuan.emrs.ui.selftest

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.qiangyuan.emrs.R
import com.qiangyuan.emrs.core.StatusHub
import com.qiangyuan.emrs.protocol.FrameCodec
import com.qiangyuan.emrs.ui.ScreenHost
import com.qiangyuan.emrs.ui.ScreenManager
import com.qiangyuan.emrs.ui.common.VBarMeter

/**
 * Form2 自检屏（架构 ui/selftest/SelfTestScreen.kt / 规格书 §2.2、§3.2-①）。
 * - 状态帧 msg[1]=1 → 更新电池电压/VAB 显示与电压条（MainActivity 分发 onStatusFrame）；
 * - 返回：发命令02（连续 2 次）→ ACK 后回主屏。
 */
class SelfTestScreen(
    private val host: ScreenHost,
    container: ViewGroup
) : ScreenManager.Screen {

    override val key = ScreenHost.KEY_SELFTEST
    override val statusView: TextView? = null

    override val root: View =
        LayoutInflater.from(container.context).inflate(R.layout.screen_selftest, container, false)

    private val tvBattery = root.findViewById<TextView>(R.id.tv_battery)
    private val tvVab = root.findViewById<TextView>(R.id.tv_vab)
    private val vbarVab = root.findViewById<VBarMeter>(R.id.vbar_vab)

    init {
        root.findViewById<Button>(R.id.btn_return).setOnClickListener {
            // Form2.Button1Click：发 A0 A0 02 02 AF AF（6B，连续发送两次）
            StatusHub.post(root.context.getString(R.string.status_sending))
            val frame = FrameCodec.selfTestStopFrame()
            host.sendCommand(FrameCodec.EmrsCommand.SELF_TEST_STOP, frame)
            host.sendCommand(FrameCodec.EmrsCommand.SELF_TEST_STOP, frame)
        }
    }

    override fun onShow() {
        // 初始显示（DFM 初值：Edit2=54 / Edit1=50）
        tvBattery.text = "54"
        tvVab.text = "50"
        vbarVab.setValue(50.0)
    }

    override fun onHide() {
        // 无动作
    }

    override fun onBack(): Boolean {
        // 返回键 = 点“返回”：发命令02×2 → ACK(2) 由 MainActivity 回主屏
        root.findViewById<Button>(R.id.btn_return).performClick()
        return true
    }

    /** 状态帧更新（msg[1]=1，LX1 L451-465 语义；vab/电池已两位截断） */
    fun onStatusFrame(vab: Double, battery: Double) {
        tvBattery.text = com.qiangyuan.emrs.algo.Formatting.floatToStr(battery)
        tvVab.text = com.qiangyuan.emrs.algo.Formatting.floatToStr(vab)
        vbarVab.setValue(vab)
    }
}
