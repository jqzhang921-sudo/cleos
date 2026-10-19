package com.cleo.cleos.data

import com.cleo.cleos.data.StickerText.Piece
import com.cleo.cleos.data.db.StickerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerTextTest {
    private fun sticker(id: Long, name: String, description: String = "", aliases: List<String> = emptyList()) = StickerEntity(
        id = id,
        name = name,
        description = description,
        file = "sticker-$id.png",
        width = 100,
        height = 100,
        aliases = StickerBook.encodeAliases(aliases),
        createdAt = id,
    )

    private val dizzy = sticker(1, "兔子晕倒", "转圈圈倒下", aliases = listOf("晕倒兔"))
    private val hug = sticker(2, "抱抱")
    private val book = StickerBook(listOf(dizzy, hug))

    @Test fun standaloneModelShortNamesUseRealPicturesAndUnknownNamesAreExplicitlyUnavailable() {
        val fixed = StickerText.normalizeModelTokens("好呀\n[[晕倒兔]]\n[[没有这张]]", book)
        assertEquals("好呀\n[[sticker:兔子晕倒]]\n[[sticker:没有这张]]", fixed)
        assertEquals(listOf(Piece.Words("好呀"), Piece.Sticker(dizzy), Piece.Unavailable("没有这张")), StickerText.split(fixed, book))
        assertFalse(StickerText.forHistory(fixed, book, true).contains("[[没有这张]]"))
    }

    @Test fun ordinaryActionsInlineQuotesAndCanonicalTokensAreNotRewritten() {
        val words = "[摸摸]\n引用 [[抱抱]] 是一句话\n[[sticker:抱抱]]\n[[other:123]]"
        assertEquals(words, StickerText.normalizeModelTokens(words, book))
        // User messages never opt into model-marker normalization.
        assertEquals(listOf(Piece.Words("[[抱抱]]")), StickerText.split("[[抱抱]]", book))
    }

    @Test
    fun wordsAndStickersComeApartInOrder() {
        assertEquals(
            listOf(Piece.Words("哈哈"), Piece.Sticker(dizzy), Piece.Words("你太逗了")),
            StickerText.split("哈哈\n[[sticker:兔子晕倒]]\n你太逗了", book),
        )
        assertEquals(listOf(Piece.Sticker(hug), Piece.Sticker(dizzy)), StickerText.split("[[sticker:抱抱]][[sticker:兔子晕倒]]", book))
    }

    @Test
    fun wordsWithoutStickersStayOnePieceUntouched() {
        assertEquals(listOf(Piece.Words("你好 [在吗]\n")), StickerText.split("你好 [在吗]\n", book))
        assertEquals(emptyList<Piece>(), StickerText.split("", book))
    }

    @Test
    fun theSpellingsAModelDriftsToAreRead() {
        for (t in listOf("[[表情包：兔子晕倒]]", "[[ sticker : 兔子晕倒 ]]", "[[STICKER:兔子晕倒]]", "[[表情:兔子晕倒]]")) {
            assertEquals(t, listOf(Piece.Sticker(dizzy)), StickerText.split(t, book))
        }
    }

    @Test
    fun theLooseFormCountsOnlyOnALineOfItsOwnAndUnknownNamesAreUnavailable() {
        assertEquals(listOf(Piece.Sticker(dizzy)), StickerText.split("[表情包：兔子晕倒]", book))
        assertEquals(listOf(Piece.Words("好呀"), Piece.Sticker(hug)), StickerText.split("好呀\n【表情包：抱抱】", book))
        assertEquals(listOf(Piece.Unavailable("猫猫")), StickerText.split("【表情包：猫猫】", book))
        // Talking about one is not sending it.
        val about = "你给我贴的「哈哈 [表情包：兔子晕倒]…」我看到了"
        assertEquals(listOf(Piece.Words(about)), StickerText.split(about, book))
    }

    @Test
    fun aNameNoLongerThereIsNotPresentedAsASuccessfulPicture() {
        assertEquals(listOf(Piece.Words("看"), Piece.Unavailable("不在了"), Piece.Words("这个")),
            StickerText.split("看 [[sticker:不在了]] 这个", book))
    }

    @Test
    fun bracketedActionsAndOrdinaryQuotedDescriptionsStayText() {
        for (text in listOf("[摸摸]", "[抱抱]", "代码里 [0] 是首项", "他说【表情包：猫猫】很好笑")) {
            assertEquals(listOf(Piece.Words(text)), StickerText.split(text, book))
            assertEquals(text, StickerText.forHistory(text, book, sendTokens = true))
        }
    }

    @Test
    fun unknownNamesCannotBeReplayedAsSuccessfullySentStickers() {
        for (send in listOf(true, false)) {
            assertEquals("好呀\n（表情包不可用：猫猫，本次没有可用的对应图片）\n继续聊", StickerText.forHistory(
                "好呀\n[[sticker:猫猫]]\n继续聊", book, send))
            assertEquals("（表情包不可用：猫猫，本次没有可用的对应图片）", StickerText.forHistory("【表情包：猫猫】", book, send))
        }
        assertEquals("[[sticker:兔子晕倒]]", StickerText.forHistory("[[sticker:晕倒兔]]", book, sendTokens = true))
        assertEquals("（表情包不可用：抱抱，本次没有可用的对应图片）",
            StickerText.forHistory("[[sticker:抱抱]]", StickerBook.EMPTY, sendTokens = true))
    }

    @Test
    fun namesAreFoundByOldNameByKeyAndByTheOneTheyAreIn() {
        assertEquals(dizzy, book.find("晕倒兔"))
        assertEquals(dizzy, book.find(" 兔子 晕倒！"))
        assertEquals(dizzy, book.find("兔子"))
        assertNull("one character finds too much", book.find("兔"))
        val two = StickerBook(listOf(dizzy, sticker(3, "兔子跳舞")))
        assertNull("two have it in them: neither", two.find("兔子"))
    }

    @Test
    fun aStickersOwnNameWinsOverAnothersOldOne() {
        val happy = sticker(4, "开心")
        val renamed = sticker(5, "很开心", aliases = listOf("开心"))
        assertEquals(happy, StickerBook(listOf(renamed, happy)).find("开心"))
    }

    @Test
    fun aNameIsTakenByNamesAndOldNamesButNotByItsOwn() {
        assertTrue(book.taken("晕倒兔"))
        assertTrue(book.taken("兔子 晕倒"))
        assertFalse(book.taken("兔子晕倒", except = dizzy.id))
        assertFalse(book.taken("晕倒兔", except = dizzy.id))
        assertFalse(book.taken("小猫"))
    }

    @Test
    fun whereOnlyWordsShowAStickerIsItsName() {
        assertEquals("[表情包：兔子晕倒] 哈", StickerText.plain("[[sticker:兔子晕倒]] 哈"))
        assertEquals("没有表情包", StickerText.plain("没有表情包"))
        assertEquals("兔子晕倒", StickerText.only(" [[sticker:兔子晕倒]]\n"))
        assertNull(StickerText.only("哈 [[sticker:兔子晕倒]]"))
    }

    @Test
    fun aTokenStillComingInIsHeldBack() {
        assertEquals("哈哈", StickerText.finishedPart("哈哈[[stic"))
        assertEquals("哈哈", StickerText.finishedPart("哈哈["))
        assertEquals("哈哈[[sticker:抱抱]]", StickerText.finishedPart("哈哈[[sticker:抱抱]]"))
        assertEquals("[[sticker:抱抱]] 再", StickerText.finishedPart("[[sticker:抱抱]] 再[[sticker:兔"))
    }

    @Test
    fun aTaNotSendingStickersIsToldWhatWasSentInWords() {
        assertEquals("（发了一张表情包：兔子晕倒，转圈圈倒下）", StickerText.described("[[sticker:兔子晕倒]]", book))
        assertEquals("好（发了一张表情包：抱抱）", StickerText.described("好[[sticker:抱抱]]", book))
    }

    @Test
    fun theListHasEachNameAndWhatIsInThePicture() {
        assertEquals("兔子晕倒：转圈圈倒下\n抱抱", StickerText.menu(listOf(dizzy, hug)))
        val many = (1L..(StickerText.MAX_LISTED + 5)).map { sticker(it, "图$it") }
        assertEquals(StickerText.MAX_LISTED, StickerText.menu(many).lines().size)
    }

    @Test
    fun aReactionGoesOnAndComesOff() {
        val on = MessageReactions.toggle(emptyList(), "❤️", at = 10)
        assertEquals(listOf(MessageReaction("❤️", 10)), on)
        assertEquals(on, MessageReactions.decode(MessageReactions.encode(on)))
        assertEquals(emptyList<MessageReaction>(), MessageReactions.toggle(on, "❤️", at = 11))
        assertNull(MessageReactions.encode(emptyList()))
        assertEquals(emptyList<MessageReaction>(), MessageReactions.decode("not json"))
    }
}
