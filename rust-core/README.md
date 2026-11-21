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

- 首次前台播放执行 `ttEncrypt(gzip(JSON))` 注册，校验 device/installation ID。并发注册合并；身份只保留在内存，不携带账号 Cookie。
- 优先请求移动端 v5 模型，签名绑定最终查询、gzip 后正文与当前身份；`device_level=1` 请求可兼容档位。空响应或没有可播档位时回退一次至单集 Gorgon/Argus/Ladon 模型，必要时读取该模型提供的 `fallback_api`。
- HTTP 拒绝、业务拒绝、Retry-After、错集、格式错误不会触发跨接口重试。连续空响应可在后续前台请求更换身份；注册有 30 秒冷却，失败不覆盖旧身份。整个取流过程有 12 秒上限，取消会终止正在等待的请求。
- 模型支持对象、JSON/Base64 字符串，按请求分集匹配，保留清晰度、编码、主备地址与内存密钥；跳过 bytevc2 和损坏的单个档位。地址密钥和媒体 URL 不发送到桌面界面。
- CENC 使用 MP4 样本/子样本索引和 AES-CTR；支持非对齐 Range、拖动进度和原编码标记恢复。准备源时验证并限制索引大小，开始播放后固定同一文件。
- `models` feature 可独立解析明文模型；`web` 提供网站目录、清单源及分段读取；`registered` 增加完整注册取流。Android JNI 当前仍只复用续播、选档和 Range 规则，Android 的网络播放入口继续使用 Kotlin。

来源是本仓库 `main` / `origin/main` 的 `DeviceRegister.kt`、`PlaybackDeviceSession.kt`、`MobilePlayback.kt`、`Signer.kt`、`AesV3.kt`、`V5Constants.kt`、`PlayerSignature.kt`、`PlaybackModels.kt`、`MediaCrypto.kt`。上游来源及许可证保留于 [NOTICE](../kotlin-tv/NOTICE.md)。

## 验证

```sh
cargo test --manifest-path rust-core/Cargo.toml --all-features --locked
cargo test --manifest-path rust-core/Cargo.toml --features models --locked
cargo test --manifest-path rust-core/Cargo.toml --features android-jni --locked
cargo clippy --manifest-path rust-core/Cargo.toml --all-features --all-targets --locked -- -D warnings
cargo test --manifest-path rust-desktop/Cargo.toml --locked
```

测试覆盖 main 的 Go 签名/ttEncrypt 向量、32 个 SDK 摘要向量、四种 v5 carrier 时间戳、并发设备复用、注册失败冷却、拒绝/空响应处理、CENC 索引及 NIST 非对齐子样本解密。常规测试仅使用本机模拟服务。

实网探测需显式传入一个分集编号；默认仅解密读取 64 KiB。可选第二参数保存最多 2 MiB 的诊断样本，文件必须不存在。输出不包含设备身份、密钥或访问地址。

```sh
cargo run --manifest-path rust-core/Cargo.toml --features registered --locked \
  --example registered_probe -- <episode_id> [sample.mp4]
```
