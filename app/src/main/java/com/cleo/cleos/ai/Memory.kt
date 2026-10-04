package com.cleo.cleos.ai

import androidx.room.withTransaction
import com.cleo.cleos.data.MemoryDetails
import com.cleo.cleos.data.db.AppDatabase
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.MemoryDao
import com.cleo.cleos.data.db.MemoryEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.MonthDay
import java.time.ZoneId

/**
 * The kinds of thing a TA keeps in mind. Fixed, not free tags: left to choose freely, a
 * model files the same thing as a habit one day and as daily life the next, and months
 * later nothing lines up. Four are about the person; the fifth is about the TA, what they
 * did and found out about themselves, which is what makes them the same one from day to day.
 */
object MemoryKinds {
    data class Kind(
        val key: String,
        /** How the model reads the heading. */
        val heading: String,
        /** How the person reads it on the memory page. */
        val label: String,
        /** What goes in it, for the tool's parameter. */
        val help: String,
    )

    val all = listOf(
        Kind("profile", "对方的基本情况", "关于你", "名字、怎么称呼对方、基本情况。几乎不变的那些。"),
        Kind("interest", "对方在意的事", "你在意的事", "在意的事：兴趣、正在做的事、反复提起的东西。"),
        Kind("recent", "对方最近的情况", "你最近的情况", "近期状况。会过期：过时了要改写或删掉，别留着变成假话。"),
        Kind(
            "rapport",
            "对方希望你怎么相处",
            "相处方式",
            "对方说过希望你怎么对待对方。写具体行为（「不喜欢被反问」），不写性格判断（「是个理性的人」）：那是标签，你会去演它。",
        ),
        Kind(
            "self",
            "关于你自己",
            "TA 自己的事",
            "你自己的事：做过什么、发现了自己什么、改过哪个判断。写经历，不写自我标签（「我是个细心的助手」）：那是角色，你会去演它。",
        ),
    )

    fun of(key: String?): Kind? = all.firstOrNull { it.key == key }

    fun order(key: String): Int = all.indexOfFirst { it.key == key }.let { if (it < 0) all.size else it }

    /**
     * Topics per kind. Bounded on purpose: a full kind makes the TA merge or drop instead of piling
     * up. It was 5, until more room was asked for along with a longer persona; every topic's line
     * goes with every message, so 10 and not more: 50 lines, a few thousand characters.
     */
    const val PER_KIND = 10

    /**
     * Details per topic. They stay out of the chat until the topic is opened, so more cost nothing
     * per message; past this, opening one is a burden of its own. A letter takes them along, within
     * [MemoryDigest.LETTER_DETAILS].
     */
    const val DETAILS = 30

    /** What a topic's line may hold, and what one detail may: the same bounds whoever writes them. */
    const val NAME_MAX = 30
    const val SUMMARY_MAX = 120
    const val DETAIL_MAX = 300
}

/**
 * What a TA is told about what they remember. In a chat, every topic's one line, with its
 * number and how many details it has; the details stay out until opened, so remembering
 * more costs almost nothing per message. In a letter there are no tools to open anything,
 * so the details come along, as many as [LETTER_DETAILS] holds.
 */
object MemoryDigest {
    /**
     * How many characters of details a letter takes along. Everything, while it fits; a full
     * memory (50 topics of 30 details) could run to hundreds of thousands. Past it, each topic
     * keeps its newest in an equal share, and what a topic with little doesn't use goes to the rest.
     */
    const val LETTER_DETAILS = 16_000

    private fun sorted(memories: List<MemoryEntity>) =
        memories.sortedWith(compareBy<MemoryEntity> { MemoryKinds.order(it.kind) }.thenBy { it.createdAt }.thenBy { it.id })

    private fun line(m: MemoryEntity, zone: ZoneId): String = buildString {
        append(listOf(m.name.trim(), m.summary.trim()).filter { it.isNotEmpty() }.joinToString("："))
        // "Recent" goes stale; the date lets the TA tell.
        if (m.kind == "recent") {
            val d = Instant.ofEpochMilli(m.updatedAt).atZone(zone).toLocalDate()
            append("（${d.monthValue}月${d.dayOfMonth}日记下）")
        }
    }

