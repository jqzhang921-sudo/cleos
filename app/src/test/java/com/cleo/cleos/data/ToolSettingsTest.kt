package com.cleo.cleos.data

import com.cleo.cleos.ai.ToolGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolSettingsTest {
    @Test
    fun phoneSharingIsOffForNewAndOldSettingsAndRoundTripsIndependently() {
        val phone = setOf(ToolGroup.PhoneBattery, ToolGroup.PhoneUsage)
        assertEquals(emptySet<ToolGroup>(), AppSettings().tools.intersect(phone))
        val old = ToolGroup.entries.filter { it !in phone }.joinToString(",") { "${it.name}:on" }
        assertEquals(emptySet<ToolGroup>(), decodeTools(old).intersect(phone))
        assertEquals(emptySet<ToolGroup>(), decodeTools("Todos,Weather").intersect(phone))
        for (chosen in listOf(emptySet(), setOf(ToolGroup.PhoneBattery), setOf(ToolGroup.PhoneUsage), phone))
            assertEquals(chosen, decodeTools(encodeTools(chosen)))
    }
    @Test
    fun worldRecallIsOffForOldSettingsAndRoundTripsWithoutChangingMemoryOrPhotoPermissions() {
        val previous = ToolGroup.entries.filter { it != ToolGroup.WorldRecall }.joinToString(",") { "${it.name}:on" }
        assertEquals(ToolGroup.entries.toSet() - ToolGroup.WorldRecall, decodeTools(previous))
        assertEquals(setOf(ToolGroup.WorldRecall), decodeTools(encodeTools(setOf(ToolGroup.WorldRecall))))
        assertEquals(false, ToolGroup.WorldRecall in decodeTools("Todos,Weather"))
    }
    @Test
    fun feedReadingIsAvailableForExistingSettingsAndCanBeDisabledSeparately() {
        val previous = ToolGroup.entries.filter { it != ToolGroup.Feed }.joinToString(",") { "${it.name}:off" }
        assertEquals(setOf(ToolGroup.Feed), decodeTools(previous))
        assertEquals(emptySet<ToolGroup>(), decodeTools(encodeTools(emptySet())))
    }
    @Test
    fun choicesSurviveAndGroupsAddedLaterGetTheirDefault() {
        val chosen = setOf(ToolGroup.Todos, ToolGroup.Diary)
        assertEquals(chosen, decodeTools(encodeTools(chosen)))
        // Written before Secrets, Avatar, Messages, Memory, Lore and the rest existed: never
        // chosen, so they take their default (on).
        assertEquals(
            chosen + ToolGroup.Secrets + ToolGroup.Avatar + ToolGroup.Messages + ToolGroup.Letters +
                ToolGroup.Memory + ToolGroup.Lore + ToolGroup.Alarm + ToolGroup.Stickers + ToolGroup.Pat + ToolGroup.Feed + ToolGroup.FeedActions,
            decodeTools("Todos:on,Diary:on,AiDiary:off,Weather:off"),
        )
    }

    @Test
    fun whatVersion030WroteStillReads() {
        // 0.3.0 listed only the groups that were on, and knew Todos, Diary and Weather.
        val newer = setOf(
            ToolGroup.AiDiary, ToolGroup.Secrets, ToolGroup.Avatar, ToolGroup.Messages, ToolGroup.Letters,
            ToolGroup.Memory, ToolGroup.Lore, ToolGroup.Alarm, ToolGroup.Stickers, ToolGroup.Pat, ToolGroup.Feed, ToolGroup.FeedActions,
        )
        assertEquals(setOf(ToolGroup.Todos, ToolGroup.Weather) + newer, decodeTools("Todos,Weather"))
        // Everything switched off then stays off; the newer groups still start on.
        assertEquals(newer, decodeTools(""))
    }

    @Test
    fun unknownNamesAreSkipped() {
        assertEquals(AppSettings().tools - ToolGroup.Todos, decodeTools("Todos:off,Teleport:on"))
    }

    @Test
    fun version069KeepsReadChoiceAndAddsChatActionsWithoutChangingOtherSwitches() {
        val previous = ToolGroup.entries.filter { it != ToolGroup.FeedActions }.joinToString(",") { "${it.name}:off" }
        assertEquals(setOf(ToolGroup.FeedActions), decodeTools(previous))
        assertEquals(setOf(ToolGroup.Feed), decodeTools(encodeTools(setOf(ToolGroup.Feed))))
    }
}
