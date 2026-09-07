package com.qiangyuan.emrs.serial.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * USB 转串口芯片波特率分频纯函数用例（USB增量）。
 * 只验证分频/舍入/编码算法，不触碰 Android USB API。
 */
class UsbBaudCalcTest {

    private fun errPct(requested: Int, actual: Int): Double =
        Math.abs(actual - requested) / requested.toDouble() * 100.0

    // ================= CH340（依据：Linux 内核 ch341.c ch341_get_divisor） =================

    @Test
    fun ch340_9600_divisorAndError() {
        // 内核算法在 9600 下的取值：ps=2, fact=0, div=78 → 0xB202，实际 9615（误差 0.16%）
        val v = Ch340Driver.calcDivisor(9600)!!
        assertEquals(0xB202, v)
        val actual = Ch340Driver.effectiveBaudRate(v)
        assertEquals(9615, actual)
        assertTrue(errPct(9600, actual) < 0.25)
    }

    @Test
    fun ch340_115200_divisorAndError() {
        val v = Ch340Driver.calcDivisor(115200)!!
        assertEquals(0xCC03, v)
        val actual = Ch340Driver.effectiveBaudRate(v)
        assertEquals(115384, actual)
        assertTrue(errPct(115200, actual) < 0.25)
    }

    @Test
    fun ch340_300_divisorAndError() {
        val v = Ch340Driver.calcDivisor(300)!!
        assertEquals(0xD900, v)
        val actual = Ch340Driver.effectiveBaudRate(v)
        assertEquals(300, actual)
        assertTrue(errPct(300, actual) < 0.25)
    }

    @Test
    fun ch340_rangeClamp() {
        // 量程外钳位到 [46, 3000000]，与内核 clamp_val 行为一致
        assertEquals(Ch340Driver.calcDivisor(46), Ch340Driver.calcDivisor(1))
        assertEquals(Ch340Driver.calcDivisor(3000000), Ch340Driver.calcDivisor(4000000))
        // 满档 3M：ps=3, fact=0, div=2 → 0xFE03，实际 3000000
        val v = Ch340Driver.calcDivisor(3000000)!!
        assertEquals(0xFE03, v)
        assertEquals(3000000, Ch340Driver.effectiveBaudRate(v))
    }

    @Test
    fun ch340_lcrEncoding() {
        assertEquals(0xC3, Ch340Driver.calcLcr(8, 1, 0))   // RX|TX|CS8
        assertEquals(0xCB, Ch340Driver.calcLcr(8, 1, 1))   // + 奇校验
        assertEquals(0xDB, Ch340Driver.calcLcr(8, 1, 2))   // + 偶校验
        assertEquals(0xC7, Ch340Driver.calcLcr(8, 2, 0))   // + 2 停止位
        assertEquals(0xC2, Ch340Driver.calcLcr(7, 1, 0))   // CS7
    }

    // ================= FTDI（依据：mik3y FtdiSerialDriver.setBaudrate） =================

    @Test
    fun ftdi_9600_subdivisorHalf() {
        // 24M/9600=2500 → div=312, sub=4(0.5) → value=0x4138；wIndex=1（端口 0）
        val d = FtdiDriver.calcDivisor(9600)!!
        assertEquals(312, d.divisor)
        assertEquals(4, d.subdivisor)
        assertEquals(0x4138, d.value)
        assertEquals(1, d.index)                 // P1-3：非 H 系列也要带端口号
        assertEquals(9600, d.effectiveBaudRate)
    }

    @Test
    fun ftdi_115200_noSubdivisor() {
        val d = FtdiDriver.calcDivisor(115200)!!
        assertEquals(26, d.divisor)
        assertEquals(0, d.subdivisor)
        assertEquals(26, d.value)
        assertEquals(1, d.index)                 // P1-3
        assertEquals(115385, d.effectiveBaudRate)   // 误差 0.16%，远小于 3%
    }

    @Test
    fun ftdi_300_lowBaud() {
        // 300 波特：div=10000（14 位内），实际 300
        val d = FtdiDriver.calcDivisor(300)!!
        assertEquals(10000, d.divisor)
        assertEquals(0x2710, d.value)
        assertEquals(1, d.index)                 // P1-3
        assertEquals(300, d.effectiveBaudRate)
    }

