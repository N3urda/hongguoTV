# 0.15.1 发布交接

版本 0.15.1，versionCode 20，minSdk 21。

- 改善随心看缓冲停滞、视频分段暂停后超时。
- 失效首集提示可从详情核实并更新缓存，不保证上游持续空响应恢复。
- 统一详情、随心看和公共面板视觉，改善选集状态显示。

已通过核心和 Android 单测、Debug lint、诊断 APK 构建、32 项发布合约测试。模拟器验收范围与限制见 docs/PLAYBACK_VALIDATION_2026_11_13.md、docs/UI_STYLE_ALIGNMENT_2026_11_14.md。

本机无原签名配置，NDK 下载失败；未构建完整正式 APK，未创建发布标签。请在持有原签名的机器合并本 PR 后，按 AUTO_UPDATES.md 完成含 FFmpeg 的三架构正式构建、证书校验和设备验证，再推送 kotlin-v0.15.1 并运行本地发布脚本。versionCode 20 已准备，无需再次递增。
