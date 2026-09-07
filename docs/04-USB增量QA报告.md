# 04-USB 增量 QA 审查报告（EMRS-Android · USB-OTG 串口驱动）

- 审查人：QA 工程师（严过关），**独立新鲜视角，未参与本增量实现**
- 日期：2026-09-07（第 1 轮 / 上限 2 轮）
- 对象：`EMRS-Android/` 新增的 USB-OTG 串口驱动增量（12 个新增/修改文件）
- 基线：`docs/03-QA审查报告.md`（前两轮 37 PASS / 0 FAIL 结论）、`docs/02-架构设计.md`
- 手段：**静态逐行审查 + Python 独立推演**（本机无 JDK / Android SDK / 外网，`gradlew test` 无法执行）
- 证据脚本：`qa-tools/usb_check.py`（**64 项断言，64 PASS / 0 FAIL**；含 CH340/FTDI 分频独立重写、CDC 布局、位域审计、VID/PID 十进制↔十六进制逐项换算、FTDI 剥头分包边界仿真）

## 结论：**FAIL**（P0×1，P1×3，P2×11）——转工程师修复后进入第 2 轮回归

> 说明：P0 只有一条且只影响 **CP210x（10C4:EA60）** 一种芯片；CH340 / FTDI / PL2303 三条路径
> 的协议与算法经 Python 独立推演**全部与内核 / 官方参考实现一致**。协议层（`ProtocolEngine`
> / `RxFrameParser`）确认**零改动**。

> **第 2 轮回归（2026-09-07）：第 1 轮 4 项必改 + E2 三处测试缺口均已就位，结论已转为
> PASS（P0×0 / P1×0），详见 §7。**

---

## 1. 逐项结果表

### A. 协议层零改动（P0 最重要）

| # | 审查项 | 结果 | 说明 |
|---|--------|------|------|
| A1 | `RxFrameParser.kt` 是否仍为两轮验证通过版 | ✅ | 文件内**无任何【USB增量】标记**。逐行比对 `docs/03-QA审查报告.md` §6 记录的复核通过版：L52-59 整块扫描最后一个 A0A0（`start=i+2` / `msgLen=0` / `haveHead=true`，不提前退出）、L61 `!haveHead→return` 门控、L64-87 逐字节累积 + L78-86 帧尾 AFAF 判定、L90-105 `emitFrame` 校验 ∈{0x00,0x80} 与 payload=msg[1..len-2] —— **与报告描述逐条吻合，判定一行未动** |
| A2 | `ProtocolEngine.kt` 状态机改动范围 | ✅ | 改动仅 4 处且全为类型收口：L10-11（删 `SerialPortManager` import，新增注释 + `SerialTransport` import）、L33 `private lateinit var serial: SerialTransport`、L59 `start(..., serial: SerialTransport, ...)`、L63 `SerialTransport.RxListener`。**状态机主体（waiting_ack 扫描 / 重发 Timer3 / 看门狗 Timer4 / handleType1·2·4 / ACK·NAK）一行未改**，行号由 L157→L158 仅因 import 区多 1 行 |
| A3 | 组帧语义是否引入漂移 | ✅ | USB 与原生走同一条路：`UsbSerialManager` rx thread → `RxListener.onBytes(chunk)` → `handler.post{onRxBytes}` → `parser.feed(chunk, settings)`。chunk 边界变化（USB 一次 bulk 可能带多帧/半帧）**不改变 `feed()` 语义**：`feed()` 本就按"整块扫最后一个 A0A0"处理任意切分，与原生 4096 字节块等价 |
| A4 | `SerialTransport` 抽象语义一致性 | ⚠️ | 见下表 A4-1~A4-5 |
| A4-1 | write 全量语义 | ✅ | 原生 `SerialPortJni.write(...)==bytes.size`；USB `p.write(...)==bytes.size`（`UsbSerialPortBase.write` 内部循环直到写完，短写返回 <size → false）。**语义一致** |
| A4-2 | close 幂等 | ✅ | 原生 `fd=-1` 守卫；USB `port=null` 守卫 + `connection=null`。两者 `open()` 入口均先 `closeInternal()`。**均幂等** |
| A4-3 | open 时序（异步 + 主线程回调） | ✅ | 两者均 `Exec.background{...}` + `Exec.main{callback.onResult}`。USB 额外多一步授权子流程，但最终回调仍在主线程且只由 `doOpen` 成功/失败决定 |
| A4-4 | isOpen | ✅ | 原生 `fd>=0`；USB `port!=null`。同为"已打开"标志 |
| A4-5 | **read 返回值语义** | ❌ **P1-2** | 原生 JNI `read` 返回 `>0 / 0(EAGAIN) / <0(错误)` 三态；USB `UsbSerialPortBase.read:100-103` 把 `bulkTransfer` 负值**一律压成 0**，吞掉了"设备错误/已拔出"。→ 拔线后读线程忙等 |
| A5 | Router 切换是否关闭旧 transport | ⚠️ **P2-1** | `SerialTransportRouter.switchTo():53-56` **不关闭**旧 target（注释把责任推给 MainActivity）。当前 `MainActivity.reconnectSerialInternal():203-209` 确实先 `nativeSerial.close()` / `usbSerial.close()` 再 `switchTo()`，**实测无泄漏、无双读线程**；但 API 语义脆弱，建议内建 |

### B. 芯片驱动正确性（Python 独立推演）

