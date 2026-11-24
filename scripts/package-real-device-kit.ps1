param(
    [Parameter(Mandatory)][string]$Apk,
    [string]$Aab = '',
    [string]$OutDir = ''
    ,
    [switch]$KeepStage
)

# 真机执行包：把工具链、公共库、文档和 APK 一起打包，避免只复制三个脚本导致
# real_device_smoke.py 在设备端 import ui_walk 失败、soak-inject 找不到 build-lib。
# 本脚本只做本地打包与自检，不构建 APK，不修改项目源码。

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutDir) { $OutDir = Join-Path $ProjectRoot 'release' }
# 允许 -OutDir release 这类相对路径；不转绝对路径时，Push-Location 后
# $checkFile 会被相对路径二次拼接，自检找不到文件。
if (-not [System.IO.Path]::IsPathRooted($OutDir)) {
    $OutDir = Join-Path $ProjectRoot $OutDir
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Fail([string]$Message) {
    Write-Host "  X   $Message" -ForegroundColor Red
    exit 1
}

if (-not (Test-Path -LiteralPath $Apk)) { Fail "APK 不存在：$Apk" }
if ($Aab -and -not (Test-Path -LiteralPath $Aab)) { Fail "AAB 不存在：$Aab" }
$apkInfo = Get-Item -LiteralPath $Apk
if ($apkInfo.Length -lt 2) { Fail "APK 为空：$Apk" }
$apkHeader = [System.IO.File]::ReadAllBytes($Apk)[0..1]
if ($apkHeader[0] -ne 0x50 -or $apkHeader[1] -ne 0x4B) {
    Fail "APK 不是有效 ZIP/APK：$Apk（长度 $($apkInfo.Length)）"
}
if ($Aab) {
    $aabInfo = Get-Item -LiteralPath $Aab
    if ($aabInfo.Length -lt 2) { Fail "AAB 为空：$Aab" }
    $aabHeader = [System.IO.File]::ReadAllBytes($Aab)[0..1]
    if ($aabHeader[0] -ne 0x50 -or $aabHeader[1] -ne 0x4B) {
        Fail "AAB 不是有效 ZIP/AAB：$Aab（长度 $($aabInfo.Length)）"
    }
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$stage = Join-Path $OutDir "real-device-kit-$stamp"
if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null

$files = @(
    'tools\verify\real_device_smoke.py',
    'tools\verify\ui_walk.py',
    'tools\verify\soak-inject.ps1',
    'tools\verify\stability-7d.ps1',
    'tools\verify\real-device\README.md',
    'scripts\build-lib.ps1',
    'package.json',
    'docs\05-兼容性与稳定性验证矩阵.md'
)

foreach ($relative in $files) {
    $source = Join-Path $ProjectRoot $relative
    if (-not (Test-Path -LiteralPath $source)) { Fail "打包源文件缺失：$source" }
    $target = Join-Path $stage $relative
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
    Copy-Item -LiteralPath $source -Destination $target -Force
}

# APK 与可选 AAB 放在包根，README 的示例命令可直接使用。
Copy-Item -LiteralPath $Apk -Destination (Join-Path $stage (Split-Path -Leaf $Apk)) -Force
if ($Aab) {
    Copy-Item -LiteralPath $Aab -Destination (Join-Path $stage (Split-Path -Leaf $Aab)) -Force
}

# 关键自检：不是只看文件存在，而是真的 import；同时解析两个入口脚本。
$check = @'
import pathlib
import sys
sys.path.insert(0, str(pathlib.Path("tools/verify").resolve()))
import ui_walk
print("ui_walk import OK")
'@
$checkFile = Join-Path $stage 'tools\verify\_import-check.py'
[System.IO.File]::WriteAllText($checkFile, $check, (New-Object System.Text.UTF8Encoding($false)))
Push-Location $stage
try {
    $python = Get-Command python.exe -ErrorAction SilentlyContinue
    if (-not $python) { $python = Get-Command python -ErrorAction SilentlyContinue }
    if (-not $python) { Fail '未找到 python，无法做 ui_walk 导入自检' }
    & $python.Source $checkFile
    if ($LASTEXITCODE -ne 0) { Fail 'ui_walk 导入自检失败' }

    foreach ($script in @('tools\verify\soak-inject.ps1', 'tools\verify\stability-7d.ps1')) {
        $errors = $null
        [System.Management.Automation.Language.Parser]::ParseFile(
            (Resolve-Path -LiteralPath $script).Path, [ref]$null, [ref]$errors
        ) | Out-Null
        if ($errors.Count -gt 0) {
            $errors | ForEach-Object { Write-Host $_.Message }
            Fail "$script 语法检查失败"
        }
    }
    Write-Host "   OK  ui_walk 导入 + PowerShell 语法自检"
} finally {
    Pop-Location
}
Remove-Item -LiteralPath $checkFile -Force

# 清单：明确记录包内每个文件的 SHA256，交付后可复核。
$manifest = Get-ChildItem -LiteralPath $stage -Recurse -File |
    Sort-Object FullName |
    ForEach-Object {
        $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $([System.IO.Path]::GetRelativePath($stage, $_.FullName))"
    }
$manifestPath = Join-Path $stage 'MANIFEST.sha256'
[System.IO.File]::WriteAllText($manifestPath, ($manifest -join "`r`n") + "`r`n", (New-Object System.Text.UTF8Encoding($false)))

$zip = Join-Path $OutDir "QuietSentry-real-device-kit-$stamp.zip"
if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zip -CompressionLevel Optimal
if (-not (Test-Path -LiteralPath $zip)) { Fail "ZIP 未生成：$zip" }

if (-not $KeepStage) { Remove-Item -LiteralPath $stage -Recurse -Force }
$zipHash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Host "  OK  真机执行包：$zip"
Write-Host "  OK  SHA256：$zipHash"
Write-Host "  ..  包内已包含 ui_walk.py、build-lib.ps1、README 与 docs/05；APK：$(Split-Path -Leaf $Apk)"
