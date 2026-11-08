# 构建脚本共用的工具链定位、版本推进与产物查找。
# 每个入口脚本都 dot-source 本文件，避免各自的路径判断互相漂移。
$ErrorActionPreference = 'Stop'

$script:ProjectRoot = Split-Path -Parent $PSScriptRoot
$script:PackageJson = Join-Path $script:ProjectRoot 'package.json'

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "[QuietSentry] $Message" -ForegroundColor Cyan
}

function Write-Ok([string]$Message) {
    Write-Host "  OK  $Message" -ForegroundColor Green
}

function Write-Note([string]$Message) {
    Write-Host "  ..  $Message" -ForegroundColor Yellow
}

function Write-Fail([string]$Message) {
    Write-Host "  X   $Message" -ForegroundColor Red
}

function Resolve-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        return $env:JAVA_HOME
    }
    # 本机把 JDK 放在 E:\Android\jdk17 下，版本目录会随升级变化，因此按“含 bin\java.exe”扫描
    if (Test-Path 'E:\Android\jdk17') {
        $roots = @('E:\Android\jdk17') + (Get-ChildItem 'E:\Android\jdk17' -Directory | ForEach-Object { $_.FullName })
        foreach ($root in $roots) {
            if (Test-Path (Join-Path $root 'bin\java.exe')) { return $root }
        }
    }
    $java = Get-Command java -ErrorAction SilentlyContinue
    if ($java) {
        $home = Split-Path -Parent (Split-Path -Parent $java.Source)
        if (Test-Path (Join-Path $home 'bin\javac.exe')) { return $home }
    }
    return $null
}

function Resolve-AndroidSdk {
    if ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) { return $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT -and (Test-Path $env:ANDROID_SDK_ROOT)) { return $env:ANDROID_SDK_ROOT }
    $localProps = Join-Path $script:ProjectRoot 'local.properties'
    if (Test-Path $localProps) {
        $line = Get-Content $localProps | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) {
            # local.properties 里的冒号与反斜杠都是 Java Properties 转义形式（E\:\\Android\\sdk）
            $path = $line -replace '^sdk\.dir=', ''
            $path = $path.Replace('\\', '\').Replace('\:', ':')
            if (Test-Path $path) { return $path }
        }
    }
    if (Test-Path 'E:\Android\sdk') { return 'E:\Android\sdk' }
    return $null
}

function Resolve-Gradle {
    $wrapper = Join-Path $script:ProjectRoot 'gradlew.bat'
    if (Test-Path $wrapper) { return $wrapper }
    $cmd = Get-Command gradle -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    # 本机没有全局 gradle，发行版解压在 E:\Android\gradle-* 下
    $candidates = Get-ChildItem 'E:\Android' -Directory -Filter 'gradle-*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        ForEach-Object { Join-Path $_.FullName 'bin\gradle.bat' }
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) { return $candidate }
    }
    return $null
}

function Get-Toolchain {
    $javaHome = Resolve-JavaHome
    $sdk = Resolve-AndroidSdk
    $gradle = Resolve-Gradle
    $adb = $null
    if ($sdk) {
        $adbPath = Join-Path $sdk 'platform-tools\adb.exe'
        if (Test-Path $adbPath) { $adb = $adbPath }
    }
    if (-not $adb) {
        $cmd = Get-Command adb -ErrorAction SilentlyContinue
        if ($cmd) { $adb = $cmd.Source }
    }
    [pscustomobject]@{
        JavaHome = $javaHome
        Java     = if ($javaHome) { Join-Path $javaHome 'bin\java.exe' } else { $null }
        Sdk      = $sdk
        Gradle   = $gradle
        Adb      = $adb
    }
}

function Get-CompileSdk {
    $gradleFile = Join-Path $script:ProjectRoot 'app\build.gradle.kts'
    if (Test-Path $gradleFile) {
        $text = Get-Content $gradleFile -Raw
        if ($text -match 'compileSdk\s*=\s*(\d+)') { return [int]$Matches[1] }
    }
    return 35
}

