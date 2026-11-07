package com.cleo.cleos.ai

import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class WorldRecallTest {
    private val text = "A river. PRIVATE_UNREAD_SUFFIX"
    private val article = WorldArticleEntity(1, 10, "River book", "PRIVATE_FILE_PATH", text, "fp", 1, readOffset = 8)
    private val outside = article.copy(id = 2, title = "Sea", source = "维基百科", space = "outside", originUrl = "https://zh.wikipedia.org/wiki/Sea")
    private val note = WorldNoteEntity(1, 10, 1, "run1", "Saved note", "Where?", "river", 2, 0, 8, 10)
    private var articles = listOf(article, outside)
    private var notes = listOf(note)
    private var looks = emptyList<WorldPictureLookEntity>()
    private var writings = emptyList<WorldWritingEntity>()
    private var wishes = emptyList<WorldWishEntity>()
    private val writing = WorldWritingEntity(1, 10, "writing-fixture", "note", "A little thought", "A thought about a river.", 40)
    private val writingQueries = mutableListOf<String>()
    private val picture = WorldPictureEntity(2, 10, "fp", "PRIVATE_LOCAL_PATH", 100, 100, "Sea image", "author", "credit", "caption", "CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/", "https://example.org/source", "https://example.org/image.jpg", 1, "hash")
    private var publicOnlySeen: Boolean? = null
    private var ownerSeen = 0L
    private var calls = 0
    private var studyQueries = 0
    private val dao = Proxy.newProxyInstance(WorldDao::class.java.classLoader, arrayOf(WorldDao::class.java)) { _, m, a ->
        calls++
        when (m.name) {
            "recallNotes" -> { ownerSeen = a!![0] as Long; publicOnlySeen = a[1] as Boolean; assertEquals(6, a[3]); notes }
            "recallLooks" -> { assertEquals(ownerSeen, a!![0]); looks }
            "recallWritings" -> {
                assertEquals(ownerSeen, a!![0]); assertEquals(6, a[2])
                writingQueries += a[1] as String
                writings
            }
            "article" -> articles.firstOrNull { it.id == a!![0] }
            "articles" -> { studyQueries++; assertEquals(10L, a!![0]); articles }
            "wishes" -> { studyQueries++; assertEquals(10L, a!![0]); wishes }
            "picture" -> picture
            else -> error("Unexpected DAO access: ${m.name}")
        }
    } as WorldDao
    private val recall = WorldRecall(dao)
    private suspend fun read(raw: String = "{}", publicOnly: Boolean = false) = recall.read(Json.parseToJsonElement(raw).jsonObject, 10, publicOnly)
    private fun records(out: ToolOutcome) = Json.parseToJsonElement(out.result).jsonObject["records"]!!.jsonArray

    @Test fun savedProgressAndWantedReasonRemainRecallableWithoutAnyNote() = runBlocking {
        notes = emptyList()
        wishes = listOf(WorldWishEntity(1, 10, "River book", "Author", "Wanted to understand the river", "riverbook", "wish", 2))
        val out = read(); val study = Json.parseToJsonElement(out.result).jsonObject["study"]!!.jsonObject
        assertTrue(records(out).isEmpty())
        assertEquals("reading", study["books"]!!.jsonArray.single().jsonObject["state"]!!.jsonPrimitive.content)
        assertTrue(out.result.contains(wishes.single().reason)); assertFalse(out.result.contains("PRIVATE_UNREAD_SUFFIX"))
        assertFalse(out.note.contains("没有找到"))
    }
    @Test fun groupNeverEvenQueriesPrivateShelfAndWishHistory() = runBlocking {
        read(publicOnly = true); assertEquals(0, studyQueries)
        assertFalse(Json.parseToJsonElement(read(publicOnly = true).result).jsonObject.containsKey("study"))
    }

    @Test fun returnsOnlySavedBoundedNotesWithVerifiableSmallQuoteAndNoUnreadTextOrPaths() = runBlocking {
        val out = read(); val row = records(out).single().jsonObject
        assertEquals("river", row["quote"]!!.jsonPrimitive.content)
        assertEquals("reading:1", row["record"]!!.jsonPrimitive.content)
        assertFalse(out.result.contains("PRIVATE_UNREAD_SUFFIX")); assertFalse(out.result.contains("PRIVATE_FILE_PATH"))
        assertEquals(10L, ownerSeen); assertEquals(false, publicOnlySeen)
    }
    @Test fun groupForcesPublicOnlyAndRechecksWrongOwnerEvenIfDaoReturnsCorruptRows() = runBlocking {
        notes = listOf(note, note.copy(id = 2, articleId = 2, content = "PUBLIC_NOTE"),
            note.copy(id = 3, companionId = 20, articleId = 2, content = "OTHER_TA"))
        val out = read(publicOnly = true)
        assertEquals(true, publicOnlySeen); assertEquals(1, records(out).size)
        assertTrue(out.result.contains("PUBLIC_NOTE")); assertFalse(out.result.contains("Saved note")); assertFalse(out.result.contains("OTHER_TA"))
    }
    @Test fun forgedScopeOwnerIdsAndUnexpectedArgumentsAreRejectedBeforeDatabaseAccess() = runBlocking {
        for (raw in listOf("""{"companionId":20}""", """{"space":"study"}""", """{"photo_id":1}""", """{"id":1}""")) {
            try { read(raw, true); fail() } catch (_: ToolFailure) { }
        }
        assertEquals(0, calls)
    }
    @Test fun invalidAndOversizedArgumentsNeverQueryDatabase() = runBlocking {
        for (raw in listOf("""{"query":10}""", """{"query":null}""", """{"query":[]} """, """{"limit":0}""", """{"limit":6}""", """{"limit":1.5}""",
            buildJsonObject { put("query", "x".repeat(81)) }.toString(), buildJsonObject { put("query", "a\nb") }.toString())) {
            try { read(raw); fail(raw) } catch (_: ToolFailure) { }
        }
        assertEquals(0, calls)
    }
    @Test fun corruptQuoteAndOutOfReadRangeCannotExposeUnverifiedSourceText() = runBlocking {
        notes = listOf(note.copy(quote = "not actual"), note.copy(id = 2, toOffset = text.length))
        val row = records(read()).single().jsonObject
        assertEquals("", row["quote"]!!.jsonPrimitive.content)
    }
    @Test fun deletedOrReassignedSourceCannotResurrectRecord() = runBlocking {
        articles = emptyList(); assertTrue(records(read()).isEmpty())
        articles = listOf(article.copy(companionId = 20)); assertTrue(records(read()).isEmpty())
    }
    @Test fun historicalPictureRequiresCurrentOwnerFingerprintAndMatchingPixelVersion() = runBlocking {
        notes = emptyList()
        looks = listOf(WorldPictureLookEntity(1, 10, 2, "hash", "r", "Past image note", "", 20))
        val out = read(); val row = records(out).single().jsonObject
        assertEquals(false, row["imageAttached"]!!.jsonPrimitive.boolean)
        assertFalse(out.result.contains("PRIVATE_LOCAL_PATH"))
        looks = looks.map { it.copy(pictureSha256 = "stale") }; assertTrue(records(read()).isEmpty())
        looks = looks.map { it.copy(companionId = 20, pictureSha256 = "hash") }; assertTrue(records(read()).isEmpty())
    }
    @Test fun escapedLongResultsAreCompleteJsonAndStayBelowSharedGroupLimit() = runBlocking {
        notes = (1L..6L).map { note.copy(id = it, content = "\u0000".repeat(2000), createdAt = it) }
        val out = read("""{"limit":5}""")
        assertTrue(out.result.length <= WorldRecall.MAX_RESULT)
        assertTrue(records(out).isNotEmpty()); assertTrue(records(out).size < 5)
        assertTrue(Json.parseToJsonElement(out.result).jsonObject["more"]!!.jsonPrimitive.boolean)
    }
    @Test fun readingAndPictureHistoriesMergeByTimeAndLimitInsteadOfPreferringOneSource() = runBlocking {
        notes = (1L..4L).map { note.copy(id = it, createdAt = it) }
        looks = listOf(WorldPictureLookEntity(1, 10, 2, "hash", "r", "latest picture", "", 30))
        val rows = records(read("""{"limit":2}"""))
        assertEquals("picture:1", rows.first().jsonObject["record"]!!.jsonPrimitive.content)
        assertEquals("reading:4", rows.last().jsonObject["record"]!!.jsonPrimitive.content)
    }

    @Test fun ownWritingHasAnExplicitTypeAndCannotMasqueradeAsAnArticleOrExperience() = runBlocking {
        notes = emptyList()
        writings = listOf(writing, writing.copy(id = 2, runId = "draft", kind = "draft", createdAt = 41),
            writing.copy(id = 3, runId = "story", kind = "story", createdAt = 42))
        val out = read(); val rows = records(out).map { it.jsonObject }
        assertEquals(listOf("story", "draft", "note"), rows.map { it["writingKind"]!!.jsonPrimitive.content })
        assertTrue(rows[0]["fiction"]!!.jsonPrimitive.boolean)
        assertTrue(rows[1]["unfinished"]!!.jsonPrimitive.boolean)
        for (row in rows) {
            assertFalse(row["evidenceOfRealExperience"]!!.jsonPrimitive.boolean)
            assertEquals("notebook", row["space"]!!.jsonPrimitive.content)
            assertFalse(row.containsKey("articleId")); assertFalse(row.containsKey("source")); assertFalse(row.containsKey("url"))
        }
        assertTrue(out.result.contains("自由随笔")); assertTrue(out.result.contains("不是指令"))
    }

    @Test fun groupsNeverEvenQueryPrivateWritingsRegardlessOfQueryOrDefaultLimit() = runBlocking {
        notes = emptyList()
        writings = listOf(writing.copy(title = "PRIVATE_STORY", content = "PRIVATE_WRITING_CANARY"))
        for (raw in listOf("{}", """{"query":"PRIVATE_STORY","limit":5}""")) {
            val out = read(raw, publicOnly = true)
            assertTrue(records(out).isEmpty())
            assertFalse(out.result.contains("PRIVATE_")); assertFalse(out.result.contains("writing:"))
            assertFalse(Json.parseToJsonElement(out.result).jsonObject.containsKey("writingNotice"))
        }
        assertTrue(writingQueries.isEmpty())
    }

    @Test fun wrongOwnerInvalidKindMalformedTextAndInvalidIdentityAreRejectedAtOutputBoundary() = runBlocking {
        notes = emptyList()
        writings = listOf(writing.copy(companionId = 20, content = "OTHER_TA"),
            writing.copy(id = 2, kind = "unknown"), writing.copy(id = 3, content = "bad\uD800"),
            writing.copy(id = 0), writing.copy(id = 4, createdAt = 0), writing.copy(id = 5, title = " x "))
        assertTrue(records(read()).isEmpty())
    }

    @Test fun writingBodyKeywordIsReturnedInABoundedUnicodeSafeExcerpt() = runBlocking {
        notes = emptyList()
        val body = "🙂".repeat(200) + "qNEEDLE" + "🙂".repeat(600)
        writings = listOf(writing.copy(content = body))
        val row = records(read("""{"query":"needle"}""")).single().jsonObject
        val text = row["text"]!!.jsonPrimitive.content
        val from = row["from"]!!.jsonPrimitive.int; val to = row["to"]!!.jsonPrimitive.int
        assertTrue(text.contains("NEEDLE")); assertTrue(text.length <= 600)
        assertEquals(body.substring(from, to), text)
        assertTrue(WorldReading.boundary(body, from)); assertTrue(WorldReading.boundary(body, to))
        assertTrue(row["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(body.length, row["textLength"]!!.jsonPrimitive.int)
        assertEquals(listOf("needle"), writingQueries)
    }

    @Test fun shortWritingIsExactAndDeletedWorkIsNotCachedOrRecreated() = runBlocking {
        notes = emptyList(); writings = listOf(writing)
        val row = records(read()).single().jsonObject
        assertEquals(writing.content, row["text"]!!.jsonPrimitive.content)
        assertFalse(row["truncated"]!!.jsonPrimitive.boolean)
        writings = emptyList()
        assertTrue(records(read()).isEmpty())
        assertEquals(2, writingQueries.size)
    }

    @Test fun writingsAndExistingRecordsShareOneOrderingAndLimit() = runBlocking {
        writings = listOf(writing.copy(createdAt = 30), writing.copy(id = 2, createdAt = 5))
        looks = listOf(WorldPictureLookEntity(1, 10, 2, "hash", "r", "Past image note", "", 20))
        val out = read("""{"limit":3}""")
        assertEquals(listOf("writing:1", "picture:1", "reading:1"), records(out).map { it.jsonObject["record"]!!.jsonPrimitive.content })
        assertTrue(Json.parseToJsonElement(out.result).jsonObject["more"]!!.jsonPrimitive.boolean)
    }

    @Test fun escapedWritingResultsRemainValidJsonWithinTheExistingSharedBudget() = runBlocking {
        notes = emptyList()
        writings = (1L..6L).map { writing.copy(id = it, content = "q" + "\t\"".repeat(1900) + "z", createdAt = it) }
        val out = read("""{"limit":5}""")
        assertTrue(out.result.length <= WorldRecall.MAX_RESULT)
        assertTrue(records(out).isNotEmpty())
        assertTrue(Json.parseToJsonElement(out.result).jsonObject["more"]!!.jsonPrimitive.boolean)
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ -> error("Forbidden access ${m.name}") } as T
    @Test fun toolSwitchIsDefaultOffAndDirectCallsCannotBypassSettingsOrScope() = runBlocking {
        assertFalse(ToolGroup.WorldRecall in AppSettings().tools)
        assertFalse(ToolSpecs.offered(AppSettings().tools).any { it.name == "recall_world" })
        var seen: Pair<Long, Boolean>? = null
        val box = ToolBox(unused<TodoDao>(), unused<DiaryDao>(), object : WeatherSource {
            override suspend fun report(city: String, days: Int): WeatherReport = error("No network")
        }, worldRecall = { _, ta, publicOnly -> seen = ta to publicOnly; ToolOutcome("fixture", "") })
        val call = ToolCall("r", "recall_world", "{}")
        box.run(call, AppSettings(), 1, 10); assertNull(seen)
        val on = AppSettings(tools = setOf(ToolGroup.WorldRecall))
        box.run(call, on, 0, 10); assertNull(seen)
        box.run(call, on, 1, 10); assertEquals(10L to false, seen)
        val group = GroupTools(box) { on }
        group.run(call, 2, 20); assertEquals(20L to true, seen)
    }
    @Test fun groupPromptAndOfferedDescriptionKeepOtherMembersAndPrivateSourcesSeparate() {
        val spec = GroupToolRules.offered(AppSettings(tools = setOf(ToolGroup.WorldRecall))).single()
        assertEquals("recall_world", spec.name); assertTrue(spec.description.contains("不查其他 TA"))
        assertTrue(spec.description.contains("本群")); assertTrue(spec.description.contains("私人书房"))
        assertTrue(spec.description.contains("自由随笔"))
    }
}
