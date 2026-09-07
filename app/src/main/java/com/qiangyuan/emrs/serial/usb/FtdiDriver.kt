package com.qiangyuan.emrs.serial.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import java.io.IOException

/**
 * FTDI 驱动（VID 0x0403：0x6001/0x6010/0x6014/0x6015）。
 *
 * 协议依据：FTDI SIO 厂商请求（libftdi / Linux ftdi_sio /
 * mik3y usb-serial-for-android FtdiSerialDriver.setBaudrate，已逐条核对）：
 * - RESET 0x00 / SET_MODEM_CONTROL 0x01 / SET_FLOW_CONTROL 0x02 /
 *   SET_BAUD_RATE 0x03 / SET_DATA 0x04（reqtype 0x40）；
 * - 基准 48MHz/16 = 3MHz，14 位整数分频 + 3 位子分频（1/8 步进，编码于
 *   wValue 高 2 位 + wIndex bit0）；
 * - bulk IN 每个端点最大包长前 2 字节为 modem/line status 头，读取时剥离。
 */
class FtdiDriver(device: UsbDevice) : UsbSerialPortBase(device) {

    companion object {
        // 0x40 = vendor|host→device（见 Ch340Driver 同类注释：用字面量避开 const 检查）
        val REQTYPE_HOST_TO_DEVICE = 0x40

        const val REQ_RESET = 0x00
        const val REQ_MODEM_CONTROL = 0x01
        const val REQ_SET_FLOW_CONTROL = 0x02
        const val REQ_SET_BAUD_RATE = 0x03
        const val REQ_SET_DATA = 0x04

        const val RESET_ALL = 0x00
        const val MODEM_DTR_ENABLE = 0x0101
        const val MODEM_RTS_ENABLE = 0x0202

        const val READ_HEADER_LENGTH = 2
        private const val READ_CHUNK = 4096

        /** 分频结果（value/index 即 SET_BAUD_RATE 0x03 的 wValue/wIndex） */
        data class Divisor(
            val divisor: Int,
            val subdivisor: Int,
            val value: Int,
            val index: Int,
            val effectiveBaudRate: Int
        )

        /**
         * 波特率分频（mik3y FtdiSerialDriver.setBaudrate 忠实移植，纯函数便于单测）。
         *
         * d = round(24MHz / baud)（17 位：14 位整数 + 3 位子分频）；
         * 舍入：+1 右移 1 位即四舍五入（round-half-up）；
         * 误差 ≥3.1% 或整数部分 >0x3FFF（约低于 183 波特）视为不支持；
         * 子分频编码：4→value|0x4000(0.5)、2→0x8000(0.25)、1→0xC000(0.125)、
         * 3/5/6/7→另置 index bit0（0.375/0.625/0.75/0.875）；
         * 最终 wIndex 一律带上端口号 1（见函数末尾注释）。
         * withPort：H 系列（FT2232H/FT4232H/FT232H）wIndex 需把子分频位左移到高字节。
         */
        fun calcDivisor(baudRate: Int, withPort: Boolean = false): Divisor? {
            if (baudRate <= 0) return null
            var divisor: Int
            var subdivisor: Int
            val effective: Int
            if (baudRate > 3500000) {
                return null
            } else if (baudRate >= 2500000) {
                divisor = 0
                subdivisor = 0
                effective = 3000000
            } else if (baudRate >= 1750000) {
                divisor = 1
                subdivisor = 0
                effective = 2000000
            } else {
                var d = (24000000 shl 1) / baudRate
                d = (d + 1) shr 1                     // 四舍五入
                subdivisor = d and 0x07
                divisor = d shr 3
                if (divisor > 0x3fff) return null     // 超出 14 位整数分频
                effective = ((24000000 shl 1) / ((divisor shl 3) + subdivisor) + 1) shr 1
            }
            val err = Math.abs(1.0 - effective / baudRate.toDouble())
            if (err >= 0.031) return null             // 偏差超过 3%
            var value = divisor
            var index = 0
            when (subdivisor) {
                4 -> value = value or 0x4000
                2 -> value = value or 0x8000
                1 -> value = value or 0xC000
                3 -> index = index or 1
                5 -> {
                    value = value or 0x4000
                    index = index or 1
                }
                6 -> {
                    value = value or 0x8000
                    index = index or 1
                }
                7 -> {
                    value = value or 0xC000
                    index = index or 1
                }
            }
            // 【P1-3】wIndex 端口号：内核 ftdi_sio 用 priv->interface、mik3y 非 H 分支
            // 写 index |= 1，两者等价（单口/第一口 = 1）。H 系列还需把子分频位放到高字节。
            // 对常用波特率（子分频只编码在 wValue 的 bit14-15）而言 index 写 0 或 1
            // 对波特率数值零影响，但双口芯片（FT2232C/H 等）严格依赖它选通道，故按规范置 1。
            index = if (withPort) (index shl 8) or 1 else index or 1
            return Divisor(divisor, subdivisor, value, index, effective)
        }

        /** SET_DATA(0x04) 的 wValue 编码；5/6 数据位与 1.5 停止位不支持，返回 null */
        fun calcDataConfig(dataBits: Int, stopBits: Int, parity: Int): Int? {
            if (dataBits != 7 && dataBits != 8) return null
            var config = dataBits
            when (parity) {
                0 -> {}
                1 -> config = config or 0x100         // Odd
                2 -> config = config or 0x200         // Even
                else -> return null
            }
            when (stopBits) {
                1 -> {}
                2 -> config = config or 0x1000
                else -> return null
            }
            return config
        }
    }

