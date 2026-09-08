# EMRS-Android —— EMRS-2B 微机电磁勘探仪采集软件（Android 原生版）

2007 年 Delphi/VCL(Win32) 采集软件 `lx.exe`（EMRS-2B）的 **1:1 功能移植**，运行于
带**原生串口**（/dev/ttyS\*，需 root 或系统签名）的工控 Android 平板（7~10 寸横屏）。

- 界面/协议/文件格式/算法均与原版逐行对齐，兼容旧 Windows 后处理软件（txt/dat 为 GBK+CRLF）。
- 不依赖任何第三方串口/图表/数据库库；串口驱动为自研 JNI（C + termios + CMake/NDK）。

---

## 1. 技术栈与版本（架构定稿）

| 项 | 版本 |
|---|---|
| 语言 | Kotlin 1.8.x（jvmTarget 1.8） |
| JDK | 17 |
| AGP / Gradle | 7.4.2 / 7.6.4（wrapper properties 已带；**gradle-wrapper.jar 未随仓库分发**，首次构建需按 §2.1 修复，见下） |
| SDK | compileSdk 33 / targetSdk 30 / minSdk 24 |
| NDK / CMake | 21.4.7075529 / 3.18.1（客户机未装可在 `app/build.gradle` 注释 ndkVersion，用 AS 默认 NDK） |
| 依赖 | 仅 androidx appcompat / material / core-ktx / constraintlayout |

## 2. 构建

> 只想**尽快拿到可安装的 APK**、不想在本机装任何开发环境？请直接看同目录的
> **《APK获取指南.md》**：用 GitHub Actions 云端一键编译，约 5~10 分钟即可下载
> `app-debug.apk`（工程已内置工作流 `.github/workflows/build-apk.yml`）。

### 2.1 首次构建前：修复 Gradle Wrapper（必须做一次）

本仓库因二进制分发限制**未附带 `gradle/wrapper/gradle-wrapper.jar`**（`gradle-wrapper.properties`、
`gradlew`、`gradlew.bat` 均已在）。未修复前 `./gradlew` 会报
"Could not find or load main class org.gradle.wrapper.GradleWrapperMain"。三选一：

- **方式 A（推荐，Windows 客户机）**：双击工程根目录的 **`fix-wrapper.bat`**——
  自动定位本机已装的 Gradle（PATH / GRADLE_HOME / 常见安装目录），执行
  `gradle wrapper --gradle-version 7.6.4` 生成配套 wrapper jar。
- **方式 B（Android Studio）**：用 Android Studio 打开本工程，IDE 检测到 wrapper 缺失会提示
  自动修复（或菜单 `File → Sync Project with Gradle Files` 按提示修复）。
- **方式 C（手工）**：任一装有 Gradle 的机器上在本目录执行
  `gradle wrapper --gradle-version 7.6.4`，把生成的
  `gradle/wrapper/gradle-wrapper.jar` 拷入本工程同名目录。

### 2.2 正式构建

1. Android Studio（Flamingo 或更新，含 NDK + CMake）打开本目录（`EMRS-Android/`）。
2. 等待 Gradle Sync（首次需联网下载 Gradle 7.6.4 与依赖）。
3. `Build → Generate Signed APK`，或命令行：

```bash
./gradlew assembleDebug      # 调试包 app/build/outputs/apk/debug/
./gradlew assembleRelease    # 需自行配置签名（见下）
./gradlew test               # 算法/协议单元测试（JUnit）
```

签名（`app/build.gradle` 未内置 signingConfig，按需添加或用 AS 向导）：

```groovy
signingConfigs {
    release {
        storeFile file('xxx.jks'); storePassword '***'
        keyAlias '***'; keyPassword '***'
    }
}
```

## 3. 安装与串口权限（重要）

### 3.1 权限分级（RootHelper 自动处理，失败不崩溃）

1. **厂商系统签名应用（推荐）**：用设备厂商平台签名（platform.pk8/.x509.pem + signapk）
   重签 APK 并预装 `/system/priv-app/`，`/dev/ttyS*` 天然可读写。
2. **root 设备**：安装后首次打开串口时自动执行 `su -c chmod 666 /dev/ttySx`
   （每次开机后首次打开需重新执行；需设备已 root 且授权本应用 su）。
3. **都不满足**：状态条提示“串口打开失败：无 root 权限（su 不可用）”，App 其余功能可用。

### 3.2 串口配置

设置屏 → “串口设置”页：设备路径（自动扫描 /dev + 常用清单）、波特率
（默认 **9600 8N1**——原版 TComm 控件默认值，如仪器不同请实测后修改默认值）。
选择即保存并重开串口；状态条实时显示打开结果。

### 3.3 USB-OTG 连接仪器（USB增量）

客户平板若只有**普通 USB 口**（无 /dev/ttyS* 原生串口），可改用
**USB-OTG 转 RS232 线**连接仪器。串口驱动全部自研（`android.hardware.usb` 直连，
未引入 usb-serial-for-android 等第三方库），协议层与原生串口走同一条
`RxFrameParser` 解析路，功能完全一致。

**接线示意**

```
[仪器 EMRS-2B]                          [平板]
   RS232 9针 ──母头→公头── OTG转RS232线 ══ USB口(OTG) ──→ 平板
              (交叉线：2-3 交叉、5-5 地，与原 Windows 台式机接法相同)
```

> 即：仪器侧仍用原 RS-232 电缆；OTG 线一端是 USB-A 公头插平板，另一端是
> DB9 母头接仪器电缆。若原电缆是 DB9 公头直出，需另配 **DB9 双公头转接线**。

**使用步骤**

