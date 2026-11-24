# 构建脚本共用的工具链定位、版本推进与产物查找。
# 每个入口脚本都 dot-source 本文件，避免各自的路径判断互相漂移。
$ErrorActionPreference = 'Stop'

$script:ProjectRoot = Split-Path -Parent $PSScriptRoot
$script:PackageJson = Join-Path $script:ProjectRoot 'package.json'
$script:ReleaseDir = Join-Path $script:ProjectRoot 'release'

function Get-BuildRoot {
    # Gradle 构建目录前缀只在 gradle.properties 的 qs.buildDir 定义一次，
    # 这里读同一行，避免「Gradle 写到 A、脚本去 B 找产物」这种两边漂移。
    $prefix = 'release\.build'
    $props = Join-Path $script:ProjectRoot 'gradle.properties'
    if (Test-Path -LiteralPath $props) {
        $line = Get-Content -LiteralPath $props -Encoding UTF8 |
            Where-Object { $_ -match '^\s*qs\.buildDir\s*=' } |
            Select-Object -First 1
        if ($line) {
            $value = ($line -split '=', 2)[1].Trim()
            if ($value) { $prefix = $value }
        }
    }
    # 允许 qs.buildDir 配成绝对路径（换盘构建时避免拼成 X:\proj\E:\...）。
    if ([System.IO.Path]::IsPathRooted($prefix)) {
        return [System.IO.Path]::GetFullPath($prefix)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $script:ProjectRoot $prefix))
}

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

function Get-JavaMajorVersion {
    param([Parameter(Mandatory)][string]$JavaHome)
    # 返回 JDK 主版本号：Java 9+ 取首个数字，Java 8 及以前取 1.8 的第二个数字。
    # 优先读 JDK 自带 release 文件（java -version 写 stderr，在 Stop 模式下易掩盖真实错误），
    # 缺失时回退到 java -version 输出。
    $releaseFile = Join-Path $JavaHome 'release'
    $versionText = if (Test-Path $releaseFile) { Get-Content $releaseFile -Raw } else { '' }
    if ($versionText -notmatch 'JAVA_VERSION="([^"]+)"') {
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $versionText = (& (Join-Path $JavaHome 'bin\java.exe') -version 2>&1 | Out-String)
        } finally {
            $ErrorActionPreference = $previous
        }
    }
    $ver = $null
    $m = [regex]::Match($versionText, 'version "([^"]+)"')
    if ($m.Success) { $ver = $m.Groups[1].Value }
    else {
        $m2 = [regex]::Match($versionText, 'JAVA_VERSION="([^"]+)"')
        if ($m2.Success) { $ver = $m2.Groups[1].Value }
    }
    if (-not $ver) { return 0 }
    $parts = $ver -split '[._+]'
    if ($parts[0] -eq '1' -and $parts.Count -gt 1) { return [int]$parts[1] }
    return [int]$parts[0]
}

function Resolve-BuildJavaHome {
    # 构建必须用 ≤17 的 JDK：本项目 AGP/Kotlin 版本无法解析高于 17 的 JDK 版本号
    # （实测 JDK 25 在 Kotlin JavaVersion.parse 抛 IllegalArgumentException: 25.0.1）。
    # 解析顺序：已知 JDK 17 目录 -> 环境 JAVA_HOME（若 ≤17）-> PATH 上的 java（若 ≤17）。
    # 命中返回该 JDK 根目录；全部不满足返回 $null，交由依赖检查硬失败而非在 Gradle 阶段崩溃。
    $maxMajor = 17
    $jdk17Base = 'E:\Android\jdk17'
    if (Test-Path $jdk17Base) {
        $roots = @($jdk17Base) + @(Get-ChildItem $jdk17Base -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
        foreach ($root in $roots) {
            if (Test-Path (Join-Path $root 'bin\javac.exe')) {
                $v = Get-JavaMajorVersion $root
                if ($v -and $v -le $maxMajor) { return $root }
            }
        }
    }
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        $v = Get-JavaMajorVersion $env:JAVA_HOME
        if ($v -and $v -le $maxMajor) { return $env:JAVA_HOME }
    }
    $java = Get-Command java -ErrorAction SilentlyContinue
    if ($java) {
        $home = Split-Path -Parent (Split-Path -Parent $java.Source)
        if (Test-Path (Join-Path $home 'bin\javac.exe')) {
            $v = Get-JavaMajorVersion $home
            if ($v -and $v -le $maxMajor) { return $home }
        }
    }
    return $null
}

