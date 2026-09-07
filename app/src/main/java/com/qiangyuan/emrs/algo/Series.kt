package com.qiangyuan.emrs.algo

/**
 * 道时间/道坐标表（架构 algo/Series.kt，映射 LX3.Button1Click / LX5.Timer1Timer / LX5.TabbedNotebook1Click）。
 */
object Series {

    /**
     * 道时间 shjian[1..points]（LX5 L668-684：k=1; shjian[1]=0.08;
     * for i=2..points: sum=Σ(k 递增) ；shjian[i]=sum/i*0.08）。
     */
    fun computeShjian(points: Int, shjian: DoubleArray) {
        var k = 1
        if (shjian.size > 1) shjian[1] = 1 * 0.08
        var sum = 0
        for (i in 2..points) {
            sum = 0
            for (j in 1..i) {
                k++
                sum += k
            }
            if (shjian.size > i) shjian[i] = sum.toDouble() / i * 0.08
        }
    }

    /**
     * 道 x 像素坐标 ruler_h[1..points]。
     * @param useRound true=LX5.Timer1Timer（round，L555）；false=LX3.Button1（trunc，L129）。
     * ruler_h[1] 恒为 0。
     */
    fun computeRulerH(points: Int, pointNo: Double, imgW: Int, rulerH: IntArray, useRound: Boolean) {
        if (rulerH.isEmpty()) return
        rulerH[1] = 0   // trunc(0*img_w/point_no)
        var k = 1
        var sum = 0
        for (i in 2..points) {
            sum = 0
            for (j in 1..i) {
                k++
                sum += k
            }
            if (rulerH.size > i) {
                val v = (sum.toDouble() / i - 1) * imgW / pointNo
                rulerH[i] = if (useRound) Math.round(v).toInt() else Formatting.trunc(v).toInt()
            }
        }
    }

    /** 程序编号 → 道数参数（架构 algo/Series.kt：ProgramSpec） */
    data class ProgramSpec(val points: Int, val pointsEnd1: Int, val pointNo: Double, val clsjv: Int, val endTimeUs: Int)

    fun specOf(prg: Int): ProgramSpec = when (prg) {
        1 -> ProgramSpec(22, 253, 242.5, 30, 19400)
        2 -> ProgramSpec(28, 406, 392.5, 61, 31400)
        3 -> ProgramSpec(34, 595, 578.5, 93, 46280)
        else -> ProgramSpec(40, 820, 800.5, 126, 64040)
    }
}
