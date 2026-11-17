# QuietSentry SonarQube 扫描报告（修复前基线）

- 项目：QuietSentry（`projectKey=QuietSentry`）
- 服务：本地 SonarQube Community Build 26.1.0.118079，`http://127.0.0.1:9000`
- 扫描器：sonar-scanner 8.0.1.6346（Windows x64）
- 分析范围：`app/src/main/java`，62 个 Kotlin 文件，分析完成 62/62
- 质量配置：Kotlin / Sonar way
- 分析时间：2026-11-14 17:17（UTC+8）
- CE 任务：`c0b70862-51fb-4c34-8af3-f23150739efb`（SUCCESS）
- 代码规模：ncloc 10,326；圈复杂度合计 1,962；认知复杂度合计 1,394

## 一、总体结果

| 指标 | 数值 |
|---|---|
| OPEN 问题总数 | **61** |
| 按严重级别 | CRITICAL 25 / MAJOR 20 / MINOR 16 |
| 按类型 | CODE_SMELL 52 / BUG 7 / VULNERABILITY 2 |
| 重复行密度 | 未参与门禁 |
| 质量门 | OK（首次分析尚无“新代码”基线，覆盖率条件未参与判定） |

## 二、按规则分布

| 规则 | 数量 | 含义 |
|---|---:|---|
| kotlin:S3776 | 16 | 认知复杂度超过 15 |
| kotlin:S6518 | 8 | 应使用索引访问替代函数调用 |
| kotlin:S899 | 7 | 忽略 `delete` 的布尔返回值 |
| kotlin:S1192 | 5 | 重复字符串字面量 |
| kotlin:S6516 | 4 | 显式函数式接口实现可改为 lambda |
| kotlin:S1874 | 4 | 使用已弃用 API（`URL(String)` 构造器） |
| kotlin:S1128 | 4 | 未使用的 import |
| kotlin:S6619 | 2 | 无效的非空判断 / 空安全访问 |
| kotlin:S4423 | 2 | 弱 TLS 协议（安全漏洞） |
| kotlin:S1186 | 2 | 空函数缺少说明 |
| kotlin:S107 | 1 | 函数参数过多（8 > 7） |
| kotlin:S108 | 1 | 空代码块 |
| kotlin:S1172 | 1 | 未使用的函数参数 |
| kotlin:S1481 | 1 | 未使用的局部变量 |
| kotlin:S1871 | 1 | 分支代码重复 |
| kotlin:S6510 | 1 | `when` 各分支的 `return` 应前置 |
| kotlin:S6532 | 1 | 可用 `check` 替代 if 抛异常 |

## 三、风险判断

1. **安全漏洞 2 条（CRITICAL）**：`TlsIdentity.kt` 与 `TlsTrust.kt` 使用 `SSLContext.getInstance("TLS")`，可能协商到 TLS 1.0/1.1，属于必须修复项。
2. **BUG 7 条（MINOR）**：全部为文件删除未检查返回值，长期运行会积累孤儿文件或静默丢失清理。
3. **认知复杂度 16 条（CRITICAL）**：集中在相机绑定、直播推送、HTTP 路由等核心链路，是维护风险与回归风险的主要来源。
4. 无 BLOCKER 级问题；无硬编码凭据类漏洞。

## 四、问题明细（61 条）

