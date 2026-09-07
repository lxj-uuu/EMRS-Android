package com.qiangyuan.emrs.algo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 算法包纯函数单测（架构 T03 验收点）。
 * 样例均按源码公式手算验证。
 */
class AlgoTest {

    @Test
    fun sparseAve_totalPointsEqualsPointsEnd() {
        // points=3 → 用点 1+2+3=6；v2i[1]=v2[1]；v2i[2]=(v2[2]+v2[3])/2；v2i[3]=(v2[4]+v2[5]+v2[6])/3
        val v2 = DoubleArray(3203)
        v2[1] = 10.0; v2[2] = 20.0; v2[3] = 40.0; v2[4] = 30.0; v2[5] = 50.0; v2[6] = 70.0
        val v2i = DoubleArray(57)
        SparseAve.sparseAve(v2, 3, v2i)
        assertEquals(10.0, v2i[1], 1e-12)
        assertEquals(30.0, v2i[2], 1e-12)
        assertEquals(50.0, v2i[3], 1e-12)
    }

    @Test
    fun normalizeI_onlyWhenPositive() {
        val v2i = doubleArrayOf(0.0, 2.0, 4.0)
        Normalize.normalizeI(v2i, 2, 2.0)
        assertEquals(1.0, v2i[1], 1e-12)
        assertEquals(2.0, v2i[2], 1e-12)
        val v2i2 = doubleArrayOf(0.0, 2.0, 4.0)
        Normalize.normalizeI(v2i2, 2, 0.0)   // average_i=0 → 不除（源码 if average_i>0）
        assertEquals(2.0, v2i2[1], 1e-12)
    }

    @Test
    fun shjian_matchesSourceFormula() {
        // shjian[1]=0.08；i=2: k=2,3 → sum=5 → 5/2*0.08=0.2；i=3: k=4,5,6 → sum=15 → 15/3*0.08=0.4
        val shjian = DoubleArray(57)
        Series.computeShjian(3, shjian)
        assertEquals(0.08, shjian[1], 1e-12)
        assertEquals(0.2, shjian[2], 1e-12)
        assertEquals(0.4, shjian[3], 1e-12)
    }

    @Test
    fun rulerH_index1IsZero() {
        val ruler = IntArray(41)
        Series.computeRulerH(22, 242.5, 672, ruler, useRound = true)
        assertEquals(0, ruler[1])
        // i=2: sum=5 → (5/2-1)*672/242.5 = 1.5*672/242.5 ≈ 4.157 → round=4
        assertEquals(4, ruler[2])
    }

    @Test
    fun scale14_positiveAndNegative() {
        // h=0,l=0 → 0
        assertEquals(0.0, Decode.scale14(0, 0), 1e-9)
        // h=0x40（bit32=0 正、增益=64→/128），l=0 → x=0 → 0
        assertEquals(0.0, Decode.scale14(0x40, 0), 1e-9)
        // 负数：h=0x60（bit32=1），x=(0x60&63)*256=8192 → xy=-8192；增益 64→/128
        val v = Decode.scale14(0x60, 0)
        assertEquals(-8192.0 / 8191.0 * 1e7 / 128.0, v, 1e-6)
    }

    @Test
    fun currentScale_formula() {
        // ((h&3)*256+l)/1023*100 * mddl/100 * 5/4
        val v = Decode.currentScale(3, 255, 2000)
        assertEquals(1023.0 / 1023.0 * 100.0 * 20.0 * 1.25, v, 1e-9)
    }

    @Test
    fun iabAve_nonlinearCorrection() {
        // 4 路电流全 1023 满码，mddl=2000，bl=2：
        // temp=100*20*1.25=2500；sum=10000；modify=2500-0.000068*2500^2=2075；
        // iab=trunc(2075*2*100)/100=4150.00
        val payload = buildType2Payload()
        val shot = Decode.decodeDataFrame(payload, payload.size, 2000, 2.0, 0, 0, 0)
        assertEquals(4150.0, shot.iabAve, 1e-9)
        assertEquals(1, shot.gdcs)
    }

    @Test
    fun v2_subtractsV250hzWithWrap() {
        val payload = buildType2PayloadCustom(
            v250hz = { i -> if (i == 1) doubleArrayOf(0x40, 0x00) else doubleArrayOf(0, 0) },
            v2Bytes = { i -> if (i == 1) doubleArrayOf(0x40, 0x08) else doubleArrayOf(0, 0) }
        )
        val shot = Decode.decodeDataFrame(payload, payload.size, 2000, 1.0, 0, 0, 0)
        // v250hz[1]=0（h=0x40,l=0 → x=0）；v250hz[2]=0；j50hz 从 2 起 → v2[1]=x(0x40,0x08)/128
        // x=(0x40&63)*256+8=8 → v=8/8191*1e7/128 - 0
        assertEquals(8.0 / 8191.0 * 1e7 / 128.0, shot.v2[1], 1e-6)
    }

