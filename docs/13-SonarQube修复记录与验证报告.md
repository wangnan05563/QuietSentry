# QuietSentry SonarQube 修复记录与验证报告

- 执行日期：2026-11-14（UTC+8）
- 目标：全维度扫描 → 按严重级别修复 → 复扫清零 → 测试 → 生产构建
- 结果概览：**61 条 OPEN 问题全部修复，复扫 0 条 OPEN，new_violations=0；编译通过；生产构建成功且签名校验通过。**
- 未达成项：**质量门仍为 ERROR，唯一原因是新代码覆盖率不足**（补测后 `new_coverage=0.2%`、项目整体 `coverage=2.2%`，阈值 80%）；详见第五节与 `14-JaCoCo覆盖率与单元测试报告.md`。

## 一、修复策略

- 全部问题走**代码修复**，未使用 `@Suppress` / `NOSONAR` 抑制，也未在质量配置中放宽规则。
- 修复顺序：安全漏洞 → BUG → CRITICAL 复杂度/重复字面量 → MAJOR → MINOR。
- 每条修复保持原有行为语义；重构只拆方法、抽常量、补返回值处理，不改变业务分支结果。

## 二、按规则修复明细

| 规则 | 数量 | 修复方式 | 涉及文件 |
|---|---:|---|---|
| kotlin:S3776 | 16 | 按职责拆分长方法，抽取私有辅助方法/私有状态类，降低认知复杂度至 15 以下 | CaptureService、DeviceListActivity、DeviceServer、H264Streamer、HumanDetector、LiveStreamer、MainActivity、MjpegClient、MotionDetector、NetworkPath、SoundDetector |
| kotlin:S4423 | 2 | `SSLContext.getInstance("TLS")` → 常量 `TLS_PROTOCOL="TLSv1.2"`（minSdk 28 不支持 TLS 1.3，选协议下限而非弱协议） | TlsIdentity、TlsTrust |
| kotlin:S6518 | 8 | `Calendar.get(...)` / `ByteBuffer.get(i)` → 索引访问 `[i]` | Deterrent、OnvifService、H264Streamer |
| kotlin:S899 | 7 | 抽出 `deleteFile()` / `clearPendingMarker()`，删除失败记 `Log.w`，不再忽略返回值 | EventStore、CaptureService |
| kotlin:S1192 | 5 | 抽常量：`PENDING_SUFFIX`、`MIME_JPEG`、`MIME_MP4`、`HEADER_ACCEPT_RANGES`、`HEADER_RANGE_BYTES`、`DIGEST_ALGORITHM` | CaptureService、DeviceServer、PairingStore |
| kotlin:S6516 | 4 | `object : Runnable { ... }` → `Runnable { 具名方法() }` | DeviceListActivity、LiveViewActivity、EventPlayerActivity、MainActivity |
| kotlin:S1874 | 4 | `URL(String)` → `URI.create(...).toURL()`（JDK 20+ 弃用；Android 侧等价且更严格） | DeviceHttp、MjpegClient、UpdateChecker |
| kotlin:S1128 | 4 | 删除未使用 import（`Context` / `SuppressLint` / `MotionEvent` / `ScrollView`） | Deterrent、DeviceListActivity |
| kotlin:S6619 | 2 | 去掉恒成立的非空判断与多余 `?.` | DeviceHttp、DeviceListActivity |
| kotlin:S1186 | 2 | 为空回调补“为何为空”的内嵌注释 | LiveStreamer |
| kotlin:S107 | 1 | `DeviceHttp.request` 去掉可由调用方编码的 `body: String?` 参数（8 → 7 参） | DeviceHttp |
| kotlin:S1172 | 1 | `handleInfo(req, out)` → `handleInfo(out)`，同步改路由调用 | DeviceServer |
| kotlin:S1481 | 1 | 删除未使用的 `grant` 局部变量 | DeviceServer |
| kotlin:S1871 | 1 | 两个相同分支合并为一行多值分支 | SettingsActivity |
| kotlin:S6510 | 1 | `when` 各分支 `return` 前置为 `return when(...)` | DeviceServer |
| kotlin:S108 | 1 | 空 catch 补说明注释 | DeviceServer |
| kotlin:S6532 | 1 | `if (code !in 200..299) throw ...` → `check(code in 200..299) { "HTTP $code" }` | UpdateChecker |

