/*
 * serial_port.c —— 自研 termios 串口驱动（架构 §3.2）
 * open(O_RDWR|O_NOCTTY|O_NONBLOCK) + cfmakeraw + 波特率表 + 8N1/校验/停止位配置；
 * 读写封装；listPorts 扫描 /dev/ttyS{0..63}/ttyUSB*/ttyMT*/ttyACM*。
 * 错误一律以 -(errno) 返回，Java 层转可读提示。
 */
#include "serial_port.h"

#include <errno.h>
#include <fcntl.h>
#include <dirent.h>
#include <string.h>
#include <sys/stat.h>
#include <termios.h>
#include <unistd.h>

/* 波特率 → termios 常量（含常用非标档位回退） */
static speed_t baud_to_constant(int baud) {
    switch (baud) {
        case 1200:   return B1200;
        case 2400:   return B2400;
        case 4800:   return B4800;
        case 9600:   return B9600;
        case 19200:  return B19200;
        case 38400:  return B38400;
        case 57600:  return B57600;
        case 115200: return B115200;
        case 230400: return B230400;
        default:     return B9600; /* 默认 9600（TComm 控件默认值，规格书 §4.1） */
    }
}

JNIEXPORT jint JNICALL
Java_com_qiangyuan_emrs_serial_SerialPortJni_open
        (JNIEnv *env, jobject thiz, jstring path, jint baud,
         jint dataBits, jint parity, jint stopBits) {
    if (path == NULL) return -EINVAL;

    const char *device = (*env)->GetStringUTFChars(env, path, NULL);
    if (device == NULL) return -ENOMEM;

    /* O_NONBLOCK：非阻塞打开，Java 层读循环轮询（架构 §3.3） */
    int fd = open(device, O_RDWR | O_NOCTTY | O_NONBLOCK);
    if (fd < 0) {
        int err = errno;
        (*env)->ReleaseStringUTFChars(env, path, device);
        return -err;
    }

    struct termios tio;
    if (tcgetattr(fd, &tio) != 0) {
        int err = errno;
        close(fd);
        (*env)->ReleaseStringUTFChars(env, path, device);
        return -err;
    }

    cfmakeraw(&tio);

    speed_t speed = baud_to_constant(baud);
    cfsetispeed(&tio, speed);
    cfsetospeed(&tio, speed);

    /* CLOCAL 忽略调制解调器状态线；CREAD 使能接收 */
    tio.c_cflag |= CLOCAL | CREAD;
    tio.c_cflag &= ~CSIZE;

    switch (dataBits) {
        case 7: tio.c_cflag |= CS7; break;
        case 6: tio.c_cflag |= CS6; break;
        case 5: tio.c_cflag |= CS5; break;
        default: tio.c_cflag |= CS8; break;
    }

    switch (parity) {
        case 1: /* Odd */
            tio.c_cflag |= PARENB | PARODD | INPCK;
            tio.c_iflag |= INPCK;
            break;
        case 2: /* Even */
            tio.c_cflag |= PARENB | INPCK;
            tio.c_cflag &= ~PARODD;
            tio.c_iflag |= INPCK;
            break;
        default: /* None */
            tio.c_cflag &= ~(PARENB | PARODD | INPCK);
            tio.c_iflag &= ~INPCK;
            break;
    }

    if (stopBits == 2) {
        tio.c_cflag |= CSTOPB;
    } else {
        tio.c_cflag &= ~CSTOPB;
    }

    /* 无流控（fcDefault 等价） */
    tio.c_cflag &= ~CRTSCTS;
    tio.c_iflag &= ~(IXON | IXOFF | IXANY);

    /* 非规范模式、立即返回：VMIN=0 VTIME=0 */
    tio.c_cc[VMIN] = 0;
    tio.c_cc[VTIME] = 0;

    if (tcsetattr(fd, TCSANOW, &tio) != 0) {
        int err = errno;
        close(fd);
        (*env)->ReleaseStringUTFChars(env, path, device);
        return -err;
    }

    /* 清空残余输入输出缓冲 */
    tcflush(fd, TCIOFLUSH);

    (*env)->ReleaseStringUTFChars(env, path, device);
    return fd;
}

