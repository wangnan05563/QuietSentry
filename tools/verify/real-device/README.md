# QuietSentry 真机矩阵与 7 天稳定性执行包

本目录把 `docs/05-兼容性与稳定性验证矩阵.md` 里仍需真机执行的门禁，固化成可复跑的脚本与证据格式。

**现状先说清楚**：仓库实际执行过模拟器 `emulator-5554` 的 `--arm` PASS，以及 D3/D4/D7 的工具自测（D7 2 轮重启）；真实三星 SM-G9980（Android 15，`R5CR716VVEM`）的 `--arm` 只有历史记录，原 `E:\QuietSentryVerifyLive` 证据目录已随本机重启被清理，需重跑。真机 D3/D4/D7 注入、8 品牌矩阵与 7 天连续稳定性**仍未执行**。本目录提供的是执行工具和记录模板，不是门禁通过证明，模拟器跑出的 PASS 也不能替代真机。

当前待测包为 `release\QuietSentry-0.1.0-release.apk`（35,767,573 bytes，versionCode 76，versionName `0.1.0-20261123.2`；`build-info.json` 里的 versionName `0.1.0` 只是基线号，Gradle 会把 `package.json` 的 patch 拼进去）。真机执行时请以这个包为准；`release/` 已存在，重新构建后文件名不变时请以 `build-info.json` 的时间为准。

执行包必须与脚本同目录带 `ui_walk.py`，并保留 `scripts/build-lib.ps1`；不要只复制三个入口脚本。仓库提供 `scripts/package-real-device-kit.ps1 -Apk <apk>`，会把 `ui_walk.py`、`build-lib.ps1`、本文档和 APK 一起打包，并在打包时做 `import ui_walk` 与 PowerShell 语法自检。

当前执行包由 `package-real-device-kit.ps1` 按时间戳生成，打包结束会打印 ZIP 路径与 SHA256，交付时以那次输出为准。上一版 `20261124-230226` 早于 `ui_walk.open_app` 显式 `am start` 修复，不要再用。

---

## 1. 工具一：单台真机基线冒烟

`tools/verify/real_device_smoke.py` 做一台机器上可自动化的那部分：

1. 安装 release APK（可选）并清空旧数据；
2. 走完合规三页、引导页、系统权限；
3. 开始值守，确认前台服务与“值守中”通知；
4. 锁屏跑满 `--duration-min`（矩阵要求 5 分钟），每 30 秒采 pid / 服务 / 通知 / 电量 / 温度 / 屏幕状态；
5. 停止值守，确认保存提示、通知清理与相册导出；
6. 进设置页抓镜头摘要并尝试一键诊断；
7. 保存截图、UI dump、logcat、`samples.csv` 和 `SUMMARY.md`。

### 单台

```powershell
python tools/verify/real_device_smoke.py `
  --serial <adb序列号> `
  --apk release\QuietSentry-0.1.0-release.apk `
  --label "小米/Redmi Note" `
  --expect-version 0.1.0-20261123.2 `
  --fresh
```

- 默认 `--duration-min 5`，默认锁屏；只做工具自测时才加 `--no-screen-off`。
- `--expect-version` 在 `collect_info` 后校验设备上的 `versionName`；不一致直接按 FAIL 处理。`--skip-install` 时尤其应带上，避免拿旧包填证据。
- `--fresh` 会 `pm clear`，走首次启动合规流程；不想清数据就去掉。
- 设置页“一键诊断”在 720×1280 小屏上可能被底部导航条裁掉一截，抓不到就加 `--no-diagnostics`，镜头摘要仍会尽量抓；诊断日志可在真机上手动导出。
- 退出码：全部 PASS 为 0；出现 PARTIAL/FAIL 为 1。

### 多台批量

```powershell
python tools/verify/real_device_smoke.py `
  --all `
  --apk release\QuietSentry-0.1.0-release.apk
