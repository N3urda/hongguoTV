# 上下键切集与菜单优化：主分支迁移验真

日期：2026-11-17。

## 基线与范围

- 独立工作区：`/Users/lu.ziyong/Github/hongguoTV-player-remote`。
- 分支：`codex/player-remote-menu`。
- 基线：`origin/main`，`6672b55d5308ea1ec26d2fe0bc0b2401ef4aa0c3`（0.16.6）。建立前及验真结束时均重新 fetch；结束时 HEAD 与 origin/main 提交差为 `0 / 0`，功能改动仍在工作区中。
- 原工作区及其未提交改动保留。本次未迁移旧工作区的 KMP、首页、构建配置等其他改动。
- 首轮迁移仅修改 MainActivity 播放交互、TvTools 子菜单返回、TvSocial 弹窗状态、PlaybackQueueTest 和 README；新增本验真记录。后续按反馈增加底部选集及共用组件，见末节。没有修改主分支的签名、媒体解析、解码、首页或版本号，没有提交、推送或发布。

## 迁移结果

全屏观看时上键上一集、下键下一集；长按只切一次，首尾提示不越界，快速连点合并后切至最终目标。方向键在菜单、选集及恢复控件内用于导航，不穿透切集。

确认键暂停/继续并展示菜单，同一次抬键不会再次点击；菜单键直接展示且不暂停。一级菜单前置选集、倍速，高级设置收拢；后续按用户反馈精简为单行，弹幕设置紧跟倍速，评论单独保留，删除上下集按钮。菜单不再自动消失，首帧不抢走菜单焦点。

针对最新主分支进行了以下适配，而非覆盖旧版文件：

- 保留画面旋转、常驻播放进度入口，以及 `recreateView` 和视频 Surface 重建路径。
- 保留渐进预加载的用户交互让路逻辑和首页导航返回逻辑。
- 首页/手机发起播放时，返回键先关闭已打开的播放菜单，再执行原返回路径。
- 保留手机遥控分发、带封面的选择菜单等主分支能力；新增手机方向键切集回归测试。
- 旋转子菜单返回上一级；常驻进度开关可滚动到达并切换。

## 首轮迁移自动化与构建结果

| 范围 | 结果 |
| --- | --- |
| Android 6+ 应用单元/Robolectric 测试 | 327 项，全部通过，无跳过 |
| Android 5 兼容版应用测试 | 398 项，全部通过，无跳过 |
| core 测试 | 395 项，全部通过，无跳过 |
| 两版 Release lint | 通过；平台版 92 条警告、兼容版 86 条警告，均 0 错误 |
| 两版 Debug APK 构建 | 通过；兼容版原生音频库构建完成 |
| `git diff --check` | 通过 |

两版测试存在重叠，不将上述数量相加宣称独立测试用例数。首轮迁移时播放器队列与交互测试为 22 项，包含迁移所需的旋转/常驻进度入口、手机按键及首页播放返回优先级回归。其余新交互测试覆盖重复按键、快速连点、边界、暂停状态、异步首帧、焦点、定时停止和自动/手动切集竞争。

执行命令：

```sh
kotlin-tv/gradlew -p kotlin-tv :core:test :app:testDebugUnitTest :app:lintRelease :app:assembleDebug --console=plain
kotlin-tv/gradlew -p kotlin-tv -PlegacyAudio=true :app:testDebugUnitTest :app:lintRelease :app:assembleDebug --console=plain
```

[平台版日志](/Users/lu.ziyong/Github/hongguoTV-player-remote/outputs/player-remote-main-validation/platform-build.log)、[兼容版日志](/Users/lu.ziyong/Github/hongguoTV-player-remote/outputs/player-remote-main-validation/legacy-build.log)。本次构建的是 debug 验证包，未签发新的正式发行版。

## 模拟器交互验真

在 Android TV arm64 模拟器上使用独立 QA 包 `com.hongguotv.nativeapp.remotemain.debug`，避免覆盖原安装。仅 QA 构建通过外部 init 脚本加入探针，不写入正式应用源码。

