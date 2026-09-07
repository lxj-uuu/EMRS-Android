package com.qiangyuan.emrs.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 组帧状态机单测（T02 验收点：拆包多块的 A0A0…AFAF 正确组帧与校验）。
 */
class RxFrameParserTest {

    private class CollectingSink : RxFrameParser.Sink {
        val valid = ArrayList<Triple<Int, ByteArray, Int>>()
        val invalid = ArrayList<Int>()
        override fun onValidFrame(type: Int, msg: ByteArray, payloadLen: Int) {
            valid.add(Triple(type, msg.copyOf(), payloadLen))
        }
        override fun onInvalidFrame(type: Int) {
            invalid.add(type)
        }
    }

    private val settings = ProtocolSettings()

    private fun frame(vararg body: Int): ByteArray {
        // body = 完整帧字节（含帧头帧尾）
        val b = ByteArray(body.size)
        for (i in body.indices) b[i] = body[i].toByte()
        return b
    }

    @Test
    fun singleFrame_complete() {
        val sink = CollectingSink()
        val parser = RxFrameParser(sink)
        // 自检帧：A0 A0 01 01 AF AF → 校验 01^01=0 → 合法，type=1，payloadLen=2
        parser.feed(frame(0xA0, 0xA0, 0x01, 0x01, 0xAF, 0xAF), settings)
        assertEquals(1, sink.valid.size)
        assertEquals(1, sink.valid[0].first)
        assertEquals(2, sink.valid[0].third)
    }

    @Test
    fun splitAcrossFeeds_afterHeaderPair_accumulates() {
        val sink = CollectingSink()
        val parser = RxFrameParser(sink)
        // P0-1 修复后语义（LX1 L407-415）：帧头对 A0A0 必须完整落在同一块内才被识别，
        // 帧体跨块正常累积。本帧校验 01^01^02=0x02 → 非法帧 type=1。
        // （原用例 splitAcrossFeeds_accumulates 把首块只给单个 A0 并期望跨块记忆，
        //   与源码“块外不记忆单字节”相悖，系第 1 轮旧实现语义的过时断言，QA 第 2 轮修正）
        parser.feed(byteArrayOf(0xA0.toByte(), 0xA0.toByte()), settings)
        parser.feed(byteArrayOf(0x01.toByte(), 0x01.toByte(), 0x02.toByte()), settings)
        parser.feed(byteArrayOf(0xAF.toByte(), 0xAF.toByte()), settings)
        assertEquals(0, sink.valid.size)
        assertEquals(1, sink.invalid.size)
        assertEquals(1, sink.invalid[0])   // msg[1]=01 → type=1，不发 NAK 由引擎决定
    }

    @Test
    fun loneA0Chunk_dropped_frameLost() {
        val sink = CollectingSink()
        val parser = RxFrameParser(sink)
        // 源码不跨块记忆单字节 A0（L416 门控）：帧头对被拆散 → 帧整体丢失。
        // 原版同此行为，丢失帧由 Timer4 看门狗（1000/6000ms）触发重发兜底。
        parser.feed(byteArrayOf(0xA0.toByte()), settings)
        parser.feed(byteArrayOf(0xA0.toByte(), 0x01.toByte(), 0x01.toByte(), 0xAF.toByte(), 0xAF.toByte()), settings)
        assertEquals(0, sink.valid.size)
        assertEquals(0, sink.invalid.size)
    }

    @Test
    fun checksum80_valid() {
        val sink = CollectingSink()
        val parser = RxFrameParser(sink)
        // payload=04 00 04 84 → XOR=04^00^04^84=0x80 → 合法
        parser.feed(frame(0xA0, 0xA0, 0x04, 0x00, 0x04, 0x84, 0xAF, 0xAF), settings)
        assertEquals(1, sink.valid.size)
        assertEquals(4, sink.valid[0].first)
        assertEquals(4, sink.valid[0].third)
    }

