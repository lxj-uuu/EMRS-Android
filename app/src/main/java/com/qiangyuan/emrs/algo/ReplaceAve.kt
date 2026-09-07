package com.qiangyuan.emrs.algo


/**
 * 替位平均（架构 algo/ReplaceAve.kt，逐行映射 LX5.replace_ave L225-324）。
 *
 * @param v2value 1 基 [1..32][1..3200]
 * @param times exprmt_times
 * @param n points_end1
 * @param v2Out v2[1..3202] 输出（等价源码全局 v2）
 *
 * 源码语义：
 * - times==1：直接复制 v2[j]=v2value[1,j]；
 * - 否则逐列：均值 ave、标准差 dlt=sqrt(Σ(x-ave)²/(times-1))，保留 |x-ave|≤3σ 至 rplc[]；
 *   k<2 → v2[j]=ave（用原始均值）；
 *   迭代（loop_rplc）：找与 ave 偏差最大者，若 dlt>|ave|*0.02 → 替为 ave 重算，直到收敛。
 */
object ReplaceAve {

    /** 日志注入点（默认 stdout；避免依赖 android.util.Log 以便 JVM 单测） */
    @Volatile
    var logSink: ((String) -> Unit)? = { println("EmrsAlgo: $it") }

    private fun log(msg: String) {
        logSink?.invoke(msg)
    }

    fun replaceAve(v2value: Array<DoubleArray>, times: Int, n: Int, v2Out: DoubleArray) {
        if (times == 1) {
            for (j in 1..n) {
                v2Out[j] = v2value[1][j]
            }
            return
        }
        val rplc = DoubleArray(33)   // 源码 rplc:array[0..32]
        for (j in 1..n) {
            var sum = 0.0
            for (i in 1..times) {
                sum += v2value[i][j]
            }
            var ave = sum / times
            sum = 0.0
            for (i in 1..times) {
                val d = v2value[i][j] - ave
                sum += d * d
            }
            var dlt = Math.sqrt(sum / (times - 1))
            var k = 0
            for (i in 1..times) {
                if (Math.abs(v2value[i][j] - ave) <= 3 * dlt) {
                    k++
                    rplc[k] = v2value[i][j]
                }
            }
            if (k < 2) {
                v2Out[j] = ave
                continue
            }
            // loop_rplc 迭代（防御性迭代上限 10000，正常远小于此；不改变收敛结果）
            var iter = 0
            while (true) {
                sum = 0.0
                for (i in 1..k) {
                    sum += rplc[i]
                }
                ave = sum / k
                dlt = 0.0
                var iBad = 1
                for (i in 1..k) {
                    val d = Math.abs(rplc[i] - ave)
                    if (d > dlt) {
                        dlt = d
                        iBad = i
                    }
                }
                if (dlt > Math.abs(ave) * 0.02) {
                    rplc[iBad] = ave
                    iter++
                    if (iter > 10000) {
                        log("replace_ave: iteration cap reached at col $j")
                        break
                    }
                    continue
                }
                break
            }
            v2Out[j] = ave
        }
    }
}
