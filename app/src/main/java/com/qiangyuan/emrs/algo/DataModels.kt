package com.qiangyuan.emrs.algo

/**
 * 协议/处理数据传输模型（架构 algo/DataModels.kt）。
 * 数组均为 1 基 Pascal 语义：下标 0 废弃，[1] 为第一个有效元素。
 */
data class ShotData(
    /** 本次供电次数（帧 msg[2]） */
    val gdcs: Int,
    /** 本次平均电流 iab_ave[gdcs]（已乘倍率、截断两位小数） */
    val iabAve: Double,
    /** 250 点 50Hz 参考序列 v250hz[1..250] */
    val v250hz: DoubleArray,
    /** 1200 点去 50Hz 后 V2 原始序列 v2[1..1200] */
    val v2: DoubleArray,
    /** 本帧写入 StringGrid2 的单元格内容 */
    val cellText: String,
    /** 写入单元格列号 grid2_lie（写入前值） */
    val cellCol: Int,
    /** 写入单元格行号 grid2_hang（写入前值） */
    val cellRow: Int
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * 数据处理流水线输出（ProcessPipeline.runMeasure / runCalibration）。
 */
data class ProcessOutput(
    /** 每道显示值 displ[1..points]（已按模式规整阶梯处理） */
    val displ: DoubleArray,
    /** memo 清单行：“dot, 值” */
    val memoLines: List<String>,
    /** 每道曲线绘制值：绝对值并钳位 1e6（供 LogCurveView 着色与映射） */
    val curveValues: DoubleArray,
    /** 每道曲线颜色：true=红(正)，false=蓝(负)；标定正演曲线另用 LogCurveView.drawForward */
    val curvePositive: BooleanArray
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
