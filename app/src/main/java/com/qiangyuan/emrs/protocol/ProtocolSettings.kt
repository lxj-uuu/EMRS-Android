package com.qiangyuan.emrs.protocol

/**
 * 协议可配置项（架构 protocol/ProtocolSettings.kt / §4.4）。
 * 默认值全部取源码字面行为；对应规格书 §6.3 待确认问题做成开关。
 */
data class ProtocolSettings(
    /** 帧级 ACK 总开关（type≠1 的合法帧回 $A5×16） */
    val ackEnabled: Boolean = true,

    /** ACK 字节重复次数（源码 16 次，规格书问 3） */
    val ackRepeatCount: Int = 16,

    /**
     * true=按源码语义逐字节写 16 次（每次 1B）；false=整包 16B 一次写。
     * 字节流结果一致，仅 write 粒度不同。
     */
    val ackPerByte: Boolean = false,

    /** 重发上限（源码 resend_count>=3 报故障，即总共发 3 次） */
    val retryLimit: Int = 3,

    /** 重发间隔（源码 Timer3.interval=3000ms） */
    val resendMs: Long = 3000L,

    /**
     * 严格模式：true=复刻源码“每帧处理完 exit，同批后续字节丢弃”（LX1 L627）；
     * false（默认）=稳健模式，同批可连续取多帧（架构 §4.3）。
     */
    val strictOneFramePerChunk: Boolean = false,

    /** 看门狗间隔：自检 1000ms（源码 ACK 后 Timer4.interval=1000） */
    val watchdogSelfTestMs: Long = 1000L,

    /** 看门狗间隔：供电测量/充电 6000ms */
    val watchdogDataMs: Long = 6000L,

    /** 供电命令 send[4] 固定字节（源码=3；若客户证实为 prg 相关可改，架构问 9） */
    val supplyPrgByte: Int = 3
)
