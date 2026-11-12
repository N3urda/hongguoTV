# 手机控制台验证记录

日期：2026-11-12（Asia/Shanghai）

基线：远程正式主线 `origin/main`，`4193dd6a6490bdfa7053391822d74b77bb26d057`。远程不存在 `master`。实现分支 `codex/phone-control-console-main`，独立工作树未混入本地多端实验修改。

## 已通过

- 完整核心 JVM 测试：360 项，0 失败，0 跳过。
- 完整 Android Robolectric 单元测试：333 项，0 失败，0 跳过。
- 新增控制台测试共 34 项（包括 API 21 / API 26 参数化执行）：请求鉴权、上限、两线程并发、慢客户端、关闭连接、更换令牌、账户隔离、分页、80 条缓存上限、未上线禁止播放、云端续播来源、加载状态、关闭二维码继续使用、退出前台关闭、启动期间退出不发布过期服务。
- `:app:lintDebug` 通过。
- `:app:assembleDebug` 通过，包括四个 ABI 的 FFmpeg 原生音频库构建。FFmpeg 6.1.6 源码按主线脚本 SHA-256 校验。
- 手机 Chromium 浏览器 14 项交互检查通过：搜索分页、搜索期间可遥控、标题按文本渲染防止脚本注入、播放指令携带账户代次、未上线作品预约、异步账户列表刷新、账户历史续播来源、倍速/清晰度/集数/方向指令、390px 宽度无横向溢出、页面隐藏停止轮询。页面在 CSP 与 no-referrer 策略下正常发送同源 Origin / 自定义请求头。
- `git diff --check` 通过。

最终完整执行命令：

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
node scripts/test-phone-control-ui.cjs
```

浏览器测试使用模拟片单和播放状态，检查实际发布的 HTML/JS，不冒充真实电视端到端验收。自动化账户测试使用合成凭据与模拟数据，没有操作真实账号收藏或预约。

## 性能结论的范围

已验证连接和线程上限、卡片淘汰、会话关闭和后台停止行为；实现不引入额外播放器、电视 Bitmap 缓存、后台 Service 或全量片单镜像。状态轮询不访问上游。

已补充 1 GB Android TV API 36 模拟器联网实测，结果见 [DEBUG_EMULATOR_VALIDATION.md](DEBUG_EMULATOR_VALIDATION.md)。尚无实体电视 CPU、GC 或掉帧测量，不能宣称已达到 5 / 10 MiB 增量预算，也不能声称真实账户预约已验收。真机步骤与预算见 [PHONE_CONTROL.md](PHONE_CONTROL.md)。

产物为独立包名 `com.hongguotv.nativeapp.debug` 的 debug 签名测试 APK，名称「红果 TV 测试版」，与正式版并存，账户需单独登录，默认关闭自动更新。未发布 Release、未合并主线。

## 预约路径优化

待上线作品改为点击即预约，移除单选菜单和需要关闭的结果弹窗。自动核对账户预约列表，必要时只重试读取一次（间隔 750ms）；成功显示「已预约」，尚未同步显示「已提交」。手机按钮立即显示「预约中…」，并自动更新结果。没有额外常驻线程或持续上游轮询。

新增测试验证延迟确认只写入一次、处理中重复点击不重复写入；补充未确认时再次操作只核对不重发。浏览器测试验证按钮自动变为「已预约」且禁用。

## 常用交互复查

进一步简化收藏、输入集数播放和云端继续观看，修复手机相关作品返回、翻页失败重试和片单自动更新。最终 693 项自动化测试、14 项浏览器检查通过。流程对照与保留步骤见 [INTERACTION_AUDIT.md](INTERACTION_AUDIT.md)。