```

`--all` 默认跳过 `emulator-*`；只对模拟器自测时加 `--include-emulators`。多台跑完会在输出根目录生成：

- `matrix.csv`：序列号、厂商、型号、Android、结论、值守/通知、进程中断、服务非运行、崩溃行、证据目录；
- `matrix.md`：同一份结果的阅读版。

### 证据目录

默认在 `verify-output/real-device/<serial>/<时间戳>/`：

```text
SUMMARY.md               # 单台结论与检查项
summary.json             # 机器可读结果
samples.csv              # 锁屏采样
screenshots/             # guard-running / stop-dialog / stop-after / settings / settings-diagnostics
ui/                      # uiautomator dump
logs/                    # logcat、dumpsys activity services、notification、media.camera、相册查询
```

`verify-output/` 已在 `.gitignore` 里，证据留在本机，不随仓库提交。

---

## 2. 工具二：7 天连续采样

`tools/verify/stability-7d.ps1` 是外部 ADB 轮询，不依赖 App 内埋点；进程死了也能留下 `pid=0` 的记录。

```powershell
# 正式跑 D1：7 天，每 10 分钟一个点
pwsh -ExecutionPolicy Bypass -File tools/verify/stability-7d.ps1 -Serial <serial>

# 冒烟自测：只采 2 个点
pwsh -ExecutionPolicy Bypass -File tools/verify/stability-7d.ps1 -Serial <serial> -MaxSamples 2 -IntervalSec 5
```

CSV 字段：

```text
time,pid,pss_kb,battery_pct,battery_temp_c,thermal_status,clip_files,clip_source,thumb_files,crash_lines,service_running,adb_errors
```

`adb_errors` 是本次采样中 ADB 超时/错误的计数；大于 0 时摘要会判失败，不能把“读不到设备”当成服务正常或崩溃 0。

release 包不是 debuggable，`run-as` 会被系统拒绝；脚本已自动回退到 `/sdcard/Movies/QuietSentry` 的相册导出计数，`clip_source` 会写 `exports`。debug 包仍走私有目录，写 `private`。

注意：这个脚本只负责**连续采样**，不负责 D2 断电、D3 强杀、D4 网络抖动、D5 边充边录、D6 存储压力、D7 重启这些注入动作；注入按下一节手工做，采样脚本全程开着。

---

## 2.1 注入助手（D3 / D4 / D7）

`tools/verify/soak-inject.ps1` 负责可自动化的注入和逐次留档：

```powershell
# D3 强杀：默认前 3 次服务不在时自动 --arm 重开；第 3 次后不再自动打开 App，
# 后续轮次记为 MANUAL_REQUIRED，由人工按 30 分钟间隔重开
pwsh -File tools/verify/soak-inject.ps1 -Step D3 -Serial <serial> -Apk <apk> -ExpectVersion <version>

# D3 只做强杀、不自动重开（开始前必须已值守运行）
pwsh -File tools/verify/soak-inject.ps1 -Step D3 -Serial <serial> -NoAutoArm

# D4 网络抖动：初始 Wi-Fi 必须开启；只切 Wi-Fi，结束按原状态恢复
pwsh -File tools/verify/soak-inject.ps1 -Step D4 -Serial <serial> -Count 10 -Apk <apk> -ExpectVersion <version>

# D7 重启 ×5：先 --arm，再显式 -Force；开始前值守未运行会直接 exit 2
pwsh -File tools/verify/soak-inject.ps1 -Step D7 -Serial <serial> -Force

