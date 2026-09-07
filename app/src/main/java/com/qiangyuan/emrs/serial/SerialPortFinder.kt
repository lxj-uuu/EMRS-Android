package com.qiangyuan.emrs.serial

/**
 * 串口枚举（架构 serial/SerialPortFinder.kt）。
 * JNI listPorts 扫描 /dev；失败时回退常用路径清单（COMMON_PATHS），
 * 供设置页下拉选择。
 */
object SerialPortFinder {

    /** 枚举串口路径：JNI 扫描 + 常用路径合并去重 */
    fun find(): List<String> {
        val found = linkedSetOf<String>()
        try {
            val ports = SerialPortJni.listPorts()
            ports?.forEach { if (it.isNotBlank()) found.add(it) }
        } catch (e: Throwable) {
            // JNI 不可用（如 so 加载失败）时退化为静态清单
        }
        SerialPortConfig.COMMON_PATHS.forEach { found.add(it) }
        return found.toList()
    }
}
