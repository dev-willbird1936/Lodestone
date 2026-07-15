@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-recorded-survival-tree-goal.ps1" %*
exit /b %ERRORLEVEL%
