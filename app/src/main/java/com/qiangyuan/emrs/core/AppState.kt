package com.qiangyuan.emrs.core

import com.qiangyuan.emrs.algo.Series
import com.qiangyuan.emrs.data.WorkParams

/**
 * 全局“工作内存”单例 —— 1:1 等价 lx1.pas 顶部的全局变量区（规格书 §3.0）。
 *
 * 数组全部按源码尺寸分配并 0 初始化（架构 §8.1-C：源码依赖“越界读到全局 0 初值”），
 * 索引保持 Pascal 1 基语义：第 [0] 位废弃不用，[1] 为第一个有效元素。
 */
object AppState {

    /** 模式字（lx1.Button3/Button4 中的 bdcl）：bd=标定，cl=测量 */
    const val MODE_CALIBRATION = "bd"
    const val MODE_MEASURE = "cl"

    // ---- 程序编号相关（LX5.Timer1Timer / LX3.Button1 的 case prg_no）----
    var prgNo: Int = 1               // prg_no：ComboBox1
    var supplyTimes: Int = 1         // exprmt_times：ComboBox2
    var points: Int = 22             // 输出道数
    var pointsEnd: Int = 253         // points_end
    var pointsEnd1: Int = 253        // points_end1（=points_end）
    var pointNo: Double = 242.5      // x 轴采样刻度基数
    var imgW: Int = 672              // PaintBox1 宽（运行时由 SupplyScreen 刷新）
    var imgH: Int = 413              // PaintBox1 高

    // ---- 电压/电流 ----
    var vab: Double = 0.0            // 当前 VAB
    var dianchi: Double = 0.0        // 电池电压
    var sumIAve: Double = 0.0        // sum_i_ave：各次供电电流累计
    var averageI: Double = 0.0       // average_i：平均电流

    // ---- 数组（1 基，[0] 废弃）----
    val rulerH = IntArray(41)                    // ruler_h[1..40]
    val v250hz = DoubleArray(251)                // v250hz[1..250]
    val v2value = Array(33) { DoubleArray(3201) } // v2value[1..32][1..3200]
    val biaoding = DoubleArray(57)               // 标定系数[1..56]
    val zhengyan = DoubleArray(57)               // 正演值[1..56]
    val shjian = DoubleArray(57)                 // 道时间[1..56]
    val iabAve = DoubleArray(33)                 // iab_ave[1..32]
    val v2 = DoubleArray(3203)                   // v2[1..3202]（替位平均输出）
    val v2i = DoubleArray(57)                    // v2i[1..56]（疏密平均输出）
    val displ = DoubleArray(57)                  // displ[1..56]（显示用）

    // ---- 平滑滤波参数（LX5.FormCreate：filter_bgn 恒定；filter_no[1] 恒 1）----
    val filterBgn = doubleArrayOf(0.0, 100.0, 100.0, 10.0, 1.0, 0.1)
    val filterNo = intArrayOf(1, 1, 1, 1, 1, 1)  // [2..5] 运行时取自 lbcs2..5

    // ---- 供电测量会话状态 ----
    var gdcs: Int = 0                // 已供电次数（来自帧 msg[2]）
    var dots: Int = 1                // 当前处理道号
    val gridCells = arrayOfNulls<String>(32)     // StringGrid2 8列×4行 = 32 单元格
    var grid2Lie: Int = 0            // 表格写指针（列）
    var grid2Hang: Int = 0           // 表格写指针（行）

    // ---- 标志位（lx1/lx5 全局）----
    var keSjcl: Boolean = false      // 可数据处理
    var keCunpan: Boolean = false    // 可存盘
    var cppd: Boolean = false        // 已有处理结果未存盘提醒
    var agnexit: Boolean = true      // 已提醒过一次（只提醒一次）
    var yibiaoding: Boolean = false  // 已标定
    var dataCame: Boolean = false    // 看门狗：本周期已收到数据

    /** 当前模式：bd 标定 / cl 测量 */
    var mode: String = MODE_MEASURE

    // ---- 共享字符串 ----
    var wjljv: String = ""           // 数据目录（=form3.wjlj.text）
    var oldXmbs: String = ""         // 上次项目标识
    var oldXianhao: String = ""      // 上次线号
    var tmpvab: String = "30"        // gzcs 第 10 行 → Form6 初始目标 VAB
    var targetVab: String = "30"     // form6.edit2 当前目标 VAB（文本）

    /** 工作参数（gzcs.txt 19 项） */
    val work = WorkParams()

    /** 满档电流 mddl（表单量，不进 gzcs；2000/4000/8000） */
    var fullCurrent: Int = 2000

    /**
     * 按当前 prgNo 计算 points/points_end1/point_no 与 ruler_h。
     * 等价 LX5.Timer1Timer 与 LX3.Button1 的 case 段。
     * @param useRound true=LX5.Timer1Timer（round，绘图前）；false=LX3.Button1（trunc）
     */
    fun applyPrg(useRound: Boolean) {
        when (prgNo) {
            1 -> { points = 22; pointsEnd1 = 253; pointsEnd = 253; pointNo = 242.5 }
            2 -> { points = 28; pointsEnd1 = 406; pointsEnd = 406; pointNo = 392.5 }
            3 -> { points = 34; pointsEnd1 = 595; pointsEnd = 595; pointNo = 578.5 }
            4 -> { points = 40; pointsEnd1 = 820; pointsEnd = 820; pointNo = 800.5 }
            else -> { points = 22; pointsEnd1 = 253; pointsEnd = 253; pointNo = 242.5 }
        }
        Series.computeRulerH(points, pointNo, imgW, rulerH, useRound)
    }

    /** 计算道时间 shjian[1..points]（LX5.TabbedNotebook1Click else 分支 / LX3.Button2） */
    fun computeShjian() {
        Series.computeShjian(points, shjian)
    }

    /** 清除 d1..d32 单元格内容（等价 d1.caption:='' … 循环体） */
    fun clearGridCells() {
        for (i in 0 until 32) gridCells[i] = null
        grid2Lie = 0
        grid2Hang = 0
    }

    /**
     * 进入供电/标定前的会话复位（等价 Form1.Button3/Button4 与 ACK(cmd=3) 中的复位段）。
     */
    fun resetMeasureSession() {
        clearGridCells()
        gdcs = 0
        dots = 1
        keSjcl = false
        keCunpan = false
    }
}