    @Test
    fun ftdi_allConfigBaudsCarryPortBit() {
        // 覆盖缺口②：SerialPortConfig.BAUD_OPTIONS 全部 8 档都要带端口号位，
        // 且子分频只编码在 wValue 的 bit14-15（不占 wIndex bit0），故不影响波特率
        for (baud in intArrayOf(1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200)) {
            val d = FtdiDriver.calcDivisor(baud)
            assertTrue("baud=$baud 应受支持", d != null)
            assertEquals("baud=$baud 端口号位", 1, d!!.index and 0x01)
            assertEquals("baud=$baud 非 H 系列高字节应为 0", 0, d.index and 0xFF00)
            assertTrue("baud=$baud 误差应 <1%", errPct(baud, d.effectiveBaudRate) < 1.0)
        }
    }

    @Test
    fun ftdi_unsupportedRates() {
        assertNull(FtdiDriver.calcDivisor(100))      // 整数分频 30000 > 0x3FFF
        assertNull(FtdiDriver.calcDivisor(4000000))  // 超过 3.5M 上限
        assertNull(FtdiDriver.calcDivisor(2600000))  // 固定 3M 档误差 15% > 3.1%
    }

    @Test
    fun ftdi_indexEncoding() {
        // 14400：div=208, sub=3 → index bit0=1（子分频 3/5/6/7 借 wIndex bit0 编码）
        val d = FtdiDriver.calcDivisor(14400)!!
        assertEquals(208, d.divisor)
        assertEquals(3, d.subdivisor)
        assertEquals(1, d.index)
        // H 系列：子分频位左移到高字节，低字节仍为端口号 1 → 0x0101
        val h = FtdiDriver.calcDivisor(14400, withPort = true)!!
        assertEquals(0x0101, h.index)
    }

    @Test
    fun ftdi_dataConfigEncoding() {
        assertEquals(0x0008, FtdiDriver.calcDataConfig(8, 1, 0))
        assertEquals(0x0108, FtdiDriver.calcDataConfig(8, 1, 1))
        assertEquals(0x0208, FtdiDriver.calcDataConfig(8, 1, 2))
        assertEquals(0x1008, FtdiDriver.calcDataConfig(8, 2, 0))
        assertEquals(0x0007, FtdiDriver.calcDataConfig(7, 1, 0))
        assertNull(FtdiDriver.calcDataConfig(5, 1, 0))   // 5 数据位不支持
        assertNull(FtdiDriver.calcDataConfig(8, 3, 0))   // 非法停止位
    }

    // ================= CP210x（依据：Linux cp210x.c + mik3y Cp21xxSerialDriver） =================

    @Test
    fun cp210x_requestCodesAreVendorSpecific() {
        // P0-1 回归防护：CP210x 是 vendor-specific 设备，必须走 Silabs 私有请求，
        // 绝不能用 CDC-PSTN 的 0x20 SET_LINE_CODING（设备会 STALL）
        assertEquals(0x00, Cp210xDriver.REQ_IFC_ENABLE)
        assertEquals(0x03, Cp210xDriver.REQ_SET_LINE_CTL)
        assertEquals(0x07, Cp210xDriver.REQ_SET_MHS)
        assertEquals(0x1E, Cp210xDriver.REQ_SET_BAUDRATE)
        assertEquals(0x41, Cp210xDriver.REQTYPE_HOST_TO_INTERFACE)   // vendor|interface|out
        assertTrue("CP210x 不得使用 CDC 类请求", Cp210xDriver.REQ_SET_BAUDRATE != 0x20)
    }

    @Test
    fun cp210x_baudRateDataIsLittleEndian32() {
        // SET_BAUDRATE(0x1E) 数据阶段 = 4 字节小端波特率
        val b = Cp210xDriver.buildBaudRateData(9600)          // 0x00002580
        assertEquals(4, b.size)
        assertEquals(0x80.toByte(), b[0])
        assertEquals(0x25.toByte(), b[1])
        assertEquals(0x00.toByte(), b[2])
        assertEquals(0x00.toByte(), b[3])
        val b2 = Cp210xDriver.buildBaudRateData(115200)       // 0x0001C200
        assertEquals(0x00.toByte(), b2[0])
        assertEquals(0xC2.toByte(), b2[1])
        assertEquals(0x01.toByte(), b2[2])
        assertEquals(0x00.toByte(), b2[3])
    }

