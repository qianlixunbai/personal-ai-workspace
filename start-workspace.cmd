@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-workspace.ps1" %*
set "workspaceLaunchExit=%ERRORLEVEL%"
echo.
pause
exit /b %workspaceLaunchExit%
