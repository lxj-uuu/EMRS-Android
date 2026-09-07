package com.qiangyuan.emrs.serial.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.qiangyuan.emrs.core.Exec
import com.qiangyuan.emrs.serial.SerialPortConfig
import com.qiangyuan.emrs.serial.SerialPortManager
import com.qiangyuan.emrs.serial.SerialTransport
import java.io.IOException

/**
 * USB-OTG 串口传输（serial/usb/UsbSerialManager.kt，USB增量）。
 *
 * 职责：
 * - 设备枚举：按 UsbSerialIds 的 VID/PID 表匹配并给出芯片名；
 * - 运行时权限：requestPermission + 动态注册 PendingIntent 广播
 *   （Android 12+ 必须 FLAG_MUTABLE，否则系统回填授权结果会抛异常）；
 * - UsbDeviceConnection 生命周期：open → claim → 配置 → 读线程 → close/release；
 * - bulk IN 轮询读线程：read 内部阻塞约 40ms（10~50ms 区间），退出可中断；
 * - 写互斥：synchronized 直写，全量语义；
 * - 拔出监听：ACTION_USB_DEVICE_DETACHED 广播 → 关闭并复用原生串口的
 *   StatusNotifier 错误通知（状态条“通信中断！”）。
 *
 * 收到的字节经 RxListener 投递给 ProtocolEngine → RxFrameParser.feed()，
 * 与原生串口走同一条协议解析路径。
 */
