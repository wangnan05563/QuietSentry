$ErrorActionPreference = 'Stop'
$adb = 'E:\Android\sdk\platform-tools\adb.exe'
$py = 'C:\Windows\py.exe'
$out = 'E:\qs-ui-logs'

& $adb -s emulator-5558 shell 'input tap 360 1115'
Start-Sleep -Seconds 1
$xml = (& $adb -s emulator-5558 shell 'uiautomator dump /sdcard/camui.xml >/dev/null 2>&1; cat /sdcard/camui.xml') -join ''
if ($xml -notmatch '配对码 (\d{6})') { throw 'pair code not found' }
$code = $Matches[1]
if ($xml -match '剩余 ([\d:]+)') { $ttl = $Matches[1] } else { $ttl = '' }
"CODE=$code TTL=$ttl AT=$(Get-Date -Format HH:mm:ss)"

$png = "$out\qr-$code.png"
$payload = "quietsentry://pair?host=10.0.2.2&port=18081&code=$code&fp=77:A1:A9:FE:04:DA:DD:E9"
& $py -3 "$out\gen_qr.py" $payload $png
& $adb -s emulator-5554 emu virtualscene-image wall $png
Start-Sleep -Seconds 2
& $adb -s emulator-5554 shell 'screencap -p /sdcard/texture-check.png'
& $adb -s emulator-5554 pull /sdcard/texture-check.png "$out\texture-check.png"
$decoded = & $py -3 -c "import cv2; im=cv2.imread(r'$out\texture-check.png'); print(cv2.QRCodeDetector().detectAndDecode(im)[0])"
"DISPLAYED=$decoded EXPECTED_CODE=$code AT=$(Get-Date -Format HH:mm:ss)"
