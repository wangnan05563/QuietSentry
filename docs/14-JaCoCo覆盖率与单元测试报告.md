# 14 · JaCoCo 覆盖率与纯逻辑类单元测试报告

- 日期：2026-11-14（UTC+8）
- 项目：QuietSentry（`:app`，纯 Kotlin，62 个 `.kt`，ncloc 10326）
- 目标：接入 JaCoCo 覆盖率并让 SonarQube 读取；为无 Android 依赖的纯 JVM 逻辑类补单元测试；复扫确认问题数仍为 0，观察质量门 `new_coverage` 是否转 OK
- 结论：**覆盖率链路已打通，48 个用例全部通过，问题仍为 0；质量门仍为 ERROR，`new_coverage` 由 0.0% 升至 0.2%，远未达到 80% 阈值。**

## 一、改动清单

### 1.1 `app/build.gradle.kts`

| 改动 | 内容 |
|---|---|
| 插件 | 新增 `jacoco`，`toolVersion = "0.8.12"` |
| 测试任务 | `tasks.withType<Test>` 配置 `JacocoTaskExtension`：`isIncludeNoLocationClasses = true`、`excludes = listOf("jdk.internal.*")` |
| 报告任务 | 注册 `jacocoTestReport`（依赖 `testDebugUnitTest`），输出 XML + HTML；类目录 `build/tmp/kotlin-classes/debug`，exec `jacoco/testDebugUnitTest.exec`；排除 `R`、`BuildConfig`、`Manifest`、`*Test*` |
| 单测环境 | `android.testOptions.unitTests.isReturnDefaultValues = true` |
| 依赖 | `testImplementation("junit:junit:4.13.2")` |

### 1.2 `sonar-project.properties`

```properties
sonar.coverage.jacoco.xmlReportPaths=app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml
```

### 1.3 测试源码

目录：`app/src/test/java/com/quietsentry/app/`（新建）

| 测试类 | 用例 | 覆盖对象与意图 |
|---|---:|---|
| `DeviceStateTest` | 14 | 状态优先级（离线 > 低电 > 过热 > 存储 > 值守 > 待机）、告警叠加、心跳仅在 guard 模式判超时、事件时间取本地与服务端较新者、电量边界 `in 1..20` |
| `NetworkPathTest` | 8 | `isPrivateAddress` / `isOverlayAddress` 的网段边界（10/8、172.16–31、192.168/16、100.64/10）与非法输入拒绝 |
| `PersonBoxesTest` | 5 | 人形框跨线程发布/读取、超时失效、空列表作废旧结果、多目标顺序保留 |
| `AlertPolicyTest` | 8 | 同源第 3 次升级紧急、声音事件不升级、免打扰与检测时段跨午夜判定与 `HH:mm` 格式化 |
| `GuardRuntimeTest` | 5 | 值守启动清零分片计数、心跳刷新、分片递增、复位清空运行态、降载标记 |
| `EventTypeTest` | 3 | `fromRaw` 已知值映射、未知/空值回退 MOTION、大小写敏感性、枚举自洽 |
| `TlsTrustTest` | 5 | 注册与按主机+端口查询、端口 0/空指纹拒绝注册、重复注册保留首指纹、不同端口隔离、未注册返回空 |

## 二、测试与覆盖率实测

命令：`gradle testDebugUnitTest jacocoTestReport`（JDK17，Gradle 8.9）

```
BUILD SUCCESSFUL in 11m 40s
25 actionable tasks: 3 executed, 22 up-to-date
```

| 指标 | 数值 |
|---|---|
| 测试类 / 用例 | 7 / **48** |
| 失败 / 错误 / 跳过 | 0 / 0 / 0 |
| 用例总耗时 | 约 2.4 s |
| 行覆盖 | **125 / 7017 = 1.78%** |
| 分支覆盖 | 116 / 3912 = 2.96% |

过程记录：首轮 48 用例中 1 条失败，原因是断言写成“电量 20 不算低电”，而源码判定是 `battery in 1..20`（含等号）。修正为 20→LOW_BATTERY、21→IDLE 后全部通过；未改动生产代码。

被覆盖类明细（JaCoCo XML）：

| 类 | 行覆盖 |
|---|---|
| `DeviceState` / `EventType` / `TlsTrust` | 100% |
| `AlertPolicy.DetectPlan` / `AlertPolicy.Quiet` | 100% |
| `GuardRuntime` | 95.8% |
| `PersonBoxes` | 75.0% |
| `NetworkPath` | 32.4%（仅纯字符串判定，`probe()` 需真实网络） |

## 三、SonarQube 复扫

- 扫描器：`sonar-scanner 8.0.1.6346`，CE 任务 `b76cce0a-28eb-4649-b1ac-c4fa39d879e4`，状态 **SUCCESS**
- 问题：`/api/issues/search?resolved=false` → **total = 0**
- 指标：`code_smells=0`、`bugs=0`、`vulnerabilities=0`、`coverage=2.2%`（行 1.78% + 分支 3.0%）、`lines_to_cover=7016`、`uncovered_lines=6891`、`ncloc=10326`、`duplicated_lines_density=0.4%`、`security_hotspots=2`

