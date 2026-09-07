package com.qiangyuan.emrs

import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.qiangyuan.emrs.algo.ShotData
import com.qiangyuan.emrs.core.AppState
import com.qiangyuan.emrs.core.StatusHub
import com.qiangyuan.emrs.data.GzcsStore
import com.qiangyuan.emrs.protocol.EngineListener
import com.qiangyuan.emrs.protocol.FrameCodec
import com.qiangyuan.emrs.protocol.ProtocolEngine
import com.qiangyuan.emrs.protocol.ProtocolSettings
import com.qiangyuan.emrs.serial.SerialPortConfig
import com.qiangyuan.emrs.serial.SerialPortManager
import com.qiangyuan.emrs.serial.SerialTransport
import com.qiangyuan.emrs.serial.SerialTransportRouter
import com.qiangyuan.emrs.serial.usb.UsbSerialManager
import com.qiangyuan.emrs.ui.ScreenHost
import com.qiangyuan.emrs.ui.ScreenManager
import com.qiangyuan.emrs.ui.charge.ChargeScreen
import com.qiangyuan.emrs.ui.common.StatusBarView
import com.qiangyuan.emrs.ui.main.MainScreen
import com.qiangyuan.emrs.ui.selftest.SelfTestScreen
import com.qiangyuan.emrs.ui.setup.SetupScreen
import com.qiangyuan.emrs.ui.supply.SupplyScreen
import com.qiangyuan.emrs.ui.wave.WaveDebugScreen

/**
 * 唯一 Activity（架构 MainActivity.kt / T05 接线收口）。
 * - 横屏锁定（Manifest）、装载 ScreenManager 六屏；
 * - 启动序：GzcsStore.load（Form3.FormCreate 等价）→ applyPrg/ruler → 串口打开（Form1.FormCreate Comm1.Open）→ 引擎启动；
 * - EngineListener 分发：ACK→屏切换/看门狗/状态语、数据帧→SupplyScreen、超时→状态语。
 * 【USB增量】串口层经 SerialTransportRouter 收口：原生 ttyS（SerialPortManager）与
 * USB-OTG（UsbSerialManager）按 SerialPortConfig.connectionType 切换，协议/算法/UI 逻辑不变。
 */
class MainActivity : AppCompatActivity(), ScreenHost, EngineListener {

    private lateinit var screenManager: ScreenManager
    private lateinit var statusBar: StatusBarView
    private lateinit var nativeSerial: SerialPortManager
    private lateinit var usbSerial: UsbSerialManager
    private val router = SerialTransportRouter()
    private val engine = ProtocolEngine()

    // ---- ScreenHost ----

    override fun sendCommand(cmd: FrameCodec.EmrsCommand, frame: ByteArray) {
        engine.sendCommand(cmd, frame)
    }

    override fun showScreen(key: String) {
        screenManager.show(key)
    }

