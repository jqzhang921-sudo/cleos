package com.cleo.cleos.ai

/** Only bounded aggregate text crosses this boundary; no app names or raw events. */
interface PhoneStatusSource {
    suspend fun battery(): String
    suspend fun usage(): String
}

/** Re-check device-local consent before reading and before returning, including in-flight revocation. */
class GuardedPhoneStatus(private val source: PhoneStatusSource, private val allowed: suspend () -> Set<ToolGroup>) : PhoneStatusSource {
    private suspend fun read(group: ToolGroup, block: suspend () -> String): String {
        fun denied(): Nothing = throw ToolFailure("手机状态分享已关闭，未返回数据。", "设置里关着")
        if (group !in allowed()) denied()
        val result = block()
        if (group !in allowed()) denied()
        return result
    }
    override suspend fun battery() = read(ToolGroup.PhoneBattery) { source.battery() }
    override suspend fun usage() = read(ToolGroup.PhoneUsage) { source.usage() }
}

data class BatteryReading(val percent: Int, val charging: Boolean?) {
    companion object {
        fun from(level: Int, scale: Int, status: Int): BatteryReading? {
            if (scale <= 0 || level !in 0..scale) return null
            return BatteryReading((level.toLong() * 100 / scale).toInt(), when (status) {
                2, 5 -> true // Android charging / full
                3, 4 -> false // discharging / not charging
                else -> null
            })
        }
    }
}

/** No package name or app activity is retained. */
data class ScreenUseEvent(val at: Long, val kind: Kind) {
    enum class Kind { On, Off, Unlock, Lock, Shutdown, Startup }
}
data class ScreenUseEstimate(val millis: Long, val incomplete: Boolean)

object ScreenUse {
    /** Count the union of interactive AND unlocked intervals, clipped to today's local midnight. */
    fun estimate(events: List<ScreenUseEvent>, begin: Long, end: Long): ScreenUseEstimate? {
        require(end >= begin)
        val ordered = events.filter { it.at <= end }.sortedBy { it.at }
        if (ordered.isEmpty()) return null
        var screen: Boolean? = null
        var unlocked: Boolean? = null
        var cursor = begin
        var total = 0L
        var missing = 0L
        fun add(until: Long) {
            val stop = until.coerceIn(begin, end)
            if (stop > cursor) {
                if (screen == true && unlocked == true) total += stop - cursor
                else if (screen != false && unlocked != false) missing += stop - cursor
                cursor = stop
            }
        }
        for (event in ordered) {
            add(event.at)
            when (event.kind) {
                ScreenUseEvent.Kind.On -> screen = true
                ScreenUseEvent.Kind.Off -> screen = false
                ScreenUseEvent.Kind.Unlock -> unlocked = true
                ScreenUseEvent.Kind.Lock -> unlocked = false
                ScreenUseEvent.Kind.Shutdown -> { screen = false; unlocked = false }
                ScreenUseEvent.Kind.Startup -> { screen = null; unlocked = null }
            }
        }
        add(end)
        return ScreenUseEstimate(total, missing > 0)
    }
    fun duration(millis: Long): String {
        val minutes = millis.coerceAtLeast(0) / 60_000
        return if (minutes == 0L) "不足 1 分钟" else if (minutes < 60) "${minutes} 分钟" else "${minutes / 60} 小时 ${minutes % 60} 分钟"
    }
}
