<p align="center"><img src="../assets/brand/logo-pixel.png" width="96" alt="红果 TV 标识" /></p>

# 红果 TV 原生版

Kotlin + Android 原生 View + Media3，单 APK，最低 Android 5.0 / API 21。包名 `com.hongguotv.nativeapp`；界面功能见 [项目说明](../README.md)，合并策略与验证见 [集成记录](MAIN_INTEGRATION.md)。

## 使用

设置 → 账户 → 登录与账户管理，选择手机红果 App 扫码并在手机确认。登录态加密保存在本机，不进入片单备份；Android 5 使用 Keystore RSA 包装 AES 密钥，Android 6+ 使用 Keystore AES。退出账号清除账号数据，不删除本机收藏或历史。

收藏与观看历史有本机／账户页签。账户预约区分待上线与已上线；相关作品支持带封面的待上线预约。详情页可查看制作方及已核实的同制作方作品，更多作品支持分页检索与失败重试（平台检索不保证覆盖全部作品，见[说明](../docs/features/producer-works.md)）。正式播放会同步账号进度，首集试播不写正式历史。

首页点“播放全集”直接按观看记录续播，返回进入选集页。首页左右换剧会独立预取后续作品；等待时保留当前画面，右侧显示加载或重试卡片。顶部导航复用同一组控件，仅更新栏目内容。

播放器内遥控器确定暂停／继续，左右快退快进，向下选集、向上打开操作。设置中可选择画面旋转、常驻进度、倍速、清晰度和跨季连播。自动更新来自 `N3urda/hongguoTV`，由系统确认安装。

## 构建

需要 JDK 17、Android SDK 36 和 Build Tools 36.0.0。配置 `ANDROID_HOME` 或本机 `local.properties` 的 `sdk.dir`。

```sh
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

签名包使用 `scripts/build-kotlin.sh`。签名配置来自未跟踪的 `kotlin-tv/keystore.properties`（storeFile/storePassword/keyAlias/keyPassword），或 HONGGUOTV_KEYSTORE、HONGGUOTV_STORE_PASSWORD、HONGGUOTV_KEY_ALIAS、HONGGUOTV_KEY_PASSWORD 环境变量。不要提交签名材料。正式包必须沿用原证书；debug 包不能覆盖原签名包。

主线继续保留 API 21 兼容目标，因此 Media3 使用 1.8.0，新版预加载 API 已适配。Android 5/6/7 实体电视的解码、安装及登录加密存储还需实机验收；不能把 JVM 测试等同于硬件验证。

## 发布

[发布流程](AUTO_UPDATES.md)沿用主线单仓库 Release。源代码推送或 PR 本身不会发布 APK；正式标签才触发发布。可先在本地完成构建、签名与发布材料校验，用户合并后再发布。

[第三方声明](NOTICE.md) · [GPL-3.0](LICENSE) · [原功能分支验证](UPSTREAM_SYNC.md)
