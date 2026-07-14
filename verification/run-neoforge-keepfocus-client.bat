@echo off
setlocal

if "%LODESTONE_TOKEN%"=="" (
  echo Set LODESTONE_TOKEN before launching this client.
  exit /b 1
)

if "%LODESTONE_PORT%"=="" set "LODESTONE_PORT=37828"

call "%~dp0..\gradlew.bat" --no-daemon --console=plain "-Dlodestone.port=%LODESTONE_PORT%" "-Dlodestone.token=%LODESTONE_TOKEN%" "-Dlodestone.permissions=%LODESTONE_PERMISSIONS%" :hosts:neoforge:mc1_21_1:runKeepFocusClient
exit /b %ERRORLEVEL%
