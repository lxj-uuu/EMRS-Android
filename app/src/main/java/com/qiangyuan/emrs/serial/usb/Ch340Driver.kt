package com.qiangyuan.emrs.serial.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import java.io.IOException

/**
 * CH340/CH341 驱动（VID 0x1A86：0x7522/0x7523）。
 *
 * 协议依据：Linux 内核 drivers/usb/serial/ch341.c（ch341_get_divisor /
 * ch341_set_baudrate_lcr / ch341_configure，主线 2019 重实现版）：
 * - 写寄存器请求 0x9A（CH341_REQ_WRITE_REG）：分频写 0x13/0x12（REG_DIVISOR/PRESCALER），
 *   线控写 0x25/0x18（REG_LCR2/LCR，仅版本 ≥ 0x30）；
 * - 初始化 0xA1（REQ_SERIAL_INIT）、握手 0xA4（REQ_MODEM_CTRL）、版本 0x5F（REQ_READ_VERSION）；
 * - 时钟 48MHz，波特率 = 48MHz / (2^(12-3*ps-fact) * div)。
 */
class Ch340Driver(device: UsbDevice) : UsbSerialPortBase(device) {

    companion object {
        const val CLKRATE = 48000000

        // 控制请求码（ch341.c）
        const val REQ_READ_VERSION = 0x5F
        const val REQ_WRITE_REG = 0x9A
        const val REQ_SERIAL_INIT = 0xA1
        const val REQ_MODEM_CTRL = 0xA4

        // 寄存器地址（wValue 高/低字节 = 两个目标寄存器）
        const val REG_DIVISOR = 0x13
        const val REG_PRESCALER = 0x12
        const val REG_LCR2 = 0x25
        const val REG_LCR = 0x18

        // LCR 位（ch341.c 原文）
        const val LCR_ENABLE_RX = 0x80
        const val LCR_ENABLE_TX = 0x40
        const val LCR_PAR_EVEN = 0x10
        const val LCR_ENABLE_PAR = 0x08
        const val LCR_STOP_BITS_2 = 0x04
        const val LCR_CS8 = 0x03
        const val LCR_CS7 = 0x02
        const val LCR_CS6 = 0x01
        const val LCR_CS5 = 0x00

        // 量程（内核 clamp 值）：CH341_MIN_BPS = DIV_ROUND_UP(48M, 4096*256) = 46；
        // CH341_MAX_BPS = 48M / (8*2) = 3000000
        const val MIN_BPS = 46
        const val MAX_BPS = 3000000

        // bmRequestType：0x40 = vendor|host→device，0xC0 = vendor|device→host。
        // 用字面量而不用 UsbConstants.X or UsbConstants.Y，是为了避开 Kotlin
        // “const val 初始化必须是常量表达式”的检查（infix or 不一定被折叠）。
        val REQTYPE_HOST_TO_DEVICE = 0x40
        val REQTYPE_DEVICE_TO_HOST = 0xC0

        /** CH341_CLK_DIV(ps, fact) = 1 << (12 - 3*ps - fact) */
        fun clkDivOf(ps: Int, fact: Int): Int = 1 shl (12 - 3 * ps - fact)

        /** CH341_MIN_RATE(ps) = CLKRATE / (CLK_DIV(ps,1) * 512)，即 {45, 366, 2929, 23437} */
        fun minRate(ps: Int): Int = CLKRATE / (clkDivOf(ps, 1) * 512)

        /**
         * 分频计算（内核 ch341_get_divisor 忠实移植，纯函数便于单测）。
         *
         * 返回 16 位寄存器值：(0x100 - div) << 8 | fact << 2 | ps。
         * 舍入规则（与内核一致，依据见函数内注释）：
         * 1. div 对目标波特率向下取整；
         * 2. div 过小（<9）或过大（>255）时基时钟减半（fact=1→0）；
         * 3. 用 16 倍整数放大比较相邻两档误差，过半则 div+1；
         * 4. div 为偶数且 fact=1 时优先降基时钟（内核注释：使接收端对波特率误差更宽容）。
         */
        fun calcDivisor(baudRate: Int): Int? {
            val speed = baudRate.coerceIn(MIN_BPS, MAX_BPS)
            // 从高档位（ps=3）向低找第一个 speed 高于其下限的预分频档
            var ps = -1
            for (p in 3 downTo 0) {
                if (speed > minRate(p)) {
                    ps = p
                    break
                }
            }
            if (ps < 0) return null
            var fact = 1
            var clkDiv = clkDivOf(ps, fact)
            var div = CLKRATE / (clkDiv * speed)
            if (div < 9 || div > 255) {
                div /= 2
                clkDiv *= 2
                fact = 0
            }
            if (div < 2) return null
            // 误差过半进位（16 倍放大避免低速整数比较失真）
            if (16 * CLKRATE / (clkDiv * div) - 16 * speed >=
                16 * speed - 16 * CLKRATE / (clkDiv * (div + 1))
            ) {
                div++
            }
            if (fact == 1 && div % 2 == 0) {
                div /= 2
                fact = 0
            }
            return ((0x100 - div) shl 8) or (fact shl 2) or ps
        }

        /** 由寄存器值反算实际波特率（供误差校验/单测） */
        fun effectiveBaudRate(divisorVal: Int): Int {
            val div = 0x100 - (divisorVal shr 8)
            val fact = (divisorVal shr 2) and 1
            val ps = divisorVal and 3
            return CLKRATE / (clkDivOf(ps, fact) * div)
        }

        /** 线控字节 LCR = 使能收发 | 数据位 | 校验 | 停止位 */
        fun calcLcr(dataBits: Int, stopBits: Int, parity: Int): Int {
            var lcr = LCR_ENABLE_RX or LCR_ENABLE_TX
            lcr = lcr or when (dataBits) {
                8 -> LCR_CS8
                7 -> LCR_CS7
                6 -> LCR_CS6
                else -> LCR_CS5
            }
            when (parity) {
                1 -> lcr = lcr or LCR_ENABLE_PAR                          // Odd
                2 -> lcr = lcr or LCR_ENABLE_PAR or LCR_PAR_EVEN          // Even
            }
            if (stopBits == 2) lcr = lcr or LCR_STOP_BITS_2
            return lcr
        }
    }

