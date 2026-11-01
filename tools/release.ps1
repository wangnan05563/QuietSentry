param(
    [switch]$SkipBuild,
    [switch]$Draft,
    [switch]$NoUpload
)

# 一键发布：推进 patch -> 签名构建 APK 与 AAB -> 校验签名 -> 创建 GitHub Release。
# 任何一步失败都保留已生成产物，退出码非 0，交由人工判断而不是静默发布半成品。
. (Join-Path $PSScriptRoot 'build-lib.ps1')

function Get-KeystoreProps {
    $file = Join-Path $script:ProjectRoot 'keystore.properties'
    if (-not (Test-Path $file)) {
        throw @'
缺少 keystore.properties。请先创建发布密钥（密钥文件与配置均不进版本库）：

  keytool -genkeypair -v -keystore release.jks -alias quiet-sentry ^
    -keyalg RSA -keysize 2048 -validity 10000

然后把 keystore.properties.example 复制为 keystore.properties，填入 storeFile、
storePassword、keyAlias、keyPassword。仅本地使用，切勿提交。
'@
    }
    $props = @{}
    # PowerShell 5.1 对无 BOM 的 UTF-8 文件按 ANSI 解码，中文注释会把换行吞掉，
    # 导致注释与下一行配置粘连；这里显式指定 UTF8，避免注释语言影响解析
    foreach ($line in Get-Content $file -Encoding UTF8) {
        if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
        $key, $value = $line -split '=', 2
        $props[$key.Trim()] = $value.Trim()
    }
    foreach ($required in @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')) {
        if (-not $props[$required]) { throw "keystore.properties 缺少 $required" }
        if ($props[$required] -match '^<.*>$') { throw "keystore.properties 仍为占位符：$required" }
    }
    return $props
}

function Get-ApkSigner {
    $sdk = Resolve-AndroidSdk
    if (-not $sdk) { return $null }
    $buildTools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending
    foreach ($tool in $buildTools) {
        $signer = Join-Path $tool.FullName 'apksigner.bat'
        if (Test-Path $signer) { return $signer }
    }
    return $null
}

function Get-RepoSlug {
    $remote = & git -C $script:ProjectRoot remote get-url origin 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $remote) { return $null }
    if ($remote -match 'github\.com[:/]([^/]+/[^/.]+)(\.git)?$') { return $Matches[1] }
    return $null
}

