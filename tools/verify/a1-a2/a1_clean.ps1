$ErrorActionPreference = 'Stop'
$adb = 'E:\Android\sdk\platform-tools\adb.exe'
$py = 'C:\Windows\py.exe'
$out = 'E:\qs-ui-logs'
$viewer = 'emulator-5554'
$camera = 'emulator-5558'

# 0) 清空观看端数据，模拟首次安装用户
& $adb -s $viewer shell 'pm clear com.quietsentry.app'
& $adb -s $viewer shell 'pm grant com.quietsentry.app android.permission.CAMERA'
& $adb -s $viewer shell 'pm disable-user --user 0 io.github.wangnan05563.airmirror' | Out-Null

# 1) 刷相机码
& $adb -s $camera shell 'input tap 360 1115'
Start-Sleep -Seconds 1
$xml = (& $adb -s $camera shell 'uiautomator dump /sdcard/camui.xml >/dev/null 2>&1; cat /sdcard/camui.xml') -join ''
if ($xml -notmatch '配对码 (\d{6})') { throw 'pair code not found' }
$code = $Matches[1]
if ($xml -match '剩余 ([\d:]+)') { $ttl = $Matches[1] } else { $ttl = '' }
"CODE=$code TTL=$ttl CLEAN_START=$(Get-Date -Format HH:mm:ss)"

# 2) 唯一文件名，避免 virtualscene 纹理缓存旧图
$png = "$out\qr-clean-$code.png"
$payload = "quietsentry://pair?host=10.0.2.2&port=18081&code=$code&fp=77:A1:A9:FE:04:DA:DD:E9"
& $py -3 "$out\gen_qr.py" $payload $png
& $adb -s $viewer emu virtualscene-image wall $png

# 3) 直接进扫码页
& $adb -s $viewer shell 'su 0 am start -W -n com.quietsentry.app/.ScanPairActivity'
"SCAN_START=$(Get-Date -Format HH:mm:ss)"
Start-Sleep -Seconds 25

# 4) 配对后状态：焦点、注册表、截图
& $adb -s $viewer shell 'dumpsys window | grep -E "mCurrentFocus|mFocusedApp"'
& $adb -s $viewer shell 'su 0 cat /data/data/com.quietsentry.app/shared_prefs/device_registry.xml'
& $adb -s $viewer shell 'screencap -p /sdcard/a1-clean-paired.png'
& $adb -s $viewer pull /sdcard/a1-clean-paired.png "$out\a1-clean-paired.png"

# 5) 冷启查看设备列表，验证重启后仍在线
& $adb -s $viewer shell 'am force-stop com.quietsentry.app'
Start-Sleep -Seconds 1
& $adb -s $viewer shell 'su 0 am start -W -n com.quietsentry.app/.DeviceListActivity'
Start-Sleep -Seconds 8
& $adb -s $viewer shell 'screencap -p /sdcard/a1-clean-restart.png'
& $adb -s $viewer pull /sdcard/a1-clean-restart.png "$out\a1-clean-restart.png"
"DONE=$(Get-Date -Format HH:mm:ss)"
