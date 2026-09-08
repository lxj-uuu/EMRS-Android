@rem EMRS-Android Gradle 启动脚本（简化版，请优先用 Android Studio 打开工程构建）
@rem 若 gradle\wrapper\gradle-wrapper.jar 不存在，请在 Android Studio 中同步工程，
@rem 或在装有 Gradle 的机器上执行: gradle wrapper
@echo off
set DIRNAME=%~dp0
set CLASSPATH=%DIRNAME%gradle\wrapper\gradle-wrapper.jar
if exist "%CLASSPATH%" (
  set JAVACMD=java
  if defined JAVA_HOME set JAVACMD=%JAVA_HOME%\bin\java.exe
  "%JAVACMD%" -Xmx64m -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
) else (
  echo gradle-wrapper.jar 不存在。请用 Android Studio 打开本工程构建，或执行: gradle wrapper
  exit /b 1
)
