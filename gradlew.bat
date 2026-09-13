@echo off
setlocal
where gradle >nul 2>&1
if %ERRORLEVEL% neq 0 (
  echo Gradle is not installed or not on PATH.
  echo Install Gradle 9.4.0 or set PATH. To bootstrap automatically run: setup.bat
  exit /b 1
)
gradle -p "%~dp0" %*
