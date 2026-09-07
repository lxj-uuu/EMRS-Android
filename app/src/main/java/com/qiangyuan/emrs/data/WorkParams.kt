package com.qiangyuan.emrs.data

/**
 * 工作参数（架构 data/WorkParams.kt / §5.1）。
 * 19 项对应 gzcs.txt 19 行（顺序见 GzcsStore）；字段均为源码控件文本原样字符串。
 * 满档电流 mddl 属 Form3 表单量，不进 gzcs（架构 §5.1），仅随 AppState.fullCurrent 生效。
 */
data class WorkParams(
    var projectName: String = "青海湖",     // 1 xmmc 项目名称
    var projectTag: String = "q",          // 2 xmbs 项目标识（取第 1 字符存盘）
    var lineNo: String = "1",              // 3 xianhao 线号
    var pointNo: String = "0",             // 4 dianhao 点号
    var loopSide: String = "100",          // 5 gongquan 供圈边长(m)
    var coilArea: String = "100",          // 6 cequan 接收面积/测圈
    var operatorName: String = "李小二",    // 7 czy 操作员姓名
    var prgNo: String = "1",               // 8 ComboBox1 程序编号（读回强制 '1'，源码 L198）
    var supplyTimes: String = "1",         // 9 ComboBox2 供电次数
    var targetVab: String = "30",          // 10 form6.edit2 目标 VAB（→tmpvab）
    var workAreaNo: String = "01",         // 11 gqh 工区号
    var operatorCode: String = "0001",     // 12 czydm 操作员代码
    var ratio: String = "2",               // 13 bl 倍率
    var receiveMode: String = "中心",       // 14 jsfs 接收方式（中心/重叠）
    var dataDir: String = "",              // 15 wjlj 文件路径（Android=沙箱目录）
    var filterNo2: String = "5",           // 16 lbcs2 1E2 滤波次数(0..5)
    var filterNo3: String = "5",           // 17 lbcs3 1E1
    var filterNo4: String = "5",           // 18 lbcs4 1E0
    var filterNo5: String = "5"            // 19 lbcs5 1E-1
) {
    /** 正演文件名（Form3.zyjg.text；不进 gzcs 19 行，仅会话内有效，等价 Delphi 控件文本） */
    var forwardFile: String = "zyjg1.txt"

    fun prgNoInt(): Int = prgNo.toIntOrNull() ?: 1
    fun supplyTimesInt(): Int = supplyTimes.toIntOrNull() ?: 1
    fun ratioDouble(): Double = ratio.toDoubleOrNull() ?: 1.0

    /** 项目标识首字符（源码 sstr[1] 取第 1 字符写 gzcs/拼文件名） */
    fun tagFirstChar(): String {
        return if (projectTag.isNotEmpty()) projectTag.substring(0, 1) else "q"
    }

    companion object {
        /** 下拉允许值（源码 DFM Items，架构 §5.1） */
        val PRG_OPTIONS = listOf("1", "2", "3", "4")
        val SUPPLY_TIMES_OPTIONS = listOf("1", "4", "8", "16", "32")
        val RATIO_OPTIONS = listOf("0.5", "1", "2", "3", "4", "5", "6")
        val RECEIVE_MODE_OPTIONS = listOf("中心", "重叠")
        val FILTER_OPTIONS = listOf("0", "1", "2", "3", "4", "5")
        val FULL_CURRENT_OPTIONS = listOf("2000", "4000", "8000")   // mddl（不进 gzcs）

        /** 项目标识校验：仅允许 1 个英文字母（架构 §5.1，规格书 §2.3 提示语） */
        fun validTag(tag: String): Boolean {
            if (tag.length != 1) return false
            val c = tag[0]
            return c in 'a'..'z' || c in 'A'..'Z'
        }
    }
}
