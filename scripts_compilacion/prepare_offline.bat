@echo off
rem ============================================================
rem  TurboDL - Preparacion para compilacion offline (Windows)
rem  Ejecutar UNA VEZ con internet. Deja el cache de Gradle
rem  listo para que las siguientes compilaciones NO necesiten
rem  conexion (las dependencias de la app ya estan vendidas
rem  en app\libs, y esto descarga/cachea Gradle + AGP + Kotlin).
rem Uso:  doble clic, o:  cmd /c scripts_compilacion\prepare_offline.bat
rem ============================================================
setlocal
cd /d "%~dp0.."

echo [1/2] Verificando Java y el SDK de Android...
if "%JAVA_HOME%"=="" (
  echo   AVISO: JAVA_HOME no esta definido. Se usara el "java" del PATH.
  echo   Define JAVA_HOME a un JDK 17+ antes de compilar sin internet.
)
if "%ANDROID_HOME%"=="" (
  if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
)
if "%ANDROID_HOME%"=="" (
  echo   ERROR: ANDROID_HOME no esta definido y no se encontro el SDK.
  echo   Instala el SDK de Android y define ANDROID_HOME.
  exit /b 1
)
echo   JAVA_HOME=%JAVA_HOME%
echo   ANDROID_HOME=%ANDROID_HOME%

echo [2/2] Compilando una vez CON internet para poblar el cache...
call gradlew.bat assembleRelease --no-daemon
if errorlevel 1 (
  echo Fallo la compilacion inicial. Revisa el error de arriba.
  exit /b 1
)

echo.
echo Listo. A partir de ahora usa scripts_compilacion\build_offline.bat
echo (o: gradlew.bat --offline assembleRelease) sin conexion.
endlocal
