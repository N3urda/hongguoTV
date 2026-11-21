# 红果短剧 Rust 桌面试用版

在 Windows 和 macOS 浏览真人剧、漫剧和 AI 剧，搜索剧名、查看完整集单、收藏剧集和续播公开试看。桌面窗口使用系统 WebView，请求、播放会话和本地资料库由 Rust 实现。发行包随附程序所需的静态界面资源，可独立联网使用。

**在应用中试看官网公开的 MP4。** 官网提供的片段时长、清晰度和可播集数随作品变化。任意分集可使用「在官网播放」交给默认浏览器。账号功能由官网浏览器页面提供。

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

现有 Android 手机、Android TV 和 Compose Desktop 仍使用仓库各自的构建入口。
