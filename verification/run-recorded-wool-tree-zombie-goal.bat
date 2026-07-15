@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-recorded-wool-tree-zombie-goal.ps1" %*
exit /b %ERRORLEVEL%
