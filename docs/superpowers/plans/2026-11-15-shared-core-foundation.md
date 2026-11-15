# Shared Core Foundation Implementation Plan

**Goal:** 建立四端可复用的 KMP 业务核心，并让现有 Android 通过 JVM core 实际使用它。

**Architecture:** 新增 shared-core（commonMain + JVM target）；原 core 保留网络、加密、JSON 和线程适配，并通过 api 依赖 shared-core。保留类型包名与数据字段，JSON 成员改为 JVM 扩展函数，调用方显式补充导入。

**Tech Stack:** Kotlin 2.1.20、KMP、JVM 17、kotlin.test、现有 Gradle/Android 工具链。

## 执行步骤

- [x] 运行现有 `:core:test` 建立基线；记录环境或既有失败。
- [x] 添加 `:shared-core` 和 multiplatform 插件；配置 JVM target、公共测试；core 使用 `api(project(":shared-core"))`。
- [x] 将 ContentType、EpisodeSummary、PlaybackSpeed、PrefetchPolicy、PlaybackComfort 原样迁移；抽取内容/播放/进度模型、续播和恢复预算；JSON 转换留在 core。
- [x] 将选集输入、画面旋转和进度展示纯逻辑与搜索历史 JSON 分开。保留已有数据格式和包名。
- [x] 编写公共测试，覆盖续播身份匹配、进度优先级、恢复预算耗尽、清晰度降级与计时；原 JVM 测试继续验证解析和存储兼容。
- [x] 运行 `:shared-core:allTests :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`；验证 `-PlegacyAudio=true` Android 构建与测试。
- [x] 记录模块依赖、桌面接入方式与尚未迁移的模块，更新总计划进度，提交实现。

## 本轮边界

交付真实接入 Android 的核心基础，不实现桌面播放器、四端 UI 或数据库迁移；不把 JVM 构建通过称作 Windows/macOS 实机验收。公共层无 java、Android、OkHttp、JSON 和 Media3 引用。暂不添加没有调用方的播放器抽象或异步门面。

## 验证记录

本机 macOS，JDK 17：

- 基线 `:core:test` 通过（Gradle 使用已有最新结果）。
- 改动后实际执行：公共核心 10 项、JVM core 381 项、Android 普通版 312 项测试，均无失败或跳过；普通版 lintDebug、assembleDebug 通过。
- Android legacyAudio 384 项测试及 lintDebug、assembleDebug 通过，但使用 `-x :decoder-ffmpeg:buildNativeAudio` 复用已有 FFmpeg 原生产物。四种架构缓存的源码/配置标记均与当前脚本、JNI 源码、FFmpeg 摘要和 NDK 版本匹配。完整原生构建尝试因当前 SDK 缺少 NDK 27.1.12297006 失败，不能视为从源码构建验收。
- 最终普通版检查在新增 JSON 兼容测试后重新执行并通过。
- Windows/macOS CI job 已配置，未推送运行；没有进行桌面播放器、真机或长时间播放验收。
- `git diff --check` 通过。

JSON 兼容测试检查旧字段、空值处理、备份脱敏和损坏记录拒绝。原 Android 测试继续通过传递依赖消费新的共享模型。
