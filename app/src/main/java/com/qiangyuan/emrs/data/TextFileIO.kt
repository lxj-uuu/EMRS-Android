package com.qiangyuan.emrs.data

import com.qiangyuan.emrs.algo.Formatting
import com.qiangyuan.emrs.util.Gbk
import java.io.File
import java.io.FileOutputStream

/**
 * 原格式文本文件读写（架构 data/TextFileIO.kt / §5.6）。
 * 统一 GBK 编码 + CRLF 换行（与 Delphi TextFile 默认一致，兼容旧 Windows 软件）。
 * 数值写出走 [Formatting.delphiReal]（Delphi real 默认文本近似）与整型字面量。
 */
object TextFileIO {

    private val GBK = charset("GBK")

    /** 覆写文件（等价 Rewrite + writeln×n） */
    fun writeLines(file: File, lines: List<String>): Boolean {
        return try {
            val sb = StringBuilder()
            for (line in lines) {
                sb.append(line).append("\r\n")
            }
            FileOutputStream(file, false).use { it.write(Gbk.encode(sb.toString())) }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 追加一行（等价 Append + writeln） */
    fun appendLine(file: File, line: String): Boolean {
        return try {
            FileOutputStream(file, true).use { it.write(Gbk.encode(line + "\r\n")) }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 读全部行（等价 Reset + readln；兼容 CRLF/LF/CR，尾空行去除） */
    fun readLines(file: File): List<String> {
        return try {
            val text = Gbk.decode(file.readBytes())
            text.split("\r\n", "\n", "\r")
                .dropLastWhile { it.isEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun exists(file: File): Boolean = file.exists() && file.isFile

    // ---------- 数值写出格式 ----------

    /** Delphi real 默认文本（科学计数 15 位有效数字） */
    fun delphiReal(d: Double): String = Formatting.delphiReal(d)

    /** Delphi floattostr（常规格式，显示/无格式字段用） */
    fun floatToStr(d: Double): String = Formatting.floatToStr(d)

    /** 整型字面量（等价 writeln(integer)） */
    fun intStr(v: Long): String = v.toString()
}
