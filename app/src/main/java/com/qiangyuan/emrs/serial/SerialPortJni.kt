package com.qiangyuan.emrs.serial

/**
 * JNI 串口接口声明（架构 serial/SerialPortJni.kt / §3.2）。
 * C 实现见 app/src/main/cpp/serial/serial_port.c（libemrsserial.so）。
 */
object SerialPortJni {

    init {
        System.loadLibrary("emrsserial")
    }

    /** 打开串口；返回 fd（>=0），失败返回 -(errno) */
    external fun open(path: String, baud: Int, dataBits: Int, parity: Int, stopBits: Int): Int

    /** 关闭串口 */
    external fun close(fd: Int)

    /** 非阻塞读；0=暂无数据，负值=-(errno) */
    external fun read(fd: Int, buf: ByteArray, len: Int): Int

    /** 循环写直到写完；负值=-(errno) */
    external fun write(fd: Int, buf: ByteArray, len: Int): Int

    /** 扫描 /dev/ttyS*/ttyUSB*/ttyMT*/ttyACM*，返回可达路径数组 */
    external fun listPorts(): Array<String>
}
