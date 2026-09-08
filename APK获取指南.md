# 拿到 EMRS-Android 的 APK 安装包（客户向指南）

本指南提供两条拿到 `app-debug.apk` 的路：

- **路径 A（推荐）：用 GitHub 云端编译，自己电脑上不用装任何开发环境**，全程鼠标点，约 5~10 分钟拿到 APK。
- **路径 B：在自己电脑上用 Android Studio 本地编译**（适合后续要改代码的场景）。

> APK 名称：`app-debug.apk`（调试版，可直接安装使用；如需正式签名发布版请另说）。
> 本软件要求平板 **Android 7.0（API 24）及以上、ARM 架构 CPU**，横屏 7~10 寸使用。

---

## 路径 A：GitHub 云端编译（推荐，不用装开发环境）

### A-1. 注册 GitHub 账号

打开 https://github.com → Sign up → 用邮箱注册 → 到邮箱点验证链接完成激活。

### A-2. 新建一个空仓库

1. 登录后点右上角 **＋** → **New repository**。
2. Repository name 填：`EMRS-Android`（随便起也行）。
3. 选 **Public**（公共，Actions 免费额度最宽松）或 **Private**（私有，也能用，免费额度 2000 分钟/月，本工程一次构建约 5~10 分钟，够用）。
4. **重要**：下面这几个勾选项**全部不要勾**（勾了仓库就不是空的，第 3 步会冲突）：
   - Add a README file
   - Add .gitignore
   - Choose a license
5. 点 **Create repository**。

### A-3. 上传工程文件（保持目录结构）

创建完会进入一个"Empty repository"页面，点 **uploading an existing file** 这个链接。

把本工程**根目录里的全部内容**拖进去上传，务必包含：

```
app\                          ← 工程源码（必须）
gradle\                       ← Gradle 配置（必须）
.github\                      ← 云端构建工作流（必须！没有它 Actions 页不会出现工作流）
build.gradle                  （必须）
settings.gradle               （必须）
gradle.properties             （必须）
gradlew / gradlew.bat         （必须）
README.md、APK获取指南.md      （文档，可选但建议一起传）
```

上传要点：

- **`.github` 是隐藏目录**（名字以点开头）。Windows 资源管理器里能直接看到并拖进去；如果先压缩成 zip 再解压上传，请确认解压后 `.github` 目录还在。
- 直接在网页上拖整个文件夹时，浏览器通常支持整个目录拖入（也可以分批：先拖 `.github`，再拖 `app`，再拖其余文件）。
- 传完后回到仓库首页，确认能看到 `app`、`.github`、`build.gradle` 这几项。

### A-4. 触发云端构建

1. 仓库页面点上方 **Actions** 标签页。
2. 首次进入会提示 "I understand my workflows, go ahead and enable them" → 点这个按钮启用。
3. 左侧 workflows 列表里点 **Build Android APK**。
4. 右侧点 **Run workflow** → 再点绿色的 **Run workflow**（分支选 main）。
5. 列表里会出现一条正在运行的记录（黄色圆点=进行中，绿色对勾=成功，红叉=失败）。
   **整个过程约 5~10 分钟**（首次会慢一些，要下载 SDK/NDK）。

> 提示：以后每次往 main 分支推代码，也会自动触发一次构建，不用手动点。

### A-5. 下载 APK

构建记录变**绿色对勾**后：

1. 点进这条运行记录（点标题 "Build Android APK" 或那个提交信息）。
2. 拉到页面最下方 **Artifacts**（产物）区域。
3. 点 **EMRS-debug-apk** → 浏览器会下载一个 `EMRS-debug-apk.zip`。
4. 解压 → 得到 **`app-debug.apk`**。
5. 产物默认保留 30 天，过期需重新构建。

### A-6. 装到平板

三种方式任选：

- **USB 拷**：APK 拷到平板存储 → 平板上用"文件管理"打开 APK → 首次会提示"允许安装未知应用"，按提示给文件管理器授权 → 安装。
- **微信/QQ 传**：传到平板后同样用文件管理器打开安装（部分 ROM 需要在设置里单独给微信/QQ 开"允许安装未知应用"）。
- **adb**：`adb install -r app-debug.apk`（仅限已开 USB 调试的场合）。

---

## 路径 B：本地用 Android Studio 编译

适合需要在自己电脑上改代码、或网络不便的场景。

1. 安装 **Android Studio（Flamingo 2023.2 或更新）**：https://developer.android.com/studio
2. 首次启动时在 SDK Manager → **SDK Tools** 里勾选并安装：
   - **NDK（Side by side）**：版本 `21.4.7075529`（若列表里没有，就装最新的 NDK，然后把 `app/build.gradle` 里 `ndkVersion '21.4.7075529'` 这行注释掉）
   - **CMake**：版本 `3.18.1`（同上，没有就装最新的，并把 `cmake { version '3.18.1' }` 改成已装版本）
   - **Android SDK Platform 33**、**Android SDK Build-Tools 33.0.2**