function Get-KotlinPersistentDir {
    # Kotlin 插件 2.x 的 kotlin.project.persistent.dir 指向的目录必须预先存在：
    # 它不会自动 mkdir，目录缺失时编译 daemon 写会话文件会抛
    # java.io.IOException: 系统找不到指定的路径（GradleErrorMessageCollector.flush），
    # 随后 4 次重连失败并回退无 daemon，仍写同一路径而彻底失败。
    # 路径来源与 Gradle 侧同源：gradle.properties 的 kotlin.project.persistent.dir。
    $prefix = 'release\.build\kotlin'
    $props = Join-Path $script:ProjectRoot 'gradle.properties'
    if (Test-Path -LiteralPath $props) {
        $line = Get-Content -LiteralPath $props -Encoding UTF8 |
            Where-Object { $_ -match '^\s*kotlin\.project\.persistent\.dir\s*=' } |
            Select-Object -First 1
        if ($line) {
            $value = ($line -split '=', 2)[1].Trim()
            if ($value) { $prefix = $value }
        }
    }
    # 允许把 persistent.dir 配成绝对路径（换盘构建时避免相对路径被解析到
    # 错误的工作目录）；绝对路径不能再拼项目根，否则会得到 X:\proj\X:\... 。
    if ([System.IO.Path]::IsPathRooted($prefix)) {
        return [System.IO.Path]::GetFullPath($prefix)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $script:ProjectRoot $prefix))
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
    # 构建用的 JDK 必须是 ≤17；Resolve-BuildJavaHome 负责在环境 JDK 过高时自动切换
    $javaHome = Resolve-BuildJavaHome
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

    # 环境里实际指向的 JDK（可能是用户 PATH/JAVA_HOME 设的过高版本），仅用于提示切换来源
    $envJavaHome = Resolve-JavaHome
    $envMajor = if ($envJavaHome) { Get-JavaMajorVersion $envJavaHome } else { 0 }

    # 实际用于构建的 JDK：必须是 ≤17，否则 Kotlin 编译器会抛 IllegalArgumentException
    $buildJava = Resolve-BuildJavaHome
    if (-not $buildJava) {
        if ($envMajor -gt 17) {
            $failures += "JAVA_HOME 指向 JDK $envMajor（$envJavaHome），高于 17，本项目 AGP/Kotlin 无法解析该版本号；且在 E:\Android\jdk17 也未找到兼容的 JDK 17。请安装 JDK 17 并放到 E:\Android\jdk17。"
        } elseif ($envMajor -gt 0 -and $envMajor -lt 17) {
            $failures += "JDK 版本过低（$envMajor），本项目需要 JDK 17。当前：$envJavaHome"
        } else {
            $failures += '未找到 JDK 17。请安装 JDK 17 并放到 E:\Android\jdk17，或设置 JAVA_HOME 指向 JDK 17。'
        }
    } else {
        $buildMajor = Get-JavaMajorVersion $buildJava
        if ($envJavaHome -and $envJavaHome -ne $buildJava -and $envMajor -gt 17) {
            # 环境 JDK 过高会拖垮 Kotlin 编译，这里明确告知已自动切到兼容 JDK，避免“警告后崩溃”
            Write-Note "JAVA_HOME 指向 JDK $envMajor（$envJavaHome），高于 17 会导致 Kotlin 编译失败；已自动改用 JDK $buildMajor（$buildJava）"
        }
        Write-Ok "JDK $buildMajor ($buildJava)"
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
        # 项目级缓存（默认项目根 .gradle）也收进 release/.build，删 release/ 时不留残渣
        $projectCache = Join-Path (Get-BuildRoot) 'gradle'
        if (-not (Test-Path -LiteralPath $projectCache)) {
            New-Item -ItemType Directory -Path $projectCache -Force | Out-Null
        }
        # Kotlin 2.x 不会自动创建 persistent.dir，目录缺失会直接搞垮编译 daemon；
        # 构建前确保它存在（路径来自 gradle.properties，不在这里硬编码）。
        $kotlinDir = Get-KotlinPersistentDir
        if (-not (Test-Path -LiteralPath $kotlinDir)) {
            New-Item -ItemType Directory -Path $kotlinDir -Force | Out-Null
        }
        # Kotlin 插件的会话标记写在 persistent.dir/sessions 下，父目录同样不会自动建；
        # 只建 persistent.dir 时新机器（无历史缓存）会在第 1 次编译即 NoSuchFileException。
        $kotlinSessions = Join-Path $kotlinDir 'sessions'
        if (-not (Test-Path -LiteralPath $kotlinSessions)) {
            New-Item -ItemType Directory -Path $kotlinSessions -Force | Out-Null
        }
        $gradleArgs = @($Tasks) + @("--project-cache-dir=$projectCache", '--console=plain', '--stacktrace')
        & $tc.Gradle @gradleArgs
        if ($LASTEXITCODE -ne 0) { throw "Gradle 任务失败（退出码 $LASTEXITCODE）" }
    } finally {
        Pop-Location
    }
}

