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

$header = 'time,pid,pss_kb,battery_pct,battery_temp_c,thermal_status,clip_files,thumb_files,crash_lines,service_running'
[System.IO.File]::WriteAllText($csvPath, $header + "`r`n", (New-Object System.Text.UTF8Encoding($false)))

function Invoke-Adb {
    param([string[]]$CommandArgs)
    # adb 的部分子命令会把提示写到 stderr；在 Stop 模式下会变成终止错误，
    # 这里按普通文本合并，让采样在“设备离线”时也能留下 pid=0 的记录而不是直接退出
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        return ((& $adb -s $Serial @CommandArgs) 2>&1 | Out-String).Trim()
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Get-Sample {
    $now = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'

    $pidText = Invoke-Adb @('shell', 'pidof', 'com.quietsentry.app')
    $pidValue = if ($pidText -match '(\d+)') { [int]$Matches[1] } else { 0 }

    $memText = Invoke-Adb @('shell', 'dumpsys', 'meminfo', 'com.quietsentry.app')
    $pss = 0
    if ($memText -match 'TOTAL PSS:\s*(\d+)') {
        $pss = [int]$Matches[1]
    } elseif ($memText -match 'TOTAL\s+(\d+)') {
        $pss = [int]$Matches[1]
    }

    $batteryText = Invoke-Adb @('shell', 'dumpsys', 'battery')
    $level = if ($batteryText -match 'level:\s*(\d+)') { [int]$Matches[1] } else { -1 }
    $temperature = if ($batteryText -match 'temperature:\s*(\d+)') { [int]$Matches[1] / 10.0 } else { -1.0 }

    $thermalText = Invoke-Adb @('shell', 'dumpsys', 'thermalservice')
    $thermal = if ($thermalText -match 'Thermal Status:\s*(\d+)') { [int]$Matches[1] } else { -1 }

    $clipText = Invoke-Adb @('shell', 'run-as', 'com.quietsentry.app', 'ls', 'files/clips')
    $clipCount = ($clipText -split "`n" | Where-Object { $_ -match '\.(mp4|pending|orphan)' }).Count
    $thumbText = Invoke-Adb @('shell', 'run-as', 'com.quietsentry.app', 'ls', 'files/events/thumbs')
    $thumbCount = ($thumbText -split "`n" | Where-Object { $_ -match '\.jpg' }).Count

    $crashText = Invoke-Adb @('shell', 'logcat', '-b', 'crash', '-d')
    $crashLines = ($crashText -split "`n" | Where-Object { $_.Trim().Length -gt 0 }).Count

    $serviceText = Invoke-Adb @('shell', 'dumpsys', 'activity', 'services', 'com.quietsentry.app')
    $serviceRunning = ($serviceText -match 'quietsentry\.app/\.CaptureService') -and ($serviceText -match 'isForeground=true')

    [pscustomobject]@{
        Time           = $now
        Pid            = $pidValue
        PssKb          = $pss
        BatteryPct     = $level
        BatteryTempC   = $temperature
        Thermal        = $thermal
        ClipFiles      = $clipCount
        ThumbFiles     = $thumbCount
        CrashLines     = $crashLines
        ServiceRunning = [bool]$serviceRunning
    }
}

$interval = if ($IntervalSec -gt 0) { $IntervalSec } else { $IntervalMin * 60 }
$target = if ($MaxSamples -gt 0) {
    $MaxSamples
} else {
    [math]::Ceiling(($Days * 24 * 60) / [math]::Max($IntervalMin, 1))
}

Write-Step "稳定性采样开始：$Serial，共 $target 个采样点（间隔 $interval 秒）"
Write-Note "CSV: $csvPath"

for ($index = 0; $index -lt $target; $index++) {
    $sample = Get-Sample
    $row = "$($sample.Time),$($sample.Pid),$($sample.PssKb),$($sample.BatteryPct),$($sample.BatteryTempC),$($sample.Thermal),$($sample.ClipFiles),$($sample.ThumbFiles),$($sample.CrashLines),$($sample.ServiceRunning)"
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
$interruptions = ($rows | Where-Object { [int]$_.pid -eq 0 }).Count
$serviceOff = ($rows | Where-Object { $_.service_running -ne 'True' }).Count
$crashFirst = if ($rows.Count -gt 0) { [int]$rows[0].crash_lines } else { 0 }
$crashLast = if ($rows.Count -gt 0) { [int]$rows[-1].crash_lines } else { 0 }
$maxTemp = ($rows | ForEach-Object { [double]$_.battery_temp_c } | Measure-Object -Maximum).Maximum
$minLevel = ($rows | ForEach-Object { [int]$_.battery_pct } | Measure-Object -Minimum).Minimum
$finalClips = if ($rows.Count -gt 0) { [int]$rows[-1].clip_files } else { 0 }

$summary = @"
=== 稳定性采样摘要 ===
设备: $Serial
采样点: $($rows.Count)
进程中断: $interruptions 次
服务非运行: $serviceOff 次
崩溃缓冲增长: $($crashLast - $crashFirst) 行
最高电池温度: $maxTemp C
最低电量: $minLevel %
最后分片数: $finalClips
CSV: $csvPath

判定：进程中断与服务非运行应为 0；崩溃缓冲不应有新增；温度曲线需低于设备保护阈值。
"@
$summary | Set-Content -Path (Join-Path $OutDir "summary-$stampText.txt") -Encoding UTF8
Write-Host $summary