3. **先修复 Gradle Wrapper**（只做一次）：在本工程根目录双击 **`fix-wrapper.bat`**，
   它会自动找到本机 Gradle 并生成缺失的 `gradle/wrapper/gradle-wrapper.jar`。
4. Android Studio → **File → Open** → 选择工程根目录（即含 `settings.gradle` 的那一层）→ 等待 Gradle Sync（首次需联网下载依赖）。
5. 菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**，右下角提示成功后点 **locate**，
   得到 `app\build\outputs\apk\debug\app-debug.apk`。
6. 按 A-6 的方法装到平板。

命令行方式（可选）：

```bash
./gradlew test assembleDebug     # macOS / Linux
gradlew test assembleDebug       # Windows
```

---

## 常见问题排查

### 1. APK 装上后打不开 / 提示"安装包解析失败"

- 平板 Android 版本低于 7.0 → 不支持（本软件 minSdk 24）。
- 平板是 **x86 CPU** 的 Windows 二合一/模拟器 → 不支持：本工程只打包了 `arm64-v8a` 和 `armeabi-v7a` 两种 ARM 架构。
- 安装包下载不完整 → 重新下载解压，或用 USB 重新拷一次。

### 2. GitHub Actions 构建失败（红叉）

点进失败记录 → 点失败的步骤名展开日志，看红色报错行：

- **`SDK location not found` / `licenses not accepted`** → 一般是工作流第 4 步许可协议没过；直接**再 Run workflow 一次**通常就好，仍失败请把日志发给我们。
- **`NDK not configured` / `Requested NDK version ... not available`** → Google 下架了旧 NDK；把 `app/build.gradle` 的 `ndkVersion '21.4.7075529'` 改成 `25.2.9519653`、`cmake { version '3.18.1' }` 改成 `3.22.1`，并把 `.github/workflows/build-apk.yml` 第 4 步里的 `ndk;21.4.7075529`、`cmake;3.18.1` 同步改成 `ndk;25.2.9519653`、`cmake;3.22.1`。
- **`Could not find or load main class org.gradle.wrapper.GradleWrapperMain`** → 说明第 5 步生成 wrapper 失败（多为临时网络问题），重新 Run workflow 即可。
- **`Could not resolve ...` / 依赖下载超时** → 网络波动，重新 Run workflow。
- **`Unsupported class file major version` / JDK 相关报错** → 工作流已固定 JDK 17，出现此报错请把完整日志发给我们。

> 构建失败时，工作流会自动上传一份 `EMRS-build-reports` 报告（同样在 Artifacts 区），把它下载后发给我们，能大幅加快定位。

### 3. Actions 页面里看不到 "Build Android APK" 这个工作流

- 最常见原因：**上传时漏了 `.github` 目录**（隐藏目录），或只上传了 `app` 目录而没传工程根目录的文件。
  回到仓库首页确认能看到 `.github/workflows/build-apk.yml`。
- 也可能是文件放错层级：确保是 `仓库根/.github/workflows/build-apk.yml`，而不是 `仓库根/EMRS-Android/.github/...`。
- 确认仓库默认分支是 `main`（如果建仓库时默认分支叫 `master`，把工作流里的 `branches: [ "main" ]` 改成 `master`，或把分支重命名为 main）。

### 4. 本地 Android Studio 同步/编译失败

- **Gradle Sync 卡住或报 wrapper 错误** → 没执行 `fix-wrapper.bat`，按路径 B 第 3 步做一次。
- **找不到 NDK / CMake** → 见路径 B 第 2 步；或者直接注释掉 `app/build.gradle` 里的 `ndkVersion` 一行用默认 NDK。
- **工程路径含中文或空格** → 换个纯英文短路径（如 `D:\EMRS\`）再打开。
- **杀软/防火墙拦截下载** → 临时关闭或放行 Gradle、Android Studio。

### 5. 平板插上 USB-OTG 转串口线没反应

与 APK 无关，属硬件连接问题，参见 `README.md` 的 **3.3 USB-OTG 连接仪器** 章节：

- 必须用 **OTG 线**（普通 USB 延长线不行，平板不供电、不识别从设备）；
- 部分平板 OTG 供电不足或**不支持 HUB**，直插最稳；
- 支持的芯片：CH340/CH341、FTDI、CP210x、PL2303，其它芯片请在设置屏串口页看提示的 VID/PID 并反馈给我们；
- 插上后需在设置屏 → 串口页把"连接方式"选 **USB-OTG** → 点"授权并连接"，并在系统弹窗里允许 USB 权限。

---

## 附：一次成功构建的正常日志要点

- 第 4 步能看到 `ndk;21.4.7075529`、`cmake;3.18.1` 安装成功；
- 第 5 步能看到 Gradle 版本号输出（7.6.4）；
- 第 6 步 `BUILD SUCCESSFUL`；
- 第 7 步能看到 `app-debug.apk` 及大小（约数 MB）；
- 第 8 步 Artifacts 出现 `EMRS-debug-apk`。