class UsbSerialManager(
    private val prefs: SharedPreferences
) : SerialTransport {

    companion object {
        private const val TAG = "EmrsUsbSerial"
        const val ACTION_USB_PERMISSION = "com.qiangyuan.emrs.USB_PERMISSION"
        private const val READ_TIMEOUT_MS = 40
        private const val WRITE_TIMEOUT_MS = 1000
        private const val READ_BUFFER_SIZE = 4096
    }

    /** USB 设备信息（设置页列表用；chipName=null 表示不支持） */
    data class UsbDeviceInfo(
        val vid: Int,
        val pid: Int,
        val chipName: String?,
        val displayName: String,
        val granted: Boolean
    )

    @Volatile
    private var running = false

    @Volatile
    private var port: UsbSerialPort? = null

    @Volatile
    private var connection: UsbDeviceConnection? = null

    private var device: UsbDevice? = null
    private var rxThread: Thread? = null
    private var rxListener: SerialTransport.RxListener? = null
    private val writeLock = Any()
    private var appContext: Context? = null
    private var detachReceiver: BroadcastReceiver? = null

    override val isOpen: Boolean
        get() = port != null

    /** 枚举已连接 USB 设备（含未支持的，供设置页展示芯片名/VID:PID） */
    fun listDevices(context: Context): List<UsbDeviceInfo> {
        val um = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return um.deviceList.values.map { d ->
            val chip = UsbSerialIds.chipNameFor(d.vendorId, d.productId)
            val idText = "VID:${UsbSerialIds.hex4(d.vendorId)}/PID:${UsbSerialIds.hex4(d.productId)}"
            UsbDeviceInfo(
                vid = d.vendorId,
                pid = d.productId,
                chipName = chip,
                displayName = if (chip != null) "$chip（$idText）" else "未知设备（$idText）",
                granted = um.hasPermission(d)
            )
        }
    }

    /**
     * 申请系统 USB 权限（动态注册广播接收系统回执）。
     * 注意 Android 12+（API 31）起 PendingIntent 必须 FLAG_MUTABLE。
     */
    fun requestPermission(context: Context, target: UsbDevice, callback: (Boolean) -> Unit) {
        val ctx = context.applicationContext
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        if (um.hasPermission(target)) {
            callback(true)
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                try {
                    ctx.unregisterReceiver(this)
                } catch (e: Exception) {
                    // 已注销等场景忽略
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                callback(granted)
            }
        }
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName), flags
        )
        // targetSdk 30：USB 授权为系统定向广播，动态注册无需 RECEIVER_NOT_EXPORTED 标记
        ctx.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION))
        um.requestPermission(target, pi)
    }

    /** 异步打开：枚举 → 授权（如需）→ 连接 → 配置 → 启动读线程 */
    override fun open(context: Context, callback: SerialTransport.OpenCallback?) {
        val ctx = context.applicationContext
        appContext = ctx
        Exec.background {
            closeInternal()
            val cfg = SerialPortConfig.load(prefs)
            val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            val dev = pickDevice(um, cfg)
            if (dev == null) {
                Exec.main {
                    callback?.onResult(false, "未检测到可识别的 USB 串口设备（支持 CH340/FTDI/CP210x/PL2303），请检查 OTG 线与供电")
                }
                return@background
            }
            if (!um.hasPermission(dev)) {
                Exec.main {
                    callback?.onResult(false, "等待 USB 授权，请在系统弹窗中允许")
                }
                requestPermission(ctx, dev) { granted ->
                    if (granted) {
                        Exec.background { doOpen(ctx, um, dev, cfg, callback) }
                    } else {
                        Exec.main {
                            callback?.onResult(false, "USB 授权被拒绝")
                        }
                    }
                }
                return@background
            }
            doOpen(ctx, um, dev, cfg, callback)
        }
    }

    /** 设备选择：优先最近使用过的 vid/pid，否则取第一台受支持设备 */
    private fun pickDevice(um: UsbManager, cfg: SerialPortConfig): UsbDevice? {
        if (cfg.usbVid != 0) {
            um.deviceList.values.firstOrNull {
                it.vendorId == cfg.usbVid && it.productId == cfg.usbPid
            }?.let { return it }
        }
        return um.deviceList.values.firstOrNull {
            UsbSerialIds.isSupported(it.vendorId, it.productId)
        }
    }

    private fun doOpen(
        ctx: Context,
        um: UsbManager,
        dev: UsbDevice,
        cfg: SerialPortConfig,
        callback: SerialTransport.OpenCallback?
    ) {
        try {
            val conn = um.openDevice(dev) ?: throw IOException("openDevice 失败（未授权或被占用）")
            val drv = UsbSerialIds.createDriver(dev)
            if (drv == null) {
                // 驱动实例化都失败时没人接管 conn，必须就地关闭，避免泄漏
                try {
                    conn.close()
                } catch (e: Exception) {
                    // 忽略关闭异常
                }
                throw IOException("无匹配的芯片驱动")
            }
            // 【P1-1】先把 port/connection 登记进去，再 open/setBaudRate：
            // 这两步任一步失败时，catch 里的 closeInternal() 才能走到 p.close()
            // 真正 releaseInterface + close，否则连接泄漏，拔插重试会一直
            // 报“openDevice 失败（未授权或被占用）”，只能杀进程。
            port = drv
            connection = conn
            drv.open(conn)
            drv.setBaudRate(cfg.baudRate, cfg.dataBits, cfg.stopBits, cfg.parity)
            device = dev
            // 记住最近使用的 USB 设备（下次打开优先命中）
            SerialPortConfig.load(prefs).copy(usbVid = dev.vendorId, usbPid = dev.productId).save(prefs)
            registerDetach(ctx)
            startRx()
            val chip = UsbSerialIds.chipNameFor(dev.vendorId, dev.productId) ?: "USB"
            Exec.main {
                callback?.onResult(true, "$chip ${dev.deviceName}")
            }
        } catch (e: Exception) {
            closeInternal()
            Log.w(TAG, "usb serial open failed", e)
            Exec.main {
                callback?.onResult(false, e.message ?: e.toString())
            }
        }
    }

    /** bulk IN 轮询读线程：n>0 投递 RxListener（与原生串口同一条路）；异常/断开退出 */
    private fun startRx() {
        running = true
        val t = Thread {
            val buf = ByteArray(READ_BUFFER_SIZE)
            while (running) {
                val p = port ?: break
                val n = try {
                    p.read(buf, READ_TIMEOUT_MS)
                } catch (e: Exception) {
                    -1
                }
                if (n > 0) {
                    rxListener?.onBytes(buf.copyOf(n))
                } else if (n < 0) {
                    running = false
                    Exec.main { SerialPortManager.StatusNotifier.notifySerialError() }
                    break
                } else {
                    // 【P1-2】n == 0：本轮无数据（含 bulkTransfer 超时返回的负值，
                    // 详见 UsbSerialPortBase.read 契约说明）。这里休眠 10ms 退避，
                    // 与原生 JNI 串口“EAGAIN 休眠 10ms 重试”完全同构，避免拔线后
                    // 读线程空转打满一个 CPU 核；设备拔出最终由 DETACHED 广播收口。
                    try {
                        Thread.sleep(10)
                    } catch (e: InterruptedException) {
                        running = false
                        break
                    }
                }
            }
        }
        t.name = "emrs-usb-rx"
        t.isDaemon = true
        rxThread = t
        t.start()
    }

    override fun setRxListener(listener: SerialTransport.RxListener?) {
        rxListener = listener
    }

    /** 写互斥：synchronized 直写，全量语义 */
    override fun write(bytes: ByteArray): Boolean {
        val p = port ?: return false
        return synchronized(writeLock) {
            try {
                p.write(bytes, WRITE_TIMEOUT_MS) == bytes.size
            } catch (e: Exception) {
                false
            }
        }
    }

    override fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        running = false
        // 经驱动 close 释放接口并关闭连接（releaseInterface + close）
        port?.let { p ->
            try {
                p.close()
            } catch (e: Exception) {
                // 忽略关闭异常
            }
        }
        port = null
        // 兜底：驱动尚未接管连接（例如 doOpen 早期失败）时，这里保证连接被关闭；
        // 已被驱动关闭过的连接再次 close 是安全的（异常已吞掉）
        connection?.let { c ->
            try {
                c.close()
            } catch (e: Exception) {
                // 忽略关闭异常
            }
        }
        connection = null
        rxThread?.join(500)
        rxThread = null
        unregisterDetach()
    }

    /** 拔出监听：拔出当前设备 → 关闭 + 状态条“通信中断！”（复用原生串口通知点） */
    private fun registerDetach(ctx: Context) {
        if (detachReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val d = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                if (d.vendorId == device?.vendorId && d.productId == device?.productId) {
                    close()
                    SerialPortManager.StatusNotifier.notifySerialError()
                }
            }
        }
        detachReceiver = receiver
        ctx.registerReceiver(receiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
    }

    private fun unregisterDetach() {
        detachReceiver?.let { r ->
            appContext?.let { c ->
                try {
                    c.unregisterReceiver(r)
                } catch (e: Exception) {
                    // 未注册等场景忽略
                }
            }
        }
        detachReceiver = null
    }
}