### 复杂度重构要点（16 处）

- `CaptureService`：`bindCamera` 拆为 `reportCameraMissing` / `startedCaption` / `buildMotionDetector` / `buildFrameAnalyzer` / `handleAnalysisFrame` / `bindTorchSwitch`；`watchCamera` 拆为 `isSettling` / `onCameraReleased`；`startNextSegment` 拆为 `nextSegmentFile` / `prepareSegment` / `startSegmentRecording` / `onSegmentFinalized` / `onSegmentError`。
- `DeviceServer`：`route` 拆为 `routeGet` / `routePost`；`handleLive` 拆为 `admitViewer` / `writeStreamHead` / `pumpFrames` / `revoked` + 私有类 `LiveSession`（每条连接独立的帧序、空窗计数、令牌复核时刻、降级档位）。
- `DeviceListActivity`：`showPreview` 拆出 `createPreviewStreamer` / `prefetchSnapshot`；`pairWith` 拆出 `resolveSecurePort` / `applyPairResult`。
- `MjpegClient`：`readFrame` 拆出 `readFrameLength` / `drainHeaders` / `readExactly`。
- `MotionDetector`：`sampleBlocks` 拆出 `sampleGrid` / `averageBlock` / `updateChangedRatios`。
- `SoundDetector`：`loop` 拆出 `createRecorder` / `readLoop` / `decibelOf` / `emitIfDue`。
- `HumanDetector`：`detectBoxes` 拆出 `fillInput` / `fillUint8Input` / `fillFloatInput`。
- `H264Streamer`：`fillNv12` 拆出 `fillLuma` / `fillChroma`；`splitAnnexB` 拆出 `isStartCode` / `endOfNal` / `addNal`。
- `LiveStreamer`：`loop` 拆出 `handleOpenFailure` / `drainFrames` / `waitBeforeRetry`。
- `MainActivity`：`onCreate` 的“更多”菜单拆为 `showMoreMenu` / `onMoreMenuItem` / `startRecordOnlyEntry` / `startBlackScreenEntry`。
- `NetworkPath`：`overlayAddress` 拆出 `overlayAddressOf`。

## 三、验证结果

### 3.1 编译

```
> Task :app:compileDebugKotlin
BUILD SUCCESSFUL in 1m 21s
```

唯一告警：`H264Streamer.kt:226 'COLOR_FormatYUV420SemiPlanar' is deprecated`（平台常量弃用提示，未被 Sonar 记为问题）。

### 3.2 测试

| 测试类型 | 命令 | 结果 | 说明 |
|---|---|---|---|
| 单元测试（首次） | `gradle testDebugUnitTest` | `NO-SOURCE` + BUILD SUCCESSFUL | 当时仓库不存在 `app/src/test` |
| 单元测试（补测后） | `gradle testDebugUnitTest jacocoTestReport` | **BUILD SUCCESSFUL**，7 个测试类 / 48 用例 / 0 失败 / 0 跳过 | 覆盖 DeviceState、NetworkPath、PersonBoxes、AlertPolicy、GuardRuntime、EventType、TlsTrust |
| 覆盖率报告 | JaCoCo 0.8.12 → `jacocoTestReport.xml` | 行覆盖 125/7017 = **1.78%**，分支 116/3912 = 2.96% | 已配置 `sonar.coverage.jacoco.xmlReportPaths`，复扫被 Sonar 读取（`coverage=2.2%`） |
| 集成 / 仪器化测试 | `connectedDebugAndroidTest` | **未执行** | 仓库不存在 `app/src/androidTest`；`adb devices` 无连接设备 |
| release 静态检查 | `lintVitalRelease`（release 构建内置） | 通过 | 随生产构建一起执行，无致命项 |

> 如实说明：本节首次交付时仓库确实没有任何自动化测试资产；2026-11-14 晚已补 7 个测试类共 48 个 JVM 用例并全部通过（详见 `14-JaCoCo覆盖率与单元测试报告.md`）。覆盖率仍然很低（1.78%），未虚构或夸大。

### 3.3 生产构建

