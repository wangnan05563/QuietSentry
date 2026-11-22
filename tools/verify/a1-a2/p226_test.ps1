$ErrorActionPreference = 'Stop'
$adb = 'E:\Android\sdk\platform-tools\adb.exe'
$py = 'C:\Windows\py.exe'
$out = 'E:\qs-ui-logs'
$viewer = 'emulator-5554'
$camera = 'emulator-5558'

# 1) 刷相机码并读取
& $adb -s $camera shell 'input tap 360 1130'
Start-Sleep -Seconds 1
$xml = (& $adb -s $camera shell 'uiautomator dump /sdcard/camui.xml >/dev/null 2>&1; cat /sdcard/camui.xml') -join ''
if ($xml -notmatch '配对码 (\d{6})') { throw 'pair code not found' }
$code = $Matches[1]
if ($xml -match '剩余 ([\d:]+)') { $ttl = $Matches[1] } else { $ttl = '' }
"CODE=$code TTL=$ttl START=$(Get-Date -Format HH:mm:ss)"

# 2) 唯一文件名，避免 virtualscene 贴图缓存
$png = "$out\qr-p226-$code.png"
$payload = "quietsentry://pair?host=10.0.2.2&port=18081&code=$code&fp=77:A1:A9:FE:04:DA:DD:E9"
& $py -3 "$out\gen_qr.py" $payload $png
& $adb -s $viewer emu virtualscene-image wall $png

# 3) 从既有 registry（tlsFingerprint 为空、port=8080）重扫
& $adb -s $viewer shell 'am force-stop com.quietsentry.app'
& $adb -s $viewer shell 'su 0 am start -W -n com.quietsentry.app/.ScanPairActivity'
"SCAN_START=$(Get-Date -Format HH:mm:ss)"
Start-Sleep -Seconds 20

# 4) 配对后状态
& $adb -s $viewer shell 'dumpsys window | grep -E "mCurrentFocus|mFocusedApp"'
& $adb -s $viewer shell 'su 0 cat /data/data/com.quietsentry.app/shared_prefs/device_registry.xml'
& $adb -s $viewer shell 'screencap -p /sdcard/p226-pair-result.png'
& $adb -s $viewer pull /sdcard/p226-pair-result.png "$out\p226-pair-result.png"

# 5) 打开设备列表并冷启复测
& $adb -s $viewer shell 'su 0 am start -W -n com.quietsentry.app/.DeviceListActivity'
Start-Sleep -Seconds 6
& $adb -s $viewer shell 'screencap -p /sdcard/p226-after-pair.png'
& $adb -s $viewer pull /sdcard/p226-after-pair.png "$out\p226-after-pair.png"
& $adb -s $viewer shell 'am force-stop com.quietsentry.app'
Start-Sleep -Seconds 1
& $adb -s $viewer shell 'su 0 am start -W -n com.quietsentry.app/.DeviceListActivity'
Start-Sleep -Seconds 6
& $adb -s $viewer shell 'screencap -p /sdcard/p226-cold-restart.png'
& $adb -s $viewer pull /sdcard/p226-cold-restart.png "$out\p226-cold-restart.png"
"DONE=$(Get-Date -Format HH:mm:ss)"