| # | 审查项 | 结果 | 说明 |
|---|--------|------|------|
| B1 | CH340 分频（内核 ch341.c） | ✅ | Python 独立重写 `ch341_get_divisor`，**10/10 与 UsbBaudCalcTest 期望一致**（详见 §2.1） |
| B2 | CH340 LCR / 初始化 / 握手 / 位域 | ✅ | LCR 5/5 一致；`wValue=0x1312`+分频值在 `wIndex`、`~0x60=0xFF9F` 握手、`version≥0x30` 才写 LCR —— 均与内核一致（详见 §2.2） |
| B3 | FTDI 分频 / 子分频编码 | ✅ | Python 独立重写 `convertBaudrate`+编码，**17/17 与测试期望一致**（详见 §2.3） |
| B4 | FTDI SET_DATA 编码 | ✅ | 7/7 一致（含 5 数据位/非法停止位 → null） |
| B5 | **FTDI bulk IN 剥头分包边界** | ✅ | 按端点最大包长切包、每包剥 2 字节状态头，与 mik3y `readFilter` 语义等价；Python 对 mps∈{8,16,32,64,512} × n∈{3,100,mps,2mps,4095,4096} 共 30 组仿真，**剥头后长度恒 ≤ 读缓冲 4096，无越界**（详见 §2.4）。发现 1 处脆弱写法 → P2-7 |
| B6 | **FTDI SET_BAUDRATE 的 wIndex** | ❌ **P1-3** | 非 H 系列（FT232R/FT231X，最常见线缆）wIndex 被写成子分频位（**常用 8 档波特率全部为 0**），而内核 `ftdi_sio.c` / mik3y 均写**端口号 1**（详见 §2.5） |
| B7 | **CP210x SET_LINE_CODING(0x20)** | ❌ **P0-1** | **0x20 是 CDC-ACM 类请求，不是 CP210x 的厂商请求**；CP210x 为 vendor-specific（bInterfaceClass=0xFF），三份权威参考均用 `SET_BAUDRATE 0x1E`(4 字节小端) + `SET_LINE_CTL 0x03`（详见 §2.6） |
| B8 | CP210x IFC_ENABLE / SET_MHS | ✅ | `0x41` + `0x00`(wValue=1) + `0x07`(wValue=0x0303) 与 AN571 / cp210x.c / mik3y / felhr 四源一致 |
| B9 | PL2303 请求与位域 | ✅ | `SET_LINE 0x20` / `SET_CONTROL 0x22` 的 bmRequestType=**0x21**（class\|interface\|out）、vendor `0x01` 写=**0x40** / 读=**0xC0** —— 与 `pl2303.c` 的 `SET_LINE_REQUEST_TYPE 0x21` / `VENDOR_WRITE_REQUEST_TYPE 0x40` / `VENDOR_READ_REQUEST_TYPE 0xC0` **逐条一致** |
| B10 | PL2303 HX 初始化序列 | ⚠️ **P2-5** | `0x8484/0x0404/0x8383/0x0000/0x0001` 主序列与 mik3y 一致；**末步 `0x0002,0x0024` 未按 HX/非 HX 分支**（mik3y 对 HX 写 `0x0000`） |
| B11 | 全部 `controlTransfer` 位域审计（17 处） | ✅ 15 / ❌ 2 | 位域（direction/recipient/type）**17 处全部正确**；2 处问题在请求码/wIndex 而非位域本身（§2.7） |

### C. 权限与生命周期（P0）

| # | 审查项 | 结果 | 说明 |
|---|--------|------|------|
| C1 | `uses-feature` required=false | ✅ | `AndroidManifest.xml:9-11` `android.hardware.usb.host` + `required="false"` —— 无 USB 主机能力的原生串口机型仍可安装 |
| C2 | `USB_DEVICE_ATTACHED` intent-filter | ✅ | `AndroidManifest.xml:32-34`，挂在 `MainActivity`（`launchMode=singleTask`） |
| C3 | `meta-data` 指向 device_filter | ✅ | `AndroidManifest.xml:36-38`，`android:name=android.hardware.usb.action.USB_DEVICE_ATTACHED` + `android:resource=@xml/device_filter`（写法与官方一致） |
| C4 | device_filter 十进制 ↔ UsbSerialIds 十六进制 | ✅ | **8/8 逐项换算全对**（6790:29986=1A86:7522 … 1659:8963=067B:2303，详见 §2.8） |
| C5 | 运行时授权 requestPermission + PendingIntent | ✅ | `UsbSerialManager.kt:96-122`：`hasPermission` 短路 → 动态注册广播 → `requestPermission(dev, pi)` |
| C6 | API31+ `FLAG_MUTABLE` | ✅ | `UsbSerialManager.kt:115` `if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0` —— **正确且必要**（Android 12 起未指定 mutable/immutable 会直接抛 `IllegalArgumentException`，与 targetSdk 无关）；用 `FLAG_MUTABLE` 才能收到系统回填的 `EXTRA_PERMISSION_GRANTED` |
| C7 | 授权被拒路径 | ✅ | `open():146-150` → 主线程 `onResult(false, "USB 授权被拒绝")`，**不崩溃** |
| C8 | 拔线路径 | ✅ | `registerDetach():268-281` 监听 `ACTION_USB_DEVICE_DETACHED` → `close()` + `StatusNotifier.notifySerialError()` → 状态条"通信中断！"。但触发时机依赖广播（配合 P1-2） |
| C9 | 读线程异常路径 | ⚠️ **P1-2** | `startRx():209-220`：`read` 抛异常 → n=-1 → `running=false` + 错误通知 + break。但 `read` 本身吞掉负返回码，实际**只在 null 端口/抛异常时**才走这条 |
| C10 | close 幂等 / 无泄漏（正常路径） | ✅ | `closeInternal():250-265`：`port?.close()`（驱动内 `releaseInterface`+`conn.close()`，各自 try/catch）→ `port=null; connection=null` → `rxThread?.join(500)` → `unregisterDetach()`。`MainActivity.onDestroy` 连调 `router.close()`+两个 manager 的 `close()` 均安全 |
| C11 | **doOpen 失败路径的连接泄漏** | ❌ **P1-1** | `doOpen():177-199`：`um.openDevice()` 成功后若 `createDriver/drv.open/setBaudRate` 任一步抛异常，`catch` 只调 `closeInternal()`，而 `port` 尚未赋值 → **UsbDeviceConnection 与已 claim 的接口永不释放**（详见 §3 P1-1） |

### D. 一致性与兼容（P1）

