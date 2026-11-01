@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\push.ps1" %*
exit /b %errorlevel%
