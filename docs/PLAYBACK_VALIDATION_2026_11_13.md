# 播放排查（2026-11-13）

基于 origin/main 387e45f，分支 codex/playback-stream-timeout。

## 发现与修改

- Android TV API 34 模拟器随心看真实播放出现 `IllegalStateException: Playback stuck buffering and not loading`（22:41:28）。试播 LoadControl 的 6 MiB 字节预算优先于最小缓冲时长，改为时长优先，避免高码率内容提前停止加载。尚需对同一故障剧目及低内存电视持续回归。
- RemoteVideo.openRead 继承接口客户端的总请求超时，暂停或缓冲等待也计时。仅对流式分段清除总超时，保留连接/读取超时及主动取消。回归测试修复前失败，修复后通过。

## 已验证

- core 369 项测试通过；lintDebug 通过；git diff --check 通过。
- 诊断 APK assembleDebug 通过，跳过 buildNativeAudio，使用系统音频解码器；不等同正式完整构建。
- 独立 debug 包安装在 HongguoTV_Comic 模拟器。随心看真实内容首帧正常，暂停超过 40 秒后恢复，当前进程未记录新的播放器异常。
- 全屏真实短剧播放日志从 position=118238 到 153267 持续推进，视频帧由 573 到 1624，缓冲正常补充。
- 本地截图与日志位于 outputs/playback-validation。

## 未完成

- Android 全套单测等待 Robolectric Maven 运行时下载，线程栈确认阻塞于 MavenArtifactFetcher 网络读取，不能记为通过。
- NDK 27.1 下载未完成，含 FFmpeg 的完整构建未验证。
- 同一故障剧目长时间重放、自动跨集、弱网恢复、实体电视音画同步与掉帧验收仍需完成。
- 未推送、未合并、未发布。

## 2026-11-14 冷启动补充

- 旧诊断包冷启动随心看复现 `stage=compatibility-model` 空响应，同剧全屏也失败；因此尚未证明该实测故障完全来自首集缓存。
- 补充首集提示自愈：前台遇到 EmptyPlaybackResponseException 时核对详情，只有真实首集与提示不同才更换集号重试，同时更新启动缓存；后台不额外请求，集号一致保留原错误和既有恢复上限。
- 新增失效启动缓存回归，验证本次无需打开其他视频即可恢复，下次后端重建不重复查详情。
- core 369、app 373 项全部通过；lintDebug、assembleDebug 通过（仍跳过缺失 NDK 的 native audio 构建）。
- 新诊断包进程 4110 冷启动后直接进入随心看，真实内容显示视频、无错误提示；推荐内容已变更，此结果不代表原失败剧目已恢复，仍不能证明所有上游空响应已解决。