| 规则 | 级别 | 类型 | 文件:行 | 说明 |
|---|---|---|---|---|
| kotlin:S1186 | CRITICAL | CODE_SMELL | app/src/main/java/com/quietsentry/app/LiveStreamer.kt:35 | Add a nested comment explaining why this function is empty or complete the implementation. |
| kotlin:S1186 | CRITICAL | CODE_SMELL | app/src/main/java/com/quietsentry/app/LiveStreamer.kt:41 | Add a nested comment explaining why this function is empty or complete the implementation. |
| kotlin:S1192 | CRITICAL | CODE_SMELL | app/src/main/java/com/quietsentry/app/CaptureService.kt:444 | Define a constant instead of duplicating this literal ".pending" 3 times. |
| kotlin:S1192 | CRITICAL | CODE_SMELL | app/src/main/java/com/quietsentry/app/DeviceServer.kt:338 | Define a constant instead of duplicating this literal "image/jpeg" 3 times. |
| kotlin:S1192 | CRITICAL | CODE_SMELL | app/src/main/java/com/quietsentry/app/DeviceServer.kt:796 | Define a constant instead of duplicating this literal "video/mp4" 5 times. |
| kotlin:S1192 | CRITICAL | CODE_SMELL | app/src/main/java/com/quietsentry/app/DeviceServer.kt:796 | Define a constant instead of duplicating this literal "Accept-Ranges" 4 times. |
| kotlin:S1192 | CRITICAL | CODE_SMELL | app/src/main/java/com/quietsentry/app/PairingStore.kt:91 | Define a constant instead of duplicating this literal "SHA-256" 3 times. |
| kotlin:S3776 | CRITICAL | CODE_SMELL | app/src/main/java/com/quietsentry/app/CaptureService.kt:243 | Refactor this method to reduce its Cognitive Complexity from 23 to the 15 allowed. |
| kotlin:S3776 | CRITICAL | CODE_SMELL | app/src/main/java/com/quietsentry/app/CaptureService.kt:382 | Refactor this method to reduce its Cognitive Complexity from 17 to the 15 allowed. |
| kotlin:S3776 | CRITICAL | CODE_SMELL | app/src/main/java/com/quietsentry/app/CaptureService.kt:430 | Refactor this method to reduce its Cognitive Complexity from 22 to the 15 allowed. |
| kotlin:S3776 | CRITICAL | CODE_SMELL | app/src/main/java/com/quietsentry/app/DeviceListActivity.kt:404 | Refactor this method to reduce its Cognitive Complexity from 20 to the 15 allowed. |
| kotlin:S3776 | CRITICAL | CODE_SMELL | app/src/main/java/com/quietsentry/app/DeviceListActivity.kt:960 | Refactor this method to reduce its Cognitive Complexity from 28 to the 15 allowed. |
| kotlin:S3776 | CRITICAL | CODE_SMELL | app/src/main/java/com/quietsentry/app/DeviceServer.kt:174 | Refactor this method to reduce its Cognitive Complexity from 21 to the 15 allowed. |
| kotlin:S3776 | CRITICAL | CODE_SMELL | app/src/main/java/com/quietsentry/app/DeviceServer.kt:382 | Refactor this method to reduce its Cognitive Complexity from 24 to the 15 allowed. |
| kotlin:S3776 | CRITICAL | CODE_SMELL | app/src/main/java/com/quietsentry/app/H264Streamer.kt:258 | Refactor this method to reduce its Cognitive Complexity from 16 to the 15 allowed. |
| kotlin:S3776 | CRITICAL | CODE_SMELL | app/src/main/java/com/quietsentry/app/H264Streamer.kt:302 | Refactor this method to reduce its Cognitive Complexity from 21 to the 15 allowed. |
| kotlin:S3776 | CRITICAL | CODE_SMELL | app/src/main/java/com/quietsentry/app/HumanDetector.kt:57 | Refactor this method to reduce its Cognitive Complexity from 20 to the 15 allowed. |
| kotlin:S3776 | CRITICAL | CODE_SMELL | app/src/main/java/com/quietsentry/app/LiveStreamer.kt:83 | Refactor this method to reduce its Cognitive Complexity from 24 to the 15 allowed. |
| kotlin:S3776 | CRITICAL | CODE_SMELL | app/src/main/java/com/quietsentry/app/MainActivity.kt:124 | Refactor this method to reduce its Cognitive Complexity from 19 to the 15 allowed. |
| kotlin:S3776 | CRITICAL | CODE_SMELL | app/src/main/java/com/quietsentry/app/MjpegClient.kt:63 | Refactor this method to reduce its Cognitive Complexity from 31 to the 15 allowed. |
| kotlin:S3776 | CRITICAL | CODE_SMELL | app/src/main/java/com/quietsentry/app/MotionDetector.kt:96 | Refactor this method to reduce its Cognitive Complexity from 27 to the 15 allowed. |
| kotlin:S3776 | CRITICAL | CODE_SMELL | app/src/main/java/com/quietsentry/app/NetworkPath.kt:33 | Refactor this method to reduce its Cognitive Complexity from 18 to the 15 allowed. |
| kotlin:S3776 | CRITICAL | CODE_SMELL | app/src/main/java/com/quietsentry/app/SoundDetector.kt:56 | Refactor this method to reduce its Cognitive Complexity from 17 to the 15 allowed. |
| kotlin:S4423 | CRITICAL | VULNERABILITY | app/src/main/java/com/quietsentry/app/TlsIdentity.kt:71 | Change this code to use a stronger protocol. |
| kotlin:S4423 | CRITICAL | VULNERABILITY | app/src/main/java/com/quietsentry/app/TlsTrust.kt:51 | Change this code to use a stronger protocol. |
| kotlin:S107 | MAJOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/DeviceHttp.kt:240 | This function has 8 parameters, which is greater than the 7 authorized. |
| kotlin:S108 | MAJOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/DeviceServer.kt:99 | Either remove or fill this block of code. |
| kotlin:S1172 | MAJOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/DeviceServer.kt:209 | Remove this unused function parameter "req". |
| kotlin:S1871 | MAJOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/SettingsActivity.kt:624 | This branch's code block is the same as the block for the branch on line 613. |
| kotlin:S6510 | MAJOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/DeviceServer.kt:694 | Move "return" statements from all branches before "when" statement. |
| kotlin:S6516 | MAJOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/DeviceListActivity.kt:105 | Replace explicit functional interface implementation with lambda expression. |
| kotlin:S6516 | MAJOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/EventPlayerActivity.kt:67 | Replace explicit functional interface implementation with lambda expression. |
| kotlin:S6516 | MAJOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/LiveViewActivity.kt:64 | Replace explicit functional interface implementation with lambda expression. |
| kotlin:S6516 | MAJOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/MainActivity.kt:90 | Replace explicit functional interface implementation with lambda expression. |
| kotlin:S6518 | MAJOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/Deterrent.kt:112 | Replace function call with indexed accessor. |
| kotlin:S6518 | MAJOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/H264Streamer.kt:294 | Replace function call with indexed accessor. |
| kotlin:S6518 | MAJOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/OnvifService.kt:409 | Replace function call with indexed accessor. |
| kotlin:S6518 | MAJOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/OnvifService.kt:410 | Replace function call with indexed accessor. |
| kotlin:S6518 | MAJOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/OnvifService.kt:411 | Replace function call with indexed accessor. |
| kotlin:S6518 | MAJOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/OnvifService.kt:412 | Replace function call with indexed accessor. |
| kotlin:S6518 | MAJOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/OnvifService.kt:413 | Replace function call with indexed accessor. |
| kotlin:S6518 | MAJOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/OnvifService.kt:414 | Replace function call with indexed accessor. |
| kotlin:S6532 | MAJOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/UpdateChecker.kt:100 | Replace this if expression with check(!(connection.responseCode !in 200..299)) { "HTTP ${connection.responseCode}" }. |
| kotlin:S6619 | MAJOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/DeviceHttp.kt:264 | Remove this useless non-null check, it always succeeds. |
| kotlin:S6619 | MAJOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/DeviceListActivity.kt:998 | Remove this useless null-safe access ?., it always succeeds. |
| kotlin:S1128 | MINOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/Deterrent.kt:3 | Remove this unused import. |
| kotlin:S1128 | MINOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/DeviceListActivity.kt:3 | Remove this unused import. |
| kotlin:S1128 | MINOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/DeviceListActivity.kt:17 | Remove this unused import. |
| kotlin:S1128 | MINOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/DeviceListActivity.kt:24 | Remove this unused import. |
| kotlin:S1481 | MINOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/DeviceServer.kt:276 | Remove this unused "grant" local variable. |
| kotlin:S1874 | MINOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/DeviceHttp.kt:171 | Deprecated code should not be used. |
| kotlin:S1874 | MINOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/DeviceHttp.kt:252 | Deprecated code should not be used. |
| kotlin:S1874 | MINOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/MjpegClient.kt:40 | Deprecated code should not be used. |
| kotlin:S1874 | MINOR | CODE_SMELL | app/src/main/java/com/quietsentry/app/UpdateChecker.kt:92 | Deprecated code should not be used. |
| kotlin:S899 | MINOR | BUG | app/src/main/java/com/quietsentry/app/CaptureService.kt:457 | Do something with the "Boolean" value returned by "delete". |
| kotlin:S899 | MINOR | BUG | app/src/main/java/com/quietsentry/app/CaptureService.kt:472 | Do something with the "Boolean" value returned by "delete". |
| kotlin:S899 | MINOR | BUG | app/src/main/java/com/quietsentry/app/CaptureService.kt:494 | Do something with the "Boolean" value returned by "delete". |
| kotlin:S899 | MINOR | BUG | app/src/main/java/com/quietsentry/app/EventStore.kt:213 | Do something with the "Boolean" value returned by "delete". |
| kotlin:S899 | MINOR | BUG | app/src/main/java/com/quietsentry/app/EventStore.kt:214 | Do something with the "Boolean" value returned by "delete". |
| kotlin:S899 | MINOR | BUG | app/src/main/java/com/quietsentry/app/EventStore.kt:233 | Do something with the "Boolean" value returned by "delete". |
| kotlin:S899 | MINOR | BUG | app/src/main/java/com/quietsentry/app/EventStore.kt:234 | Do something with the "Boolean" value returned by "delete". |

## 五、原始数据

- 扫描日志：`scan-baseline.log`
- 问题原始 JSON（61 条）：`baseline-issues.json`
- 精简清单（key|severity|type|rule|file:line|message）：`baseline-issues.txt`

> 该三份原始文件保存在本次任务的工作区 `sonar/` 目录中，未复制进仓库，避免把工具产物混入源码目录。
