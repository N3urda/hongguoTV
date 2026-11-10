# hongguoTV

非官方红果短剧电视客户端，当前交付 **Kotlin 原生独立 APK**。最低 Android 5.0（API 21），安装后联网即可使用，不需要部署内容服务。

**[下载正式签名 APK](https://github.com/N3urda/hongguoTV/releases/latest)** · [原生版说明与构建](kotlin-tv/README.md)

## 安装与更新

1. 下载 APK，通过 U 盘或电视支持的方式侧载安装。已连接 ADB 时可使用 `adb install -r APK文件路径`。
2. 打开「红果 TV 原生版」，通过首页、搜索或排行榜选剧播放。
3. 后续在设置中的「版本与更新」检查新版，由 Android 系统确认安装。同包名、同签名覆盖安装可保留本机记录，请勿为升级先卸载。

原生版包名为 `com.hongguotv.nativeapp`，与旧 React Native 版 `com.hongguotv` 可并存；两者的收藏和历史不自动迁移。应用内更新与发布配置见 [自动更新说明](kotlin-tv/AUTO_UPDATES.md)。

## 功能与使用边界

- 推荐、搜索、排行榜、详情选集与电视遥控器操作。
- 本地收藏、最近观看、稍后看与断点续播。
- 清晰度选择、自动连播、倍速和定时停止。
- 内容接入与媒体处理在 APK 内完成，无需常驻电脑或 NAS。

最低 Android 5.0 配置不等于实际电视验收。具体设备的解码、声音、遥控器和长时间稳定性仍需实机验证，详情见 [原生版说明](kotlin-tv/README.md)。第三方接口变化可能导致内容失效，错误不会替换成虚假内容。

## 开发与验证

原生工程位于 `kotlin-tv/`。需要 JDK 17、Android SDK Platform 36 / Build Tools 36.0.0，并配置 `ANDROID_HOME` 或 `kotlin-tv/local.properties`。

```sh
# 单元测试、Android lint 和开发签名 APK
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:lintDebug :app:assembleDebug
# 正式签名配置完成后构建发布 APK
./scripts/build-kotlin.sh
```

签名配置、开发包与正式包的区别见 [构建说明](kotlin-tv/README.md#构建)。main/PR 验证并上传开发 APK；仅匹配源码版本的标签触发同仓库正式发布，原签名 Secrets 未配置时明确失败。详见 [发布流水线](kotlin-tv/AUTO_UPDATES.md)。本仓库不再构建或发布容器镜像、Compose 配置及 Docker 部署包。

## 保留的开发参考

旧 React Native / Node 源码、测试、通用依赖与许可仍保留，供协议对照和历史维护使用，不是当前用户部署方式。`kotlin-tv/tools/signing-oracle.cjs` 仍读取 `server/vendor/hongguo.cjs` 生成签名测试样本；请勿因为移除容器交付而删除这些文件。普通 APK 构建与运行不需要 Node。

旧工程检查可运行 `npm ci`、`npm run typecheck` 和 `npm test`，独立的旧工程 CI 保留这些检查。根目录 `.env.example` 仅用于旧 Node 服务开发，不是 APK 配置。

- [旧版 v0.1.1 交付记录](docs/RELEASE_0.1.1.md)
- [旧版首版交付记录](docs/FIRST_RELEASE.md)
- [旧版项目状态](docs/PROJECT_STATUS.md)
- [旧版 React Native 与多平台架构](docs/ARCHITECTURE.md)
- [旧版服务 API](docs/API.md)
- [接口研究](docs/research/2026-09-21-hongguo-open-source.md)

## 来源与许可证

项目与红果官方无关联。项目代码按 [GPL-3.0](LICENSE) 提供，第三方部分保留各自许可。原生移植来源见 [原生版第三方说明](kotlin-tv/NOTICE.md)；保留的 Node 源使用固定提交的 `drpys` 实现，原文件、GPL-3.0 许可证及来源记录见 [第三方说明](server/vendor/NOTICE.md)。Android / tvOS 旧工程骨架源于 MIT 许可的 RN TV 模板。
