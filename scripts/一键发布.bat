@echo off
setlocal enabledelayedexpansion
rem ============================================================
rem  QuietSentry 一键发布（构建打包 + GitHub Release，无需手动输入）
rem ------------------------------------------------------------
rem  本文件是「启动器」：真正的流水线在 scripts/release.ps1
rem  （校验依赖 -> 推进版本 -> 签名构建 APK/AAB -> 校验签名 -> 发 GitHub Release）。
rem  复用既有 Powershell 实现，不在批处理里重写业务逻辑。
rem
rem  编码：GBK（无 BOM）+ CRLF。cmd.exe 在中文 Windows 默认 GBK 代码页下读取
rem  批处理最稳；UTF-8 会被按 GBK 误读，3 字节汉字错位触发 > < & 把行拆断。
rem
rem  前置检查项（参考 AirMirror/scripts/一键发布.bat）：
rem   1) 从仓库根 .env 载入 GH_TOKEN / GITHUB_TOKEN（环境已设置优先，不回显值）
rem   2) git 可用（release.ps1 依赖 git remote / git log）
rem   3) Powershell 可用（流水线宿主）
rem   4) gh CLI 可用则优先；否则用 GH_TOKEN 走 GitHub REST
rem   5) -CheckOnly：只跑前置检查，不调用 release.ps1（便于自检）
rem
rem  用法：
rem    双击 一键发布.bat                 （自动构建 + 自动 tag + 发布）
rem    一键发布.bat -NoUpload           （只构建与校验，不发布）
rem    一键发布.bat -SkipBuild          （复用 release/ 现有产物发布）
rem    一键发布.bat -CheckOnly          （只做前置检查，不构建不发布）
rem ============================================================

set "ROOT=%~dp0.."
set "RELEASE_PS=%~dp0release.ps1"
set "ENV_FILE=%ROOT%\.env"

rem --- 参数解析：剥离 -CheckOnly，其余原样透传给 release.ps1 ---
set "CHECK_ONLY="
set "EXTRA="
:parse_args
if "%~1"=="" goto :args_done
if /i "%~1"=="-CheckOnly" (
    set "CHECK_ONLY=1"
) else (
    set "EXTRA=!EXTRA! %1"
)
shift
goto :parse_args
:args_done

rem --- 0. 从 .env 载入 GH_TOKEN / GITHUB_TOKEN（环境已设置则优先）---
if not defined GH_TOKEN (
    for /f "tokens=1,* delims==" %%a in ('findstr /i "^GH_TOKEN=" "%ENV_FILE%" 2^>nul') do set "GH_TOKEN=%%b"
)
if not defined GITHUB_TOKEN (
    for /f "tokens=1,* delims==" %%a in ('findstr /i "^GITHUB_TOKEN=" "%ENV_FILE%" 2^>nul') do set "GITHUB_TOKEN=%%b"
)
if defined GH_TOKEN set "GH_TOKEN=!GH_TOKEN:"=!"
if defined GITHUB_TOKEN set "GITHUB_TOKEN=!GITHUB_TOKEN:"=!"
if defined GH_TOKEN (echo [OK]   GitHub Token 已就绪（环境或 .env）) else (
    if defined GITHUB_TOKEN (echo [OK]   GitHub Token 已就绪（环境或 .env）) else (
        echo [WARN] 未设置 GH_TOKEN/GITHUB_TOKEN 且 .env 未找到；将跳过 GitHub Release（产物仍留在本地）。
    )
)

rem --- 1. 环境检测：git ---
where git >nul 2>nul
if errorlevel 1 (
    echo [FAIL] 未检测到 git。请安装 Git for Windows 并加入 PATH。
    goto :fail
)
for /f "tokens=*" %%v in ('git --version 2^>nul') do echo [OK]   %%v

rem --- 2. 环境检测：Powershell ---
where powershell >nul 2>nul
if errorlevel 1 (
    echo [FAIL] 未检测到 Powershell，无法调用 release.ps1。
    goto :fail
)
echo [OK]   Powershell 可用

rem --- 3. 环境检测：gh CLI（可选，有则优先用于发布）---
where gh >nul 2>nul
if not errorlevel 1 (
    echo [OK]   gh CLI 可用（优先用于发布）
) else (
    echo [OK]   未安装 gh，将以 GH_TOKEN 走 GitHub REST 发布（无 Token 则跳过发布）
)

if defined CHECK_ONLY (
    echo.
    echo [INFO] 透传参数：!EXTRA!
    echo [DONE] 前置检查通过（-CheckOnly，未调用 release.ps1）。
    if not defined NO_PAUSE pause
    exit /b 0
)

echo.
echo [INFO] 调用：powershell -NoProfile -ExecutionPolicy Bypass -File "%RELEASE_PS%" !EXTRA!
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%RELEASE_PS%" !EXTRA!
set "CODE=%ERRORLEVEL%"

if not "%CODE%"=="0" (
    echo [FAIL] 发布流程退出码 %CODE%。详见上方 Powershell 输出。
    goto :fail
)

echo.
echo [DONE] 发布完成。Release 链接见上方 Powershell 输出。
if not defined NO_PAUSE pause
exit /b 0

:fail
if not defined NO_PAUSE pause
exit /b 1
