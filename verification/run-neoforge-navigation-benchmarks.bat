@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-neoforge-navigation-benchmarks.ps1" %*
exit /b %ERRORLEVEL%
