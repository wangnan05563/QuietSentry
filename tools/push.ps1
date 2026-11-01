param(
    [ValidateSet('debug', 'release')][string]$Variant = 'debug',
    [string]$Serial = '',
    [switch]$NoLaunch
)

# 把已构建的 APK 推到设备：adb 检测 -> 安装（保留数据）-> 强制重启界面。
# 不做自动授权：相机/通知权限属于用户知情范围，脚本代授会让“值守必然可见”的约束失效。
. (Join-Path $PSScriptRoot 'build-lib.ps1')

try {
    $apk = Find-Apk -Variant $Variant
    if (-not $apk) {
        Write-Fail "没有找到 $Variant APK，请先运行 build.bat"
        exit 1
    }

    Write-Step "检测已连接设备"
    $serials = @(Get-AdbSerials)
    if ($serials.Count -eq 0) {
        Write-Fail '没有可用设备。请开启 USB 调试，或用 adb connect 连接无线调试。'
        exit 1
    }
    if (-not $Serial) {
        if ($serials.Count -eq 1) {
            $Serial = $serials[0]
        } elseif ([Console]::IsInputRedirected) {
            # 非交互执行时不能默默选第一台：可能装到错误的设备上，而且用户看不到任何提示
            Write-Fail "检测到多台设备，请用 -Serial 指定：`n  $($serials -join "`n  ")"
            exit 1
        } else {
            Write-Host '检测到多台设备：'
            for ($i = 0; $i -lt $serials.Count; $i++) { Write-Host "  [$i] $($serials[$i])" }
            $answer = Read-Host '选择设备序号'
            $index = [int]$answer
            if ($index -lt 0 -or $index -ge $serials.Count) { throw '设备序号越界' }
            $Serial = $serials[$index]
        }
    }
    if ($serials -notcontains $Serial) {
        Write-Fail "指定的设备不在线：$Serial"
        exit 1
    }
    Write-Ok "目标设备 $Serial"

    $tc = Get-Toolchain
    Write-Step "安装 $Variant APK"
    & $tc.Adb -s $Serial install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "adb install 失败（退出码 $LASTEXITCODE）" }
    Write-Ok '安装完成'

    if (-not $NoLaunch) {
        & $tc.Adb -s $Serial shell am force-stop com.quietsentry.app | Out-Null
        & $tc.Adb -s $Serial shell am start -n com.quietsentry.app/.MainActivity | Out-Null
        Write-Ok '已启动 MainActivity'
    }

    $versionLine = (& $tc.Adb -s $Serial shell dumpsys package com.quietsentry.app | Select-String 'versionName')
    if ($versionLine) { Write-Ok ($versionLine.ToString().Trim()) }
    Write-Host ""
} catch {
    Write-Fail $_.Exception.Message
    exit 1
}
