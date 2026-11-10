# hongguoTV

面向 Android 电视和电视盒子的非官方红果短剧客户端，支持短剧、漫剧浏览与播放，针对电视大屏和遥控器操作设计。

应用采用 Kotlin 原生开发，以独立 APK 运行。安装后联网即可使用，无需部署服务，也不需要常驻电脑或 NAS。

**[下载 APK](https://github.com/N3urda/hongguoTV/releases)** · [详细使用与构建说明](kotlin-tv/README.md) · [反馈问题](https://github.com/N3urda/hongguoTV/issues)

## 主要功能

- **发现内容**：推荐、关键词搜索、漫剧排行榜、剧集详情与选集。
- **电视操作**：支持遥控器方向键、确认键、返回键和媒体控制。
- **播放控制**：暂停、快进快退、清晰度选择、倍速、自动连播与定时停止。
- **画面设置**：原比例显示、等比铺满、拉伸、视频旋转与常驻进度显示。
- **观看管理**：本地收藏、最近观看、稍后看、已看标记与断点续播。
- **手机辅助**：同一局域网内通过手机输入搜索词、备份和恢复本机观看记录。

## 安装与使用

支持 Android 5.0（API 21）及以上系统。不同电视的解码能力、音频输出和遥控器适配可能有所差异。

1. 在 [Releases](https://github.com/N3urda/hongguoTV/releases) 选择 APK，并阅读对应安装说明。
2. 通过 U 盘、电视文件管理器或 ADB 安装：

   ```sh
   adb install -r APK文件路径
   ```

3. 打开「红果 TV 原生版」，通过推荐、搜索或排行榜选择剧集。
4. 在设置中调整默认清晰度、倍速、画面模式等选项。

播放时，确认键暂停或继续，左右键跳转进度，下键打开选集，上键或菜单键打开更多播放操作。

**更新请使用与已安装应用相同签名的 APK。** 开发签名预览包不能覆盖正式签名包，请勿先卸载旧应用，以免丢失本机记录。具体兼容性以下载页说明为准。

收藏、观看进度和设置保存在本机，不与红果账号自动同步。手机辅助功能仅在对应窗口打开时临时提供局域网访问。

## 开发与构建

原生工程位于 `kotlin-tv/`，包含独立的内容处理模块和 Android 电视应用。

| 目录 | 用途 |
| --- | --- |
| `kotlin-tv/app/` | 电视界面、遥控器交互、本地记录与播放器 |
| `kotlin-tv/core/` | 内容接口、请求签名、播放地址解析与媒体处理 |
| `kotlin-tv/core/src/test/` | 核心逻辑单元测试与接口响应回归测试 |
| `kotlin-tv/tools/` | 开发与验证工具 |

需要 JDK 17、Android SDK Platform 36 / Build Tools 36.0.0，并配置 `ANDROID_HOME` 或 `kotlin-tv/local.properties`。

```sh
# 运行单元测试、Android lint，并构建开发签名 APK
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:lintDebug :app:assembleDebug
```

产物位于 `kotlin-tv/app/build/outputs/apk/debug/app-debug.apk`。普通 APK 构建与运行不需要 Node.js。

正式签名构建及发布配置见 [构建说明](kotlin-tv/README.md#构建) 和 [应用更新说明](kotlin-tv/AUTO_UPDATES.md)。

## 反馈问题

请在 [Issues](https://github.com/N3urda/hongguoTV/issues) 中提供应用版本、设备型号、Android 版本、复现步骤和错误提示。播放问题可附剧名与集数，便于区分片源问题与设备兼容问题。请勿上传账号密码、令牌或其他私密信息。

## 来源与许可证

本项目与红果官方无关联。内容依赖第三方接口，接口调整或内容下架可能影响可用性。

项目代码按 [GPL-3.0](LICENSE) 发布，第三方代码保留各自许可证与来源声明，详见 [原生版第三方说明](kotlin-tv/NOTICE.md) 和 [第三方来源记录](server/vendor/NOTICE.md)。