    /** H 系列（bcdDevice 高字节 7/8/9）SET_BAUD_RATE 的 index 需带端口号 */
    private var baudRateWithPort = false

    /** 剥头用暂存缓冲（与管理器读缓冲同为 4096，剥头后必然放得下） */
    private val staging = ByteArray(READ_CHUNK)

    override fun openDevice(connection: UsbDeviceConnection) {
        // bcdDevice 高字节 7/8/9 = H 系列（mik3y 同法）；双接口芯片（FT2232C/H）同理
        val raw = connection.rawDescriptors ?: throw IOException("读取 FTDI 描述符失败")
        if (raw.size < 14) throw IOException("FTDI 描述符过短")
        val deviceType = raw[13].toInt() and 0xFF
        baudRateWithPort = deviceType == 7 || deviceType == 8 || deviceType == 9 ||
                device.interfaceCount > 1
        if (connection.controlTransfer(REQTYPE_HOST_TO_DEVICE, REQ_RESET, RESET_ALL, 1, null, 0, CTRL_TIMEOUT_MS) < 0) {
            throw IOException("FTDI 复位失败")
        }
        if (connection.controlTransfer(
                REQTYPE_HOST_TO_DEVICE, REQ_MODEM_CONTROL,
                MODEM_DTR_ENABLE or MODEM_RTS_ENABLE, 1, null, 0, CTRL_TIMEOUT_MS
            ) < 0
        ) {
            throw IOException("FTDI 设置 DTR/RTS 失败")
        }
        // 无流控（wValue=0）
        if (connection.controlTransfer(REQTYPE_HOST_TO_DEVICE, REQ_SET_FLOW_CONTROL, 0, 1, null, 0, CTRL_TIMEOUT_MS) < 0) {
            throw IOException("FTDI 设置流控失败")
        }
    }

    override fun setBaudRate(baudRate: Int, dataBits: Int, stopBits: Int, parity: Int) {
        val conn = connection ?: throw IOException("USB 串口未打开")
        val d = calcDivisor(baudRate, baudRateWithPort) ?: throw IOException("FTDI 不支持的波特率：$baudRate")
        if (conn.controlTransfer(REQTYPE_HOST_TO_DEVICE, REQ_SET_BAUD_RATE, d.value, d.index, null, 0, CTRL_TIMEOUT_MS) < 0) {
            throw IOException("FTDI 设置波特率失败")
        }
        val config = calcDataConfig(dataBits, stopBits, parity) ?: throw IOException("FTDI 不支持的线控参数")
        if (conn.controlTransfer(REQTYPE_HOST_TO_DEVICE, REQ_SET_DATA, config, 1, null, 0, CTRL_TIMEOUT_MS) < 0) {
            throw IOException("FTDI 设置线控失败")
        }
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val conn = connection ?: throw IOException("USB 串口未打开")
        val ep = readEndpoint ?: throw IOException("USB 串口未打开")
        val start = System.nanoTime()
        val n = conn.bulkTransfer(ep, staging, staging.size, timeoutMs)
        // 与基类同构的三态契约（超时返回负值，连续早退才是硬错误）
        if (isHardReadError(n, start, timeoutMs)) {
            throw IOException("FTDI bulk 读连续失败（rc=$n），设备可能已拔出")
        }
        if (n <= 0) return 0
        // 按端点最大包长分包，剥离每包前 2 字节 modem/line status 头（mik3y readFilter 语义）
        val maxPacketSize = ep.maxPacketSize
        var destPos = 0
        var srcPos = 0
        while (srcPos < n) {
            val end = minOf(srcPos + maxPacketSize, n)
            val len = end - srcPos - READ_HEADER_LENGTH
            if (len < 0) return 0                  // 异常短包（不足状态头），按无数据处理
            val copy = minOf(len, buffer.size - destPos)
            if (copy > 0) System.arraycopy(staging, srcPos + READ_HEADER_LENGTH, buffer, destPos, copy)
            destPos += len
            srcPos += maxPacketSize
        }
        return destPos
    }
}
