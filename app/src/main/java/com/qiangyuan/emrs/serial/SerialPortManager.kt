package com.qiangyuan.emrs.serial

import android.content.Context
import android.content.SharedPreferences
import com.qiangyuan.emrs.core.Exec

/**
 * 串口生命周期管理（架构 serial/SerialPortManager.kt / §3.3）。
 * - open：RootHelper 提权后 JNI open，启动 Rx 读线程（读→投递，不在读线程解析）；
 * - write：synchronized 直写（命令都 ≤8B，时序由 ProtocolEngine 控制，架构 §3.4）；
 * 【USB增量】原嵌套 RxListener/OpenCallback 上移为 SerialTransport 嵌套接口，
 * 本类改为实现 SerialTransport，打开/读写/读线程逻辑与原版完全一致；
 * USB 侧（UsbSerialManager）复用 StatusNotifier 错误通知点。
 */
class SerialPortManager(
    private val prefs: SharedPreferences
) : SerialTransport {

    @Volatile
    private var fd: Int = -1

    @Volatile
    private var running: Boolean = false

    private var rxThread: Thread? = null
    private var config: SerialPortConfig = SerialPortConfig.load(prefs)
    private var rxListener: SerialTransport.RxListener? = null
    private val writeLock = Any()

    override val isOpen: Boolean get() = fd >= 0

    override fun setRxListener(l: SerialTransport.RxListener?) {
        rxListener = l
    }

    /** 异步打开（后台线程做 root 提权 + open，结果回调在主线程） */
    override fun open(context: Context, callback: SerialTransport.OpenCallback?) {
        Exec.background {
            closeInternal()
            val cfg = SerialPortConfig.load(prefs)
            config = cfg
            // §3.5：root 提权 chmod 666（系统签名应用时 grantDevice 直接探测通过）
            val granted = RootHelper.grantDevice(cfg.devicePath)
            val tryFd = try {
                SerialPortJni.open(cfg.devicePath, cfg.baudRate, cfg.dataBits, cfg.parity, cfg.stopBits)
            } catch (e: Throwable) {
                -999 // so 加载失败等
            }
            if (tryFd >= 0) {
                fd = tryFd
                startRx()
                Exec.main {
                    callback?.onResult(true, cfg.devicePath)
                }
            } else {
                val reason = if (!granted) RootHelper.describeFailure(cfg.devicePath)
                else "errno=${-tryFd}"
                Exec.main {
                    callback?.onResult(false, reason)
                }
            }
        }
    }

    private fun closeInternal() {
        running = false
        val old = fd
        fd = -1
        if (old >= 0) {
            try {
                SerialPortJni.close(old)
            } catch (e: Throwable) {
                // 忽略关闭异常
            }
        }
        rxThread?.join(500)
        rxThread = null
    }

    /** synchronized 直写；返回是否全部写完 */
    override fun write(bytes: ByteArray): Boolean {
        val cur = fd
        if (cur < 0) return false
        return synchronized(writeLock) {
            try {
                val n = SerialPortJni.write(cur, bytes, bytes.size)
                n == bytes.size
            } catch (e: Throwable) {
                false
            }
        }
    }

    private fun startRx() {
        running = true
        val t = Thread {
            val buf = ByteArray(4096)
            while (running && fd >= 0) {
                val cur = fd
                if (cur < 0) break
                val n = try {
                    SerialPortJni.read(cur, buf, buf.size)
                } catch (e: Throwable) {
                    -1
                }
                if (n > 0) {
                    rxListener?.onBytes(buf.copyOf(n))
                } else if (n == 0) {
                    // EAGAIN：休眠 10ms 重试（架构 §3.3）
                    try {
                        Thread.sleep(10)
                    } catch (e: InterruptedException) {
                        break
                    }
                } else {
                    // 读错误：串口断开等；停止线程，由 UI 层提示
                    running = false
                    Exec.main { StatusNotifier.notifySerialError() }
                }
            }
        }
        t.name = "emrs-serial-rx"
        t.isDaemon = true
        rxThread = t
        t.start()
    }

    /** 串口读错误通知（原生/USB 传输共用，避免反向依赖 UI 层的简单挂接点） */
    object StatusNotifier {
        @Volatile
        var onError: (() -> Unit)? = null

        fun notifySerialError() {
            onError?.invoke()
        }
    }

    override fun close() {
        closeInternal()
    }
}
