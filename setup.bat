@echo off
setlocal
where java >nul 2>&1 || ( echo Java is required ^(>=25^). & exit /b 1 )
where gradle >nul 2>&1
if %ERRORLEVEL% equ 0 (
  echo Using existing Gradle on PATH.
) else (
  set "GRADLE_DIR=.gradle-dist"
  set "GRADLE_VERSION=9.4.0"
  set "ZIP=%GRADLE_DIR%\gradle-%GRADLE_VERSION%-bin.zip"
  if not exist "%GRADLE_DIR%" mkdir "%GRADLE_DIR%"
  if not exist "%ZIP%" (
    echo Downloading Gradle %GRADLE_VERSION%...
    curl -fSL -o "%ZIP%" "https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip"
  )
  echo Extracting...
  tar -xf "%ZIP%" -C "%GRADLE_DIR%"
  set "PATH=%~dp0%GRADLE_DIR%\gradle-%GRADLE_VERSION%\bin;%PATH%"
)
gradle wrapper --gradle-version 9.4.0
echo Wrapper created. You can now use gradlew.bat
