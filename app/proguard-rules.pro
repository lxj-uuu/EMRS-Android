# EMRS-Android proguard 规则
# JNI 本地方法不可混淆
-keepclasseswithmembernames class com.qiangyuan.emrs.serial.SerialPortJni {
    native <methods>;
}
-keep class com.qiangyuan.emrs.protocol.** { *; }
-keep class com.qiangyuan.emrs.algo.** { *; }
-keep class com.qiangyuan.emrs.data.** { *; }
