package com.cleo.cleos.ui.world

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test

class WorldNavigationModelTest {
    @Test fun deckPositionsRestoreByScopeAndOwnerWithoutProse() {
        val handle = SavedStateHandle(); val state = WorldNavigationModel(handle); state.select(7)
        state.saveNotebookDeck(0, "serial:1"); state.saveNotebookDeck(1, "writing:12"); state.notebookOverview.value = true
        val restored = WorldNavigationModel(handle)
        assertEquals("serial:1", restored.notebookDeckKey(0)); assertEquals("writing:12", restored.notebookDeckKey(1))
        assertTrue(restored.notebookOverview.value)
        restored.saveNotebookDeck(1, "PRIVATE PROSE"); assertEquals("writing:12", restored.notebookDeckKey(1))
        restored.select(8); assertEquals("", restored.notebookDeckKey(0)); assertEquals("", restored.notebookDeckKey(1)); assertFalse(restored.notebookOverview.value)
    }
    @Test fun notebookEntryRestoresButDoesNotFollowAnotherOwner() {
        val handle = SavedStateHandle(); val state = WorldNavigationModel(handle); state.select(7)
        state.notebookEntry.value = "writing:12"; state.notebookBook.value = 9
        val restored = WorldNavigationModel(handle); restored.select(7)
        assertEquals("writing:12", restored.notebookEntry.value); assertEquals(9L, restored.notebookBook.value)
        restored.select(8); assertEquals("", restored.notebookEntry.value); assertEquals(0L, restored.notebookBook.value); assertNull(restored.notebookSerialEditor.value)
    }

