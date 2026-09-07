package com.qiangyuan.emrs.protocol

/**
 * 上行组帧状态机（架构 protocol/RxFrameParser.kt / §4.3，映射 LX1.Comm1RxChar L407-631）。
 *
 * 忠实复刻点（LX1 L407-415 / L416-630）：
 * - 帧头 A0A0、帧尾 AFAF，帧体跨 feed() 调用累积于 msg[1..]（1 基，msg[0] 废弃）；
 * - **每次收到数据块先整块扫描"最后一个 A0A0"**（无条件执行，即使在累积态）：
 *   命中即 msg_ii:=1 丢弃既有累积内容、have_head:=true、从该对之后（ij+2）开始累积。
 *   注意：源码只在"本块"内找 A0A0——跨块相邻的两个 A0（上块尾/下块头）不构成帧头，按原版丢弃；
 * - have_head 为假时整块丢弃（源码不跨块记忆单字节 A0）；
 * - 累积态中相邻两字节均为 A0 也重启累积（QA P0-1 修法①；因整块扫描先剔除块内全部 A0A0 对，
 *   该分支在正常路径不可达，仅作防御保留，与源码行为一致）；
 * - 写入的字节对 msg[len-1]==AF && msg[len]==AF 判帧尾（L420）；
 * - 校验 = XOR(msg[1..len-2])，合法 ∈ {0x00, 0x80}（L423-426）；
 * - payload = msg[1..len-2]（含类型字节 msg[1] 与校验字节）；
 * - strictOneFramePerChunk=true 时复刻源码 L627 exit 语义（一帧后丢弃同批剩余字节），默认 false。
 *   注：引入整块扫描后，非严格模式下帧尾后剩余字节因整块内已无 A0A0 对且 have_head=false，
 *   同样会被丢弃——两模式对源码语义等价，开关仅为显式表达该行为。
 */
class RxFrameParser(private val sink: Sink) {

    interface Sink {
        /** 校验合法的完整帧。type=msg[1]；payload/msg 为 1 基缓冲（下标 1..payloadLen），len=payloadLen */
        fun onValidFrame(type: Int, msg: ByteArray, payloadLen: Int)

        /** 校验失败的帧（type 可能不可信，取 msg[1]） */
        fun onInvalidFrame(type: Int)
    }

    companion object {
        /** 源码 msg:array[0..5000] */
        const val MSG_SIZE = 5001
        const val HEAD1 = 0xA0
        const val HEAD2 = 0xA0
        const val TAIL = 0xAF
    }

    private val msg = ByteArray(MSG_SIZE)
    private var msgLen = 0          // 已写入的字节数（=Pascal msg_ii）
    private var haveHead = false    // =Pascal have_head

    fun reset() {
        msgLen = 0
        haveHead = false
    }

    /** 投递一块收到的字节（任意线程，但必须与其它 feed 串行） */
    fun feed(chunk: ByteArray, settings: ProtocolSettings) {
        // —— 整块扫描（LX1 L407-415）：无条件寻找本块内"最后一个 A0A0"对，
        //    命中即丢弃既有累积（msg_ii:=1）并从该对之后开始累积。循环不提前退出，最后一对生效。
        var start = 0
        for (i in 0 until chunk.size - 1) {
            if ((chunk[i].toInt() and 0xFF) == HEAD1 && (chunk[i + 1].toInt() and 0xFF) == HEAD2) {
                haveHead = true
                msgLen = 0
                start = i + 2
            }
        }
        // have_head 为假时整块丢弃（源码 L416 的 if have_head and (buf_ii<=count) 门控）
        if (!haveHead) return
        // —— 逐字节累积（LX1 L416-630）
        for (i in start until chunk.size) {
            val b = chunk[i].toInt() and 0xFF
            if (msgLen >= MSG_SIZE - 1) {
                // 缓冲溢出保护（源码无此检查；超长视为坏帧丢弃重同步）
                reset()
                return
            }
            // 累积态中相邻两字节均为 A0 → 重启累积（QA P0-1 修法①；整块扫描已保证
            // 本块剩余部分无 A0A0 对，此分支正常路径不可达，防御保留）
            if (b == HEAD1 && msgLen >= 1 && (msg[msgLen].toInt() and 0xFF) == HEAD1) {
                msgLen = 0
                continue
            }
            msgLen++
            msg[msgLen] = chunk[i]
            if (msgLen >= 2 &&
                (msg[msgLen - 1].toInt() and 0xFF) == TAIL &&
                (msg[msgLen].toInt() and 0xFF) == TAIL
            ) {
                haveHead = false
                emitFrame(msgLen)
                msgLen = 0
                if (settings.strictOneFramePerChunk) return   // 源码 L627 exit
            }
        }
    }

    private fun emitFrame(len: Int) {
        if (len < 2) {
            sink.onInvalidFrame(-1)
            return
        }
        var check = 0
        for (j in 1..len - 2) {
            check = check xor (msg[j].toInt() and 0xFF)
        }
        val type = msg[1].toInt() and 0xFF
        if (check == 0x00 || check == 0x80) {
            sink.onValidFrame(type, msg, len - 2)   // payload=msg[1..len-2]
        } else {
            sink.onInvalidFrame(type)
        }
    }
}
