@echo off
setlocal
cd /d "%~dp0"

echo Lodestone Minecraft goal checks
py -3 -m py_compile verification\goal-orchestrator-milestone1.py
if errorlevel 1 exit /b %ERRORLEVEL%
py -3 -m unittest discover -s verification -p "test_goal_orchestrator_milestone1_*.py"
if errorlevel 1 exit /b %ERRORLEVEL%
py -3 -m unittest discover -s verification\goal_orchestrator_draft\tests -p "test_*.py"
if errorlevel 1 exit /b %ERRORLEVEL%
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
