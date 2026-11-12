<p align="center">
  <img src="assets/brand/readme-banner.png" width="720" alt="红果 TV — 好故事，大屏看" />
</p>

# 红果 TV

非官方红果短剧电视客户端。Kotlin 原生 APK，安装后联网使用，仅提供 APK 安装包。

## 功能

- 推荐首集试播，分类筛选、遥控搜索、联想词及排行榜。
- 红果扫码登录，账户收藏、云端历史续播、已上线／待上线预约、相关季预约；本机记录与账号记录分开保存。
- 选集、倍速、清晰度、画面模式与旋转、常驻播放进度、定时停止及可选跨季连播。
- 评论、时间轴弹幕、播放恢复与解码兼容策略。
- 应用内更新：校验版本、摘要和原发布签名后交给系统确认安装。

## 下载与兼容

[发布版本](https://github.com/N3urda/hongguoTV/releases) · [构建说明](kotlin-tv/README.md) · [更新与发布](kotlin-tv/AUTO_UPDATES.md)

本次功能集成保留包名 `com.hongguotv.nativeapp`、原签名配置和主线 Android 5.0（API 21）兼容目标。具体测试结果见 [集成记录](kotlin-tv/MAIN_INTEGRATION.md)，最低 SDK 配置不代表所有实体电视已验收。开发签名预览包不能覆盖原签名包，请勿为更新先卸载。

账户预约每类当前最多读取最近 20 部，云端历史读取最近 50 部；以手机账号数据核对为准。旧「稍后看」与备份底层数据保留，新界面暂不提供入口。Mac 客户端与伴机服务端未纳入本次交付。

## 反馈与贡献

欢迎大家在使用过程中提出问题、分享建议，一起让项目变得更好。如果遇到使用问题，或有希望增加的功能，可以直接提交 [Issue](https://github.com/N3urda/hongguoTV/issues)；也欢迎通过 [Pull Request](https://github.com/N3urda/hongguoTV/pulls) 贡献修复和改进。

我们会认真阅读并评估每一份反馈与贡献，结合项目定位、实际需求和维护成本，逐步安排修复、完善功能，或审阅并合并合适的代码改动。

本项目主要利用业余时间维护，受日常工作安排影响，回复、修复和代码审阅可能不够及时，暂时无法承诺具体的处理时间。感谢大家的耐心、理解与支持，也感谢每一位愿意帮助项目持续完善的朋友。

## 本地验证

```sh
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
# 使用原签名配置构建，文件只生成在本地
./scripts/build-kotlin.sh
```

公开仓库标准 GitHub Actions 构建继续保留；本地测试与构建也可独立执行。正式发布在用户合并 PR 后进行，不把测试包当成已发布版本。

本项目与红果官方无关联，平台接口变化可能导致功能失效。代码采用 GPL-3.0，来源与依赖说明见 [NOTICE](kotlin-tv/NOTICE.md)。`android/`、`ios/`、`src/`、`server/` 保留旧实现，原生 APK 不依赖这些目录。
