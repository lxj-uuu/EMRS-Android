package com.qiangyuan.emrs.serial

import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

/**
 * root / 系统权限检测与提权（架构 serial/RootHelper.kt / §3.5）。
 *
 * 分级策略：
 * 1. 厂商系统签名（首选）—— /dev/ttyS* 对系统应用天然可读写，本类不做任何事；
 * 2. root 提权 —— 打开前执行 `su -c chmod 666 <dev>`（每次会话执行，重启后需重跑）；
 * 3. 都不可用 —— 返回带原因的失败结果，UI 提示，不崩溃。
 */
object RootHelper {

    enum class RootState { UNKNOWN, AVAILABLE, UNAVAILABLE }

    @Volatile
    private var rootState: RootState = RootState.UNKNOWN

    fun isRootAvailable(): Boolean {
        if (rootState == RootState.AVAILABLE) return true
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = BufferedReader(InputStreamReader(p.inputStream)).readLine()
            p.waitFor()
            val ok = out != null && out.contains("uid=0")
            rootState = if (ok) RootState.AVAILABLE else RootState.UNAVAILABLE
            ok
        } catch (e: Exception) {
            rootState = RootState.UNAVAILABLE
            false
        }
    }

    /**
     * 尝试对设备节点提权 chmod 666。
     * @return true=提权成功或本就可用；false=失败（UI 需提示原因）
     */
    fun grantDevice(path: String): Boolean {
        // 先直接探测：系统签名应用或节点已 666 时无需 su
        try {
            val f = java.io.File(path)
            if (f.exists() && f.canRead() && f.canWrite()) return true
        } catch (e: Exception) {
            // 继续走 su 流程
        }
        if (!isRootAvailable()) return false
        return try {
            val p = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(p.outputStream)
            os.writeBytes("chmod 666 $path\n")
            os.writeBytes("exit\n")
            os.flush()
            val code = p.waitFor()
            code == 0
        } catch (e: Exception) {
            false
        }
    }

    /** 人类可读的失败原因（供状态条/弹窗） */
    fun describeFailure(path: String): String {
        return if (!isRootAvailable()) {
            "无 root 权限（su 不可用）"
        } else {
            "su 拒绝或 chmod 失败：$path"
        }
    }
}
