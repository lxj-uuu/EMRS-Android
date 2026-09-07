package com.qiangyuan.emrs.data

import java.io.File

/**
 * 数据目录管理（架构 data/DataDirManager.kt / §5.7）。
 * 目录：getExternalFilesDir(null)/emrs/（App 外部私有区，Android 7~11 免权限）。
 * 首启建目录；不存在时不崩溃（规格书问 11 的处理）。
 */
object DataDirManager {

    @Volatile
    private var cachedDir: File? = null

    /** 获取（并确保存在）数据目录 */
    fun dataDir(context: android.content.Context): File {
        cachedDir?.let { if (it.exists()) return it }
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "emrs")
        try {
            if (!dir.exists()) dir.mkdirs()
        } catch (e: Exception) {
            // 建目录失败不崩溃，读写时再报错
        }
        cachedDir = dir
        return dir
    }

    /** 列出数据目录下的文件名（按名称排序） */
    fun listFiles(context: android.content.Context): List<String> {
        val dir = dataDir(context)
        val names = dir.listFiles()?.map { it.name } ?: emptyList()
        return names.sorted()
    }

    /** 在数据目录中定位文件（不存在返回 null） */
    fun find(context: android.content.Context, name: String): File? {
        val f = File(dataDir(context), name)
        return if (f.exists()) f else null
    }

    /** 数据目录绝对路径（设置页“文件路径”显示） */
    fun path(context: android.content.Context): String {
        val d = dataDir(context)
        var p = d.absolutePath
        if (!p.endsWith("/") && !p.endsWith("\\")) p += "\\"
        return p
    }
}
