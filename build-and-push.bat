@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\build-and-push.ps1" %*
exit /b %errorlevel%
