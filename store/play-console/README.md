# Play Console 提交包（0.1.0-20261123.2 / versionCode 76）

本目录给出可直接照抄到 Play Console 的字段、顺序与审核材料。**建议先走 Internal testing；Production 等真机 8 品牌矩阵与 7 天稳定性完成后再提审**（见 `docs/17` §6）。

## 1 构建与签名

| 项 | 值 |
|---|---|
| 包名 | `com.quietsentry.app` |
| versionName | `0.1.0-20261123.2` |
| versionCode | `76` |
| AAB | `E:\QuietSentryBuild\release\QuietSentry-0.1.0-20261123.2.aab`，16,758,037 bytes，SHA256 `B504FC37D6953FE7C751E1108AEE750557C83CF0D71B64FD0C603E8B86253C75` |
| APK（侧载/内测备用） | `E:\QuietSentryBuild\release\QuietSentry-0.1.0-20261123.2-release.apk`，35,767,573 bytes，SHA256 `962450D82F13B2DA5D000F19BC2A9713BDAB407AB770FAB62FDA286F84888FB3` |
| 签名 | APK Signature Scheme v2；证书 `CN=QuietSentry`，SHA-256 `58465a53738b4634326c0508aec97b831d85693172c28427775b1d0f2ed40866` |
| 构建信息 | `E:\QuietSentryBuild\release\build-info.json`（builtAt 2026-11-23 01:40:50） |

> 构建副本标记 `dirty=true`，但代码门禁、签名、冒烟证据见 `docs/17`。如需“干净可复现”构建，应在干净工作树重新执行 `scripts\build.ps1 -Variant release` 并更新本节 SHA。

## 2 商店资料（App details）

| 字段 | 填写 |
|---|---|
| App name | `静哨 QuietSentry` |
| 默认语言 | 简体中文（zh-CN）；可另加 en-US |
| 应用类别 | House & Home（家居） |
| 标签 | Home security、Camera、Local storage |
| 开发者联系邮箱 | **填当前 Play 开发者账号邮箱**（仓库未内置邮箱；隐私页用 GitHub Issues 作为公开联系入口） |
| 网站（可选） | `https://github.com/wangnan05563/QuietSentry` |
| 隐私政策 | `https://4mjujuuzy196r.doubao.link/app/app_17jm1yrg5ue` |

短描述（≤80 字符，直接复制）：

> 把闲置 Android 设备变成本地值守摄像头：熄屏可录，数据不出家门。

完整描述（≤4000 字符，直接复制）：

> **把闲置手机变成一台值守摄像头**
>
> 家里的旧手机、旧平板放着也是放着。静哨让它在屏幕熄灭的情况下继续记录，画面保存在设备本地，不经过任何服务器。
>
> · 值守模式：屏幕熄灭仍持续录制，30 秒分片保存，进程被系统回收也只损失最后一片
> · 黑屏记录：屏幕全黑不显示取景画面，单击拍照、双击切换前后镜头
> · 本地优先：无账号、无订阅，画面默认不出设备
> · 状态透明：采集期间始终显示常驻通知，可随时停止
>
> **请仅在你本人拥有或已获授权的场所使用。** 在他人不知情的情况下拍摄他人生活空间，在多数地区属于违法行为。本应用会在采集期间持续显示通知，且无法关闭。
>
> 目前版本已提供实时观看、配对授权、家庭成员共享（含限时访客）与事件回看。

商店图片与视频：

| 类型 | 文件 |
|---|---|
| 应用图标 512×512 | `store/icon-512.png` |
| 特色图片 1024×500 | `store/feature-graphic-1024x500.png` |
| 手机截图 5 张 | `store/screenshots/phone/01-live-view.png` ~ `05-settings.png` |
| 平板截图 2 张 | `store/screenshots/tablet/01-settings.png`、`02-remote-access.png` |
| camera FGS 演示视频 | `store/fgs-demo/QuietSentry-FGS-Demo-0.1.0-20261123.2.mp4`（34.2 秒，1080×1920，无音轨） |
| 视频封面（备用） | `store/fgs-demo/poster.png` |

## 3 App content 声明