    @Test
    fun newHeaderResetsAccumulation() {
        val sink = CollectingSink()
        val parser = RxFrameParser(sink)
        // 前半帧垃圾 + 新帧头：应只解析最后一个头之后的内容
        parser.feed(frame(0xA0, 0xA0, 0x07, 0x07, 0xA0, 0xA0, 0x01, 0x01, 0xAF, 0xAF), settings)
        assertEquals(1, sink.valid.size)
        assertEquals(1, sink.valid[0].first)
    }

    @Test
    fun crossBlockFeed_headerInsideDataResetsAccumulation() {
        val sink = CollectingSink()
        val parser = RxFrameParser(sink)
        // P0-1 回归：跨块续帧且第 2 块数据中混入 A0A0 ——
        // 源码 LX1 L407-415 语义：第 2 块整块扫描命中"最后一个 A0A0"→ 丢弃第 1 块累积的前缀，
        // 解出 type=5 合法帧（校验 05^05=0），而非把前缀与第 2 块拼成假帧
        parser.feed(frame(0xA0, 0xA0, 0x02, 0x02), settings)
        parser.feed(frame(0x03, 0x03, 0xA0, 0xA0, 0x05, 0x05, 0xAF, 0xAF), settings)
        assertEquals(1, sink.valid.size)
        assertEquals(5, sink.valid[0].first)
        assertEquals(2, sink.valid[0].third)
        assertEquals(0, sink.invalid.size)
    }

    @Test
    fun strictMode_dropsBytesAfterFrame() {
        val sink = CollectingSink()
        val parser = RxFrameParser(sink)
        val strict = ProtocolSettings(strictOneFramePerChunk = true)
        // 一块内两帧：严格模式只处理第一帧
        val two = frame(0xA0, 0xA0, 0x01, 0x01, 0xAF, 0xAF,
            0xA0, 0xA0, 0x01, 0x01, 0xAF, 0xAF)
        parser.feed(two, strict)
        assertEquals(1, sink.valid.size)
    }

    @Test
    fun robustMode_blockScanTakesLastHeader() {
        val sink = CollectingSink()
        val parser = RxFrameParser(sink)
        // P0-1 修复后：整块扫描取"最后一个 A0A0"→ 同块两帧只解析后一帧（与源码一致）；
        // strict 开关仅影响帧尾后同块残余字节的处理，此处不影响结果
        val two = frame(0xA0, 0xA0, 0x01, 0x01, 0xAF, 0xAF,
            0xA0, 0xA0, 0x01, 0x01, 0xAF, 0xAF)
        parser.feed(two, settings)   // 默认稳健模式
        assertEquals(1, sink.valid.size)
        assertEquals(1, sink.valid[0].first)
    }

    @Test
    fun supplyFrame_bytes() {
        // A0 A0 03 03 04 04 AF AF（N=4，校验=3^3^4=4）
        val f = FrameCodec.supplyFrame(4)
        assertEquals(8, f.size)
        val expected = intArrayOf(0xA0, 0xA0, 0x03, 0x03, 0x04, 0x04, 0xAF, 0xAF)
        for (i in expected.indices) {
            assertEquals(expected[i], f[i].toInt() and 0xFF)
        }
    }

    @Test
    fun chargeFrame_encoding() {
        // 目标 30V：sheding=trunc(30/50*1023)=613；H=(613/256)&3=2；L=613%256=101；校验=4^2^101=103
        val f = FrameCodec.chargeFrame(30.0)
        assertEquals(0x02, f[3].toInt() and 0xFF)
        assertEquals(101, f[4].toInt() and 0xFF)
        assertEquals(0x04 xor 0x02 xor 101, f[5].toInt() and 0xFF)
    }

    @Test
    fun chargeEnterFrame_bytes() {
        // A0 A0 04 00 00 04 AF AF（H=0,L=0，校验=4^0^0=04；整体 XOR=0）
        val f = FrameCodec.chargeEnterFrame()
        val expected = intArrayOf(0xA0, 0xA0, 0x04, 0x00, 0x00, 0x04, 0xAF, 0xAF)
        for (i in expected.indices) {
            assertEquals(expected[i], f[i].toInt() and 0xFF)
        }
    }
}
