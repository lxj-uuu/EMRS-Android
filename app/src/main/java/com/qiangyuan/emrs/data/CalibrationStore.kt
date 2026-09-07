package com.qiangyuan.emrs.data

import android.content.Context
import com.qiangyuan.emrs.algo.Formatting
import java.io.File

/**
 * bdxs.txt 标定系数库读写（架构 data/CalibrationStore.kt / §5.2）。
 * 写：标定完成 → 第 1 行 points，随后 points 行 biaoding[dot]（原样未规整值）。
 * 读：测量入口 → 第 1 行 points，随后 points 行 biaoding。
 */
object CalibrationStore {

    fun file(context: Context): File = File(DataDirManager.dataDir(context), "bdxs.txt")

    /**
     * 读取标定系数。
     * @return (points, biaoding[1..points])；失败返回 null
     */
    fun read(context: Context): Pair<Int, DoubleArray>? {
        val f = file(context)
        if (!TextFileIO.exists(f)) return null
        val lines = TextFileIO.readLines(f)
        if (lines.isEmpty()) return null
        val points = lines[0].trim().toIntOrNull() ?: return null
        if (points < 1 || points > 56) return null
        if (lines.size < points + 1) return null
        val arr = DoubleArray(57)
        for (i in 1..points) {
            val v = lines[i].trim().toDoubleOrNull() ?: return null
            arr[i] = v
        }
        return Pair(points, arr)
    }

    /** 写入标定系数（LX5.Timer2Timer 末段） */
    fun write(context: Context, points: Int, biaoding: DoubleArray): Boolean {
        val lines = ArrayList<String>(points + 1)
        lines.add(points.toString())               // 道数，原为线圈边长（源码注释）
        for (i in 1..points) {
            lines.add(Formatting.delphiReal(biaoding[i]))
        }
        return TextFileIO.writeLines(file(context), lines)
    }
}
