package com.qiangyuan.emrs.ui.wave

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.qiangyuan.emrs.R
import com.qiangyuan.emrs.core.Exec
import com.qiangyuan.emrs.core.StatusHub
import com.qiangyuan.emrs.data.DataDirManager
import com.qiangyuan.emrs.data.TextFileIO
import com.qiangyuan.emrs.ui.ScreenHost
import com.qiangyuan.emrs.ui.ScreenManager
import com.qiangyuan.emrs.ui.common.WaveView
import java.io.File

/**
 * Form4 波形显示/50Hz 辅助屏（P2 调试屏，架构 ui/wave/WaveDebugScreen.kt）。
 * “波形显示”读数据目录 hz50.txt 250 点画波形；退出回主屏。
 */
class WaveDebugScreen(
    private val host: ScreenHost,
    container: ViewGroup
) : ScreenManager.Screen {

    override val key = ScreenHost.KEY_WAVE
    override val statusView: TextView? = null

    override val root: View =
        LayoutInflater.from(container.context).inflate(R.layout.screen_wave, container, false)

    private val waveView = root.findViewById<WaveView>(R.id.wave_view)

    init {
        root.findViewById<Button>(R.id.btn_draw_wave).setOnClickListener {
            // Form4.Button4Click：读 hz50.txt 250 点画波形
            Exec.background {
                val f = File(DataDirManager.dataDir(root.context), "hz50.txt")
                if (!TextFileIO.exists(f)) {
                    Exec.main { StatusHub.post(root.context.getString(R.string.wave_file_missing)) }
                    return@background
                }
                val values = DoubleArray(251)   // 1 基 [1..250]
                var count = 0
                for (line in TextFileIO.readLines(f)) {
                    val v = line.trim().toDoubleOrNull() ?: continue
                    count++
                    if (count > 250) break
                    values[count] = v
                }
                Exec.main {
                    if (count == 0) {
                        StatusHub.post(root.context.getString(R.string.file_read_error))
                    } else {
                        waveView.setData(values, count)
                    }
                }
            }
        }
        root.findViewById<Button>(R.id.btn_exit2).setOnClickListener {
            // Form4.Button1Click：隐藏标签并回主界面
            host.showScreen(ScreenHost.KEY_MAIN)
        }
    }

    override fun onShow() {
        StatusHub.postNow(root.context.getString(R.string.btn_draw_wave))
    }

    override fun onHide() {
        // 无动作
    }

    override fun onBack(): Boolean {
        host.showScreen(ScreenHost.KEY_MAIN)
        return true
    }
}
