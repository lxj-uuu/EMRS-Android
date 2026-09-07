package com.qiangyuan.emrs.algo

/**
 * 疏密平均/成道（架构 algo/SparseAve.kt，逐行映射 LX5.sparse_ave L326-343）。
 *
 * k=1; v2i[1]=v2[1]；for i=2..points: 累加 v2[k+1..k+i] 共 i 个求平均 = v2i[i]；
 * 总用点数 = 1+2+…+points = points_end。
 */
object SparseAve {

    fun sparseAve(v2: DoubleArray, points: Int, v2iOut: DoubleArray) {
        var k = 1
        if (v2iOut.size > 1) v2iOut[1] = v2[1]
        for (i in 2..points) {
            var sum = 0.0
            for (j in 1..i) {
                k++
                sum += v2[k]
            }
            if (v2iOut.size > i) v2iOut[i] = sum / i
        }
    }
}
