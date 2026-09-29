@echo off
setlocal EnableExtensions
cd /d "%~dp0"
chcp 65001 >nul

echo Starting Paper 1.20.1 from: %CD%

where java >nul 2>nul
if errorlevel 1 (
    echo ERROR: Java was not found. Install Java 17 and add it to PATH.
    if /i not "%~1"=="--service" pause
    exit /b 1
)

if not exist "Paper-1.20.1.jar" (
    echo ERROR: Paper-1.20.1.jar was not found in this folder.
    if /i not "%~1"=="--service" pause
    exit /b 1
)

findstr /i /x /c:"eula=true" "eula.txt" >nul 2>nul
if errorlevel 1 (
    echo ERROR: EULA is not accepted. Review eula.txt and set eula=true if you agree.
    if /i not "%~1"=="--service" pause
    exit /b 1
)

call java -Dfile.encoding=UTF-8 -Xms2G -Xmx4G -jar "Paper-1.20.1.jar" nogui
set "serverExit=%errorlevel%"

echo.
if not "%serverExit%"=="0" (
    echo ERROR: Paper exited with code %serverExit%.
    echo Check logs\latest.log. If you see a world/session lock error, stop the other Paper process first.
    echo Do not delete world\session.lock while another server may be running.
) else (
    echo Paper stopped normally.
)
if /i not "%~1"=="--service" pause
exit /b %serverExit%
