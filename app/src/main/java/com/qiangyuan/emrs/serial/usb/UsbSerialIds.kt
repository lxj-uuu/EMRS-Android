package com.qiangyuan.emrs.serial.usb

import android.hardware.usb.UsbDevice

/**
 * 支持的 USB 转串口芯片 VID/PID 清单（USB增量）。
 * 与 res/xml/device_filter.xml（十进制）保持一致，新增芯片须两处同步。
 */
object UsbSerialIds {

    const val VID_CH340 = 0x1A86
    val PIDS_CH340 = intArrayOf(0x7522, 0x7523)
    const val VID_FTDI = 0x0403
    val PIDS_FTDI = intArrayOf(0x6001, 0x6010, 0x6014, 0x6015)
    const val VID_CP210X = 0x10C4
    val PIDS_CP210X = intArrayOf(0xEA60)
    const val VID_PL2303 = 0x067B
    val PIDS_PL2303 = intArrayOf(0x2303)

    data class Entry(val vid: Int, val pid: Int, val chipName: String)

    /** 全量清单（设备过滤器/文档/测试对照用） */
    fun all(): List<Entry> {
        val list = ArrayList<Entry>()
        PIDS_CH340.forEach { list.add(Entry(VID_CH340, it, "CH340")) }
        PIDS_FTDI.forEach { list.add(Entry(VID_FTDI, it, "FTDI")) }
        PIDS_CP210X.forEach { list.add(Entry(VID_CP210X, it, "CP210x")) }
        PIDS_PL2303.forEach { list.add(Entry(VID_PL2303, it, "PL2303")) }
        return list
    }

    /** 按 VID/PID 查芯片名；未支持返回 null */
    fun chipNameFor(vid: Int, pid: Int): String? =
        all().firstOrNull { it.vid == vid && it.pid == pid }?.chipName

    fun isSupported(vid: Int, pid: Int): Boolean = chipNameFor(vid, pid) != null

    /** 按 VID/PID 实例化对应芯片驱动 */
    fun createDriver(device: UsbDevice): UsbSerialPort? {
        val vid = device.vendorId
        val pid = device.productId
        return when {
            vid == VID_CH340 && PIDS_CH340.contains(pid) -> Ch340Driver(device)
            vid == VID_FTDI && PIDS_FTDI.contains(pid) -> FtdiDriver(device)
            vid == VID_CP210X && PIDS_CP210X.contains(pid) -> Cp210xDriver(device)
            vid == VID_PL2303 && PIDS_PL2303.contains(pid) -> Pl2303Driver(device)
            else -> null
        }
    }

    fun hex4(v: Int): String = String.format("%04X", v)
}
