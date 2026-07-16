@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-recorded-reach-nether-goal.ps1" %*
endlocal
