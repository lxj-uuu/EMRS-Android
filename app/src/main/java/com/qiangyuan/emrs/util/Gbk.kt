package com.qiangyuan.emrs.util

/**
 * GBK 编解码助手（架构 util/Gbk.kt）。
 * 导出文件保持 Windows ANSI(GBK) 以兼容旧 Windows 软件（规格书 §5.9）；
 * 读取旧文件时 GBK 失败则回退 UTF-8 / ISO-8859-1。
 */
object Gbk {

    private val GBK = charset("GBK")
    private val UTF8 = charset("UTF-8")
    private val LATIN1 = charset("ISO-8859-1")

    /** String → GBK 字节（写出用；失败回退 UTF-8） */
    fun encode(s: String): ByteArray {
        return try {
            s.toByteArray(GBK)
        } catch (e: Exception) {
            s.toByteArray(UTF8)
        }
    }

    /** GBK 字节 → String（读入用；失败依次回退 UTF-8 / Latin1） */
    fun decode(b: ByteArray): String {
        return try {
            String(b, GBK)
        } catch (e: Exception) {
            try {
                String(b, UTF8)
            } catch (e2: Exception) {
                String(b, LATIN1)
            }
        }
    }
}