| # | 审查项 | 结果 | 说明 |
|---|--------|------|------|
| D1 | 新文件包名 / import 齐全 | ✅ | 7 个新增 kt 包名均为 `com.qiangyuan.emrs.serial(.usb)`；`Exec`/`SerialPortConfig`/`SerialPortManager`/`SerialTransport` 引用均在位；无未解析符号（静态核查） |
| D2 | 布局 id 齐全 | ✅ | `screen_setup.xml` 新增 `sp_conn_type`(L190) / `row_usb`(L258) / `sp_usb_device`(L273) / `btn_usb_refresh`(L279) / `btn_usb_connect`(L289) / `tv_usb_status`(L301)，与 `SetupScreen.kt:76-79` 一一对应 |
| D3 | strings 齐全 | ✅ | 新增 `serial_conn_type` / `conn_native` / `conn_usb` / `usb_device` / `btn_usb_refresh` / `btn_usb_connect` / `usb_none_detected` / `usb_chip_unsupported`（strings.xml L80-87），全部被引用；`serial_opened`(L124) 已被 MainActivity 使用 |
| D4 | Gradle 依赖未变 | ✅ | `app/build.gradle:53-61` 仍为 androidx 四件套 + junit；**未引入任何第三方 USB/串口库**（自研达成）；compileSdk 33 / targetSdk 30 / minSdk 24 未动 |
| D5 | `SerialPortConfig` 旧配置缺键回退 NATIVE | ✅ | `SerialPortConfig.load():60-77`：`KEY_CONN_TYPE` 缺失→`NATIVE`；`valueOf` 抛 `IllegalArgumentException` 被 catch→`NATIVE`；`usbVid/usbPid` 默认 0。`SerialPortConfig()` 恢复默认同时清空 USB 记忆（符合 KDoc） |
| D6 | README 3.3 与实际控件 | ⚠️ **P2-8** | 步骤 1/2/3/4 与控件名一致；但"**自动列出**识别到的 USB 设备"与"插入时系统会**自动唤起**"表述过强——实现只在 `onShow()` / 点"刷新 USB 设备"时刷新，`MainActivity` 未覆写 `onNewIntent`，插线只唤起不重连；"不支持的芯片(VID:PID)"实际文案为"未知设备（VID:xxxx/PID:xxxx）" |
| D7 | 支持矩阵与 device_filter 同步性 | ⚠️ **P2** | 两处均为 8 项且已人工核对一致，但**无单测锁定**该一致性（现有 `usbIds_tableComplete` 只测 Kotlin 侧，不读 XML） |

### E. 测试质量（P1）

| # | 审查项 | 结果 | 说明 |
|---|--------|------|------|
| E1 | UsbBaudCalcTest 期望值 == 内核真值 | ✅ | **Python 独立重算 64 项，0 处"实现自说自话"**（CH340 10/10、FTDI 17/17、CDC 3/3、PL2303 3/3、VID/PID 9/9、FTDI 剥头 1/1、PL2303 位域 4/4 …） |
| E2 | 用例是否覆盖真实生效路径 | ❌ | **3 处覆盖缺口，恰好掩盖了 P0-1 与 P1-3**（详见 §4）：① `cp210x_baudDivTable` 测的是**死代码** `baudDiv()`，真正生效的 `setBaudRate()` 无用例；② `ftdi_indexEncoding` 只测了 subdivisor=3（index=1）的分支，未覆盖常用波特率（subdivisor∈{0,2,4} → index=0）；③ 无 `device_filter.xml` ↔ `UsbSerialIds` 一致性用例 |
| E3 | 测试可运行性 | ⚠️ **P2-10** | 单测调用 `Ch340Driver`/`FtdiDriver`/`Cp210xDriver` 的 companion 纯函数，这些类的签名引用 `android.hardware.usb.*`，依赖 AGP 的 mockable android.jar 能解析（通常可以）；另 `const val ... = UsbConstants.X or UsbConstants.Y` 是否通过 Kotlin 1.8.22 的常量表达式检查需首次 `gradlew test` 确认 |

---

## 2. Python 独立推演对照表（证据：`qa-tools/usb_check.py`）

### 2.1 CH340 / CH341 分频（内核 `ch341.c ch341_get_divisor` 独立重写）

基准：CLKRATE=48 000 000，MIN_BPS=46（`DIV_ROUND_UP(48M, 4096*256)`），MAX_BPS=3 000 000；
`min_rate(ps=0..3) = 45, 366, 2929, 23437`（**由高档 ps=3 向低找第一个 speed>min_rate 的档**）。

| 请求波特率 | 我算 ps | fact | div | 我算寄存器值 | 测试期望 | 我算实际波特率 | 测试期望 | 误差 | 判定 |
|---|---|---|---|---|---|---|---|---|---|
| 9600 | 2 | 0 | 78 | **0xB202** | 0xB202 | **9615** | 9615 | 0.156% | ✅ |
| 115200 | 3 | 0 | 52 | **0xCC03** | 0xCC03 | **115384** | 115384 | 0.160% | ✅ |
| 300 | 0 | 0 | 39 | **0xD900** | 0xD900 | **300** | 300 | 0.160% | ✅ |
| 3 000 000 | 3 | 0 | 2 | **0xFE03** | 0xFE03 | **3 000 000** | 3000000 | 0% | ✅ |
| 1（钳到 46） | 0 | 0 | 255 | **0x0100** | ==calcDivisor(46) | 45 | — | — | ✅ |
| 4 000 000（钳到 3M） | 3 | 0 | 2 | **0xFE03** | ==calcDivisor(3e6) | 3 000 000 | — | — | ✅ |

常用波特率全扫描（内核算法）：1200→0xB201(1201, 0.083%)、2400→0xD901(2403, 0.125%)、4800→0x6402(4807)、
9600→0xB202(9615)、19200→0xD902(19230)、38400→0x6403(38461)、57600→0x9803(57692)、115200→0xCC03(115384)。
**全部 ≤0.16%，满足 UART 2% 容差。**

LCR 编码：8N1→**0xC3**、8O1→**0xCB**、8E1→**0xDB**、8N2→**0xC7**、7N1→**0xC2** —— 5/5 与测试期望一致。

### 2.2 CH340 初始化 / 握手 / 写寄存器（与内核对照）

| 步骤 | 请求 | bmRequestType | wValue | wIndex | 内核依据 | 判定 |
|---|---|---|---|---|---|---|
| 读版本 | 0x5F | 0xC0（vendor\|device\|in） | 0 | 0 | `ch341_control_in(REQ_READ_VERSION)` | ✅ |
| 串口初始化 | 0xA1 | 0x40（vendor\|device\|out） | 0 | 0 | `REQ_SERIAL_INIT` | ✅ |
| 握手 DTR\|RTS | 0xA4 | 0x40 | **0xFF9F** | 0 | `ch341_set_handshake(port, ~mcr)`，`mcr=DTR\|RTS=0x60` → `~0x60=0xFF9F` | ✅ |
| 写分频 | 0x9A | 0x40 | **0x1312**（REG_DIVISOR<<8\|REG_PRESCALER） | **分频值** | `ch341_control_out(..., 0x1312, val)` —— **分频值在 wIndex** | ✅ |
| 写线控 | 0x9A | 0x40 | **0x2518**（REG_LCR2<<8\|REG_LCR） | LCR | 仅 `version ≥ 0x30`，与 `ch341_set_baudrate_lcr` 一致 | ✅ |
| BIT(7) 开关 | — | — | `value or 0x80` | — | 内核 `if (version > 0x27) val \|= BIT(7)`；**bit7 位于低字节空闲位**（`fact<<2\|ps` 只占 bit0-3），不会污染分频 | ✅ |

