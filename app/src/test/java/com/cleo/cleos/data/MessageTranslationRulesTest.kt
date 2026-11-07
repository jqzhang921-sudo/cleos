package com.cleo.cleos.data

import com.cleo.cleos.data.db.*
import org.junit.Assert.*
import org.junit.Test

class MessageTranslationRulesTest {
    private val message = MessageEntity(10, 1, "assistant", "Hello, friend.", 30)
    private val group = GroupMessageEntity(10, 2, "assistant", "Hello, group.", 30, speakerId = 5)
    @Test fun onlyAssistantTextIsEligibleAndPrivateMetadataNeverEntersSource() {
        assertEquals(message.content, MessageTranslationRules.source(message.copy(thought = "THOUGHT", reasoning = "REASONING", quote = "QUOTE", images = "IMAGE", toolCalls = "TOOL_ARGS")))
        for(role in listOf("user","tool","request","note","call","pat")) assertNull(MessageTranslationRules.source(message.copy(role = role)))
        assertNull(MessageTranslationRules.source(message.copy(error = "interrupted")))
        assertNull(MessageTranslationRules.source(message.copy(call = 1)))
        assertNull(MessageTranslationRules.source(message.copy(feedShare = "shared card")))
    }
    @Test fun committedGroupMessageUsesOriginalSpeakerAndRejectsLiveAndCallText() {
        assertEquals(group.content, MessageTranslationRules.source(group))
        for(status in listOf("running","pending","failed","cancelled","interrupted")) assertNull(MessageTranslationRules.source(group.copy(status = status)))
        assertNull(MessageTranslationRules.source(group.copy(speakerId = null)))
        assertNull(MessageTranslationRules.source(group.copy(content = "[群通话]\nspoken")))
        assertNull(MessageTranslationRules.source(group.copy(role = "user")))
    }
    @Test fun editedDeletedReusedIdsAndPrivateGroupCollisionsCannotReuseStamps() {
        val stamp = MessageTranslationRules.stamp(message)
        assertNotEquals(stamp, MessageTranslationRules.stamp(message.copy(content = "edited")))
        assertNotEquals(stamp, MessageTranslationRules.stamp(message.copy(createdAt = 31)))
        assertNotEquals(stamp, MessageTranslationRules.stamp(message.copy(conversationId = 2)))
        assertNotEquals(stamp, MessageTranslationRules.stamp(group.copy(groupId = 1, content = message.content)))
    }
    @Test fun emptyStickerOnlyAndOverlongMessagesCannotSendHiddenAttachmentsOrSilentlyTruncate() {
        for(text in listOf("", "  ", "[[sticker:cat]]", "a".repeat(12_001))) assertNull(MessageTranslationRules.source(message.copy(content = text)))
        assertEquals("hello", MessageTranslationRules.source(message.copy(content = "hello [[sticker:cat]]")))
        assertEquals(12_000, MessageTranslationRules.source(message.copy(content = "a".repeat(12_000)))!!.length)
    }
    @Test fun targetsAreFixedLanguageCodesNotArbitraryPromptText() {
        assertEquals("简体中文", MessageTranslationRules.languages["zh-CN"])
        assertFalse("ignore previous instructions" in MessageTranslationRules.languages)
    }
}