    @Test
    fun cp210x_lineControlEncoding() {
        // SET_LINE_CTL(0x03)：数据位<<8 | 校验<<4 | 停止位（Linux BITS_* 定义）
        assertEquals(0x0800, Cp210xDriver.buildLineControl(8, 1, 0))   // 8N1
        assertEquals(0x0810, Cp210xDriver.buildLineControl(8, 1, 1))   // 8O1（Odd = 1<<4）
        assertEquals(0x0820, Cp210xDriver.buildLineControl(8, 1, 2))   // 8E1（Even = 2<<4）
        assertEquals(0x0802, Cp210xDriver.buildLineControl(8, 2, 0))   // 8N2（2 停止位 = 0x0002）
        assertEquals(0x0700, Cp210xDriver.buildLineControl(7, 1, 0))   // 7N1
        assertEquals(0x0800, Cp210xDriver.buildLineControl(9, 1, 0))   // 越界数据位回退 8
    }

    /** 7 字节 CDC LINE_CODING 结构（PL2303 的 SET_LINE_CODING 0x20 使用） */
    @Test
    fun cdc_lineCodingLayout() {
        // 9600 = 0x00002580 → 小端 80 25 00 00；8N1 → 0/0/8
        val lc = buildCdcLineCoding(9600, 8, 1, 0)
        assertEquals(7, lc.size)
        assertEquals(0x80.toByte(), lc[0])
        assertEquals(0x25.toByte(), lc[1])
        assertEquals(0x00.toByte(), lc[2])
        assertEquals(0x00.toByte(), lc[3])
        assertEquals(0x00.toByte(), lc[4])   // 1 停止位
        assertEquals(0x00.toByte(), lc[5])   // 无校验
        assertEquals(0x08.toByte(), lc[6])   // 8 数据位
        // 115200 = 0x0001C200 → 小端 00 C2 01 00；2 停止位
        val lc2 = buildCdcLineCoding(115200, 8, 2, 0)
        assertEquals(0x00.toByte(), lc2[0])
        assertEquals(0xC2.toByte(), lc2[1])
        assertEquals(0x01.toByte(), lc2[2])
        assertEquals(0x02.toByte(), lc2[4])
        // 偶校验
        assertEquals(0x02.toByte(), buildCdcLineCoding(9600, 8, 1, 2)[5])
    }

    // ================= PL2303 =================

    @Test
    fun pl2303_baudClamp() {
        assertEquals(9600, Pl2303Driver.clampBaud(9600))
        assertEquals(75, Pl2303Driver.clampBaud(50))
        assertEquals(921600, Pl2303Driver.clampBaud(15000000))
    }

    // ================= VID/PID 清单 =================

    @Test
    fun usbIds_tableComplete() {
        val all = UsbSerialIds.all()
        assertEquals(8, all.size)   // CH340×2 + FTDI×4 + CP210x×1 + PL2303×1
        assertTrue(UsbSerialIds.isSupported(0x1A86, 0x7523))
        assertTrue(UsbSerialIds.isSupported(0x0403, 0x6001))
        assertTrue(UsbSerialIds.isSupported(0x10C4, 0xEA60))
        assertTrue(UsbSerialIds.isSupported(0x067B, 0x2303))
        assertEquals("CH340", UsbSerialIds.chipNameFor(0x1A86, 0x7523))
        assertNull(UsbSerialIds.chipNameFor(0x1A86, 0x7524))
    }

    /**
     * 覆盖缺口③：res/xml/device_filter.xml 与本清单的一致性（新增芯片必须两处同步，
     * 否则会出现“App 内能用但插线不自动唤起”或反之）。这里把 XML 里的十进制
     * VID/PID 抄成期望值，与代码清单逐项比对。
     */
    @Test
    fun usbIds_matchesDeviceFilterXml() {
        val fromXml = listOf(
            6790 to 29986,   // 0x1A86:0x7522 CH340
            6790 to 29987,   // 0x1A86:0x7523 CH340
            1027 to 24577,   // 0x0403:0x6001 FT232R
            1027 to 24592,   // 0x0403:0x6010 FT2232H
            1027 to 24596,   // 0x0403:0x6014 FT232H
            1027 to 24597,   // 0x0403:0x6015 FT231X
            4292 to 60000,   // 0x10C4:0xEA60 CP210x
            1659 to 8963     // 0x067B:0x2303 PL2303
        )
        val fromCode = UsbSerialIds.all().map { it.vid to it.pid }
        assertEquals("数量应与 device_filter.xml 一致", fromXml.size, fromCode.size)
        assertTrue("代码清单应覆盖 XML 全部条目", fromCode.containsAll(fromXml))
        fromXml.forEach { (vid, pid) ->
            assertTrue("XML 条目 VID=$vid PID=$pid 应受支持", UsbSerialIds.isSupported(vid, pid))
        }
    }
}
