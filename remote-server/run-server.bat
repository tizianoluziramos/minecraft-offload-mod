@echo off
setlocal
if "%~1"=="" (
  echo Usage: run-server.bat [--port N] [--config path] [--token X]
  exit /b 1
)
java -jar build\libs\remote-server-fat.jar %*