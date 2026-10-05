package com.cleo.cleos.ui.chat

import com.cleo.cleos.ai.ToolSpecs

/** What a tool call is about, for the one icon that stands for it in the chat: a handful of kinds, not one per tool. */
internal enum class ToolKind { Todo, Diary, Secret, Memory, Letter, Avatar, Weather, Location, Alarm, Timer, Calendar, Music, Outside, Other }

/** A folded run of tool calls as its icons: the first few kinds (each once), and how many calls those don't stand for. */
internal data class ToolStack(
    /** The kind, and whether any call of it didn't go through (then a warning is drawn in its place). */
    val shown: List<Pair<ToolKind, Boolean>>,
    val more: Int,
    /** A call that didn't go through is among the [more]: the warning is drawn on its own. */
    val hiddenFailed: Boolean,
)

internal object ToolKinds {
    private val byName: Map<String, ToolKind> = mapOf(
        ToolSpecs.addTodo.name to ToolKind.Todo,
        ToolSpecs.listTodos.name to ToolKind.Todo,
        ToolSpecs.updateTodo.name to ToolKind.Todo,
        ToolSpecs.readDiary.name to ToolKind.Diary,
        ToolSpecs.writeDiary.name to ToolKind.Diary,
        ToolSpecs.listSecrets.name to ToolKind.Secret,
        ToolSpecs.requestSecret.name to ToolKind.Secret,
        ToolSpecs.memory.name to ToolKind.Memory,
        ToolSpecs.readLetters.name to ToolKind.Letter,
        ToolSpecs.setMyAvatar.name to ToolKind.Avatar,
        ToolSpecs.getWeather.name to ToolKind.Weather,
        ToolSpecs.getLocation.name to ToolKind.Location,
        ToolSpecs.setAlarm.name to ToolKind.Alarm,
        ToolSpecs.setTimer.name to ToolKind.Timer,
        ToolSpecs.readCalendar.name to ToolKind.Calendar,
        ToolSpecs.addEvent.name to ToolKind.Calendar,
        ToolSpecs.updateEvent.name to ToolKind.Calendar,
        ToolSpecs.deleteEvent.name to ToolKind.Calendar,
        ToolSpecs.musicControl.name to ToolKind.Music,
    )

    /** The kind of a tool by the name the model called it: services the person added (mcp_…) are all one kind. */
    fun of(toolName: String?): ToolKind = when {
        toolName == null -> ToolKind.Other
        toolName.startsWith("mcp_") -> ToolKind.Outside
        else -> byName[toolName] ?: ToolKind.Other
    }

    /** [kinds] and [failed] are the calls in order, one each. */
    fun stack(kinds: List<ToolKind>, failed: List<Boolean>, max: Int): ToolStack {
        val anyFailed = LinkedHashMap<ToolKind, Boolean>()
        val calls = HashMap<ToolKind, Int>()
        for (i in kinds.indices) {
            anyFailed[kinds[i]] = (anyFailed[kinds[i]] ?: false) || failed[i]
            calls[kinds[i]] = (calls[kinds[i]] ?: 0) + 1
        }
        val shown = anyFailed.entries.take(max).map { it.key to it.value }
        val covered = shown.map { it.first }.toSet()
        val more = kinds.indices.count { kinds[it] !in covered }
        val hiddenFailed = kinds.indices.any { kinds[it] !in covered && failed[it] }
        return ToolStack(shown, more, hiddenFailed)
    }
}
