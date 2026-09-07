package com.qiangyuan.emrs.data

import android.content.Context
import com.qiangyuan.emrs.algo.Formatting
import com.qiangyuan.emrs.core.AppState
import java.io.File
import java.util.Calendar

/**
 * 测量成果四件套 + zygs.txt 生成 + zyjg 读取
 * （架构 data/ResultFiles.kt / §5.3，逐行对齐 LX5.Button5Click / LX3.Button2Click / LX5.Button2Click）。
 * 全部 GBK+CRLF；数值用 Delphi real 默认文本（科学计数），整型字段用字面量。
 */
object ResultFiles {

    private fun dir(context: Context): File = DataDirManager.dataDir(context)

    private fun pTrunc(d: Double): Long = Formatting.trunc(d)

    // ============ (1) <tag><L>s<P>.txt 逐采样点 V2/I 原始序列（L806-815） ============
    fun writeSFile(context: Context): File {
        val st = AppState
        val p = st.work
        val f = File(dir(context), p.tagFirstChar() + st.work.lineNo + "s" + p.pointNo + ".txt")
        val lines = ArrayList<String>(st.pointsEnd1 + 5)
        lines.add("线圈边长（m）: " + p.loopSide)
        // 源码 writeln(F,'供电电流（A）: ',trunc((average_i*100)/100)) —— 整型
        lines.add("供电电流（A）: " + pTrunc((st.averageI * 100) / 100).toString())
        lines.add("倍率: " + p.ratio)
        lines.add(st.pointsEnd1.toString() + "个V2/I值（采样间隔80微秒）: ")
        for (i in 1..st.pointsEnd1) {
            lines.add(i.toString() + "  " + Formatting.delphiReal(st.v2[i] / st.averageI))
        }
        TextFileIO.writeLines(f, lines)
        return f
    }

    // ============ (2)(3) <tag><L>n<P>.txt / c<P>.txt（L819-844） ============
    private fun headLines(): ArrayList<String> {
        val st = AppState
        val p = st.work
        val lines = ArrayList<String>()
        // 源码：重叠→尾 0，否则（中心）→尾 1
        val flag = if (p.receiveMode == "重叠") "0" else "1"
        lines.add("2 " + st.points + " " + pTrunc(p.loopSide.toDoubleOrNull() ?: 0.0) + " " +
                pTrunc((st.averageI * 100) / 100) + " " + flag)
        if (p.receiveMode == "中心") {
            lines.add(pTrunc(p.coilArea.toDoubleOrNull() ?: 0.0).toString())
        }
        lines.add("4 " + Formatting.delphiReal(0.02))
        lines.add("倍率: " + p.ratio)
        return lines
    }

    fun writeNFile(context: Context): File {
        val st = AppState
        val p = st.work
        val f = File(dir(context), p.tagFirstChar() + st.work.lineNo + "n" + p.pointNo + ".txt")
        val lines = headLines()
        for (i in 1..st.points) {
            lines.add(Formatting.delphiReal(st.shjian[i]) + " " + Formatting.delphiReal(st.v2i[i]))
        }
        TextFileIO.writeLines(f, lines)
        return f
    }

    fun writeCFile(context: Context): File {
        val st = AppState
        val p = st.work
        val f = File(dir(context), p.tagFirstChar() + st.work.lineNo + "c" + p.pointNo + ".txt")
        val lines = headLines()
        for (i in 1..st.points) {
            lines.add(Formatting.delphiReal(st.shjian[i]) + " " +
                    Formatting.delphiReal(st.v2i[i] * st.biaoding[i]))
        }
        TextFileIO.writeLines(f, lines)
        return f
    }

