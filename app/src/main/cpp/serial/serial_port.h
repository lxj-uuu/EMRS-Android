/*
 * serial_port.h —— 自研 termios 串口驱动（JNI）
 * 参考android-serialport-api(cepr) 思路自行实现，不引第三方库（架构 §3.2）。
 */
#ifndef EMRS_SERIAL_PORT_H
#define EMRS_SERIAL_PORT_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * 打开串口并配置为原始模式。
 * 返回 fd（>=0 成功）；失败返回 -(errno)。
 * parity: 0=None 1=Odd 2=Even; stopBits: 1|2; dataBits: 7|8
 */
JNIEXPORT jint JNICALL
Java_com_qiangyuan_emrs_serial_SerialPortJni_open
        (JNIEnv *env, jobject thiz, jstring path, jint baud,
         jint dataBits, jint parity, jint stopBits);

/* 关闭串口 */
JNIEXPORT void JNICALL
Java_com_qiangyuan_emrs_serial_SerialPortJni_close
        (JNIEnv *env, jobject thiz, jint fd);

/* 非阻塞读；返回实际读到的字节数（0=暂无数据），失败返回 -(errno) */
JNIEXPORT jint JNICALL
Java_com_qiangyuan_emrs_serial_SerialPortJni_read
        (JNIEnv *env, jobject thiz, jint fd, jbyteArray buf, jint len);

/* 循环写直到全部写完；返回写入字节数，失败返回 -(errno) */
JNIEXPORT jint JNICALL
Java_com_qiangyuan_emrs_serial_SerialPortJni_write
        (JNIEnv *env, jobject thiz, jint fd, jbyteArray buf, jint len);

/* 扫描 /dev 下 ttyS*/ttyUSB*/ttyMT*/ttyACM* 设备，返回路径数组 */
JNIEXPORT jobjectArray JNICALL
Java_com_qiangyuan_emrs_serial_SerialPortJni_listPorts
        (JNIEnv *env, jobject thiz);

#ifdef __cplusplus
}
#endif

#endif /* EMRS_SERIAL_PORT_H */
