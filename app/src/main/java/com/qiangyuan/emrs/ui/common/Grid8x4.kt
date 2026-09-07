package com.qiangyuan.emrs.ui.common

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.GridLayout
import android.widget.TextView

/**
 * 供电电流 8×4 表格控件（架构 ui/common/Grid8x4.kt，等价 Form5.StringGrid2）。
 * 32 个单元格（列 0..7 × 行 0..3），按 AppState.gridCells 刷新/复位。
 */
class Grid8x4 @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GridLayout(context, attrs) {

    private val cells = Array(32) { TextView(context) }

    init {
        columnCount = 8
        rowCount = 4
        setBackgroundColor(Color.WHITE)
        val pad = (resources.displayMetrics.density * 2).toInt()
        for (r in 0 until 4) {
            for (c in 0 until 8) {
                val tv = cells[r * 8 + c]
                tv.textSize = 15f
                tv.setTextColor(Color.BLACK)
                tv.gravity = Gravity.CENTER
                tv.setBackgroundColor(Color.parseColor("#F4F4F4"))
                tv.setPadding(pad, pad, pad, pad)
                val p = GridLayout.LayoutParams()
                p.width = 0
                p.height = GridLayout.LayoutParams.WRAP_CONTENT
                p.columnSpec = GridLayout.spec(c, 1f)
                p.rowSpec = GridLayout.spec(r, 1f)
                p.setMargins(1, 1, 1, 1)
                addView(tv, p)
            }
        }
        clear()
    }

    /** 从 AppState.gridCells 全量刷新 */
    fun refresh() {
        val st = com.qiangyuan.emrs.core.AppState
        for (i in 0 until 32) {
            cells[i].text = st.gridCells[i] ?: ""
        }
    }

    /** 清空（等价 双重循环 Cells[i,j]:=''） */
    fun clear() {
        for (i in 0 until 32) {
            cells[i].text = ""
        }
    }
}