JNIEXPORT void JNICALL
Java_com_qiangyuan_emrs_serial_SerialPortJni_close
        (JNIEnv *env, jobject thiz, jint fd) {
    if (fd >= 0) {
        close(fd);
    }
}

JNIEXPORT jint JNICALL
Java_com_qiangyuan_emrs_serial_SerialPortJni_read
        (JNIEnv *env, jobject thiz, jint fd, jbyteArray buf, jint len) {
    if (fd < 0 || buf == NULL || len <= 0) return -EINVAL;

    jbyte tmp[4096];
    jint want = (len < 4096) ? len : 4096;

    ssize_t n = read(fd, tmp, (size_t) want);
    if (n > 0) {
        (*env)->SetByteArrayRegion(env, buf, 0, (jint) n, tmp);
        return (jint) n;
    }
    if (n == 0) return 0;
    if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) return 0;
    return -errno;
}

JNIEXPORT jint JNICALL
Java_com_qiangyuan_emrs_serial_SerialPortJni_write
        (JNIEnv *env, jobject thiz, jint fd, jbyteArray buf, jint len) {
    if (fd < 0 || buf == NULL || len <= 0) return -EINVAL;

    jbyte tmp[4096];
    /* 缓冲防护：单次调用最多搬运 4096B（协议命令帧 ≤8B，实际不会触发） */
    jint cap = (len < 4096) ? len : 4096;
    jint total = 0;

    (*env)->GetByteArrayRegion(env, buf, 0, cap, tmp);
    while (total < cap) {
        jint chunk = cap - total;
        ssize_t n = write(fd, tmp + total, (size_t) chunk);
        if (n > 0) {
            total += (jint) n;
        } else if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) {
            continue;
        } else {
            return -errno;
        }
    }
    return total;
}

/* 判断文件名是否匹配可枚举的串口前缀 */
static int name_matches(const char *name) {
    if (strncmp(name, "ttyS", 4) == 0) return 1;    /* /dev/ttyS0..63 */
    if (strncmp(name, "ttyUSB", 6) == 0) return 1;
    if (strncmp(name, "ttyMT", 5) == 0) return 1;
    if (strncmp(name, "ttyACM", 6) == 0) return 1;
    return 0;
}

JNIEXPORT jobjectArray JNICALL
Java_com_qiangyuan_emrs_serial_SerialPortJni_listPorts
        (JNIEnv *env, jobject thiz) {
    DIR *dir = opendir("/dev");
    if (dir == NULL) {
        jclass ex = (*env)->FindClass(env, "java/lang/RuntimeException");
        if (ex != NULL) {
            (*env)->ThrowNew(env, ex, "cannot opendir /dev");
        }
        return NULL;
    }

    /* 先收集（最多 256 个） */
    char names[256][64];
    int count = 0;
    struct dirent *ent;
    while ((ent = readdir(dir)) != NULL && count < 256) {
        if (!name_matches(ent->d_name)) continue;
        char full[128];
        snprintf(full, sizeof(full), "/dev/%s", ent->d_name);
        struct stat st;
        if (stat(full, &st) != 0) continue;
        if (!S_ISCHR(st.st_mode)) continue;
        strncpy(names[count], full, sizeof(names[count]) - 1);
        names[count][sizeof(names[count]) - 1] = '\0';
        count++;
    }
    closedir(dir);

    jclass strClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, count, strClass, NULL);
    for (int i = 0; i < count; i++) {
        jstring s = (*env)->NewStringUTF(env, names[i]);
        (*env)->SetObjectArrayElement(env, result, i, s);
        (*env)->DeleteLocalRef(env, s);
    }
    return result;
}
