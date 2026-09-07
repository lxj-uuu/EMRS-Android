package com.qiangyuan.emrs.algo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Delphi 数值文本格式近似 单测（Formatting.kt）。
 */
class FormattingTest {

    @Test
    fun delphiReal_scientific() {
        assertEquals("8.00000000000000E-0002", Formatting.delphiReal(0.08))
        assertEquals("2.00000000000000E-0002", Formatting.delphiReal(0.02))
        assertEquals("-3.52000000000000E-0001", Formatting.delphiReal(-0.352))
        assertEquals("0.00000000000000E+0000", Formatting.delphiReal(0.0))
        assertEquals("1.00000000000000E+0002", Formatting.delphiReal(100.0))
    }

    @Test
    fun floatToStr_general() {
        assertEquals("0", Formatting.floatToStr(0.0))
        assertEquals("4", Formatting.floatToStr(4.0))
        assertEquals("48.53", Formatting.floatToStr(48.53))
        assertEquals("1.234567", Formatting.floatToStr(1.234567))
    }

    @Test
    fun trunc_towardZero() {
        assertEquals(3L, Formatting.trunc(3.7))
        assertEquals(-3L, Formatting.trunc(-3.7))
        assertEquals(0L, Formatting.trunc(0.9))
    }
}
