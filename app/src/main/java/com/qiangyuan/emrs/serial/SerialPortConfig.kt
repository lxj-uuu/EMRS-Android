package com.qiangyuan.emrs.serial

/**
 * 串口配置（架构 serial/SerialPortConfig.kt / §3.1）。
 * 默认 9600/8/N/1（Delphi TComm 控件默认值，源码未显式设置——规格书问 1，全部做成可配置）。
 *
 * 【USB增量】新增 connectionType（NATIVE/USB）与最近使用的 USB 设备 vid/pid；
 * 旧版本 SharedPreferences 缺失新键时按默认 NATIVE 处理；
 * 恢复默认（SerialPortConfig()）即回到原生串口并清空 USB 设备记忆。
 */
data class SerialPortConfig(
    val devicePath: String = DEFAULT_PATH,
    val baudRate: Int = 9600,
    val dataBits: Int = 8,      // 7|8
    val parity: Int = 0,        // 0=None 1=Odd 2=Even
    val stopBits: Int = 1,      // 1|2
    val connectionType: ConnectionType = ConnectionType.NATIVE,
    val usbVid: Int = 0,        // 最近使用的 USB 设备（0 = 未记录）
    val usbPid: Int = 0
) {

    /** 连接方式（USB增量） */
    enum class ConnectionType { NATIVE, USB }

    /** 序列化到 SharedPreferences */
    fun save(prefs: android.content.SharedPreferences) {
        prefs.edit()
            .putString(KEY_DEVICE, devicePath)
            .putInt(KEY_BAUD, baudRate)
            .putInt(KEY_DATA, dataBits)
            .putInt(KEY_PARITY, parity)
            .putInt(KEY_STOP, stopBits)
            .putString(KEY_CONN_TYPE, connectionType.name)
            .putInt(KEY_USB_VID, usbVid)
            .putInt(KEY_USB_PID, usbPid)
            .apply()
    }

    companion object {
        const val DEFAULT_PATH = "/dev/ttyS0"
        const val PREFS_NAME = "emrs_serial"
        const val KEY_DEVICE = "serial_device"
        const val KEY_BAUD = "serial_baud"
        const val KEY_DATA = "serial_data"
        const val KEY_PARITY = "serial_parity"
        const val KEY_STOP = "serial_stop"
        const val KEY_CONN_TYPE = "serial_conn_type"   // USB增量
        const val KEY_USB_VID = "serial_usb_vid"       // USB增量
        const val KEY_USB_PID = "serial_usb_pid"       // USB增量

        /** 常用波特率选项 */
        val BAUD_OPTIONS = intArrayOf(1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200)

        /** 常用默认路径清单（架构 §3.1） */
        val COMMON_PATHS = arrayOf(
            "/dev/ttyS0", "/dev/ttyS1", "/dev/ttyS2", "/dev/ttyS3", "/dev/ttyS4",
            "/dev/ttyMT0", "/dev/ttyMT1", "/dev/ttyUSB0", "/dev/ttyACM0"
        )

        fun load(prefs: android.content.SharedPreferences): SerialPortConfig {
            // 旧版本无 KEY_CONN_TYPE 时安全回退 NATIVE（USB增量）
            val connType = try {
                prefs.getString(KEY_CONN_TYPE, ConnectionType.NATIVE.name)
                    ?.let { ConnectionType.valueOf(it) }
            } catch (e: IllegalArgumentException) {
                null
            } ?: ConnectionType.NATIVE
            return SerialPortConfig(
                devicePath = prefs.getString(KEY_DEVICE, DEFAULT_PATH) ?: DEFAULT_PATH,
                baudRate = prefs.getInt(KEY_BAUD, 9600),
                dataBits = prefs.getInt(KEY_DATA, 8),
                parity = prefs.getInt(KEY_PARITY, 0),
                stopBits = prefs.getInt(KEY_STOP, 1),
                connectionType = connType,
                usbVid = prefs.getInt(KEY_USB_VID, 0),
                usbPid = prefs.getInt(KEY_USB_PID, 0)
            )
        }
    }
}
