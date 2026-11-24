#requires -Version 7
param(
    [Parameter(Mandatory)][ValidateSet('D3', 'D4', 'D7')][string]$Step,
    [string]$Serial = '',
    [int]$Count = 0,
    [int]$IntervalMin = 0,
    [string]$OutDir = '',
    [string]$Apk = '',
    [int]$ArmTimeoutSec = 420,
    [switch]$NoAutoArm,
    [switch]$Force,
    [switch]$DryRun
)

# D3/D4/D7 注入助手（对应 docs/05 §4）。
#
# 只做“注入 + 采样 + 留档”，不替用户判定整轮 7 天是否 PASS：
# - D3 强杀：可自动重新武装（--arm）后 force-stop； -NoAutoArm 时由人工重开 App。
# - D4 网络抖动：只切 Wi-Fi，不碰移动数据；结束后确保 Wi-Fi 恢复。
# - D7 重启：会真的重启设备，必须显式加 -Force；不做“软重启冒充断电”。
#
# 用法：
#   tools\verify\soak-inject.ps1 -Step D3 -Serial <serial>
#   tools\verify\soak-inject.ps1 -Step D4 -Serial <serial> -Count 3 -IntervalMin 1
#   tools\verify\soak-inject.ps1 -Step D7 -Serial <serial> -Force
#   tools\verify\soak-inject.ps1 -Step D7 -Serial <serial> -DryRun
#
# D2 断电、D5 边充边录、D6 存储压力需要外部电源/散热/填盘，不在本脚本里伪装自动化。

. (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'scripts\build-lib.ps1')

$ErrorActionPreference = 'Stop'
$script:ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$script:SmokeScript = Join-Path $script:ProjectRoot 'tools\verify\real_device_smoke.py'
$script:Package = 'com.quietsentry.app'

