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

## 制作说明

- 图标由 `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` 放大并补齐方形底色；不含透明边缘。
- 手机截图 01/02/04 的界面来自真机 `com.quietsentry.app` 截图；原截图中的彩色测试画面已替换为同一间夜间客厅的演示场景，UI 文案、按钮和布局未改动。
- 手机截图 03/05 与平板截图来自仓库内既有 UI 证据，原尺寸 720×1280 / 1200×2133；03/05 已放大到 1080×1920。
- 截图中的设备名 `MyCam01`、配对码与设备指纹均为测试数据；配对码已过期，不代表当前设备凭据。
- 摄像头画面为演示场景，不包含任何真实住户、客户或可识别个人信息。

## Play Console 使用

- 手机截图最多选 8 张，本目录 5 张可直接使用。
- 平板截图可选 2 张；如只提交手机截图，平板截图不影响手机端审核。
- 特色图片不得带 Play 图标或“下载”按钮；本图未包含商店徽标。
