param(
    [switch]$KeepDaemon
)

# 一键清理：删掉项目里所有可重建产物（release/ 整棵树 + 历史遗留目录），源码与配置一律不动。
# 与构建脚本的区别：构建只清最终交付物、保留 .build 里的编译缓存；本脚本连缓存一起清，
# 效果等同于刚克隆下来的干净工作区（下次构建会慢一次，之后恢复增量）。
. (Join-Path $PSScriptRoot 'build-lib.ps1')

try {
    $root = [System.IO.Path]::GetFullPath($script:ProjectRoot).TrimEnd('\')

    $tc = Get-Toolchain
    if ($tc.Gradle -and -not $KeepDaemon) {
        Write-Step '停止 Gradle 守护进程（释放对构建目录的文件占用）'
        # build-lib.ps1 把 $ErrorActionPreference 设成 Stop，原生命令的 stderr 会被当成
        # 终止性错误抛出（JDK 的 "--stop" 会打印 JVM 警告）。停守护进程失败不该阻断清理，
        # 因此这里临时降级并把失败吞掉：真正的删除失败会在下面的 Remove-Item 上暴露。
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & $tc.Gradle --stop 2>&1 | Out-Null
        } catch {
        } finally {
            $ErrorActionPreference = $previous
        }
    } elseif (-not $KeepDaemon) {
        Write-Note '未找到 Gradle，跳过守护进程停止；若删除被占用，请手动关闭 IDE 后重试'
    } else {
        Write-Note '按 -KeepDaemon 跳过守护进程停止：Windows 上可能因占用删不掉'
    }

    # 只列可重建目录；verify-output 是验证采样证据，不在清理范围内
    $targets = @(
        (Get-ReleaseDir),
        (Join-Path $root 'app\build'),
        (Join-Path $root 'build'),
        (Join-Path $root '.gradle'),
        (Join-Path $root '.kotlin'),
        (Join-Path $root '.scannerwork'),
        (Join-Path $root 'scripts\__pycache__')
    )

    $freed = 0
    $removed = 0
    foreach ($target in $targets) {
        $full = [System.IO.Path]::GetFullPath($target).TrimEnd('\')
        # 只允许删项目根以内的路径：拼错的路径在这里就拦住，绝不交给 Remove-Item
        if ($full -eq $root -or -not $full.StartsWith($root + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "拒绝删除项目根以外的路径：$full"
        }
        if (-not (Test-Path -LiteralPath $full)) { continue }
        $size = (Get-ChildItem -LiteralPath $full -Recurse -File -ErrorAction SilentlyContinue |
            Measure-Object -Property Length -Sum).Sum
        if (-not $size) { $size = 0 }
        Remove-Item -LiteralPath $full -Recurse -Force
        $freed += $size
        $removed++
        Write-Ok "已删除 $($full.Substring($root.Length + 1))（$([math]::Round($size / 1MB, 1)) MB）"
    }

    Write-Host ""
    if ($removed -eq 0) {
        Write-Ok '工作区已经是干净状态，没有需要清理的目录'
    } else {
        Write-Ok "共清理 $removed 处，释放 $([math]::Round($freed / 1MB, 1)) MB"
    }
    Write-Ok '重新执行构建脚本或一键发布即可重建全部产物'
    Write-Host ""
} catch {
    Write-Fail $_.Exception.Message
    exit 1
}