function Assert-BuildDependencies {
    param([switch]$NeedAdb)

    Write-Step "检查构建依赖"
    $failures = @()

    $javaHome = Resolve-JavaHome
    if (-not $javaHome) {
        $failures += '未找到 JDK。请安装 JDK 17 并设置 JAVA_HOME。'
    } else {
        # 优先读 JDK 自带的 release 文件：java -version 写 stderr，
        # 在 $ErrorActionPreference=Stop 下会被当成终止错误，反而掩盖真正原因
        $releaseFile = Join-Path $javaHome 'release'
        $versionText = if (Test-Path $releaseFile) { Get-Content $releaseFile -Raw } else { '' }
        $major = 0
        if ($versionText -match 'JAVA_VERSION="(\d+)') {
            $major = [int]$Matches[1]
        } else {
            $previous = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            try {
                $versionText = (& (Join-Path $javaHome 'bin\java.exe') -version 2>&1 | Out-String)
            } finally {
                $ErrorActionPreference = $previous
            }
            if ($versionText -match 'version "(\d+)') { $major = [int]$Matches[1] }
        }
        if ($major -gt 0 -and $major -lt 17) {
            $failures += "JDK 版本过低（$major），本项目需要 17。当前：$javaHome"
        } else {
            Write-Ok "JDK $major ($javaHome)"
        }
    }

    $sdk = Resolve-AndroidSdk
    if (-not $sdk) {
        $failures += '未找到 Android SDK。请设置 ANDROID_HOME 或在 local.properties 填写 sdk.dir。'
    } else {
        $compileSdk = Get-CompileSdk
        $platformJar = Join-Path $sdk "platforms\android-$compileSdk\android.jar"
        if (Test-Path $platformJar) {
            Write-Ok "Android SDK ($sdk)"
            Write-Ok "platform android-$compileSdk"
        } else {
            $failures += "缺少 platform android-$compileSdk。请在 SDK Manager 安装对应平台。"
        }
        $buildTools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending
        if ($buildTools) {
            $aapt = Join-Path $buildTools[0].FullName 'aapt2.exe'
            if (Test-Path $aapt) { Write-Ok "build-tools $($buildTools[0].Name)" }
            else { $failures += 'build-tools 目录缺少 aapt2，请重新安装 build-tools。' }
        } else {
            $failures += '缺少 build-tools，请通过 SDK Manager 安装。'
        }
    }

    $gradle = Resolve-Gradle
    if (-not $gradle) {
        $failures += '未找到 Gradle（本项目也没有 gradlew）。请把 gradle 加入 PATH 或放在 E:\Android\gradle-*。'
    } else {
        Write-Ok "Gradle ($gradle)"
    }

    # 首次构建必须联网拉依赖；缓存存在时只提示，避免把离线复用误判成失败
    $gradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
    $gradleCache = Join-Path $gradleHome 'caches\modules-2'
    if (Test-Path $gradleCache) {
        Write-Ok 'Gradle 依赖缓存'
    } else {
        Write-Note 'Gradle 依赖缓存为空：首次构建需要联网（项目已配置阿里云镜像）'
    }

    if ($NeedAdb) {
        $adb = $null
        if ($sdk) {
            $adbPath = Join-Path $sdk 'platform-tools\adb.exe'
            if (Test-Path $adbPath) { $adb = $adbPath }
        }
        if (-not $adb) {
            $cmd = Get-Command adb -ErrorAction SilentlyContinue
            if ($cmd) { $adb = $cmd.Source }
        }
        if ($adb) { Write-Ok "adb ($adb)" }
        else { $failures += '未找到 adb，无法安装到设备。请安装 Android platform-tools。' }
    }

    if ($failures.Count -gt 0) {
        Write-Host ""
        foreach ($item in $failures) { Write-Fail $item }
        throw '构建依赖检查未通过'
    }
    Write-Host ""
}

