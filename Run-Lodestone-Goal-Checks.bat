@echo off
setlocal
cd /d "%~dp0"

echo Lodestone Minecraft goal checks
call gradlew.bat :common:goal-engine:test :gateway:mcp-server:test :hosts:neoforge:mc1_21_1:build --no-daemon --console=plain
set "RESULT=%ERRORLEVEL%"

if not "%RESULT%"=="0" (
  echo.
  echo Lodestone goal checks failed with exit code %RESULT%.
  exit /b %RESULT%
)

echo.
echo Lodestone goal checks passed.
exit /b 0
