package com.qiangyuan.emrs.protocol

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.qiangyuan.emrs.algo.Decode
import com.qiangyuan.emrs.algo.ShotData
import com.qiangyuan.emrs.core.AppState
// 【USB增量】依赖由 SerialPortManager 收窄为统一传输接口 SerialTransport，状态机逻辑零改动
import com.qiangyuan.emrs.serial.SerialTransport

/**
 * 协议大脑（架构 protocol/ProtocolEngine.kt / §4.2-4.4）。
 * 全部状态机逻辑在 ProtocolThread（HandlerThread）内单线程运行；
 * listener 回调投递到主线程。
 *
 * 忠实复刻源码（LX1.PAS）：
 * - waiting_ack 扫描 $A5 → 按 send[3] 切换界面并启动看门狗；
 * - 收帧 type≠1 回 ACK $A5×16、校验错回 NAK $AA、type=1 不回；
 * - Timer3：3000ms 重发，命令 01/02/03 重发（6B/6B/8B），命令 04 不重发但计数，
 *   resend_count>=3 报“通信有故障！”（命令 02 特殊回主屏“未收到采样机应答！”）；
 * - Timer4：周期看门狗（1000/6000ms），每周期检查 data_come 并复位；type1/2/4 帧置 dataCame=true；
 *   “供电完毕”时停看门狗（源码 Timer4.enabled:=false）。
 */
class ProtocolEngine : RxFrameParser.Sink {

    companion object {
        const val TAG = "EmrsProtocol"
    }

    private lateinit var listener: EngineListener
    private lateinit var serial: SerialTransport
    var settings = ProtocolSettings()
        private set

    private val thread = HandlerThread("emrs-protocol").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    private val parser = RxFrameParser(this)

    // ---- waiting_ack 状态（等价 lx1 全局）----
    private var waitingAck = false
    private var pendingCmd = 0            // send[3]
    private var pendingFrame: ByteArray = ByteArray(0)
    private var resendCount = 1

    // ---- 定时器（Handler 版 Timer3/Timer4）----
    private val resendRunnable = object : Runnable {
        override fun run() = onResendTick()
    }
    private val watchdogRunnable = object : Runnable {
        override fun run() = onWatchdogTick()
    }
    private var watchdogRunning = false

    /** 启动引擎（MainActivity 调一次） */
    fun start(listener: EngineListener, serial: SerialTransport, settings: ProtocolSettings = ProtocolSettings()) {
        this.listener = listener
        this.serial = serial
        this.settings = settings
        serial.setRxListener(object : SerialTransport.RxListener {
            override fun onBytes(chunk: ByteArray) {
                // 读线程只投递，不在读线程解析（架构 §3.3）
                handler.post { onRxBytes(chunk) }
            }
        })
    }

    fun stop() {
        handler.removeCallbacks(resendRunnable)
        handler.removeCallbacks(watchdogRunnable)
        watchdogRunning = false
        thread.quitSafely()
    }

    // ================= 下行 =================

    /**
     * 发送命令（任意线程可调）。等价各按钮点击中：组帧 → write → waiting_ack:=true →
     * resend_count:=1 → Timer3(3000) 开。
     */
    fun sendCommand(cmd: FrameCodec.EmrsCommand, frame: ByteArray) {
        handler.post {
            pendingCmd = cmd.b
            pendingFrame = frame
            waitingAck = true
            resendCount = 1
            write(frame)
            handler.removeCallbacks(resendRunnable)
            handler.postDelayed(resendRunnable, settings.resendMs)
        }
    }

    private fun write(bytes: ByteArray) {
        val ok = serial.write(bytes)
        if (!ok) {
            Log.w(TAG, "serial write failed")
        }
    }

    /** Timer3Timer 语义（源码 L634-664） */
    private fun onResendTick() {
        if (!waitingAck) return
        if (resendCount >= settings.retryLimit) {
            waitingAck = false
            // 源码 case send[3]：4/1→主屏“通信有故障！”；2→回主屏“未收到采样机应答！”；3→Form5“通信有故障！”
            val cmd = pendingCmd
            main.post { listener.onResendOut(cmd) }
        } else {
            when (pendingCmd) {
                1 -> write(pendingFrame)   // 源码重发 6B
                2 -> write(pendingFrame)   // 源码重发 6B
                3 -> write(pendingFrame)   // 源码重发 8B
                // 4：源码无 case → 不重发，但保持等待并计数
            }
            waitingAck = true
            resendCount++
            handler.postDelayed(resendRunnable, settings.resendMs)
        }
    }

