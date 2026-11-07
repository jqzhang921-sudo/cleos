package com.cleo.cleos.data

import com.cleo.cleos.data.db.*

/** Saved grouping/ordering only. Eligibility is always rechecked against current public sources. */
object WorldJourneyTopics {
    const val MAX_TOPICS = 500
    const val MAX_TITLE = 80
    val kinds = linkedMapOf("topic" to "主题", "place" to "地方")
    fun title(raw: String): String = raw.trim().also { value ->
        require(value.isNotBlank() && value.length <= MAX_TITLE && value.none { it.isISOControl() } && value.indices.all { i ->
            when { value[i].isHighSurrogate() -> i + 1 < value.length && value[i + 1].isLowSurrogate()
                value[i].isLowSurrogate() -> i > 0 && value[i - 1].isHighSurrogate(); else -> true }
        }) { "请填写可读的专题名，最多 80 个字符" }
    }
    fun selection(owner: Long, ids: List<Long>, articles: List<WorldArticleEntity>): List<WorldArticleEntity> {
        require(ids.isNotEmpty() && ids.size <= WorldOutside.MAX_ITEMS && ids.distinct().size == ids.size && ids.all { it > 0 }) { "请选择不重复的窗外见闻" }
        val eligible = articles.filter { it.companionId == owner && it.space == "outside" }.associateBy { it.id }
        return ids.map { eligible[it] ?: throw IllegalArgumentException("见闻已移除或归属已变化，请重新选择") }
    }
    fun articles(owner: Long?, topic: WorldJourneyTopicEntity?, items: List<WorldJourneyItemEntity>, articles: List<WorldArticleEntity>): List<WorldArticleEntity> {
        if (owner == null || topic == null || topic.companionId != owner) return emptyList()
        val eligible = articles.filter { it.companionId == owner && it.space == "outside" }.associateBy { it.id }
        return items.filter { it.topicId == topic.id && it.companionId == owner }.sortedBy { it.position }.distinctBy { it.articleId }.mapNotNull { eligible[it.articleId] }
    }
    fun validate(owners: Set<Long>, topics: List<WorldJourneyTopicEntity>, items: List<WorldJourneyItemEntity>, articles: List<WorldArticleEntity>) {
        require(topics.size <= MAX_TOPICS && items.size <= MAX_TOPICS * WorldOutside.MAX_ITEMS && topics.map { it.id }.distinct().size == topics.size) { "专题数量或编号无效" }
        val byId = topics.associateBy { it.id }; val sources = articles.associateBy { it.id }
        topics.forEach { require(it.id > 0 && it.companionId in owners && it.title == title(it.title) && it.kind in kinds && it.createdAt > 0 && it.updatedAt >= it.createdAt) { "专题资料无效" } }
        require(items.distinctBy { it.topicId to it.articleId }.size == items.size && items.distinctBy { it.topicId to it.position }.size == items.size) { "专题顺序或关联重复" }
        items.forEach { row ->
            val topic = byId[row.topicId]; val article = sources[row.articleId]
            require(row.position in 0 until WorldOutside.MAX_ITEMS && topic != null && article != null && row.companionId == topic.companionId &&
                article.companionId == row.companionId && article.space == "outside") { "专题包含已移除、私人或其他 TA 的资料" }
        }
    }
}
