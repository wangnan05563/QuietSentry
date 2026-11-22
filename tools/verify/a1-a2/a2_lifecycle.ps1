$ErrorActionPreference = 'Continue'
$adb = 'E:\Android\sdk\platform-tools\adb.exe'
$out = 'E:\qs-ui-logs'
$token = $env:QS_TEST_TOKEN
if ([string]::IsNullOrWhiteSpace($token)) { throw 'set QS_TEST_TOKEN before probing /api/status' }
$cam = 'emulator-5558'
$viewer = 'emulator-5554'

function Probe([string]$label) {
    $stamp = Get-Date -Format HH:mm:ss
    $info = 'FAIL'
    $tls = 'FAIL'
    $status = 'FAIL'
    try { $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 6 'http://127.0.0.1:18081/api/info'; $info = "$($r.StatusCode)" } catch { $info = "ERR:$($_.Exception.Message.Substring(0,[Math]::Min(60,$_.Exception.Message.Length)))" }
    try { $r = Invoke-WebRequest -UseBasicParsing -SkipCertificateCheck -TimeoutSec 6 'https://127.0.0.1:8443/api/info'; $tls = "$($r.StatusCode)" } catch { $tls = "ERR:$($_.Exception.Message.Substring(0,[Math]::Min(60,$_.Exception.Message.Length)))" }
    try { $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 6 -Headers @{'X-Auth-Token'=$token} 'http://127.0.0.1:18081/api/status'; $j = $r.Content | ConvertFrom-Json; $status = "HTTP$($r.StatusCode) mode=$($j.mode) paused=$($j.paused) watching=$($j.watching) grants=$($j.grants)" } catch { $status = "ERR:$($_.Exception.Message.Substring(0,[Math]::Min(60,$_.Exception.Message.Length)))" }
    $proc = (& $adb -s $cam shell 'pidof com.quietsentry.app').Trim()
    $psline = (& $adb -s $cam shell "ps -A -o PID,STAT,NAME | grep com.quietsentry" 2>&1) -join ' '
    "PROBE[$label] $stamp INFO=$info TLS=$tls STATUS=$status PID=$proc PS=$psline"
}

Probe 'camera-foreground-paused'

& $adb -s $cam shell 'input keyevent 3'
Start-Sleep -Seconds 10
Probe 'camera-background-10s'

Start-Sleep -Seconds 50
Probe 'camera-background-60s'

& $adb -s $viewer shell 'screencap -p /sdcard/a2-bg.png'
& $adb -s $viewer pull /sdcard/a2-bg.png "$out\a2-bg.png" | Out-Null

& $adb -s $cam shell 'input keyevent 26'
Start-Sleep -Seconds 10
Probe 'camera-screenoff-10s'

Start-Sleep -Seconds 30
Probe 'camera-screenoff-40s'

& $adb -s $cam shell 'input keyevent 26'
Start-Sleep -Seconds 8
Probe 'camera-screenon-8s'

"DONE=$(Get-Date -Format HH:mm:ss)"
