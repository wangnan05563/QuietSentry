@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0clean.ps1" %*
exit /b %errorlevel%