1. 设置屏 → 串口页 → “连接方式”选 **USB-OTG**（选即保存并重开串口）。
2. 插上 OTG 线 + 转 RS232 线 + 仪器；页面下方自动列出识别到的 USB 串口设备
   （芯片型号 + VID/PID）。
3. 点“**授权并连接**”：系统弹出 USB 权限对话框 → 勾选“默认用于该设备”并允许。
4. 状态条显示打开结果；此后收发与原生串口完全一致。
   支持的芯片见下表；也可在平板弹窗出现前先插线——插入时系统会自动唤起本应用。

**支持芯片**

| 芯片 | VID:PID | 依据 |
|---|---|---|
| CH340/CH341 | 1A86:7522 / 1A86:7523 | Linux 内核 drivers/usb/serial/ch341.c 主线分频算法（48MHz 时钟） |
| FT232/FT2232/FT232H/FT231X | 0403:6001/6010/6014/6015 | FTDI 官方 SIO 编程指南（24MHz 基准 14+3 位分频） |
| CP210x | 10C4:EA60 | Linux cp210x.c + mik3y：Silabs 私有请求 SET_BAUDRATE(0x1E，4 字节小端)+SET_LINE_CTL(0x03)，非 CDC 设备 |
| PL2303(HX) | 067B:2303 | Prolific PL2303 编程指南 + HX 系列厂商初始化序列 |

**常见问题**

- **插上没反应 / 设备列表为空**：
  - 确认用的是 **OTG 线**（不是普通 USB 延长线——延长线不供电、平板不识别从设备）；
  - 部分平板 OTG 供电不足，换带供电的 OTG HUB 试（注意有些平板**不支持 HUB**，直插最稳）；
  - 转接芯片不在支持列表：页面会显示“不支持的芯片(VID:PID)”，把 VID/PID 报给我们加驱动。
- **权限弹窗没出现 / 又弹一次**：每次插拔都是新设备实例，勾选弹窗里
  “默认情况下用于该 USB 设备”后同一根线只弹一次。
- **连接后无数据**：仪器波特率是否仍为 9600 8N1（与原生串口一致）；仪器与平板
  共地是否正常；DB9 转 3.5mm 之类非标转接头的线序是否正确。
- **拔线/通信中断**：状态条提示“通信中断！”，重新插线后到串口页再点“授权并连接”。
- 原生串口机型不受影响：连接方式默认仍为 **原生串口**，原功能零改动。


## 4. 数据目录 / 导入导出

- 数据目录：`Android/data/com.qiangyuan.emrs/files/emrs/`（App 外部私有区，免存储权限）。
  设置屏“文件路径”显示该目录；gzcs.txt 19 项在此读写（首启自动创建）。
- 导出/分享：文件管理器或第三方应用经 FileProvider 分享（GBK 字节原样，旧软件可直接打开）。
- 导入：`ACTION_OPEN_DOCUMENT` 原字节复制入数据目录（gzcs.txt / bdxs.txt / zyjg\*.txt / hz50.txt 可由旧机器拷入）。
- 成果文件名与格式与旧版一致：`<标识><线号>s/n/c<点号>.txt`、`<点号4位>-<线号3位>.dat`、`bdxs.txt`、`zygs.txt`。

## 5. 操作流程（与原版一致）

主菜单 → **自检**（电池电压/VAB 显示，返回自动停自检）→ **充电**（设定目标 VAB → 充电 → 充电完毕提示）→
**标定**（供电测量 → 计算标定系数：读正演文件 zyjg\*.txt → 生成 bdxs.txt）→
**测量**（读 bdxs.txt → 供电开始 → 供电完毕 → 处理测量数据 → 存储处理结果 → 四件套 + 项目登记 + dat）→ **设置**（图头/参数）。

未存盘守卫：处理完未存盘时点击线号/点号/返回/供电开始 → “数据尚未存盘!!!”+提示音（只提醒一次）。

## 6. 工程结构

```
app/src/main/
  cpp/serial/          自研 JNI 串口（termios）
  java/com/qiangyuan/emrs/
    MainActivity.kt    单 Activity 接线收口
    core/              AppState（lx1 全局变量）/ StatusHub / Exec
    serial/            JNI 封装 / 配置 / 枚举 / RootHelper / 管理器 / SerialTransport（USB增量）
    serial/usb/        USB-OTG 驱动（USB增量）：CH340/FTDI/CP210x/PL2303 + 管理器
    protocol/          FrameCodec / RxFrameParser / ProtocolEngine / ProtocolSettings
    algo/              Decode / smooth5_3 / replace_ave / sparse_ave / normal_i /
                       ProcessPipeline / Formatting（Delphi 数值格式）
    data/              gzcs/bdxs/项目登记/成果四件套/TextFileIO(GBK+CRLF)/SAF 导入
    ui/                ScreenManager + 六屏（main/selftest/setup/supply/charge/wave）+ 自绘控件
  res/layout/          activity_main + screen_*.xml
app/src/test/          算法与协议 JUnit 测试
```

## 7. 已知差异与待硬件确认项

- 全部规格书 §6.3 待确认问题的默认值均取**源码字面行为**，集中于
  `protocol/ProtocolSettings.kt`（ACK 次数/重发/看门狗）与 `serial/SerialPortConfig.kt`（串口参数）。
- Delphi `Write(real)` 默认科学计数字符串在 `algo/Formatting.delphiReal` 近似实现，
  P1 阶段建议用旧机器真实成果文件做逐字节回归校准。
- 主屏右下角小号“显示波形”按钮为 P2 调试屏入口（原版隐藏不可达，为联调保留）。
