package com.qiangyuan.emrs.protocol

/**
 * 命令帧构造（架构 protocol/FrameCodec.kt / 规格书 §4.2，逐字节对齐源码 send[] 组帧）。
 *
 * 帧格式：帧头 A0 A0，随后命令字节 send[3]=命令号；命令 01/02 以 AF AF 直接结束（6B）；
 * 命令 03/04 带参数+异或校验（8B）；校验=相关命令字节异或。
 */
object FrameCodec {

    const val HEAD1: Byte = 0xA0.toByte()
    const val HEAD2: Byte = 0xA0.toByte()
    const val TAIL: Byte = 0xAF.toByte()
    const val ACK_BYTE: Byte = 0xA5.toByte()
    const val NAK_BYTE: Byte = 0xAA.toByte()

    /** 命令枚举（b=命令字节；resendable 对应源码 Timer3Timer 中 case 1/2/3 才重发） */
    enum class EmrsCommand(val b: Int, val len: Int, val resendable: Boolean) {
        SELF_TEST(1, 6, true),      // A0 A0 01 01 AF AF
        SELF_TEST_STOP(2, 6, true), // A0 A0 02 02 AF AF（Form2 返回时连发 2 次，由调用方控制）
        SUPPLY(3, 8, true),         // A0 A0 03 03 N N AF AF
        CHARGE_ENTER(4, 8, false),  // A0 A0 04 00 04 AF AF（主界面充电，目标 0）
        CHARGE(4, 8, false)         // A0 A0 04 H L (04^H^L) AF AF（Form6 按目标电压充电）
    }

    /** C1：自检 6B */
    fun selfTestFrame(): ByteArray =
        byteArrayOf(HEAD1, HEAD2, 0x01, 0x01, TAIL, TAIL)

    /** C2：自检返回 6B */
    fun selfTestStopFrame(): ByteArray =
        byteArrayOf(HEAD1, HEAD2, 0x02, 0x02, TAIL, TAIL)

    /**
     * C3：供电开始 8B：A0 A0 03 03 N N AF AF。
     * send[4]=3 恒为仪器内程序 3（LX5 L711 注释：89C592's prg_no=3；架构问 9，不随 ComboBox1 变化）。
     * 校验 = 3 ^ 3 ^ N = N。
     */
    fun supplyFrame(times: Int, supplyPrgByte: Int = 3): ByteArray {
        val p = supplyPrgByte and 0xFF
        val n = times and 0xFF
        return byteArrayOf(
            HEAD1, HEAD2, 0x03, p.toByte(), n.toByte(),
            (0x03 xor p xor n).toByte(), TAIL, TAIL
        )
    }

    /** C4a：主界面充电（目标 0V），8B：A0 A0 04 00 00 04 AF AF（LX1.Button8Click，H=0,L=0，校验=4^0^0=4） */
    fun chargeEnterFrame(): ByteArray =
        byteArrayOf(HEAD1, HEAD2, 0x04, 0x00, 0x00, 0x04, TAIL, TAIL)

    /**
     * C4b：按目标电压充电 8B（LX6.Button1Click）。
     * sheding = trunc(vab/50*1023)；H=(sheding div 256) and 3；L=sheding mod 256；校验=4^H^L。
     * ⚠ 架构 §8.1-A：>50V 时编码按 /50 回绕，按原样复刻不“改正”。
     */
    fun chargeFrame(targetVab: Double): ByteArray {
        val d = targetVab / 50.0 * 1023.0
        val sheding = if (d >= 0) Math.floor(d).toInt() else Math.ceil(d).toInt()
        val h = (sheding / 256) and 3
        val l = sheding % 256
        val chk = 0x04 xor h xor l
        return byteArrayOf(HEAD1, HEAD2, 0x04, h.toByte(), l.toByte(), chk.toByte(), TAIL, TAIL)
    }

    /** ACK：$A5 重复 repeat 次（源码 16 次 1 字节 write；整包/逐字节由 ProtocolSettings 决定） */
    fun ackFrame(repeat: Int): ByteArray {
        val n = if (repeat < 1) 16 else repeat
        val b = ByteArray(n)
        for (i in 0 until n) b[i] = ACK_BYTE
        return b
    }

    /** NAK：$AA×1 */
    fun nakFrame(): ByteArray = byteArrayOf(NAK_BYTE)
}