function Find-GradleApk {
    param([ValidateSet('debug', 'release')][string]$Variant = 'debug')
    # Gradle 原始产物：只供构建/发布脚本复制到 release/，不作为安装或发布入口。
    # 目录跟随 qs.buildDir（release/.build/app），不再是模块下的 app/build。
    $dir = Join-Path (Get-BuildRoot) "app\outputs\apk\$Variant"
    if (-not (Test-Path -LiteralPath $dir)) { return $null }
    # release 未签名时文件名带 -unsigned，因此按“最新产物”取，而不是拼固定文件名
    $apk = Get-ChildItem $dir -Filter '*.apk' -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($apk) { return $apk.FullName }
    return $null
}

function Find-GradleBundle {
    $dir = Join-Path (Get-BuildRoot) 'app\outputs\bundle\release'
    if (-not (Test-Path -LiteralPath $dir)) { return $null }
    $aab = Get-ChildItem $dir -Filter '*.aab' -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($aab) { return $aab.FullName }
    return $null
}

# ---------- release 产物目录（T04714） ----------
# 所有可重建产物都收在这一棵树里：顶层放最终交付物，.build 下放 Gradle 中间产物、报告、
# 项目缓存与 Scanner 工作目录。整目录可删：下一次构建 / 发布入口会全部重建。
function Get-ReleaseDir {
    return $script:ReleaseDir
}

