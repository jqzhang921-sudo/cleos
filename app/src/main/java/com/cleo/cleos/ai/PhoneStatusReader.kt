package com.cleo.cleos.ai

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.coroutineContext

/** One-shot reads only. No receiver, worker, stored log, app-name access or polling service. */
class PhoneStatusReader(context: Context, private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault) : PhoneStatusSource {
    private val context = context.applicationContext
    override suspend fun battery(): String = withContext(Dispatchers.IO) {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: throw ToolFailure("系统未提供当前电量，不能确认。", "电量不可用")
        val value = BatteryReading.from(intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1), intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1))
            ?: throw ToolFailure("系统返回的电量无效，不能确认。", "电量不可用")
        "查询时间：${stamp(clock())}；手机电量 ${value.percent}%；" + when (value.charging) {
            true -> "正在充电或已充满。"
            false -> "当前未充电。"
            null -> "充电状态未知。"
        }
    }
    override suspend fun usage(): String = withContext(Dispatchers.IO) {
        requireUsageAccess()
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true)
            throw ToolFailure("设备尚未完成开机解锁，暂时不能查询使用时长。", "暂时不可用")
        val now = clock()
        val timeZone = zone()
        val local = Instant.ofEpochMilli(now).atZone(timeZone)
        val begin = local.toLocalDate().atStartOfDay(timeZone).toInstant().toEpochMilli()
        // Prior-day state is used only to reconstruct an interval crossing midnight.
        val lookback = local.toLocalDate().minusDays(1).atStartOfDay(timeZone).toInstant().toEpochMilli()
        val manager = context.getSystemService(UsageStatsManager::class.java)
            ?: throw ToolFailure("系统不支持使用时长查询。", "暂时不可用")
        val source = manager.queryEvents(lookback, now)
            ?: throw ToolFailure("系统尚未提供使用统计，不能当作零分钟。", "暂无统计")
        val event = UsageEvents.Event()
        val screenEvents = ArrayList<ScreenUseEvent>()
        var scanned = 0
        while (source.hasNextEvent()) {
            coroutineContext.ensureActive()
            if (++scanned > 100_000) throw ToolFailure("系统使用记录过多，本次未完成统计。", "统计未完成")
            if (!source.getNextEvent(event)) break
            val kind = when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> ScreenUseEvent.Kind.On
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> ScreenUseEvent.Kind.Off
                UsageEvents.Event.KEYGUARD_HIDDEN -> ScreenUseEvent.Kind.Unlock
                UsageEvents.Event.KEYGUARD_SHOWN -> ScreenUseEvent.Kind.Lock
                UsageEvents.Event.DEVICE_SHUTDOWN -> ScreenUseEvent.Kind.Shutdown
                UsageEvents.Event.DEVICE_STARTUP -> ScreenUseEvent.Kind.Startup
                else -> null
            }
            // Deliberately never read event.packageName, className, or extras.
            if (kind != null) screenEvents += ScreenUseEvent(event.timeStamp, kind)
        }
        requireUsageAccess()
        val value = ScreenUse.estimate(screenEvents, begin, now)
            ?: throw ToolFailure("系统暂无可用的亮屏与解锁记录，不能当作零分钟。", "暂无统计")
        "查询时间：${stamp(now)}；日期：${local.toLocalDate()}；今日亮屏且解锁累计" +
            (if (value.incomplete) "（已确认部分）约 " else "约 ") + ScreenUse.duration(value.millis) +
            (if (value.incomplete) "。部分时段缺少记录，仅计算可确认的时段。" else "。") +
            "这是系统事件估算，可能与手机自带统计不同；不代表一直注视屏幕，不包含应用明细，也没有读取屏幕内容。"
    }
    private fun stamp(at: Long) = Instant.ofEpochMilli(at).atZone(zone()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX"))
    private fun requireUsageAccess() {
        if (!usageAllowed(context)) throw ToolFailure("未授予使用情况访问权限，或权限已撤回。请让用户自行在设置中授权，未读取使用时长。", "未获使用情况访问权限")
    }
    companion object {
        @Suppress("DEPRECATION") // App-op check also works on supported Android 10–16 devices.
        fun usageAllowed(context: Context): Boolean = runCatching {
            context.getSystemService(AppOpsManager::class.java)?.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
        fun accessIntent(context: Context) = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }
}
