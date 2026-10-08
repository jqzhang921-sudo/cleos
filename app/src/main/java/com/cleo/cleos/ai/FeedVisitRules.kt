package com.cleo.cleos.ai

import com.cleo.cleos.data.FeedComments
import com.cleo.cleos.data.FeedInteractions
import com.cleo.cleos.data.db.CompanionEntity
import com.cleo.cleos.data.db.FeedPostEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.ZonedDateTime

data class FeedVisitTarget(val post: FeedPostEntity, val replyTo: String?)

@Serializable
data class FeedVisitDraft(val action: String, val content: String = "", val postId: Long? = null,
    val replyTo: String? = null, val like: Boolean = false, val sourceIndex: Int? = null)

object FeedVisitRules {
    private val json = Json { ignoreUnknownKeys = true }
    data class Level(val id: Int, val label: String, val minMinutes: Int, val maxMinutes: Int, val dailyMax: Int)
    val LEVELS = listOf(Level(0, "偶尔", 480, 720, 2), Level(1, "自然", 240, 360, 4), Level(2, "常来看看", 120, 240, 6))
    fun level(id: Int) = LEVELS.firstOrNull { it.id == id } ?: LEVELS.first()
    fun quiet(ta: CompanionEntity, now: ZonedDateTime) = FreeTopicRules.quiet(now, ta.feedVisitQuietOn,
        FreeTopicRules.minute(ta.feedVisitQuietStart, 1380), FreeTopicRules.minute(ta.feedVisitQuietEnd, 480))
    fun next(ta: CompanionEntity, now: ZonedDateTime, fraction: Double, exhausted: Boolean = false): Long {
        val level = level(ta.feedVisitLevel)
        val at = if (exhausted) now.plusDays(1).withHour(8).withMinute(0).withSecond(0).withNano(0)
        else now.plusMinutes((level.minMinutes + (level.maxMinutes - level.minMinutes) * fraction.coerceIn(0.0, 1.0)).toLong())
        return FreeTopicRules.outsideQuiet(at, ta.feedVisitQuietOn, FreeTopicRules.minute(ta.feedVisitQuietStart, 1380),
            FreeTopicRules.minute(ta.feedVisitQuietEnd, 480)).toInstant().toEpochMilli()
    }
    fun held(ta: CompanionEntity, now: ZonedDateTime, occupied: Boolean, attempts: Int, configured: Boolean): String? = when {
        !ta.feedVisitEnabled -> "主动逛朋友圈已关闭"
        quiet(ta, now) -> "免打扰时段，稍后再逛"
        occupied -> "正在聊天或处理动态，稍后再逛"
        !configured -> "还没有配置模型密钥"
        attempts >= level(ta.feedVisitLevel).dailyMax -> "今天的逛逛次数已用完"
        else -> null
    }
    fun targets(posts: List<FeedPostEntity>, taId: Long, now: Long): List<FeedVisitTarget> = posts.take(20)
        .filter { now - it.createdAt in 0..7 * 24 * 60 * 60_000L }.mapNotNull { post ->
            val reply = FeedComments.decode(post.comments).lastOrNull { FeedInteractions.canInvite(post, taId, it.id) }
            when {
                reply != null -> FeedVisitTarget(post, reply.id)
                FeedInteractions.canInvite(post, taId, null) -> FeedVisitTarget(post, null)
                else -> null
            }
        }.take(4)
    fun decode(raw: String, targets: List<FeedVisitTarget>, sources: List<FeedNewsItem>, allowPost: Boolean): FeedVisitDraft {
        val draft = json.decodeFromString<FeedVisitDraft>(raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        val text = draft.content.trim()
        when (draft.action) {
            "skip" -> require(text.isBlank() && !draft.like && draft.postId == null && draft.sourceIndex == null && draft.replyTo == null) { "TA 的安静结果包含多余动作" }
            "reply" -> {
                require(targets.any { it.post.id == draft.postId && it.replyTo == draft.replyTo }) { "TA 没有选到有效的回复对象" }
                require(text.length <= 200 && draft.sourceIndex == null) { "TA 的回复过长或来源不符" }
            }
            "post" -> {
                require(allowPost && text.isNotBlank() && draft.postId == null && draft.replyTo == null && !draft.like) { "本次不能发新动态" }
                require(if (draft.sourceIndex == null) text.length <= 80 else text.length <= 600 && sources.getOrNull(draft.sourceIndex) != null) { "TA 的动态过长或资讯来源不符" }
            }
            else -> error("TA 没有返回有效的逛逛动作")
        }
        return draft.copy(content = text)
    }
    fun system(ta: CompanionEntity, interests: String, allowPost: Boolean): String = FeedAiRules.system(ta, interests)
        .substringBefore("只输出 JSON：") + """
        这是你独立逛朋友圈的一次机会，不是聊天消息，不需要发消息提醒对方，也不要因没人回复而催促。
        每个阅读对象都标明了作者和要回应的评论。你的正文和评论属于你自己，不能当成别人写的或无缘无故反驳自己。
        只在给出的阅读对象中选一个，用 reply 点赞或回应；回复对象必须原样使用给出的 postId 和 replyTo。
        也可以只是看看，选择 skip。没有收到图片时，不编造照片内容；不重复自己最近发过的动态和评论。
        ${if (allowPost) "允许选择 post 分享一条新动态。日常限 80 字；仅依据给出的资讯才可写话题，并填写对应 sourceIndex，限 600 字。" else "这次不允许发新动态，只能 reply 或 skip。"}
        一次最多一个动作。只输出 JSON：{"action":"skip|reply|post","content":"正文或空字符串","postId":null,"replyTo":null,"like":false,"sourceIndex":null}。
        skip 的正文为空且不携带其他动作；reply 时 sourceIndex 为 null；post 时 postId、replyTo 为 null，like 为 false。
    """.trimIndent()
}
