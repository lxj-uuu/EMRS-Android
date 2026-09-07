package com.qiangyuan.emrs.ui.supply

import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.TextView
import com.qiangyuan.emrs.R
import com.qiangyuan.emrs.algo.Formatting
import com.qiangyuan.emrs.algo.ProcessOutput
import com.qiangyuan.emrs.algo.ProcessPipeline
import com.qiangyuan.emrs.algo.ShotData
import com.qiangyuan.emrs.core.AppState
import com.qiangyuan.emrs.core.Exec
import com.qiangyuan.emrs.core.StatusHub
import com.qiangyuan.emrs.data.CalibrationStore
import com.qiangyuan.emrs.data.ProjectStore
import com.qiangyuan.emrs.data.ResultFiles
import com.qiangyuan.emrs.protocol.FrameCodec
import com.qiangyuan.emrs.ui.ScreenHost
import com.qiangyuan.emrs.ui.ScreenManager
import com.qiangyuan.emrs.ui.common.Grid8x4
import com.qiangyuan.emrs.ui.common.LogCurveView

/**
 * Form5 供电测量/数据处理屏（架构 ui/supply/SupplyScreen.kt / 规格书 §2.5、§3.3-§3.5）。
 *
 * 页0 供电测量：Grid8x4 + 已供次数 + 供电开始（命令03）；
 * 页1 数据处理：LogCurveView + 信息面板 + d1..d32 数字条 + memo + 处理/存储按钮；
 * 未存盘守卫：cppd && !agnexit → “数据尚未存盘!!!”+Beep 仅一次（§3.5）。
 */
