package com.qiangyuan.emrs.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * SAF 导入旧文件（架构 data/FileImport.kt / §5.7）。
 * ACTION_OPEN_DOCUMENT 选择旧 Windows 文件 → 原字节复制入数据目录（文件名不变），
 * 不做任何编码转换（保持 GBK 字节原样）。
 */
object FileImport {

    /**
     * 复制所选文件到数据目录。
     * @return 导入后的文件名；失败返回 null
     */
    fun importFromSaf(context: Context, uri: Uri): String? {
        return try {
            val name = queryDisplayName(context, uri) ?: ("import_" + System.currentTimeMillis() + ".txt")
            val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null
            val target = java.io.File(DataDirManager.dataDir(context), safeName)
            target.writeBytes(bytes)
            safeName
        } catch (e: Exception) {
            null
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
