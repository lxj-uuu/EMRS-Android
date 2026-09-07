package com.qiangyuan.emrs.algo

import com.qiangyuan.emrs.core.AppState

/**
 * 数据处理编排器（架构 algo/ProcessPipeline.kt / §5.5）。
 *
 * runMeasure  = LX5.Timer3Timer（测量处理，L1083-1174）
 * runCalibration = LX5.Timer2Timer（标定处理，L986-1081）
 *
 * 两者显示规整阶梯不同（架构 §0/§5.4 已核对），勿共用：
 * 测量： ≥1e6 trunc；≥1e5 trunc(×1)/1；≥1e4 ×10/10；≥1e3 ×100/100；≥100 ×1000/1000；≥10 ×1e4/1e4；否则 ×1e6/1e6
 * 标定： ≥1e4 trunc；≥1e3 ×10/10；≥100 ×100/100；≥10 ×1000/1000；否则 ×1e5/1e5
 */
object ProcessPipeline {

    /** 共用算法链（Timer2/Timer3 均为 smooth5_3 → replace_ave → sparse_ave → normal_i） */
    private fun runChain() {
        val st = AppState
        Smoothing.smooth5_3(st.v2value, st.iabAve, st.supplyTimes, st.pointsEnd1, st.filterNo, st.filterBgn, st.v2)
        ReplaceAve.replaceAve(st.v2value, st.supplyTimes, st.pointsEnd1, st.v2)
        SparseAve.sparseAve(st.v2, st.points, st.v2i)
        Normalize.normalizeI(st.v2i, st.points, st.averageI)
    }

    /** 测量模式处理（Timer3Timer）。前置：AppState.averageI 已由屏层按 Button2Click 算好 */
    fun runMeasure(): ProcessOutput {
        val st = AppState
        runChain()

        val memo = ArrayList<String>(st.points)
        val curveValues = DoubleArray(st.points + 1)
        val curvePositive = BooleanArray(st.points + 1)

        for (dot in 1..st.points) {
            val v = st.v2i[dot]
            st.displ[dot] = Math.abs(v)
            val sgn = when {
                v == 0.0 -> 0
                v > 0 -> 1
                else -> -1
            }
            st.displ[dot] = roundMeasureLadder(st.displ[dot])
            st.displ[dot] = sgn * st.displ[dot]

            memo.add(Formatting.floatToStr(dot.toDouble()) + ", " + Formatting.floatToStr(st.displ[dot]))

            // 曲线：正=红 clRed，负=蓝 clBlue；绝对值钳位 1e6
            val pos = v >= 0
            curvePositive[dot] = pos
            var v2idot = Math.abs(v)
            if (v2idot >= 1000000.0) v2idot = 1000000.0
            curveValues[dot] = v2idot
        }

        // 完成态（源码 L1167-1173）
        st.keSjcl = false
        st.cppd = true
        st.keCunpan = true
        st.agnexit = false

        return ProcessOutput(
            displ = st.displ.copyOf(),
            memoLines = memo,
            curveValues = curveValues,
            curvePositive = curvePositive
        )
    }

    /** 标定模式处理（Timer2Timer）。前置：zhengyan[] 已读入且已规整 */
    fun runCalibration(): ProcessOutput {
        val st = AppState
        runChain()

        val memo = ArrayList<String>(st.points)
        val curveValues = DoubleArray(st.points + 1)
        val curvePositive = BooleanArray(st.points + 1)

        for (dot in 1..st.points) {
            st.biaoding[dot] = st.zhengyan[dot] / st.v2i[dot]
            val v = st.biaoding[dot]
            st.displ[dot] = Math.abs(v)
            val sgn = if (v >= 0) 1 else -1     // 源码：=0 时 sgn 未赋值沿用旧值；此处按 +1
            st.displ[dot] = roundCalibLadder(st.displ[dot])
            st.displ[dot] = sgn * st.displ[dot]

            memo.add(Formatting.floatToStr(dot.toDouble()) + ", " + Formatting.floatToStr(st.displ[dot]))

            val pos = v >= 0
            curvePositive[dot] = pos
            var v2idot = Math.abs(v)
            if (v2idot >= 1000000.0) v2idot = 1000000.0
            curveValues[dot] = v2idot
        }

        // 完成态（源码 L1070-1080；bdxs.txt 写盘由屏层调 CalibrationStore 完成）
        st.keSjcl = false
        st.keCunpan = false
        st.yibiaoding = true

        return ProcessOutput(
            displ = st.displ.copyOf(),
            memoLines = memo,
            curveValues = curveValues,
            curvePositive = curvePositive
        )
    }

    /** 测量显示规整阶梯（LX5 L1103-1115） */
    fun roundMeasureLadder(d: Double): Double {
        val t = Formatting.trunc(d).toDouble()
        return when {
            d >= 1000000.0 -> t
            d >= 100000.0 -> Formatting.trunc(d * 1).toDouble() / 1.0
            d >= 10000.0 -> Formatting.trunc(d * 10).toDouble() / 10.0
            d >= 1000.0 -> Formatting.trunc(d * 100).toDouble() / 100.0
            d >= 100.0 -> Formatting.trunc(d * 1000).toDouble() / 1000.0
            d >= 10.0 -> Formatting.trunc(d * 10000).toDouble() / 10000.0
            else -> Formatting.trunc(d * 1000000).toDouble() / 1000000.0
        }
    }

    /** 标定显示规整阶梯（LX5 L1010-1018） */
    fun roundCalibLadder(d: Double): Double {
        val t = Formatting.trunc(d).toDouble()
        return when {
            d >= 10000.0 -> t
            d >= 1000.0 -> Formatting.trunc(d * 10).toDouble() / 10.0
            d >= 100.0 -> Formatting.trunc(d * 100).toDouble() / 100.0
            d >= 10.0 -> Formatting.trunc(d * 1000).toDouble() / 1000.0
            else -> Formatting.trunc(d * 100000).toDouble() / 100000.0
        }
    }

    /** 正演值规整（LX5 L448-461，Button2Click 标定段读入后） */
    fun roundForwardLadder(zhengyan: DoubleArray, points: Int) {
        for (i in 1..points) {
            val v = zhengyan[i]
            zhengyan[i] = when {
                v >= 10000.0 -> Formatting.trunc(v).toDouble()
                v >= 1000.0 -> Formatting.trunc(v * 10).toDouble() / 10.0
                v >= 100.0 -> Formatting.trunc(v * 100).toDouble() / 100.0
                v >= 10.0 -> Formatting.trunc(v * 1000).toDouble() / 1000.0
                else -> Formatting.trunc(v * 100000).toDouble() / 100000.0
            }
        }
    }

    /** 曲线 y 像素（LX5 L1066/1163）：y=img_h-img_h*log10(v*10000)/9（1e-4..1e5 量程） */
    fun logY(v: Double, imgH: Int): Int {
        val d = Math.log10(v * 10000.0) / 9.0
        return (imgH - imgH * d).toInt()   // 源码 trunc
    }
}