质量门（period `PREVIOUS_VERSION`，基线 2026-11-14T17:17:41+08:00）：

| 条件 | 状态 | 实际值 | 阈值 |
|---|---|---|---|
| new_coverage | **ERROR** | 0.2% | ≥ 80% |
| new_duplicated_lines_density | OK | 0.0% | ≤ 3% |
| new_violations | OK | 0 | ≤ 0 |

**`new_coverage` 未转 OK。** 结论如实记录：覆盖率从 0.0% 提升到 0.2%（整体 2.2%），但离 80% 仍差两个数量级。

## 四、差距分析（为什么不建议现在硬刷 80%）

1. **可测面天然受限**：7016 个可覆盖行中，`CaptureService`（465 行）、`DeviceServer`（621 行）、`DeviceListActivity`（650 行）、`SettingsActivity`（426 行）、`LiveViewActivity`（274 行）等直接依赖 CameraX、Android Service、View 体系，JVM 单测无法实例化。
2. **“新代码”口径**：`sonar.scm.disabled=true`，且 `sonar.projectVersion` 固定为 `0.1.0`，质量门以首次分析为基线比较。Sonar 无法按行归属变更，`new_coverage` 实际接近“全量代码覆盖率”，而不是“本次改动行覆盖率”。
3. **成本对比**：要实质性提升，需引入 Robolectric（Android 单元测试运行时）或 `androidTest` 仪器化测试（本机无连接设备）。这属于测试基础设施建设，不是本轮“纯逻辑类补测”的范围。

可选路径（需用户决策，未擅自执行）：

- **A. 调整门禁口径**：把 `new_coverage` 阈值按阶段下调（如 5%），或改为对 `coverage` 设阈值，先把门禁变绿，随补测逐步抬高。
- **B. 继续补纯 JVM 类**：`DeviceHttp`（134 行，需处理 `org.json` 桩）、`MjpegClient`（71 行，Socket 流）、`PowerGuard`（27 行）、`DetectionProfile`（30 行）、`Event`（18 行）；预计整体覆盖率可到 3%–4%，仍不足以过 80%。
- **C. 引入 Robolectric + 仪器化测试**：覆盖 Service/Activity 才有望接近阈值，工作量以人日计。
- **D. 启用 SCM**：`sonar.scm.disabled=false` 并提交一次基线 commit，让 `new_*` 指标真正按变更行计算；此后只要求“改动行覆盖 80%”，可执行性更强。

## 五、扫描器排障记录（本次卡点根因）

现象：`sonar-scanner` 在 `Starting SonarScanner Engine...` 后长时间无输出，项目根不生成 `.scannerwork`；首轮从启动到 `Communicating with SonarQube` 耗时约 24 分钟。

定位：对扫描器 JVM 执行 `jstack`，主线程停在

```
sun.security.mscapi.CKeyStore.loadKeysOrCertificateChains(Native Method)
  ... TrustManagerUtils.createTrustManagerWithSystemTrustedCertificates
  ... HttpClientFactory.configureSsl
```

即扫描器构建 HTTP 客户端时枚举 Windows 证书库（MSCAPI）卡死，与 SonarQube 服务、项目配置无关。

处置：杀掉 scanner 进程树后，追加启动参数

```
-Dsonar.scanner.skipJreProvisioning=true
-Dsonar.scanner.skipSystemTruststore=true
```

`Communicating` 由 24 分钟缩短到 25 秒，扫描正常完成（总耗时 17 分 18 秒，其中文本/密钥分析 67 秒、报告压缩 45 秒，属本机磁盘与 CPU 现状）。

## 六、生产构建回归

改动仅涉及测试依赖与 JaCoCo 插件，理论上不影响 release 产物；为避免“未验证”的结论，实跑 `gradle :app:assembleRelease`：

```
BUILD SUCCESSFUL in 4m 42s
50 actionable tasks: 4 executed, 46 up-to-date
```

`validateSigningRelease`、`lintVitalRelease` 均通过，产物 `app/build/outputs/apk/release/app-release.apk`（34.03 MB）与此前 `release/` 交付包一致。未重跑 `release.ps1`，避免无功能变更却推进版本号、清空已交付的 `release/` 目录。

## 七、结论

- JaCoCo 已接入并被 SonarQube 成功读取（`coverage=2.2%`），覆盖率数据链路可用。
- 新增 7 个测试类 48 个用例，全部通过；未修改任何生产代码逻辑。
- 复扫问题数仍为 **0**，`new_violations=0`，未因补测引入新问题。
- 质量门 `new_coverage` **仍为 ERROR**（0.2% < 80%）；这是测试基础设施缺口，不是配置错误，已给出四条可选路径供决策。
