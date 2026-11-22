param(
    [ValidateSet('debug', 'release')][string]$Variant = 'debug',
    [switch]$Clean,
    [switch]$SkipPatch
)

# 构建入口：依赖检查 -> 推进 patch 版本 -> 调 Gradle -> 报告产物。
# 实际构建链在 build-lib.ps1 的 Invoke-BuildChain，一键发布复用同一套实现，
# 两个入口不再各写一份 Gradle 调用、产物命名与清单格式。
# patch 放在构建链里而不是 Gradle 里，是为了让“随手点一次 IDE 构建”不改写工作区版本文件。
. (Join-Path $PSScriptRoot 'build-lib.ps1')

try {
    Write-Step "构建 $Variant 包"

    # release 变体同时产出 AAB：这样「一键发布 -SkipBuild」能直接发布这一套，
    # 不必再为了一个 AAB 重跑一遍全量构建
    $built = Invoke-BuildChain -Variant $Variant -Clean:$Clean -SkipPatch:$SkipPatch `
        -IncludeBundle:($Variant -eq 'release')

    $info = Get-Item -LiteralPath $built.Apk
    Write-Step '构建完成'
    Write-Ok "APK: $($info.FullName)"
    Write-Ok "体积: $([math]::Round($info.Length / 1MB, 2)) MB"
    if ($built.Aab) {
        $aabInfo = Get-Item -LiteralPath $built.Aab
        Write-Ok "AAB: $($aabInfo.FullName)（$([math]::Round($aabInfo.Length / 1MB, 2)) MB）"
    }
    Write-Ok "产物目录: $($built.ReleaseDir)（可整体删除后重建）"

    if ($Variant -eq 'release') {
        if ($built.ApkName -match '-unsigned') {
            Write-Note '当前 release APK 未签名：填写 keystore.properties 后重新构建，或运行 scripts\一键发布.bat'
        } else {
            Write-Note '这套产物可直接发布：scripts\一键发布.bat -SkipBuild（省掉重复构建）'
        }
    }
    Write-Host ""
} catch {
    Write-Fail $_.Exception.Message
    exit 1
}
