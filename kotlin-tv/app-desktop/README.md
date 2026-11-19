# 红果桌面试用版

Windows/macOS 共用 Compose Desktop 外壳、KMP shared-core 和现有 JVM core。默认加载真实内容，可浏览、搜索、收藏、查看历史、选集并播放。视频由 MediaMP 0.5.1 / libmpv 渲染在红果窗口内，外壳控制暂停、跳转、倍速、清晰度、自动连播和续播。

## 直接使用

macOS 本机已生成并启动：`build/compose/binaries/main/app/HongguoDesktop.app`。

1. 打开应用，选择剧集。
2. 点击“播放 / 继续观看”，或点击某个集数。
3. 当前窗口开始播放；桌面底部提供控制条。
4. “停止播放”释放播放器和本机视频服务；退出应用会保存进度并停止播放。

安装包自带当前平台的 MediaMP 原生运行库。首次播放时库从应用的 classpath 解压并加载。

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

两个参数均运行同一个窗口内验收流程，通过真实目录和片源验证画面变化、音轨、暂停、拖动、切集与续播。验收使用临时资料库。

## 模块边界

- `DesktopState`：浏览/搜索/收藏/历史页面状态及过期请求隔离。
- `DesktopPlayback`：播放生命周期、连播、控制命令与进度保存。
- `PlaybackBridge`：只绑定 127.0.0.1 的每会话 Range 服务，随机访问路径；复用 `RemoteVideo` 读取和处理视频数据。
- `MpvMediampPlayerSurface`：桌面 Compose 视频画布，使用进程内原生播放器。
- `DesktopLibrary`：本地数据读写；`DesktopArtwork`：有界封面缓存；`DesktopNetwork`：桌面传输策略。

首次匿名播放会话准备使用独立的有界时间预算，不再挤占片源模型请求的短时限。平台播放器和本地 HTTP 服务没有进入公共业务模块。

桌面模块使用独立的 Gradle included build，固定 Compose 1.12.0、Kotlin 2.3.20 和 [MediaMP 0.5.1](https://github.com/open-ani/mediamp/releases/tag/v0.5.1)。公共模块继续使用原 Kotlin 工具链。

## 导航与资料库

详情返回保留当前列表和页码；制作方作品可以翻页。选集按 40 集分组，也可输入集数播放。侧栏可切换深色/浅色外观，并提供本机“稍后再看”。

“本机资料备份”支持导入电视端备份中的收藏、历史和稍后看，以及导出相同格式；合并保留较新的本机进度。登录态和云端收藏不会随备份写入，播放设置不被导入覆盖。最新验证为 428 项自动测试通过，真实播放控制器回归通过。

## 桌面界面与模块边界

macOS 和 Windows 使用 `:app-desktop` 的 Compose Desktop 首页、剧集详情和播放控制条。窗口装饰跟随操作系统。首页的「继续观看」取本机和已登录账号的最近两部作品，进入详情后可续播或选集。播放期间可继续浏览；底部「更多」展开进度、清晰度、倍速、弹幕等控制。

电视 UI 位于 `:app`。两个前端分别依赖 JVM `:core`，再由它引用 `:shared-core` 的公共业务规则。导航、布局、焦点和窗口交互归各自前端；修改共享的续播或选集规则时应同时运行核心与客户端测试。根 Gradle 构建检查跨模块依赖，防止公共模块依赖前端，或电视和桌面互相引用。