    @Test fun sourcePositionSurvivesRecreationAndSameDefaultOpen() {
        val handle = SavedStateHandle(); val state = WorldNavigationModel(handle); state.select(7)
        state.openSource(9, 0); state.prepareSourceScroll(9, "version-a", 0); state.saveSourceScroll(12, 850)
        val restored = WorldNavigationModel(handle); restored.openSource(9, 0); restored.prepareSourceScroll(9, "version-a", 0)
        assertEquals(12 to 850, restored.sourceScroll())
        restored.select(8); assertNull(restored.sourceScroll())
    }
    @Test fun explicitSourceJumpOrChangedTextInvalidatesPreviousViewport() {
        val state = WorldNavigationModel(SavedStateHandle()); state.select(7)
        state.openSource(9, 0); state.prepareSourceScroll(9, "version-a", 0); state.saveSourceScroll(12, 850)
        state.openSource(9, 100); state.prepareSourceScroll(9, "version-a", 100); assertNull(state.sourceScroll())
        state.saveSourceScroll(2, 25); state.openSource(9, 100); assertNull(state.sourceScroll())
        state.saveSourceScroll(2, 25); state.prepareSourceScroll(9, "version-b", 100); assertNull(state.sourceScroll())
    }
    @Test fun spaceAnchorsSurviveRecreationIndependentlyAndClearOnOwnerChange() {
        val handle = SavedStateHandle(); val state = WorldNavigationModel(handle)
        state.select(7)
        listOf("shelf", "wishes", "bookmarks", "notebook", "album").forEachIndexed { i, page -> state.savePageScroll(page, i + 3, 100 + i) }
        val restored = WorldNavigationModel(handle)
        listOf("shelf", "wishes", "bookmarks", "notebook", "album").forEachIndexed { i, page -> assertEquals(i + 3 to 100 + i, restored.pageScroll(page)) }
        restored.select(7); assertEquals(6 to 103, restored.pageScroll("notebook"))
        restored.select(8)
        listOf("shelf", "wishes", "bookmarks", "notebook", "album").forEach { assertEquals(0 to 0, restored.pageScroll(it)) }
    }
    @Test fun negativePageAnchorsCannotReachTheList() {
        val state = WorldNavigationModel(SavedStateHandle()); state.savePageScroll("album", -3, -99)
        assertEquals(0 to 0, state.pageScroll("album"))
    }
    @Test fun journeyRoutesKeepDetailAnchorsThroughChildrenAndRecreationButClearForAnotherTa() {
        val handle=SavedStateHandle();val state=WorldNavigationModel(handle);state.select(1)
        state.openJourneyTopic(7);state.journeyIndex.value=3;state.journeyOffset.value=14
        state.openJourneyTopic(7);assertEquals(3,state.journeyIndex.value)
        val restored=WorldNavigationModel(handle);assertEquals(7L,restored.journeyTopicId.value);assertEquals(14,restored.journeyOffset.value)
        assertTrue(restored.backJourney());assertEquals(0L,restored.journeyTopicId.value);assertTrue(restored.journeyOpen.value)
        restored.openRecentJourney();assertTrue(restored.journeyRecent.value);assertTrue(restored.backJourney());assertFalse(restored.backJourney())
        restored.openJourneyTopic(8);restored.journeyLibraryIndex.value=4;restored.select(2)
        assertEquals(0L,restored.journeyTopicId.value);assertEquals(0,restored.journeyIndex.value);assertEquals(0,restored.journeyLibraryIndex.value)
    }
    @Test fun outsideScrollAnchorsSurviveRecreationIndependentlyAndClearForAnotherTa() {
        val handle = SavedStateHandle(); val state = WorldNavigationModel(handle)
        state.select(1); state.saveOutsideScroll(0, 7, 19); state.saveOutsideScroll(1, 4, 25); state.saveOutsideScroll(2, 6, 43)
        val restored = WorldNavigationModel(handle)
        restored.select(1)
        assertEquals(7 to 19, restored.outsideScroll(0)); assertEquals(4 to 25, restored.outsideScroll(1)); assertEquals(6 to 43, restored.outsideScroll(2))
        restored.saveOutsideScroll(2, -1, -1); assertEquals(0 to 0, restored.outsideScroll(2))
        restored.select(2); (0..2).forEach { assertEquals(0 to 0, restored.outsideScroll(it)) }
    }
    @Test fun outsideViewsSettingsAndSourceRestoreLocallyButClearOnAnotherTa() {
        val handle = SavedStateHandle(); val state = WorldNavigationModel(handle)
        state.select(1); state.outsideTab.value = 2; state.outsideOptions.value = true
        state.outsideBrowseSource.value = "NASA"; state.outsideSource.value = "nasa"
        val restored = WorldNavigationModel(handle)
        restored.select(1)
        assertEquals(2, restored.outsideTab.value); assertTrue(restored.outsideOptions.value)
        assertEquals("NASA", restored.outsideBrowseSource.value); assertEquals("nasa", restored.outsideSource.value)
        restored.select(2)
        assertEquals(0, restored.outsideTab.value); assertFalse(restored.outsideOptions.value)
        assertEquals("", restored.outsideBrowseSource.value); assertEquals("un", restored.outsideSource.value)
    }
    @Test fun outsidePreviewSurvivesRecreationButDoesNotFollowAnotherTa() {
        val handle = SavedStateHandle(); val state = WorldNavigationModel(handle)
        state.select(1); state.outsideExpanded.value = 7
        val restored = WorldNavigationModel(handle)
        restored.select(1); assertEquals(7L, restored.outsideExpanded.value)
        restored.select(2); assertEquals(0L, restored.outsideExpanded.value)
    }
    @Test fun largePastedDraftStaysOutOfSavedStateAndChangingTaClearsIt() {
        val handle = SavedStateHandle(); val state = WorldNavigationModel(handle)
        state.select(1); state.openLookup("Place"); state.openPaste(17)
        state.pasteTitle.value = "Title"; state.pasteSource.value = "Source"
        state.pasteBody.value = "x".repeat(500_000); state.pastePreview.value = true
        state.select(1)
        assertEquals(500_000, state.pasteBody.value.length); assertTrue(state.pastePreview.value)
        assertFalse(handle.keys().any { it.startsWith("paste") })
        val afterProcessDeath = WorldNavigationModel(handle)
        assertFalse(afterProcessDeath.pasteOpen.value); assertTrue(afterProcessDeath.pasteBody.value.isEmpty())
        state.select(2)
        assertFalse(state.pasteOpen.value); assertFalse(state.pastePreview.value)
        assertTrue(state.pasteBody.value.isEmpty()); assertTrue(state.pasteTitle.value.isEmpty()); assertTrue(state.pasteSource.value.isEmpty())
    }
    @Test fun lookupDraftSurvivesRecreationAndLoadedTaButChangingTaClearsIt() {
        val handle = SavedStateHandle(); val state = WorldNavigationModel(handle)
        state.select(1); state.openLookup("  北京\n 城市  ")
        assertEquals("北京  城市", state.lookupQuery.value)
        state.lookupQuery.value = "Beijing & maps 🗺️"
        val recreated = WorldNavigationModel(handle)
        assertTrue(recreated.lookupOpen.value)
        assertEquals("Beijing & maps 🗺️", recreated.lookupQuery.value)
        recreated.select(1); assertEquals("Beijing & maps 🗺️", recreated.lookupQuery.value)
        recreated.select(2)
        assertEquals("", recreated.lookupQuery.value); assertEquals("", recreated.lookupSeed.value)
        recreated.openLookup("x".repeat(159) + "🗺")
        assertEquals("x".repeat(159), recreated.lookupQuery.value)
    }
    @Test fun outsideManagementSurvivesRecreationButAnotherTaStartsWithoutASelection() {
        val handle = SavedStateHandle()
        val state = WorldNavigationModel(handle)
        state.select(1); state.outsideManaging.value = true; state.outsideFilter.value = "NASA"
        state.outsideSelected.value = arrayListOf(7, 9)
        val restored = WorldNavigationModel(handle)
        assertTrue(restored.outsideManaging.value); assertEquals("NASA", restored.outsideFilter.value)
        assertEquals(listOf(7L, 9L), restored.outsideSelected.value)
        restored.select(2)
        assertFalse(restored.outsideManaging.value); assertEquals("", restored.outsideFilter.value)
        assertTrue(restored.outsideSelected.value.isEmpty())
    }
    @Test fun destinationDoesNotDependOnWhetherTaFlowHasLoaded() {
        val handle = SavedStateHandle(mapOf("selected" to 7L, "space" to WorldSpace.Outside, "journal" to 31L, "journey" to true, "online" to true, "depth" to false))
        val state = WorldNavigationModel(handle)
        assertEquals(31L, state.journalId.value); assertEquals(WorldSpace.Outside, state.space.value)
        assertFalse(state.depthRequested.value)
        assertTrue(state.journeyOpen.value)
        state.articleId.value = 31L; state.sourceOffset.value = 200
        state.onlineQuery.value = "pageid:260"
        state.onlineFullText.value = true
        state.studyTab.value = 2; state.studyCategory.value = "book"; state.studyOptions.value = true
        state.notebookQuery.value = "颜色"; state.notebookFilter.value = "picture"; state.albumOptions.value = true; state.albumExpanded.value = 21L
        val recreated = WorldNavigationModel(handle)
        assertEquals(7L, recreated.selectedId.value); assertEquals(31L, recreated.articleId.value)
        assertEquals(200, recreated.sourceOffset.value); assertEquals(31L, recreated.journalId.value)
        assertTrue(recreated.journeyOpen.value)
        assertTrue(recreated.onlineOpen.value)
        assertEquals("pageid:260", recreated.onlineQuery.value)
        assertTrue(recreated.onlineFullText.value)
        assertEquals(2, recreated.studyTab.value); assertEquals("book", recreated.studyCategory.value); assertTrue(recreated.studyOptions.value)
        assertEquals("颜色", recreated.notebookQuery.value); assertEquals("picture", recreated.notebookFilter.value)
        assertTrue(recreated.albumOptions.value); assertEquals(21L, recreated.albumExpanded.value)
    }
    @Test fun choosingAnotherTaClearsOnlyDestinationsAndKeepsMotionPreference() {
        val state = WorldNavigationModel(SavedStateHandle())
        state.selectedId.value = 7L; state.journalId.value = 31L; state.articleId.value = 31L
        state.lookupOpen.value = true; state.adding.value = true; state.depthRequested.value = false
        state.journeyOpen.value = true
        state.onlineOpen.value = true
        state.onlineQuery.value = "之前的检索词"
        state.onlineFullText.value = true
        state.studyTab.value = 2; state.studyCategory.value = "book"; state.studyOptions.value = true
        state.notebookQuery.value = "之前的问题"; state.notebookFilter.value = "questions"; state.albumOptions.value = true; state.albumExpanded.value = 21L
        state.select(7L); assertEquals(31L, state.journalId.value)
        assertTrue(state.onlineFullText.value)
        assertTrue(state.studyOptions.value); assertEquals(2, state.studyTab.value)
        assertEquals("之前的问题", state.notebookQuery.value); assertTrue(state.albumOptions.value); assertEquals(21L, state.albumExpanded.value)
        state.select(8L)
        assertEquals(0L, state.journalId.value); assertEquals(0L, state.articleId.value)
        assertFalse(state.lookupOpen.value); assertFalse(state.adding.value); assertFalse(state.depthRequested.value)
        assertFalse(state.journeyOpen.value)
        assertFalse(state.onlineOpen.value)
        assertEquals("", state.onlineQuery.value)
        assertFalse(state.onlineFullText.value)
        assertEquals(0, state.studyTab.value); assertEquals("all", state.studyCategory.value); assertFalse(state.studyOptions.value)
        assertEquals("", state.notebookQuery.value); assertEquals("all", state.notebookFilter.value)
        assertFalse(state.albumOptions.value); assertEquals(0L, state.albumExpanded.value)
    }
}
