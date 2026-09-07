package com.qiangyuan.emrs.serial

import android.content.Context

/**
 * 统一传输抽象（serial/SerialTransport.kt，USB增量）。
 *
 * 原生 ttyS（SerialPortManager）与 USB-OTG（UsbSerialManager）各实现一份，
 * ProtocolEngine/上层只认本接口，方法语义与原 SerialPortManager 完全一致：
 * - open：异步打开，结果回调在主线程（opened=false 时 message 为可读原因）；
 * - write：全量语义，返回是否全部写完；
 * - setRxListener：读线程收到的字节块回调（→ ProtocolEngine → RxFrameParser 同一条路）。
 */
interface SerialTransport : AutoCloseable {

    /** 是否已打开 */
    val isOpen: Boolean

    /** 异步打开（后台线程完成连接/提权/授权，结果回调在主线程） */
    fun open(context: Context, callback: OpenCallback?)

    /** 全量写；返回是否全部写完 */
    fun write(bytes: ByteArray): Boolean

    /** 设置读线程回调（收到的字节块） */
    fun setRxListener(listener: RxListener?)

    /** 收到的字节块回调（原 SerialPortManager.RxListener 上移为此） */
    interface RxListener {
        fun onBytes(chunk: ByteArray)
    }

    /** 打开结果回调（原 SerialPortManager.OpenCallback 上移为此） */
    interface OpenCallback {
        fun onResult(opened: Boolean, message: String)
    }
}

/**
 * 传输路由器（USB增量）。
 *
 * ProtocolEngine 只持有本对象一次（start 时传入），连接方式切换时 MainActivity
 * 调 switchTo() 换目标传输，协议层零改动；旧目标的关闭由 MainActivity 负责。
 */
class SerialTransportRouter : SerialTransport {

    @Volatile
    private var target: SerialTransport? = null

    private var listener: SerialTransport.RxListener? = null

    /** 切换目标传输（须先于目标 open() 调用；切换后监听器自动补挂） */
    fun switchTo(t: SerialTransport) {
        target = t
        t.setRxListener(listener)
    }

    override val isOpen: Boolean
        get() = target?.isOpen ?: false

    override fun open(context: Context, callback: SerialTransport.OpenCallback?) {
        val t = target
        if (t == null) callback?.onResult(false, "未选择连接方式") else t.open(context, callback)
    }

    override fun write(bytes: ByteArray): Boolean = target?.write(bytes) ?: false

    override fun setRxListener(listener: SerialTransport.RxListener?) {
        this.listener = listener
        target?.setRxListener(listener)
    }

    override fun close() {
        target?.close()
    }
}
