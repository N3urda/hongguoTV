# GitHub 发布与电视应用内更新

唯一发布渠道：[N3urda/hongguoTV Releases](https://github.com/N3urda/hongguoTV/releases)。不再同步到占位的 updates 仓库。旧正式版可能没有 update.json；此时新版客户端提示尚无可用自动更新版本，不代表新版本已发布。

## 验证与发布

- main 推送、所有 PR 和手动运行 `kotlin-native.yml`：离线发布合约测试、core 测试、lintDebug、assembleDebug，并上传开发 APK 与报告。开发包不能覆盖原正式签名安装。
- **仅推送 `kotlin-vMAJOR.MINOR.PATCH` 标签触发 `kotlin-release.yml`**，main 推送不发布。fork 不运行正式发布任务，PR 不接触签名 Secrets。
- 标签必须精确匹配 `kotlin-tv/app/build.gradle.kts` 的默认 versionName；versionCode 手动递增，不按 run_number 重写。当前源码为 0.15.1 / 20。创建标签前提交全部改动并完成评审及设备验证。
- 正式流水线先检查四个签名 Secrets，再跑 core 测试、Release lint 与正式构建。逐一验证三个 APK 的架构库、包名、版本、API21、原证书以及 v1 签名后才创建草稿。
- 必需产物：通用版 `android5.apk`、32 位 `arm32-v7a.apk`、64 位 `arm64-v8a.apk` 三个 APK、该提交完整源码 ZIP（保留 GPL 和第三方许可）、update.json、SHA256SUMS。上传后逐一核对 GitHub 返回的 SHA-256、大小、状态，再发布并验证 latest。未通过时保留草稿供排查，不冒充成功。
- 全仓库发布任务串行。已发布标签重跑不覆盖附件；同提交草稿可重试。旧版本、未知正式标签格式、低 versionCode、不同提交草稿或额外附件均拒绝发布，需维护者检查，不会自动删除。

## 首次启用需要原签名

在主仓库 Settings → Secrets and variables → Actions 配置以下四个 Secrets：

- `HONGGUOTV_KEYSTORE_B64`：原正式 keystore 的 Base64。
- `HONGGUOTV_STORE_PASSWORD`
- `HONGGUOTV_KEY_ALIAS`
- `HONGGUOTV_KEY_PASSWORD`

原证书 SHA-256 固定为 `3579f7a8a91bbbc44688db60fb16a5561921784af23fa92f39b849086d76ca98`。缺少任何项或证书不符直接失败，不生成新签名、不降级为 debug。密钥只恢复到 runner 临时目录，结束时删除，不上传密钥或签名配置。无需跨仓库 deploy key、PAT 或 TV_UPDATES_ENABLED。

维护者需要确保 Actions 可使用仓库 GITHUB_TOKEN 的 contents:write 权限，并保护 main、发布标签和 workflow 文件。不要将签名 Secrets 配置到不受信任 fork，批准或合并 PR 前应审查工作流变更。建议使用受保护标签规则并限制创建/移动正式标签。不得移动已发布标签或手工覆盖正式 APK。维护者的手工发布也应遵守版本递增与同签名约束。

本地验证（不需 Secrets、不发布）：

```sh
python3 -m unittest discover -s kotlin-tv/tools -p 'test_*.py'
python3 scripts/release_tv.py validate kotlin-v0.15.1
./kotlin-tv/gradlew -p kotlin-tv :core:test :app:lintDebug :app:assembleDebug
```

复现正式构建需 JDK 17、Android SDK 36 / Build Tools 36.0.0，在对应标签源码上配置自己的签名后执行 `scripts/build-kotlin.sh`。自己的签名无法覆盖原正式版；发布流水线仅接受原证书。不要传版本覆盖环境变量到正式流水线，脚本会核对 APK 与标签源码版本一致。首次正式发布前仍需原签名持有人配置 Secrets；本仓库源码本身不证明签名发布已成功。

## 电视上的行为与迁移

前台浏览每 6 小时检查 latest/download/update.json；首次延迟 8 秒，播放或离开前台取消请求。设置 → 版本与更新可手动检查、关闭自动下载。下载会验证 HTTPS、长度、SHA-256，以及 APK 包名、版本、最低 SDK 和已安装应用签名。不在 APK 保存 GitHub 令牌。

安装由 Android 系统确认，不是静默安装。GitHub 不可达、系统无安装器或禁止未知来源时不能完成更新。下载失败不影响已安装应用。

0.10.0 如实声明 minSdk 21，旧 0.9 更新器不识别该元数据，首次需手动同签名覆盖安装，请勿先卸载。最低 Android 5.0 不等于旧电视解码、音频、遥控器及安装流程已验收。公开搜索接口曾返回 HTTP 502 / signature_unavailable；本版改善本地解析和错误处理，不宣称上游搜索、播放已恢复。


## 本地签名发布（0.11.0 起）

Android 5 AAC 兼容库从固定版本源码编译；需 Python 3.12+、curl、make、JDK 17、
Android SDK 36 / build-tools 36.0.0 和 NDK 27.1.12297006。详见
`decoder-ffmpeg/README.md`。公开仓库的验证工作流仍保留。

在修复已合入远端 main、工作区干净、版本及原签名 APK 已验证后，把对应
`kotlin-vX.Y.Z` 标签推送到同一 main 提交，再执行：

```sh
python3 scripts/release_tv.py publish kotlin-vX.Y.Z --local
```

本地发布显式核验 canonical origin、远端 main/tag、三个 APK 的原签名、精确 ABI 集合、版本递增和
上传资产哈希；使用已登录的 gh，不需要把私钥或签名密码上传到 GitHub。
源码 ZIP 同时包含 FFmpeg 对应源码归档和重编译所需的桥接代码、脚本与许可。
仓库未配置签名 secrets 时，标签触发的云端发布不会成功；本地发布可独立完成。

正式构建由 `scripts/build-kotlin.sh` 和 CI 统一传入 `-PreleaseAbis=true`。发布前必须同时存在 `app-universal-release.apk`、`app-armeabi-v7a-release.apk`、`app-arm64-v8a-release.apk`，缺任何一个立即失败。通用包保留 ARM32、ARM64、x86、x86_64；两个 ARM 包只含各自架构。三个包均纳入 SHA256SUMS 和上传后校验。`update.json` 继续指向通用包，兼容现有自动更新客户端。无需额外的临时 Gradle 脚本。

## 0.13.1 下载加速与无打扰更新

版本信息和 APK 优先通过 `https://ghfast.top/` 代理公开 GitHub Release 文件，失败回退 GitHub 直连。版本检查每源最多 15 秒；APK 每源最多 5 分钟，连接超时 8 秒、无数据读取超时 15 秒。切换源重新下载，取消请求后不再尝试下一源。

仅允许固定 HTTPS 域名，镜像错误页面、长度异常或 SHA-256 不匹配会回退。安装前继续验证实际包名、版本与原安装签名。公开镜像不保证持续可用或国内各地区速度；旧客户端需先安装本版才能使用加速。

后台检查、下载完成和安装结果不主动弹窗；在设置 → 版本与更新中主动安装，仍由系统确认。