本地测试视频经实际 Media3 解码及首帧回调验证，以下链路通过：上下切集、第一集边界、确认暂停与再确认恢复、菜单不暂停、选集内方向导航不切集、关闭选集恢复播放/焦点、设置逐层返回、菜单超过 8.5 秒保持焦点、旋转子菜单返回、滚动到常驻进度并切换。最后一次探针结果为 `PASS` / `INSTRUMENTATION_CODE: 0`。

[交互验真结果](/Users/lu.ziyong/Github/hongguoTV-player-remote/outputs/player-remote-main-validation/offline-instrumentation.txt)、[探针源码](/Users/lu.ziyong/Github/hongguoTV-player-remote/outputs/player-remote-main-validation/probe/RemoteInteractionProbe.kt)。

截图：

- [播放菜单](/Users/lu.ziyong/Github/hongguoTV-player-remote/outputs/player-remote-main-validation/screenshots-final/01-confirm-menu.png)
- [更多设置](/Users/lu.ziyong/Github/hongguoTV-player-remote/outputs/player-remote-main-validation/screenshots-final/03-more-settings.png)
- [常驻进度完整滚动可见](/Users/lu.ziyong/Github/hongguoTV-player-remote/outputs/player-remote-main-validation/screenshots-final/06-persistent-progress-visible.png)
- [旋转子菜单](/Users/lu.ziyong/Github/hongguoTV-player-remote/outputs/player-remote-main-validation/screenshots-final/07-rotation-picker.png)

更多设置保留主分支功能后需要纵向滚动；已验证下方条目能完整进入可视区域并操作。

## 真实在线播放验真

复用主分支的 `LivePlaybackInstrumentation`，使用真实接口和实际播放器，没有用示例地址替代。7 集均输出真实画面并播放至少 5 秒；同时通过进度跳转、暂停/恢复、自动下一集：

- 《少夫人来自东北3》第 1、2 集；第 1 集包含跳转后进度达到 18 秒、恢复后达到 19 秒的验证，第 2 集通过自动连播进入。
- 《嫌疑人的秘密2藏金生死线》第 1、2 集。
- 《无敌财神！我觉醒了唯一隐藏职业》第 54、55、70 集。

结果包含纵向 `1080×1920` 和横向 `1920×1080` 视频的实际尺寸，最终输出 `PASS: seven real episodes, rendered frames, seek, pause/resume and automatic next episode`。

[在线验真结果](/Users/lu.ziyong/Github/hongguoTV-player-remote/outputs/player-remote-main-validation/live-instrumentation.txt)。在线探针验证主分支播放能力未被迁移破坏；新按键的端到端操作由前述本地视频交互探针覆盖，二者不混淆。

这次最新主分支在线验真成功，取代旧工作区“在线接口空响应、尚未在线验收”的局限。仍未连接物理电视，不据此声称完成真实电视遥控器、低配设备性能和跨季全链路验收。QA 应用、本地视频服务及对应 ADB 映射均已停止或清理。

## 后续菜单精简（同日）

根据截图反馈，删除菜单内上一集/下一集，保留全屏方向快捷键；菜单收为一行“播放/暂停、选集、倍速、弹幕设置、评论、更多设置”。选集网格本身保持原有每组 20 集、每行 5 集、跳转集数和焦点恢复行为。

已调整原布局断言并移除失效的菜单下一集按钮测试；重新运行 PlaybackQueueTest（21 项）与 TvSocialTest（6 项），共 27 项通过。Debug 构建通过。更新的模拟器探针额外验证弹幕设置直达及返回焦点，使用 80 集测试列表展示选集面板。上方全量与在线结果是精简前同基线的验收记录，不冒充本次重跑结果。

精简后的模拟器交互探针已通过（PASS，退出码 0），弹幕设置打开/返回和焦点恢复正常。新截图位于 `outputs/player-remote-main-validation/screenshots-simplified/`；探针结果为 `simplified-menu-instrumentation.txt`。

## 底部选集与详情页统一（同日）

