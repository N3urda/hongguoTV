# 四端共享业务核心

此模块的 `commonMain` 包含不依赖 Android、Java、网络客户端或 JSON 库的业务代码。首批目标为 Android 手机、Android TV、Windows 与 macOS；这四端均消费 JVM 产物，因此目前只配置 `jvm()`，没有宣称 Native/iOS/Web 支持。

## 当前依赖关系

```text
Android app ──> core（JVM 网络、加密、解析、存储转换）
                  └── api ──> shared-core（KMP 业务与模型）
未来桌面 app ──> core 或 shared-core
```

共享模块保留原 `com.hongguotv.core` 包名，避免界面重复维护模型。原 core 通过 `api` 导出 shared-core，因此现有 Android 入口已经使用迁移后的实现。没有复制第二份业务代码。

## 已迁移内容

| 文件 | 职责 |
| --- | --- |
| ContentModels | 剧集、详情、目录页、排行、视频变体与清晰度选择 |
| ProgressModels | 本机和云端观看进度 |
| Watching | 续播合并、重试预算、清晰度偏好、收藏更新高水位 |
| ContentType | 内容类型与偏好默认值 |
| DramaDiscoveryState | 分类题材模型和分类切换状态 |
| EpisodeSummary | 剧集总数及续播摘要 |
| PlaybackComfort | 画面模式、注入时钟的定时停止 |
| PlaybackSpeed | 倍速选项与异常值归一化 |
| PrefetchPolicy | 不与当前缓冲竞争的预加载条件 |
| ViewingTools | 选集输入、旋转尺寸、播放进度显示规则 |

状态对象（RecoveryBudget、SleepTimer、DramaDiscoveryState）按会话创建，由调用方在同一线程串行访问。时钟使用调用方注入的单调时间；不引入后台线程或全局状态。旧中文展示文本暂时保留，国际化属于后续界面任务。

## JVM 适配与持久化兼容

`Series.toJson`、`Series.fromJson`、`BackupProgress.json`、`FavoriteUpdate.json/decode` 已变为原 core 模块中的扩展函数。保留旧 JSON 字段、空值处理、备份脱敏规则和校验，不执行数据迁移。Kotlin 调用方使用 `import com.hongguotv.core.*` 或显式导入对应扩展函数；外部已编译产物需要重新编译，不能把这次拆分视为二进制 ABI 兼容更新。

Java 加密、OkHttp、Jsoup、org.json、线程安全缓存和局域网服务仍在 `core`。账户凭据及其安全存储、完整播放协调器、请求取消与播放器契约尚未迁入公共层。Media3 和厂商兼容仍在 Android app。没有为这些模块添加尚无调用方的空接口。

## 后续客户端接入

同一 Gradle 构建中的桌面 JVM 客户端可以声明：

```kotlin
dependencies {
    implementation(project(":core")) // 包含现有数据访问能力和共享业务
    // 只需要纯业务规则的模块则直接依赖 project(":shared-core")。
}
```

网络调用沿用原 core 的同步约定，必须由客户端在后台执行；共享模块本身不负责调度。桌面应用必须提供自己的播放器、持久化位置和安全凭据实现，不能复用 Android Activity 或 Media3 实例。

## 验证

```sh
./kotlin-tv/gradlew -p kotlin-tv :shared-core:allTests :core:test
./kotlin-tv/gradlew -p kotlin-tv :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

`:core:test` 自动依赖共享模块的 `jvmTest`，旧验证入口不会漏跑公共业务测试。新增 Windows/macOS CI job 验证共享规则和 JVM 集成；配置完成不代表远程 CI 已执行，也不代表桌面播放器已验收。

整体路线见 [四端迁移计划](../../docs/KMP_SHARED_CORE_PLAN.md)。
