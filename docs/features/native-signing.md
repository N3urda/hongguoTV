# 本机原生签名构建

此构建把经核对版本的 ARM64 安全 SDK 放在电视进程内，使用电视自己的注册身份和本人登录会话。默认构建继续使用 Kotlin V5；原生 SDK 由构建者显式提供，不从公共签名服务取签名，也不在运行时下载。

## 准备输入

要求 Python 3.10+、项目现有 Android 构建环境，以及已取得的官方 `com.phoenix.read` 7.4.5.32 APK。自动提取仅接受本轮核对的文件：

- APK SHA-256：`c4583f645d72b5022f060038a9fd286117f316c6fa7f46990c088083344a7d92`
- `lib/arm64-v8a/libmetasec_ml.so` SHA-256：`c6831ab265a0cb76dacfa3b7041e9ea0f706490106a74e12f39058671cc14fe3`
- 应用范围：`aid=8662`。这不是历史 `aid=1967` 签名配置。

在仓库根目录执行，将 APK 路径换成本机文件：

```sh
python3 scripts/prepare_native_signing_sdk.py \
  --apk /absolute/path/official.apk \
  --output outputs/native-signing-sdk
```

脚本从固定 APK 提取库及 SDK 初始化配置，不安装或启动官方 App；清空设备、安装、会话和扩展槽位，写入哈希清单。输入 APK、输出库和完整配置保留在忽略目录，不提交到 Git。输出配置默认使用该版本的 basic-mode 值，不代表复现了官方全部隐私授权或环境采集路径。

也可用 `--metadata /absolute/path/sdk-metadata.json` 提供已核对的 17 槽位配置。脚本仍校验库版本、应用范围并清除身份数据；当前准备脚本、构建和运行仅接受已验证的采集模式 `5`，拒绝未覆盖的标准采集模式 `99999`。该入口不支持未经研究的新 SDK 版本。

## 编译

```sh
./kotlin-tv/gradlew -p kotlin-tv \
  -PnativeSigningSdkDir="$PWD/outputs/native-signing-sdk" \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

产物在 `kotlin-tv/app/build/native-signing/outputs/apk/`。该选项只生成 ARM64、Android 6+ 安装包，不能和 `-PlegacyAudio=true` 合用。没有这个参数时，默认构建不包含 SDK 库及配置，原有 ARM32 和 Android 5 构建方式不变。

构建和运行均校验库哈希。签名初始化失败、未知设备回调、签名结果不完整会返回明确错误；不自动回退到其他签名器，不重新生成设备身份。JNI 类名保留规则也用于混淆构建。

## 设备与账号状态

首次签名前，SDK 接收本机已持久保存的注册身份。初始化、`first_time`、fetchedDid 登记通知、did/iid 同步、`did-iid-update`、账号代际对应的 `login` 和签名串行执行，避免并发请求混用身份。fetchedDid 取自本机注册结果，与普通更新设备 ID 的入口不同；固定设备对照中，补上它后 SDK 自行生成了 f16 并恢复模型返回，未手工改写原生签名。`login` 事件不直接传入 Cookie 或 Token；真正的账号头在最终请求签名入口交给 SDK。

已返回的事件和登录代际在本机记账，进程重启重新绑定 did/iid；不会将每次 Token 刷新当成一次新登录。SDK 本机调用返回不证明服务端接纳了事件或永久信任此设备。当前没有猜测实现 session setter、TokenGuard 或主动 Token 刷新协议。

同一进程仅接受首次初始化使用的登记 DID，正常短信登录沿用它。如果历史登录档案切换导致登记 DID 变化，会明确要求重启应用，让 SDK 重新绑定，避免请求头中的设备与 fetchedDid 状态混用。

模型、短信和账号接口的响应也回到同一签名提供者，先处理 SDK 状态，再更新账号会话或交付模型。已接入 `x-bdms-ctrl` URL 规则持久化及 `x-bdms-cj` 对应事件，按 native 开关和匹配规则执行异步报告。需要修改正文的 `sync` 规则、`x-bdms-dy` 动态模块目前未实现：命中时明确停止，后者的待支持状态会持久保存，不通过重启或隐藏回退忽略服务端要求。

本适配器未包含独立的 SDKMonitor 遥测模块，其回调返回不可用，不伪造初始化成功；这不是官方 App 所有组件的完整移植。包名、版本、证书、系统电量等已实现回调读取宿主应用的实际环境，未知回调仍报错。

SDK 网络报告使用独立、无账号 CookieJar 的 HTTPS 传输，限定服务域名、响应大小和超时，不递归进入模型签名。网络状态变化也通知 SDK。厂商广告标识目前未采集，返回“尚未取得”的空值；厂商设备证明仅支持查询能力是否缺席。如果确实存在专有服务或安全 Provider，明确提示尚未接入，不用缺席状态替代真实证明。

账号实验的开启和重新登录方式见 [账号播放](account-playback.md)。登录、取得模型与正式播放器首帧分别记录；真实账号在实体电视上的续期与长期播放，需要单独验收。

## 验证入口

请在隔离 ARM64 模拟器安装对应 debug APK 和 androidTest APK，保持官方 App 未安装、账号未登录。以下测试有不同证据范围：

```sh
# 合成请求仅调用真实 JNI 签名，检查初始化与返回结构。
adb shell am instrument -w -e nativeSigningProbe true \
  com.hongguotv.nativeapp.debug.test/com.hongguotv.nativeapp.LivePlaybackInstrumentation

# 真实游客请求进入正式播放器，覆盖三个样本、首帧、seek 和切集。
adb shell am instrument -w -e nativePlaybackSamples true \
  com.hongguotv.nativeapp.debug.test/com.hongguotv.nativeapp.LivePlaybackInstrumentation
```

具体 applicationId 以生成的 APK manifest 为准。JNI 冒烟测试不会发送验证码或使用真实账号；SDK 自身可能执行初始化/事件网络任务。播放器验收会访问生产媒体接口，使用该隔离应用自己的持久注册身份。

测试日志只输出阶段、计数和播放结果，不能把签名、媒体地址、设备标识、Cookie 或 Token 加入日志或提交。单元测试覆盖串行身份绑定、事件重试、进程恢复、账号头进入签名、请求取消和凭据隔离；它们不能代替平台和真实设备验收。

2026-11-25：真实 JNI 合成探针已通过播放、短信发送、短信登录和账号资料四类 URL 的签名生成，均返回三个必需安全头。该探针不向短信和账号接口发送请求，不能据此认定真实短信流程或账号取流已被平台接受。

原生实现的 13 项状态/策略/能力边界单元测试、`lintDebug` 和 Release 的 `minifyReleaseWithR8` 均已通过；混淆编译通过不等于实体设备已运行该 Release 包。

同日，隔离 ARM64 模拟器的正式播放器通过三个问题样本：均渲染 1920×1080 首帧并持续播放，另外通过拖动、切到下一集和暂停恢复。播放结束后再次请求模型，未出现未支持的 SDK 回调。结束进程后再次执行整套验收，同一持久设备身份校验通过，模型和播放仍成功。原生签名由应用进程内的 SDK 生成，未安装或启动官方 App，未使用真实账号。脱敏结果在本地忽略目录 `outputs/issue119-followup/native-playback-final.log` 与 `native-playback-restart.log`；最终交付 APK 又通过一轮重启验收，记录在 `native-playback-packaged.log`。这验证了当前版本的游客链路和进程恢复，不等于实体电视、账号续期或长期服务端策略已获验证。