    @Test
    fun displayLadders_matchSource() {
        // 测量阶梯
        assertEquals(1234567.0, ProcessPipeline.roundMeasureLadder(1234567.8), 1e-9)
        assertEquals(123456.0, ProcessPipeline.roundMeasureLadder(123456.7), 1e-9)
        assertEquals(12345.6, ProcessPipeline.roundMeasureLadder(12345.67), 1e-9)
        assertEquals(1234.56, ProcessPipeline.roundMeasureLadder(1234.567), 1e-9)
        assertEquals(123.456, ProcessPipeline.roundMeasureLadder(123.4567), 1e-9)
        assertEquals(12.3456, ProcessPipeline.roundMeasureLadder(12.34567), 1e-9)
        assertEquals(1.234567, ProcessPipeline.roundMeasureLadder(1.2345678), 1e-9)
        // 标定阶梯
        assertEquals(12345.0, ProcessPipeline.roundCalibLadder(12345.6), 1e-9)
        assertEquals(1234.5, ProcessPipeline.roundCalibLadder(1234.56), 1e-9)
        assertEquals(123.45, ProcessPipeline.roundCalibLadder(123.456), 1e-9)
        assertEquals(12.345, ProcessPipeline.roundCalibLadder(12.3456), 1e-9)
        assertEquals(1.23456, ProcessPipeline.roundCalibLadder(1.234567), 1e-9)
    }

    @Test
    fun smooth5_3_zeroCurrentDoesNotHang() {
        // iab_ave=0 时源码死循环；防御后应正常返回（架构 §8.1-F）
        val v2value = Array(33) { DoubleArray(3201) }
        for (j in 1..300) v2value[1][j] = 150.0
        val iabAve = DoubleArray(33)
        val filterNo = intArrayOf(1, 1, 5, 5, 5, 5)
        val filterBgn = doubleArrayOf(0.0, 100.0, 100.0, 10.0, 1.0, 0.1)
        val v2 = DoubleArray(3203)
        Smoothing.smooth5_3(v2value, iabAve, 1, 253, filterNo, filterBgn, v2)
        // 无异常即通过
    }

    @Test
    fun replaceAve_singleShotCopies() {
        val v2value = Array(33) { DoubleArray(3201) }
        v2value[1][1] = 7.5
        v2value[1][2] = -3.25
        val v2 = DoubleArray(3203)
        ReplaceAve.replaceAve(v2value, 1, 2, v2)
        assertEquals(7.5, v2[1], 1e-12)
        assertEquals(-3.25, v2[2], 1e-12)
    }

    @Test
    fun replaceAve_outlierRemoved() {
        // 2 次供电：列值 [100, 900]（QA 第 1 轮 P1-1 修正，Python 独立实现交叉验证）
        // ave=500, dlt=400, 3σ=1200 → 都保留进 rplc；迭代（平局严格大于、i=1 先登记）：
        // 500→700→800→850→875→887.5（|887.5-700|dlt 收敛 ≤887.5×0.02 时退出）→ 收敛 887.5
        val v2value = Array(33) { DoubleArray(3201) }
        v2value[1][1] = 100.0
        v2value[2][1] = 900.0
        val v2 = DoubleArray(3203)
        ReplaceAve.replaceAve(v2value, 2, 1, v2)
        assertEquals(887.5, v2[1], 1e-9)
    }

    @Test
    fun replaceAve_tieBreakDependsOnInputOrder() {
        // 锁定源码怪癖：交换输入顺序 [900, 100] → 平局裁决先到先得，收敛 101.5625
        // （QA 第 1 轮用 Python 独立实现验证，两方向结果均与 LX5 L261-278 逐行一致）
        val v2value = Array(33) { DoubleArray(3201) }
        v2value[1][1] = 900.0
        v2value[2][1] = 100.0
        val v2 = DoubleArray(3203)
        ReplaceAve.replaceAve(v2value, 2, 1, v2)
        assertEquals(101.5625, v2[1], 1e-9)
    }

    /** 构造 type=2 测试帧 payload：250 点 v250hz 全 0 + 4 路电流满码 + 1200 点 V2 全 0 */
    private fun buildType2Payload(): ByteArray {
        return buildType2PayloadCustom({ doubleArrayOf(0, 0) }, { doubleArrayOf(0, 0) })
    }

    private fun buildType2PayloadCustom(
        v250hz: (Int) -> DoubleArray,
        v2Bytes: (Int) -> DoubleArray
    ): ByteArray {
        val out = ArrayList<Byte>(2912)
        out.add(2)      // msg[1] 类型
        out.add(1)      // msg[2] gdcs
        for (i in 1..250) {
            val b = v250hz(i)
            out.add(b[0].toByte()); out.add(b[1].toByte())
        }
        repeat(4) {
            out.add(0x03.toByte()); out.add(0xFF.toByte())   // 1023 满码
        }
        for (i in 1..1200) {
            val b = v2Bytes(i)
            out.add(b[0].toByte()); out.add(b[1].toByte())
        }
        // 校验字节：XOR(msg[1..len-2]) —— 源码要求 0 或 0x80；测试直接解帧不走校验层，
        // 追加 0 使整体长度对齐帧尾语义
        var chk = 0
        for (b in out) chk = chk xor (b.toInt() and 0xFF)
        out.add((chk xor 0x00).toByte())
        return out.toByteArray()
    }
}