### 2.3 FTDI 分频 / 子分频编码（AN232B-05 + mik3y 独立重写）

| 请求波特率 | 我算 divisor | sub | 我算 wValue | 期望 wValue | 我算 wIndex（非 H） | 期望 | 我算实际 | 期望实际 | 判定 |
|---|---|---|---|---|---|---|---|---|---|
| 9600 | 312 | 4 | **0x4138** | 0x4138 | 0 | 0 | 9600 | 9600 | ✅ |
| 115200 | 26 | 0 | **0x001A** | 0x001A | 0 | 0 | 115385 | 115385 | ✅ |
| 300 | 10000 | 0 | **0x2710** | 0x2710 | — | — | 300 | 300 | ✅ |
| 14400 | 208 | 3 | **0x00D0** | 0x00D0 | **1** | 1 | 14397 | — | ✅ |
| 14400（H 系列） | 208 | 3 | 0x00D0 | — | **0x0101** | 0x0101 | — | — | ✅ |
| 100 / 2 600 000 / 4 000 000 | — | — | **null** | null | — | — | — | — | ✅ |

SET_DATA(0x04) 编码：8N1→**0x0008**、8O1→**0x0108**、8E1→**0x0208**、8N2→**0x1008**（内核 `FTDI_SIO_SET_DATA_STOP_BITS_2 = 2<<11`）、7N1→**0x0007**；5 数据位/非法停止位→null。7/7 ✅

### 2.4 FTDI bulk IN 剥 2 字节状态头：分包边界仿真

`mps` = 端点最大包长，`n` = 单次 `bulkTransfer` 返回长度；按 `min(mps, n-src) - 2` 剥头后累加：

| mps | n | 包数 | 剥头后字节 | ≤ 4096 缓冲 |
|---|---|---|---|---|
| 64 | 64 | 1 | 62 | ✅ |
| 64 | 128 | 2 | 124 | ✅ |
| 64 | 100 | 2 | 96（62 + 34，**末短包正确**） | ✅ |
| 64 | 4096 | 64 | 3968 | ✅ |
| 512 | 4096 | 8 | 4080 | ✅ |
| 512 | 4095 | 8 | 4079 | ✅ |
| 8 | 4096 | 512 | 3072 | ✅ |

**30 组仿真无一越界；末短包（n 非 mps 整数倍）处理正确；2 字节纯状态包 → length=0 不误判。** 结论：剥头逻辑与 mik3y `readFilter` 等价，**无分包边界 bug**。

### 2.5 FTDI SET_BAUDRATE 的 wIndex（P1-3 依据）

| 来源 | wIndex（非 H 系列，如 FT232R/FT231X） | wIndex（H 系列） |
|---|---|---|
| Linux `ftdi_sio.c set_ftdi_divisor` | `priv->interface` = **1** | `(value>>16) \| priv->interface` → **1** |
| mik3y `FtdiSerialDriver` | `index \|= 1` → **1** | `index <<= 8; index \|= mPort` |
| **本工程 `FtdiDriver.kt:101`** | `if (withPort) index = (index shl 8) or 1` → **非 H 时 index = 子分频位（0 或 1）** | `(bit<<8)\|1` ✅ |

实测扫描：**8 个常用波特率（1200…115200）在 FT232R/FT231X 上 wIndex 全部被写成 0**（见 §2.3 表）。
mik3y 的 `index |= 1` 是非 H 分支的**无条件置端口位**，本工程缺少这一步。

> **好消息**：这 8 档的 `subdivisor` 分别为 `0/0/0/4/2/1/1/0`，**全部 ∈ {0,1,2,4}**，
> 子分频位只编码在 `wValue` 的 bit14-15，**不占用 wIndex bit0** —— 因此"wIndex 写 0 还是 1"
> 对这 8 档的**波特率数值没有任何影响**。风险主要在于芯片是否"要求"wIndex 为合法端口号。

> 风险：多数 FTDI 单口芯片会忽略 wIndex，故未必立即表现为故障；但 FT2232C/FT2232H 双口芯片
> 严格依赖 wIndex 选端口。**若真机 FT232R 线缆波特率异常，第一嫌疑即此项。**

### 2.6 CP210x 请求码审计（P0-1 依据）

| 功能 | 内核 cp210x.c / AN571 | mik3y | felhr | **本工程** | 判定 |
|---|---|---|---|---|---|
| 使能 UART | `IFC_ENABLE 0x00` | 0x00 | 0x00 | **0x00** | ✅ |
| 设置 DTR/RTS | `SET_MHS 0x07` | 0x07 | 0x07 | **0x07** | ✅ |
| 设置波特率 | `SET_BAUDRATE 0x1E`（4 字节小端） | 0x1E（4 字节） | 0x1E（4 字节） | **0x20（7 字节 CDC）** | ❌ **P0** |
| 设置数据位/校验/停止位 | `SET_LINE_CTL 0x03` | 0x03 | 0x03 | **（完全未发送）** | ❌ **P0** |
| 旧式分频（后备） | `SET_BAUDDIV 0x01`（16 位分频值） | 0x01 | 0x01 | 注释写成 **0x1E**（代码未用） | ⚠️ P2-6 |

**判定理由（三条独立证据）：**
1. `0x20` 是 **USB CDC-PSTN 类请求 `SET_LINE_CODING`**（配套 bmRequestType = **0x21**：class\|interface\|out）；
   本工程却用 **0x41**（vendor\|interface\|out）—— 位域与请求码属于两套不相干的规范。
2. **CP210x 不是 CDC-ACM 设备**：其 `bDeviceClass=0x00`、`bInterfaceClass=0xFF`（vendor-specific），
   由 Silicon Labs 私有 VCP 驱动接管，不会实现 CDC 类请求。
3. Linux 主线 `cp210x.c` 的请求码集合为 `0x00~0x1E` + `0xFF`，**不含 0x20**；
   三份公开实现（内核 / mik3y / felhr）无一使用 0x20。

**失败模式（重要）：** 该请求若被芯片 STALL，`controlTransfer` 返回负值 → `Cp210xDriver.setBaudRate:74-76`
抛出 `IOException("CP210x 设置线控失败")` → `doOpen` 捕获 → 打开失败并提示。**属于"失败可见"而非
"静默跑在错误波特率上"，不会污染采集数据**；但 CP210x 机型将**完全不可用**。

