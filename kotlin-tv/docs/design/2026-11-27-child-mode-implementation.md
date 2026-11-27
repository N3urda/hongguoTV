# 儿童模式实施清单

依据同目录 child-mode 设计；遵守 Agents.md，仅 kotlin-tv，保留网络封装、签名、重试与 API 23 兼容性。

- [x] 独立 ChildModeStore：原子配置、作品白名单、PIN、恢复码、短期独立授权和失败锁定。
- [x] 独立 ChildModeUi：遥控数字输入、管理、儿童片单；MainActivity 入口和异步播放最终校验。
- [x] PhoneConsole 服务端操作鉴权；独立手机儿童管理 UI，复用现有搜索卡片。
- [x] TvSocial 默认关闭及儿童模式强制关闭；退出不自动恢复。
- [x] 一次定向测试与 debug 编译；不运行 CI、不执行额外多轮 review。

验证重点：ID 权限、PIN/会话隔离、冷启动持久化、损坏关闭、旧弹幕偏好迁移、手机控制不能绕过白名单。

## 实施结果

- 权限、存储和 TV 家长界面分别放在 ChildModeStore.kt 与 ChildModeUi.kt；手机网页新增 ChildControlPage.kt。
- 现有文件只接入页面切换、播放权限、手机命令权限与弹幕策略；红果接口、签名和重试文件未改动。
- ChildModeTest 的 5 项 API 23 定向检查已通过；手机 JavaScript 语法检查通过，debug APK 已成功构建。
- 本机补齐 SDK 36 后构建；未修改项目 SDK 配置，未触发 CI 或运行全量测试。
- 尚未进行真实 Android TV 设备的遥控操作验收。

使用：TV 设置 → 账户 → 儿童模式；首次设置 6 位密码并保存恢复码。普通模式作品详情可加入片单；手机控制台的「儿童模式 · 家长管理」验证密码后可在搜索结果中增删片单。
