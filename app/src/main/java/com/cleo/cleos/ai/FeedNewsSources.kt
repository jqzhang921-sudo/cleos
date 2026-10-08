package com.cleo.cleos.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class FeedNewsSource(val url: String, val name: String)
data class FeedNewsGroup(val id: String, val label: String, val description: String, val feeds: List<FeedNewsSource>)
data class FeedNewsStatus(val label: String, val count: Int)
data class FeedNewsBatch(val items: List<FeedNewsItem>, val statuses: List<FeedNewsStatus>)

/** The person bounds the subjects; the model chooses among a balanced set of real articles. */
object FeedNewsSources {
    const val CUSTOM = "custom"
    val GROUPS = listOf(
        FeedNewsGroup("cats", "猫猫宠物", "Modern Cat / Cats.com · 猫咪趣事与日常", listOf(
            FeedNewsSource("https://moderncat.com/feed/", "Modern Cat"), FeedNewsSource("https://cats.com/feed", "Cats.com"))),
        FeedNewsGroup("travel", "旅行风景", "Condé Nast Traveler · 旅行故事与目的地", listOf(FeedNewsSource("https://www.cntraveler.com/feed/rss", "Condé Nast Traveler"))),
        FeedNewsGroup("food", "美食厨房", "The Kitchn / RecipeTin Eats · 食谱与厨房灵感", listOf(
            FeedNewsSource("https://www.thekitchn.com/main.rss", "The Kitchn"), FeedNewsSource("https://www.recipetineats.com/feed/", "RecipeTin Eats"))),
        FeedNewsGroup("tech", "科技数码", "少数派 / The Verge · 数字生活与科技", listOf(
            FeedNewsSource("https://sspai.com/feed", "少数派"), FeedNewsSource("https://www.theverge.com/rss/index.xml", "The Verge"))),
        FeedNewsGroup("space", "科学太空", "NASA · 科学与太空发现", listOf(FeedNewsSource("https://www.nasa.gov/news-release/feed/", "NASA"))),
    )
    private val known = GROUPS.map { it.id }.toSet() + CUSTOM

    // A missing preference preserves old behaviour, including a previously entered custom feed.
    fun selected(raw: String, customUrl: String): Set<String> = if (raw.isBlank())
        setOf(if (customUrl.isBlank()) "space" else CUSTOM)
    else raw.split(',').map { it.trim() }.filter { it in known }.toSet()
    fun encode(ids: Set<String>) = (GROUPS.map { it.id } + CUSTOM).filter { it in ids }.joinToString(",")
    fun validate(ids: Set<String>, customUrl: String) {
        require(ids.isNotEmpty() && ids.all { it in known }) { "至少选择一个想看的方向" }
        if (CUSTOM in ids) {
            val url = customUrl.trim().toHttpUrlOrNull()
            require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() && customUrl.length <= 2000) { "自选订阅请填写有效的 https:// 地址" }
        }
    }
    fun groups(raw: String, customUrl: String): List<FeedNewsGroup> {
        val ids = selected(raw, customUrl)
        validate(ids, customUrl)
        return GROUPS.filter { it.id in ids } + if (CUSTOM in ids)
            listOf(FeedNewsGroup(CUSTOM, "自选订阅", "", listOf(FeedNewsSource(customUrl.trim(), "自选订阅")))) else emptyList()
    }
    fun summary(raw: String, customUrl: String) = selected(raw, customUrl).let { ids ->
        (GROUPS.filter { it.id in ids }.map { it.label } + if (CUSTOM in ids) listOf("自选订阅") else emptyList()).joinToString("、").ifBlank { "还没有选择方向" }
    }

    /** One article per category per round, so a fast-updating technology feed cannot fill the list. */
    fun balanced(lists: List<List<FeedNewsItem>>, excluded: Set<String> = emptySet(), maximum: Int = 12): List<FeedNewsItem> {
        val rows = lists.map { it.filterNot { item -> item.url in excluded } }
        val seen = mutableSetOf<String>()
        return buildList {
            for (i in 0 until (rows.maxOfOrNull { it.size } ?: 0)) {
                for (row in rows) row.getOrNull(i)?.let { if (size < maximum.coerceIn(1, 12) && seen.add(it.url)) add(it) }
            }
        }
    }

    suspend fun collect(groups: List<FeedNewsGroup>, excluded: Set<String> = emptySet(), maximum: Int = 12,
        read: suspend (FeedNewsSource) -> List<FeedNewsItem>): FeedNewsBatch = coroutineScope {
        val slots = Semaphore(3)
        val results = groups.map { group -> async {
            val items = slots.withPermit {
                withTimeoutOrNull(25_000) {
                    var found = emptyList<FeedNewsItem>()
                    for (source in group.feeds) {
                        try { found = read(source).map { it.copy(category = group.label) } }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { /* Another source in this category may still be reachable. */ }
                        if (found.isNotEmpty()) break
                    }
                    found
                }.orEmpty()
            }
            items to FeedNewsStatus(group.label, items.size)
        } }.awaitAll()
        FeedNewsBatch(balanced(results.map { it.first }, excluded, maximum), results.map { it.second })
    }
}