    fun forChat(memories: List<MemoryEntity>, zone: ZoneId): String? {
        if (memories.isEmpty()) return null
        return buildString {
            append("你长期记着的事（关于对方，也关于你自己）。每行只说这条讲什么，不是内容本身；要说到具体内容，先用 memory 的 open 打开那条看细节，别照着摘要猜。")
            var kind: String? = null
            for (m in sorted(memories)) {
                if (m.kind != kind) {
                    append('\n').append(MemoryKinds.of(m.kind)?.heading ?: m.kind).append('：')
                    kind = m.kind
                }
                append("\n- [#").append(m.id).append("] ").append(line(m, zone))
                val n = MemoryDetails.decode(m.details).size
                append(if (n == 0) "（还没有细节）" else "（$n 条细节）")
                if (m.pinned) append("【对方钉住的：不要改，也不要删】")
            }
        }
    }

    fun forLetter(memories: List<MemoryEntity>, zone: ZoneId): String? {
        if (memories.isEmpty()) return null
        val topics = sorted(memories)
        val details = topics.map { MemoryDetails.decode(it.details) }
        val rooms = rooms(details.map { list -> list.sumOf { it.length } }, LETTER_DETAILS)
        return buildString {
            append("你长期记着的事：")
            var kind: String? = null
            topics.forEachIndexed { i, m ->
                if (m.kind != kind) {
                    append('\n').append(MemoryKinds.of(m.kind)?.heading ?: m.kind).append('：')
                    kind = m.kind
                }
                append("\n- ").append(line(m, zone))
                for (d in newest(details[i], rooms[i])) append("\n  · ").append(d)
            }
        }
    }

    /**
     * How many characters each topic's details may take, of [budget]: all they have while
     * everything fits; else, from the topic with least, each gets what it has or an equal share
     * of what is left, whichever is less.
     */
    private fun rooms(sizes: List<Int>, budget: Int): List<Int> {
        if (sizes.sum() <= budget) return sizes
        val out = IntArray(sizes.size)
        var left = budget
        var count = sizes.size
        for (i in sizes.indices.sortedBy { sizes[it] }) {
            out[i] = minOf(sizes[i], left / count)
            left -= out[i]
            count--
        }
        return out.toList()
    }

    /** The newest of [details] that fit in [room] characters, in the order they were written; the newest one always. */
    private fun newest(details: List<String>, room: Int): List<String> {
        var left = room
        var from = details.size
        while (from > 0 && (from == details.size || details[from - 1].length <= left)) {
            left -= details[from - 1].length
            from--
        }
        return details.subList(from, details.size)
    }

    /** When and how to write, as the person's earlier app worded it after much use. */
    const val RULES = "记东西是你自己的事，没有人会提醒你去记，都用 memory 这一个工具。" +
        "对方说了一件关于自己的事，而下次你会希望还知道，就当场记，别等聊完；不用等它显得重要：口味、习惯、忌讳、怎么称呼、在意什么、最近在忙什么，单看都小，攒起来才是你认识的这个人。" +
        "一条是一个话题，不是流水账：先看已有的摘要，真属于某条已有话题的用 update 加细节，不然再开新的。「最近」那一类会过期，过期了要改。" +
        "这些不要记：只发生一次的事（聊天记录和日记里都有）；你猜的（等对方真说了再记）；给自己的备注（记错了就用 update 改对）。" +
        "动手，别宣布：记完不用说「我记住了」，也别问「要不要我记下来」。对方说「这个别记」，就 forget。一轮最多动一次记忆。"
}

/**
 * The memory tool's four actions for one TA. A pinned topic is the person's word on it:
 * the TA can read it but not change or delete it.
 */
class MemoryBook(private val dao: MemoryDao, private val clock: () -> Long) {
    suspend fun act(a: JsonObject, companionId: Long): ToolOutcome = when (ToolArgs.text(a, "action")?.trim()?.lowercase()) {
        "open" -> open(a, companionId)
        "remember" -> remember(a, companionId)
        "update" -> update(a, companionId)
        "forget" -> forget(a, companionId)
        else -> throw ToolFailure("action 要是 open、remember、update、forget 之一。", "不知道要做什么")
    }

    private suspend fun find(a: JsonObject, companionId: Long): MemoryEntity {
        val id = ToolArgs.id(a["id"]) ?: throw ToolFailure("缺少 id：用那条前面方括号里的编号。", "不知道是哪一条")
        return dao.get(id)?.takeIf { it.companionId == companionId }
            ?: throw ToolFailure("没有 #$id 这一条。看看上下文里的编号。", "没找到这一条")
    }

