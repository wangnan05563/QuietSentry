param(
    [ValidateSet('debug', 'release')][string]$Variant = 'debug',
    [switch]$Clean,
    [switch]$SkipPatch
)

# 构建入口：依赖检查 -> 推进 patch 版本 -> 调 Gradle -> 报告产物。
# patch 放在这里而不是 Gradle 里，是为了让“随手点一次 IDE 构建”不改写工作区版本文件。
. (Join-Path $PSScriptRoot 'build-lib.ps1')

try {
    Write-Step "构建 $Variant 包"
    Assert-BuildDependencies

    if ($SkipPatch) {
        $meta = Get-Content $script:PackageJson -Raw | ConvertFrom-Json
        $versionName = [string]$meta.version
        Write-Note "跳过 patch 推进，使用基线版本 $versionName"
    } else {
        $meta = Step-BuildPatch
        Write-Ok "版本 $($meta.VersionName)（versionCode $($meta.BuildNumber)）"
    }

    $task = if ($Variant -eq 'debug') { 'assembleDebug' } else { 'assembleRelease' }
    $tasks = @()
    if ($Clean) { $tasks += 'clean' }
    $tasks += $task
    Write-Step "执行 Gradle: $($tasks -join ' ')"
    Invoke-GradleTask -Tasks $tasks

    $apk = Find-Apk -Variant $Variant
    if (-not $apk) { throw "构建结束但没有找到 $Variant APK" }

    $info = Get-Item $apk
    Write-Step "构建完成"
    Write-Ok "APK: $($info.FullName)"
    Write-Ok "体积: $([math]::Round($info.Length / 1MB, 2)) MB"
    if ($Variant -eq 'release' -and $apk -match '-unsigned') {
        Write-Note '当前 release APK 未签名：填写 keystore.properties 后重新构建，或运行 scripts\一键发布.bat'
    }
    Write-Host ""
} catch {
    Write-Fail $_.Exception.Message
    exit 1
}
