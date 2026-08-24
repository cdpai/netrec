@echo off
rem Assemble the standalone distributable. See make-dist.ps1 for what it does and why.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0make-dist.ps1" %*
exit /b %ERRORLEVEL%
