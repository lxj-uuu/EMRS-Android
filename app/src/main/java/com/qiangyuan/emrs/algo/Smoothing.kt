package com.qiangyuan.emrs.algo

/**
 * 五点三次平滑 + 分段滤波（架构 algo/Smoothing.kt，逐行映射 LX5.smooth5_3 L157-223）。
 *
 * @param v2value 1 基 [1..32][1..3200]（行=供电次数，列=采样点；越界位置保持 0——架构 §8.1-C）
 * @param iabAve iab_ave[1..32]
 * @param times exprmt_times（供电次数）
 * @param n points_end1
 * @param filterNo filter_no[1..5]（[1] 恒 1；[2..5] 取自 lbcs2..5）
 * @param filterBgn filter_bgn[1..5]=100,100,10,1,0.1
 * @param v2Scratch v2[1..3202] 工作数组（复用 AppState.v2 语义）
 *
 * 源码怪癖按原样复刻：
 * - 毛刺剔除只做一遍（i×j 双重循环），不在 k 段内重复；
 * - 滤波读 a[i][j±2] 当 j 接近 n 时越界读到全局 0 初值（数组预分配 3201 保证）；
 * - iab_ave[i]≤0 时源码除零死循环（架构 §8.1-F）→ 加防御日志并置 bgin_j=15，不改变正常结果。
 * 说明：算法包保持纯 Kotlin（可 JUnit），日志走可注入的 [logSink]，默认打印到 stdout。
 */
object Smoothing {

    /** 日志注入点（默认 stdout；避免依赖 android.util.Log 以便 JVM 单测） */
    @Volatile
    var logSink: ((String) -> Unit)? = { println("EmrsAlgo: $it") }

    private fun log(msg: String) {
        logSink?.invoke(msg)
    }

    fun smooth5_3(
        v2value: Array<DoubleArray>,
        iabAve: DoubleArray,
        times: Int,
        n: Int,
        filterNo: IntArray,
        filterBgn: DoubleArray,
        v2Scratch: DoubleArray
    ) {
        // ① 粗去毛刺（3 点中值式，L164-181）：j=2..n-1
        for (i in 1..times) {
            val row = v2value[i]
            for (j in 2..n - 1) {
                var aaa = 0.0
                var min = 9999999.0
                var max = -9999999.0
                aaa += row[j - 1]
                if (row[j - 1] <= min) min = row[j - 1]
                if (row[j - 1] >= max) max = row[j - 1]
                aaa += row[j]
                if (row[j] <= min) min = row[j]
                if (row[j] >= max) max = row[j]
                aaa += row[j + 1]
                if (row[j + 1] <= min) min = row[j + 1]
                if (row[j + 1] >= max) max = row[j + 1]
                aaa = aaa - min - max
                if (Math.abs(row[j] - aaa) > 0.6 * Math.abs(aaa)) {
                    row[j] = aaa
                }
            }
        }

        // ② 分段五点三次滤波（L195-222）
        for (i in 1..times) {
            val row = v2value[i]
            for (k in 1..5) {
                var bginJ = 15
                if (iabAve[i] <= 0.0) {
                    // 架构 §8.1-F：源码 v2value[i,j]/iab_ave[i]=0 死循环 → 防御处理（记日志，结果取 bgin_j=15）
                    log("smooth5_3: iab_ave[$i]<=0, skip threshold scan (row $i, seg $k)")
                } else {
                    var j = 15
                    // 越界读依赖 0 初值：j 上限受数组长度保护（3200 内为 0 即退出）
                    while (j < v2value[i].size - 1 && row[j] / iabAve[i] >= filterBgn[k]) {
                        j++
                    }
                    bginJ = j
                }
                if (n - bginJ >= 3 && filterNo[k] > 0) {
                    for (l in 1..filterNo[k]) {
                        for (j in bginJ..n - 1) {
                            // 源码越界读 a[i][n+1]/a[i][n+2] → 0（数组 0 初始化，架构 §8.1-C）
                            val jm2 = if (j - 2 >= 0) row[j - 2] else 0.0
                            val jp1 = if (j + 1 < row.size) row[j + 1] else 0.0
                            val jp2 = if (j + 2 < row.size) row[j + 2] else 0.0
                            v2Scratch[j] = (17.0 * row[j] + 12.0 * (row[j - 1] + jp1) -
                                    3.0 * (jm2 + jp2)) / 35.0
                        }
                        for (j in bginJ..n - 2) {
                            row[j] = v2Scratch[j]
                        }
                    }
                }
            }
        }
    }
}
