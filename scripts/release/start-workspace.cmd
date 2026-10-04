@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0release\start-release.ps1" %*
set "workspaceLaunchExit=%ERRORLEVEL%"
if not "%workspaceLaunchExit%"=="0" pause
exit /b %workspaceLaunchExit%