    // ================= 上行 =================

    /** 收到字节块（ProtocolThread 内） */
    private fun onRxBytes(chunk: ByteArray) {
        // 【阶段A】waiting_ack 扫描 $A5（源码对缓冲内每个 $A5 都触发，操作幂等，此处首字节触发一次）
        if (waitingAck && chunk.any { (it.toInt() and 0xFF) == 0xA5 }) {
            handler.removeCallbacks(resendRunnable)
            waitingAck = false
            parser.reset()                     // 源码 have_head:=false
            val cmd = pendingCmd
            when (cmd) {
                4 -> {                          // → 充电屏，看门狗 6000
                    AppState.dataCame = false
                    startWatchdog(settings.watchdogDataMs)
                    main.post { listener.onAck(4) }
                }
                1 -> {                          // → 自检屏，看门狗 1000
                    AppState.dataCame = false
                    startWatchdog(settings.watchdogSelfTestMs)
                    main.post { listener.onAck(1) }
                }
                2 -> {                          // → 回主屏（原版遗留的看门狗继续跑不可见；安卓侧停掉）
                    stopWatchdog()
                    main.post { listener.onAck(2) }
                }
                3 -> {                          // → 测量态复位，看门狗 6000
                    AppState.dataCame = false
                    AppState.resetMeasureSession()
                    startWatchdog(settings.watchdogDataMs)
                    main.post { listener.onAck(3) }
                }
            }
        }
        // 【阶段B】无论是否 ACK 都继续组帧（与源码一致）
        parser.feed(chunk, settings)
    }

    // ---- RxFrameParser.Sink ----

    override fun onValidFrame(type: Int, msg: ByteArray, payloadLen: Int) {
        if (type != 1 && settings.ackEnabled) {
            val ack = FrameCodec.ackFrame(settings.ackRepeatCount)
            if (settings.ackPerByte) {
                val one = byteArrayOf(FrameCodec.ACK_BYTE)
                repeat(settings.ackRepeatCount) { write(one) }   // 源码：16 次 1 字节 write
            } else {
                write(ack)
            }
        }
        when (type) {
            1 -> handleType1(msg)
            2 -> handleType2(msg, payloadLen)
            4 -> handleType4(msg)
            else -> Log.d(TAG, "unknown frame type=$type")
        }
    }

    override fun onInvalidFrame(type: Int) {
        // 源码：if msg[1]<>1 → NAK $AA×1
        if (type != 1) {
            write(FrameCodec.nakFrame())
        }
        Log.d(TAG, "invalid frame type=$type")
    }

    /** type=1 自检状态帧（LX1 L451-465） */
    private fun handleType1(msg: ByteArray) {
        AppState.dataCame = true
        val v = b(msg, 2, 3) / 1023.0 * 50.0
        val dianchi = b(msg, 4, 5) / 1023.0 * 50.45
        val vab2 = Decode.pTrunc(v * 100).toDouble() / 100.0        // trunc(vab*100)/100
        val dianchi2 = Decode.pTrunc(dianchi * 100).toDouble() / 100.0
        AppState.vab = vab2
        AppState.dianchi = dianchi2
        main.post { listener.onStatusFrame(1, vab2, dianchi2) }
    }

    /** type=4 充电状态帧（LX1 L435-449） */
    private fun handleType4(msg: ByteArray) {
        AppState.dataCame = true
        val v = b(msg, 2, 3) / 1023.0 * 50.0
        val vab2 = Decode.pTrunc(v * 100).toDouble() / 100.0
        AppState.vab = vab2
        // 充电完毕判定：源码用原始未截断值与 edit2 目标比较
        val target = AppState.targetVab.toDoubleOrNull() ?: 0.0
        val done = v >= target
        main.post { listener.onStatusFrame(4, vab2, 0.0) }
        if (done) {
            main.post {
                // “充电完毕 !” 由 ChargeScreen 在 onStatusFrame 后通过 target 判定呈现
            }
        }
    }

