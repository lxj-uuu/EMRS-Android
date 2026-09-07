package com.qiangyuan.emrs.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * FileProvider 导出/分享（架构 util/ShareExport.kt / §5.7）。
 * 以 GBK 字节流原样分享，不被转码（规格书问 10 默认兼容）。
 */
object ShareExport {

    private fun authority(context: Context): String = context.packageName + ".fileprovider"

    fun contentUri(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(context, authority(context), file)
    }

    /** 分享单个文件（数据目录内） */
    fun shareFile(context: Context, file: File) {
        val uri = contentUri(context, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出/分享 " + file.name))
    }

    /** 另存为（ACTION_CREATE_DOCUMENT） */
    fun createDocumentIntent(mime: String = "application/octet-stream"): Intent {
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
        }
    }

    /** 选择导入文件（ACTION_OPEN_DOCUMENT） */
    fun openDocumentIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
    }
}
