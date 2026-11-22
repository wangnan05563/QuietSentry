$ErrorActionPreference = 'Stop'
$adb = 'E:\Android\sdk\platform-tools\adb.exe'
$py = 'C:\Windows\py.exe'
$out = 'E:\qs-ui-logs'

# 1) 刷新相机配对码并读取 6 位码
& $adb -s emulator-5558 shell 'input tap 360 1115'
Start-Sleep -Seconds 1
$xml = (& $adb -s emulator-5558 shell 'uiautomator dump /sdcard/camui.xml >/dev/null 2>&1; cat /sdcard/camui.xml') -join ''
if ($xml -notmatch '配对码 (\d{6})') { throw 'pair code not found' }
$code = $Matches[1]
if ($xml -match '剩余 ([\d:]+)') { $ttl = $Matches[1] } else { $ttl = '' }
"CODE=$code TTL=$ttl AT=$(Get-Date -Format HH:mm:ss)"

# 2) 立即生成指向宿主转发端口的二维码
$payload = "quietsentry://pair?host=10.0.2.2&port=18081&code=$code&fp=77:A1:A9:FE:04:DA:DD:E9"
& $py -3 "$out\gen_qr.py" $payload "$out\qr-target.png"

# 3) 重载虚拟场景贴图，冷启扫码页
& $adb -s emulator-5554 emu virtualscene-image wall "$out\qr-target.png"
& $adb -s emulator-5554 shell 'am force-stop com.quietsentry.app'
& $adb -s emulator-5554 shell 'su 0 am start -W -n com.quietsentry.app/.ScanPairActivity'
"SCAN_STARTED=$(Get-Date -Format HH:mm:ss)"

# 4) 4 秒后抓观看端线程栈，定位“正在配对”卡在哪
Start-Sleep -Seconds 4
$pidViewer = (& $adb -s emulator-5554 shell 'pidof com.quietsentry.app').Trim()
"VIEWER_PID=$pidViewer AT=$(Get-Date -Format HH:mm:ss)"
& $adb -s emulator-5554 shell "su 0 kill -3 $pidViewer"
Start-Sleep -Seconds 3
& $adb -s emulator-5554 shell 'su 0 cat /data/anr/trace_00' > "$out\trace-viewer-a1.txt" 2>&1
& $adb -s emulator-5554 shell 'screencap -p /sdcard/a1-trace-state.png'
& $adb -s emulator-5554 pull /sdcard/a1-trace-state.png "$out\a1-trace-state.png"
"DONE=$(Get-Date -Format HH:mm:ss) TRACE=$((Get-Item "$out\trace-viewer-a1.txt").Length)"
