@echo off
setlocal
if "%LODESTONE_TOKEN%"=="" (
  echo LODESTONE_TOKEN is required.
  exit /b 2
)
if "%LODESTONE_PORT%"=="" set LODESTONE_PORT=37830
if "%LODESTONE_PERMISSIONS%"=="" set LODESTONE_PERMISSIONS=observe,capture-screen,communicate,control-player,modify-world,administer-server,manage-files-or-processes
call "%~dp0..\gradlew.bat" --no-daemon --console=plain -p "%~dp0..\hosts\fabric\1.21.1" "-Dlodestone.port=%LODESTONE_PORT%" "-Dlodestone.token=%LODESTONE_TOKEN%" "-Dlodestone.permissions=%LODESTONE_PERMISSIONS%" runClient
exit /b %ERRORLEVEL%
