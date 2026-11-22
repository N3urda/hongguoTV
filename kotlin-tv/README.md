<p align="center"><img src="../assets/brand/logo-pixel.png" width="96" alt="红果 TV 标识" /></p>

# 红果 TV 原生版

Android 原生 View 和 Media3 负责界面与解码，Rust 内核负责续播目标、清晰度选择和视频分段响应校验。通用 APK 会根据设备选择界面。电视使用遥控器导航，手机使用底部导航、系统输入法、竖屏选集和触控播放器。默认要求 Android 6.0 / API 23，Android 5 使用兼容包。包名为 `com.hongguotv.nativeapp`。界面功能见 [项目说明](../README.md)。

## 使用

设置 → 账户 → 登录与账户管理，选择手机红果 App 扫码并在手机确认。登录态加密保存在本机，不进入片单备份；Android 5 使用 Keystore RSA 包装 AES 密钥，Android 6+ 使用 Keystore AES。退出账号清除账号数据，不删除本机收藏或历史。

收藏与观看历史有本机／账户页签。账户预约区分待上线与已上线；相关作品支持带封面的待上线预约。详情页可查看制作方及已核实的同制作方作品，更多作品支持分页检索与失败重试（平台检索不保证覆盖全部作品，见[说明](../docs/features/producer-works.md)）。正式播放会同步账号进度，首集试播不写正式历史。

首页点“播放全集”直接按观看记录续播，返回进入选集页。首页左右换剧会独立预取后续作品；等待时保留当前画面，右侧显示加载或重试卡片。顶部导航复用同一组控件，仅更新栏目内容。

播放器全屏观看时向上切上一集、向下切下一集，长按只切一次，左右快退快进。确定键暂停／继续并打开菜单，再次确定继续并收起；菜单键打开菜单且不改变播放状态。菜单为单行透明图标按钮（倍速直接显示当前倍率）：播放／暂停、选集、倍速、弹幕设置、更多设置；不再保留上下集按钮，菜单和弹窗内方向键仅导航，返回逐层关闭。播放内选集在菜单按钮下方内嵌展开（无独立弹窗），与详情页共用集数分组和网格（每组 20 集、每行 10 集），播放内分组和集数均为透明底，支持直接选择分组，不显示跳转集数入口；详情页保留跳转集数。设置中可选择画面旋转、常驻进度、倍速、清晰度和跨季连播。自动更新来自 `N3urda/hongguoTV`，由系统确认安装。

在触屏手机上轻点视频可打开或收起播放菜单，拖动进度条可定位播放位置。播放菜单提供时间轴弹幕设置。首页的「随心看」支持上下滑切换作品，选集按钮用于同一作品。登录后，手机「我的」合并本机与账户的收藏、观看历史，并按最近观看时间续播。「我的 → 预约」显示待上线与已上线的记录，详情页「相关作品」可预约待上线作品。

进入设置默认显示独立的“检查更新”分类，焦点落在检查更新按钮，可查看当前版本、检查、下载并安装新版；关闭更新面板后焦点回到该按钮。

设置 → 外观与操作 → 界面主题可选择经典黑、纯净白、暖米色、森林绿、深海蓝。切换即时生效并保存到本机；弹窗与焦点使用对应配色，视频深色遮罩上的文字保持浅色。

## 构建

在 Linux 或 macOS 上使用 JDK 17、Android SDK 36、Build Tools 36.0.0、NDK 27.1.12297006、Python 3.12+ 和 Rust 1.92+。配置 `ANDROID_HOME` 或本机 `local.properties` 的 `sdk.dir`。

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
./kotlin-tv/gradlew -p kotlin-tv -PdebugDevice=tv :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

手机和电视的本地试用包使用 `-PdebugDevice=phone` 或 `-PdebugDevice=tv` 构建，安装后均显示为 `hongguoTV`。手机包强制使用触控界面，电视包保留 Android TV 及普通启动器入口，并强制使用遥控器界面。配合 `-PlegacyAudio=true` 时，产物分别位于 `app/build/phone/outputs/apk/debug/` 和 `app/build/tv/outputs/apk/debug/`。两个试用包使用同一个测试包名，可分别覆盖同签名的旧试用版并保留记录。

设备验收可通过 `:app:assembleDebug :app:assembleDebugAndroidTest -PlegacyAudio=true` 构建兼容版及其测试包。通过 ADB 安装两包后运行共享内核验收。

```sh
adb shell am instrument -w -e suite rust \
  com.hongguotv.nativeapp.debug.test/com.hongguotv.nativeapp.LivePlaybackInstrumentation
```

使用 `-e suite live` 测试在线播放。在 Android TV 上，该用例也会发送方向键验证上下集切换。

签名包使用 `scripts/build-kotlin.sh`。签名配置来自未跟踪的 `kotlin-tv/keystore.properties`（storeFile/storePassword/keyAlias/keyPassword），或 HONGGUOTV_KEYSTORE、HONGGUOTV_STORE_PASSWORD、HONGGUOTV_KEY_ALIAS、HONGGUOTV_KEY_PASSWORD 环境变量。不要提交签名材料。正式包必须沿用原证书；debug 包不能覆盖原签名包。

兼容包继续保留 API 21 目标，因此 Media3 使用 1.8.0，新版预加载 API 已适配。Android 5/6/7 实体电视的解码、安装及登录加密存储还需实机验收；不能把 JVM 测试等同于硬件验证。

## 发布

[发布流程](AUTO_UPDATES.md)沿用主线单仓库 Release。源代码推送或 PR 本身不会发布 APK；正式标签才触发发布。可先在本地完成构建、签名与发布材料校验，用户合并后再发布。

[第三方声明](NOTICE.md) · [GPL-3.0](LICENSE) · [原功能分支验证](UPSTREAM_SYNC.md)

默认构建不带 FFmpeg；添加 `-PlegacyAudio=true` 构建 Android 5 兼容版。四种正式 APK（Android 5 通用、Android 6+ ARM32、ARM64 和通用） 与更新迁移见 [发布说明](AUTO_UPDATES.md)。
