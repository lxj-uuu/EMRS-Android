package com.qiangyuan.emrs.algo

/**
 * V2/I 归一（架构 algo/Normalize.kt，逐行映射 LX5.normal_i L345-359）。
 * average_i>0 时全部除以 average_i（v2i → uV/A）。
 */
object Normalize {

    fun normalizeI(v2i: DoubleArray, points: Int, averageI: Double) {
        if (averageI <= 0) return   // 源码 if average_i>0 才除
        for (i in 1..points) {
            if (v2i.size > i) v2i[i] = v2i[i] / averageI
        }
    }
}
