# 红果桌面试用版

Windows/macOS 共用 Compose Desktop 外壳、KMP shared-core 和现有 JVM core。默认加载真实内容，可浏览、搜索、收藏、查看历史、选集并播放。视频显示在独立的 mpv 原生窗口，外壳控制暂停、跳转、倍速、清晰度、自动连播和续播。

## 直接使用

macOS 本机已生成并启动：`build/compose/binaries/main/app/HongguoDesktop.app`。

1. 打开应用，选择剧集。
2. 点击“播放 / 继续观看”，或点击某个集数。
3. 原生视频窗口开始播放；桌面底部提供控制条。
4. “停止播放”释放播放器和本机视频服务；退出应用会保存进度并停止播放。

本机使用已安装的 `/opt/homebrew/bin/mpv`。其他机器需要安装 mpv，或通过环境变量 `HONGGUO_MPV` 指定可执行文件。Windows 自动检查 PATH 和 `C:/Program Files/mpv/mpv.exe`。本包没有捆绑 mpv，也不是已签名正式发布。

默认直连内容服务，与 Android 端行为一致。需要系统代理的网络可用 `HONGGUO_USE_SYSTEM_PROXY=1` 启动；应用不修改操作系统代理。实测本机代理会显著延长部分内容接口的握手时间，超过原核心的请求时限。

## 本地数据

收藏和最近 200 条观看进度保存在：

- macOS：`~/Library/Application Support/HongguoDesktop/library.json`
- Windows：`%LOCALAPPDATA%/HongguoDesktop/library.json`
- Linux：`~/.local/share/hongguo-desktop/library.json`

可用 JVM 属性 `hongguo.dataDir` 指定独立目录。写入先生成临时文件再替换；损坏文件不自动覆盖，界面会提示。播放中每约 5 秒保存进度，停止/切集/退出时再保存。演示内容和真实收藏隔离，演示集数不能播放。

## 开发与验证

JDK 17，在仓库根目录：

```sh
./kotlin-tv/gradlew -p kotlin-tv :app-desktop:run
./kotlin-tv/gradlew -p kotlin-tv :app-desktop:test :core:test
./kotlin-tv/gradlew -p kotlin-tv :app-desktop:createDistributable
```

Windows 使用 `kotlin-tv\gradlew.bat`。桌面构建不需要 Android SDK；Windows 安装器需在 Windows 主机生成。macOS 打包使用 Temurin JDK 17；未关闭 Compose 对不兼容 Homebrew JDK 的检查。

显式在线验收（会访问上游并打开播放窗口，不在 CI 自动执行）：

```sh
./kotlin-tv/gradlew -p kotlin-tv :app-desktop:run --args=--smoke
./kotlin-tv/gradlew -p kotlin-tv :app-desktop:run --args=--smoke-controller
```

第一项验证真实片源、解码、暂停、seek 和倍速；第二项验证控制器、自动切到下一集、本地进度与续播。报告写在 `app-desktop/build/reports/`，不含片源地址或密钥。

## 2026-11-16 验收结果

- 13 项桌面测试、382 项 JVM core 测试、10 项公共核心测试通过，共 405 项。Android Kotlin 编译通过。
- 本机真实剧集解码成功，取得 1080×1920 / 720×1280 视频参数和音轨，进度持续推进。
- 暂停、跳转至 15 秒、1.25 倍速、画面比例及旋转参数通过；控制器自动进入第二集，从持久化进度恢复，并在设定“播完本集”后停止。
- 画面参数延迟到视频轨道就绪后设置，修复冷启动时参数不可用导致的失败；修复后真实控制器回归通过。
- macOS 钥匙串以独立虚构凭据完成写入/读回/清理；实际扫码二维码、漫剧分类、排行榜和制作方详情入口已验证。
- 真实封面成功解码；macOS 应用包重新生成并实际启动。
- 最终从应用界面点击真实剧集“播放 / 继续观看”，进度推进至 27 秒，再用界面按钮暂停，显示“继续”。
- Windows/macOS CI 包含桌面测试和 assemble，但未推送执行；Windows 安装、命名管道 IPC 及实机播放仍待验收。
- Ctrl/Cmd+K、Enter、Esc 和空格快捷键已实现；此前自动化键盘注入没有生效，未将键盘实机体验标为完整验收。

账号功能已接入：侧栏“登录 / 我的账号”提供扫码、短信登录和退出；勾选“记住登录”后使用 macOS 钥匙串或 Windows 用户级 DPAPI，默认仅本次会话有效。登录后收藏使用云收藏，历史合并本机/云端记录，播放自动同步进度。云端写入后回读确认，未确认时提示刷新核对。

本轮新增分类题材、搜索建议、个性化推荐、相关季数/预约、制作方作品、评论、定时弹幕文字、跨季连播、画面比例/旋转、定时关闭和偏好保存。完整实现与验收边界见 [桌面/TV 对照表](../../docs/superpowers/plans/2026-11-16-desktop-tv-parity.md)。真实账号登录后的云端读写尚未完成用户验收；Windows 实机、内嵌视频、自动更新和长时间压力测试仍待完成。

## 模块边界

- `DesktopState`：浏览/搜索/收藏/历史页面状态及过期请求隔离。
- `DesktopPlayback`：播放生命周期、连播、控制命令与进度保存。
- `PlaybackBridge`：只绑定 127.0.0.1 的每会话 Range 服务，随机访问路径；复用 `RemoteVideo` 读取和处理视频数据。
- `MpvPlayer`：独立进程和 JSON IPC；macOS 使用 Unix socket，Windows 使用命名管道。
- `DesktopLibrary`：本地数据读写；`DesktopArtwork`：有界封面缓存；`DesktopNetwork`：桌面传输策略。

首次匿名播放会话准备使用独立的有界时间预算，不再挤占片源模型请求的短时限。平台播放器和本地 HTTP 服务没有进入公共业务模块。

参考：[mpv JSON IPC 官方手册](https://mpv.io/manual/stable/#json-ipc)、[Compose 1.8.2 官方说明](https://kotlinlang.org/docs/multiplatform/whats-new-compose-180.html)。
