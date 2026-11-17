package com.cleo.cleos.ai

import com.cleo.cleos.data.*
import com.cleo.cleos.data.db.FeedPostEntity
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FeedThreadContextTest {
    private val post = FeedPostEntity(id = 1, authorId = 0, content = "POST_ANCHOR", createdAt = 100)
    private fun rows(vararg comments: FeedComment) = post.copy(comments = FeedComments.encode(comments.toList()))
    private fun context(post: FeedPostEntity, reply: String?) = Json.parseToJsonElement(FeedThreadContext.render(post, 7, reply, mapOf(7L to "同名", 8L to "同名"), "用户")).jsonObject
    @Test fun oldParentsAreKeptInsteadOfUnrelatedRecentComments() {
        val parent = FeedComment("root", 0, "USER_ANCHOR", 100)
        val child = FeedComment("child", 8, "ACTUAL_REPLY", 101, "root")
        val unrelated = (1..20).map { FeedComment("noise-$it", 9, "UNRELATED_CANARY", 102) }
        val data = context(post.copy(comments = FeedComments.encode(listOf(parent, child) + unrelated)), "child")
        assertEquals(listOf("root", "child"), data["reply_chain"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertTrue(data.toString().contains("POST_ANCHOR")); assertTrue(data.toString().contains("USER_ANCHOR"))
        assertFalse(data.toString().contains("UNRELATED_CANARY")); assertFalse(data["context_incomplete"]!!.jsonPrimitive.boolean)
    }
    @Test fun namesDoNotReplaceStableIdentityAndSelfIsExplicit() {
        val data = context(rows(FeedComment("self", 7, "SELF", 1), FeedComment("friend", 8, "FRIEND", 2, "self")), "friend")
        val chain = data["reply_chain"]!!.jsonArray
        assertEquals("self", chain[0].jsonObject["author"]!!.jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("friend", chain[1].jsonObject["author"]!!.jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(8, data["target"]!!.jsonObject["author"]!!.jsonObject["id"]!!.jsonPrimitive.int)
        assertEquals(7, data["target"]!!.jsonObject["reply_to_author"]!!.jsonObject["id"]!!.jsonPrimitive.int)
        assertEquals("self", data["target"]!!.jsonObject["reply_to_author"]!!.jsonObject["role"]!!.jsonPrimitive.content)
        val root = context(rows(FeedComment("root", 8, "ROOT", 1)), "root")["target"]!!.jsonObject
        assertEquals(0, root["reply_to_author"]!!.jsonObject["id"]!!.jsonPrimitive.int)
        assertTrue(root["reply_to_post"]!!.jsonPrimitive.boolean)
    }
    @Test fun malformedOrCrossPostParentsAreMarkedMissingAndCyclesAreBounded() {
        for (p in listOf(rows(FeedComment("c", 8, "x", 1, "missing")), rows(FeedComment("c", 8, "x", 1, "c")))) {
            val data = context(p, "c")
            assertTrue(data["context_incomplete"]!!.jsonPrimitive.boolean); assertEquals(1, data["reply_chain"]!!.jsonArray.size)
        }
        assertTrue(context(post, "foreign-comment")["context_incomplete"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, context(post, null)["target"])
    }
    @Test fun longChainsAndUnicodeAreBoundedWithoutPretendingCompleteContext() {
        val chain = (0..12).map { FeedComment("c$it", 8, "x".repeat(999) + "😺", 1, if (it == 0) null else "c${it - 1}") }
        val data = context(post.copy(comments = FeedComments.encode(chain)), "c12")
        assertEquals(6, data["reply_chain"]!!.jsonArray.size); assertTrue(data["context_incomplete"]!!.jsonPrimitive.boolean)
        assertEquals(999, data["target"]!!.jsonObject["content"]!!.jsonPrimitive.content.length)
    }
    @Test fun automaticVisitsCanStillReplyToOtherFriends() {
        val bodyRead = post.copy(interactions = FeedInteractions.record(post, 7, null, false))
        val noise = FeedComment("unrelated", 8, "more props", 100)
        assertEquals("unrelated", FeedVisitRules.targets(listOf(bodyRead.copy(comments = FeedComments.encode(listOf(noise)))), 7, 100).single().replyTo)
        val own = FeedComment("mine", 7, "hello", 100)
        val addressed = FeedComment("to-me", 8, "reply", 100, "mine")
        assertEquals("to-me", FeedVisitRules.targets(listOf(bodyRead.copy(comments = FeedComments.encode(listOf(own, noise, addressed)))), 7, 100).single().replyTo)
    }
    @Test fun ownCommentCountDoesNotLimitNewReplyTargets() {
        val p = rows(FeedComment("a", 7, "one", 100), FeedComment("b", 8, "two", 100, "a"), FeedComment("c", 7, "three", 100, "b"), FeedComment("d", 8, "four", 100, "c"))
        assertEquals("d", FeedVisitRules.targets(listOf(p), 7, 100).single().replyTo)
        assertTrue(FeedInteractions.canInvite(p, 7, "d")) // Manual invitation is still available.
        val added = p.copy(comments = FeedComments.encode(FeedComments.decode(p.comments) + FeedComment("human", 0, "我也说一句", 100)))
        assertEquals("human", FeedVisitRules.targets(listOf(added), 7, 100).single().replyTo)
    }
    @Test fun promptsPermitSilenceAndAnchorRepliesWithoutBanningHumour() {
        val ta = com.cleo.cleos.data.db.CompanionEntity(id = 7, apiBaseUrl = "", apiModel = "", createdAt = 1)
        for (prompt in listOf(FeedAiRules.reactionSystem(ta, ""), FeedVisitRules.system(ta, "", false))) {
            assertTrue(prompt.contains(FeedThreadContext.RULE)); assertTrue(prompt.contains("A 回复 B 不等于 A 在回复你"))
        }
    }
}
