package com.cleo.cleos.data

import com.cleo.cleos.data.db.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorldJourneyTopicsTest {
    private val a = WorldArticleEntity(1,7,"Sea","Source","A source", "f", 1,space="outside")
    private val b = a.copy(id=2,title="Sky",fingerprint="g")
    private val topic = WorldJourneyTopicEntity(1,7,"A small world","place",10,20)
    private val rows = listOf(WorldJourneyItemEntity(1,2,7,0),WorldJourneyItemEntity(1,1,7,1))
    private fun valid(topics:List<WorldJourneyTopicEntity> = listOf(topic), items:List<WorldJourneyItemEntity> = rows,
        articles:List<WorldArticleEntity> = listOf(a,b)) = WorldJourneyTopics.validate(setOf(7),topics,items,articles)
    private fun reject(block:()->Unit) = assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException)

    @Test fun selectionPreservesExplicitOrderAndRejectsOtherTasBooksDuplicatesMissingAndEmptyRows() {
        assertEquals(listOf(b,a),WorldJourneyTopics.selection(7,listOf(2,1),listOf(a,b)))
        val all=listOf(a,b,a.copy(id=3,companionId=8),a.copy(id=4,space="study"))
        for(ids in listOf(emptyList(),listOf(1L,1L),listOf(3L),listOf(4L),listOf(99L),listOf(0L),(1L..61).toList()))
            reject { WorldJourneyTopics.selection(7,ids,all) }
    }
    @Test fun projectionsRetainSavedOrderAndNeverResurrectRemovedOrPrivateSources() {
        assertEquals(listOf(b,a),WorldJourneyTopics.articles(7,topic,rows,listOf(a,b)))
        assertEquals(listOf(a),WorldJourneyTopics.articles(7,topic,rows,listOf(a)))
        assertTrue(WorldJourneyTopics.articles(8,topic,rows,listOf(a,b)).isEmpty())
        assertTrue(WorldJourneyTopics.articles(7,topic,rows,listOf(a.copy(space="study"),b.copy(companionId=8))).isEmpty())
        assertTrue(WorldJourneyTopics.articles(null,topic,rows,listOf(a,b)).isEmpty())
    }
    @Test fun emptySavedTopicsAndGapsAfterArticleDeletionRemainValid() {
        valid(items=emptyList());valid(items=listOf(rows[1]),articles=listOf(a))
        assertEquals(topic.title,"A small world")
    }
    @Test fun backupRejectsCrossOwnerOrphanAndDuplicateConnections() {
        valid()
        for(items in listOf(rows+rows[0],rows+rows[0].copy(articleId=1),rows.map {it.copy(companionId=8)},rows.map {it.copy(topicId=99)},rows.map {it.copy(position=-1)},rows.map {it.copy(position=60)}))
            reject { valid(items=items) }
        reject {valid(articles=listOf(a,b.copy(companionId=8)))}
        reject {valid(articles=listOf(a,b.copy(space="study")))}
        reject {valid(articles=listOf(a))}
    }
    @Test fun invalidTopicMetadataAndBrokenUnicodeCannotEnterBackup() {
        for(bad in listOf(topic.copy(id=0),topic.copy(companionId=9),topic.copy(title=" "),topic.copy(title=" line "),topic.copy(title="x\nline"),
            topic.copy(title="x".repeat(81)),topic.copy(title="\uD83C"),topic.copy(kind="visited"),topic.copy(createdAt=0),topic.copy(updatedAt=9)))
            reject { valid(topics=listOf(bad)) }
        reject { valid(topics=listOf(topic,topic)) }
        assertEquals("Sea 🌊",WorldJourneyTopics.title("  Sea 🌊  "))
    }
    @Test fun legacyBackupsDefaultEmptyAndNewFormatRoundTripsOrderedTopicsWithoutPermissions() {
        val settings=BackupSettings(apiBaseUrl="https://synthetic.invalid/v1",apiModel="synthetic",aiName="TA",userName="User",persona="",historySize=10,
            wallpaper=null,glassMode="liquid",wallpaperDark=null,wallpaperHue=null,wallpaperChroma=null)
        val backup=BackupFile(BackupFile.FORMAT,BackupFile.VERSION,1,settings,emptyList(),emptyList(),emptyList(),emptyList(),
            worldArticles=listOf(a,b),worldJourneyTopics=listOf(topic),worldJourneyItems=rows)
        val json=Json { encodeDefaults=true;ignoreUnknownKeys=true }
        val raw=json.encodeToString(BackupFile.serializer(),backup)
        val restored=json.decodeFromString(BackupFile.serializer(),raw)
        assertEquals(listOf(topic),restored.worldJourneyTopics);assertEquals(rows,restored.worldJourneyItems);assertEquals(BackupFile.VERSION,restored.version);assertTrue(restored.version >= 26)
        val old=Json.parseToJsonElement(raw).jsonObject.toMutableMap().apply {remove("worldJourneyTopics");remove("worldJourneyItems");put("version",JsonPrimitive(25))}
        val legacy=json.decodeFromString(BackupFile.serializer(),JsonObject(old).toString())
        assertTrue(legacy.worldJourneyTopics.isEmpty());assertTrue(legacy.worldJourneyItems.isEmpty())
        assertFalse(raw.contains("journeySchedules"));assertFalse(raw.contains("journeyPermission"))
        assertTrue(BackupSummary(1,0,0,0,0,0,0,worldJourneyTopics=1).toString().contains("1 份图文专题"))
    }
}
