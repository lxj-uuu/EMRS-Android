package com.qiangyuan.emrs.ui.charge

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import com.qiangyuan.emrs.R
import com.qiangyuan.emrs.algo.Formatting
import com.qiangyuan.emrs.core.AppState
import com.qiangyuan.emrs.core.StatusHub
import com.qiangyuan.emrs.data.GzcsStore
import com.qiangyuan.emrs.protocol.FrameCodec
import com.qiangyuan.emrs.ui.ScreenHost
import com.qiangyuan.emrs.ui.ScreenManager
import com.qiangyuan.emrs.ui.common.VBarMeter

/**
 * Form6 充电屏（架构 ui/charge/ChargeScreen.kt / 规格书 §2.6、§3.2-②）。
 * - 设定 Edit2 ↔ SeekBar（Max=120 → 0..60V）联动（Edit2Change/TrackBar2Change）；
 * - 充电：按目标 VAB 组帧 8B 命令04（不重发）；
 * - 实测帧 msg[1]=4 → 更新实测条；vab≥目标 → “充电完毕 !”+焦点转退出；
 * - 退出：写 gzcs.txt（19 行）→ 回主屏。
 */
class ChargeScreen(
    private val host: ScreenHost,
    container: ViewGroup
) : ScreenManager.Screen {

    override val key = ScreenHost.KEY_CHARGE
    override val statusView: TextView? = null

    override val root: View =
        LayoutInflater.from(container.context).inflate(R.layout.screen_charge, container, false)

    private val ctx = root.context
    private val etTarget = root.findViewById<EditText>(R.id.et_target)
    private val seekTarget = root.findViewById<SeekBar>(R.id.seek_target)
    private val tvActual = root.findViewById<TextView>(R.id.tv_actual)
    private val vbarActual = root.findViewById<VBarMeter>(R.id.vbar_actual)

    /** 防止程序性赋值触发联动回写 */
    private var syncing = false

    init {
        // Edit2 → 滑块（Edit2Change：0..60 限制 + 滑块联动）
        etTarget.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (syncing) return
                val v = s?.toString()?.toDoubleOrNull() ?: 0.0
                AppState.targetVab = s?.toString() ?: ""
                syncing = true
                // Edit2Change（LX6 L136-145）：trunc(vab/60*120+0.5)（四舍五入）
                seekTarget.progress = Formatting.trunc(v.coerceIn(0.0, 60.0) / 60.0 * 120.0 + 0.5).toInt()
                syncing = false
            }
        })
        // 滑块 → Edit2（TrackBar2Change）
        seekTarget.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || syncing) return
                // TrackBar2Change（LX6 L147-154）：vab=pos/120*60; vab=trunc(vab*100)/100 ——
                // 两位截断保留 0.5V 档（QA 第 1 轮 P1-2：pos=59 → 29.5，非 29）
                val vab = progress / 120.0 * 60.0
                val v = Formatting.trunc(vab * 100.0).toDouble() / 100.0
                syncing = true
                etTarget.setText(Formatting.floatToStr(v))
                syncing = false
                AppState.targetVab = Formatting.floatToStr(v)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        root.findViewById<Button>(R.id.btn_charge_start).setOnClickListener {
            // Form6.Button1Click：sheding=trunc(vab/50*1023) 组帧 8B（命令04 不重发）
            val target = etTarget.text.toString().toDoubleOrNull() ?: 0.0
            StatusHub.post(ctx.getString(R.string.status_charging))
            host.sendCommand(FrameCodec.EmrsCommand.CHARGE, FrameCodec.chargeFrame(target))
        }
        root.findViewById<Button>(R.id.btn_exit2).setOnClickListener {
            // Form6.Button2Click：写 gzcs.txt → 回主界面
            GzcsStore.save(ctx, AppState.targetVab)
            host.showScreen(ScreenHost.KEY_MAIN)
        }
    }

    override fun onShow() {
        // Form6.FormCreate：edit2.text := tmpvab（gzcs 第 10 行恢复）
        AppState.targetVab = AppState.tmpvab
        syncing = true
        etTarget.setText(AppState.tmpvab)
        val v = AppState.tmpvab.toDoubleOrNull() ?: 0.0
        // FormCreate 恢复 tmpvab 后滑块联动走 Edit2Change 语义（trunc(vab/60*120+0.5)）
        seekTarget.progress = Formatting.trunc(v.coerceIn(0.0, 60.0) / 60.0 * 120.0 + 0.5).toInt()
        syncing = false
        tvActual.text = "0"
        vbarActual.setValue(0.0)
        StatusHub.postNow(ctx.getString(R.string.status_charge_please_set))
    }

    override fun onHide() {
        // 无动作
    }

    override fun onBack(): Boolean {
        // 返回键 = 点“退出”
        root.findViewById<Button>(R.id.btn_exit2).performClick()
        return true
    }

    /** 实测状态帧（msg[1]=4，LX1 L435-449） */
    fun onActualVab(vab: Double) {
        tvActual.text = Formatting.floatToStr(vab)
        vbarActual.setValue(vab)
        // 充电完毕判定（引擎用原始未截断值判定，此处按显示值再次呈现提示语）
        val target = AppState.targetVab.toDoubleOrNull() ?: return
        if (vab >= target) {
            StatusHub.post(ctx.getString(R.string.status_charge_done))
            StatusHub.beep()
        }
    }
}
