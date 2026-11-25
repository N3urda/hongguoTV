# 官方设备、账号与签名：长期实现的边界

## 要达到的目标

让每台电视用自己的持久注册身份承载本人的 App 账号会话，按照完整请求上下文生成签名，并维护服务端返回的状态。登录成功和播放成功应分别验证，不能把“已登录”自动标成“媒体接口已接受该账号设备”。

设备注册与是否匿名是两个维度。官方 App 在登录前就取得设备 ID / 安装 ID；登录后继续使用设备上下文，同时增加账号会话。自己的持久设备可以先处于游客状态，再用于账号请求。没有证据表明登录后可以省略注册身份或安全签名。

本轮没有验证某个接口可以把第三方 App 永久加入设备白名单。#119 的跨 App 恢复现象仍不能简单解释成两个 App 共享 Token。

## 官方 APK 的直接证据

样本：`com.phoenix.read` 7.4.5.32，APK SHA-256 `c4583f645d72b5022f060038a9fd286117f316c6fa7f46990c088083344a7d92`。基于本地 APK 的定点反编译与已有 Dalvik 指令，未读取用户账号。

1. **注册身份交给安全模块管理。** `MSConfig.Builder` 提供 `setDeviceID` / `setInstallID`；`MSManager` 的同名更新方法进入 `ms.bd.c.f3`，先更新 `mDeviceID` / `mInstallID`，再通知 native 模块。不能只在 URL 写入一套 ID、签名内部仍沿用另一套。
2. **账号头进入安全签名之前的请求。** `k78.a` 通过 `TTTokenManager.addRequestHeader` 注入账号头；OkHttp/Cronet 的安全回调再把最终 URL 和头集合传给 `NetworkParams.tryAddSecurityFactor`，后者调用 `ms.bd.c.k3` 的 native 入口。SDK 接收头集合，不等于已经证明每个头都参与某个摘要。
3. **账号状态有响应生命周期。** `TTTokenManager.processResponseHeader` 处理响应中的 Token 更新和过期。只保存首次登录 Cookie 不等价于完整账号客户端。
4. **SDK 的 session setter 存在，但不能凭名字猜调用。** `MSManager.setSessionID` 最终会写 `mSessionID` 并进入 native；当前静态扫描尚未找到业务层直接调用。`MSConfig.Builder.setSecssionID` 在这个样本中甚至直接返回 `this`。不能据此杜撰“把 sessionid 填进去就完成设备认证”。
5. **核心实现是 native 库。** APK 内实际提供 `lib/arm64-v8a/libmetasec_ml.so`，4,501,136 字节，SHA-256 `c6831ab265a0cb76dacfa3b7041e9ea0f706490106a74e12f39058671cc14fe3`。该样本没有同名 ARM32 库；直接采用此库不能覆盖当前项目的全部电视平台。
6. **有明确的初始化、设备与登录事件链。** 启动任务 `c3.run` 调用 `e3.b(context)` 初始化安全 SDK；首次启动报告 `first_time`。它订阅注册结果，交 `g3.accept` 调 `setDeviceID`、`setInstallID`，随后 `report("did-iid-update")`。`ig3.l0.onDeviceRegistrationInfoChanged` 也同步这两个 ID 并报告更新。启动任务还注册账号状态监听 `b3`；收到登录为 true 时，`d3.run` 调 `MSManager.report("login")`。这段调用没有直接传 session/token，不能据此推断事件内的所有内容或平台已经授信。
7. **初始化还有独立的已登记 DID 入口。** `MSConfig.Builder.setFetchedDidInterface` 的业务实现 `com.dragon.read.app.launch.task.y2` 读取 `AppLog.getDid()`（或其缓存优化路径）。SDK 的 `ms.bd.c.g3` 在初始 `mDeviceID` 为空时取得这个 DID，用应用 ID 获取 manager，调用 native `0x200000e`，第五参为整数 `0`；Java 配置同时把 `mOrigin` 标记为 `1`。这条链与普通 `setDeviceID` 不等价，不能漏掉。

源码证据在忽略目录 `outputs/issue119-official/static-account/jadx/`：`MSConfig`、`MSManager`、`ms.bd.c.f3`、`NetworkParams`、`k78.a`、`TTTokenManager`、`OkHttp3SecurityFactorInterceptor`、`AbsCronetDependAdapter`，以及启动任务 `b3/c3/d3/e3/g3` 和 `ig3.l0`。详细调用位置见同目录上一级的 `findings-2026-11-25.md`。native 内部的完整状态算法没有在本轮全部还原。

## 原生 SDK 固定设备对照

隔离 ARM64 模拟器未安装官方 App、未登录真实账号。对照使用正式 `streamLadderWithCompatibility` 路径，两个签名提供者共用同一持久注册档案；包装器逐次断言签名输入的 did/iid 与档案一致，并确认签名确实被调用。宿主 APK 的包名、安装版本和签名证书保持实际值，接口 query 使用已核对的协议版本。

| 条件 | Kotlin V5 | 原生 SDK |
| --- | --- | --- |
| 只同步 did/iid 和事件，缺 fetchedDid 初始化 | 5 档模型 | 主模型和补充模型均 HTTP 200 / 0 字节 |
| 仅把 basic-mode `5` 换为标准采集模式 `99999` | 5 档模型 | 仍空流；较长等待还暴露未覆盖的标准模式采集回调 |
| 恢复 basic-mode `5`，补官方 fetchedDid 初始化 | f16 长度 25，5 档模型 | **SDK 自行生成 f16，长度 25，5 档模型** |

