param(
    [ValidateSet('debug', 'release')][string]$Variant = 'debug',
    [string]$Serial = '',
    [switch]$Clean,
    [switch]$NoLaunch
)

# 构建 + 推送的组合入口：调试联调时最常用的两步，串起来避免“忘了先构建”。
$buildArgs = @('-Variant', $Variant)
if ($Clean) { $buildArgs += '-Clean' }
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build.ps1') @buildArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$pushArgs = @('-Variant', $Variant)
if ($Serial) { $pushArgs += @('-Serial', $Serial) }
if ($NoLaunch) { $pushArgs += '-NoLaunch' }
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'push.ps1') @pushArgs
exit $LASTEXITCODE
