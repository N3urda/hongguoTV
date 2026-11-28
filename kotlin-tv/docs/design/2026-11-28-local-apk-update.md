# 局域网上传更新

定制版本以手机遥控页面的「应用更新」替代上游在线更新。MainActivity 不再实例化 AppUpdater，也不展示检查更新、下载和代理线路选项。保留上游 AppUpdater / AppUpdates 源文件，避免删除整套上游实现造成同步冲突；同步后需确保入口仍为 LocalAppUpdater。

## 使用

同一局域网扫码，打开应用更新。已设置家长密码时先在家长设置验证；未设置时在电视允许本次上传，再点击上传并安装。只接收 200 MB 内单个 APK，包名、当前签名一致且 versionCode 更高。微信文件需先能被系统文件选择器访问。

32 KiB 分块通过现有带随机 URL、Origin 和请求头校验的遥控接口上传，逐块写私有缓存。支持同一分块重试、进度和取消，不提供跨页面恢复。一般请求仍限制 4 KiB，仅上传分块可到 64 KiB。上传任务绑定遥控连接并逐次检查管理授权；父母授权不解除儿童播放限制。

校验复用 UpdateApkSignatures，安装复用 UpdateInstallSession；不访问上游版本清单或安装包服务器，不改 Hongguo API。Android 24 及以上预查 minSdk，Android 23 由系统安装会话执行最低版本检查。系统可能要求未知来源权限及用户确认，不承诺静默安装。

提交安装后不立即删除源文件。终态回调清理，成功覆盖的 MY_PACKAGE_REPLACED 广播与启动版本检查兜底；残留文件启动时按 24 小时过期清理。上传任务 10 分钟无操作在下次请求时回收。应用被系统关闭后手机连接可能失效，重新扫码查看版本；仅读取到目标版本才报告升级成功。

## 上游同步关注点

- MainActivity 仍创建 LocalAppUpdater，设置页无在线线路。
- PhoneConsole 保留 localUpdate 回调；PhoneControlPage 保留独立 LocalUpdatePage 插槽。
- UpdateInstallSession 保留本地缓存终态清理钩子。
- Manifest 保留替换广播接收器。

版本为 0.16.22 / 43。只检查 Kotlin 编译、组合网页脚本语法和 diff 格式，未打 APK，未运行全量测试、CI 或真机安装。
