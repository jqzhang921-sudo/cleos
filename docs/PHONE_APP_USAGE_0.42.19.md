# 0.42.19 · 应用使用时长

入口：设置 → 能做的事 → 手机状态 →「让 TA 看应用使用时长」。默认关闭，与电量、总时长分别控制。沿用 Android 使用情况访问授权；已给总时长授权也不会自动开启应用明细。仅限私聊和原有自主唤醒流程按需查询，群聊工具白名单不包含新工具。

新工具 `get_app_usage` 返回今天本地午夜以来各应用的前台时长估算，最多列出时长最多的 20 项，包含查询时间、日期、应用名（不可解析时标为应用标识）、完整性与剩余未展示项数。用户确认分享时明确说明结果发往其 TA 配置的模型接口并保存到聊天工具记录。没有新增模型服务、轮询、后台任务或主动消息频率。

统计基于 UsageEvents 的 resumed/paused/stopped、亮屏、解锁和重启事件。相同应用多个 activity 的时段取并集，避免页面切换重复计数；不同应用在分屏时可重叠，不把各项相加冒充总亮屏时长。只在亮屏且解锁的区间计算，后台音频与前台服务不算前台页面使用。前一天事件仅用于还原跨午夜状态。原始事件、activity 标识仅在本次内存中处理，不落盘、不发给模型；不读取屏幕、聊天或具体操作。

Android 的事件可能缺失或延迟，开头状态未知、孤立 pause 或重启后缺失状态均明确标记部分统计。没有应用事件时返回暂无统计，不谎称 0。系统统计口径与厂商数字健康可能不同。扫描有上限并支持取消；模型读取前和返回前复核分享开关，系统权限同样两次复核。应用名称是带引号的数据，不作为指令。

只声明 MAIN/LAUNCHER 的 queries 以解析常用应用显示名，不申请 QUERY_ALL_PACKAGES、不枚举完整安装列表，也不依赖无障碍或屏幕录制。已有总时长工具仍只取屏幕/锁屏类型与时间，不取应用名；新能力关闭不影响原能力。

工具设置按名称编码，旧数据缺少 PhoneAppUsage 时保持关闭。数据库 68 / 备份格式 32 不变。

依据：
- https://developer.android.com/reference/android/app/usage/UsageStatsManager#queryEvents(long,%20long)
- https://developer.android.com/reference/android/app/usage/UsageEvents.Event#ACTIVITY_RESUMED
- https://developer.android.com/training/package-visibility

验证证据在会话 outputs/Cleos-0.42.19-app-usage；构建、原生验收、安装状态分别记录，不把合成验证当作真实模型完成查询。
