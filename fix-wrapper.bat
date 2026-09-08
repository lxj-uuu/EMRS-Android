@echo off
rem ============================================================
rem fix-wrapper.bat —— 首次构建前生成 gradle\wrapper\gradle-wrapper.jar
rem
rem 本仓库未附带 wrapper jar（二进制不分发），本脚本用本机已装的
rem Gradle 执行 `gradle wrapper --gradle-version 7.6.4` 自动补齐。
rem 用法：双击运行，或在命令行中于本工程根目录执行。
rem ============================================================
setlocal
cd /d "%~dp0"

if exist "gradle\wrapper\gradle-wrapper.jar" (
    echo [OK] gradle-wrapper.jar 已存在，无需修复。
    pause
    exit /b 0
)

rem ---- 1. 定位本机 Gradle ----
set "GRADLE_CMD="
where gradle >nul 2>&1 && set "GRADLE_CMD=gradle"
if not defined GRADLE_CMD if defined GRADLE_HOME if exist "%GRADLE_HOME%\bin\gradle.bat" set "GRADLE_CMD=%GRADLE_HOME%\bin\gradle.bat"
if not defined GRADLE_CMD for %%D in (
    "C:\Gradle"
    "C:\Program Files\Gradle"
    "%LOCALAPPDATA%\Programs\gradle"
    "%USERPROFILE%\scoop\apps\gradle\current"
) do (
    if exist "%%~D" (
        for /d %%V in ("%%~D\*") do (
            if exist "%%~V\bin\gradle.bat" if not defined GRADLE_CMD set "GRADLE_CMD=%%~V\bin\gradle.bat"
        )
    )
)

if not defined GRADLE_CMD (
    echo [失败] 未找到本机 Gradle。请任选其一：
    echo   1. 安装 Gradle 7.x（或将已有安装的 bin 加入 PATH）后重跑本脚本；
    echo   2. 直接用 Android Studio 打开本工程，按 IDE 提示修复 wrapper；
    echo   3. 手工在本目录执行：gradle wrapper --gradle-version 7.6.4
    pause
    exit /b 1
)

rem ---- 2. 生成 7.6.4 wrapper ----
echo [..] 使用 "%GRADLE_CMD%" 生成 Gradle 7.6.4 wrapper ...
call "%GRADLE_CMD%" wrapper --gradle-version 7.6.4
if errorlevel 1 (
    echo [失败] wrapper 生成失败，请查看上方 Gradle 输出。
    pause
    exit /b 1
)
if exist "gradle\wrapper\gradle-wrapper.jar" (
    echo [OK] gradle-wrapper.jar 已生成。现在可以用 gradlew / Android Studio 正常构建。
) else (
    echo [失败] 未检测到 gradle\wrapper\gradle-wrapper.jar，请手工检查。
)
pause
