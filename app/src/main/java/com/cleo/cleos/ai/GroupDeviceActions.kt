package com.cleo.cleos.ai

import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/** The group runs members serially. Durable attempts prevent another member/retry repeating the same device write. */
internal object GroupDeviceActions {
    private const val DONE = "本轮已处理，未重复创建"
    private const val UNCERTAIN = "此前状态未确认，未重复创建"
    fun isWrite(name: String) = name == "set_alarm" || name == "set_timer"

    private fun signature(name: String, raw: String): String? = runCatching {
        val args = requireNotNull(ToolArgs.parse(raw))
        when (name) {
            "set_alarm" -> "alarm:${AlarmText.time(requireNotNull(ToolArgs.text(args, "time")))}:" +
                AlarmText.days(ToolArgs.text(args, "repeat")).joinToString(",")
            "set_timer" -> {
                val minutes = requireNotNull((args["minutes"] as? JsonPrimitive)?.content?.toDoubleOrNull())
                require(minutes.isFinite())
                val seconds = (minutes * 60).roundToInt()
                require(seconds in 1..86400)
                "timer:$seconds"
            }
            else -> null
        }
    }.getOrNull()

    fun previous(call: ToolCall, records: List<GroupToolRecord>): ToolOutcome? {
        if (!isWrite(call.name)) return null
        val key = signature(call.name, call.arguments) ?: return null
        val matching = records.filter { signature(it.name, it.arguments) == key }
        val success = matching.firstOrNull { it.state == "returned" &&
            (it.note.startsWith("定了闹钟：") || it.note.startsWith("开了计时器：") || it.note == DONE) }
        if (success != null) return ToolOutcome("本轮已执行过相同的时钟操作，本次没有重复创建。此前实际返回：\n${success.result}", DONE)
        if (matching.any { it.state == "running" || it.state == "failed" || it.state == "returned" && it.note == UNCERTAIN })
            return ToolOutcome("本轮此前有相同操作，但完成状态未确认。请用户查看手机时钟，本次没有重复创建，也不能声称已经成功。", UNCERTAIN)
        return null
    }
}
