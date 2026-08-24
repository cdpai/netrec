@echo off
rem Starts the browser with netrec's CDP recording port open, and checks that it opened.
rem The desktop shortcut created by install.cmd points here. See start-browser-debug.ps1.
rem
rem   start-browser-debug.cmd                  Vivaldi if present, else Chrome, else Edge
rem   start-browser-debug.cmd -Browser chrome
rem   start-browser-debug.cmd -Force           close a running browser without asking
setlocal EnableDelayedExpansion
set "ARGS=%*"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-browser-debug.ps1" !ARGS!
set "RC=!ERRORLEVEL!"
rem Keep the window up on anything other than a clean start, so the reason can be read.
if not "!RC!"=="0" (
  echo.
  pause
)
exit /b !RC!
