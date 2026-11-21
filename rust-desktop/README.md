# 红果短剧 Rust 桌面试用版

在 Windows 和 macOS 浏览真人剧、漫剧和 AI 剧，搜索剧名、查看集单、剧评和官网排行榜。桌面窗口使用系统 WebView，请求、播放会话和本机资料库由 Rust 实现。发行包随附静态界面资源和原生程序。

「设置 → 账户」可使用红果 App 扫码登录官网。勾选「记住登录」后，macOS 通过钥匙串保存登录态，Windows 通过当前用户 DPAPI 加密保存。电视端的账号收藏、观看历史和预约云同步仍待接入。

应用内通过 Rust core 自动注册匿名播放设备并获取正片源，也可直连已配置的授权正片源。影片详情显示当前分集的可用播放方式，官网公开试看可在详情中手动选择。「设置 → 播放」可以选默认清晰度上限、倍速、画面模式、旋转、常驻进度和自动连播。播放器列出本集实际提供的清晰度。有多个档位时，切换会保留当前位置及暂停状态。「在官网播放」用默认浏览器打开对应分集。

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

`rust-core` 负责目录和分集查询、匿名设备注册、v5 / Argus 签名取流、播放模型解析、选档、主备地址和远端 MP4 分段解密。`models` feature 可独立解析播放模型，不依赖 HTTP 客户端。桌面负责窗口、会话、账号凭据和本机资料。Android 手机及 Android TV 通过 JNI 共用续播、选档和视频分段规则，继续使用 Android 播放适配；新模型解析器尚未接入 Android JNI。Android 安装包使用 [Android 构建入口](../kotlin-tv/README.md#构建)。

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
        {
          "quality": 720,
          "url": "https://media.example/episode-200-720.mp4",
          "backup_url": "https://media.example/backup/episode-200-720.mp4"
        }
      ]
    }
  ]
}
```

每个分集也可以用 `video_model` 代替 `sources`，接收内容方提供的模型对象或 JSON 字符串。以下为单个分集条目，外层清单仍须配置 `version` 和 `origins`：

```json
{
  "series": "100",
  "episode": "200",
  "video_model": {
    "video_list": [
      {
        "quality_desc": "720p",
        "codec_type": "h264",
        "vwidth": 720,
        "vheight": 1280,
        "main_url": "https://media.example/episode-200-720.mp4",
        "backup_url_1": "https://media.example/backup/episode-200-720.mp4"
      }
    ]
  }
}
```

模型解析支持对象或数组形式的 `video_list`、普通 HTTPS 地址及其 Base64 编码，并保留有效条目的清晰度、编码和尺寸。同档位的不同编码由 core 按已有清晰度上限及兼容规则选择；尺寸按短边推导清晰度，未提供清晰度时显示“自动”。桌面启用 `registered` feature，支持 `key_seed` 地址解密、`spade_a` 密钥派生及 CENC 视频解密；没有有效密钥的加密条目和不兼容的 bytevc2 条目会被跳过，全都不可用时返回明确错误。

主、备用地址都须在 `origins` 中。打开主源失败时只尝试该档位的备用地址；开始播放后分段读取固定使用同一个文件，避免混用文件内容。批量接口的已授权响应可通过 `PlaybackModel::decode_response` 按分集编号解析，错误响应或缺失分集不会替换成其他集。

未提供匹配清单时，普通播放使用 core 的 `RegisteredSource`：首次播放注册匿名设备，优先使用移动端 v5 模型接口；空响应或无可用档位时有界回退到单集 Argus/Ladon 接口。账户 Cookie 不参与设备注册和取流，失败不会自动切为试看。设备身份只在当前进程内复用。

完整 core 接口、测试与实网探测方式见 [rust-core/README.md](../rust-core/README.md)。