    /** type=2 供电数据帧（LX1 L466-618） */
    private fun handleType2(msg: ByteArray, payloadLen: Int) {
        AppState.dataCame = true
        val st = AppState
        val newGdcs = b(msg, 2, 2)
        // 重复帧：goto same —— 跳过解析，只做完成判定（架构 §8.1-I）
        if (newGdcs == st.gdcs) {
            checkSupplyFinished(st.gdcs)
            main.post { listener.onRepeatFrame(newGdcs) }
            return
        }
        // 跳号：仅提示，不阻断（源码 if msg[2]>gdcs+1）
        if (newGdcs > st.gdcs + 1) {
            main.post { listener.onDataLoss() }
        }
        st.gdcs = newGdcs

        // 解码（读取当前 mddl/bl 参数）
        val mddl = st.fullCurrent
        val ratio = st.work.ratio.toDoubleOrNull() ?: 1.0
        val shot: ShotData = try {
            Decode.decodeDataFrame(msg, payloadLen, mddl, ratio, st.gdcs, st.grid2Lie, st.grid2Hang)
        } catch (e: Exception) {
            Log.e(TAG, "decode error", e)
            return
        }

        // 写入全局工作内存（等价源码对 v250hz/v2value/iab_ave/sum_i_ave 的写入）
        for (i in 1..250) st.v250hz[i] = shot.v250hz[i]
        if (newGdcs in 1..32) {
            for (i in 1..1200) st.v2value[newGdcs][i] = shot.v2[i]
            st.iabAve[newGdcs] = shot.iabAve
        }
        st.sumIAve += shot.iabAve

        // StringGrid2 单元格 + 写指针推进（LX1 L534-543）
        val idx = st.grid2Hang * 8 + st.grid2Lie
        if (idx in 0..31) st.gridCells[idx] = shot.cellText
        st.grid2Lie++
        if (st.grid2Lie >= 8) {
            st.grid2Lie = 0
            st.grid2Hang++
        }
        if (st.grid2Hang >= 4) st.grid2Hang = 0

        main.post { listener.onShot(shot) }
        checkSupplyFinished(newGdcs)
    }

    /** 完成判定（源码 same 标号处）：gdcs==供电次数 → “供电完毕”，ke_sjcl=true，停看门狗 */
    private fun checkSupplyFinished(gdcs: Int) {
        val st = AppState
        if (gdcs == st.supplyTimes) {
            st.keSjcl = true
            stopWatchdog()   // 源码 form1.Timer4.enabled:=false
            main.post { listener.onSupplyFinished() }
        }
    }

    private fun b(msg: ByteArray, i: Int, j: Int): Int {
        // msg 为 1 基缓冲；源码 ((msg[i]&3)*256+msg[j])
        val h = msg[i - 1].toInt() and 0xFF
        val l = msg[j - 1].toInt() and 0xFF
        return (h and 3) * 256 + l
    }

    // ================= 看门狗（Timer4Timer L694-709） =================

    /** 周期看门狗：每周期检查 data_come 并复位（源码 Timer4 保持使能、周期触发） */
    private fun startWatchdog(intervalMs: Long) {
        handler.removeCallbacks(watchdogRunnable)
        watchdogRunning = true
        handler.postDelayed(watchdogRunnable, intervalMs)
    }

    fun stopWatchdog() {
        handler.removeCallbacks(watchdogRunnable)
        watchdogRunning = false
    }

    private fun onWatchdogTick() {
        if (!watchdogRunning) return
        if (!AppState.dataCame) {
            watchdogRunning = false    // 源码 Timer4.enabled:=false
            main.post { listener.onWatchdogTimeout() }
            return
        }
        AppState.dataCame = false      // 源码 data_come:=false
        // 按当前会话类型选择间隔：自检 1000，其余 6000（源码按启动时 interval 保持）
        val interval = if (pendingCmd == 1 && waitingAck.not()) settings.watchdogSelfTestMs
        else settings.watchdogDataMs
        handler.postDelayed(watchdogRunnable, interval)
    }
}