最后一组每个提供者仅签名一次即取得主模型，SDK 报告传输返回 HTTP 200。首次成功时另有异步回调待补；补齐后，正式播放器的三个样本、1080p 首帧、拖动、切集和暂停恢复均通过，结束时再次取模型且未支持回调集合为空。进程重启后使用同一持久身份再次通过。脱敏对照记录为 `outputs/issue119-followup/native-fetched-compare.log`；最终播放器结果见 [原生构建与验收](../features/native-signing.md)。

合成签名解包先用仓库已有独立 V5 carrier 向量及合成 f16 向量验证逆运算；原生初始化不完整时确实没有 f16，aid、license ID、SDK 版本和 f21 则与本仓库协议一致。Android 测试里的解包助手仅输出 f16 存在性/长度，真实设备标识、签名和字段内容不导出。

这组证据支持：本次原生适配的空流缺口是漏掉已登记 DID 的初始化通知，原生 SDK 能自行生成所需运行字段。它没有证明 f16 的全部内部算法，也没有证明平台把设备加入永久白名单；#119 的跨 App 恢复现象仍需在同机分阶段验证。

## 当前 Kotlin 实现到底在算什么

它不是复用一次抓包的签名字符串。`Signer.signPlayback` 每次根据当前请求计算：

```text
最终 query → SM3
实际发送的压缩正文 → MD5
以上摘要 + 当前时间戳 → F13
摘要、时间、设备身份、协议版本、运行字段 → Medusa 消息
消息 + 每次随机量 → 混淆、协议封装 → X-Medusa
时间戳及协议配置 → X-Khronos / X-Helios
```

现在只实现部分摘要分支，通过调整 `_rticket` 选择已实现分支；不是整个官方 SDK 的等价实现。签名中的若干环境字段使用兼容常量，完整生命周期未还原。此次 f16 修复只是补上已通过固定设备实验确认的缺失项，不能称为已经重现官方设备信任逻辑。

研究时的 `MobilePlaybackSigner` 接口只接收 URL、正文和设备，账号头由后续拦截器加入。官方则先准备账号头再调用安全模块。后续实现已用 `AppRequestSigner` 统一最终请求入口并修正这个顺序，见 [实现说明](../features/account-playback.md)。游客实验没有验证账号头是否影响 native 签名中的具体字段，因此不把顺序差异认定为账号空流的唯一根因。

## 两条可研究的签名路线

| 路线 | 能改善什么 | 尚需验证 |
| --- | --- | --- |
| 在本机调用匹配版本的原生 SDK | 减少手工重建签名分支与运行字段；允许最终账号请求上下文进入官方计算入口；本轮已验证初始化、固定游客设备与进程恢复 | 真实账号、实体电视和续期；当前 APK 库只有 ARM64，不能直接覆盖 ARM32/旧系统 |
| 继续纯 Kotlin，但以官方 SDK 为对照 | 保留 ARM32/旧系统兼容与可审查源码；逐步还原状态而非不断堆常量 | 完整输入、必要状态、摘要分支、跨版本对照；服务端接受与真实播放均需验收 |

历史 [#102 研究](2026-11-23-issue102-nonweb-playback.md) 记录过 ARM64 TV 内置 native SDK 的播放验证；这是可行性线索，不代表该实现目前仍在当前分支，也不是本轮重新验证的账号结果。本轮核对 APK 库哈希与历史记录一致。

把签名放到另一台机器的公共服务，不能自动使服务器认可这台电视自己的账号设备，且增加身份混用与运维负担；不作为本目标的首选。

## 建议的落地顺序

1. **保留可播放基线。** 当前 f16 最小修复与持久设备/账号分支分别有明确验证边界，便于后续同身份对照。
2. **统一最终请求入口。** 设备快照、按域账号头、实际正文先准备完成，再交签名提供者处理。保留 Kotlin 与研究 SDK 的可替换边界，禁止签完再改 URL/正文；账号凭据只用于允许的 API。
3. **按官方回调维护状态。** 设备注册成功/恢复后同步 did/iid 和设备更新事件；账号登录成功触发 SDK 的 login 事件；接收 Token/Cookie 更新；退出或换账号取消旧请求与模型缓存。这些事件需要真实 SDK 或经过对照的等价实现，不能随意拼一个 HTTP 地址代替。只实现有证据的状态，不猜测 `setSessionID`、`app_alert_check` 或未知字段能够永久授信。
4. **把“媒体会话已验证”作为独立状态。** 登录资料接口成功只表示账号登录成功；使用该账号上下文取得正确模型并解码后，才记为该版本与设备组合的播放成功。失败保留脱敏的模式、凭据存在性、路由、状态、长度，不能静默改用另一台匿名设备后仍显示账号播放成功。
5. **验收生命周期。** 固定同一电视/账号，覆盖游客与账号、重启、切集、会话更新和过期；原生与 Kotlin 使用相同最终请求做对照。用户当前不方便 ADB，本轮没有用真实账号补完这些验证。

长期目标是维护一个一致的设备与账号客户端，而不是保存一个永远有效的签名。即使采用官方 SDK，服务端策略和协议版本仍由平台控制，不能承诺一次修改永久有效。
