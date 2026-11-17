@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-and-push.ps1" %*
exit /b %errorlevel%