    override fun confirmExit() {
        AlertDialog.Builder(this)
            .setMessage(R.string.exit_confirm)
            .setPositiveButton(R.string.dialog_ok) { _, _ -> finish() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /** USB增量：返回路由器（引擎/各屏只认 SerialTransport） */
    override fun serialManager(): SerialTransport = router

    /** USB增量：USB 串口管理器（设置屏 USB 设备列表/授权用） */
    override fun usbSerialManager(): UsbSerialManager = usbSerial

    /** USB增量：按当前配置重开串口（连接方式/设备/波特率变更后由设置屏触发） */
    override fun reconnectSerial() {
        reconnectSerialInternal()
    }

    // ---- EngineListener（回调已在主线程） ----

    override fun onAck(cmd: Int) {
        when (cmd) {
            1 -> {
                // ACK(01)：form1.hide; form2.show；“正在自检...”；看门狗 1000ms 已由引擎启动
                screenManager.show(ScreenHost.KEY_SELFTEST)
                StatusHub.post(getString(R.string.status_selftesting))
            }
            2 -> {
                // ACK(02)：form2.hide; form1.show
                screenManager.show(ScreenHost.KEY_MAIN)
                StatusHub.post(getString(R.string.status_please_click))
            }
            3 -> {
                // ACK(03)：Form5 置测量态“正在供电、测量...”（引擎已复位会话并开 6000ms 看门狗）
                (screenManager.get(ScreenHost.KEY_SUPPLY) as? SupplyScreen)?.onMeasureAck()
            }
            4 -> {
                // ACK(04)：form6.show；“请设定要求的VAB，设好后点击 充电 键。”
                screenManager.show(ScreenHost.KEY_CHARGE)
                StatusHub.post(getString(R.string.status_charge_please_set))
            }
        }
    }

    override fun onStatusFrame(type: Int, vab: Double, battery: Double) {
        when (type) {
            1 -> (screenManager.get(ScreenHost.KEY_SELFTEST) as? SelfTestScreen)?.onStatusFrame(vab, battery)
            4 -> (screenManager.get(ScreenHost.KEY_CHARGE) as? ChargeScreen)?.onActualVab(vab)
        }
    }

    override fun onShot(shot: ShotData) {
        (screenManager.get(ScreenHost.KEY_SUPPLY) as? SupplyScreen)?.onShot(shot)
        statusBar.setSupplyCount(AppState.gdcs, AppState.supplyTimes)
    }

    override fun onSupplyFinished() {
        (screenManager.get(ScreenHost.KEY_SUPPLY) as? SupplyScreen)?.onSupplyFinished()
    }

    override fun onDataLoss() {
        (screenManager.get(ScreenHost.KEY_SUPPLY) as? SupplyScreen)?.onDataLoss()
    }

    override fun onRepeatFrame(gdcs: Int) {
        // 源码 goto same：只做完成判定（引擎已做），无界面动作
    }

    override fun onResendOut(cmd: Int) {
        // Timer3Timer：resend_count>=3 按命令分支（源码 case send[3]）
        when (cmd) {
            2 -> {
                screenManager.show(ScreenHost.KEY_MAIN)
                StatusHub.post(getString(R.string.status_no_ack))
                StatusHub.beep()
            }
            3 -> {
                StatusHub.post(getString(R.string.status_comm_fault))
                StatusHub.beep()
            }
            else -> {
                // 命令 01/04：回主屏“通信有故障！”
                screenManager.show(ScreenHost.KEY_MAIN)
                StatusHub.post(getString(R.string.status_comm_fault))
                StatusHub.beep()
            }
        }
    }

    override fun onWatchdogTimeout() {
        // Timer4Timer：data_come=false → 对应窗状态“通信中断！”
        StatusHub.post(getString(R.string.status_comm_lost))
        StatusHub.beep()
    }

    // ---- 生命周期 ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        UiScaleUtilInit()
        StatusHub.appContext = applicationContext

        // Form3.FormCreate 等价：读 gzcs.txt 恢复 19 项（文件缺失回退 SP/默认，不崩溃）
        GzcsStore.load(this)
        AppState.prgNo = AppState.work.prgNoInt()
        AppState.supplyTimes = AppState.work.supplyTimesInt()
        AppState.applyPrg(true)          // LX5.Timer1Timer 段（round）
        AppState.computeShjian()         // LX5.TabbedNotebook1Click 段

        statusBar = findViewById(R.id.status_bar)
        val container = findViewById<FrameLayout>(R.id.screen_container)

        // 六屏注册（Form1..Form6 创建顺序等价）
        screenManager = ScreenManager(container)
        screenManager.register(MainScreen(this, container))
        screenManager.register(SelfTestScreen(this, container))
        screenManager.register(SetupScreen(this, container))
        screenManager.register(SupplyScreen(this, container))
        screenManager.register(ChargeScreen(this, container))
        val wave = WaveDebugScreen(this, container)
        screenManager.register(wave)

        // 串口（Form1.FormCreate Comm1.Open 等价）。USB增量：
        // 原生 ttyS 与 USB-OTG 各建一个管理器，按 SerialPortConfig.connectionType 选择，
        // 经 SerialTransportRouter 交给引擎，切换连接方式时协议层无感。
        val serialPrefs = getSharedPreferences(SerialPortConfig.PREFS_NAME, MODE_PRIVATE)
        nativeSerial = SerialPortManager(serialPrefs)
        usbSerial = UsbSerialManager(serialPrefs)
        SerialPortManager.StatusNotifier.onError = {
            StatusHub.post(getString(R.string.status_comm_lost))
        }
        reconnectSerialInternal()

        // 协议引擎（ProtocolThread 单线程状态机）：只认统一传输接口（USB增量）
        engine.start(this, router, ProtocolSettings())

        screenManager.show(ScreenHost.KEY_MAIN)
    }

    /** USB增量：按配置切换原生/USB 传输并打开（原 openSerial 的扩展版，回调逻辑不变） */
    private fun reconnectSerialInternal() {
        val prefs = getSharedPreferences(SerialPortConfig.PREFS_NAME, MODE_PRIVATE)
        val cfg = SerialPortConfig.load(prefs)
        if (cfg.connectionType == SerialPortConfig.ConnectionType.USB) {
            nativeSerial.close()
            router.switchTo(usbSerial)
        } else {
            usbSerial.close()
            router.switchTo(nativeSerial)
        }
        router.open(this, object : SerialTransport.OpenCallback {
            override fun onResult(opened: Boolean, message: String) {
                StatusHub.serialText =
                    if (opened) getString(R.string.serial_opened, message)
                    else getString(R.string.serial_open_fail, message)
            }
        })
    }

    override fun onBackPressed() {
        // 返回键交当前屏；未处理 → 退出确认（Form1 BorderIcons[biSystemMenu] 语义）
        if (!screenManager.back()) {
            confirmExit()
        }
    }

    override fun onDestroy() {
        engine.stop()
        router.close()
        nativeSerial.close()
        usbSerial.close()
        SerialPortManager.StatusNotifier.onError = null
        super.onDestroy()
    }
}