命令：`scripts\release.ps1 -NoUpload`（版本推进 → `clean` + `assembleRelease` + `bundleRelease` → `apksigner verify`）

```
BUILD SUCCESSFUL in 5m 27s
Signer #1 certificate DN: CN=QuietSentry, OU=Local, O=QuietSentry, L=Local, ST=Local, C=CN
Signer #1 certificate SHA-256 digest: 58465a53738b4634326c0508aec97b831d85693172c28427775b1d0f2ed40866
  OK  签名有效
```

产物（`release/`）：

| 文件 | 大小 | 说明 |
|---|---:|---|
| `QuietSentry-0.1.0-20261114.3-release.apk` | 34.03 MB | 已签名 release APK，apksigner 校验通过 |
| `QuietSentry-0.1.0-20261114.3.aab` | 15.98 MB | 已签名 release AAB，用于商店提交 |
| `build-info.json` | — | 版本、构建时间与产物清单 |

版本推进：`versionName=0.1.0-20261114.3`，`versionCode(buildNumber)=33`（由项目自带 `Step-BuildPatch` 写入 `package.json`）。

> 构建前已把原 `release/` 中的旧产物（0.1.0-20261114.1 APK/AAB、0.1.0-20261114.2 debug APK）备份到任务工作区 `sonar/release-before/`，因为该脚本会清空 `release/` 目录。

## 四、变更范围

- 源码：22 个 Kotlin 文件（均在 `app/src/main/java/com/quietsentry/app/`）。
- 配置：新增 `sonar-project.properties`（projectKey / sources / exclusions / `sonar.java.binaries`）。
- 版本：`package.json`（patch `20261114.3`，buildNumber 33）。
- 产物：`release/` 下新的 APK/AAB/build-info.json。

## 五、遗留风险与建议

1. **质量门未通过（new_coverage 0.2% < 80%）**
   已接入 JaCoCo（0.8.12）并在 `sonar-project.properties` 配置 `sonar.coverage.jacoco.xmlReportPaths`，复扫确认 Sonar 已读取报告（项目 `coverage=2.2%`、行覆盖 1.78%）。差距不是配置问题，而是可测面问题：7016 个可覆盖行里，CameraX/Service/Activity 依赖 Android 运行时，JVM 单测覆盖不到。要真正逼近 80%，只有引入 Robolectric 或仪器化测试，成本远高于纯逻辑类补测。
2. **测试覆盖结构不均**
   现有 48 个用例集中在 7 个纯逻辑类（DeviceState 100%、EventType 100%、TlsTrust 100%、GuardRuntime 95.8%、AlertPolicy 内部类 100%、PersonBoxes 75%、NetworkPath 32.4%）。`DeviceHttp`（134 行）、`MjpegClient`（71 行）、`PowerGuard`（27 行）等同为纯 JVM 可测但尚未覆盖，是下一步性价比最高的补测对象；相机/服务链路建议用 `tools/verify/` 下的脚本做真机回归。
3. **2 个 Security Hotspot 待人工复核**
   指标 `security_hotspots=2`；当前令牌访问 `/api/hotspots/*` 返回 403（`Insufficient privileges`），未能列出规则与位置。热点不等于漏洞，需在 Sonar 控制台逐条标记为 Safe/Fixed。
4. **扫描器残留目录**
   `sonar-scanner` 会在项目根生成 `.scannerwork/`（本次已清理）。JRE 缓存不在仓库内（位于用户目录），无需处理；若后续再扫仍会重新生成 `.scannerwork/`，可把它加入 `.gitignore`。
5. **构建脚本清空 release/**
   `release.ps1` 会删除 `release/` 内全部旧文件。若需要保留历史产物，发布前应先备份或改用归档目录。

## 六、结论

- 扫描维度（规范 / Bug / 安全漏洞 / 性能与复杂度）：**61 → 0，清零**。
- 修复未引入新问题：`new_violations=0`，重复行密度 0.4%。
- 编译与生产构建：**通过**，release APK/AAB 已签名并可校验。
- 质量门：**未通过**，`new_coverage` 由 0.0% 升至 0.2%，仍低于 80%；覆盖率链路已打通，剩余差距需 Robolectric/仪器化测试才能实质缩小。
