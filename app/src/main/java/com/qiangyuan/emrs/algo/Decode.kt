package com.qiangyuan.emrs.algo

/**
 * msg[1]=2 数据帧逐字节解析（架构 algo/Decode.kt，映射 LX1.Comm1RxChar L481-605，已逐行核对）。
 *
 * 输入 payload = 帧 msg[1..msg_ii-2]（1 基缓冲：下标 0 废弃，msg[1]=类型字节 2，msg[2]=gdcs）。
 * 布局：msg[3..] 依次 = 250×2B(v250hz) + 4×2B(电流) + 1200×2B(V2)。
 */
object Decode {

    private const val V250_COUNT = 250
    private const val I_COUNT = 4
    private const val V2_COUNT = 1200

    /** 1 基取字节（b(i) = payload[i-1]） */
    private fun b(payload: ByteArray, i: Int): Int = payload[i - 1].toInt() and 0xFF

    /**
     * 14 位补码 + 增益归一（LX1 L488-496 / L579-587）：
     *   x=(h&63)*256+l；h&32≠0 → xy=-(((x|0xC000)^0xFFFF)+1)；v=xy/8191*1e7；
     *   增益 h&192：0→×1，192→/16384，其余→/128。
     */
    fun scale14(h: Int, l: Int): Double {
        val x = (h and 63) * 256 + l
        var xy = x
        if (h and 32 != 0) {
            xy = -(((x or 0xC000) xor 0xFFFF) + 1)
        }
        var v = xy / 8191.0 * 10000000.0
        val sgn = h and 192
        v = when (sgn) {
            0 -> v * 1.0
            192 -> v / 16384.0
            else -> v / 128.0
        }
        return v
    }

    /**
     * 电流换算（LX1 L519-525）：
     *   temp=((h&3)*256+l)/1023*100 * mddl/100 * 5/4。
     * mddl∈{2000,4000,8000}（满档电流）；5/4 系数为源码字面（架构问 8，待硬件确认）。
     */
    fun currentScale(h: Int, l: Int, mddl: Int): Double {
        val raw = ((h and 3) * 256 + l) / 1023.0 * 100.0
        return raw * mddl / 100.0 * 5.0 / 4.0
    }

    /** Pascal trunc：向零取整 */
    fun pTrunc(d: Double): Long {
        return if (d >= 0) Math.floor(d).toLong() else Math.ceil(d).toLong()
    }

    /**
     * 解析完整数据帧。
     * @param payload 1 基帧体（msg[1..payloadLen]，含类型/校验字节）
     * @param mddl 满档电流（AppState.work.fullCurrent）
     * @param ratio 倍率 bl
     * @param prevGdcs 上一次 gdcs（AppState.gdcs，用于单元格文本“数据有丢失”判断由引擎做）
     * @return ShotData
     */
    fun decodeDataFrame(
        payload: ByteArray,
        payloadLen: Int,
        mddl: Int,
        ratio: Double,
        prevGdcs: Int,
        cellCol: Int,
        cellRow: Int
    ): ShotData {
        val gdcs = b(payload, 2)

        // ---- 1) v250hz：前 250 对字节（去 50Hz 参考）----
        val v250hz = DoubleArray(V250_COUNT + 1)   // 1 基
        var mi = 3
        for (iii in 1..V250_COUNT) {
            val h = b(payload, mi)
            val l = b(payload, mi + 1)
            v250hz[iii] = scale14(h, l)
            mi += 2
        }

        // ---- 2) 电流 I：4 对字节求和平均 → 非线性校正 → 乘倍率截两位 ----
        var sumI = 0.0
        for (iii in 1..I_COUNT) {
            val h = b(payload, mi)
            val l = b(payload, mi + 1)
            sumI += currentScale(h, l, mddl)
            mi += 2
        }
        var modifyI = sumI / I_COUNT
        modifyI = modifyI - 0.000068 * modifyI * modifyI          // 非线性校正（源码字面）
        val iabAve = pTrunc(modifyI * ratio * 100).toDouble() / 100.0

        // ---- 3) V2：后 1200 对字节，逐点减 v250hz[j50hz]（j50hz 从 2 起，>250 回绕 1）----
        val v2 = DoubleArray(V2_COUNT + 1)                        // 1 基
        var j50hz = 2
        for (iii in 1..V2_COUNT) {
            val h = b(payload, mi)
            val l = b(payload, mi + 1)
            val tempV2 = scale14(h, l)
            v2[iii] = tempV2 - v250hz[j50hz]
            j50hz += 1
            if (j50hz > V250_COUNT) j50hz = 1
            mi += 2
        }

        val cellText = Formatting.floatToStr(iabAve)   // Delphi floattostr(iab_ave[gdcs])
        return ShotData(gdcs, iabAve, v250hz, v2, cellText, cellCol, cellRow)
    }
}
