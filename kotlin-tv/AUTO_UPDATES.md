# 发布与自动更新

唯一发布渠道为 N3urda/hongguoTV Releases。main/PR 验证不会发布；仅
`kotlin-vMAJOR.MINOR.PATCH` 标签触发正式发布，标签必须匹配源码 versionName。
发布前递增 versionCode、提交改动并完成评审。已发布资产不可变；更正安装包布局必须递增补丁版本和 versionCode，禁止重开旧 Release、覆盖旧附件或移动旧标签。

## 强制发布清单（0.16.4 起）

- `android6.apk`：Android 6+（API 23），默认构建，不依赖 FFmpeg，没有原生 `.so`，支持包括 ARM32 在内的平台。
- `android5.apk`：Android 5 兼容包（API 21），使用 `-PlegacyAudio=true` 构建，包含四种 ABI 的 FFmpeg，仅 API 21–22 启用扩展音频解码。

- `android6-arm32-v7a.apk`：Android 6+ ARM32，来自 Gradle armeabi-v7a 分包。
- `android6-arm64-v8a.apk`：Android 6+ ARM64，来自 Gradle arm64-v8a 分包。

每次发布必须包含以上四个 APK，不能以通用包替代 ARM32/ARM64 交付，也不能仅复制或重命名通用包冒充分包。
Android 6+ 包没有原生库，ARM 两包的可执行代码可能相同，不能通过 `.so` 目录判断分包是否正确；必须校验 Gradle output-metadata.json 中的 ABI filters。
四包使用相同包名、版本和正式证书。ARM32/ARM64 按 Android 用户态 ABI 选择，不能只看芯片位数。
通用 android6 包继续为 0.16.3 更新器提供兼容更新入口；不明确 ABI 时也可以使用通用包。
平台包输出在 `app/build/platform/`；兼容包在 `app/build/`。构建顺序先兼容版后平台版。
不要在两次构建之间执行 clean；兼容版 clean 会移除包含在内的平台版输出。

## 验证

需要 JDK 17、SDK 36、Build Tools 36.0.0；兼容版额外需要 NDK 27.1.12297006。

```sh
python3 -m unittest discover -s kotlin-tv/tools -p 'test_*.py'
for legacy in true false; do
  ./kotlin-tv/gradlew -p kotlin-tv :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug -PlegacyAudio="$legacy"
done
python3 kotlin-tv/tools/verify_audio_packages.py debug
```

配置原签名后用 `bash scripts/build-kotlin.sh` 构建并检查四种正式 APK。
签名来自本地未跟踪的 keystore.properties 或 HONGGUOTV_KEYSTORE、
HONGGUOTV_STORE_PASSWORD、HONGGUOTV_KEY_ALIAS、HONGGUOTV_KEY_PASSWORD 环境变量。
CI 另外需要 HONGGUOTV_KEYSTORE_B64。不可更换原签名或提交签名材料。
正式证书 SHA256：`3579f7a8a91bbbc44688db60fb16a5561921784af23fa92f39b849086d76ca98`。

发布脚本逐个校验包名、版本、minSdk、原证书、v1 签名、原生库内容及输出元数据；
缺任一个包即拒绝发布。四种 APK、完整源码 ZIP（含 FFmpeg 源码与许可证）、
update.json 和 SHA256SUMS 均进行上传后的哈希、大小及状态校验。
已发布标签不可覆盖；异常保留草稿。fork/PR 不接触正式签名。

## 自动更新兼容

`update.json` 保留 schema 1 和指向 Android 5 兼容包的 `apk`，新增 `platformApk`。
新版客户端在 API 23+ 选择 platformApk，API 21–22 使用 apk；旧元数据没有
platformApk 时仍可读取。旧客户端忽略新字段，先更新到兼容包；后续更高版本
更新时才自动选择平台包。因此希望立即移除 FFmpeg 的现有 Android 6+ 用户应
手动同签名覆盖安装 android6.apk，无需卸载。不会自动重复安装同版本。

下载仍通过固定 HTTPS 源，校验大小、SHA256、包名、版本、最低 SDK 和安装签名；
由 Android 系统确认安装。issue #55 的真机崩溃尚未复现，移除 FFmpeg 是隔离措施，
构建和打包检查不能替代问题设备回测。

本地发布需干净工作区且 main/tag/HEAD 一致，再执行
`python3 scripts/release_tv.py publish kotlin-vX.Y.Z --local`。没有明确发布指令时不执行。

## 发布前后检查清单

1. 四包构建、单元测试和 Lint 通过；原签名、包名、版本、minSdk、FFmpeg 有无及 ABI 输出元数据全部核验，缺一个 ARM 分包即停止发布。
2. 只从与远程 main 一致的干净检出发布；版本号与标签一致。SHA256SUMS 必须覆盖四包、源码及 update.json。
3. 发布说明分别列出 Android 5、Android 6+ ARM32、ARM64 和通用包的用途，明确旧客户端更新迁移行为及未覆盖的设备测试。
4. 发布后回读附件数量、大小、SHA256、update.json 和 latest；保留旧版附件及校验值，不覆盖已发布版本。