function Step-BuildPatch {
    if (-not (Test-Path $script:PackageJson)) {
        throw "缺少根 package.json：$($script:PackageJson)"
    }
    $meta = Get-Content $script:PackageJson -Raw | ConvertFrom-Json
    $today = Get-Date -Format 'yyyyMMdd'

    # 同一天内多次构建按序号递增，跨天自动从 1 开始；patch 号只用于人眼识别，
    # 每次构建唯一性由单调递增的 buildNumber（即 versionCode）保证
    $lastPatch = ''
    if ($meta.build) { $lastPatch = [string]$meta.build.patch }
    $seq = 0
    if ($lastPatch -match ('^' + $today + '\.(\d+)$')) { $seq = [int]$Matches[1] }
    $seq++

    $buildNumber = 1
    if ($meta.build -and $meta.build.buildNumber) { $buildNumber = [int]$meta.build.buildNumber + 1 }
    $patch = "$today.$seq"
    $version = if ($meta.version) { [string]$meta.version } else { '0.1.0' }
    $builtAt = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    $repoUrl = ''
    if ($meta.repository -and $meta.repository.url) { $repoUrl = [string]$meta.repository.url }
    $displayName = if ($meta.displayName) { [string]$meta.displayName } else { 'QuietSentry' }
    $description = if ($meta.description) { [string]$meta.description } else { '' }

    $text = @"
{
  "name": "$($meta.name)",
  "displayName": "$displayName",
  "version": "$version",
  "private": true,
  "description": "$description",
  "repository": {
    "type": "git",
    "url": "$repoUrl"
  },
  "build": {
    "buildNumber": $buildNumber,
    "patch": "$patch",
    "builtAt": "$builtAt"
  }
}
"@
    # 无 BOM 写入：Gradle 的 readText 按 UTF-8 读取，BOM 会粘在首字段上
    [System.IO.File]::WriteAllText($script:PackageJson, $text, (New-Object System.Text.UTF8Encoding($false)))

    [pscustomobject]@{
        Version     = $version
        Patch       = $patch
        BuildNumber = $buildNumber
        VersionName = "$version-$patch"
    }
}

function Invoke-GradleTask {
    param([Parameter(Mandatory)][string[]]$Tasks)

    $tc = Get-Toolchain
    if (-not $tc.Gradle) { throw '未找到 Gradle 可执行文件' }
    if (-not $tc.JavaHome) { throw '未找到 JDK' }
    if (-not $tc.Sdk) { throw '未找到 Android SDK' }

    # 环境变量只在本进程内生效，不改动用户全局配置
    $env:JAVA_HOME = $tc.JavaHome
    $env:ANDROID_HOME = $tc.Sdk
    $env:ANDROID_SDK_ROOT = $tc.Sdk
    $env:PATH = "$($tc.JavaHome)\bin;$($tc.Sdk)\platform-tools;$env:PATH"

    Push-Location $script:ProjectRoot
    try {
        $gradleArgs = @($Tasks) + @('--console=plain', '--stacktrace')
        & $tc.Gradle @gradleArgs
        if ($LASTEXITCODE -ne 0) { throw "Gradle 任务失败（退出码 $LASTEXITCODE）" }
    } finally {
        Pop-Location
    }
}

function Find-Apk {
    param([ValidateSet('debug', 'release')][string]$Variant = 'debug')
    $dir = Join-Path $script:ProjectRoot "app\build\outputs\apk\$Variant"
    if (-not (Test-Path $dir)) { return $null }
    # release 未签名时文件名带 -unsigned，因此按“最新产物”取，而不是拼固定文件名
    $apk = Get-ChildItem $dir -Filter '*.apk' -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($apk) { return $apk.FullName }
    return $null
}

function Get-AdbSerials {
    $tc = Get-Toolchain
    if (-not $tc.Adb) { throw '未找到 adb' }
    $lines = & $tc.Adb devices
    $serials = @()
    foreach ($line in $lines) {
        if ($line -match '^(\S+)\s+device$') { $serials += $Matches[1] }
    }
    return @($serials)
}
