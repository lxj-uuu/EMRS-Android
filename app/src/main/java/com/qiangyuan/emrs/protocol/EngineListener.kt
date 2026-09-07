package com.qiangyuan.emrs.protocol

import com.qiangyuan.emrs.algo.ShotData

/**
 * 协议引擎 UI 回调接口（架构 protocol/EngineListener.kt）。
 * 所有回调已被 ProtocolEngine 投递到主线程，实现方可直接操作 UI。
 */
interface EngineListener {

    /** 收到 ACK（$A5），按命令字节切换界面/看门狗（LX1.Comm1RxChar 阶段A） */
    fun onAck(cmd: Int) {}

    /**
     * 状态帧：
     * type=1 自检（vab、battery 均为两位截断后的电压）；
     * type=4 充电状态（battery 无效传 0）。
     */
    fun onStatusFrame(type: Int, vab: Double, battery: Double) {}

    /** 供电数据帧解码完成（引擎已写入 AppState） */
    fun onShot(shot: ShotData) {}

    /** gdcs==供电次数：供电完毕 */
    fun onSupplyFinished() {}

    /** msg[2] > gdcs+1：数据有丢失（仅提示） */
    fun onDataLoss() {}

    /** 重复 gdcs 帧（源码 goto same，只做完成判定不重复解析） */
    fun onRepeatFrame(gdcs: Int) {}

    /** 连续 3 次（resend_count>=3）未收到 ACK（Timer3Timer 源码语义） */
    fun onResendOut(cmd: Int) {}

    /** 看门狗到点未收到数据（Timer4Timer：data_come=false） */
    fun onWatchdogTimeout() {}
}
