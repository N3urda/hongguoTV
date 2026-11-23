# Issue #102：非官网取流方案调查与实测

日期：2026-11-23（北京时间）。

## 结论与范围

用户明确要求不支持官网兜底。本轮只调查 App 播放模型、原生签名、CDN 备用地址和独立片源；没有将官网播放实现合入产品，也没有发布新版本或向 issue 发评论。

[Issue #102](https://github.com/N3urda/hongguoTV/issues/102) 的截图显示 0.16.13 在 `mobile-model` 阶段收到 `HTTP 200 / 0B`。本轮不同开源实现的实测复现了同类现象：尚未取得播放模型和 CDN 地址，故不能靠更换已有 CDN 地址的主机来修复首次播放。

**当前结论：纯 Kotlin 签名已接入默认播放路径，真实播放验收通过。** 进一步同请求逐字段 A/B 将本轮关键差异定位到 Medusa 的第 21 字段：将原有 377 修正为成功 App/SDK 使用的 312，即可在不移植其他动态字段的情况下恢复两个模型对照样本。配合已验证的当前请求参数和 VOD 编码回退，三个问题分集与下一集在 ARM64 Android TV 36 上播放出 1080P，定位、切集和暂停恢复通过。已移除 JNI 实验代码和原生 SDK 构建接线；普通构建直接使用 Kotlin，不需要 `.so`、官方 App、Frida、签名服务或官网。实机、ARM32 和 Android 5/6 的实际播放仍未验收，尚未合并发布。详见文末的纯 Kotlin 实现与验证。

下文 19:13 以前的测试记录保留为失败对照。搜索和匿名注册成功，不能证明播放请求满足服务端所有校验；空响应也不能单独证明封 IP、封账号或所有客户端全局不可用。本轮主要使用同一网络出口，没有跨网络对照，不把网络或服务端策略推测写成已证实根因。

调查分支 `codex/issue102-cdn-recovery`，基线 `ac3a1e2e866b2a917651575ba507adfe62344932`。19:38 以前仅进行外部研究，之后先验证原生适配，再通过逐字段实验完成纯 Kotlin 修复。另两个隔离工作区中的官网实验未迁入此分支；用户主工作区的已有改动保留。下文各时间段的失败、成本估算和限制按当时状态保留。历史 JNI/对照探针已移至本机忽略目录 `outputs/issue102-cdn/native-prototype-source/`，旧命令仅用于解释当时实验，不代表当前默认构建。

## 其他项目采用的办法

| 项目与检查版本 | 实际办法 | 本轮判断 |
| --- | --- | --- |
| [openhongguo](https://github.com/beilunyang/openhongguo/tree/33c888d3a8a2db3ea1ff2f1dce5c479c15bf7626)，0.4.0 | V5 签名、匿名设备、批量 POST；可接 unidbg 原生签名服务。[PR #16](https://github.com/beilunyang/openhongguo/pull/16) 增加 video_tab GET，[PR #17](https://github.com/beilunyang/openhongguo/pull/17) 更换批量 POST 路径 | 主线、组合补丁、原生签名均实际测试，3 个分集都没有模型 |
| [dddmiku/hongguo-tv](https://github.com/dddmiku/hongguo-tv/tree/059b0d83fbde3751565b38f0eb86c17101359fc6)，1.3.5 | Android JNI 调用原生签名库，批量 POST + 单集 GET；有模型后才使用 CDN backup_url | ARM64 Android 36 隔离 QA 包实测 4 个分集，全部未取得模型；不能据此推荐迁移原生库 |
| [guoapp-modules/hongguo](https://github.com/guoapp-modules/hongguo/blob/cdf8a24b83df522aeceff860b45f1cb2efa2f286/main.js)，1.0.7 | 74532、V5 video_tab GET + player POST；有模型但只有不兼容编码时请求 fallback_api；最终 resolveVideoMedia 仍调用 webMedia | 编码回退依赖先有模型，解决不了本次 0B；官网部分排除。本轮检查源码，未声称运行完整 guoapp |
| [tsukiyo-zero/hongguoTV](https://github.com/tsukiyo-zero/hongguoTV)，0.1.37 | V5/Argus 与 GET/POST 路径组合、74532；仍有 [#124](https://github.com/tsukiyo-zero/hongguoTV/issues/124)、[#112](https://github.com/tsukiyo-zero/hongguoTV/issues/112) 的播放失败反馈 | 不以发布说明或“此前成功”推断当前可用；其路径思路也在本轮 SDK 对照中覆盖，但不等同于完整 App 验收 |
| [jackotom/hongguo-drama](https://github.com/jackotom/hongguo-drama/blob/ba1fd443f0e1e94bc6cbcbf4bd99459339a2a287/docs/issue-178-179-2026-11-23.md)，8.0 | 多个 App 路径失败后增加官网取流 | 官网方案排除；其新的单集 V5 POST 路径本轮对照仍为空 |
| [yxt02/hongguo-drama-linux](https://github.com/yxt02/hongguo-drama-linux/commit/1fddd848ba)，1.74.12 | 11 月 23 日的修复明确改用官网完整分集 | 排除 |
| [sunnysky123/hongguo-web](https://github.com/sunnysky123/hongguo-web/commit/17f7b86502)，1.3.3 | 同步本项目 0.16.10 的 video_tab V5 GET | 属于已有方案的移植，未发现新的取流机制 |

另检查了独立 HLS 片源的开源 SDK。固定域名连接失败，但文档列出的一个镜像能返回搜索结果；使用 issue 中的剧名搜索后，结果属于另一套片库，未匹配该剧。没有取用这些不匹配结果的媒体，也没有将这种服务作为红果剧集的替换源。镜像可连接仅证明接口可达，不等于具有所需剧集或已验收播放。

## 真实请求对照

样本：

- `7709114791150767128`：《谁让这个文盲修仙的》第七季第 1 集。
- `7704857946630409240`：《全球死考》第六季第 153 集。
- `7709197486451084312`：《和离后我带女儿横扫魔域》第三季完结篇第 1 集。
- `7663495632371321880`：《无敌财神！我觉醒了唯一隐藏职业》第 54 集，仅用于 Android 原生库对照；也是该项目历史验收样本。

| 实测组合 | 样本 | 结果 |
| --- | --- | --- |
| openhongguo 主线 Go 签名 | 前 3 集 | 3/3 返回 `ANONYMOUS_LIMITED`，无模型 |
| 主线 + PR #16 + PR #17，Go 签名 | 前 3 集 | 批量 POST、video_tab GET、旧单集接口均空 |
| 同一组合 + unidbg 原生签名，sinfonlinea | 前 3 集 | 原生签名服务返回成功，播放仍 200/0B |
| 同一组合 + 原生签名，sinfonlineb | 前 3 集 | 搜索 200/68413B，全部播放请求 200/0B |
| PR #16 + 原生签名，恢复原 player/v1 路径，sinfonlinea | 前 3 集 | 搜索 200/63473B，全部播放请求 200/0B |
| dddmiku Android 原生 JNI 实现 | 全部 4 集 | 0/4 得到可播放模型；批量 POST 报 HTTP 200 无响应，随后 GET 仍失败 |

SDK 探针每次运行用一个新的内存匿名设备会话，三个分集复用该会话；关闭设备重置、外层重试设为 1。SDK 自带的批量空响应重试仍保留。没有使用个人登录态。原生服务成功生成签名并且搜索成功是有效对照，但不证明播放端签名必然被接受。

Android 对照在 ARM64 Android 36 模拟器中使用 `cn.hongguo.tv.qa`。测试开始断言未登录。为适配本机已有构建工具，仅在临时克隆中将 AGP 7.4.2/compileSdk 33 改为 AGP 8.11.0/compileSdk 36、启用 BuildConfig；播放和签名代码未修改，附加一个只读模型探针。沿用上游公开包的匿名设备配置，未注册或访问个人账号。没有取得模型，因此没有进入下载、解密或实际解码；不将探针构建成功算成播放验收通过。

## 可复查证据

本机证据目录：`outputs/issue102-cdn/`（仓库忽略的输出目录）。保留了请求方法、主机、路径、HTTP 状态、响应字节数及断言结果，不保存完整签名 URL、签名头或媒体密钥到该目录。

- `openhongguo-probe.go`：SDK 探针，含搜索对照和 3 个分集。
- `openhongguo-go-probe.log`、`openhongguo-pr16-pr17-go.log`：Go 签名结果。
- `openhongguo-pr16-pr17-native.log`、`openhongguo-native-b.log`、`openhongguo-native-original-path.log`：原生服务及路径/域名对照。
- `Issue102ProbeTest.java`、`native-jni-probe.log`、`native-jni-sanitized.log`：Android 探针及 4 集失败证据。

临时研究克隆位于 `/tmp/hongguotv-issue102-cdn/`；SDK 基线及补丁在上表标明。运行命令为 `go run ./cmd/issue102probe [本地原生签名服务地址]`，可用 `PROBE_API_ORIGIN` 指定 API 域名。Android 命令为 `adb -s emulator-5580 shell am instrument -w -r -e class cn.hongguo.tv.media.Issue102ProbeTest cn.hongguo.tv.qa.test/android.test.InstrumentationTestRunner`。

## 19:13 时的后续修复验收条件

能够处理 #102 的非官网方案必须先在当前网络取得正确分集的 App 模型，或从独立片库准确找到相同剧和分集。随后验证完整时长、实际解码、后段分集、切集以及 Android 5/6 兼容性，才适合合并发布。现阶段不能把重复注册、增加重试、换 CDN 主机、引入较大的原生库当成已修复方案；也不能从这些空响应推导出唯一根因。

## 19 时复查：新增项目与实际对照

用户要求继续追踪开源更新。本轮在北京时间 19:03 起重新检查提交、开放 PR 和发行记录，新增以下有实质差异的参考实现。这里的“上游成功”指维护者记录，和本机复现结果分开列示。

### android-cast：当天 V5 GET 修复

[skydeadpeng/hongguo-android-cast 的 53735e4](https://github.com/skydeadpeng/hongguo-android-cast/commit/53735e4aa097) 在 11 月 23 日 15:22 提交，随后发布手机 0.12.90 / 原生 TV 0.1.40。

- 主路改为 V5 无正文 video_tab GET，保留 POST 和旧 GET 作为后备。
- 修正其 JS Medusa 第 9 字段的旧常量 `67503104` 为 `0x040a0300`。本项目 Kotlin V5 已经使用正确值，不能把这处修正直接套用为 #102 的根因。
- GET 保留 `x-ss-stub=MD5(空字节)` 和 Content-Type；本项目当前 GET 会移除这两个头。这是本轮实际进行 A/B 的差异。
- 上游[发行记录](https://github.com/skydeadpeng/hongguo-android-cast/blob/640b7fd992ba/docs/android-release-2026-11-23.md) 记载模拟器《家犬之谜》起播、桌面取到 5 档。

本机结果：

1. 使用其当前 JS 注册与签名代码，三个分集分别保留/移除 GET 的摘要及 Content-Type，共 6 次请求，均 `200 / 0B`。两个问题样本为修仙第 1 集、全球死考第 153 集；第三个 ID `7700946421087751193` 取自上游测试向量，仅作额外请求样本，不宣称它就是上游已验收的《家犬之谜》。
2. 四条完整 Medusa 黄金向量测试通过，说明本机运行的 JS 实现与该项目 Java 向量一致；这不是线上签名被接受的证明。
3. 编译原生产 Java 类，运行其 `RegisteredPlaybackModel` + 原 `ModelHttpTransport`（OkHttp）和 `UpstreamDeviceRegister`。同一新注册匿名身份复用到前三个问题分集，V5 GET、V5 POST、旧 GET 共 9 次播放请求，全部 `200 / 0B`，均 `APP_EMPTY_RESPONSE`。使用 speculative 模式关闭失败后的注册轮换，未改签名或请求构造。

### jycao2：四签名头与参数对齐

[jycao2/hongguoTV 的 0e71680](https://github.com/jycao2/hongguoTV/commit/0e71680d08d2739f6bd4646ff0ae3c15682e9544) 在 11 月 23 日 02:37 提交：PlayerSigner 增加 X-Gorgon，与 Khronos/Ladon/Argus 组成四头；POST device_level 改为 3；POST UA 版本改为 74532。

其[研究记录](https://github.com/jycao2/hongguoTV/blob/63ef594d767d5c9eff835dd91211e1790e144e8a/docs/research/2026-11-23-hongguo-streaming-solutions.md) 报告探针取得 1080P，同时明确没有严格 A/B、服务端状态也可能变化。

本机运行当前源码：

- 三个问题分集使用同一新注册匿名身份，原版四头 POST 与仅去掉 Gorgon 的对照共 6 次，全为 `200 / 0B`。level=3 和 UA=74532 保持上游当前值。
- 另跑一次真正的 `ContentRepository.stream()` 入口，避免只验证后备 POST：前置 video_tab GET 带 Gorgon，仍 `200 / 0B`，随后按其策略抛 `PlaybackThrottledException`，没有走到媒体下载。探针禁止请求 hongguoduanju.com。
- Gradle/JVM 探针构建成功，在线断言失败；不能写成“测试通过”。

### Adsryen/zhenguojian：Chrome TLS 对照

此“真果鉴”是 [Adsryen/zhenguojian](https://github.com/Adsryen/zhenguojian)，与此前查到的 kuoluoss 同名项目不同。其 Go App 取流使用四头签名和 `tls-client` Chrome 150 连接，主路为 `/novel/player/multi_video_model/v1/`。该传输实现与本项目 OkHttp/此前 Go 标准 HTTP 对照不同，值得单独实测。

本轮直接运行其 `guoapp-src/native/core`，新建独立临时资料并注册匿名身份。三个问题分集分别用原 Chrome 150 传输和底层标准 HTTP 传输，使用同一设备、原请求构造和 level=3，共 6 次，全部 `200 / 0B`。限定单次 App 模型请求，没有调用官网、外部解析或低清兜底。因此还不能认定引入 Chrome TLS 库能修复 #102。

其源码另留有外部解析接口 `djapi.999888456.xyz`。仅以一个公开分集 ID 检查连通性，Python/OpenSSL curl 均在 TLS 阶段失败（no application protocol），未取得 HTTP 响应或媒体。不能把这条线路当作已验证备用源。

### 其他更新与结论

- `openhongguo` 主线仍停在 11 月 18 日。新开放的 [PR #22](https://github.com/beilunyang/openhongguo/pull/22)（11 月 23 日 18:50 更新）增加调用方自定义 `FallbackPlaybackProvider`，没有随附可用片源，验收为本地夹具；它是扩展入口，不是现成的播放恢复服务。
- `tsukiyo-zero` 0.1.37、`guoapp-modules/hongguo` 1.0.7、`dddmiku` 1.3.5 的播放相关基线未比前轮更新。
- `jackotom` 新提交为 8.0 验收与发布记录，取流仍含已排除的官网方案。

本轮确实发现了新的非官网修复提交，并复查了源码和真实请求，但没有在当前环境恢复播放。结果仅覆盖本机网络出口和新注册匿名设备；不否定上游其他设备的成功记录，也不证明唯一根因是 IP、身份或签名。不向产品加入没有证实有效的协议改动。

新增探针、脱敏日志和精确源码版本保存在本机 `outputs/issue102-cdn/refresh-1900/`：`sky-v5-probe.log`、`sky-v5-vectors.log`、`sky-jvm-probe.log`、`jycao-production-entry.log`、`zhenguojian-chrome-probe.log`。四头 POST A/B 的具体结果按本轮终端输出记录；对应首次构建日志与探针源码一并保留。没有提交、推送、发布或发 issue 评论。

## 19:38 正向突破：真实 App 签名与 CDN 媒体

参考 [yinjg1997/hongguo](https://github.com/yinjg1997/hongguo) 的 App 签名服务思路，在任务专用 Android 36 ARM64 Google APIs 模拟器 `Issue102Official` 上安装 `com.phoenix.read` 7.4.5.32。安装包来自应用汇商店的公开下载入口，包版本、APK 签名校验通过；签名证书 SHA-256 为 `1fe01f1425c72dc1cd158e05cb696cb9fc17259a70f9d233fc48e8e42e6510ec`。没有登录账号，没有使用个人设备或既有登录资料。通知、相册权限均未授予。

官方 App 的匿名推荐页首先出现可变视频画面；随后进入完整剧集，播放器报告 1,229.4 秒时长。通过观察本机测试进程，取得该模型的 1080P CDN 地址，下载前 4 MiB，使用模型提供的 CENC 播放信息解码前 5 秒成功。这个对照证明了同网络下存在真实可播放的 App/CDN 来源。部分 ByteVC2 内容在模拟器画面上有花屏，后续验收因此使用兼容编码的完整文件和 FFmpeg，不将模拟器所有画面都宣称验收通过。

### 请求链和有效性边界

1. 在已初始化的 App 进程内调用 `NetworkParams.addCommonParams()`，取得当前匿名设备的公共参数。
2. 将真实 gzip 正文字节的 MD5 与请求 URL 交给 `NetworkParams.tryAddSecurityFactor()`。只需保留当前 App 返回的 `X-Khronos / X-Helios / X-Medusa` 三头。
3. 从电脑通过普通 curl POST 到 `https://reading.snssdk.com/novel/player/multi_video_model/preload/v1`，正文使用 `mixed_video_id_map`、`video_platform=1024`、`device_level=2`、`dr_scene=preload`。响应返回对应分集的 `video_model`。
4. 从模型选择兼容编码的 CDN 地址。仅有 ByteVC2 时，使用模型随附的 `fallback_api` 请求 `codec_type=1`，取得 H.265 档位；这属于 App/VOD 编码回退，没有访问官网播放器。其 URL 封装解码沿用 [guoapp-modules/hongguo](https://github.com/guoapp-modules/hongguo) 的公开实现。
5. 下载 CDN 完整文件，核对文件字节数，并以 `ffmpeg -xerror` 全程解码；加密媒体使用当前模型随附的播放信息。

观察到的 `video_pack_fields/video_playback` 请求主要用于评论、弹幕和互动等附加字段，**本次没有把它认定为取流接口**。真正取得模型的是上面的 `multi_video_model` 请求。

### 三个问题样本的独立脚本验收

以下为 19:38 使用仓库脚本重新运行的结果，和前面手工探针分开记录。每个分集只请求一次模型，无设备轮换、无官网请求。

| 分集 | 模型响应 | 媒体 | 完整下载字节 | 全程解码 |
| --- | --- | --- | --- | --- |
| 全球死考六季第 153 集 `7704857946630409240` | 200 / 16,227 B | 1920×1080 H.265 + AAC，40.519 秒；经 App codec 回退 | 2,887,938 | exit 0，错误日志 0 B |
| 谁让这个文盲修仙七季第 1 集 `7709114791150767128` | 200 / 24,516 B | 1920×1080 ByteVC1/HEVC + AAC，89.281 秒 | 16,405,714 | exit 0，错误日志 0 B |
| 和离后我带女儿横扫魔域三季完结篇第 1 集 `7709197486451084312` | 200 / 25,045 B | 1920×1080 ByteVC1/HEVC + AAC，203.872 秒 | 39,674,748 | exit 0，错误日志 0 B |

三个样本模型中请求的分集 ID 均匹配，下载字节数与对应档位模型声明一致。前一轮手工请求也取得相同分集和时长，并完成全文件解码；本次不是只验证 HTTP 200、文件头或预告画面。

### 签名与接口对照

- 真实 App 的完整六头：三个分集均成功。
- 同一真实 App 身份与运行时，仅保留 V5 三头：全球死考第 153 集成功；整理后的三头脚本三个分集全部成功。
- 同一真实 App，保留旧 `Ladon/Khronos/Argus/Gorgon` 四头、移除 V5 两头：全球死考第 153 集 `200 / 0B`。
- 使用真实 App 的公共设备参数，但换成 android-cast 当前公开 JS V5 签名：和离第 1 集 `200 / 0B`，真实 App 签名则成功。该 JS 会调整 `_rticket` 以选择分支，不能把这称为完全相同 URL 的逐字节签名对照。
- 真实 App 签名改回 `/novel/player/multi_video_model/v1` 旧路径，全球死考第 153 集仍成功。因此单纯替换 `/preload` 路径并不是本次成功的充分解释。
- 本轮真实 App 的 Medusa 头长度约 1,300 字符，公开 JS 约 700 字符。这证明输出内容差异明显，但长度本身不证明哪个具体字段是根因。

现有证据将问题范围缩小到公开签名实现和真实 App 签名/运行时信息的差异。仅注册新设备、仅更换 CDN 域名、仅增加旧四头并未在本机恢复。尚未还原缺失的具体字段，也没有证明把原生 `.so` 搬到 TV 就能得到同样效果；此前 unidbg/JNI 失败对照仍然成立。

### 可复现脚本与落地建议

新增 `scripts/issue102_app_stream_probe.py`。依赖 Python 3.10+、`frida-tools`、`cryptography`、curl 和 FFmpeg；本次实测 Frida 17.27.4，脚本显式加载 Frida 17 的 Java bridge。需要自己的可调试/root 隔离 Android 实例、已初始化的测试 App 及匹配的 Frida server，将服务通过 adb 转发到本机 `127.0.0.1:27042`。脚本不会自动安装应用、登录、启动公网服务或修改设备身份。宿主系统 Python 未安装 Frida，本次使用独立 venv，不修改系统 Python。

准备 Python 环境后，使用该环境的 Python 运行：

```sh
python3 -m venv /tmp/hongguo-stream-probe-venv
/tmp/hongguo-stream-probe-venv/bin/pip install frida-tools 'frida==17.27.4' cryptography
```

```sh
/tmp/hongguo-stream-probe-venv/bin/python scripts/issue102_app_stream_probe.py \
  --adb /path/to/adb \
  --serial emulator-5584 \
  --output outputs/issue102-cdn/app-oracle \
  --verify
```

默认验证以上三个分集；可重复传 `--episode <公开分集ID>`。不带 `--verify` 时只解析模型和可用 CDN 地址。输出 `summary.json` 为不含签名/密钥的结果，`*-stream.private.json`、模型及媒体为本地私有调试资料，保存在 Git 忽略目录并以仅当前用户可读写权限创建。不要将完整签名、设备标识、临时 URL 或播放密钥复制进 issue 或提交。

目前可落地的方案是**运行一个 Android App 签名服务，由 TV 请求模型后直连 CDN**。本次做到了可调用的解析与媒体验证脚本，没有实现常驻 HTTP 服务或 TV 配置界面。实际运行还需处理 App 生命周期、会话有效期和并发；TV 实机切集、长时间运行和 Android 5/6 尚未验收。纯客户端修复可以继续以这些成功请求为对照研究签名，但不应把现有 PoC 宣称成已经发布的修复。

此阶段测试结束后停止任务的观察脚本、Frida 端口转发和官方 App 模拟器，保留隔离 AVD 和本机证据用于复现。当时产品代码和主工作区既有改动均未修改，没有提交、推送、发布或发 issue 评论。

## 20:06 TV 内置签名移植与实际播放验收

### 发现的调用差异

真实 App 7.4.5.32 的 ARM64 `libmetasec_ml.so` 与前面 dddmiku 对照包的库 SHA-256 相同：`c6831ab265a0cb76dacfa3b7041e9ea0f706490106a74e12f39058671cc14fe3`。因此之前“已有 JNI 项目也失败”不能简单归结为二进制过期。

观察真实 App 的 JNI 入口后，确认初始化管理器和签名句柄不是同一个对象：

1. Java 桥接链为 `com.bytedance.mobsec.metasec.ml.MS -> ms.bd.c.q2 -> ms.bd.c.k3`。
2. `0x1000003` 传入应用上下文，`0x4000001` 初始化 SDK，`0x4000002` 返回全局管理器。
3. SDK 回调 `MS.b(0x2000001, ...)` 提供另一个签名句柄。匿名设备的 device ID / install ID 更新仍使用全局管理器的 `0x2000002` / `0x2000003`。
4. 签名使用 `0x3000001`、回调提供的签名句柄、最终 URL 和请求头数组。数组中的 `X-SS-STUB` 是实际发送的 gzip 正文字节的 MD5。此前的 `0x2000006 + 全局管理器 + 原始正文` 不能视为同一调用。
5. 返回值只取 V5 的 Khronos / Helios / Medusa 三个签名头，设备身份由本项目独立匿名注册，不复制官方 App 的设备会话。

先修正临时 JNI 对照包，在官方 App 已停止的情况下成功取得三个模型；随后换成独立新注册的匿名身份，三个模型仍成功。再将同一对照放到没有官方 App 的 Android TV 模拟器上，仍为 3/3。最后才接入本项目，避免把外部探针的成功当作 TV 产品验收。

### 本项目接入与边界

新增 `NativePlaybackSigner` 和最小 JNI 桥接类，通过 `ContentRepository` 注入正式剧集播放器及首页预览。SDK 在 TV 进程内加载；签名调用串行化，初始化惰性执行，SDK 与初始化资料在构建时打包，不在运行时下载。Git 源码不包含原生库或捕获的匿名设备标识。

第一次本项目取流仍出现 `200 / 0B`。补齐 74532 公共参数并调整 User-Agent 后通过正式播放器验收；这两组调整一起进行，未证明某一个字段就是全部根因。当前公共参数沿用成功对照中的公开设备描述，不把它宣称为适合所有旧电视的最终配置。

请求使用 App 的 `multi_video_model/preload/v1`。没有兼容档位时，跟随模型给出的 VOD `fallback_api` 协商 codec 1/3，复用已有媒体解包和播放逻辑。VOD 请求不转发签名头或账户 Cookie；没有增加官网接口或网页播放器。

`-PnativeSigningSdkDir=...` 显式启用此研究构建，只生成 ARM64 platform 包；未启用时继续使用原有 Kotlin 签名。`legacyAudio` 与此选项组合会拒绝构建，避免把未经验证的旧平台支持混入结果。签名库 SHA-256 在准备脚本和 Gradle 构建阶段均检查。

### 验收证据

环境：任务专用 Android TV API 36 ARM64 模拟器 `Issue102Peer`，官方 App 已卸载，未安装/运行 Frida，未使用 root 或个人登录态。测试直接调用本项目 `MainActivity` 的生产播放入口与 ExoPlayer，断言正确媒体 ID、第一帧已渲染、画面尺寸、播放状态和进度。

| 用例 | 结果 |
| --- | --- |
| 全球死考第 153 集 `7704857946630409240` | 1920×1080，连续播放超过 5 秒；定位到 20 秒后推进超过 22 秒 |
| 切换第 154 集 `7704857947322469400` | 正确切集，1920×1080，播放超过 5 秒 |
| 修仙第七季第 1 集 `7709114791150767128` | 1920×1080，播放超过 5 秒 |
| 和离第三季第 1 集 `7709197486451084312` | 1920×1080，播放超过 5 秒；暂停保持、恢复后继续推进 |
| 清空本项目调试包数据，再跑以上全部用例 | PASS；重新匿名注册，无既有模型/会话缓存依赖 |
| 核心与 Android 单元测试 | 411 + 348 = 759，失败 0、跳过 0 |
| Android debug lint | 通过 |

两次本项目真实播放记录为 `outputs/issue102-cdn/native-port/own-tv-live2.log` 和 `own-tv-cold-live.log`。更早的 `own-tv-live.log` 是首次失败对照，保留但不计入通过次数。`own-tv-final-checks.log` 是单元测试和 lint 构建记录；`summary.json` 汇总 APK 哈希和结果。此次 TV 验收是实际解码与短时交互，不代替前阶段完整文件解码，也不代表所有剧集或长时间连续播放已验收。

### 本机复现与测试包

以下从隔离仓库根目录执行。本机使用 JDK 17、Gradle 8.14.3 和已有 Android SDK。SDK 配置输入来自前述 dddmiku 公开源码对照，准备脚本会移除所有设备身份槽位；运行时再填入本项目新注册身份。

```sh
python3 scripts/prepare_native_signing_sdk.py \
  --apk /tmp/hongguotv-issue102-cdn/official-app.apk \
  --metadata /tmp/hongguotv-issue102-cdn/native-tv/app/src/main/assets/sdk-metadata.bin \
  --output "$PWD/outputs/issue102-cdn/native-port/sdk"

./kotlin-tv/gradlew -p kotlin-tv \
  :core:test :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleDebugAndroidTest \
  -PnativeSigningSdkDir="$PWD/outputs/issue102-cdn/native-port/sdk"
```

在任务专用 TV 测试设备安装 APK 与 AndroidTest APK 后，先确认 `adb shell pm path com.phoenix.read` 没有结果，检查进程中没有 Frida，再执行：

```sh
adb shell pm clear com.hongguotv.nativeapp.debug
adb shell am instrument -w -r -e nativeSamples true \
  com.hongguotv.nativeapp.debug.test/com.hongguotv.nativeapp.LivePlaybackInstrumentation
```

必须看到 `PASS native TV playback`；仅 adb 返回 0 不算通过。清理命令只针对隔离测试设备上的 `.debug` 包。

已验收 APK：`outputs/issue102-cdn/native-port/hongguoTV-issue102-native-arm64-debug.apk`，17,716,030 字节，SHA-256 `6cbd856da644a8ed152616c0fca3855e4c00a44929bf43ff5e7a53e9ef0e8a6f`。该包与正式版包名不同，可独立安装；其中包含本机供给的原生 SDK。SDK 输入与 APK 均保存在 Git 忽略的本地输出目录。

尚未验收 ARM32、Android 5/6、真实电视硬件、发布压缩构建及长时间稳定性；没有完成纯 Kotlin 签名算法还原。此轮没有提交、推送、发布、购买服务或向 issue 发评论。

## 20:40 纯 Kotlin 实现可行性与成本评估

用户进一步要求评估完全用 Kotlin 实现签名的成本。本轮增加明确启用的 `SignatureParityProbe` 测试入口，没有修改生产签名算法，也没有把未验证的字段替换放进已验收 APK。

### 固定请求、单独替换签名头

在同一 Android TV 测试实例中独立匿名注册，使用当前 App 公共参数与 gzip 正文。先由项目 Kotlin 签名器选择其已实现的分支，再让原生 SDK 对这个最终 URL 签名。断言二者 URL 和 Khronos 时间戳相同，全部请求复用同一设备身份、正文、User-Agent、OkHttp 传输及非签名头。每个分集先请求原生基线，分别替换两个因子，最后重复原生基线。

| 签名组合 | 全球死考第 153 集 | 和离第 1 集 |
| --- | --- | --- |
| 原生 Helios + 原生 Medusa | 200 / 16,190 B，有对应模型 | 200 / 25,338 B，有对应模型 |
| **Kotlin Helios + 原生 Medusa** | 200 / 16,190 B，有对应模型 | 200 / 25,318 B，有对应模型 |
| 原生 Helios + **Kotlin Medusa** | 200 / 0 B | 200 / 0 B |
| Kotlin Helios + Kotlin Medusa | 200 / 0 B | 200 / 0 B |
| 重复原生组合 | 200 / 16,190 B，有对应模型 | 200 / 25,128 B，有对应模型 |

十次请求没有更换设备、使用官网、下载媒体或操作账号。这是签名与模型对照，不新增整集解码/播放验收结论。它把这两个样本的失败范围收敛到 Medusa；不能推导所有接口都只校验这一个头。

### Medusa 内部差异

根据本项目已有 GPL `Signer.kt` / `AesV3.kt`，编写本地诊断逆变换：四套分组变体的 64 个合成块往返通过，字节混淆层三个不同长度样本往返通过。解开本轮原生与 Kotlin 的四个签名，使用捕获的随机量重新封装，四个均与原始 wire bytes 完全一致。工具用于局部分析；这不等于已经能独立生成通过服务端校验的新 Medusa。

- 同一分集的身份、版本、请求摘要前缀、时间戳等公共字段匹配。
- **F13 的前 16 字节在两个样本中完全相同，后 4 字节不同。** 因此现有分支的主要摘要计算可以复用；尾部计算/状态来源仍需查明，不能把它直接认定为已定位的唯一错误。
- 全球死考的原生消息为 814 B，和离为 766 B；Kotlin 都是 450 B。长度差本身不是失败根因。
- F10、F15、F21、F23、F24 存在差异。F23 的原生设备/运行环境结构有额外字段，F24 的运行状态 JSON 也有额外键。部分字段本来随随机量、计数和运行状态变化，不应以“复制一次捕获值”作为实现。
- 现有 F13 回归向量只断言前 16 字节；这些测试通过不能证明真实 SDK 的完整 20 字节一致。

相比全面重写 SDK，当前可复用：匿名设备注册、请求构造、Khronos、已在线验证的 Helios、Medusa 外层封装，以及当前选定分支的 F13 主摘要。主要未知是 F13 尾部、必要环境字段、状态演变，以及服务端实际要求哪些字段。

### 外部实现核查

核查 [it00021hot/hongguo-desktop 的 F13 实现](https://github.com/it00021hot/hongguo-desktop/blob/main/crates/hongguo-signer/src/medusa_v5/field13.rs)：其 Rust 实现从 4,501,136 字节的原生库读取数据、校验 SM3、应用 ELF 重定位，并以有界 VM 解释字节码；不是已经把全部分支化简成普通算法的 Kotlin 等价物。其[研究记录](https://github.com/it00021hot/hongguo-desktop/blob/main/docs/req-hg-003-playback-parity.md)报告覆盖 16 个选择器，但本轮没有在本机复跑该项目，不能把其历史播放记录当作当前验收。

本项目目前通过调整请求票据选择已实现的分支。本轮原生签名对同一 URL 的成功说明，这种 URL 调整至少在这两个样本中并未阻断取流；尚无证据要求为 TV 播放立即还原 SDK 全部 16 个分支。

### 工程工作量估算

以下是依据本轮差异范围的工程估算，不是完成期限承诺：

| 目标 | 预估投入 | 主要不确定性 |
| --- | --- | --- |
| 补差实验：定位 F13 尾部与服务端必需字段 | 先投入 1–2 人日 | 差异字段不一定都是必需项，需要独立生成和逐项对照 |
| 当前播放路径的纯 Kotlin 最小可用版 | 若字段可局部还原，合计约 3–7 人日 | 不能依赖捕获的 SDK 会话/动态字段值 |
| 可发布的替换实现，含重启、连续切集和不同 Android 版本验证 | 合计约 1–3 人周 | 会话状态、长期稳定性、旧平台和服务端变化 |
| 还原全部分支及通用 SDK 行为 | 数周或更长，当前无法可靠封顶 | 字节码还原范围明显扩大；并非已证明的 TV 播放必要条件 |

推荐先以最小路径验证为投入边界：**完全不加载 `.so`，独立新注册与冷启动，三个问题样本和后段切集均取得模型并实际播放**。达到后再做平台兼容与长期验收。当前仍保留原生实验包作为可用基线和研究对照，不能将本轮解包或头替换成功宣称成纯 Kotlin 修复已完成。

### 复现与证据

构建并安装原生调试包和最新测试 APK 后：

```sh
adb shell am instrument -w -r -e signatureParity true \
  com.hongguotv.nativeapp.debug.test/com.hongguotv.nativeapp.LivePlaybackInstrumentation
```

`DONE signature parity` 表示实验完成，逐行 `model` 字段表示结果。脱敏日志、字段差异、诊断脚本及构建记录保存在 `outputs/issue102-cdn/kotlin-feasibility/`。完整签名仅保存于调试应用私有目录和本机隔离的 `private/` 子目录，权限为 0600，不进入 Git 或公开报告。

## 纯 Kotlin 实现与验证（2026-11-23）

### 逐字段对照定位到 F21

沿用上一阶段已经确认相同 URL、gzip 正文、匿名身份及 Khronos 的签名对，独立解包/封装 Medusa，只替换指定字段。所有组合通过同一个宿主 curl 传输，前后保留原生基线。首个样本为全球死考第 153 集。

| 组合 | 模型结果 |
| --- | --- |
| 原生 Medusa 解包后原样封装 | 200 / 16,188 B，成功 |
| 原 Kotlin Medusa | 200 / 0 B |
| 原生 Medusa 单独换成 Kotlin 的 F13、F10、F15、F23 或 F24 | 每组均成功 |
| 原生 Medusa 单独换成 Kotlin 的 F21 | 200 / 0 B |
| Kotlin Medusa 仅换成原生的 F13 | 200 / 0 B |
| Kotlin Medusa 换入 F13、F10、F21 | 200 / 16,188 B，成功 |
| 最后重复原生基线 | 200 / 16,188 B，成功 |

随后收窄到**只改 Kotlin 的 F21**：

| 样本 | 原生基线 | Kotlin 仅改 F21 | 再次请求 Kotlin 仅改 F21 |
| --- | --- | --- | --- |
| 全球死考第 153 集 | 16,188 B | 16,228 B | 16,188 B |
| 和离第 1 集 | 25,182 B | 25,128 B | 25,316 B |

以上全部为 HTTP 200 且含正确分集的模型。SDK 与先前真实 App 的 F21 原始 protobuf 数值都是 624，即 zigzag 有符号值 **312**；原 Kotlin 为 **377**。当前请求条件下，这一个字段的替换足以恢复这两个模型样本。其内部 SDK 语义仍未知，不将它命名为已证实的版本号、校验值或设备状态。

F13 后四字节与运行环境字段的差异仍然存在，但完整 Kotlin 请求通过证明它们不是这组样本恢复取流所必需的移植内容。此前按全面还原动态字段给出的工作量估算，不再代表本次最小修复的实际范围。

### 最终生产代码

- `V5Constants.field21 = 312`；`Signer.medusaV5` 使用该已验证常量，保留现有独立 Kotlin 摘要、封装和有界票据选择。
- `MobilePlayback.signer` 默认选择 74532 当前请求参数、`preload/v1` 和当前 User-Agent；仍签名实际发送的 gzip 正文字节。
- 默认模型主机为 `reading.snssdk.com`，显式传入的备用 App 主机仍生效。修正了原生实验阶段固定主机导致备用请求仍落到原主机的问题，并保留对应回归测试。
- ByteVC2-only 模型通过模型提供的 VOD 编码接口取得兼容档位；VOD 不携带签名头或账号 Cookie。
- 删除原生签名适配器、三个 JNI 桥接类、可选 SDK 构建开关、打包路径及保留类名规则。`MainActivity`、首页播放和仓库注入实验接线恢复为原有默认入口；核心模块不再为 JNI 公开额外签名接口。
- 原生研究材料归档至忽略的本机输出目录。运行和普通构建均不需要原生库、SDK 配置、Frida、模拟签名服务或官方 App。完整协议常量由现有 Kotlin 源码提供；没有复制捕获的设备会话或动态字段到产品。

### 回归与实际播放

新增公开合成身份的完整签名 wire 固定向量，经过独立解包和逐字节重封装核验，防止 F21 或整个签名封装再次回退。原有核心测试、首页预览与播放恢复用例跟随实际新路径更新；设备身份共享、空响应处理、取消和备用主机行为继续覆盖。

清理 app 构建目录后，`:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` 全部成功：核心 **412** 项、Android **348** 项，合计 **760** 项，失败、错误、跳过均为 0。

在没有官方 App 和 Frida 的 ARM64 Android TV API 36 实例上，先卸载旧调试包，安装无 SDK 初版并完成真实播放验收；删除 JNI 代码后，再安装最终 universal 包、清空该调试包数据，重复全部验收。最终包另做了一次保留数据、强制停止进程后的重启复验，同样通过。均使用生产 `MainActivity` / ExoPlayer，三个问题分集和下一集显示 1920×1080，播放超过 5 秒；全球死考第 153 集定位后推进超过 22 秒；切集、暂停恢复均通过。

最终 APK 的 ZIP 中没有 `lib/` 原生库条目，没有 SDK 配置；DEX 中也没有原生签名适配器和 JNI 桥接类。APK 签名校验通过。以上证明本次签名和取流独立于原生 SDK；不将模拟器短时播放外推为所有电视、所有剧集或长期稳定性保证。

### 当前复现与交付

从仓库根目录正常构建，不传 `nativeSigningSdkDir`：

```sh
./kotlin-tv/gradlew -p kotlin-tv \
  :core:test :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

在任务测试设备安装 debug 与 AndroidTest APK 后：

```sh
adb shell pm clear com.hongguotv.nativeapp.debug
adb shell am instrument -w -r -e issue102Samples true \
  com.hongguotv.nativeapp.debug.test/com.hongguotv.nativeapp.LivePlaybackInstrumentation
```

成功标志为 `PASS pure Kotlin TV playback`。脚本只清理隔离设备上的调试包；真实用户设备不需要为正常使用执行此命令。

本机交付包：`outputs/issue102-cdn/pure-kotlin/hongguoTV-issue102-pure-kotlin-debug.apk`，11,839,092 字节；SHA-256 `398f292c866b9698be8e34c5eb22f4f1f85a0b2722b2c4ad99ee22f135609c6a`。此包可与正式包并存，仍是 0.16.13 基线的调试修复包，没有发布新版本号。

同目录保存 `field-ab-summary.json`、`f21-ab-summary.json`、`pure-cold-live.log`、`final-cold-live.log`、`final-restart-live.log`、`final-clean-checks.log` 和 `summary.json`。原生研究对照的封装脚本只保留在本机研究目录，不进入运行时。以上是发布前研究阶段的记录；主工作区既有改动未触碰。

### 0.16.14 兼容版发布准备

版本递增为 0.16.14 / versionCode 35，保留 Android 5（minSdk 21）兼容包，以及 Android 6+（minSdk 23）通用、ARM32 和 ARM64 包的原证书发布流程。Android 5 包继续包含原有 AAC 音频解码扩展；“纯 Kotlin”指签名实现，不表示删除旧系统所需的音频库。

同一纯 Kotlin 修复包在独立 Android 6 / API 23 ARM64 模拟器上冷启动复测通过：三个问题样本及下一集均解出 1920×1080 画面并推进超过 5 秒，拖动至 20 秒后继续推进，切集和暂停恢复通过。该测试没有安装官方 App，不依赖签名服务。34 项发布工具契约检查通过。正式交付仍必须完成四包构建、Release lint、原证书/v1 签名、版本、最低系统版本、ABI 元数据及发布后资产哈希校验；不能将模拟器结果当作全部电视真机的验收。
