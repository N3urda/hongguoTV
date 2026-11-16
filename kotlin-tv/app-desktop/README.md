# 红果桌面外壳原型

Windows/macOS 共用 Compose Multiplatform Desktop 界面，直接依赖现有 JVM core 和 KMP shared-core。当前原型只做浏览与交互，不包含视频播放器、账户登录、云同步或持久化收藏。

## 运行

需要 JDK 17，在仓库根目录运行：

```sh
./kotlin-tv/gradlew -p kotlin-tv :app-desktop:run
```

Windows 使用 `kotlin-tv\gradlew.bat -p kotlin-tv :app-desktop:run`。桌面构建不需要 Android SDK。

```sh
./kotlin-tv/gradlew -p kotlin-tv :app-desktop:test :core:test
./kotlin-tv/gradlew -p kotlin-tv :app-desktop:createDistributable
```

本地应用目录：`build/compose/binaries/main/app/`。macOS 生成 `HongguoDesktop.app`；Windows 需在 Windows 主机生成对应应用或 MSI，不能把本机 macOS 构建当成 Windows 验收。

打包需使用 Temurin 等受支持 JDK；Compose 会拒绝部分 Homebrew JDK 分发。本次使用机器已有 Temurin JDK 17 成功打包，未关闭检查。安装器版本 1.0.0 仅满足平台打包格式，应用本身仍是未签名原型，不是正式发布。

## 可以体验

- 固定侧栏、顶部搜索、适应窗口宽度的内容网格。
- 发现、排行榜、会话收藏、剧集详情和选集。
- 默认演示模式：虚构剧集和排版海报，所有页面明确标识。
- 取消“演示内容”可使用 core 的真实分类、排行、搜索和详情接口；失败显示重试，不以演示数据替代。
- 收藏仅保留在当前会话，演示与在线数据分开。
- 实现 Tab/Shift+Tab 导航、Enter 搜索、Ctrl/Cmd+K 搜索焦点、Esc 返回；实际键盘验收状态见下文。
- 点击集数显示播放区域占位，明确提示尚不支持视频播放。

## 验证记录（2026-11-16）

- 桌面状态测试 3 项通过：迟到响应隔离、收藏模式隔离与选集边界、在线失败不回退演示。
- 公共核心 10 项、JVM core 381 项回归通过。
- macOS 原生应用包生成并实际启动，截图检查首页布局；鼠标验证排行切换、详情、收藏和收藏列表。
- 关闭演示模式后真实分类接口返回了剧集和分页信息。截图时封面请求未成功，正确显示封面占位；图片加载质量仍需进一步验证。
- 当前计算机自动化向 Java 窗口发送键盘输入未产生效果，未将键盘快捷键或真实搜索流程标为实机验收通过。
- Windows/macOS CI 已增加桌面 test/assemble，但远程 CI 未运行；Windows 安装和键鼠实机体验尚未验收。

## 代码边界

`DesktopState` 负责页面状态、请求代次与会话收藏；`CatalogSource` 隔离演示/真实数据；`DesktopShell` 负责桌面布局；`Main` 负责窗口生命周期。网络调用在 IO 线程执行，旧请求即使迟到也不能覆盖新页面。

后续优先级：键盘实机验收与无障碍标签、图片缓存和加载、桌面播放器原型、收藏/进度持久化，再推进签名与发布。现有 Android TV/手机界面未替换。

工具链选择参考 [Compose 1.8.2 官方说明](https://kotlinlang.org/docs/multiplatform/whats-new-compose-180.html)，保持与当前 Kotlin 2.1.20 配套。
