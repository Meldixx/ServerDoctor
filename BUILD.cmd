@echo off
setlocal
cd /d "%~dp0"
title ServerDoctor - Build
where powershell.exe >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Windows PowerShell is not available.
  echo Install JDK 21 and Maven 3.9+ and run: mvn -B clean verify
  if /I not "%~1"=="--no-pause" pause
  exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0BUILD.ps1"
set "RESULT=%ERRORLEVEL%"
echo.
if "%RESULT%"=="0" (
  echo [SUCCESS] target\ServerDoctor-v1.1.2.jar
) else (
  echo [FAILED] Build did not finish. See build.log in this folder.
)
if /I not "%~1"=="--no-pause" pause
exit /b %RESULT%