try {
    Write-Step '一键发布'
    Assert-BuildDependencies
    $props = Get-KeystoreProps
    $keystorePath = Join-Path $script:ProjectRoot $props['storeFile']
    if (-not (Test-Path $keystorePath)) { throw "密钥库不存在：$keystorePath" }
    Write-Ok "发布密钥 $($props['storeFile'])（alias $($props['keyAlias'])）"

    if ($SkipBuild) {
        $meta = Get-Content $script:PackageJson -Raw | ConvertFrom-Json
        $versionName = [string]$meta.version
        $tag = "v$versionName"
        Write-Note "跳过构建，按当前版本 $versionName 发布"
    } else {
        $meta = Step-BuildPatch
        $tag = "v$($meta.VersionName)"
        Write-Ok "版本 $($meta.VersionName)（versionCode $($meta.BuildNumber)）"
        Write-Step '构建 release APK 与 AAB'
        Invoke-GradleTask -Tasks @('clean', 'assembleRelease', 'bundleRelease')
    }

    $apk = Find-Apk -Variant 'release'
    if (-not $apk) { throw '未找到 release APK' }
    $aab = Join-Path $script:ProjectRoot 'app\build\outputs\bundle\release\app-release.aab'
    if (-not (Test-Path $aab)) { throw '未找到 release AAB' }

    Write-Step '校验 APK 签名'
    $signer = Get-ApkSigner
    if (-not $signer) { throw '未找到 apksigner，请安装 build-tools' }
    & $signer verify --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK 签名校验失败' }
    Write-Ok '签名有效'

    $apkInfo = Get-Item $apk
    $aabInfo = Get-Item $aab
    Write-Ok "APK $([math]::Round($apkInfo.Length / 1MB, 2)) MB：$($apkInfo.FullName)"
    Write-Ok "AAB $([math]::Round($aabInfo.Length / 1MB, 2)) MB：$($aabInfo.FullName)"

    if ($NoUpload) {
        Write-Note '已指定 -NoUpload：产物留在本地，未创建 GitHub Release'
        exit 0
    }

    $slug = Get-RepoSlug
    if (-not $slug) {
        Write-Note '未配置 GitHub origin，跳过发布。产物已生成，可手动上传。'
        Write-Host '  配置远端后重试：git remote add origin https://github.com/<owner>/<repo>.git'
        exit 0
    }

    $notes = (& git -C $script:ProjectRoot log --pretty=format:'- %s' -12 | Out-String).Trim()
    if (-not $notes) { $notes = "Release $tag" }

    $gh = Get-Command gh -ErrorAction SilentlyContinue
    if ($gh) {
        Write-Step "使用 gh 发布 $tag 到 $slug"
        $ghArgs = @('release', 'create', $tag, $apk, $aab, '--title', $tag, '--notes', $notes, '--repo', $slug)
        if ($Draft) { $ghArgs += '--draft' }
        & $gh.Source @ghArgs
        if ($LASTEXITCODE -ne 0) { throw 'gh release create 失败' }
        Write-Ok "已发布：https://github.com/$slug/releases/tag/$tag"
        exit 0
    }

    $token = if ($env:GITHUB_TOKEN) { $env:GITHUB_TOKEN } elseif ($env:GH_TOKEN) { $env:GH_TOKEN } else { '' }
    if (-not $token) {
        Write-Note '未安装 gh 且未设置 GITHUB_TOKEN，跳过发布。产物已生成，可手动上传。'
        Write-Host '  安装 gh：winget install GitHub.cli；或设置 GITHUB_TOKEN 后重跑。'
        exit 0
    }

    Write-Step "使用 GitHub REST API 发布 $tag 到 $slug"
    $headers = @{
        Authorization          = "token $token"
        Accept                 = 'application/vnd.github+json'
        'User-Agent'           = 'QuietSentry-release'
        'X-GitHub-Api-Version' = '2022-11-28'
    }
    $body = @{
        tag_name   = $tag
        name       = $tag
        body       = $notes
        draft      = [bool]$Draft
        prerelease = $false
    } | ConvertTo-Json -Depth 4
    $release = Invoke-RestMethod -Method Post `
        -Uri "https://api.github.com/repos/$slug/releases" `
        -Headers $headers `
        -ContentType 'application/json; charset=utf-8' `
        -Body ([System.Text.Encoding]::UTF8.GetBytes($body))
    Write-Ok "Release 已创建（id $($release.id)）"

    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if (-not $curl) { throw '未找到 curl.exe，无法上传产物' }
    $assets = @(
        @{ Path = $apk; Name = $apkInfo.Name; Type = 'application/vnd.android.package-archive' },
        @{ Path = $aab; Name = $aabInfo.Name; Type = 'application/octet-stream' }
    )
    foreach ($asset in $assets) {
        Write-Note "上传 $($asset.Name)"
        & $curl.Source -sS -X POST `
            -H "Authorization: token $token" `
            -H "Content-Type: $($asset.Type)" `
            --data-binary "@$($asset.Path)" `
            "https://uploads.github.com/repos/$slug/releases/$($release.id)/assets?name=$($asset.Name)"
        if ($LASTEXITCODE -ne 0) { throw "上传 $($asset.Name) 失败" }
        Write-Host ""
    }
    Write-Ok "已发布：https://github.com/$slug/releases/tag/$tag"
    Write-Host ""
} catch {
    Write-Fail $_.Exception.Message
    exit 1
}