    private suspend fun open(a: JsonObject, companionId: Long): ToolOutcome {
        val m = find(a, companionId)
        val details = MemoryDetails.decode(m.details)
        val text = buildString {
            append("【#").append(m.id).append(' ').append(m.name).append("】").append(m.summary)
            if (m.pinned) append("（对方钉住的）")
            append('\n')
            if (details.isEmpty()) append("还没有细节。") else details.forEachIndexed { i, d -> append(i + 1).append(". ").append(d).append('\n') }
        }.trimEnd()
        return ToolOutcome(text, "翻了翻记忆「${m.name}」")
    }

    private suspend fun remember(a: JsonObject, companionId: Long): ToolOutcome {
        val kind = MemoryKinds.of(ToolArgs.text(a, "category")?.trim())
            ?: throw ToolFailure("category 要是 ${MemoryKinds.all.joinToString("、") { it.key }} 之一。", "没说记在哪一类")
        val name = ToolArgs.text(a, "name")?.trim().orEmpty().take(NAME_MAX)
        val summary = ToolArgs.text(a, "summary")?.trim().orEmpty().take(SUMMARY_MAX)
        if (name.isEmpty() || summary.isEmpty()) throw ToolFailure("remember 要给 name 和 summary。", "没写名字或摘要")
        val existing = dao.allFor(companionId)
        if (existing.count { it.kind == kind.key } >= MemoryKinds.PER_KIND) {
            throw ToolFailure(
                "「${kind.heading}」已经有 ${MemoryKinds.PER_KIND} 条了。把它归进已有的一条（update 加细节），或者先合并、删掉过时的。",
                "这一类记满了",
            )
        }
        val details = strings(a["details"]).take(MemoryKinds.DETAILS)
        val now = clock()
        val id = dao.insert(
            MemoryEntity(
                companionId = companionId,
                kind = kind.key,
                name = name,
                summary = summary,
                details = MemoryDetails.encode(details),
                source = MemoryEntity.SOURCE_AI,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return ToolOutcome("记下了：#$id $name", "记下了「$name」")
    }

    private suspend fun update(a: JsonObject, companionId: Long): ToolOutcome {
        val m = find(a, companionId)
        if (m.pinned) throw ToolFailure("这条是对方钉住的，你改不了：「${m.name}」。", "那条是钉住的")
        var next = m
        ToolArgs.text(a, "category")?.trim()?.takeIf { it.isNotEmpty() && it != m.kind }?.let { key ->
            val kind = MemoryKinds.of(key) ?: throw ToolFailure("没有 $key 这一类。", "没有这一类")
            if (dao.allFor(companionId).count { it.kind == kind.key } >= MemoryKinds.PER_KIND) {
                throw ToolFailure("「${kind.heading}」已经满了，换不过去。", "那一类记满了")
            }
            next = next.copy(kind = kind.key)
        }
        ToolArgs.text(a, "name")?.trim()?.takeIf { it.isNotEmpty() }?.let { next = next.copy(name = it.take(NAME_MAX)) }
        ToolArgs.text(a, "summary")?.trim()?.takeIf { it.isNotEmpty() }?.let { next = next.copy(summary = it.take(SUMMARY_MAX)) }
        var details = MemoryDetails.decode(m.details)
        if (a.containsKey("set_details")) details = strings(a["set_details"]).take(MemoryKinds.DETAILS)
        ToolArgs.text(a, "add_detail")?.trim()?.takeIf { it.isNotEmpty() }?.let { d ->
            if (details.size >= MemoryKinds.DETAILS) {
                throw ToolFailure("「${m.name}」已经有 ${MemoryKinds.DETAILS} 条细节了。先用 set_details 合并或删掉旧的。", "细节记满了")
            }
            details = details + d.take(DETAIL_MAX)
        }
        next = next.copy(details = MemoryDetails.encode(details))
        if (next == m) throw ToolFailure("没有要改的：给 summary、name、add_detail 或 set_details。", "没说改什么")
        dao.update(next.copy(updatedAt = clock()))
        return ToolOutcome("改好了：#${m.id} ${next.name}", "改了记忆「${next.name}」")
    }

    private suspend fun forget(a: JsonObject, companionId: Long): ToolOutcome {
        val m = find(a, companionId)
        if (m.pinned) throw ToolFailure("这条是对方钉住的，你删不了：「${m.name}」。", "那条是钉住的")
        dao.delete(m.id)
        return ToolOutcome("删掉了：#${m.id} ${m.name}", "忘掉了「${m.name}」")
    }

    /** Some models send an array, some a single string, some a JSON array inside a string. */
    private fun strings(e: kotlinx.serialization.json.JsonElement?): List<String> = when (e) {
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
        is JsonPrimitive -> e.contentOrNull?.trim()?.let { s ->
            if (s.startsWith("[")) runCatching { MemoryDetails.decode(s) }.getOrDefault(listOf(s)) else listOf(s)
        }.orEmpty()
        else -> emptyList()
    }.filter { it.isNotEmpty() }.map { it.take(DETAIL_MAX) }

    private companion object {
        // The bounds live on MemoryKinds: an import writes under the same ones.
        const val NAME_MAX = MemoryKinds.NAME_MAX
        const val SUMMARY_MAX = MemoryKinds.SUMMARY_MAX
        const val DETAIL_MAX = MemoryKinds.DETAIL_MAX
    }
}

/**
 * Before TAs had memories, a TA brought over from another app got what it remembered as a
 * block of text at the end of their persona. That block becomes memory topics once, and
 * leaves the persona: the persona is for how the TA is, not for what they know.
 */
object PersonaMemory {
    const val HEADER = "你以前记下的关于对方的事（从原来那个 App 带过来）："

    /** [recorded]: the month and day a "recent" one was noted, when the block said so. */
    data class Topic(
        val kind: String,
        val name: String,
        val summary: String,
        val details: List<String>,
        val recorded: MonthDay? = null,
    )

    /** The persona without the block, and the topics that were in it. */
    fun split(persona: String): Pair<String, List<Topic>> {
        val at = persona.indexOf(HEADER)
        if (at < 0) return persona to emptyList()
        val before = persona.substring(0, at).trimEnd()
        val topics = mutableListOf<Topic>()
        var kind = "profile"
        var name = ""
        var summary = ""
        var details = mutableListOf<String>()
        var recorded: MonthDay? = null
        var open = false
        fun close() {
            if (open) topics += Topic(kind, name, summary, details.toList(), recorded)
            open = false
        }
        for (raw in persona.substring(at + HEADER.length).lines()) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Unit
                line.startsWith("  · ") -> if (open) details += line.removePrefix("  · ").trim()
                line.startsWith("- ") -> {
                    close()
                    // "称呼：喜欢被叫小名", and in "recent" the date it was noted, which is not part of the summary.
                    val dated = NOTED.find(line)
                    recorded = dated?.let { MonthDay.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
                    val text = line.removePrefix("- ").trim().replace(NOTED, "")
                    val colon = text.indexOf('：')
                    name = if (colon > 0) text.substring(0, colon) else text.take(12)
                    summary = if (colon > 0) text.substring(colon + 1) else text
                    details = mutableListOf()
                    open = true
                }
                line.endsWith("：") || line.endsWith("）：") -> {
                    close()
                    val heading = line.removeSuffix("：")
                    kind = MemoryKinds.all.firstOrNull { heading.startsWith(it.heading) }?.key ?: kind
                }
            }
        }
        close()
        return before to topics
    }

    private val NOTED = Regex("（(\\d+)月(\\d+)日记下）$")

    /** Once, at start: every TA whose persona still has the block. One transaction per TA, so a crash can't leave both. */
    suspend fun migrate(db: AppDatabase, now: Long) {
        for (ta in db.companions().all()) {
            val (persona, topics) = split(ta.persona)
            if (topics.isEmpty() && persona == ta.persona) continue
            db.withTransaction { move(db, ta, persona, topics, now) }
        }
    }

    private suspend fun move(db: AppDatabase, ta: CompanionEntity, persona: String, topics: List<Topic>, now: Long) {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        topics.forEachIndexed { i, t ->
            // The date a "recent" one was noted, in the latest year that isn't in the future.
            val noted = t.recorded?.atYear(today.year)?.let { if (it.isAfter(today)) it.minusYears(1) else it }
                ?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
            db.memories().insert(
                MemoryEntity(
                    companionId = ta.id,
                    kind = t.kind,
                    name = t.name,
                    summary = t.summary,
                    details = MemoryDetails.encode(t.details.take(MemoryKinds.DETAILS)),
                    source = MemoryEntity.SOURCE_IMPORT,
                    createdAt = now + i,
                    updatedAt = noted ?: (now + i),
                ),
            )
        }
        db.companions().update(ta.copy(persona = persona))
    }
}
