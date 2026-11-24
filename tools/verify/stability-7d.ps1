param(
    [string]$Serial = '',
    [int]$Days = 7,
    [int]$IntervalMin = 10,
    [int]$MaxSamples = 0,
    [int]$IntervalSec = 0,
    [string]$OutDir = ''
)

# 7 天连续稳定性采样（T03968）。
#
# 为什么用外部 adb 轮询而不是在 App 内埋点：稳定性门禁要能观察“App 已经崩了”的状态，
# 进程内统计在进程死亡时就一起消失了；外部采样才能留下中断证据。
#
# 默认每 10 分钟采一次，7 天共 1008 个点。CSV 可断点续看，进程中断、服务掉线、
# 温度与电量曲线、崩溃缓冲增长都会逐行留档，结束后自动生成摘要。
#
# 用法：
#   tools\verify\stability-7d.ps1 -Serial emulator-5554
#   tools\verify\stability-7d.ps1 -Serial <serial> -Days 1 -IntervalMin 5
#   tools\verify\stability-7d.ps1 -Serial <serial> -MaxSamples 3 -IntervalSec 2   # 冒烟自测

# 构建公共库已统一到 scripts/；本脚本在 tools/verify，向上两级才是项目根
. (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'scripts\build-lib.ps1')

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

if (-not $OutDir) {
    $OutDir = Join-Path $script:ProjectRoot 'verify-output\stability'
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$stampText = Get-Date -Format 'yyyyMMdd-HHmmss'
$csvPath = Join-Path $OutDir "stability-$stampText.csv"
$logPath = Join-Path $OutDir "stability-$stampText.log"

$header = 'time,pid,pss_kb,battery_pct,battery_temp_c,thermal_status,clip_files,clip_source,thumb_files,crash_lines,service_running,adb_errors'
[System.IO.File]::WriteAllText($csvPath, $header + "`r`n", (New-Object System.Text.UTF8Encoding($false)))

function Invoke-Adb {
    param([string[]]$CommandArgs, [int]$TimeoutSec = 90)
    # 用 ProcessStartInfo 而不是直接调用 & adb：后者的 TimeoutSec 参数没有约束力，
    # 设备离线或 uiautomator/dumpsys 卡住时会把 7 天采样永久挂起。
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
        # 设备掉线时 adb 可能返回 'device not found' 而不是抛异常；不能当成 pid=0。
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

function Get-Sample {
    $now = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    $adbErrors = 0

    $pidText = Invoke-Adb @('shell', 'pidof', 'com.quietsentry.app')
    if (Test-AdbFailure $pidText) { $adbErrors++ }
    $pidValue = if (-not (Test-AdbFailure $pidText) -and $pidText -match '(\d+)') { [int]$Matches[1] } else { 0 }

    $memText = Invoke-Adb @('shell', 'dumpsys', 'meminfo', 'com.quietsentry.app')
    if (Test-AdbFailure $memText) { $adbErrors++ }
    $pss = 0
    if (-not (Test-AdbFailure $memText) -and $memText -match 'TOTAL PSS:\s*(\d+)') {
        $pss = [int]$Matches[1]
    } elseif (-not (Test-AdbFailure $memText) -and $memText -match 'TOTAL\s+(\d+)') {
        $pss = [int]$Matches[1]
    }

    $batteryText = Invoke-Adb @('shell', 'dumpsys', 'battery')
    if (Test-AdbFailure $batteryText) { $adbErrors++ }
    $level = if (-not (Test-AdbFailure $batteryText) -and $batteryText -match 'level:\s*(\d+)') { [int]$Matches[1] } else { -1 }
    $temperature = if (-not (Test-AdbFailure $batteryText) -and $batteryText -match 'temperature:\s*(\d+)') { [int]$Matches[1] / 10.0 } else { -1.0 }

    $thermalText = Invoke-Adb @('shell', 'dumpsys', 'thermalservice')
    if (Test-AdbFailure $thermalText) { $adbErrors++ }
    $thermal = if (-not (Test-AdbFailure $thermalText) -and $thermalText -match 'Thermal Status:\s*(\d+)') { [int]$Matches[1] } else { -1 }

    # release 包不是 debuggable，run-as 会被系统拒绝；先验证 run-as 本身是否可用，
    # 不靠 ROM 的错误文案猜，避免不同 OEM 把可读/不可读判断反。
    $runAsId = Invoke-Adb @('shell', 'run-as', 'com.quietsentry.app', 'id') -TimeoutSec 20
    if (Test-AdbFailure $runAsId) { $adbErrors++ }
    $clipDebugReadable = [bool]($runAsId -match 'uid=\d+')
    $clipText = ''
    $thumbText = ''
    if ($clipDebugReadable) {
        $clipText = Invoke-Adb @('shell', 'run-as', 'com.quietsentry.app', 'ls', 'files/clips') -TimeoutSec 30
        if (Test-AdbFailure $clipText) { $adbErrors++ }
        $thumbText = Invoke-Adb @('shell', 'run-as', 'com.quietsentry.app', 'ls', 'files/events/thumbs') -TimeoutSec 30
        if (Test-AdbFailure $thumbText) { $adbErrors++ }
    }
    $clipCount = ($clipText -split "`n" | Where-Object { $_ -match '\.(mp4|pending|orphan)' }).Count
    $thumbCount = ($thumbText -split "`n" | Where-Object { $_ -match '\.jpg' }).Count

    # release 包退回相册导出目录计数（导出是 app 的正式行为，不依赖私有目录权限）。
    $clipSource = 'private'
    if (-not $clipDebugReadable) {
        $exportText = Invoke-Adb @('shell', 'ls', '-1', '/sdcard/Movies/QuietSentry') -TimeoutSec 30
        if (Test-AdbFailure $exportText) { $adbErrors++ }
        $clipCount = ($exportText -split "`n" | Where-Object { $_ -match '\.mp4$' }).Count
        $thumbCount = 0
        $clipSource = 'exports'
    }

    $crashText = Invoke-Adb @('shell', 'logcat', '-b', 'crash', '-d') -TimeoutSec 60
    if (Test-AdbFailure $crashText) { $adbErrors++ }
    $crashLines = ($crashText -split "`n" | Where-Object {
        $_ -match 'FATAL EXCEPTION' -or
        $_ -match 'Process: com\.quietsentry\.app' -or
        $_ -match 'ANR in com\.quietsentry\.app'
    }).Count

    $serviceText = Invoke-Adb @('shell', 'dumpsys', 'activity', 'services', 'com.quietsentry.app') -TimeoutSec 60
    if (Test-AdbFailure $serviceText) { $adbErrors++ }
    $serviceBlock = [regex]::Match(
        $serviceText,
        '(?s)ServiceRecord\{[^\n]*com\.quietsentry\.app/\.CaptureService.*?(?=ServiceRecord\{|\z)'
    ).Value
    $serviceRunning = [bool]($serviceBlock -match 'isForeground=true')

    [pscustomobject]@{
        Time           = $now
        Pid            = $pidValue
        PssKb          = $pss
        BatteryPct     = $level
        BatteryTempC   = $temperature
        Thermal        = $thermal
        ClipFiles      = $clipCount
        ThumbFiles     = $thumbCount
        ClipSource     = $clipSource
        CrashLines     = $crashLines
        ServiceRunning = [bool]$serviceRunning
        AdbErrors      = $adbErrors
    }
}

$interval = if ($IntervalSec -gt 0) { $IntervalSec } elseif ($IntervalMin -gt 0) { $IntervalMin * 60 } else {
    Write-Fail 'IntervalMin 与 IntervalSec 不能同时为 0'
    exit 1
}
$target = if ($MaxSamples -gt 0) {
    $MaxSamples
} else {
    if ($Days -le 0) {
        Write-Fail 'Days 必须大于 0，或提供 MaxSamples'
        exit 1
    }
    # 用实际 interval 计算，而不是用 IntervalMin：只给 IntervalSec 时旧算法会提前结束。
    [math]::Ceiling(($Days * 24 * 3600) / $interval)
}

Write-Step "稳定性采样开始：$Serial，共 $target 个采样点（间隔 $interval 秒）"
Write-Note "CSV: $csvPath"

for ($index = 0; $index -lt $target; $index++) {
    $sample = Get-Sample
    $row = "$($sample.Time),$($sample.Pid),$($sample.PssKb),$($sample.BatteryPct),$($sample.BatteryTempC),$($sample.Thermal),$($sample.ClipFiles),$($sample.ClipSource),$($sample.ThumbFiles),$($sample.CrashLines),$($sample.ServiceRunning),$($sample.AdbErrors)"
    Add-Content -Path $csvPath -Value $row -Encoding UTF8
    Add-Content -Path $logPath -Value $row -Encoding UTF8

    $state = if ($sample.ServiceRunning) { '服务运行' } else { '服务停止' }
    Write-Host ("  [{0}/{1}] {2} pid={3} pss={4}KB 电量={5}% 温度={6}C 分片={7} 崩溃行={8} {9}" -f `
        ($index + 1), $target, $sample.Time, $sample.Pid, $sample.PssKb,
        $sample.BatteryPct, $sample.BatteryTempC, $sample.ClipFiles, $sample.CrashLines, $state)

    if ($index -lt $target - 1) {
        Start-Sleep -Seconds $interval
    }
}

# Import-Csv 只有一行时返回单个对象而不是数组，$rows.Count 会为空，摘要数字随之为空
$rows = @(Import-Csv $csvPath)
# pid=0 只代表“采样时没进程”，不能把首点未启动或重启后的空白段算成一次中断；
# 只统计“上一个采样点还有进程、下一个点 pid 变化”的真实中断（含被杀后重启换 pid）。
$interruptions = 0
$previousPid = 0
foreach ($row in $rows) {
    $currentPid = [int]$row.pid
    if ($previousPid -gt 0 -and $currentPid -ne $previousPid) { $interruptions++ }
    $previousPid = $currentPid
}
$serviceOff = ($rows | Where-Object { $_.service_running -ne 'True' }).Count
$adbErrors = ($rows | ForEach-Object { [int]$_.adb_errors } | Measure-Object -Sum).Sum
$crashFirst = if ($rows.Count -gt 0) { [int]$rows[0].crash_lines } else { 0 }
$crashLast = if ($rows.Count -gt 0) { [int]$rows[-1].crash_lines } else { 0 }
$maxTemp = ($rows | ForEach-Object { [double]$_.battery_temp_c } | Measure-Object -Maximum).Maximum
$minLevel = ($rows | ForEach-Object { [int]$_.battery_pct } | Measure-Object -Minimum).Minimum
$finalClips = if ($rows.Count -gt 0) { [int]$rows[-1].clip_files } else { 0 }
$clipSource = if ($rows.Count -gt 0) { $rows[-1].clip_source } else { 'unknown' }
$crashGrowth = if ($rows.Count -gt 0) { [math]::Max(0, $crashLast - $crashFirst) } else { 0 }

$summary = @"
=== 稳定性采样摘要 ===
设备: $Serial
采样点: $($rows.Count)
进程中断: $interruptions 次
服务非运行: $serviceOff 次
ADB 调用失败: $adbErrors 次
崩溃缓冲增长: $crashGrowth 行
最高电池温度: $maxTemp C
最低电量: $minLevel %
最后分片数: $finalClips（来源: $clipSource）
CSV: $csvPath

判定：进程中断、服务非运行、ADB 调用失败、崩溃缓冲新增均应为 0；温度曲线需低于设备保护阈值。
"@
$summary | Set-Content -Path (Join-Path $OutDir "summary-$stampText.txt") -Encoding UTF8
Write-Host $summary

if ($interruptions -gt 0 -or $serviceOff -gt 0 -or $adbErrors -gt 0 -or $crashGrowth -gt 0) {
    exit 1
}
exit 0
