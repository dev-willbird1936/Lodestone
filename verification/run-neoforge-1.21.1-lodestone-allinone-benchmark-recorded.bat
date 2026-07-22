@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-neoforge-1.21.1-lodestone-allinone-benchmark-recorded.ps1" %*
exit /b %ERRORLEVEL%