$adb = (Get-Toolchain).Adb
if (-not $adb) {
    Write-Fail '未找到 adb，请安装 Android platform-tools'
    exit 1
}
if (-not $Serial) {
    $serials = @(Get-AdbSerials)
    if ($serials.Count -ne 1) {
        Write-Fail '检测到多台设备或无设备，请用 -Serial 指定唯一设备'
        exit 1
    }
    $Serial = $serials[0]
}
if (-not (Test-Path -LiteralPath $script:SmokeScript)) {
    Write-Fail "缺少 real_device_smoke.py：$script:SmokeScript"
    exit 1
}
if (-not $OutDir) {
    $OutDir = Join-Path $script:ProjectRoot 'verify-output\soak'
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$csvPath = Join-Path $OutDir "inject-$Step-$stamp.csv"
$logPath = Join-Path $OutDir "inject-$Step-$stamp.log"
$header = 'time,step,cycle,action,pid,service_running,notification,result'
[System.IO.File]::WriteAllText($csvPath, $header + "`r`n", (New-Object System.Text.UTF8Encoding($false)))
$script:FirstArm = $true

function Invoke-Adb {
    param([string[]]$CommandArgs, [int]$TimeoutSec = 90)
    # adb 的 stderr 在 Stop 模式下会变成终止错误；统一按文本合并。
    # 不用 Start-Job：-ArgumentList 会把数组摊平成多个位置参数，容易丢命令。
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        return ((& $adb -s $Serial @CommandArgs) 2>&1 | Out-String).Trim()
    } catch {
        return $_.Exception.Message
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Get-PidValue {
    $text = Invoke-Adb @('shell', 'pidof', $script:Package)
    if ($text -match '(\d+)') { return [int]$Matches[1] }
    return 0
}

function Test-ServiceRunning {
    $text = Invoke-Adb @('shell', 'dumpsys', 'activity', 'services', $script:Package)
    return ($text -match '\.CaptureService') -and ($text -match 'isForeground=true')
}

function Test-Notification {
    # 只认当前 NotificationRecord，避免历史/AppSettings 里的包名造成假阳性
    $text = Invoke-Adb @('shell', 'dumpsys', 'notification', '--noredact')
    return [bool]($text -match 'NotificationRecord\([^\n]*pkg=com\.quietsentry\.app')
}

function Get-Sample {
    return [pscustomobject]@{
        Time            = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
        Pid             = Get-PidValue
        ServiceRunning  = [bool](Test-ServiceRunning)
        Notification    = [bool](Test-Notification)
    }
}

function Write-Row {
    param(
        [string]$Action,
        [int]$Cycle,
        [object]$Sample,
        [string]$Result
    )
    $row = "$($Sample.Time),$Step,$Cycle,$Action,$($Sample.Pid),$($Sample.ServiceRunning),$($Sample.Notification),$Result"
    Add-Content -Path $csvPath -Value $row -Encoding UTF8
    Add-Content -Path $logPath -Value $row -Encoding UTF8
    Write-Host ("  [{0}] {1} pid={2} 服务={3} 通知={4} -> {5}" -f `
        $Sample.Time, $Action, $Sample.Pid, $Sample.ServiceRunning, $Sample.Notification, $Result)
}

function Start-Guard {
    param([switch]$AllowInstall)
    if ($DryRun) {
        Write-Note "DRY-RUN: would arm guard via $script:SmokeScript"
        return $true
    }
    $python = (Get-Command python.exe -ErrorAction SilentlyContinue)
    if (-not $python) {
        $python = (Get-Command python -ErrorAction SilentlyContinue)
    }
    if (-not $python) {
        Write-Fail '未找到 python，无法调用 real_device_smoke.py --arm'
        return $false
    }
    $pythonPath = if ($python.Path) { $python.Path } elseif ($python.Source) { $python.Source } else { $python.Definition }
    $args = @(
        $script:SmokeScript, '--serial', $Serial, '--arm',
        '--out', (Join-Path $OutDir 'arm')
    )
    if ($AllowInstall -and $script:FirstArm -and $Apk) {
        $args += @('--apk', $Apk)
    } else {
        $args += '--skip-install'
    }
    $script:FirstArm = $false
    Write-Note "重新武装：python $($args -join ' ')（超时 ${ArmTimeoutSec}s）"
    try {
        $proc = Start-Process -FilePath $pythonPath -ArgumentList $args -NoNewWindow -PassThru
        if (-not $proc.WaitForExit($ArmTimeoutSec * 1000)) {
            try { $proc.Kill() } catch {}
            Write-Fail "arm 超过 ${ArmTimeoutSec}s，已终止；下一轮会再试"
            return $false
        }
        return ($proc.ExitCode -eq 0)
    } catch {
        Write-Fail "arm 启动失败：$($_.Exception.Message)"
        return $false
    }
}

function Wait-ForBoot {
    param([int]$TimeoutSec = 300)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    do {
        $state = Invoke-Adb @('get-state') -TimeoutSec 20
        if ($state -match 'device') {
            $boot = Invoke-Adb @('shell', 'getprop', 'sys.boot_completed') -TimeoutSec 20
            if ($boot.Trim() -eq '1') { return $true }
        }
        Start-Sleep -Seconds 5
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Set-Wifi {
    param([bool]$Enabled)
    $action = if ($Enabled) { 'enable' } else { 'disable' }
    $out = Invoke-Adb @('shell', 'svc', 'wifi', $action) -TimeoutSec 30
    if ($out -match 'not found|Unknown|Error|Exception') {
        $state = if ($Enabled) { 'enabled' } else { 'disabled' }
        $out = Invoke-Adb @('shell', 'cmd', 'wifi', 'set-wifi-enabled', $state) -TimeoutSec 30
    }
    return $out
}

function Take-AfterShot {
    param([string]$Name)
    if ($DryRun) { return }
    $remote = "/sdcard/qs-$Name.png"
    Invoke-Adb @('shell', 'screencap', '-p', $remote) | Out-Null
    $local = Join-Path $OutDir "after-$Step-$stamp.png"
    Invoke-Adb @('pull', $remote, $local) | Out-Null
    Invoke-Adb @('shell', 'rm', '-f', $remote) | Out-Null
}

if ($Step -eq 'D3' -and $Count -le 0) { $Count = 5 }
if ($Step -eq 'D3' -and $IntervalMin -le 0) { $IntervalMin = 30 }
if ($Step -eq 'D4' -and $Count -le 0) { $Count = 10 }
if ($Step -eq 'D4' -and $IntervalMin -le 0) { $IntervalMin = 1 }
if ($Step -eq 'D7' -and $Count -le 0) { $Count = 5 }
if ($Step -eq 'D7' -and $IntervalMin -le 0) { $IntervalMin = 2 }

Write-Step "注入 $Step：$Serial，Count=$Count，间隔=$IntervalMin 分钟"
Write-Note "CSV: $csvPath"
if ($DryRun) { Write-Note 'DRY-RUN：只打印计划，不执行 force-stop / 切 Wi-Fi / 重启' }

$initial = Get-Sample
Write-Row -Action 'initial' -Cycle 0 -Sample $initial -Result 'INFO'

switch ($Step) {
    'D3' {
        for ($i = 1; $i -le $Count; $i++) {
            Write-Step "D3 第 $i/$Count 次强杀"
            if (-not $NoAutoArm) {
                if (-not (Test-ServiceRunning)) {
                    $armed = Start-Guard -AllowInstall
                    if (-not $armed) {
                        Write-Fail "第 $i 次重新武装失败；这不是强杀结果，终止本轮避免误判"
                        exit 2
                    }
                    Start-Sleep -Seconds 2
                }
            }
            $before = Get-Sample
            Write-Row -Action "before-kill-$i" -Cycle $i -Sample $before -Result $(if ($before.ServiceRunning) { 'RUNNING' } else { 'NOT_RUNNING' })

            if (-not $DryRun) {
                Invoke-Adb @('shell', 'am', 'force-stop', $script:Package) | Out-Null
                Start-Sleep -Seconds 3
            }
            $after = Get-Sample
            $result = if ($after.Pid -eq 0 -and -not $after.ServiceRunning) { 'KILLED' } else { 'STILL_RUNNING' }
            Write-Row -Action "force-stop-$i" -Cycle $i -Sample $after -Result $result

            if ($i -lt $Count) {
                if (-not $DryRun) { Start-Sleep -Seconds ($IntervalMin * 60) }
                if (-not $NoAutoArm) {
                    # 强制停止后先等间隔，再给下一轮重新拉起；第 Count 次后保持关闭，
                    # 留给人工验证“反复被中断”提示。
                    if ($i -lt $Count) {
                        $armed = Start-Guard -AllowInstall
                        if (-not $armed) {
                            Write-Fail "第 $($i + 1) 次重新武装失败；终止本轮避免下一轮误判"
                            exit 2
                        }
                    }
                }
            }
        }
        if (-not $DryRun) {
            # 最后一次保持关闭，按 docs/05 “第 3 次后不打开”的语义做总检
            Take-AfterShot 'd3-closed'
        }
    }

    'D4' {
        $initialWifi = Invoke-Adb @('shell', 'cmd', 'wifi', 'status') -TimeoutSec 30
        Write-Note "初始 Wi-Fi 状态：$initialWifi"
        if (-not (Test-ServiceRunning)) {
            if ($NoAutoArm) {
                Write-Fail 'D4 开始前值守未运行；请先 --arm，或去掉 -NoAutoArm'
                exit 2
            }
            $armed = Start-Guard -AllowInstall
            if (-not $armed) {
                Write-Fail 'D4 开始前武装失败；这不是网络抖动结果，终止本轮避免误判'
                exit 2
            }
        }
        for ($i = 1; $i -le $Count; $i++) {
            Write-Step "D4 第 $i/$Count 次网络抖动"
            if (-not $DryRun) { Set-Wifi -Enabled $false | Out-Null }
            if (-not $DryRun) { Start-Sleep -Seconds 30 }
            $off = Get-Sample
            $offResult = if ($off.ServiceRunning -and $off.Notification) { 'OFFLINE_TOLERATED' } else { 'SERVICE_LOST' }
            Write-Row -Action "wifi-off-$i" -Cycle $i -Sample $off -Result $offResult

            if (-not $DryRun) { Set-Wifi -Enabled $true | Out-Null }
            if (-not $DryRun) { Start-Sleep -Seconds 30 }
            $on = Get-Sample
            $onResult = if ($on.ServiceRunning -and $on.Notification) { 'RECOVERED' } else { 'SERVICE_LOST' }
            Write-Row -Action "wifi-on-$i" -Cycle $i -Sample $on -Result $onResult

            if ($i -lt $Count -and -not $DryRun) { Start-Sleep -Seconds ($IntervalMin * 60) }
        }
        if (-not $DryRun) { Set-Wifi -Enabled $true | Out-Null }
    }

    'D7' {
        if (-not $Force -and -not $DryRun) {
            Write-Fail 'D7 会重启真机；确认风险后加 -Force 再执行'
            exit 2
        }
        for ($i = 1; $i -le $Count; $i++) {
            Write-Step "D7 第 $i/$Count 次重启"
            if (-not $DryRun) {
                Invoke-Adb @('reboot') -TimeoutSec 30 | Out-Null
                $booted = Wait-ForBoot -TimeoutSec 300
                if (-not $booted) {
                    Write-Fail "第 $i 次重启后 boot_completed 超时"
                    $sample = Get-Sample
                    Write-Row -Action "reboot-$i" -Cycle $i -Sample $sample -Result 'BOOT_TIMEOUT'
                } else {
                    Start-Sleep -Seconds 60
                    $sample = Get-Sample
                    $result = if ($sample.Pid -eq 0 -and -not $sample.ServiceRunning) { 'NO_AUTOSTART' } else { 'AUTOSTARTED' }
                    Write-Row -Action "reboot-$i" -Cycle $i -Sample $sample -Result $result
                }
            } else {
                Write-Note "DRY-RUN: would reboot, wait boot_completed, wait 60s, sample"
                Write-Row -Action "reboot-$i" -Cycle $i -Sample (Get-Sample) -Result 'DRY_RUN'
            }
            if ($i -lt $Count -and -not $DryRun) { Start-Sleep -Seconds ($IntervalMin * 60) }
        }
        if (-not $DryRun) {
            # BootReceiver 不应自动拉起相机；最后只启动 App 做一次总检截图
            Invoke-Adb @('shell', 'monkey', '-p', $script:Package, '-c', 'android.intent.category.LAUNCHER', '1') | Out-Null
            Start-Sleep -Seconds 5
            Take-AfterShot 'd7-after'
        }
    }
}

$rows = @(Import-Csv $csvPath)
$killRows = @($rows | Where-Object { $_.action -like 'force-stop-*' })
$stillRunning = @($killRows | Where-Object { $_.result -eq 'STILL_RUNNING' }).Count
$serviceLost = @($rows | Where-Object { $_.result -eq 'SERVICE_LOST' }).Count
$autoStarted = @($rows | Where-Object { $_.result -eq 'AUTOSTARTED' }).Count
$bootTimeout = @($rows | Where-Object { $_.result -eq 'BOOT_TIMEOUT' }).Count

$summary = @"
=== 注入摘要（$Step） ===
设备: $Serial
动作数: $($rows.Count)
D3 强杀后仍在跑: $stillRunning
D4 采样中服务丢失: $serviceLost
D7 重启后自动拉起: $autoStarted；启动超时: $bootTimeout
CSV: $csvPath
截图: $(if (Test-Path (Join-Path $OutDir "after-$Step-$stamp.png")) { Join-Path $OutDir "after-$Step-$stamp.png" } else { '(无)' })

判定：D3 期望 force-stop 后 pid=0 且服务停止；D4 期望切换 Wi-Fi 时本地值守不断；
D7 期望 BootReceiver 不自动拉起相机、boot_completed=1。人工仍需按 docs/05 §4 检查分片可解码与恢复提示。
"@
$summary | Set-Content -Path (Join-Path $OutDir "inject-$Step-$stamp-summary.txt") -Encoding UTF8
Write-Host $summary