function Reset-ReleaseDir {
    # 只清最终交付物，保留 .build 内的编译缓存：否则每次构建都等于 clean build，
    # 编译时间白白翻倍。需要连中间产物一起清时，删整个 release/ 或跑 scripts\清理.bat。
    $dir = Get-ReleaseDir
    if (-not (Test-Path -LiteralPath $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    } else {
        Get-ChildItem -LiteralPath $dir -Force |
            Where-Object { $_.Name -ne '.build' } |
            ForEach-Object {
                Remove-Item -LiteralPath $_.FullName -Recurse -Force
            }
    }
    return $dir
}

function New-ArtifactName {
    param(
        [Parameter(Mandatory)][string]$VersionName,
        [string]$Variant = '',
        [Parameter(Mandatory)][string]$Extension
    )
    $safe = $VersionName -replace '[\\/:*?"<>|]', '-'
    $suffix = if ($Variant) { "-$Variant" } else { '' }
    return "QuietSentry-$safe$suffix.$Extension"
}

function Publish-Artifact {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Name
    )
    if (-not (Test-Path -LiteralPath $Path)) { throw "产物不存在：$Path" }
    $dir = Get-ReleaseDir
    if (-not (Test-Path -LiteralPath $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
    $target = Join-Path $dir $Name
    Copy-Item -LiteralPath $Path -Destination $target -Force
    return $target
}

function Write-ReleaseManifest {
    param([Parameter(Mandatory)][hashtable]$Data)
    $dir = Get-ReleaseDir
    if (-not (Test-Path -LiteralPath $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
    $json = $Data | ConvertTo-Json -Depth 5
    $file = Join-Path $dir 'build-info.json'
    [System.IO.File]::WriteAllText($file, $json, (New-Object System.Text.UTF8Encoding($false)))
    return $file
}

function Find-Apk {
    param([ValidateSet('debug', 'release')][string]$Variant = 'debug')
    # 安装 / 发布入口只认 release/，避免把 Gradle 中间目录里的旧包推上设备
    $dir = Get-ReleaseDir
    if (-not (Test-Path -LiteralPath $dir)) { return $null }
    $files = @(Get-ChildItem -LiteralPath $dir -Filter '*.apk' -File |
        Sort-Object LastWriteTime -Descending)
    if ($files.Count -eq 0) { return $null }
    $pattern = '-{0}\.apk$' -f $Variant
    $match = $files | Where-Object { $_.Name -match $pattern } | Select-Object -First 1
    if ($match) { return $match.FullName }
    # 兼容手工放入的单个 APK；多文件且无法判定 variant 时不猜测
    if ($files.Count -eq 1) { return $files[0].FullName }
    return $null
}

function Find-Bundle {
    $dir = Get-ReleaseDir
    if (-not (Test-Path -LiteralPath $dir)) { return $null }
    $aab = Get-ChildItem -LiteralPath $dir -Filter '*.aab' -File |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($aab) { return $aab.FullName }
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

# ---------- 统一构建链 ----------
# 打包构建（build.ps1）与一键发布（release.ps1）共用这一段：依赖检查、版本推进、
# Gradle 调用、产物复制到 release/、写构建清单。以前两边各写一份，改一处忘一处
# 就会出现「按钮打的包和一键发布发的包不是一回事」。
function Get-GitHead {
    $head = ''
    $dirty = $false
    # 原生命令的 stderr 在 Stop 模式下会被当成异常，这里临时降级
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = (& git -C $script:ProjectRoot rev-parse --short HEAD 2>$null)
        if ($LASTEXITCODE -eq 0 -and $out) { $head = ($out -join '').Trim() }
        $status = (& git -C $script:ProjectRoot status --porcelain 2>$null)
        if ($status) { $dirty = $true }
    } catch {
        # 没有 git 或不在仓库里：清单里留空，不阻断构建
    } finally {
        $ErrorActionPreference = $previous
    }
    [pscustomobject]@{ Head = $head; Dirty = $dirty }
}

function Invoke-BuildChain {
    param(
        [ValidateSet('debug', 'release')][string]$Variant = 'debug',
        [switch]$Clean,
        [switch]$SkipPatch,
        [switch]$IncludeBundle,
        [switch]$SkipDependencyCheck
    )

    if (-not $SkipDependencyCheck) { Assert-BuildDependencies }

    # 每次构建先清空 release/，保证目录里只留本次的一套产物
    $releaseDir = Reset-ReleaseDir
    Write-Ok "产物目录 $releaseDir（已清空旧产物）"

    if ($SkipPatch) {
        $meta = Get-Content $script:PackageJson -Raw | ConvertFrom-Json
        $versionName = [string]$meta.version
        $buildNumber = if ($meta.build -and $meta.build.buildNumber) { [int]$meta.build.buildNumber } else { 0 }
        Write-Note "跳过 patch 推进，使用基线版本 $versionName"
    } else {
        $meta = Step-BuildPatch
        $versionName = [string]$meta.VersionName
        $buildNumber = [int]$meta.BuildNumber
        Write-Ok "版本 $versionName（versionCode $buildNumber）"
    }

    $tasks = @()
    if ($Clean) { $tasks += 'clean' }
    $tasks += $(if ($Variant -eq 'debug') { 'assembleDebug' } else { 'assembleRelease' })
    if ($IncludeBundle) { $tasks += 'bundleRelease' }
    Write-Step "执行 Gradle: $($tasks -join ' ')"
    Invoke-GradleTask -Tasks $tasks

    $sourceApk = Find-GradleApk -Variant $Variant
    if (-not $sourceApk) { throw "构建结束但没有找到 $Variant APK" }
    $apkName = New-ArtifactName -VersionName $versionName -Variant $Variant -Extension 'apk'
    $apk = Publish-Artifact -Path $sourceApk -Name $apkName
    $artifacts = @(@{ file = $apkName; source = $sourceApk })

    $aab = ''
    $aabName = ''
    if ($IncludeBundle) {
        $sourceAab = Find-GradleBundle
        if (-not $sourceAab) { throw '构建结束但没有找到 release AAB' }
        $aabName = New-ArtifactName -VersionName $versionName -Extension 'aab'
        $aab = Publish-Artifact -Path $sourceAab -Name $aabName
        $artifacts += @{ file = $aabName; source = $sourceAab }
    }

    # 清单记录版本、变体、产物、Gradle 任务与实际提交：发布脚本据此判断
    # 「release/ 里这套产物是不是当前代码构建出来的」，而不是靠文件名猜
    $git = Get-GitHead
    $builtAt = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    $manifestFile = Write-ReleaseManifest -Data @{
        product     = 'QuietSentry'
        versionName = $versionName
        variant     = $Variant
        buildNumber = $buildNumber
        builtAt     = $builtAt
        gradleTasks = $tasks
        commit      = $git.Head
        dirty       = $git.Dirty
        artifacts   = $artifacts
    }

    [pscustomobject]@{
        Variant      = $Variant
        ReleaseDir   = $releaseDir
        VersionName  = $versionName
        BuildNumber  = $buildNumber
        Tag          = "v$versionName"
        Apk          = $apk
        Aab          = $aab
        ApkName      = $apkName
        AabName      = $aabName
        ManifestFile = $manifestFile
        BuiltAt      = $builtAt
        Commit       = $git.Head
        Dirty        = $git.Dirty
    }
}

function Read-BuildManifest {
    param(
        [ValidateSet('debug', 'release')][string]$RequiredVariant = 'release'
    )
    # 发布侧复用构建产物前必须先读清单：变体不对、文件被删、代码已改过都要说清楚，
    # 否则会出现「发布记录说 0.1.0-x，实际装的却是上一个包」。
    $dir = Get-ReleaseDir
    $file = Join-Path $dir 'build-info.json'
    if (-not (Test-Path -LiteralPath $file)) {
        throw @"
没有构建清单：$file
请先运行 scripts\安卓构建打包.bat -Variant release（或直接跑 scripts\一键发布.bat 让它自己构建）
"@
    }

    $data = Get-Content -LiteralPath $file -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($data.variant -ne $RequiredVariant) {
        throw "release/ 里的产物是 $($data.variant) 变体（$($data.versionName)），不是 $RequiredVariant；请先运行 scripts\安卓构建打包.bat -Variant release"
    }

    $apk = ''
    $aab = ''
    foreach ($item in @($data.artifacts)) {
        if (-not $item.file) { continue }
        $path = Join-Path $dir $item.file
        if (-not (Test-Path -LiteralPath $path)) {
            throw "构建清单里登记的产物已不存在：$path`nrelease/ 可能被清过，请重新构建或去掉 -SkipBuild"
        }
        if ($path -match '\.apk$') { $apk = $path }
        if ($path -match '\.aab$') { $aab = $path }
    }
    if (-not $apk) { throw "构建清单里没有 APK：$file" }
    if (-not $aab) {
        throw "构建清单里没有 AAB：$file`nscripts\安卓构建打包.bat -Variant release 会同时产出 APK 与 AAB，重新构建即可"
    }

    # 代码变更只提示不阻断：本地实验时工作区常是脏的，硬拦会让发布流程不可用
    $git = Get-GitHead
    if ($data.commit -and $git.Head -and $data.commit -ne $git.Head) {
        Write-Note "这套产物由提交 $($data.commit) 构建，当前 HEAD 是 $($git.Head)：发布包内容可能与 tag 指向的代码不一致"
    }
    if ($data.dirty) {
        Write-Note '这套产物构建时工作区有未提交改动，发布包的代码状态不在任何提交里'
    }

    [pscustomobject]@{
        VersionName = [string]$data.versionName
        Variant     = [string]$data.variant
        BuiltAt     = [string]$data.builtAt
        Commit      = [string]$data.commit
        Dirty       = [bool]$data.dirty
        Apk         = $apk
        Aab         = $aab
    }
}
