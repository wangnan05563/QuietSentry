#requires -Version 7
param(
    [Parameter(Mandatory)][ValidateSet('D3', 'D4', 'D7')][string]$Step,
    [string]$Serial = '',
    [int]$Count = 0,
    [int]$IntervalMin = 0,
    [string]$OutDir = '',
    [string]$Apk = '',
    [string]$ExpectVersion = '',
    [int]$ArmTimeoutSec = 420,
    [int]$BootTimeoutSec = 600,
    [ValidateRange(60, 1800)][int]$NotifyTimeoutSec = 600,
    [int]$AutoArmMax = 3,
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
#   tools\verify\soak-inject.ps1 -Step D3 -Serial <serial> -Apk <apk> -ExpectVersion 0.1.0-...
#   tools\verify\soak-inject.ps1 -Step D3 -Serial <serial> -AutoArmMax 5  # 旧式每轮自动重开
#   tools\verify\soak-inject.ps1 -Step D4 -Serial <serial> -Count 3 -IntervalMin 1
#   tools\verify\soak-inject.ps1 -Step D7 -Serial <serial> -Force
#   tools\verify\soak-inject.ps1 -Step D7 -Serial <serial> -DryRun
#
# D2 断电、D5 边充边录、D6 存储压力需要外部电源/散热/填盘，不在本脚本里伪装自动化。
# -ExpectVersion 会透传给 real_device_smoke --arm；跳过安装时也校验设备版本，
# 避免拿错误版本的包填 D3/D4 证据。

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
$header = 'time,step,cycle,action,pid,service_running,notification,result,notify_delay_sec'
[System.IO.File]::WriteAllText($csvPath, $header + "`r`n", (New-Object System.Text.UTF8Encoding($false)))
$script:FirstArm = $true
$script:WifiRestoreFailed = $false

function Invoke-Adb {
    param([string[]]$CommandArgs, [int]$TimeoutSec = 90)
    # 之前的版本声明了 TimeoutSec 却直接 & adb，参数实际无效；
    # 这里用可 Kill 的进程，ADB 卡死时按超时返回错误文本，调用方据此终止/记录。
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $adb
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $psi.ArgumentList.Add('-s')
    $psi.ArgumentList.Add($Serial)
    foreach ($arg in $CommandArgs) { $psi.ArgumentList.Add([string]$arg) }

    try {
        $proc = [System.Diagnostics.Process]::Start($psi)
        $stdoutTask = $proc.StandardOutput.ReadToEndAsync()
        $stderrTask = $proc.StandardError.ReadToEndAsync()
        if (-not $proc.WaitForExit($TimeoutSec * 1000)) {
            try { $proc.Kill($true) } catch {}
            return "ADB_TIMEOUT: $($CommandArgs -join ' ') 超过 ${TimeoutSec}s"
        }
        $output = (($stdoutTask.Result + $stderrTask.Result).Trim())
        # adb 在设备掉线/未授权时通常仍以退出码 0 返回一段错误文本；
        # 只认超时会把 'device not found' 当成 pid=0/服务未运行，必须显式识别为 ADB 失败。
        if ($output -match '(?im)^(?:adb(?:\.exe)?:\s*)?(?:error:\s*)?(?:device .*not found|device offline|device unauthorized|no devices/emulators found|cannot connect to|more than one device/emulator)') {
            return "ADB_ERROR: $output"
        }
        return $output
    } catch {
        return "ADB_ERROR: $($_.Exception.Message)"
    }
}

function Test-AdbFailure {
    param([string]$Text)
    return [bool]($Text -match '^ADB_(TIMEOUT|ERROR):')
}

function Get-PidValue {
    $text = Invoke-Adb @('shell', 'pidof', $script:Package)
    if (Test-AdbFailure $text) { return 0 }
    if ($text -match '(\d+)') { return [int]$Matches[1] }
    return 0
}

function Test-ServiceText {
    param([string]$Text)
    $block = [regex]::Match(
        $Text,
        '(?s)ServiceRecord\{[^\n]*com\.quietsentry\.app/\.CaptureService.*?(?=ServiceRecord\{|\z)'
    ).Value
    return [bool]($block -match 'isForeground=true')
}

function Test-ServiceRunning {
    $text = Invoke-Adb @('shell', 'dumpsys', 'activity', 'services', $script:Package) -TimeoutSec 60
    if (Test-AdbFailure $text) { return $false }
    return (Test-ServiceText $text)
}

function Test-NotificationText {
    param([string]$Text)
    return [bool]($Text -match 'NotificationRecord\([^\n]*pkg=com\.quietsentry\.app')
}

function Test-Notification {
    # 只认当前 NotificationRecord，避免历史/AppSettings 里的包名造成假阳性；
    # 不请求 --noredact，通知正文不应进入验证证据。
    $text = Invoke-Adb @('shell', 'dumpsys', 'notification') -TimeoutSec 60
    if (Test-AdbFailure $text) { return $false }
    return (Test-NotificationText $text)
}

function Get-Sample {
    $pidText = Invoke-Adb @('shell', 'pidof', $script:Package)
    $serviceText = Invoke-Adb @('shell', 'dumpsys', 'activity', 'services', $script:Package) -TimeoutSec 60
    $notifText = Invoke-Adb @('shell', 'dumpsys', 'notification') -TimeoutSec 60
    $adbError = (Test-AdbFailure $pidText) -or (Test-AdbFailure $serviceText) -or (Test-AdbFailure $notifText)
    $procId = if (-not (Test-AdbFailure $pidText) -and $pidText -match '(\d+)') { [int]$Matches[1] } else { 0 }
    return [pscustomobject]@{
        Time            = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
        Pid             = $procId
        ServiceRunning  = (-not (Test-AdbFailure $serviceText)) -and (Test-ServiceText $serviceText)
        Notification    = (-not (Test-AdbFailure $notifText)) -and (Test-NotificationText $notifText)
        AdbError        = [bool]$adbError
    }
}

function Write-Row {
    param(
        [string]$Action,
        [int]$Cycle,
        [object]$Sample,
        [string]$Result,
        [int]$NotifyDelaySec = 0
    )
    $row = "$($Sample.Time),$Step,$Cycle,$Action,$($Sample.Pid),$($Sample.ServiceRunning),$($Sample.Notification),$Result,$NotifyDelaySec"
    Add-Content -Path $csvPath -Value $row -Encoding UTF8
    Add-Content -Path $logPath -Value $row -Encoding UTF8
    Write-Host ("  [{0}] {1} pid={2} 服务={3} 通知={4} -> {5} 通知延迟={6}s" -f `
        $Sample.Time, $Action, $Sample.Pid, $Sample.ServiceRunning, $Sample.Notification, $Result, $NotifyDelaySec)
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
    $armArgs = @(
        $script:SmokeScript, '--serial', $Serial, '--arm',
        '--out', (Join-Path $OutDir 'arm')
    )
    if ($AllowInstall -and $script:FirstArm -and $Apk) {
        $armArgs += @('--apk', $Apk)
    } else {
        $armArgs += '--skip-install'
    }
    if ($ExpectVersion) {
        $armArgs += @('--expect-version', $ExpectVersion)
    }
    $script:FirstArm = $false
    Write-Note "重新武装：python $($armArgs -join ' ')（超时 ${ArmTimeoutSec}s）"
    try {
        # 用 ArgumentList 保留含空格路径；Start-Process 的字符串数组会把参数拆开。
        $psi = [System.Diagnostics.ProcessStartInfo]::new()
        $psi.FileName = $pythonPath
        $psi.UseShellExecute = $false
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.CreateNoWindow = $true
        foreach ($arg in $armArgs) { $psi.ArgumentList.Add([string]$arg) }
        $proc = [System.Diagnostics.Process]::Start($psi)
        $stdoutTask = $proc.StandardOutput.ReadToEndAsync()
        $stderrTask = $proc.StandardError.ReadToEndAsync()
        if (-not $proc.WaitForExit($ArmTimeoutSec * 1000)) {
            try { $proc.Kill($true) } catch {}
            Write-Fail "arm 超过 ${ArmTimeoutSec}s，已终止；下一轮会再试"
            return $false
        }
        $armOutput = (($stdoutTask.Result + $stderrTask.Result).Trim())
        if ($proc.ExitCode -ne 0) {
            Write-Note "arm 退出码 $($proc.ExitCode)：$armOutput"
        }
        return ($proc.ExitCode -eq 0)
    } catch {
        Write-Fail "arm 启动失败：$($_.Exception.Message)"
        return $false
    }
}

function Get-BootId {
    return (Invoke-Adb @('shell', 'cat', '/proc/sys/kernel/random/boot_id') -TimeoutSec 20).Trim()
}

function Wait-ForBoot {
    param([string]$PreviousBootId = '', [int]$TimeoutSec = 600)
    # 返回结构化结果，不能只返回 bool：设备重启后 USB/授权没回连，
    # 和“设备回来了但系统还没 boot_completed”是两种完全不同的失败。
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $noDeviceSince = $null
    $sawDevice = $false
    $sawBootOne = $false
    $lastState = ''
    do {
        $state = Invoke-Adb @('get-state') -TimeoutSec 20
        $lastState = $state
        if ($state -match '^device$') {
            $noDeviceSince = $null
            $sawDevice = $true
            $boot = Invoke-Adb @('shell', 'getprop', 'sys.boot_completed') -TimeoutSec 20
            if ($boot.Trim() -eq '1') { $sawBootOne = $true }
            $bootId = Get-BootId
            # 只看 boot_completed 会读到重启前的旧值（adb reboot 返回后设备仍可能响应数秒）。
            # boot_id 变化才说明内核真的重启过；无 boot_id 的设备退化为“至少等待旧值失效”。
            if ($boot.Trim() -eq '1' -and $bootId -match '^[0-9a-fA-F-]{32,36}$' -and $bootId -ne $PreviousBootId) {
                return [pscustomobject]@{ Ok = $true; Reason = 'BOOTED'; BootId = $bootId; LastState = $lastState }
            }
        } else {
            if (-not $noDeviceSince) { $noDeviceSince = Get-Date }
            # 设备在重启窗口内正常会短暂消失；连续 120 秒没有 adb 设备，
            # 判定为 USB/调试授权没有回连，而不是继续把它当“还没开机”。
            if (-not $sawDevice -and ((Get-Date) - $noDeviceSince).TotalSeconds -ge 120) {
                return [pscustomobject]@{ Ok = $false; Reason = 'DEVICE_LOST'; BootId = ''; LastState = $lastState }
            }
        }
        Start-Sleep -Seconds 5
    } while ((Get-Date) -lt $deadline)

    $reason = if (-not $sawDevice) {
        'DEVICE_LOST'
    } elseif (-not $sawBootOne) {
        'BOOT_NOT_COMPLETED'
    } else {
        'BOOT_ID_UNCHANGED'
    }
    return [pscustomobject]@{ Ok = $false; Reason = $reason; BootId = ''; LastState = $lastState }
}

function Get-WifiEnabled {
    $text = Invoke-Adb @('shell', 'cmd', 'wifi', 'status') -TimeoutSec 20
    if (Test-AdbFailure $text) { return $null }
    if ($text -match 'Wi-Fi is enabled|Wifi is enabled') { return $true }
    if ($text -match 'Wi-Fi is disabled|Wifi is disabled') { return $false }
    $value = Invoke-Adb @('shell', 'settings', 'get', 'global', 'wifi_on') -TimeoutSec 20
    if (Test-AdbFailure $value) { return $null }
    if ($value.Trim() -eq '1') { return $true }
    if ($value.Trim() -eq '0') { return $false }
    return $null
}

function Set-Wifi {
    param([bool]$Enabled)
    $action = if ($Enabled) { 'enable' } else { 'disable' }
    $out = Invoke-Adb @('shell', 'svc', 'wifi', $action) -TimeoutSec 30
    if (Test-AdbFailure $out) { return $false }
    $actual = Get-WifiEnabled
    if ($null -ne $actual -and $actual -eq $Enabled) { return $true }

    $state = if ($Enabled) { 'enabled' } else { 'disabled' }
    $out = Invoke-Adb @('shell', 'cmd', 'wifi', 'set-wifi-enabled', $state) -TimeoutSec 30
    if (Test-AdbFailure $out) { return $false }
    $actual = Get-WifiEnabled
    return ($null -ne $actual -and $actual -eq $Enabled)
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
$initialResult = if ($initial.AdbError) { 'ADB_ERROR' } else { 'INFO' }
Write-Row -Action 'initial' -Cycle 0 -Sample $initial -Result $initialResult
if ($initial.AdbError) {
    Write-Fail '初始采样 ADB 失败；不能把超时当成设备未运行'
    exit 2
}
if ($Step -eq 'D3' -and $NoAutoArm -and ($initial.Pid -eq 0 -or -not $initial.ServiceRunning)) {
    Write-Fail 'D3 -NoAutoArm 开始前值守未运行；请先 --arm'
    exit 2
}

switch ($Step) {
    'D3' {
        for ($i = 1; $i -le $Count; $i++) {
            Write-Step "D3 第 $i/$Count 次强杀"
            if (-not $NoAutoArm -and $i -le $AutoArmMax -and -not (Test-ServiceRunning)) {
                $armed = Start-Guard -AllowInstall
                if (-not $armed) {
                    Write-Fail "第 $i 次重新武装失败；这不是强杀结果，终止本轮避免误判"
                    exit 2
                }
                Start-Sleep -Seconds 2
            } elseif (-not $NoAutoArm -and $i -gt $AutoArmMax -and -not (Test-ServiceRunning)) {
                # docs/05 要求第 3 次后不再自动打开应用；后续轮次只能等人工重开，
                # 等不到就如实记录，不能把“没有运行”伪装成一次 KILLED。
                $manual = Get-Sample
                $manualResult = if ($manual.AdbError) { 'ADB_ERROR' } else { 'MANUAL_REQUIRED' }
                Write-Row -Action "before-kill-$i" -Cycle $i -Sample $manual -Result $manualResult
                if ($i -lt $Count) { Start-Sleep -Seconds ($IntervalMin * 60) }
                continue
            } elseif ($NoAutoArm -and -not (Test-ServiceRunning)) {
                # -NoAutoArm 不等于“只测一次”：每轮等人工重开，等不到就记 MANUAL_REQUIRED，
                # 不能在第 2 轮直接 exit 2 把后面 4 次强杀全部丢掉。
                $manual = Get-Sample
                $manualResult = if ($manual.AdbError) { 'ADB_ERROR' } else { 'MANUAL_REQUIRED' }
                Write-Row -Action "before-kill-$i" -Cycle $i -Sample $manual -Result $manualResult
                if ($i -lt $Count) { Start-Sleep -Seconds ($IntervalMin * 60) }
                continue
            }

            $before = Get-Sample
            if ($before.AdbError) {
                Write-Row -Action "before-kill-$i" -Cycle $i -Sample $before -Result 'ADB_ERROR'
                Write-Fail "第 $i 次前置采样 ADB 失败，不能把 ADB 超时当成设备状态"
                exit 2
            }
            if ($before.Pid -eq 0 -or -not $before.ServiceRunning) {
                Write-Row -Action "before-kill-$i" -Cycle $i -Sample $before -Result 'NOT_RUNNING'
                Write-Fail "第 $i 次前置状态不成立：值守未运行，不能执行/记录强杀"
                exit 2
            }
            Write-Row -Action "before-kill-$i" -Cycle $i -Sample $before -Result 'RUNNING'

            if (-not $DryRun) {
                $forceOut = Invoke-Adb @('shell', 'am', 'force-stop', $script:Package) -TimeoutSec 30
                if (Test-AdbFailure $forceOut) {
                    Write-Fail "第 $i 次 force-stop 执行失败：$forceOut"
                    exit 2
                }
                Start-Sleep -Seconds 3
            }
            $after = Get-Sample
            $result = if ($after.AdbError) {
                'ADB_ERROR'
            } elseif ($after.Pid -eq 0 -and -not $after.ServiceRunning) {
                'KILLED'
            } else {
                'STILL_RUNNING'
            }
            Write-Row -Action "force-stop-$i" -Cycle $i -Sample $after -Result $result

            if ($i -lt $Count -and -not $DryRun) { Start-Sleep -Seconds ($IntervalMin * 60) }
        }
        if (-not $DryRun) {
            # 第 3 次后不再自动重开；留截图给人工检查“反复被中断”提示。

            Take-AfterShot 'd3-closed'
        }
    }

    'D4' {
        $initialWifi = Get-WifiEnabled
        $initialWifiText = Invoke-Adb @('shell', 'cmd', 'wifi', 'status') -TimeoutSec 30
        Write-Note "初始 Wi-Fi 状态：$initialWifiText（解析=${initialWifi})"
        if ($null -eq $initialWifi) {
            Write-Fail '无法读取 Wi-Fi 状态；不能把未知状态当成断网测试'
            exit 2
        }
        if (-not $initialWifi) {
            Write-Fail 'D4 要求初始 Wi-Fi 开启；初始关闭时 disable 是空操作，会产生假 PASS'
            exit 2
        }
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
        try {
            for ($i = 1; $i -le $Count; $i++) {
                Write-Step "D4 第 $i/$Count 次网络抖动"
                if (-not $DryRun) {
                    if (-not (Set-Wifi -Enabled $false)) {
                        Write-Fail "第 $i 次无法关闭 Wi-Fi；不记录 OFFLINE_TOLERATED"
                        exit 2
                    }
                    Start-Sleep -Seconds 30
                }
                $off = Get-Sample
                # 只有确实处于 Wi-Fi 关闭状态，才把“服务仍在”解释为断网容忍。
                $wifiOffNow = Get-WifiEnabled
                $offResult = if ($off.AdbError) {
                    'ADB_ERROR'
                } elseif ($wifiOffNow -eq $false -and $off.ServiceRunning -and $off.Notification) {
                    'OFFLINE_TOLERATED'
                } elseif ($wifiOffNow -ne $false) {
                    'WIFI_TOGGLE_FAILED'
                } else {
                    'SERVICE_LOST'
                }
                Write-Row -Action "wifi-off-$i" -Cycle $i -Sample $off -Result $offResult

                if (-not $DryRun) {
                    if (-not (Set-Wifi -Enabled $true)) {
                        Write-Fail "第 $i 次无法恢复 Wi-Fi"
                        exit 2
                    }
                    Start-Sleep -Seconds 30
                }
                $on = Get-Sample
                $wifiOnNow = Get-WifiEnabled
                $onResult = if ($on.AdbError) {
                    'ADB_ERROR'
                } elseif ($wifiOnNow -eq $true -and $on.ServiceRunning -and $on.Notification) {
                    'RECOVERED'
                } elseif ($wifiOnNow -ne $true) {
                    'WIFI_TOGGLE_FAILED'
                } else {
                    'SERVICE_LOST'
                }
                Write-Row -Action "wifi-on-$i" -Cycle $i -Sample $on -Result $onResult

                if ($i -lt $Count -and -not $DryRun) { Start-Sleep -Seconds ($IntervalMin * 60) }
            }
        } finally {
            # 异常/中途失败也必须回到初始状态，不能把用户手机留在断网。
            if (-not $DryRun) {
                if (-not (Set-Wifi -Enabled $initialWifi)) {
                    $script:WifiRestoreFailed = $true
                    Write-Fail "D4 结束恢复 Wi-Fi 失败；设备可能仍处于断网状态，请手动开启 Wi-Fi"
                }
            }
        }
    }

    'D7' {
        if (-not $Force -and -not $DryRun) {
            Write-Fail 'D7 会重启真机；确认风险后加 -Force 再执行'
            exit 2
        }
        if (-not $DryRun -and -not $initial.ServiceRunning) {
            Write-Fail 'D7 开始前值守未运行；先 --arm，否则重启后没有“恢复提示”可验证'
            exit 2
        }
        for ($i = 1; $i -le $Count; $i++) {
            Write-Step "D7 第 $i/$Count 次重启"
            if (-not $DryRun) {
                $bootIdBefore = Get-BootId
                if (Test-AdbFailure $bootIdBefore -or $bootIdBefore -notmatch '^[0-9a-fA-F-]{32,36}$') {
                    Write-Fail "第 $i 次读取 boot_id 失败：$bootIdBefore"
                    exit 2
                }
                $rebootOut = Invoke-Adb @('reboot') -TimeoutSec 30
                if (Test-AdbFailure $rebootOut -or $rebootOut -match 'error|unauthorized|not found|offline') {
                    Write-Fail "第 $i 次 adb reboot 失败：$rebootOut"
                    exit 2
                }
                $bootResult = Wait-ForBoot -PreviousBootId $bootIdBefore -TimeoutSec $BootTimeoutSec
                if (-not $bootResult.Ok) {
                    Write-Fail "第 $i 次重启失败：$($bootResult.Reason)（last adb state: $($bootResult.LastState)）"
                    $sample = Get-Sample
                    Write-Row -Action "reboot-$i" -Cycle $i -Sample $sample -Result $bootResult.Reason
                    # 设备没有回连时继续下一轮只会把 ADB_ERROR 叠在同一个根因上；
                    # 记录本轮失败后立即停止，保留可复核的首个失败证据。
                    break
                } else {
                    # Android 15 / 三星 One UI 上 BOOT_COMPLETED 可能延迟到分钟级才投递；
                    # 旧逻辑只等 60s + 120s，会把“通知来晚了”误判成 NOTIFY_MISSING。
                    # 现在按 NotifyTimeoutSec（默认 600s）轮询到通知出现，并记录实际延迟。
                    $bootAt = Get-Date
                    $sample = Get-Sample
                    $notifyDeadline = (Get-Date).AddSeconds($NotifyTimeoutSec)
                    while (-not $sample.Notification -and -not $sample.AdbError -and (Get-Date) -lt $notifyDeadline) {
                        Start-Sleep -Seconds 15
                        $sample = Get-Sample
                    }
                    $notifyDelaySec = [int]((Get-Date) - $bootAt).TotalSeconds
                    $result = if ($sample.AdbError) {
                        'ADB_ERROR'
                    } elseif (-not $sample.Notification) {
                        # 重启前正在值守：BootReceiver 必须留下“设备已重启”恢复提示。
                        # 没有提示说明恢复链路本身失败，不能因为服务没自启就算 NO_AUTOSTART。
                        'NOTIFY_MISSING'
                    } elseif ($sample.ServiceRunning) {
                        # 只看采集服务是否被拉起；BootReceiver 允许启动进程并发“设备已重启”通知，
                        # 这不算自动启动相机。通知存在、pid 存在但 service=False 仍视为未自动拉起。
                        'AUTOSTARTED'
                    } else {
                        'NO_AUTOSTART'
                    }
                    Write-Row -Action "reboot-$i" -Cycle $i -Sample $sample -Result $result -NotifyDelaySec $notifyDelaySec
                }
            } else {
                Write-Note "DRY-RUN: would reboot, wait boot_completed/boot_id change, poll restart notice up to $NotifyTimeoutSec s, sample"
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
$manualRequired = @($rows | Where-Object { $_.result -eq 'MANUAL_REQUIRED' }).Count
$serviceLost = @($rows | Where-Object { $_.result -eq 'SERVICE_LOST' }).Count
$wifiToggleFailed = @($rows | Where-Object { $_.result -eq 'WIFI_TOGGLE_FAILED' }).Count
$autoStarted = @($rows | Where-Object { $_.result -eq 'AUTOSTARTED' }).Count
$notifyMissing = @($rows | Where-Object { $_.result -eq 'NOTIFY_MISSING' }).Count
$bootFailures = @($rows | Where-Object { $_.result -in @('DEVICE_LOST', 'BOOT_NOT_COMPLETED', 'BOOT_ID_UNCHANGED', 'BOOT_TIMEOUT') }).Count
$deviceLost = @($rows | Where-Object { $_.result -eq 'DEVICE_LOST' }).Count
$adbErrors = @($rows | Where-Object { $_.result -eq 'ADB_ERROR' }).Count
$wifiRestoreText = if ($script:WifiRestoreFailed) { '是' } else { '否' }

$summary = @"
=== 注入摘要（$Step） ===
设备: $Serial
动作数: $($rows.Count)
D3 强杀后仍在跑: $stillRunning；人工重开待补: $manualRequired
D4 采样中服务丢失: $serviceLost；Wi-Fi 切换失败: $wifiToggleFailed；Wi-Fi 恢复失败: $wifiRestoreText
D7 重启失败: $bootFailures（设备未回连: $deviceLost）；自动拉起: $autoStarted；恢复提示缺失: $notifyMissing
ADB 调用失败: $adbErrors
CSV: $csvPath
截图: $(if (Test-Path (Join-Path $OutDir "after-$Step-$stamp.png")) { Join-Path $OutDir "after-$Step-$stamp.png" } else { '(无)' })

判定：D3 期望 force-stop 后 pid=0 且服务停止；D4 期望切换 Wi-Fi 时本地值守不断；
D7 期望 BootReceiver 不自动拉起相机、保留“设备已重启”恢复提示、boot_completed=1。人工仍需按 docs/05 §4 检查分片可解码与恢复提示。
"@
$summary | Set-Content -Path (Join-Path $OutDir "inject-$Step-$stamp-summary.txt") -Encoding UTF8
Write-Host $summary

if ($stillRunning -gt 0 -or $serviceLost -gt 0 -or $wifiToggleFailed -gt 0 -or
    $autoStarted -gt 0 -or $notifyMissing -gt 0 -or $bootFailures -gt 0 -or $adbErrors -gt 0 -or
    $manualRequired -gt 0 -or $script:WifiRestoreFailed) {
    exit 1
}
exit 0
