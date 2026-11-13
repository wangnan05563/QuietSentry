# 构建产物目录说明（T04714）

| 项目 | 内容 |
| --- | --- |
| 对应任务 | MTask T04714 构建脚本优化：产物集中至 release 目录 |
| 日期 | 2026-11-09 |
| 适用范围 | `scripts/` 下的构建、安装与发布脚本 |
| 产物目录 | 项目根 `release/`，唯一最终产物目录，已被 `.gitignore` 忽略 |

## 1 结论

最终交付物（APK、AAB、构建清单）统一写入项目根的 `release/`。删除整个 `release/` 后，重新执行构建或发布入口即可完整重建，无需手工创建目录，也不会影响源码与 Gradle 依赖缓存。

Gradle 的中间产物仍留在 `app/build/`（由 `.gitignore` 的 `build/` 忽略）。Android Gradle 插件对输出目录有固定约定，强行改写会破坏编译缓存、增量构建与 IDE 同步，因此采用「Gradle 照常产出 → 脚本复制到 `release/`」的方式，只集中最终产物。

## 2 目录结构

```text
release/
├── QuietSentry-<versionName>-debug.apk      # build.ps1 -Variant debug
├── QuietSentry-<versionName>-release.apk    # release.ps1（与 AAB 同批）
├── QuietSentry-<versionName>.aab
└── build-info.json                          # 版本、variant、构建时间、Gradle 源文件路径
```

版本名中的文件名非法字符（路径分隔符、冒号、问号等）统一替换为 `-`。

## 3 改动清单

| 文件 | 改动 |
| --- | --- |
| `.gitignore` | 新增 `release/`，避免构建产物进版本库 |
| `scripts/build-lib.ps1` | 新增 `$script:ReleaseDir` 与产物目录公共函数；`Find-Apk` 改为只读 `release/` |
| `scripts/build.ps1` | 构建前清空 `release/`；构建后复制 APK、写 `build-info.json`；报告产物路径 |
| `scripts/release.ps1` | 构建前清空 `release/`；构建后复制 APK + AAB 并写清单；验签与上传改读 `release/`；`-SkipBuild` 复用已有产物且不清空 |
| `scripts/push.ps1` | 逻辑不变：经 `Find-Apk` 自动改读 `release/` |
| `scripts/build-and-push.ps1` | 逻辑不变：串联 `build.ps1` + `push.ps1`，两段都走 `release/` |
| `docs/09-构建产物与release目录.md` | 本说明 |

### 公共函数（`build-lib.ps1`）

| 函数 | 作用 |
| --- | --- |
| `Get-ReleaseDir` | 返回项目根下 `release` 路径（由 `$PSScriptRoot` 推导，无硬编码盘符） |
| `Reset-ReleaseDir` | 存在则清空子项，不存在则创建；构建 / 发布入口开头调用 |
| `New-ArtifactName` | 由版本名 + variant + 扩展名生成规范文件名 |
| `Publish-Artifact` | 把 Gradle 产物复制到 `release/`，返回目标路径 |
| `Write-ReleaseManifest` | 写无 BOM 的 `build-info.json` |
| `Find-GradleApk` / `Find-GradleBundle` | 只从 `app/build/outputs` 找 Gradle 原始产物，供复制使用 |
| `Find-Apk` / `Find-Bundle` | 只从 `release/` 找最终产物，供安装与发布使用 |

职责分离的意义：安装与发布入口不会再误用 `app/build` 里的旧包；构建入口也不会把中间目录当成交付物。

## 4 清理与幂等

- `build.ps1` 与 `release.ps1`（不带 `-SkipBuild`）在依赖检查通过后、Gradle 执行前调用 `Reset-ReleaseDir`，一次运行只留一套产物，旧包不残留。依赖检查失败时不清空，避免白白删掉上一轮可用产物。
- `release.ps1 -SkipBuild` 的语义是「复用现有产物直接发布」，此时**不清空**，否则会删掉自己要发布的文件；若 `release/` 缺失会明确报错提示先构建。
- 重复运行同一入口（不带 `-SkipBuild`）：先清空 `release/` 再重新构建，目录内只保留本轮产物、结构一致；但 `Step-BuildPatch` 会递增 `build.buildNumber`（versionCode）与 `build.patch`，产物文件名随之变化（如 `0.1.0-20261110.1` → `0.1.0-20261112.1`），因此「幂等」指**目录重建与产物结构一致**，而非文件同名。
- `release.ps1 -SkipBuild` 是严格幂等的入口：不清空、不推进版本，只做验签与发布，重复运行产物字节不变（T05207 实测：连续两次 SHA256 全等）。
- `build-info.json` 的 `builtAt` 反映最后一次构建时间。
- `push.ps1`、`build-and-push.ps1` 不清理目录——安装动作不应删除产物。

## 5 使用与验证

```powershell
# 构建 debug（产物写入 release/）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\build.ps1 -Variant debug -SkipPatch

# 安装到设备（从 release/ 读取）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\push.ps1 -Variant debug

# 一键发布（构建 + 验签 + 上传，产物写入 release/）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\release.ps1 -NoUpload

# 用已有产物再发布一次（不清空）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\release.ps1 -SkipBuild -NoUpload

# 清理：直接删除整个目录
Remove-Item -Recurse -Force release
```

自检顺序：构建 → 断言 `release/` 内 APK 与 `build-info.json` 存在 → 删除 `release/` → 重新构建并断言同样齐全 → 以 `-SkipBuild -NoUpload` 运行发布脚本，断言能找到 `release/` 下的 APK 与 AAB。

## 6 边界与注意

- 不改动核心业务代码与 Gradle 构建逻辑，只改脚本、`.gitignore` 与文档。
- 所有产物路径由项目根相对推导，未引入新的硬编码绝对路径；工具链定位仍沿用 `build-lib.ps1` 既有逻辑。
- `release/` 是纯运行产物目录，不要手工存放需长期保留的文件，也不要提交到版本库。
- 直接由 IDE 触发的 Gradle 构建不会更新 `release/`；需要安装或发布前，请先运行上面的构建入口。
