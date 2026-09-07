package com.qiangyuan.emrs.serial.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import java.io.IOException

/**
 * PL2303 驱动（VID 0x067B：0x2303）。
 *
 * 协议依据：Linux pl2303.c + mik3y usb-serial-for-android ProlificSerialDriver：
 * - 类请求 SET_LINE_CODING 0x20 / SET_CONTROL 0x22（reqtype 0x21 = class/interface/out）；
 * - 波特率直接写 LINE_CODING 32 位小端值（HX 系列芯片内部自行分频生成，标称值误差 <0.1%）；
 * - 打开时执行 HX 复位序列（vendor 请求 0x01：0xC0 读 / 0x40 写）。
 */
class Pl2303Driver(device: UsbDevice) : UsbSerialPortBase(device) {

    companion object {
        // bmRequestType：0x21 = class|interface|out（类请求），
        // 0x40 = vendor|host→device，0xC0 = vendor|device→host（HX 初始化序列）。
        // 用字面量是为了避开 Kotlin “const val 必须为常量表达式”的检查。
        val REQTYPE_CLASS_HOST_TO_INTERFACE = 0x21
        val REQTYPE_VENDOR_HOST_TO_DEVICE = 0x40
        val REQTYPE_VENDOR_DEVICE_TO_HOST = 0xC0

        const val VENDOR_REQUEST = 0x01          // wValue = 寄存器地址, wIndex = 值
        const val REQ_SET_LINE_CODING = 0x20
        const val REQ_SET_CONTROL = 0x22
        const val CONTROL_DTR = 0x01
        const val CONTROL_RTS = 0x02

        /** 支持的波特率公共区间（PL2303/HX 覆盖 75~12M，此处钳位到保守范围） */
        const val MIN_BAUD = 75
        const val MAX_BAUD = 921600

        /** 超界波特率钳位（纯函数便于单测） */
        fun clampBaud(baudRate: Int): Int = baudRate.coerceIn(MIN_BAUD, MAX_BAUD)
    }

    override fun openDevice(connection: UsbDeviceConnection) {
        doBlackMagic(connection)
        // 断言 DTR|RTS
        connection.controlTransfer(
            REQTYPE_CLASS_HOST_TO_INTERFACE, REQ_SET_CONTROL,
            CONTROL_DTR or CONTROL_RTS, 0, null, 0, CTRL_TIMEOUT_MS
        )
    }

    /**
     * HX 初始化序列（依据内核 pl2303.c 与 mik3y ProlificSerialDriver.doBlackMagic，
     * 顺序不可调换；末步 0x0024 为旧版 PL2303 使能写，HX 芯片上为无害寄存器）。
     */
    private fun doBlackMagic(conn: UsbDeviceConnection) {
        vendorIn(conn, 0x8484)
        vendorOut(conn, 0x0404, 0x0000)
        vendorIn(conn, 0x8484)
        vendorOut(conn, 0x0404, 0x0001)
        vendorIn(conn, 0x8383)
        vendorOut(conn, 0x0404, 0x0000)
        vendorIn(conn, 0x8383)
        vendorOut(conn, 0x0000, 0x0001)
        vendorOut(conn, 0x0001, 0x0000)
        vendorOut(conn, 0x0002, 0x0024)
    }

    private fun vendorOut(conn: UsbDeviceConnection, addr: Int, value: Int) {
        conn.controlTransfer(REQTYPE_VENDOR_HOST_TO_DEVICE, VENDOR_REQUEST, addr, value, null, 0, CTRL_TIMEOUT_MS)
    }

    private fun vendorIn(conn: UsbDeviceConnection, addr: Int) {
        val buf = ByteArray(1)
        conn.controlTransfer(REQTYPE_VENDOR_DEVICE_TO_HOST, VENDOR_REQUEST, addr, 0, buf, 1, CTRL_TIMEOUT_MS)
    }

    override fun setBaudRate(baudRate: Int, dataBits: Int, stopBits: Int, parity: Int) {
        val conn = connection ?: throw IOException("USB 串口未打开")
        val data = buildCdcLineCoding(clampBaud(baudRate), dataBits, stopBits, parity)
        if (conn.controlTransfer(REQTYPE_CLASS_HOST_TO_INTERFACE, REQ_SET_LINE_CODING, 0, 0, data, data.size, CTRL_TIMEOUT_MS) < 0) {
            throw IOException("PL2303 设置线控失败")
        }
    }
}
