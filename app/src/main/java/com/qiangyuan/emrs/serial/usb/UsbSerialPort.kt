package com.qiangyuan.emrs.serial.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.io.IOException

/**
 * USB 转串口芯片驱动接口（serial/usb/UsbSerialPort.kt，USB增量）。
 *
 * 语义与原生 JNI 串口对齐：
 * - read 返回字节数（0 = 本轮无数据；异常抛 IOException）；
 * - write 全量语义：内部循环直到写完，返回总写入字节数（== buffer.size）或负值。
 */
interface UsbSerialPort {

    /** 打开（claim 接口 + 芯片初始化序列）；失败抛 IOException */
    fun open(connection: UsbDeviceConnection)

    /** 关闭并释放接口/连接 */
    fun close()

    /**
     * 设置波特率与线控（默认 8 数据位/1 停止位/无校验）。
     * parity 沿用 SerialPortConfig 约定：0=None 1=Odd 2=Even
     */
    fun setBaudRate(baudRate: Int, dataBits: Int = 8, stopBits: Int = 1, parity: Int = 0)

    /** 读；返回字节数，0 = 超时无数据 */
    fun read(buffer: ByteArray, timeoutMs: Int): Int

    /** 写；返回总写入字节数（全量语义），负值 = 失败 */
    fun write(buffer: ByteArray, timeoutMs: Int): Int

    /** 设备节点名（如 /dev/bus/usb/001/002） */
    fun getDeviceName(): String
}

/**
 * 7 字节 CDC LINE_CODING 结构（仅 PL2303 的 SET_LINE_CODING 0x20 使用；
 * CP210x 是 vendor-specific 设备，不走此结构，见 Cp210xDriver 说明）。
 * 布局：4 字节小端波特率 + 1 字节停止位 + 1 字节校验 + 1 字节数据位。
 */
fun buildCdcLineCoding(baudRate: Int, dataBits: Int, stopBits: Int, parity: Int): ByteArray {
    val b = ByteArray(7)
    b[0] = (baudRate and 0xFF).toByte()
    b[1] = ((baudRate shr 8) and 0xFF).toByte()
    b[2] = ((baudRate shr 16) and 0xFF).toByte()
    b[3] = ((baudRate shr 24) and 0xFF).toByte()
    b[4] = if (stopBits == 2) 2 else 0           // 0=1 位 1=1.5 位 2=2 位
    b[5] = parity.coerceIn(0, 2).toByte()        // 0=None 1=Odd 2=Even
    b[6] = if (dataBits in 5..8) dataBits.toByte() else 8.toByte()
    return b
}

/**
 * 驱动公共基类：接口声明、bulk IN/OUT 端点获取、全量写循环。
 * 各芯片差异（初始化序列/波特率寄存器/读包头剥离）由子类实现。
 */
abstract class UsbSerialPortBase(protected val device: UsbDevice) : UsbSerialPort {

    companion object {
        const val CTRL_TIMEOUT_MS = 1000

        /**
         * 连续多少次“远早于超时就返回负值”才判定为硬错误（设备拔出/连接断开）。
         * 留 3 次余量是为了兼容个别 ROM 在“本轮无数据”时也立即返回 -1 的情况。
         */
        const val MAX_CONSECUTIVE_READ_ERRORS = 3
    }

    protected var connection: UsbDeviceConnection? = null
    protected var readEndpoint: UsbEndpoint? = null
    protected var writeEndpoint: UsbEndpoint? = null
    private val writeLock = Any()
    private var consecutiveReadErrors = 0

    /**
     * 判定 bulkTransfer 结果是否为“硬错误”。
     *
     * 依据 mik3y CommonUsbSerialPort 原注释：
     * “Android error propagation is improvable: nread == -1 can be: timeout,
     *  connection lost, buffer too small, ???” —— 单看返回码区分不了超时与断线。
     * 判据（与 mik3y 的 testConnection(millis < endTime) 同思路）：
     * - 用满（或接近）超时时间才返回 → 本轮无数据，按空闲处理；
     * - 远早于超时就返回负值 → 记一次异常，连续 N 次则判定为设备已拔出/硬错误。
     *
     * @return true = 调用方应抛 IOException（消费端据此退出读线程并上报“通信中断！”）
     */
    protected fun isHardReadError(rc: Int, startNs: Long, timeoutMs: Int): Boolean {
        if (rc > 0) {
            consecutiveReadErrors = 0
            return false
        }
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        val early = elapsedMs < (timeoutMs / 2L).coerceAtLeast(1L)
        if (early) {
            consecutiveReadErrors++
            return consecutiveReadErrors >= MAX_CONSECUTIVE_READ_ERRORS
        }
        consecutiveReadErrors = 0
        return false
    }

    override fun open(connection: UsbDeviceConnection) {
        this.connection = connection
        val iface = device.getInterface(0)
        if (!connection.claimInterface(iface, true)) {
            throw IOException("claimInterface 失败：${device.deviceName}")
        }
        var bulkIn: UsbEndpoint? = null
        var bulkOut: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = ep
                else if (ep.direction == UsbConstants.USB_DIR_OUT) bulkOut = ep
            }
        }
        if (bulkIn == null || bulkOut == null) {
            throw IOException("未找到 bulk IN/OUT 端点：${device.deviceName}")
        }
        readEndpoint = bulkIn
        writeEndpoint = bulkOut
        openDevice(connection)
    }

    /** 芯片初始化序列（子类实现） */
    protected abstract fun openDevice(connection: UsbDeviceConnection)

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val conn = connection ?: throw IOException("USB 串口未打开")
        val ep = readEndpoint ?: throw IOException("USB 串口未打开")
        val start = System.nanoTime()
        val n = conn.bulkTransfer(ep, buffer, buffer.size, timeoutMs)
        // 【契约（QA 第 1 轮 P1-2 修正）】与原生 JNI read 同为三态：
        //   n > 0            = 有数据；
        //   0                = 本轮无数据（超时），消费端休眠 10ms 后重试（等价 EAGAIN）；
        //   IOException      = 硬错误/设备拔出（连续 N 次远早于超时返回负值）。
        // 之所以不用“负值即抛异常”，是因为 Android 的超时也返回负值（见 isHardReadError 说明），
        // 直接抛会让空闲期就退出读线程。
        if (isHardReadError(n, start, timeoutMs)) {
            throw IOException("USB bulk 读连续失败（rc=$n），设备可能已拔出")
        }
        return if (n > 0) n else 0
    }

    override fun write(buffer: ByteArray, timeoutMs: Int): Int {
        val conn = connection ?: return -1
        val ep = writeEndpoint ?: return -1
        synchronized(writeLock) {
            var written = 0
            while (written < buffer.size) {
                // minSdk 24：带 offset 的 bulkTransfer 为 API 26+，余量小帧复制后发送
                val chunk = if (written == 0) buffer else buffer.copyOfRange(written, buffer.size)
                val n = conn.bulkTransfer(ep, chunk, chunk.size, timeoutMs)
                if (n <= 0) return if (written > 0) written else -1
                written += n
            }
            return written
        }
    }

    override fun close() {
        val conn = connection ?: return
        connection = null
        readEndpoint = null
        writeEndpoint = null
        consecutiveReadErrors = 0
        try {
            conn.releaseInterface(device.getInterface(0))
        } catch (e: Exception) {
            // 忽略释放异常
        }
        try {
            conn.close()
        } catch (e: Exception) {
            // 忽略关闭异常
        }
    }

    override fun getDeviceName(): String = device.deviceName
}
