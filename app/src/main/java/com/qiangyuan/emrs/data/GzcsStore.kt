package com.qiangyuan.emrs.data

import android.content.Context
import com.qiangyuan.emrs.core.AppState
import java.io.File

/**
 * gzcs.txt 读写（架构 data/GzcsStore.kt / §5.1，19 行顺序与源码 LX3.FormCreate / LX6.Button2 一致）。
 *
 * 恢复优先级：gzcs.txt（存在即回填）→ SharedPreferences 镜像 → 默认值
 * （原版文件缺失即 I/O 崩溃；安卓不崩溃——规格书问 11 的处理，差异记录交付说明）。
 * 源码特殊规则：第 8 行读回后程序编号强制置 '1'（LX3 L198）。
 */
object GzcsStore {

    private const val PREFS_NAME = "emrs_gzcs"

    fun gzcsFile(context: Context): File = File(DataDirManager.dataDir(context), "gzcs.txt")

    /** 启动恢复（等价 Form3.FormCreate 的 Reset 读取段） */
    fun load(context: Context) {
        val p = AppState.work
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 数据目录：Android 端始终指向沙箱（wjlj 字段仅显示/导出参考）
        p.dataDir = DataDirManager.path(context)

        val file = gzcsFile(context)
        val lines: List<String> = if (TextFileIO.exists(file)) {
            TextFileIO.readLines(file)
        } else {
            emptyList()
        }

        if (lines.size >= 19) {
            fun g(i: Int): String = if (lines.getOrNull(i - 1)?.isNotEmpty() == true) lines[i - 1] else ""
            if (g(1).isNotEmpty()) p.projectName = g(1)
            if (g(2).isNotEmpty()) p.projectTag = g(2)
            if (g(3).isNotEmpty()) p.lineNo = g(3)
            if (g(4).isNotEmpty()) p.pointNo = g(4)
            if (g(5).isNotEmpty()) p.loopSide = g(5)
            if (g(6).isNotEmpty()) p.coilArea = g(6)
            if (g(7).isNotEmpty()) p.operatorName = g(7)
            // 源码：if tmp<>'' then combobox1.text:='1';  —— 强制 '1'
            p.prgNo = "1"
            if (g(9).isNotEmpty()) p.supplyTimes = g(9)
            if (g(10).isNotEmpty()) AppState.tmpvab = g(10)       // → Form6 初始目标 VAB
            if (g(11).isNotEmpty()) p.workAreaNo = g(11)
            if (g(12).isNotEmpty()) p.operatorCode = g(12)
            if (g(13).isNotEmpty()) p.ratio = g(13)
            if (g(14).isNotEmpty()) p.receiveMode = g(14)
            // 15 行 wjlj：Android 端用沙箱目录（p.dataDir 已设），不回填旧 c:\emrs\
            if (g(16).isNotEmpty()) p.filterNo2 = g(16)
            if (g(17).isNotEmpty()) p.filterNo3 = g(17)
            if (g(18).isNotEmpty()) p.filterNo4 = g(18)
            if (g(19).isNotEmpty()) p.filterNo5 = g(19)
            mirrorToPrefs(prefs, p)
            return
        }

        // gzcs 缺失/行数不足 → 读 SP 镜像
        val fromPrefs = prefs.getString("projectName", null)
        if (fromPrefs != null) {
            p.projectName = prefs.getString("projectName", p.projectName) ?: p.projectName
            p.projectTag = prefs.getString("projectTag", p.projectTag) ?: p.projectTag
            p.lineNo = prefs.getString("lineNo", p.lineNo) ?: p.lineNo
            p.pointNo = prefs.getString("pointNo", p.pointNo) ?: p.pointNo
            p.loopSide = prefs.getString("loopSide", p.loopSide) ?: p.loopSide
            p.coilArea = prefs.getString("coilArea", p.coilArea) ?: p.coilArea
            p.operatorName = prefs.getString("operatorName", p.operatorName) ?: p.operatorName
            p.prgNo = "1"
            p.supplyTimes = prefs.getString("supplyTimes", p.supplyTimes) ?: p.supplyTimes
            AppState.tmpvab = prefs.getString("targetVab", AppState.tmpvab) ?: AppState.tmpvab
            p.workAreaNo = prefs.getString("workAreaNo", p.workAreaNo) ?: p.workAreaNo
            p.operatorCode = prefs.getString("operatorCode", p.operatorCode) ?: p.operatorCode
            p.ratio = prefs.getString("ratio", p.ratio) ?: p.ratio
            p.receiveMode = prefs.getString("receiveMode", p.receiveMode) ?: p.receiveMode
            p.filterNo2 = prefs.getString("filterNo2", p.filterNo2) ?: p.filterNo2
            p.filterNo3 = prefs.getString("filterNo3", p.filterNo3) ?: p.filterNo3
            p.filterNo4 = prefs.getString("filterNo4", p.filterNo4) ?: p.filterNo4
            p.filterNo5 = prefs.getString("filterNo5", p.filterNo5) ?: p.filterNo5
        }
        // 都不存在 → 使用默认值；首次保存时自动创建 gzcs.txt（不崩溃）
        AppState.targetVab = AppState.tmpvab
    }

    /**
     * 保存 19 行（等价 Form3.Button1 / Form6.Button2 / Form5.Button5 的 Rewrite 段）。
     * @param targetVabText form6.edit2 文本（第 10 行）
     */
    fun save(context: Context, targetVabText: String): Boolean {
        val p = AppState.work
        p.dataDir = DataDirManager.path(context)
        val lines = listOf(
            p.projectName,                                     // 1
            p.tagFirstChar(),                                  // 2 源码 sstr[1]
            p.lineNo,                                          // 3
            p.pointNo,                                         // 4
            p.loopSide,                                        // 5
            p.coilArea,                                        // 6
            p.operatorName,                                    // 7
            p.prgNo,                                           // 8 combobox1
            p.supplyTimes,                                     // 9 combobox2
            targetVabText,                                     // 10 form6.edit2
            p.workAreaNo,                                      // 11
            p.operatorCode,                                    // 12
            p.ratio,                                           // 13
            p.receiveMode,                                     // 14
            p.dataDir,                                         // 15 wjlj（Android 沙箱路径）
            p.filterNo2,                                       // 16
            p.filterNo3,                                       // 17
            p.filterNo4,                                       // 18
            p.filterNo5                                        // 19
        )
        val ok = TextFileIO.writeLines(gzcsFile(context), lines)
        mirrorToPrefs(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), p)
        return ok
    }

    private fun mirrorToPrefs(prefs: android.content.SharedPreferences, p: WorkParams) {
        prefs.edit()
            .putString("projectName", p.projectName)
            .putString("projectTag", p.projectTag)
            .putString("lineNo", p.lineNo)
            .putString("pointNo", p.pointNo)
            .putString("loopSide", p.loopSide)
            .putString("coilArea", p.coilArea)
            .putString("operatorName", p.operatorName)
            .putString("prgNo", "1")
            .putString("supplyTimes", p.supplyTimes)
            .putString("targetVab", AppState.tmpvab)
            .putString("workAreaNo", p.workAreaNo)
            .putString("operatorCode", p.operatorCode)
            .putString("ratio", p.ratio)
            .putString("receiveMode", p.receiveMode)
            .putString("filterNo2", p.filterNo2)
            .putString("filterNo3", p.filterNo3)
            .putString("filterNo4", p.filterNo4)
            .putString("filterNo5", p.filterNo5)
            .apply()
    }
}
