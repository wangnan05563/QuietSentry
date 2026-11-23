# 商店素材

对应 `0.1.0-20261123.2`（versionCode 76）的 Google Play 提交素材。

## 文件

| 文件 | 规格 | 用途 |
|---|---|---|
| `icon-512.png` | 512×512 PNG，无透明通道 | Google Play 应用图标 |
| `feature-graphic-1024x500.png` | 1024×500 PNG | Google Play 特色图片 |
| `screenshots/phone/01-live-view.png` | 1080×1920 PNG | 手机截图：实时观看、对讲/警笛/手电 |
| `screenshots/phone/02-guard.png` | 1080×1920 PNG | 手机截图：本机值守状态与停止入口 |
| `screenshots/phone/03-pairing.png` | 1080×1920 PNG | 手机截图：扫码/配对码与邀请家庭成员 |
| `screenshots/phone/04-device-list.png` | 1080×1920 PNG | 手机截图：设备列表与自定义名称 |
| `screenshots/phone/05-settings.png` | 1080×1920 PNG | 手机截图：免打扰、检测时段、提醒级别 |
| `screenshots/tablet/01-settings.png` | 1200×2133 PNG | 平板截图：设置与诊断 |
| `screenshots/tablet/02-remote-access.png` | 1200×2133 PNG | 平板截图：远程访问与连通性诊断 |
| `fgs-demo/QuietSentry-FGS-Demo-0.1.0-20261123.2.mp4` | 1080×1920，30 fps，34.2 秒，H.264，无音轨 | Play Console camera 前台服务声明演示视频 |
| `fgs-demo/poster.png` | 1080×1920 PNG | 演示视频封面 |

## 制作说明

- 图标由 `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` 放大并补齐方形底色；不含透明边缘。
- 手机截图 01/02/04 的界面来自真机 `com.quietsentry.app` 截图；原截图中的彩色测试画面已替换为同一间夜间客厅的演示场景，UI 文案、按钮和布局未改动。
- 手机截图 03/05 与平板截图来自仓库内既有 UI 证据，原尺寸 720×1280 / 1200×2133；03/05 已放大到 1080×1920。
- 截图中的设备名 `MyCam01`、配对码与设备指纹均为测试数据；配对码已过期，不代表当前设备凭据。
- 摄像头画面为演示场景，不包含任何真实住户、客户或可识别个人信息。

## camera 前台服务演示视频

`fgs-demo/QuietSentry-FGS-Demo-0.1.0-20261123.2.mp4` 为 Play Console camera FGS 声明用视频，34.2 秒、1080×1920、30 fps、H.264、无音轨，SHA256 `C06C0C4BD853409C101CC199469689D1072ACFE00EABE39951943617421345A1`。

镜头顺序：

1. 片头：应用名、版本 `0.1.0-20261123.2` / versionCode 76、录制设备。
2. 首页待机 → 点「开始值守」→ 电池优化提示 → 点「暂不授权，直接开始」。
3. 值守中：画面由前台服务后台采集，界面不再占用相机。
4. 按 Home 回到桌面 → 下拉通知栏，显示「静哨 QuietSentry · 值守中·采集中」与「停止值守」动作。
5. 从通知返回应用：已运行 29 分钟、记录 45 段、存储余量 4.8 GB。
6. 停止值守确认框（35:18、49 段）→「正在保存到相册」。
7. 片尾：流程勾选与录制环境说明。

制作口径：

- 屏幕画面来自 `emulator-5558`（AVD `qsui`，Pixel 6 / Android 14 / API 34 / 720×1280）安装 `0.1.0-20261123.2` release APK 后的真实会话；开始与通知栏段落为 `screenrecord` 实拍，关键状态为同一会话截图。
- 剪辑仅做顺序拼接、统一缩放、章节字幕、片头片尾与淡入淡出；没有合成、补拍或改写任何状态文案。
- 视频为模拟器录制，用于说明 FGS 行为与通知/停止路径，不能替代 `docs/05` 要求的真机 8 品牌矩阵与 7 天稳定性。
- 视频中「已保存 6 段」「45 段」「49 段」等计数来自对应真实会话，不是后期添加。

## Play Console 使用

- 手机截图最多选 8 张，本目录 5 张可直接使用。
- 平板截图可选 2 张；如只提交手机截图，平板截图不影响手机端审核。
- 特色图片不得带 Play 图标或“下载”按钮；本图未包含商店徽标。
- camera FGS 演示视频在 Play Console「App content → Foreground service permissions」声明流程中上传；如表单只接受链接，可先上传到可公开访问的对象存储再填链接。
