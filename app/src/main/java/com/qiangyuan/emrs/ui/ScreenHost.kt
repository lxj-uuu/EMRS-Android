package com.qiangyuan.emrs.ui

import com.qiangyuan.emrs.protocol.FrameCodec
import com.qiangyuan.emrs.serial.SerialTransport
import com.qiangyuan.emrs.serial.usb.UsbSerialManager

/**
 * 屏 → Activity 的能力接口（架构 T05 接线：各屏不发命令/不切屏，统一经 MainActivity 收口）。
 * 【USB增量】serialManager() 返回统一传输接口（原生/USB 由 MainActivity 路由）；
 * 新增 usbSerialManager()（USB 设备列表/授权）与 reconnectSerial()（配置变更后重连）。
 */
interface ScreenHost {

    companion object {
        // 六屏 key（ScreenManager 注册用）
        const val KEY_MAIN = "main"
        const val KEY_SELFTEST = "selftest"
        const val KEY_SETUP = "setup"
        const val KEY_SUPPLY = "supply"
        const val KEY_CHARGE = "charge"
        const val KEY_WAVE = "wave"
    }

    /** 经 ProtocolEngine 发命令帧（等价 Delphi 各按钮 Comm1.write + waiting_ack 段） */
    fun sendCommand(cmd: FrameCodec.EmrsCommand, frame: ByteArray)

    /** 切换到指定屏 */
    fun showScreen(key: String)

    /** 退出确认对话框（Form1.Button6） */
    fun confirmExit()

    /** 当前活动串口传输（原生 ttyS 或 USB-OTG，经路由器，USB增量） */
    fun serialManager(): SerialTransport

    /** USB-OTG 串口管理器（设置屏 USB 设备列表/授权用，USB增量） */
    fun usbSerialManager(): UsbSerialManager

    /** 按当前配置重开串口（连接方式/设备/波特率变更后调用，USB增量） */
    fun reconnectSerial()
}
