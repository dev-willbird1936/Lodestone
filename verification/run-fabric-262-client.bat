@echo off
setlocal
if "%LODESTONE_TOKEN%"=="" (
  echo LODESTONE_TOKEN is required.
  exit /b 2
)
if "%LODESTONE_PORT%"=="" set LODESTONE_PORT=37832
if "%LODESTONE_PERMISSIONS%"=="" set LODESTONE_PERMISSIONS=observe,capture-screen,communicate,control-player,modify-world,administer-server,manage-files-or-processes
set "GRADLE_EXE=%~dp0..\gradlew.bat"
if exist "%USERPROFILE%\scoop\apps\gradle-bin\current\bin\gradle.bat" set "GRADLE_EXE=%USERPROFILE%\scoop\apps\gradle-bin\current\bin\gradle.bat"
call "%GRADLE_EXE%" --no-daemon --console=plain -PincludeForge=false -PincludeForge121=false -PincludeForge192=false -PincludeForge182=false -PincludeForge165=false -PincludeFabric262=true -PincludeModern=true -p "%~dp0.." "-Dlodestone.port=%LODESTONE_PORT%" "-Dlodestone.token=%LODESTONE_TOKEN%" "-Dlodestone.permissions=%LODESTONE_PERMISSIONS%" :hosts:fabric:mc1_26_2:runClient
exit /b %ERRORLEVEL%
