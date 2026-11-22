# Hongguo playback core

Rust 公共播放层。`registered` feature 将 main 的匿名设备注册、取流签名、地址/密钥解析和视频分段解密移植到 core；桌面端已直接调用。

## 接口和分工

```rust,ignore
let source = hongguo_playback::registered::RegisteredSource::new()?;
// 每个应用共享一个 source，跨集、切清晰度和并发请求复用设备身份。
let selected = source.ladder(episode_id).await?
    .select(1080, None).ok_or_else(|| anyhow::anyhow!("无可用清晰度"))?;
let media = RemoteMp4::open_source(authorized_media_client()?, &selected).await?;
// HTTP Range 响应的每个 chunk 都须按绝对文件位置调用 decode_chunk。
// decode_chunk 同时替换 MP4 索引中的加密标记；明文视频则原样返回。
let decoded = media.decode_chunk(absolute_offset, &chunk)?;
```

- 首次使用注册源时校验并复用匿名设备档案，缺少档案时才通过 `ttEncrypt(gzip(JSON))` 注册。并发请求共享身份。`RegisteredSource::new` 默认保留内存档案，应用可通过 `with_profile_store` 提供独立的凭据存储。macOS 桌面端使用钥匙串，Windows 桌面端使用 DPAPI。档案仅含设备标识和允许的匿名 Cookie。读取失败时本次使用内存身份，并保留已有档案。
- 优先请求移动端 v5 模型，签名绑定最终查询、gzip 后正文与当前身份；`device_level=1` 请求可兼容档位。主接口返回空正文时使用同一身份请求 v5 签名的 `video_tab` GET，签名输入为空字节。主接口返回模型但没有可播档位时，使用单集 Gorgon/Argus/Ladon 模型，必要时读取该模型提供的 `fallback_api`。
- HTTP 拒绝、业务拒绝、Retry-After、错集、格式错误不会触发跨接口重试。连续空响应可在后续前台请求更换身份；注册有 30 秒冷却，失败不覆盖旧身份。整个取流过程有 12 秒上限，取消会终止正在等待的请求。
- `RegisteredSource::ladder` 返回结构化的 `PlaybackError`。调用方可以用它区分网络中断、超时、HTTP 状态、空响应和设备注册失败，也可以通过 `PlaybackError::Model` 区分上游业务拒绝、格式异常、错集和无可播资源。
- 预约、账户收藏和云端进度的 `submit` 返回 `ProviderError`。可以用 `is_explicit_rejection` 识别明确的业务拒绝；连接中断、限流和异常响应的写入结果需要通过读取上游状态确认。
- 模型支持对象、JSON/Base64 字符串，按请求分集匹配，保留清晰度、编码、主备地址与内存密钥；跳过 bytevc2 和损坏的单个档位。地址密钥和媒体 URL 不发送到桌面界面。
- CENC 使用 MP4 样本/子样本索引和 AES-CTR；支持非对齐 Range、拖动进度和原编码标记恢复。准备源时验证并限制索引大小，开始播放后固定同一文件。
- `models` feature 可独立解析明文模型。`web` 提供网站目录、清单源及分段读取，`registered` 增加完整注册取流。找剧请求、列表与游标解析、弹幕请求与时间段解析由公共 Rust 规则实现。桌面直接调用，Android 手机和 TV 通过 JNI 调用，并由 Kotlin 负责各自的原生界面、账号与网络会话。无原生库的普通 JVM 使用同样响应约定的 Kotlin 实现。

来源是本仓库 `main` / `origin/main` 的 `DeviceRegister.kt`、`PlaybackDeviceSession.kt`、`MobilePlayback.kt`、`Signer.kt`、`AesV3.kt`、`V5Constants.kt`、`PlayerSignature.kt`、`PlaybackModels.kt`、`MediaCrypto.kt`。上游来源及许可证保留于 [NOTICE](../kotlin-tv/NOTICE.md)。

完整能力对照见 [Rust core 与 OpenHongGuo 能力取舍](../docs/research/2026-11-22-rust-core-sdk-review.md)。

## 验证

```sh
cargo test --manifest-path rust-core/Cargo.toml --all-features --locked
cargo test --manifest-path rust-core/Cargo.toml --features models --locked
cargo test --manifest-path rust-core/Cargo.toml --features android-jni --locked
cargo clippy --manifest-path rust-core/Cargo.toml --all-features --all-targets --locked -- -D warnings
cargo test --manifest-path rust-desktop/Cargo.toml --locked
```

Android 调试包的 `LivePlaybackInstrumentation`（`suite=rust`）通过安装包内的 JNI 验证续播、选档、Range、找剧与弹幕，并启动对应的手机或电视界面。实网模式 `suite=catalog` 接受 `series`、`episode` 和 `duration_ms`，通过 APK 的正式读取入口验证找剧与弹幕。

测试覆盖 main 的 Go 签名/ttEncrypt 向量、32 个 SDK 摘要向量、四种 v5 carrier 时间戳、并发设备复用、注册失败冷却、拒绝/空响应处理、CENC 索引及 NIST 非对齐子样本解密。常规测试仅使用本机模拟服务。

公开目录契约检查与应用共用 `Site`，检查找剧、题材筛选、搜索、搜索联想、详情、分集、热播榜和公开分集评论。默认用找剧首页的剧名作为搜索词，也可以在命令末尾加 `-- "剧名"`。探测仅访问官网匿名目录，输出检查结果和失败阶段。GitHub Actions 的 `Check Rust public catalog contract` 工作流进入默认分支后，每周执行一次，也可以手动运行。

```sh
cargo run --manifest-path rust-core/Cargo.toml --features web --locked \
  --example catalog_contract
```

手动匿名注册源探测需要官网剧集编号、对应分集和真实时长（毫秒）。它先核对分集归属，再检查播放模型、找剧及续页、相关作品和弹幕时间段。也可通过同一个 GitHub Actions 工作流的手动输入运行。

```sh
cargo run --manifest-path rust-core/Cargo.toml --features registered --locked \
  --example registered_contract -- <series_id> <episode_id> <duration_ms>
```

实网探测需显式传入一个分集编号；默认仅解密读取 64 KiB。可选第二参数保存最多 2 MiB 的诊断样本，文件必须不存在。输出不包含设备身份、密钥或访问地址。

```sh
cargo run --manifest-path rust-core/Cargo.toml --features registered --locked \
  --example registered_probe -- <episode_id> [sample.mp4]
```