### 2.7 全部 `controlTransfer` 的 bmRequestType 位域审计（17 处）

| 文件:行 | 请求 | 实现 reqtype | 参考 | 判定 |
|---|---|---|---|---|
| Ch340Driver.kt:134 | READ_VERSION 0x5F | 0xC0 | 0xC0 | ✅ |
| Ch340Driver.kt:139 | SERIAL_INIT 0xA1 | 0x40 | 0x40 | ✅ |
| Ch340Driver.kt:143 | MODEM_CTRL 0xA4 | 0x40 | 0x40 | ✅ |
| Ch340Driver.kt:151 | WRITE_REG 0x9A 分频 | 0x40 | 0x40 | ✅ |
| Ch340Driver.kt:161 | WRITE_REG 0x9A LCR | 0x40 | 0x40 | ✅ |
| FtdiDriver.kt:137 | RESET 0x00 | 0x40 | 0x40 | ✅ |
| FtdiDriver.kt:140 | MODEM_CTRL 0x01 | 0x40 | 0x40 | ✅ |
| FtdiDriver.kt:148 | SET_FLOW 0x02 | 0x40 | 0x40 | ✅ |
| FtdiDriver.kt:156 | SET_BAUDRATE 0x03 | 0x40 | 0x40 | 位域 ✅ / wIndex ❌ P1-3 |
| FtdiDriver.kt:160 | SET_DATA 0x04 | 0x40 | 0x40 | ✅ |
| Cp210xDriver.kt:58 | IFC_ENABLE 0x00 | 0x41 | 0x41 | ✅ |
| Cp210xDriver.kt:61 | SET_MHS 0x07 | 0x41 | 0x41 | ✅ |
| Cp210xDriver.kt:74 | **"SET_LINE_CODING" 0x20** | 0x41 | **不存在此请求** | ❌ **P0-1** |
| Pl2303Driver.kt:68 | VENDOR_WRITE 0x01 | 0x40 | 0x40 | ✅ |
| Pl2303Driver.kt:73 | VENDOR_READ 0x01 | 0xC0 | 0xC0 | ✅ |
| Pl2303Driver.kt:79 | SET_LINE 0x20 | 0x21 | 0x21 | ✅ |
| Pl2303Driver.kt:44 | SET_CONTROL 0x22 | 0x21 | 0x21 | ✅ |

**位域（direction / recipient / type）17/17 全部正确** —— 这是自研 USB 驱动最高频的错法，本增量未犯。

### 2.8 device_filter.xml（十进制）↔ UsbSerialIds.kt（十六进制）8/8 换算

| XML vendor-id | XML product-id | 换算 | UsbSerialIds.kt | 判定 |
|---|---|---|---|---|
| 6790 | 29986 | 0x1A86 : 0x7522 | CH340 | ✅ |
| 6790 | 29987 | 0x1A86 : 0x7523 | CH340 | ✅ |
| 1027 | 24577 | 0x0403 : 0x6001 | FTDI FT232R | ✅ |
| 1027 | 24592 | 0x0403 : 0x6010 | FTDI FT2232H | ✅ |
| 1027 | 24596 | 0x0403 : 0x6014 | FTDI FT232H | ✅ |
| 1027 | 24597 | 0x0403 : 0x6015 | FTDI FT231X | ✅ |
| 4292 | 60000 | 0x10C4 : 0xEA60 | CP210x | ✅ |
| 1659 | 8963 | 0x067B : 0x2303 | PL2303 | ✅ |

---

## 3. 问题清单

### P0（必须修）

**P0-1 CP210x 波特率/线控使用了不存在的请求码 0x20（CDC SET_LINE_CODING），且完全未下发 SET_LINE_CTL**
- 位置：`serial/usb/Cp210xDriver.kt:27`（`REQ_SET_LINE_CODING = 0x20`）、`:74`（controlTransfer）、
  `:12-16`（KDoc 依据描述）、`:34-36`（baudDiv KDoc 把 0x1E 误称为 SET_BAUDDIV）
- 依据：§2.6 三条独立证据（CDC 类请求 vs vendor 类请求位域不匹配 / CP210x 为 vendor-specific 非 CDC-ACM /
  内核 cp210x.c 无 0x20，三份公开实现均用 0x1E + 0x03）
- 影响：**CP210x（10C4:EA60）机型 100% 打不开**（失败可见，不会静默错波特率）。CH340/FTDI/PL2303 不受影响
- 修法建议：
  1. `setBaudRate()` 改为 `controlTransfer(0x41, 0x1E /*SET_BAUDRATE*/, 0, 0, baudLE4, 4, timeout)`，
     其中 `baudLE4 = baudRate` 的 4 字节小端；
  2. 返回值 < 0 时回退 `SET_BAUDDIV 0x01`（wValue = `baudDiv(baudRate)`，即 AN571 分频表 —— 注意内核 `SET_BAUDDIV=0x01`、
     `SET_BAUDRATE=0x1E`，现有注释把两者写反了，一并订正）；
  3. 补发 `SET_LINE_CTL 0x03`：`wValue = (dataBits shl 8) or (parity shl 4) or (if (stopBits==2) 2 else 0)`
     （编码以 Linux `cp210x.c` 的 `BITS_DATA_MASK 0x0F00` / `BITS_PARITY_MASK 0x00F0` / `BITS_STOP_MASK 0x000F` 为准，
     与 mik3y 的 `config = dataBits<<8 | parity<<4 | stop` 等价）；
  4. 同步订正 KDoc 与 `README.md:115`（"SET_LINE_CODING CDC 标准请求"改为"SET_BAUDRATE(0x1E)+SET_LINE_CTL(0x03)"）；
  5. 给 `setBaudRate()` 补一条单测（现有 `cp210x_baudDivTable` 只覆盖死代码，见 §4 E2-①）
- 置信度：**高**（三份权威参考一致）；若工程师手上有 AN571 原文能给出 0x20 出处，请以原文为准并回贴证据

### P1（应修）

