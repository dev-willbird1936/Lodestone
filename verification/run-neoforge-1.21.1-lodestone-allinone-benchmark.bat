@echo off
setlocal
if "%LODESTONE_TOKEN%"=="" set LODESTONE_TOKEN=%RANDOM%%RANDOM%%RANDOM%
if "%LODESTONE_PORT%"=="" set LODESTONE_PORT=37831
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0neoforge-1.21.1-lodestone-allinone-benchmark.ps1" -Port %LODESTONE_PORT% -Token "%LODESTONE_TOKEN%" %*
exit /b %ERRORLEVEL%
