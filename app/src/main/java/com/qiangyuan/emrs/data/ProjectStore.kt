package com.qiangyuan.emrs.data

import android.content.Context
import com.qiangyuan.emrs.core.AppState
import java.io.File

/**
 * 项目登记文件 `<标识><线号>.txt`（架构 data/ProjectStore.kt / 规格书 §5.2）。
 * - 首次线号变化时覆写第 1 行 xmmc（Rewrite）；
 * - 每存一个点追加一行点号（Append）。
 */
object ProjectStore {

    fun file(context: Context, tag: String, lineNo: String): File =
        File(DataDirManager.dataDir(context), tag + lineNo + ".txt")

    /**
     * 线号/标识变化时重建登记文件（LX5.Button4Click / LX3.Button1Click 的 if 段）。
     * 返回是否执行了重建。
     */
    fun ensureProjectFile(context: Context): Boolean {
        val st = AppState
        val p = st.work
        if (st.oldXmbs != p.projectTag || st.oldXianhao != p.lineNo) {
            val f = file(context, p.tagFirstChar(), p.lineNo)
            TextFileIO.writeLines(f, listOf(p.projectName))
            st.oldXmbs = p.projectTag
            st.oldXianhao = p.lineNo
            return true
        }
        return false
    }

    /**
     * 供电开始时线号变化即重建（源码 old_xianhao<>xxh.text 判断，仅比较线号）。
     */
    fun ensureOnSupplyStart(context: Context, lineNoText: String): Boolean {
        val st = AppState
        val p = st.work
        if (st.oldXianhao != lineNoText) {
            val f = file(context, p.tagFirstChar(), lineNoText)
            TextFileIO.writeLines(f, listOf(p.projectName))
            st.oldXianhao = lineNoText
            return true
        }
        return false
    }

    /** 存盘后追加点号一行（LX5.Button5Click：append + writeln(ddh)） */
    fun appendPoint(context: Context, tag: String, lineNo: String, pointNo: String): Boolean {
        val f = file(context, tag, lineNo)
        if (!TextFileIO.exists(f)) {
            // 原版依赖文件已存在（首次线号变化时创建）；此处兜底创建防丢登记
            TextFileIO.writeLines(f, listOf(AppState.work.projectName))
        }
        return TextFileIO.appendLine(f, pointNo)
    }
}