**P1-1 doOpen 失败路径泄漏 UsbDeviceConnection（未 releaseInterface / 未 close）**
- 位置：`serial/usb/UsbSerialManager.kt:170-200`（`doOpen`），尤其 `:178-181` 与 `:193-199`
- 依据：`val conn = um.openDevice(dev)` 成功后，`:179 createDriver` / `:180 drv.open(conn)`（内部已 `claimInterface`）
  / `:181 drv.setBaudRate(...)` 任一步抛异常 → `catch` 只调 `closeInternal()`，而此时 `port` 仍为 null
  （`:182` 尚未执行），`closeInternal` 的 `port?.let{...}` 直接跳过 → **接口未释放、连接未关闭**
- 后果：再次打开时 `um.openDevice(dev)` 可能返回 null → 提示"openDevice 失败（未授权或被占用）"，
  **拔插重试无效，必须杀进程重启 App**。该路径被 P0-1 放大为 CP210x 上的**必现**问题
- 修法：在 `doOpen` 用 `try/catch/finally` 局部兜底 —— 捕获异常时若 `port == null` 则
  `runCatching { conn.releaseInterface(dev.getInterface(0)); conn.close() }`；
  或把 `port = drv; connection = conn` 提前到 `drv.open(conn)` 之前（驱动 `open` 失败时 `close()` 也能释放）

**P1-2 `UsbSerialPortBase.read()` 吞掉 `bulkTransfer` 负返回码 → 拔线后读线程忙等（CPU 100%）**
- 位置：`serial/usb/UsbSerialPort.kt:97-104`（`return if (n > 0) n else 0`）；`FtdiDriver.kt:169` 同；
  消费端 `UsbSerialManager.kt:214-222`
- 依据：原生 JNI read 是三态（>0 / 0=EAGAIN 休眠 10ms / <0=错误退出线程并通知），USB 侧被压成两态。
  设备拔出后 `bulkTransfer` 通常**立即**返回 -1（不等 40ms 超时）→ `read` 返回 0 → `while(running)` 空转打满一个核，
  直到 `ACTION_USB_DEVICE_DETACHED` 广播到达才停
- 后果：广播延迟/丢失（部分 ROM 有 quirk、App 在后台）期间 CPU 持续满载、发热耗电；且违反
  "read 返回 0/-1 语义对上层一致"的抽象契约
- 修法：`read()` 改为 `if (n < 0) throw IOException("USB bulk 读失败/设备已拔出")` 或返回 -1；
  并在 `startRx` 的 `n == 0` 分支保留短退避（如连续 N 次 0 则 `Thread.sleep(10)`），使 USB 与原生三态语义对齐

**P1-3 FTDI 非 H 系列 SET_BAUDRATE 的 wIndex 未置端口号（常用 8 档波特率全为 0）**
- 位置：`serial/usb/FtdiDriver.kt:101`（`if (withPort) index = (index shl 8) or 1`）、`:156`
- 依据：§2.5 —— 内核 `ftdi_sio.c` 用 `index = priv->interface`（=1）；mik3y 非 H 分支 `index |= 1`；
  本工程非 H 分支 `index` 直接等于子分频位，实测 1200/2400/4800/9600/19200/38400/57600/115200 **全为 0**
- 影响：FT232R / FT231X（OTG 线缆最常见芯片）。对本工程 8 档受支持波特率**无数值影响**
  （见 §2.5 好消息），风险集中在：① 多数 FTDI 单口芯片会忽略 wIndex（故可能长期无症状）；
  ② **FT2232C/FT2232H 等双口芯片严格依赖 wIndex 选端口**，写 0 可能导致配置落到错误端口
- 定级说明：保守定 P1（改动仅 1 行）。若工程师能给出 FTDI 原文证明 wIndex 可为 0，可降为 P2
- 修法：`withPort == false` 时也执行 `index = index or 1`（端口号），即把
  `if (withPort) ... else ...` 改为：
  ```kotlin
  index = if (withPort) (index shl 8) or 1 else index or 1
  ```
  **该修法对本工程 100% 安全**：`SerialPortConfig.BAUD_OPTIONS` 的 8 档（1200/2400/4800/9600/
  19200/38400/57600/115200）经 Python 扫描，subdivisor 分别为 0/0/0/4/2/1/1/0，**全部 ∈ {0,1,2,4}**，
  子分频只编码在 wValue 高 2 位，**index 置 1 不改变任何一档的波特率**。代价仅落在 sub∈{3,5,6,7}
  的非标波特率（如 14400，不在本工程选项内），与 mik3y 行为一致。
- 补测：`UsbBaudCalcTest` 增加"非 H 系列常用波特率 wIndex == 1"的断言（现仅覆盖 sub=3 分支）

### P2（建议）

- **P2-1** `SerialTransport.kt:53-56` `SerialTransportRouter.switchTo()` 不关闭旧 target，靠 MainActivity 自觉。
  建议在 `switchTo` 内 `target?.takeIf { it !== t }?.close()`（close 已幂等），消除未来调用方的踩坑面。
- **P2-2** `UsbSerialManager.kt:139-152`：未授权时先 `onResult(false, "等待 USB 授权")`，授权成功后再
  `onResult(true, ...)` —— **同一个 callback 被回调两次**（先失败后成功）。当前 MainActivity 只写
  `StatusHub.serialText`（最终态正确），但契约被破坏。建议只回调最终态一次，或新增 `onPending(msg)`。
- **P2-3** `UsbSerialManager.kt:103-114`：`requestPermission` 注册的 BroadcastReceiver **仅在收到回执时注销**；
  用户忽略系统弹窗时接收者永久泄漏、callback 悬空。建议加超时（如 30s）后强制注销并回调 false。
- **P2-4** `UsbSerialManager.kt:64` `connection` 字段赋值后从未被读取（死字段，释放实际走 `port.close()`）；
  `:66 device` 与 `:70 appContext` 在后台线程写、主线程（detach 接收者）读，缺 `@Volatile`。
  建议删死字段、`device` 加 `@Volatile`。
- **P2-5** `Pl2303Driver.kt:64`：HX 初始化末步恒写 `0x0002, 0x0024`；mik3y 对 HX 写 `0x0000`、非 HX 才 `0x0024`。
  建议按 `bcdDevice/设备类型` 分支，或保留现状但在注释中写明实测来源（现注释称"HX 上无害"缺依据）。
- **P2-6** `Cp210xDriver.kt:14 / :34-35`：注释把 `0x1E` 称为 `SET_BAUDDIV`，内核 `0x1E=SET_BAUDRATE`、
  `0x01=SET_BAUDDIV`。`baudDiv()` 为未使用的死代码，但错误注释会误导后续维护（随 P0-1 一并订正或删除）。