class SupplyScreen(
    private val host: ScreenHost,
    container: ViewGroup
) : ScreenManager.Screen {

    override val key = ScreenHost.KEY_SUPPLY
    override val statusView: TextView? = null

    override val root: View =
        LayoutInflater.from(container.context).inflate(R.layout.screen_supply, container, false)

    private val ctx = root.context

    private val pageSupply = root.findViewById<View>(R.id.page_supply)
    private val pageProcess = root.findViewById<View>(R.id.page_process)
    private val gridCurrent = root.findViewById<Grid8x4>(R.id.grid_current)
    private val tvSupplied = root.findViewById<TextView>(R.id.tv_supplied)
    private val curveView = root.findViewById<LogCurveView>(R.id.curve_view)
    private val dStrip = root.findViewById<GridLayout>(R.id.d_strip)
    private val tvMemo = root.findViewById<TextView>(R.id.tv_memo)
    private val btnProcess = root.findViewById<Button>(R.id.btn_process)
    private val btnSave = root.findViewById<Button>(R.id.btn_save)
    private val labelInfoLoop = root.findViewById<TextView>(R.id.label_info_loop)
    private val tvLoopValue = root.findViewById<TextView>(R.id.tv_loop_value)
    private val labelInfoPoint = root.findViewById<TextView>(R.id.label_info_point)
    private val tvPointValue = root.findViewById<TextView>(R.id.tv_point_value)
    private val tvAvgValue = root.findViewById<TextView>(R.id.tv_avg_value)
    private val tvLegend = root.findViewById<TextView>(R.id.tv_legend)
    private val etLine = root.findViewById<EditText>(R.id.et_line)
    private val etPoint = root.findViewById<EditText>(R.id.et_point)
    private val labelLine = root.findViewById<TextView>(R.id.label_line)
    private val labelPoint = root.findViewById<TextView>(R.id.label_point)

    /** d1..d32 数字条（16×2 TextView，等价 Panel3） */
    private val dCells = Array(32) { TextView(ctx) }

    init {
        root.findViewById<Button>(R.id.tab_supply).setOnClickListener { showPage0() }
        root.findViewById<Button>(R.id.tab_process).setOnClickListener { showPage1() }
        root.findViewById<Button>(R.id.btn_supply_start).setOnClickListener { onSupplyStart() }
        root.findViewById<Button>(R.id.btn_return).setOnClickListener { onReturn() }
        btnProcess.setOnClickListener { onProcess() }
        btnSave.setOnClickListener { onSave() }
        // 线号/点号编辑框点击 → 未存盘守卫（源码 OnClick/OnDblClick）
        val guard = View.OnClickListener { guardUnsaved() }
        etLine.setOnClickListener(guard)
        etPoint.setOnClickListener(guard)

        // d1..d32 单元格（白底黑字，右对齐）
        dStrip.removeAllViews()
        for (i in 0 until 32) {
            val tv = dCells[i]
            tv.textSize = 13f
            tv.setTextColor(Color.BLACK)
            tv.gravity = Gravity.CENTER or Gravity.RIGHT
            val p = GridLayout.LayoutParams()
            p.width = 0
            p.height = GridLayout.LayoutParams.WRAP_CONTENT
            p.columnSpec = GridLayout.spec(i % 16, 1f)
            p.rowSpec = GridLayout.spec(i / 16, 1f)
            p.setMargins(2, 2, 2, 2)
            dStrip.addView(tv, p)
        }
    }

    // ================= 页切换 =================

    /** 页0：进页/供电开始前置复位（规格书 §3.3 前置段） */
    private fun showPage0() {
        pageSupply.visibility = View.VISIBLE
        pageProcess.visibility = View.GONE
        resetSessionUi()
    }

    private fun resetSessionUi() {
        AppState.resetMeasureSession()
        AppState.keCunpan = false
        gridCurrent.refresh()
        tvSupplied.text = "0"
        clearDStrip()
        tvMemo.text = ""
    }

    /** 页1：TabbedNotebook1Click 语义 */
    private fun showPage1() {
        if (!AppState.keSjcl) {
            StatusHub.post(ctx.getString(R.string.status_no_data))
            StatusHub.beep()
        } else if (AppState.mode == AppState.MODE_MEASURE) {
            StatusHub.post(ctx.getString(R.string.status_please_process))
        } else {
            StatusHub.post(ctx.getString(R.string.status_please_calib))
        }
        pageSupply.visibility = View.GONE
        pageProcess.visibility = View.VISIBLE
        // 模式化按钮/面板（规格书 §2.5：测量=处理测量数据/存储处理结果；标定=计算标定系数/存储标定系数隐藏）
        if (AppState.mode == AppState.MODE_MEASURE) {
            btnProcess.text = ctx.getString(R.string.btn_process_measure)
            btnSave.visibility = View.VISIBLE
            labelInfoLoop.text = ctx.getString(R.string.info_loop_coil)
            labelInfoPoint.text = ctx.getString(R.string.info_point_line)
        } else {
            btnProcess.text = ctx.getString(R.string.btn_calc_calib)
            btnSave.visibility = View.GONE
            // 标定模式信息面板：Label14/15=正演曲线/实测曲线（规格书 §3.4）
            labelInfoLoop.text = ctx.getString(R.string.label_forward_curve)
            labelInfoPoint.text = ctx.getString(R.string.label_measured_curve)
        }
        // 画坐标网格（源码 Timer1Timer 直调一次）
        curveView.post {
            AppState.imgW = curveView.width
            AppState.imgH = curveView.height
            AppState.applyPrg(true)
            curveView.setRuler(AppState.rulerH, AppState.points)
        }
    }

    private fun clearDStrip() {
        for (tv in dCells) tv.text = ""
    }

    // ================= 守卫 =================

    /** §3.5：cppd && !agnexit → “数据尚未存盘!!!”+Beep，置 agnexit=true（只提醒一次，不硬拦） */
    private fun guardUnsaved() {
        if (AppState.cppd && !AppState.agnexit) {
            StatusHub.post(ctx.getString(R.string.status_not_saved))
            StatusHub.beep()
            AppState.agnexit = true
        }
    }

    // ================= 供电测量 =================

    /** Form5.Button4Click 供电开始 */
    private fun onSupplyStart() {
        guardUnsaved()
        val lineText = etLine.text.toString()
        // 1. 线号改变 → 创建/覆写项目登记文件（源码 old_xianhao 比较）
        ProjectStore.ensureOnSupplyStart(ctx, lineText)
        // 2. 回写 form3 xianhao/dianhao（源码 form3.xianhao := xxh.text）
        AppState.work.lineNo = lineText
        AppState.work.pointNo = etPoint.text.toString()
        // 3. 重置 cppd/agnexit 与会话
        AppState.cppd = false
        AppState.agnexit = false
        AppState.resetMeasureSession()
        AppState.sumIAve = 0.0
        gridCurrent.refresh()
        tvSupplied.text = "0"
        clearDStrip()
        tvMemo.text = ""
        // 4. 发命令03（A0 A0 03 03 N N AF AF；send[4]=3 固定）
        StatusHub.post(ctx.getString(R.string.status_sending))
        host.sendCommand(FrameCodec.EmrsCommand.SUPPLY, FrameCodec.supplyFrame(AppState.supplyTimes))
    }

    /** ACK(send[3]=3) 后测量态（MainActivity 分发） */
    fun onMeasureAck() {
        StatusHub.postNow(ctx.getString(R.string.status_supplying))
        AppState.keCunpan = false
        showPage0()
    }

    /** 每帧数据（引擎已写入 AppState；此处刷新表格/计数） */
    fun onShot(shot: ShotData) {
        gridCurrent.refresh()
        tvSupplied.text = shot.gdcs.toString()
        if (shot.cellCol == 0 && shot.cellRow == 0) {
            // 无动作：与源码一致，仅表格推进
        }
    }

    /** msg[2] > gdcs+1 */
    fun onDataLoss() {
        StatusHub.post(ctx.getString(R.string.status_data_loss))
        StatusHub.beep()
    }

    /** gdcs==供电次数 */
    fun onSupplyFinished() {
        StatusHub.post(ctx.getString(R.string.status_supply_done))
        StatusHub.beep()
    }

    // ================= 数据处理 =================

    /** Button2Click：测量=处理测量数据；标定=计算标定系数 */
    private fun onProcess() {
        if (!AppState.keSjcl) {
            StatusHub.post(ctx.getString(R.string.status_no_data))
            StatusHub.beep()
            return
        }
        if (AppState.mode == AppState.MODE_MEASURE) processMeasure() else processCalibration()
    }

    /** 测量模式（§3.4 Button2Click 测量段 + Timer3Timer） */
    private fun processMeasure() {
        // 每次处理前按当前 prgNo 重算道时间（QA 第 1 轮 P1-4；源码 LX5 L668-684
        // 在进入处理页时重算 shjian[1..points]，否则用户改程序编号后 n/c/dat 道时间列错误）
        AppState.applyPrg(true)
        AppState.computeShjian()
        // average_i = sum_i_ave / 供电次数
        AppState.averageI = AppState.sumIAve / AppState.supplyTimes.toDouble()
        // Panel2 信息
        tvLoopValue.text = AppState.work.loopSide + "/" + AppState.work.coilArea
        tvPointValue.text = AppState.work.pointNo + "/" + AppState.work.lineNo
        tvAvgValue.text = Formatting.floatToStr(AppState.averageI) + " A"
        tvLegend.text = ctx.getString(R.string.legend_pos) + " (红)   " + ctx.getString(R.string.legend_neg) + " (蓝)"
        StatusHub.post(ctx.getString(R.string.status_calculating))
        Exec.background {
            val out = ProcessPipeline.runMeasure()
            Exec.main { applyOutput(out, forwardCurve = null) }
        }
    }

    /** 标定模式（§3.4 Button2Click 标定段 + Timer2Timer）：读正演 → 画正演曲线 → 处理 → 写 bdxs.txt */
    private fun processCalibration() {
        // 与测量模式一致：处理前按当前 prgNo 重算道时间（QA 第 1 轮 P1-4；
        // 标定分支随后被 readZyjg 读回的正演道时间覆盖，与源码 LX5 Button2Click 时序一致）
        AppState.applyPrg(true)
        AppState.computeShjian()
        StatusHub.post(ctx.getString(R.string.status_calculating))
        Exec.background {
            // 读正演文件 wjljv+zyjg.text：第 1 行道数（≠points → “道数不一致！”）
            val zy = ResultFiles.readZyjg(ctx, AppState.work.forwardFile, AppState.points)
            if (zy == null) {
                Exec.main {
                    StatusHub.post(ctx.getString(R.string.status_dao_mismatch))
                    StatusHub.beep()
                }
                return@background
            }
            val (shj, zyv) = zy
            for (i in 1..AppState.points) {
                AppState.shjian[i] = shj[i]
                AppState.zhengyan[i] = zyv[i]
            }
            // 正演值规整（LX5 L448-461）
            ProcessPipeline.roundForwardLadder(AppState.zhengyan, AppState.points)
            // 正演曲线（正值黑 clBlack、负值亮绿 clLime；绝对值钳位 1e6）
            val n = AppState.points
            val fwdVals = DoubleArray(n + 1)
            val fwdPos = BooleanArray(n + 1)
            for (i in 1..n) {
                var v = Math.abs(AppState.zhengyan[i])
                if (v >= 1000000.0) v = 1000000.0
                fwdVals[i] = v
                fwdPos[i] = AppState.zhengyan[i] >= 0
            }
            val forward = LogCurveView.Curve(fwdVals, n, Color.BLACK, Color.parseColor("#00FF00"), fwdPos)
            // Timer2Timer：算法链 + biaoding[dot]=zhengyan[dot]/v2i[dot]
            val out = ProcessPipeline.runCalibration()
            // 写 bdxs.txt（points + points 行 biaoding）
            CalibrationStore.write(ctx, AppState.points, AppState.biaoding)
            Exec.main { applyOutput(out, forwardCurve = forward) }
        }
    }

    /** 处理结果上屏：d1..d32 + memo + 曲线 + 完成态状态语 */
    private fun applyOutput(out: ProcessOutput, forwardCurve: LogCurveView.Curve?) {
        for (dot in 1..AppState.points) {
            if (dot <= 32) dCells[dot - 1].text = Formatting.floatToStr(out.displ[dot])
        }
        tvMemo.text = out.memoLines.joinToString("\n")

        val measured = LogCurveView.Curve(
            out.curveValues, AppState.points,
            Color.parseColor("#FF0000"), Color.parseColor("#0000FF"), out.curvePositive
        )
        curveView.post {
            AppState.imgW = curveView.width
            AppState.imgH = curveView.height
            AppState.applyPrg(true)
            curveView.setRuler(AppState.rulerH, AppState.points)
            curveView.setCurves(if (forwardCurve != null) listOf(forwardCurve, measured) else listOf(measured))
        }
        StatusHub.post(
            if (forwardCurve == null) ctx.getString(R.string.status_process_done)
            else ctx.getString(R.string.calib_done)
        )
    }

    // ================= 存盘 =================

    /** Button5Click 存储处理结果（测量模式） */
    private fun onSave() {
        if (!AppState.keCunpan) {
            StatusHub.post(ctx.getString(R.string.status_nothing_save))
            StatusHub.beep()
            return
        }
        StatusHub.post(ctx.getString(R.string.status_calculating))
        Exec.background {
            val files = ResultFiles.saveMeasureResults(ctx)
            Exec.main {
                if (files.isEmpty()) {
                    StatusHub.post(ctx.getString(R.string.file_write_error))
                    StatusHub.beep()
                } else {
                    // 源码：存盘成功 → ke_cunpan=false
                    AppState.keCunpan = false
                    AppState.cppd = false
                    StatusHub.post(ctx.getString(R.string.status_save_ok))
                }
            }
        }
    }

    // ================= 返回 =================

    /** Form5.Button1Click：守卫 → 回写 form3 线/点号 → 回主界面 */
    private fun onReturn() {
        guardUnsaved()
        AppState.work.lineNo = etLine.text.toString()
        AppState.work.pointNo = etPoint.text.toString()
        host.showScreen(ScreenHost.KEY_MAIN)
    }

    // ================= Screen 生命周期 =================

    override fun onShow() {
        // Form1.Button3/4 已设置模式与 prg/次数；此处初始化界面
        etLine.setText(AppState.work.lineNo)
        etPoint.setText(AppState.work.pointNo)
        val measure = AppState.mode == AppState.MODE_MEASURE
        etLine.visibility = if (measure) View.VISIBLE else View.GONE
        etPoint.visibility = if (measure) View.VISIBLE else View.GONE
        labelLine.visibility = if (measure) View.VISIBLE else View.GONE
        labelPoint.visibility = if (measure) View.VISIBLE else View.GONE
        showPage0()
        StatusHub.postNow(ctx.getString(R.string.status_please_supply))
    }

    override fun onHide() {
        // 无动作
    }

    override fun onBack(): Boolean {
        // 返回键 = 点“返回”（含守卫）
        onReturn()
        return true
    }
}
