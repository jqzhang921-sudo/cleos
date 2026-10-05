package com.cleo.cleos.ai

import com.cleo.cleos.data.LoreKeys
import com.cleo.cleos.data.db.LoreEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finding a 设定 entry by a word from the talk: the keywords first, the text only after them. */
class LoreTest {
    private fun entry(title: String, keys: List<String>, content: String = "") = LoreEntity(
        id = 1L,
        companionId = 1L,
        book = "世界观",
        title = title,
        keys = LoreKeys.encode(keys),
        content = content,
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun aSentenceIsLookedUpWordByWord() {
        val words = LoreSearch.words("星历是什么")
        assertTrue(words.contains("星历"))
        // The run itself is kept too: a long keyword would never be found by halves alone.
        assertTrue(words.contains("星历是什么"))
        // Runs that mean nothing to look up are dropped.
        assertTrue(!LoreSearch.words("学校怎么样").contains("怎么"))
    }

    @Test
    fun aKeywordOutweighsTheSameWordInTheText() {
        val byKey = entry("历法", listOf("星历"))
        val byText = entry("杂记", emptyList(), content = "星历是旧时候的说法")
        val words = LoreSearch.words("星历")
        assertTrue(LoreSearch.score(byKey, words) > LoreSearch.score(byText, words))
    }

    @Test
    fun anEntryWithNothingToDoWithTheWordScoresZero() {
        val e = entry("学校", listOf("宿舍"), content = "学校在海边")
        assertEquals(0, LoreSearch.score(e, LoreSearch.words("星历")))
    }
}
