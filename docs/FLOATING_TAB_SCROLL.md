# 底部悬浮导航随滚动收起

## 范围与实现

- `MainScreen.kt` 负责一个共用的显示状态和 220ms `FastOutSlowInEasing` 平移动画。保留 `GlassTabBar` 本体、导航栏安全区和原有内容留白；动画只移动外层图层，不改变页面布局。
- `ui/common/TabBarScrollPolicy.kt` 累计实际消耗的纵向滚动距离。同方向累计 24dp 后收起/显示；反向时重新累计。到顶立即显示，没有空闲计时器。
- `ui/common/TabBarScroll.kt` 将策略接到 Compose nested scroll；不消费滚动。共用 `ScrollableState.canScrollBackward` 监听用户/程序返回顶部，`TabScrollColumn` 让 LazyColumn 的观察状态与真实滚动状态保持一致。
- 日记列表/月历、待办列表/月历、主页和世界的主列表、书房/原文/随手本阅读区复用此行为。横向日期条、空间轮播、对话框里的小列表不单独控制底栏。
- 仅当前可见的非聊天 Tab 接收行为。后台预热页无法改变底栏。切换 Tab、主滚动容器、键盘状态后重新显示；聊天滚动保持底栏可见，键盘弹出时仍收起。
- 滑出距离包括完整底栏高度、系统导航栏 inset 和阴影余量。完全隐藏时移除无障碍节点；收起过程中阻止点击正在离开的底栏。

## 改动文件

核心：`ui/MainScreen.kt`、上述两个 `ui/common/TabBarScroll*.kt`。

接入点：`ui/diary/DiaryListScreen.kt`、`ui/todo/TodoScreen.kt`、`ui/home/HomeScreen.kt`；`ui/world/` 下 WorldScreen、WorldStudy、WorldShelfTools、WorldWishlist、WorldNotebook、NotebookScrollReader、WorldAlbumPage、WorldOutsidePage、WorldJournal、WorldJourney、WorldJourneys、WorldTopicPage、WorldOnlineBooks、WorldImportForm、WorldPastedPage。

测试：`ui/common/TabBarScrollPolicyTest.kt`。版本：`app/build.gradle.kts` 的 0.42.20 / 300。无新依赖、数据库或备份格式变更。

## 验证

执行 `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintRelease --console=plain --max-workers=2`：1617 项测试通过（含新增 7 项滚动策略测试）；Lint 0 错误，69 条现有警告。

模拟器使用合成日记、待办、书籍和聊天数据，断网验证真实页面的上下滑动、停留、返回顶部、子页面切换、聊天例外和键盘。结果与截图在本轮 `outputs/Cleos-0.42.20-tab-scroll/`；测试后恢复原始数据库和偏好并关闭模拟器。

人工复验：打开记录或书房，连续上滑使导航滑出；停留不应弹回；反向滑动超过阈值恢复；回到顶部保持显示。聊天双向滚动应保持显示，打开键盘隐藏、收起键盘恢复。三星和红米实际帧率仍需对应设备观察，模拟器结果不能代替真机观感。
