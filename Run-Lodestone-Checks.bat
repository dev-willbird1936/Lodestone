@echo off
setlocal
cd /d "%~dp0"

echo Lodestone core release-contract checks
call gradlew.bat :common:legacy-java8:test :adapters:legacy-bridge:java:test :verification:contract-tests:test --no-daemon
set "RESULT=%ERRORLEVEL%"

if not "%RESULT%"=="0" (
  echo.
  echo Lodestone checks failed with exit code %RESULT%.
  exit /b %RESULT%
)

echo.
echo Lodestone checks passed.
exit /b 0
