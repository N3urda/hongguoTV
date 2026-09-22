# 红果 TV 原生独立版

这是独立的 **Kotlin + Android 原生 View + Media3** 工程，最低 Android 8.0 / API 26。安装一个 APK，联网后可直接浏览和播放，无需部署 Node、Docker 或填写服务器地址。

- 应用名称：红果 TV 原生版
- 包名：`com.hongguotv.nativeapp`，与旧版 `com.hongguotv` 可以并存。
- 收藏和历史独立保存在本机，不自动迁移旧版数据。
- APK 不含 JNI 库，可由 Android 在 ARM32、ARM64、x86、x86_64 上运行；视频解码能力依赖设备。
- 源码分支：`codex/kotlin-standalone`。
- [下载正式签名 APK](https://github.com/N3urda/hongguoTV/releases/tag/kotlin-v0.2.1)

0.2.1 修复简介与设置切换后的焦点跳转，并适配放大字号的卡片高度。可直接覆盖安装 0.2.0，保留收藏和观看记录；见 [交付与验证记录](DELIVERY.md)。

## 当前源码新增（待发布）

- 推荐、搜索增加「漫剧 / 短剧」类型选择，默认漫剧，重开应用保留上次选择。漫剧首页展示漫剧热播榜，搜索限定漫剧；切换类型会回到第 1 页。收藏和最近观看继续保留原有全部记录。
- 播放时按遥控器「↓」打开菜单，选择「倍速」：0.75 / 1 / 1.25 / 1.5 / 1.75 / 2×。立即生效，换集、重试及重开应用后保持；取消不修改倍速，关闭弹窗后焦点回到倍速按钮。
- 漫剧接口失败会显示重试，不会用真人短剧结果代替。搜索翻页携带接口游标，避免重新搜索导致结果重复。

以上功能在当前源码和新构建的开发 APK 中；上方已发布的 0.2.1 下载包不包含这些改动。Debug APK 使用开发签名，不能覆盖不同签名的正式安装包。

本轮测试和模拟器证据见 [漫剧与倍速验证记录](COMIC_SPEED_VERIFICATION.md)。

## 使用

1. 将 `hongguotv-kotlin-0.2.1-android8.apk` 拷贝到电视，允许文件管理器安装应用后打开 APK；或执行 `adb install -r hongguotv-kotlin-0.2.1-android8.apk`。
2. 在电视应用列表打开「红果 TV 原生版」，进入推荐或搜索。
3. 遥控器确认键进入详情，选择开始观看或续播。设置中可选择 720P / 1080P 上限和是否自动连播。

播放中：确认键暂停/播放；左右跳转 10 秒，长按连续跳转；向上显示信息；向下打开播放、上下集、选集菜单。返回键先收起菜单，再退出播放；详情返回恢复原卡片和页码。

## 构建

需要 JDK 17、Android SDK Platform 36 / Build Tools 36.0.0。Gradle 8.14.3 包装器已包含；不需要 npm、Node 或 React Native。设置 `ANDROID_HOME`，或在本目录的 `local.properties` 写入 `sdk.dir=SDK绝对路径`。

```sh
# 仓库根目录：开发包直接可安装，无需发布私钥
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:lintDebug :app:assembleDebug
adb install -r kotlin-tv/app/build/outputs/apk/debug/app-debug.apk
```

正式 APK 使用独立发布密钥，**私钥和口令不在 GitHub 中**。重建发布包时，配置以下环境变量，或创建被 Git 忽略的 `kotlin-tv/keystore.properties`：

```properties
storeFile=/absolute/path/to/your-release.jks
storePassword=your-password
keyAlias=your-alias
keyPassword=your-password
```

对应环境变量：`HONGGUOTV_KEYSTORE`、`HONGGUOTV_STORE_PASSWORD`、`HONGGUOTV_KEY_ALIAS`、`HONGGUOTV_KEY_PASSWORD`。使用自己的签名重建时，不能直接覆盖安装官方发布密钥签名的 APK；卸载会清除本机记录。更新已安装的正式版应继续使用同一发布密钥。

```sh
./scripts/build-kotlin.sh
# 输出：outputs/hongguotv-kotlin-0.2.1-android8.apk
```

## 工程结构

- `core/`：纯 Kotlin 内容列表、搜索、详情、请求签名、播放地址解析、MP4 索引和分段处理；可在 JVM 测试。
- `app/`：原生电视界面、遥控器焦点、本地收藏/进度、Media3 播放器及自定义 DataSource。
- `core/src/test/`：固定签名对照、NIST AES-CTR 向量、跨样本/非对齐分段、AVC 索引恢复及畸形输入校验。
- `tools/signing-oracle.cjs`：仅开发期生成兼容性测试样本；运行、构建 APK 不需要它。

播放器直接通过 HTTPS 分段拉取数据，经本机 Kotlin 处理后交给 Media3。没有本地 HTTP 服务、监听端口、JS 引擎或服务器进程。媒体只在内存中处理，不下载整部剧到存储。元数据响应、封面大小、索引数量和播放缓冲均有上限。

```sh
# 可选联网检查：真实网站和内容接口，网络变化可能导致失败
./kotlin-tv/gradlew -p kotlin-tv :core:run
# 漫剧分类、搜索两页、详情与真实视频分段检查
./kotlin-tv/gradlew -p kotlin-tv :core:run --args=--comic
# 重新生成签名对照样本才需要 Node
node kotlin-tv/tools/signing-oracle.cjs
```

## 验证边界

已进行 JVM 单元测试、Android lint、正式 APK 构建及 Android TV API 36 模拟器上的真实播放和遥控器检查。最低 API 26 配置和 API 兼容静态检查已验证；**Android 8.0 实体电视的芯片解码、音频输出和长期稳定性尚待实机验证**。遇到设备解码失败可尝试 720P，仍失败需对应电视的日志。

内容来自第三方接口；接口、签名、内容可用性或地区网络变化可能需要更新 APK。此版本不是内容平台官方客户端。

原生代码按 GPL-3.0-only 发布；派生来源和依赖声明见 [NOTICE.md](NOTICE.md)，完整许可见 [LICENSE](LICENSE)。