# 只打印计划，不执行强杀/切网/重启
pwsh -File tools/verify/soak-inject.ps1 -Step D7 -Serial <serial> -DryRun
```

- `--arm` 的底层调用是 `python tools/verify/real_device_smoke.py --serial <serial> --skip-install --arm`，只启动值守、不采样不停止；`arm.json` 留在 `verify-output/soak/arm/...`。传入 `-ExpectVersion` 时会追加 `--expect-version`，跳过安装也必须校验版本；`--skip-install` 未给 `--expect-version` 时直接 `exit 2`，避免拿旧包填证据。
- `--arm` 默认 420 秒超时（`-ArmTimeoutSec` 可调）。D3/D4 若首轮 arm 失败会直接 `exit 2`，不把“没武装起来”误记成强杀/网络抖动结果。
- D3 的 `AutoArmMax` 默认 3；第 3 次之后服务未运行时会记 `MANUAL_REQUIRED`，脚本会以退出码 1 结束，提示本轮“未完整执行”，不再给假 PASS。若确实要跑满 5 次，需要人工在第 4/5 次之间重开 App，或显式调整 `-AutoArmMax`。
- 若设备未接电源且 App 开着“仅充电时值守”，点“开始值守”会弹“未连接电源”确认框。`start_guard` 会自动点“仍然开始”，并在 `notes` 记录“限时 2 小时”；正式 D1/D3/D5 仍应接电源，避免 2 小时自动停止。
- `-NoAutoArm` 仍按 `-Count` 跑满轮次：每轮开始前若服务未运行，会记 `MANUAL_REQUIRED` 并按 `-IntervalMin` 等待人工重开；只有开始前未运行才直接 `exit 2`。若最终仍有 `MANUAL_REQUIRED`，退出码为 1。
- D3 CSV 会记录 `before-kill` / `force-stop` 的 pid、服务、通知；`STILL_RUNNING` 表示强杀后进程仍在，`ADB_ERROR` 表示采样本身失败，两者都不能当成 `KILLED`。
- D4 初始 Wi-Fi 关闭时直接 `exit 2`；每次 disable/enable 后都会回读状态，`WIFI_TOGGLE_FAILED` 单独记录；结束后恢复失败会写 `Wi-Fi 恢复失败` 并失败退出，避免把设备留在断网。
- D4 CSV 的 `OFFLINE_TOLERATED` 只说明服务与通知还在，不代表观看端离线摘要符合预期；离线摘要仍需按 docs/05 人工看。
- D7 开始前必须已值守，`service=False` 时直接 `exit 2`，避免“未值守也能过 D7”的假 PASS。重启后 `NO_AUTOSTART` 表示 `CaptureService` 未运行；`BootReceiver` 可能启动进程并发「设备已重启」通知，因此进程存在、通知存在但 service=false **不算** `AUTOSTARTED`；只有采集服务真正运行才判 `AUTOSTARTED`。重启前在值守时，通知最多等 60+120 秒，仍缺失则记 `NOTIFY_MISSING` 并失败。重启判定要求 `boot_id` 变化且 `sys.boot_completed=1`，`ADB_ERROR` 不会被当成 `NO_AUTOSTART`。
- D2 断电、D5 边充边录、D6 存储压力涉及外部电源/散热/填盘，**不在脚本里伪装自动化**，仍按 `docs/05` §4 手工执行。

**工具自测记录（模拟器，不是真机门禁；旧 E 盘证据已随本机重启清理，当前以 `verify-output` 为准）**：

- 2026-11-24 22:25 在 `emulator-5554` 复跑 `--arm`：`ARM PASS (guardStarted=True, foregroundService=True, fgsNotification=True)`；此前全 false 的原因是“未连接电源”确认框未处理，不是服务未启动。证据在 `E:\QuietSentryVerifyLive\arm\emulator-5554\20261124-221933`。
- 当前已验证：`ui_walk` 导入自检通过；`stability-7d.ps1 -MaxSamples 1 -IntervalSec 1` 可正常采样，首点未启动不再被误计为“进程中断”；`soak-inject.ps1 -Step D7 -DryRun` 通过。
- 2026-11-25 00:05–00:12 在 `emulator-5554` 跑 `soak-inject.ps1 -Step D3 -Count 2 -IntervalMin 1 -AutoArmMax 2`：2 次 `force-stop` 均为 `KILLED`，重新武装成功，ADB 失败 0，退出码 0。证据 `E:\QuietSentryVerifyLive\emulator-inject\inject-D3-20261125-000535.csv`。这是工具自测，不是真机 D3 门禁。
- 2026-11-25 00:27 / 00:55 模拟器 D4 自测：arm 冷启动时出现系统 ANR（`静哨 QuietSentry isn't responding`），`prepare_ui` 现已识别 ANR 窗口并让 arm FAIL、D4 `exit 2`，没有产出假网络抖动结果。重启模拟器并让 App 正常进入首页后，01:37 补跑 D4 `-Count 2`：2 次 Wi-Fi 关闭/恢复期间 pid、服务、通知都保持，Wi-Fi 恢复成功，ADB 失败 0，退出码 0；证据 `E:\QuietSentryVerifyLive\emulator-inject\inject-D4-20261125-013733.csv`。真实设备 D4 仍待 USB 恢复后执行。
- 修复前的 D4 实际注入未取得有效结果；当前 D4 的初始 Wi-Fi 检查、回读校验和结束恢复已改为硬失败，仍需真机复测。
- 2026-11-25 13:32 首次加入“恢复通知必须存在”校验时，60 秒采样两次 `NOTIFY_MISSING`；检查发现通知实际晚于 60 秒到达。D7 已改为 60 秒后再最多轮询 120 秒。13:43–13:51 最终重跑：两次重启 `pid=3698/3867`、`service=False`、`notification=True`，均 `NO_AUTOSTART`，自动拉起 0、恢复提示缺失 0、ADB 失败 0、退出码 0；证据 `verify-output\d7-final\run2\inject-D7-20261125-134333.csv`。这是工具自测，真机正式 D7 5 次仍待执行。
- 2026-11-25 13:01–13:08 同一重建包补跑 D3 `-Count 2 -AutoArmMax 2` 与 D4 `-Count 2`：D3 两次均 `KILLED`、ADB 失败 0；D4 两次 Wi-Fi 关闭/恢复期间 pid=7415、服务与通知保持，恢复成功，退出码 0。证据 `verify-output\d3-current2\inject-D3-20261125-130100.csv`、`verify-output\d4-current\inject-D4-20261125-130420.csv`。