    // ============ (4) <P4>-<L3>.dat 最终成果（L850-938） ============
    fun writeDatFile(context: Context): File {
        val st = AppState
        val p = st.work
        val cal = Calendar.getInstance()
        fun pad2(v: Int): String = v.toString().padStart(2, '0')
        val yy = pad2(cal.get(Calendar.YEAR) % 100)      // 2 位年（架构 §8.1-D）
        val mm = pad2(cal.get(Calendar.MONTH) + 1)
        val dd = pad2(cal.get(Calendar.DAY_OF_MONTH))
        val hh = pad2(cal.get(Calendar.HOUR_OF_DAY))
        val mi = pad2(cal.get(Calendar.MINUTE))

        val gdsjv = 4
        val clsjv = when (st.prgNo) {
            1 -> 30; 2 -> 61; 3 -> 93; else -> 126
        }
        val gqbhv = yy + mm + dd + p.workAreaNo
        val czydmv = p.operatorCode
        val djcsv = p.supplyTimes
        val gddyv = if (st.targetVab.isEmpty()) "0" else st.targetVab
        // gzdlv=floattostr(trunc((average_i-0.000068*average_i*average_i)*100)/100)
        val gz = (st.averageI - 0.000068 * st.averageI * st.averageI)
        val gzdlv = Formatting.floatToStr(pTrunc(gz * 100).toDouble() / 100.0)
        val cdjdv = 0
        // blv:=strtoint(bl.text)：bl=0.5 时原版抛异常；此处取整数值（差异记录交付说明）
        val blv = pTrunc(p.ratio.toDoubleOrNull() ?: 1.0)
        val jsfsv = if (p.receiveMode == "中心") 0 else 1
        val gqbcv = pTrunc(p.loopSide.toDoubleOrNull() ?: 0.0)
        val jsbcv = pTrunc(p.coilArea.toDoubleOrNull() ?: 0.0)

        var ddhv = p.pointNo
        while (ddhv.length < 4) ddhv = "0$ddhv"
        var xxhv = st.work.lineNo
        while (xxhv.length < 3) xxhv = "0$xxhv"

        val f = File(dir(context), "$ddhv-$xxhv.dat")
        val lines = ArrayList<String>(st.points + 8)
        lines.add("$gqbhv ${p.pointNo} ${st.work.lineNo} $czydmv")
        lines.add("20$yy $mm $dd $hh $mi")
        lines.add("$gdsjv $clsjv $djcsv ${st.points}")
        lines.add("$gddyv $gzdlv")
        lines.add("$cdjdv $blv $jsfsv")
        lines.add("$gqbcv $jsbcv")
        lines.add("${st.prgNo} 0")
        for (i in 1..st.points) {
            lines.add(Formatting.delphiReal(st.shjian[i]) + " " +
                    Formatting.delphiReal(st.v2i[i] * st.biaoding[i]))
        }
        TextFileIO.writeLines(f, lines)
        return f
    }

    // ============ (f) zygs.txt 生成正演格式（LX3.Button2Click） ============
    fun writeZygs(context: Context): File {
        val st = AppState
        val p = st.work
        val f = File(dir(context), "zygs.txt")
        val lines = ArrayList<String>(st.points + 3)
        val flag = if (p.receiveMode == "重叠") "0" else "1"
        lines.add("2 ${st.points} ${pTrunc(p.loopSide.toDoubleOrNull() ?: 0.0)} $flag")
        if (p.receiveMode == "中心") {
            lines.add(pTrunc(p.coilArea.toDoubleOrNull() ?: 0.0).toString())
        }
        lines.add("4 " + Formatting.delphiReal(0.02))
        st.computeShjian()
        for (i in 1..st.points) {
            lines.add(Formatting.delphiReal(st.shjian[i]))
        }
        TextFileIO.writeLines(f, lines)
        return f
    }

    // ============ (g) zyjg 正演文件读取（LX5.Button2Click 标定段） ============
    /**
     * @return (shjian[], zhengyan[])；道数不一致或读取失败返回 null（UI 提示“道数不一致！”）
     */
    fun readZyjg(context: Context, fileName: String, points: Int): Pair<DoubleArray, DoubleArray>? {
        val f = File(dir(context), fileName)
        if (!TextFileIO.exists(f)) return null
        val lines = TextFileIO.readLines(f)
        if (lines.isEmpty()) return null
        val n = lines[0].trim().toIntOrNull() ?: return null
        if (n != points) return null
        if (lines.size < points + 1) return null
        val shjian = DoubleArray(57)
        val zhengyan = DoubleArray(57)
        for (i in 1..points) {
            val parts = lines[i].trim().split(Regex("\\s+"))
            if (parts.size < 2) return null
            shjian[i] = parts[0].toDoubleOrNull() ?: return null
            zhengyan[i] = parts[1].toDoubleOrNull() ?: return null
        }
        return Pair(shjian, zhengyan)
    }

    // ============ 存盘总入口（等价 Form5.Button5Click 测量分支） ============
    /**
     * 写四件套 + 项目登记追加 + gzcs 重写。
     * @return 写出的文件列表（供提示/分享）
     */
    fun saveMeasureResults(context: Context): List<String> {
        val out = ArrayList<String>(6)
        out.add(writeSFile(context).name)
        out.add(writeNFile(context).name)
        out.add(writeCFile(context).name)
        out.add(writeDatFile(context).name)
        val p = AppState.work
        ProjectStore.appendPoint(context, p.tagFirstChar(), AppState.work.lineNo, p.pointNo)
        GzcsStore.save(context, AppState.targetVab)
        ProjectStore.ensureProjectFile(context)
        return out
    }
}