    /** 芯片版本（0x5F 读出，决定 BIT7 行为与 LCR 写入方式） */
    private var version = 0

    override fun openDevice(connection: UsbDeviceConnection) {
        val buf = ByteArray(2)
        val r = connection.controlTransfer(
            REQTYPE_DEVICE_TO_HOST, REQ_READ_VERSION, 0, 0, buf, buf.size, CTRL_TIMEOUT_MS
        )
        if (r < 0) throw IOException("CH340 读取芯片版本失败：$r")
        version = buf[0].toInt() and 0xFF
        if (connection.controlTransfer(REQTYPE_HOST_TO_DEVICE, REQ_SERIAL_INIT, 0, 0, null, 0, CTRL_TIMEOUT_MS) < 0) {
            throw IOException("CH340 初始化失败")
        }
        // ch341_set_handshake 语义：断言 DTR|RTS（value = ~mcr = ~0x60 = 0xFF9F）
        connection.controlTransfer(REQTYPE_HOST_TO_DEVICE, REQ_MODEM_CTRL, 0xFF9F, 0, null, 0, CTRL_TIMEOUT_MS)
    }

    override fun setBaudRate(baudRate: Int, dataBits: Int, stopBits: Int, parity: Int) {
        val conn = connection ?: throw IOException("USB 串口未打开")
        var value = calcDivisor(baudRate) ?: throw IOException("CH340 不支持的波特率：$baudRate")
        // CH341A 默认缓冲到满 32 字节端点包才转发，bit7=1 取消该行为（内核：version > 0x27）
        if (version > 0x27) value = value or 0x80
        if (conn.controlTransfer(
                REQTYPE_HOST_TO_DEVICE, REQ_WRITE_REG,
                (REG_DIVISOR shl 8) or REG_PRESCALER, value, null, 0, CTRL_TIMEOUT_MS
            ) < 0
        ) {
            throw IOException("CH340 设置波特率失败")
        }
        // 版本 ≥ 0x30：线控走 LCR/LCR2 寄存器（内核 ch341_set_baudrate_lcr）
        if (version >= 0x30) {
            val lcr = calcLcr(dataBits, stopBits, parity)
            if (conn.controlTransfer(
                    REQTYPE_HOST_TO_DEVICE, REQ_WRITE_REG,
                    (REG_LCR2 shl 8) or REG_LCR, lcr, null, 0, CTRL_TIMEOUT_MS
                ) < 0
            ) {
                throw IOException("CH340 设置线控失败")
            }
        }
    }
}