**真实设备记录（2026-11-24）**：

- 23:43–23:46 在 Samsung `SM-G9980` / `R5CR716VVEM`（Android 15）用当前 release 包（debug 重签覆盖安装，`0.1.0-20261123.2` / vc76）跑 `--arm`：三项全 true，`ARM PASS`（历史记录）；原 `E:\QuietSentryVerifyLive\real-arm3` 证据在 2026-11-25 本机重启后被清理，当前无法复核，需重跑。
- 23:48 设备 USB 掉线（`adb devices` 不再出现 `R5CR716VVEM`），D3 在初始采样阶段被 `ADB_ERROR` 拦下并 `exit 2`，没有产出假的强杀结果。重新插线/授权后需从 `--arm` 重新开始，D3/D4/D7 仍未执行。
- 设备上旧包是 debug 签名，发布 APK 直接覆盖会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；本轮已重签 `verify-output\QuietSentry-0.1.0-device-debugsign.apk`（debug 证书 `4c3a36...`），用 `adb install -r` 保留旧数据覆盖；发布签名 APK 仍只用于 Play 交付，不用于覆盖这台旧 debug 包。
- 同一轮掉线暴露并已修复：`real_device_smoke.py --skip-install` 设备离线时误报“未安装”，以及 `soak-inject.ps1` / `stability-7d.ps1` 只识别 `ADB_TIMEOUT`、不识别 `device not found` 的问题；现在离线会明确报“设备不可用/ADB 失败”。

---

## 3. 八品牌矩阵记录表

每台按 `docs/05` §6 填 **PASS / FAIL / N/A**；FAIL 必须附 logcat 片段与复现步骤。能力不存在填 N/A，不能当 PASS。

| # | 品牌 / ROM | 机型 | Android | 无后置镜头降级 | 录制档位回退 | 相机被占用重绑 | 熄屏 5 分钟 | 强杀恢复提示 | 白名单引导 | 结论 | 证据目录 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 小米 / HyperOS·MIUI |  |  |  |  |  |  |  |  |  |  |
| 2 | OPPO / ColorOS |  |  |  |  |  |  |  |  |  |  |
| 3 | vivo / OriginOS·Funtouch |  |  |  |  |  |  |  |  |  |  |
| 4 | 华为 / HarmonyOS·EMUI |  |  |  |  |  |  |  |  |  |  |
| 5 | 荣耀 / MagicOS |  |  |  |  |  |  |  |  |  |  |
| 6 | 三星 / One UI |  |  |  |  |  |  |  |  |  |  |
| 7 | 一加 / OxygenOS |  |  |  |  |  |  |  |  |  |  |
| 8 | Google / Pixel 原生 |  |  |  |  |  |  |  |  |  |  |

### 六个必测项怎么做

