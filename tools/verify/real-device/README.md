# QuietSentry 真机矩阵与 7 天稳定性执行包

本目录把 `docs/05-兼容性与稳定性验证矩阵.md` 里仍需真机执行的门禁，固化成可复跑的脚本与证据格式。

**现状先说清楚**：仓库目前只实际执行过 1 台三星 SM-G9980（Android 15）的 4 分钟稳定性；8 品牌矩阵与 7 天连续稳定性**仍未执行**。本目录提供的是执行工具和记录模板，不是门禁通过证明，模拟器跑出的 PASS 也不能替代真机。

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
  --apk E:\QuietSentryBuild\release\QuietSentry-0.1.0-20261123.2-release.apk `
  --label "小米/Redmi Note" `
  --fresh
```

- 默认 `--duration-min 5`，默认锁屏；只做工具自测时才加 `--no-screen-off`。
- `--fresh` 会 `pm clear`，走首次启动合规流程；不想清数据就去掉。
- 设置页“一键诊断”在 720×1280 小屏上可能被底部导航条裁掉一截，抓不到就加 `--no-diagnostics`，镜头摘要仍会尽量抓；诊断日志可在真机上手动导出。
- 退出码：全部 PASS 为 0；出现 PARTIAL/FAIL 为 1。

### 多台批量

```powershell
python tools/verify/real_device_smoke.py `
  --all `
  --apk E:\QuietSentryBuild\release\QuietSentry-0.1.0-20261123.2-release.apk
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
powershell -ExecutionPolicy Bypass -File tools/verify/stability-7d.ps1 -Serial <serial>

# 冒烟自测：只采 2 个点
powershell -ExecutionPolicy Bypass -File tools/verify/stability-7d.ps1 -Serial <serial> -MaxSamples 2 -IntervalSec 5
```

CSV 字段：

```text
time,pid,pss_kb,battery_pct,battery_temp_c,thermal_status,clip_files,clip_source,thumb_files,crash_lines,service_running
```

release 包不是 debuggable，`run-as` 会被系统拒绝；脚本已自动回退到 `/sdcard/Movies/QuietSentry` 的相册导出计数，`clip_source` 会写 `exports`。debug 包仍走私有目录，写 `private`。

注意：这个脚本只负责**连续采样**，不负责 D2 断电、D3 强杀、D4 网络抖动、D5 边充边录、D6 存储压力、D7 重启这些注入动作；注入按下一节手工做，采样脚本全程开着。

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
