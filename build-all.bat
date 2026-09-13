@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
for /f "delims=" %%d in ('dir /b /ad "%SCRIPT_DIR%.gradle-dist\jdk-25*" 2^>nul') do set "JAVA_HOME=%SCRIPT_DIR%.gradle-dist\%%d"
if defined JAVA_HOME ( set "JAVA=%JAVA_HOME%\bin\java.exe" ) else ( set "JAVA=java" )
set "SERVER_JAR=%SCRIPT_DIR%remote-server\build\libs\remote-server-%APP_VERSION%-fat.jar"
set "CLIENT_BUILD=%SCRIPT_DIR%client-mod\build\libs\client-mod-%APP_VERSION%.jar"
set "MC_MODS=%USERPROFILE%\.minecraft\mods"

echo ============================================
echo Remote Offload - Build Script
echo ============================================

if not exist "%SCRIPT_DIR%.gradle-dist\gradle-9.5.0\bin\gradle.bat" call "%SCRIPT_DIR%setup.bat"
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

set "GRADLE=%SCRIPT_DIR%.gradle-dist\gradle-9.5.0\bin\gradle.bat"

echo.
echo [1/3] Building common...
"%GRADLE%" -p "%SCRIPT_DIR%" :common:build -x test
if %ERRORLEVEL% neq 0 ( echo Build failed. & exit /b 1 )

echo.
echo [2/3] Building server fat-jar...
"%GRADLE%" -p "%SCRIPT_DIR%" :remote-server:fatJar
if %ERRORLEVEL% neq 0 ( echo Build failed. & exit /b 1 )

echo.
echo [3/3] Building Fabric client mod...
"%GRADLE%" -p "%SCRIPT_DIR%" :client-mod:jar
if %ERRORLEVEL% neq 0 ( echo Build failed. & exit /b 1 )

echo.
echo Copies for easy deploy:
if not exist "%MC_MODS%" mkdir "%MC_MODS%"
copy /y "%SCRIPT_DIR%client-mod\build\libs\*.jar" "%MC_MODS%" >nul
copy /y "%SCRIPT_DIR%remote-server\build\libs\remote-server-*-fat.jar" "%SCRIPT_DIR%remote-server\dist\" >nul

echo Build complete.