| 项 | 操作 | 通过判定 |
| --- | --- | --- |
| 无后置镜头降级 | 找只有前置镜头/无后置的设备，或禁用后置相机后启动值守 | 自动选前置或任意镜头；通知写明“本机无后置镜头”；仍能录 |
| 录制档位回退 | 看设置页“镜头摘要”的最大录制尺寸；在 ≤480p / 720p 设备上开始值守 | FHD→HD→SD 有序回退，不因申请 1080p 直接失败 |
| 相机被占用重绑 | 先用系统相机/其他相机 App 占住镜头，再在 QuietSentry 开始值守；随后释放 | 通知“等待释放”，释放后自动重绑并继续出片；不静默停在“采集中” |
| 熄屏 5 分钟 | `real_device_smoke.py` 默认锁屏跑 5 分钟 | 5 个采样点均 Asleep；pid 中断 0、服务非运行 0、通知一直在 |
| 强杀恢复提示 | 值守中 `adb shell am force-stop com.quietsentry.app`，间隔 ≥30 分钟做 5 次；第 3 次后不打开 App | 第 3 次起出现“反复被中断”建议；回到 App 可一键恢复；历史分片可播放 |
| 白名单引导 | 按 ROM 路径进设置（自启动/后台高耗电/电池优化），确认 App 内有对应引导文案 | 引导能到达正确系统页；用户操作后值守不被杀 |

前两项如果设备本身有后置且最大录制 ≥1080p，应填 **N/A（能力不存在/未触发）**，在备注写明，而不是 PASS。

---

## 4. 七天稳定性执行清单

采样脚本全程开着，注入按天做。每天结束后检查当日全部分片可解码、索引与事件可回看；出现“通知显示值守中但观看端无画面”即整体 FAIL。

| 天 | 注入 | 怎么执行 | 通过判定 |
| --- | --- | --- | --- |
| D1 | 基线 24h | 接电源、锁屏，`stability-7d.ps1` 每 10 分钟采一个点 | 分片连续；温度 ≤45°C 或降载而非停采；心跳 ≤90s |
| D2 | 断电 ×3 | 值守中直接拔电源/长按关机，重启后打开 App | 每次最多损失 1 个进行中的分片；`.pending` 被清理、坏片改 `orphan_`；不自动拉起相机 |
| D3 | 强杀 ×5 | `adb shell am force-stop com.quietsentry.app`，间隔 ≥30 分钟；第 3 次后不打开 App | 第 3 次起出现“反复被中断”建议；返回后一键恢复；历史分片可播放 |
| D4 | 网络抖动 ×10 | 关 Wi-Fi / 切飞行 / 换热点，各 10 次 | 本地录制不中断；恢复后离线摘要只推 1 条 |
| D5 | 边充边录 8h | 接电源、覆盖散热差的放置，持续 8h | 45°C 触发降载文案；50°C 暂停并说明；不静默停止 |
| D6 | 存储压力 | 预填存储至可用 <1GB，值守 4h | 按保留策略清理；始终保留最近 24h；录制不中断 |
| D7 | 重启 ×5 + 总检 | 每次重启后走恢复流程，最后全量回看 | `BootReceiver` 只发“设备已重启”提示；全部分片可解码；无 ANR/崩溃 |

**判定指标：**

- 文件可打开率 ≥99%：逐片 `MediaMetadataRetriever` 校验，失败数/总片数；
- 分片时长 30s±3s：读每片 `METADATA_KEY_DURATION`；
- 心跳 ≤90s：观看端 `DeviceHttp.Status.heartbeatAt` 与服务端 `serverTime` 差；
- 重启恢复提示 ≤1 分钟；
- 崩溃/ANR = 0：`adb logcat` 过滤 `FATAL`/`ANR`。

---

## 5. 结论口径与边界

- 单台 `SUMMARY.md` 的 PASS 只代表**基线链路**通过；六个必测项里手工分支没填完，不能把整机写成 PASS。
- 模拟器跑通只用于验证脚本本身，不能写进 8 品牌矩阵。
- 诊断页抓不到时，镜头摘要仍可从设置页读取；一键诊断日志按 `docs/05` §3 手工“设置 → 一键诊断 → 导出诊断日志”补。
- 最终判定、FAIL 证据格式、N/A 口径以 `docs/05-兼容性与稳定性验证矩阵.md` 为准。