- **P2-7** `FtdiDriver.kt:180`：`destPos += len` 累加的是**未截断**的 length，而 `copy` 被 `minOf` 截断；
  两者不一致，仅靠"staging(4096) == 读缓冲(4096) 且 (mps-2)/mps < 1"的隐式约定才没越界（Python 30 组仿真已验证当前安全）。
  建议 `destPos += copy`，并把 `READ_CHUNK` 与 `READ_BUFFER_SIZE` 合并为同一常量。
- **P2-8** `README.md:103/107/123` 与实现轻微不符：① "自动列出识别到的 USB 设备" —— 实际只在进入设置屏
  / 点"刷新 USB 设备"时刷新，插线不自动更新；② "插入时系统会自动唤起本应用" —— 只唤起不重连
  （`MainActivity` 未覆写 `onNewIntent`）；③ "页面会显示'不支持的芯片(VID:PID)'" —— 实际文案为
  "未知设备（VID:xxxx/PID:xxxx）"。建议：订正文案，或在 `onNewIntent`/ATTACHED 广播里刷新列表 + 自动重连。
- **P2-9** `FtdiDriver`：未下发 `SIO_SET_LATENCY_TIMER(0x09)`，使用芯片默认 16ms。对 6~8 字节小命令无影响，
  但 2900 字节上行数据帧的组帧延迟会略增；若联调发现上行慢可加 `latency=1`。
- **P2-10** 需首次编译确认的 2 项：① `const val REQTYPE_HOST_TO_DEVICE = UsbConstants.USB_TYPE_VENDOR or
  UsbConstants.USB_DIR_OUT`（4 个驱动各 1~2 处）在 Kotlin 1.8.22 常量表达式检查下是否通过
  （若报 "Const 'val' initializer should be a constant value"，改为字面量 `0x40` / `0xC0` / `0x41` 即可）；
  ② `UsbBaudCalcTest` 调用含 `android.hardware.usb.*` 签名的类，依赖 AGP mockable android.jar 能解析
  （若报 `NoClassDefFoundError`，把 `calcDivisor`/`calcLcr`/`baudDiv` 等纯函数抽到不依赖 `UsbDevice` 的 `object` 即可）。
- **P2-11** `UsbSerialManager.kt:272-276` detach 匹配仅按 VID/PID（同型号两台设备会误关正在用的那台），
  建议同时比对 `deviceName`；`:262` `rxThread?.join(500)` 在 detach 接收者所在主线程执行，
  最坏阻塞 500ms（当前 rx 线程 40ms 超时故实际很短，建议把 join 挪到后台线程）。

---

## 4. 测试质量补充（§1 E2 的三处覆盖缺口）

1. **`cp210x_baudDivTable` 测的是死代码**：`Cp210xDriver.baudDiv()` 在 `setBaudRate()` 中从未被调用
   （`:77` 注释明说"故不采用"）。真正生效的 `0x20` 路径**没有任何用例** —— 这正是 P0-1 未被测试拦住的原因。
   修 P0-1 后应把用例改为断言 `setBaudRate` 实际下发的请求码/数据。
2. **`ftdi_indexEncoding` 只覆盖了 sub=3 分支**：14400 波特 → sub=3 → `index=1`，恰好与"端口号 1"重合，
   掩盖了其余 7 个常用波特率（sub∈{0,2,4} → `index=0`）的错误 —— 这是 P1-3 未被测试拦住的原因。
   建议补 `calcDivisor(9600).index == 1`（非 H）与 `calcDivisor(115200).index == 1` 两条。
3. **无 device_filter.xml ↔ UsbSerialIds 一致性用例**：现有 `usbIds_tableComplete` 只测 Kotlin 侧。
   本次由 `qa-tools/usb_check.py` §[5] 用正则解析 XML 完成 8/8 换算核对；建议后续把该对照做成静态检查
   （或至少在两处文件头互相标注"改动须同步，见 04-USB 增量 QA 报告 §2.8"）。

> 除以上 3 处覆盖缺口外，**现有 4 组共 30 条断言的期望值全部等于内核/官方算法真值**（Python 独立重算
> 64 项 0 FAIL），不存在"测试跟着实现写"的自证问题。

---

## 5. 遗留风险清单（含前两轮移交项）

| 编号 | 级别 | 风险 | 建议 |
|---|---|---|---|
| U1 | P2 | **全程未编译/未实跑**：`gradlew test` 与真机联调均未执行（无 JDK/SDK）。语法/编译错误与真机时序未覆盖（P2-10 两项为最可能的编译雷点） | 首次构建按 `README §2.1` 修 wrapper 后先 `gradlew test`，再真机联调 |
| U2 | P1 | **真机芯片覆盖未验证**：CH340/FTDI/PL2303 仅静态推演通过；CP210x 已知不通（P0-1） | 客户线缆到货后按芯片逐一实测 9600 8N1；优先确认 CP210x 修复 |
| U3 | P2 | CH340 `version > 0x27` 的 `val \|= BIT(7)` 分支依据为内核记忆，未查原文；bit7 位于低字节空闲位，**即使多余也不会污染分频** | 若手上有旧版（version≤0x27）CH340 可回归确认 |
| U4 | P2 | FTDI 子分频"wIndex bit0 = 第二组分数（0.375/0.625/0.75/0.875）"的编码与 mik3y 逐条一致，但 FTDI 原文未核对 | 若 FTDI 高频波特率有偏差，先核对 AN232B-05 的 sub-integer 编码表 |
| U5 | P2 | USB 未做流控（无 RTS/CTS 硬件流控）；仪器为 9600 8N1 短帧，理论无影响 | 联调若丢字节再评估 |
| U6 | P2 | OTG 供电/兼容性依赖客户平板；部分平板不支持 HUB、部分 OTG 线不供电 | README 已列 FAQ；交付时随附已验证线缆型号 |
| F1~F6 | — | 前两轮遗留（delphiReal 逐字节校准、跨块 A0A0 原版怪癖、robust 幽灵帧、未编译、0.5V 档联调、原版怪癖保留项） | 见 `docs/03-QA审查报告.md` §11，本增量未触及，继续有效 |

---

## 6. 路由判定（第 1 轮）

- 判定：**FAIL → Send To: Engineer**
- 统计：**P0×1**（CP210x 请求码）、**P1×3**（连接泄漏 / read 吞错误码 / FTDI wIndex）、**P2×11**
- 必改项：P0-1（CP210x 请求码 + 补 SET_LINE_CTL）、P1-1（doOpen 连接泄漏）、P1-2（read 负返回码）、
  P1-3（FTDI wIndex 端口位）
