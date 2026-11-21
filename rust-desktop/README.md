# 红果短剧 Rust 桌面试用版

在 Windows 和 macOS 浏览真人剧、漫剧和 AI 剧，搜索剧名、查看集单、剧评和官网排行榜。桌面窗口使用系统 WebView，请求、播放会话和本机资料库由 Rust 实现。发行包随附静态界面资源和原生程序。

「设置 → 账户」可使用红果 App 扫码登录官网。勾选「记住登录」后，macOS 通过钥匙串保存登录态，Windows 通过当前用户 DPAPI 加密保存。电视端的账号收藏、观看历史和预约云同步仍待接入。

应用内播放官网公开试看，片段时长、清晰度和可播集数随作品变化。「在官网播放」用默认浏览器打开对应分集。播放器可选集、调节倍速、定时结束，也可在设置中指定默认画面模式、旋转、常驻进度条和自动连播。方向键可移动焦点，播放画面获得焦点时上下键切集、左右键跳转，空格键暂停或继续。`Esc` 返回，`Ctrl/⌘ + K` 打开搜索。

「我的」包含本机收藏、观看记录和稍后看。「设置 → 资料备份」可把这些资料导出为电视端兼容的 JSON，或从电视端备份中合并这三类资料。合并时保留本机关联设置和账户，完整版进度优先于试看进度。

## 安装包

- macOS arm64：解压 ZIP 后打开 `Hongguo Rust.app`。试用包采用本地临时签名，面向开发和试用。
- Windows x64：解压 ZIP 后打开 `Hongguo Rust.exe`。窗口使用 Windows 的 [Microsoft Edge WebView2 运行时](https://developer.microsoft.com/en-us/microsoft-edge/webview2/)。
- ZIP 的 SHA-256 摘要位于同目录的 `.sha256` 文件。

## 构建与验证

从仓库根目录运行，使用 Rust 1.92 或更新版本。

```sh
cargo run --manifest-path rust-desktop/Cargo.toml --locked
cargo test --manifest-path rust-desktop/Cargo.toml --locked
cargo test --manifest-path rust-desktop/Cargo.toml --locked --no-default-features

# macOS Apple Silicon
python3 rust-desktop/package.py --target aarch64-apple-darwin

# Windows x64，在 Windows 上运行
python rust-desktop/package.py --target x86_64-pc-windows-msvc
```

产物写入 `outputs/rust-desktop/`。开发时可用 `--serve` 启动仅监听回环地址的同一个应用服务，在浏览器调试界面。该服务随应用进程结束。

本机收藏和试看进度保存在 macOS 的 `~/Library/Application Support/HongguoRust/` 或 Windows 的 `%LOCALAPPDATA%\HongguoRust\`，测试可用 `HONGGUO_RUST_DATA_DIR` 指向独立目录。关闭播放器和打开下一集会保存已读进度。

本应用与 Android 手机及 Android TV 共用 `rust-core` 的续播和视频分段规则。Android 安装包使用 [Android 构建入口](../kotlin-tv/README.md#构建)。