播放中的选集由右侧改为底部面板。新增 `EpisodeSelectorView`，详情页和播放面板共用同一套范围标签、20 集分组、每行 10 集网格、当前/上次/已看标记和焦点路径。分组确认只换显示范围，保持焦点在分组；下键进入集数网格，确认才播放。两处跳转集数默认跟随最近聚焦的集数，选集播放入口共用 `playSelectedEpisode`，若目标匹配未完成的续播记录则恢复该位置。播放面板选择当前集或返回仍恢复原播放/暂停状态。

本轮重新完成平台版应用全量 327 项测试、Release lint、Debug 构建；Android 5 兼容配置下相关交互/详情/播放回归 37 项通过。新增回归覆盖底部对齐、共用十列网格、分组标签、当前/上次标记、数字跳转默认值及切换分组不误播放。

模拟器使用 80 集测试数据验证底部面板、分组 1–20 → 21–40、焦点进入第 21 集但仍播放第 1 集、返回恢复焦点，完整交互探针通过。

- 最新截图：`outputs/player-remote-main-validation/screenshots-bottom/02-episode-panel.png`
- 换组截图：`outputs/player-remote-main-validation/screenshots-bottom/08-bottom-next-group.png`
- 模拟器结果：`outputs/player-remote-main-validation/bottom-selector-instrumentation.txt`
- 平台回归与 lint：`outputs/player-remote-main-validation/bottom-selector-tests.log`
- 兼容回归：`outputs/player-remote-main-validation/bottom-selector-legacy-tests.log`

本轮没有重新执行真实在线 7 集验收；前述在线结果属于同一主分支基线上一轮记录。


## 选集改为菜单下方内嵌展开

根据最新截图反馈，撤掉上一版独立底部 Dialog，把共用 EpisodeSelectorView 直接接在播放菜单按钮下方，沿用同一个 HUD 背景。保留每组 20 集、每行 10 集、分组和跳转集数逻辑。

- 打开后焦点落到当前集；分组上键返回“选集”按钮，菜单下键进入选集区域。
- 返回或再次点击“选集”只收起集数，保留菜单并恢复原播放状态；原来暂停的仍暂停。
- 点击“播放”先收起选集再继续播放，避免收起自动恢复与播放开关互相抵消。
- 手机遥控走 Activity 的内嵌焦点链；选集展开期间不触发全屏切集。

验证：完整平台单元测试及 lintRelease 通过；补充“返回只收起 / 播放不二次反转”回归后 PlaybackQueueTest 共 22 项通过。Android 5 兼容构建的三个交互测试类 37 项通过。电视模拟器本地视频交互验真通过，覆盖上下切集、菜单、内嵌选集分组、返回恢复、弹幕、嵌套设置和焦点超时。尚未进行实体电视验收。

日志：`outputs/player-remote-main-validation/inline-selector-*.log` 和 `inline-selector-instrumentation.txt`。
实际截图：`outputs/player-remote-main-validation/screenshots-inline/02-episode-panel.png`。


## 透明图标菜单

第一排六个操作改为统一的线条图标，保留无障碍动作名称及播放/暂停动态图标；菜单按钮、播放内选集分组和集数均无实色填充，焦点采用白色描边，当前集采用强调色及“当前”文字。移除播放内跳转集数入口，并修正最后一个分组向右的焦点边界。详情页跳转入口保留。

平台交互回归 27 项及 lintRelease 通过；Android 5 兼容构建交互测试通过。电视模拟器实际播放、切集、透明图标菜单、分组和返回恢复验证通过。截图：`outputs/player-remote-main-validation/screenshots-icons/02-episode-panel.png`。实体电视尚未验收。


## 倍速按钮显示当前倍率，移除评论入口

播放菜单缩减为播放/暂停、选集、当前倍率、弹幕、更多设置五个入口。倍率按钮直接绘制当前值（例如 1×、2×），选择后同时更新播放器速度、显示值和无障碍名称。

PlaybackQueueTest 22 项通过；电视模拟器实际从 1× 选择 2×，验证播放器 speed 为 2f、按钮更新为 2× 且焦点返回，并完成其余遥控回归。截图：`outputs/player-remote-main-validation/screenshots-speed-label/09-speed-2x.png`。日志：`speed-label-instrumentation.txt`、`speed-label-tests.log`。
