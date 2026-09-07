package com.qiangyuan.emrs.serial.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import java.io.IOException

/**
 * CP210x 驱动（VID 0x10C4：0xEA60，CP2102/CP2103/CP2104/CP2105/CP2108 等）。
 *
 * 协议依据（两处独立权威源交叉核对，QA 第 1 轮 P0-1 后重写）：
 * - Linux 主线 drivers/usb/serial/cp210x.c：请求码集合 0x00~0x1E 中
 *   0x00=IFC_ENABLE、0x01=SET_BAUDDIV、0x03=SET_LINE_CTL、0x07=SET_MHS、
 *   0x1E=SET_BAUDRATE（0x20 不在其中）；SET_BAUDRATE 数据为 **4 字节小端**，
 *   wValue=0；SET_LINE_CTL 的 wValue = BITS_DATA|BITS_PARITY|BITS_STOP。
 * - mik3y usb-serial-for-android Cp21xxSerialDriver：同样只用 0x1E（4 字节小端）
 *   + 0x03，**无 SET_BAUDDIV 回退**。
 *
 * 关键说明：CP210x 不是 CDC-ACM 设备（bDeviceClass=0x00、bInterfaceClass=0xFF，
 * vendor-specific），因此**不能**使用 CDC-PSTN 类请求 0x20 SET_LINE_CODING
 * （0x20 属 class|interface|out 规范，与本机 vendor 请求码 0x41 不是一套规范）；
 * 早期版本误用 0x20 会导致 CP210x 必然 STALL、打开失败。
 *
 * 非标波特率：CP210x 内部自行分频（AN205 有专门速率表，Linux 对 <1M 的非标速率
 * 会先做 cp210x_get_an205_rate 映射）。本工程仪器 Baud 取自 BAUD_OPTIONS
 * （1200~115200，均为标准速率，误差 <0.1%），直接下发即可。
 */
class Cp210xDriver(device: UsbDevice) : UsbSerialPortBase(device) {

    companion object {

        /** bmRequestType = 0x41（vendor + interface + out，与所有 Silabs 请求配套） */
        val REQTYPE_HOST_TO_INTERFACE = 0x41

        // ---- 请求码（Linux cp210x.c "Config request codes"） ----
        const val REQ_IFC_ENABLE = 0x00
        const val REQ_SET_BAUDDIV = 0x01     // 老式分频写法，本驱动不使用，仅留档
        const val REQ_SET_LINE_CTL = 0x03
        const val REQ_SET_MHS = 0x07
        const val REQ_SET_BAUDRATE = 0x1E
        const val REQ_GET_BAUDRATE = 0x1D

        // ---- 请求参数 ----
        const val UART_ENABLE = 1
        const val UART_DISABLE = 0
        const val MODEM_DTR_ENABLE = 0x0101
        const val MODEM_RTS_ENABLE = 0x0202

        // ---- SET_LINE_CTL(0x03) 位域（Linux cp210x.c BITS_* 定义） ----
        const val BITS_DATA_MASK = 0x0F00      // 数据位在 bit8~11
        const val BITS_PARITY_MASK = 0x00F0    // 校验在 bit4~7
        const val BITS_PARITY_NONE = 0x0000
        const val BITS_PARITY_ODD = 0x0010
        const val BITS_PARITY_EVEN = 0x0020
        const val BITS_STOP_MASK = 0x000F      // 停止位在 bit0~3
        const val BITS_STOP_1 = 0x0000
        const val BITS_STOP_2 = 0x0002

        /** SET_BAUDRATE(0x1E) 的数据阶段：4 字节小端波特率（纯函数，单测用） */
        fun buildBaudRateData(baudRate: Int): ByteArray {
            val b = ByteArray(4)
            b[0] = (baudRate and 0xFF).toByte()
            b[1] = ((baudRate shr 8) and 0xFF).toByte()
            b[2] = ((baudRate shr 16) and 0xFF).toByte()
            b[3] = ((baudRate shr 24) and 0xFF).toByte()
            return b
        }

        /**
         * SET_LINE_CTL(0x03) 的 wValue：数据位<<8 | 校验<<4 | 停止位。
         * 校验沿用 SerialPortConfig 约定：0=None 1=Odd 2=Even（与 BITS_PARITY_* 的
         * 1<<4 / 2<<4 编码天然对应）。数据位仅支持 5~8，越界按 8 处理；
         * 停止位 2 → BITS_STOP_2(0x0002)，其余按 1 位。
         */
        fun buildLineControl(dataBits: Int, stopBits: Int, parity: Int): Int {
            val bits = if (dataBits in 5..8) dataBits shl 8 else 8 shl 8
            val par = when (parity) {
                1 -> BITS_PARITY_ODD
                2 -> BITS_PARITY_EVEN
                else -> BITS_PARITY_NONE
            }
            val stop = if (stopBits == 2) BITS_STOP_2 else BITS_STOP_1
            return bits or par or stop
        }
    }

    override fun openDevice(connection: UsbDeviceConnection) {
        if (connection.controlTransfer(
                REQTYPE_HOST_TO_INTERFACE, REQ_IFC_ENABLE, UART_ENABLE, 0, null, 0, CTRL_TIMEOUT_MS
            ) < 0
        ) {
            throw IOException("CP210x 启用 UART 失败")
        }
        if (connection.controlTransfer(
                REQTYPE_HOST_TO_INTERFACE, REQ_SET_MHS,
                MODEM_DTR_ENABLE or MODEM_RTS_ENABLE, 0, null, 0, CTRL_TIMEOUT_MS
            ) < 0
        ) {
            throw IOException("CP210x 设置 DTR/RTS 失败")
        }
    }

    /**
     * 波特率 + 线控：
     * 1) SET_BAUDRATE(0x1E) 下发 4 字节小端波特率（Linux/mik3y 同一条路）；
     * 2) SET_LINE_CTL(0x03) 下发数据位/校验/停止位（缺了它线控不会生效）。
     */
    override fun setBaudRate(baudRate: Int, dataBits: Int, stopBits: Int, parity: Int) {
        val conn = connection ?: throw IOException("USB 串口未打开")
        if (baudRate <= 0) throw IOException("非法波特率：$baudRate")
        val data = buildBaudRateData(baudRate)
        if (conn.controlTransfer(
                REQTYPE_HOST_TO_INTERFACE, REQ_SET_BAUDRATE, 0, 0, data, data.size, CTRL_TIMEOUT_MS
            ) < 0
        ) {
            throw IOException("CP210x 设置波特率失败：0x1E")
        }
        val lc = buildLineControl(dataBits, stopBits, parity)
        if (conn.controlTransfer(
                REQTYPE_HOST_TO_INTERFACE, REQ_SET_LINE_CTL, lc, 0, null, 0, CTRL_TIMEOUT_MS
            ) < 0
        ) {
            throw IOException("CP210x 设置线控失败：0x03")
        }
    }

    override fun close() {
        // 关闭前停掉 UART（mik3y 同做法；失败不影响连接释放）
        try {
            connection?.controlTransfer(
                REQTYPE_HOST_TO_INTERFACE, REQ_IFC_ENABLE, UART_DISABLE, 0, null, 0, CTRL_TIMEOUT_MS
            )
        } catch (e: Exception) {
            // 忽略：设备可能已拔出
        }
        super.close()
    }
}
