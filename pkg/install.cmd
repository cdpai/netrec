@echo off
rem netrec installer. Double-click this, or run it from a terminal. It does two things:
rem points netrec.exe at the jar sitting next to it, and puts this folder on your PATH.
rem
rem The work is in install.ps1 -- this shim exists so the file is double-clickable and so
rem PowerShell's execution policy cannot refuse to run it.
rem
rem   install.cmd                just install
rem   install.cmd -NoPath        stamp the jar path but leave PATH alone
rem   install.cmd -NoPause       do not wait for a keypress at the end (for scripts)
setlocal EnableDelayedExpansion
set "ARGS=%*"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" !ARGS!
set "RC=!ERRORLEVEL!"
rem A double-click gets a window that would otherwise vanish before it could be read, so pause
rem by default; a script passes -NoPause, because a pause with no keyboard behind it just hangs.
echo !ARGS! | find /i "-NoPause" >nul
if errorlevel 1 (
  echo.
  pause
)
exit /b !RC!