| 页面 | 选择/填写 |
|---|---|
| Privacy policy | 填上面的 HTTPS URL |
| App access | 无需登录；审核说明见下方 §4 |
| Ads | 无广告 |
| Content rating | 按问卷如实选“无”：无暴力、血腥、性内容、裸露、粗俗语言、受控物质、赌博；无公开用户生成内容或社交信息流。目标受众 18+。 |
| Target audience | 18 岁及以上；不面向儿童，不参与 Designed for Families |
| News app | 否 |
| COVID-19 接触追踪 | 否 |
| Data safety | 不收集、不共享任何用户数据；无账号、无分析/广告 SDK、无服务端；视频与事件仅存本机。详见 `docs/04` §4 与隐私政策。 |
| Government app | 否 |
| Financial features | 否 |
| Health apps | 否 |
| Photo/video permissions | 不申请 `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / 全文件访问；仅使用 `CAMERA` 采集，并通过 MediaStore 保存到 `Movies/QuietSentry`。如表单要求说明，按此填写。 |
| Foreground service permissions | 见 §5；选择 camera + microphone（以及 standby 用的 dataSync/specialUse，如表单分项） |
| 其他敏感权限 | 不申请位置、通讯录、电话、短信、已安装应用列表、无障碍、VPN、精确闹钟 |

## 4 App access / 审核步骤

审核无需账号。安装后：

1. 首次启动完成三屏合法用途说明，勾选“我确认仅在本人或已授权场所使用”。
2. 授予相机、麦克风（可选）、通知权限；缺少通知权限时应用会拒绝开始值守。
3. 点「开始值守」；如出现电池优化提示，可选「暂不授权，直接开始」。
4. 观察状态栏常驻通知「静哨 QuietSentry · 值守中·采集中」，通知内含「停止值守」。
5. 按 Home 回桌面，通知仍在；从通知返回应用可看到已运行时长、录制段数与存储余量。
6. 点「停止值守」→ 确认；视频保存到 `Movies/QuietSentry`，前台服务结束。
7. 可选：点「黑屏记录」验证屏幕全黑时单击拍照、长按退出。

演示视频：`store/fgs-demo/QuietSentry-FGS-Demo-0.1.0-20261123.2.mp4`。视频来自 `emulator-5558`（Pixel 6 AVD / Android 14 / 720×1280）真实会话，仅做拼接、字幕与片头片尾；不能替代真机矩阵。

## 5 camera 前台服务用途声明（英文，表单用）

> QuietSentry uses a foreground service with type `camera` (and `microphone` when the user grants audio permission) to continue recording while the device screen is off. This is the core use case: a spare device is placed on a shelf and must keep recording without keeping the display on.
>
> A persistent, non-dismissible notification is displayed for the entire duration of capture, and it contains a "Stop" action. Capture cannot start without the user pressing "Start guard" inside the app. Recorded clips are stored locally on the device and are not uploaded to any server.
>
> The app also uses a short-lived `dataSync` / `specialUse` foreground service when the user chooses online standby after remote pause; this keeps the local streaming endpoint reachable. It does not record in that state.

## 6 发布轨道与顺序

1. **Internal testing**：上传 AAB → 添加测试员 → 安装并走一遍 §4。
2. **Closed testing（可选）**：家庭成员/访客邀请与局域网观看需两台设备，建议在 closed track 复测。
3. **Production**：暂缓。先完成 `docs/05` §1.1 / `docs/17` §6 的真机 8 品牌矩阵与 7 天稳定性，再提审。
4. 提审前检查：`docs/06` §7 检查单全部勾选；Data safety 与隐私页一致；商店描述不含“偷拍/无痕/不被发现”等词。

## 7 交付物索引

| 文件 | 用途 |
|---|---|
| `docs/04-合规与商店审核材料.md` | 权限、Data Safety、FGS 声明与审核答复 |
| `docs/06-发布与商店提交清单.md` | 商店素材、提交检查单与阻塞项 |
| `docs/17-发布前质量门禁报告.md` | 构建、单测、Lint、签名、冒烟与视频证据 |
| `store/README.md` | 图片/视频规格、制作口径与校验值 |
| `store/manifest.json` | 素材尺寸、字节数与 SHA256 |
| `store/fgs-demo/` | FGS 演示视频与封面 |
| `E:\QuietSentryBuild\release\` | AAB、APK 与 build-info |

## 8 不可直接提交的边界

- 真机 8 品牌矩阵与 7 天稳定性**未完成**；模拟器与 1 台 Samsung S21+ 的结果不能替代。
- 第三方 Frigate / Home Assistant 仅完成接口级验证，商店描述只能写“支持标准 RTSP / ONVIF 接入”，不得承诺开箱即用。
- 快门音地区差异未实测；描述不得承诺静音拍照。
- 应用内无账号体系；不要在任何页面承诺云存储、跨公网访问或付费订阅。
