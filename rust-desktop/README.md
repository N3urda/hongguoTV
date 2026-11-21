# 红果短剧 Rust 桌面试用版

在 Windows 和 macOS 浏览真人剧、漫剧和 AI 剧，搜索剧名、查看集单、剧评和官网排行榜。桌面窗口使用系统 WebView，请求、播放会话和本机资料库由 Rust 实现。发行包随附静态界面资源和原生程序。

「设置 → 账户」可使用红果 App 扫码登录官网。勾选「记住登录」后，macOS 通过钥匙串保存登录态，Windows 通过当前用户 DPAPI 加密保存。电视端的账号收藏、观看历史和预约云同步仍待接入。

应用内可直连已配置的授权正片源。影片详情显示当前分集的可用播放方式，官网公开试看可在详情中手动选择。「设置 → 播放」可以选默认清晰度上限、倍速、画面模式、旋转、常驻进度和自动连播。播放器列出本集实际提供的清晰度。有多个档位时，切换会保留当前位置及暂停状态。「在官网播放」用默认浏览器打开对应分集。

「外观与操作」可配置主题、启动页面、随心看自动播放和播放时钟。播放器支持选集、定时停止、从头播放和窗口内调整画面。播放画面获得焦点时上下键切集、左右键跳转，空格键暂停或继续。`Esc` 返回，`Ctrl/⌘ + K` 打开搜索。

「我的」包含本机收藏、观看记录和稍后看。「设置 → 资料备份」可导出电视端兼容的 JSON，从电视端合并本机资料，并按需恢复清晰度、倍速、旋转、画面模式、进度显示和自动连播。完整版进度优先于试看进度。

## 安装包

- macOS arm64：解压 ZIP 后打开 `Hongguo Rust.app`。试用包采用本地临时签名，面向开发和试用。
- Windows x64：双击 `Hongguo-Rust-*-windows-x64-setup.exe`，按安装界面操作。安装程序为当前用户创建开始菜单入口和卸载项。缺少 [Microsoft Edge WebView2 运行时](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/distribution) 时，安装程序会先联网安装。
- 安装包的 SHA-256 摘要位于同目录的 `.sha256` 文件。

## 构建与验证

从仓库根目录运行，使用 Rust 1.92 或更新版本。

```sh
cargo run --manifest-path rust-desktop/Cargo.toml --locked
cargo test --manifest-path rust-desktop/Cargo.toml --locked
cargo test --manifest-path rust-desktop/Cargo.toml --locked --no-default-features

# macOS Apple Silicon
python3 rust-desktop/package.py --target aarch64-apple-darwin

# Windows x64。将 NSIS 的 makensis 加入 PATH，提供签名已校验的微软 Evergreen 引导程序。
python rust-desktop/package.py --target x86_64-pc-windows-msvc --webview2-bootstrapper MicrosoftEdgeWebview2Setup.exe
```

产物写入 `outputs/rust-desktop/`。开发时可用 `--serve` 启动仅监听回环地址的同一个应用服务，在浏览器调试界面。该服务随应用进程结束。

本机收藏和试看进度保存在 macOS 的 `~/Library/Application Support/HongguoRust/` 或 Windows 的 `%LOCALAPPDATA%\HongguoRust\`，测试可用 `HONGGUO_RUST_DATA_DIR` 指向独立目录。关闭播放器和打开下一集会保存已读进度。

`rust-core` 负责目录和分集查询、取源结果、选档和远端 MP4 分段读取。桌面负责窗口、会话、账号凭据和本机资料。Android 手机及 Android TV 通过 JNI 共用续播、选档和视频分段规则，继续使用 Android 播放适配。Android 安装包使用 [Android 构建入口](../kotlin-tv/README.md#构建)。

## 授权正片源

运行时可以将 `HONGGUO_PLAYBACK_MANIFEST` 指向运营方提供的本机 JSON 清单。清单将授权地址与剧集和分集编号对应，地址须在清单列出的 HTTPS 来源内，视频服务须支持字节范围请求。授权地址由内容方提供。分集有匹配的正式源时，桌面普通播放和自动连播会使用该源。

```json
{
  "version": 1,
  "origins": ["https://media.example"],
  "episodes": [
    {
      "series": "100",
      "episode": "200",
      "sources": [
        {"quality": 720, "url": "https://media.example/episode-200-720.mp4"}
      ]
    }
  ]
}
```