- 强烈建议一并处理：E2 的 3 处测试覆盖缺口（否则同类问题仍会被测试漏掉）
- 第 2 轮回归重点：① P0-1 修复后 CP210x 请求码与 `SET_LINE_CTL` 编码；② P1-1 失败路径资源释放；
  ③ P1-2 read 三态语义；④ P1-3 FTDI wIndex；⑤ 更新 `UsbBaudCalcTest` 并重跑 `qa-tools/usb_check.py`；
  ⑥ 协议层再次确认零改动（`git diff` 或逐行比对 `ProtocolEngine.kt` / `RxFrameParser.kt`）

---

## 7. 第 2 轮回归（2026-09-07）

- 判定：**PASS**（**P0×0 / P1×0**）。第 1 轮 4 项必改（P0-1 / P1-1 / P1-2 / P1-3）与 E2 三处测试缺口均已就位，未引入新缺陷；协议层确认零改动。

### 7.1 复跑 `qa-tools/usb_check.py`
- 本机 `python3` 可用，重跑脚本：**64 项断言，64 PASS / 0 FAIL**（退出码 0），与第 1 轮完全一致，无离线可判定缺陷。
- 日志见 `qa-tools/usb_check_r2.log`。

### 7.2 4 个修复文件静态逐行复核

| 文件 | 第 1 轮问题 | 修复确认 | 新缺陷 |
|---|---|---|---|
| `Cp210xDriver.kt` | P0-1 | `REQ_SET_BAUDRATE = 0x1E`（L39）；`setBaudRate` 用 `buildBaudRateData`（4 字节小端）下发 0x1E + `REQ_SET_LINE_CTL=0x03`（L107-124）；全文已无 `0x20` / `buildCdcLineCoding` / `baudDiv` 的代码引用（仅注释提及历史误用）；`0x01=SET_BAUDDIV` 仅作留档常量未误用 | 无 |
| `UsbSerialManager.kt` | P1-1 | `doOpen` 在 `drv.open/setBaudRate` **之前**先 `port=drv; connection=conn`（L189-197），失败时 `closeInternal()` 可真正 `releaseInterface + close`；`createDriver` 失败路径也就地关闭 conn（L180-188） | 无 |
| `UsbSerialPort.kt` | P1-2 | `read` 经 `isHardReadError()` 三态契约（L91-104、L132-147）：负值连续 N 次"远早于超时返回"才抛 `IOException`，否则返回 0；不再把负值吞成"无数据" | 无 |
| `FtdiDriver.kt` | P1-3 | `calcDivisor` 末尾 `index = if (withPort) (index shl 8) or 1 else index or 1`（L106）；非 H 系列 8 档波特率 index 恒带端口位 1，H 系列子分频位左移高字节 | 无 |

### 7.3 协议层零改动复核
- `RxFrameParser.kt`：无 `【USB增量】` 标记；`feed()` 整块扫描最后一个 A0A0 + 逐字节累积 + XOR∈{0x00,0x80} + 帧尾 AFAF 语义与 `docs/03-QA审查报告.md` §6 逐条吻合，**一行未动**。
- `ProtocolEngine.kt`：仍为 4 处类型收口（`import SerialTransport` / `serial: SerialTransport` / `start` 参数 / `RxListener` 类型），状态机主体与 `serial.write` / `setRxListener` / `parser.feed` 调用未改；本轮 4 项修复均未触碰协议层。

### 7.4 新增测试断言能拦住原始缺陷（反证）

| 原始缺陷 | 拦住它的新断言 | 反证：若回退修复会被打红 |
|---|---|---|
| P0-1（CP210x 误用 0x20） | `cp210x_requestCodesAreVendorSpecific` | 断言 `REQ_SET_BAUDRATE == 0x1E` 且 `!= 0x20`；回退即用 0x20 → 两条全红 |
| P1-3（FTDI wIndex 端口位） | `ftdi_allConfigBaudsCarryPortBit` + `ftdi_9600/115200/300` 的 `index == 1` | 回退 `else index`（不带 `or 1`）后 9600/115200/300 的 index=0，断言 `index and 0x01 == 1` 红 |
| 缺口③（device_filter ↔ UsbSerialIds 一致性） | `usbIds_matchesDeviceFilterXml` | 任一侧增删芯片致数量/条目不一致 → `assertEquals(count)` / `containsAll` 红 |

> 注：第 1 轮 §4 指出的 `cp210x_baudDivTable` 测死代码、`ftdi_indexEncoding` 只覆盖 sub=3、缺 XML 一致性用例三处，已由上述新断言 + `buildBaudRateData` / `buildLineControl` 用例替换/补全。

### 7.5 遗留风险（继续有效，未因本轮改动消除）
- **U1**（全程未编译/未实跑）、**U2**（真机芯片覆盖未验证）、**U3**（CH340 `version>0x27` bit7 未查原文）、**U4**（FTDI 子分频编码未对原文）、**U5**（无硬件流控）、**U6**（OTG 供电/兼容性）—— 均与本轮 4 项 P0/P1 修复正交，仍需首次编译 + 真机联调关闭。
- 第 1 轮 **P2-1 ~ P2-11**（含 `router.switchTo` 不关旧 transport、权限广播泄漏、HX 初始化末步、剥头 `destPos` 隐式约定、README 不符、未下发 `SIO_SET_LATENCY_TIMER`、编译雷点、detach 仅按 VID/PID 匹配等）主理人已逐文件确认**未在本轮处理**，作为已知项保留，建议后续迭代排期。

### 7.6 结论
- 第 1 轮必改项全部关闭，**PASS（P0×0 / P1×0）**；复跑证据脚本 64/64 通过；协议层零改动；新增断言可在回归中拦住 P0-1 / P1-3 / 缺口③。
- 交付建议：可进入首次编译 + 真机联调（关闭 U1/U2），P2 项按优先级排期。

---

## 附：证据脚本用法

```
cd emrs-android
python qa-tools/usb_check.py      # 退出码 0 = 无离线可判定缺陷
```

输出包含：CH340 分频 10 项、FTDI 分频/编码 17 项、FTDI 剥头 30 组仿真、CDC LINE_CODING 3 项、
PL2303 位域 7 项、VID/PID 换算 9 项，以及 17 处 `controlTransfer` 的位域审计表。
