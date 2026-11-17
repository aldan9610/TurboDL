@echo off
rem ============================================================
rem  TurboDL - Compilacion OFFLINE (Windows)
rem  Genera el APK release SIN conexion a internet.
rem  Requisito previo: haber ejecutado prepare_offline.bat una
rem  vez con internet (deja cacheado Gradle/AGP/Kotlin), tener
rem  JDK 17+ y el SDK de Android instalado. Las dependencias de
rem  la app viajan vendidas en app\libs (no se descarga nada).
rem Uso:  cmd /c scripts_compilacion\build_offline.bat
rem ============================================================
setlocal
cd /d "%~dp0.."

if "%JAVA_HOME%"=="" (
  echo   AVISO: JAVA_HOME no definido; se usara el java del PATH.
)
if "%ANDROID_HOME%"=="" (
  if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
)
if "%ANDROID_HOME%"=="" (
  echo   ERROR: define ANDROID_HOME con la ruta del SDK de Android.
  exit /b 1
)

echo Compilando SIN conexion...
call gradlew.bat --offline assembleRelease --no-daemon
if errorlevel 1 (
  echo Fallo. Si es la primera vez en este equipo ejecuta antes
  echo scripts_compilacion\prepare_offline.bat con internet.
  exit /b 1
)

if not exist release mkdir release
copy /y app\build\outputs\apk\release\TurboDL-*.apk release\ >nul
echo.
echo APK generado en release\
endlocal
