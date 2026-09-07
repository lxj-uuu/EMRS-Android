package com.qiangyuan.emrs.algo

/**
 * Delphi 数值文本格式近似（架构 algo/Formatting.kt / §5.6、§8.1-H）。
 *
 * 两种格式：
 * 1. delphiReal(d)：等价 TextFile writeln(real) 默认输出 —— 科学计数法，
 *    15 位有效数字（1 位整数 + 14 位小数），指数 E±XXXX（4 位补零）；
 *    0 输出 "0.00000000000000E+0000"。
 *    （属 P1 待与客户旧文件逐字节回归校准的点，floatFormat 策略集中于此。）
 * 2. floatToStr(d)：等价 Delphi FloatToStr —— 常规格式，整数无小数点，去尾零。
 */
object Formatting {

    /** 科学计数法阈值之外的常规输出最大有效位数 */
    private const val SIG_DIGITS = 15

    /** 等价 Pascal trunc：向零取整 */
    fun trunc(d: Double): Long = if (d >= 0) Math.floor(d).toLong() else Math.ceil(d).toLong()

    /** 等价 Delphi floattostr（常规格式） */
    fun floatToStr(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "0"
        if (d == 0.0) return "0"
        val abs = Math.abs(d)
        if (abs >= 1e15 || abs < 1e-4) {
            return sciShort(d)
        }
        // 先按 15 位有效数字舍入，再输出去尾零的定点表示
        val scale = Math.pow(10.0, (SIG_DIGITS - 1 - Math.floor(Math.log10(abs))).coerceIn(0.0, 30.0))
        val rounded = Math.round(d * scale) / scale
        if (rounded == Math.floor(rounded) && Math.abs(rounded) < 1e15) {
            return rounded.toLong().toString()
        }
        var s = java.math.BigDecimal(rounded).toPlainString()
        if (s.contains('.')) {
            s = s.trimEnd('0').trimEnd('.')
        }
        return s
    }

    private fun sciShort(d: Double): String {
        // 极端值（≥1e15 或 <1e-4）FloatToStr 亦转科学计数（Delphi 行为近似）
        val exp = Math.floor(Math.log10(Math.abs(d))).toInt()
        val mant = d / Math.pow(10.0, exp.toDouble())
        val m = java.math.BigDecimal(mant).setScale(14, java.math.BigDecimal.ROUND_HALF_UP)
            .stripTrailingZeros().toPlainString()
        return m + "E" + exp
    }

    /**
     * 等价 TextFile writeln(real) 默认格式：科学计数 15 位有效数字。
     * 例：0.08 → "8.00000000000000E-0002"；-0.352 → "-3.52000000000000E-0001"。
     * 注：旧 Windows 软件读取该类文件用的是 Pascal Read(real)，两种格式均可解析；
     *     此处尽力贴近 Delphi 逐字节输出，P1 用客户真实旧文件回归校准。
     */
    fun delphiReal(d: Double): String {
        if (d == 0.0) return "0.00000000000000E+0000"
        val neg = d < 0
        val abs = Math.abs(d)
        var exp = Math.floor(Math.log10(abs)).toInt()
        var mant = abs / Math.pow(10.0, exp.toDouble())
        // 舍入后可能进位（如 9.99999999999999999 → 10.0）
        var mantStr = java.math.BigDecimal(mant).setScale(14, java.math.BigDecimal.ROUND_HALF_UP).toPlainString()
        if (mantStr.startsWith("10.")) {
            mantStr = mantStr.substring(1) // "0." + 15 位
            exp += 1
        }
        // mantStr 形如 "d.dddddddddddddd"（1+1+14 位）
        val sign = if (neg) "-" else ""
        val expSign = if (exp < 0) "-" else "+"
        val expAbs = Math.abs(exp).toString().padStart(4, '0')
        return sign + mantStr + "E" + expSign + expAbs
    }

    /** 等价 Pascal round（银行家舍入在工程上差异可忽略；此处用四舍五入） */
    fun pRound(d: Double): Long = Math.round(d)
}
