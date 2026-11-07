package com.cleo.cleos.ui.world

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.cleo.cleos.data.WorldReading
import com.cleo.cleos.data.WorldTopicSearch

/** Keep the world's destination through activity recreation, independently of loading the TA flows. */
internal class WorldNavigationModel(handle: SavedStateHandle) : ViewModel() {
    private val pagePositions = listOf("shelf", "wishes", "bookmarks", "notebook", "album").associateWith {
        WorldSavedValue(handle, "page:$it:index", 0) to WorldSavedValue(handle, "page:$it:offset", 0)
    }
    fun pageScroll(page: String): Pair<Int, Int> = pagePositions.getValue(page).let { it.first.value to it.second.value }
    fun savePageScroll(page: String, index: Int, offset: Int) {
        pagePositions.getValue(page).let { (row, pixels) -> row.value = index.coerceAtLeast(0); pixels.value = offset.coerceAtLeast(0) }
    }
    val selectedId = WorldSavedValue(handle, "selected", 0L)
    val space = WorldSavedValue<WorldSpace?>(handle, "space", null)
    val adding = WorldSavedValue(handle, "adding", false)
    val onlineOpen = WorldSavedValue(handle, "online", false)
    val onlineQuery = WorldSavedValue(handle, "onlineQuery", "")
    val onlineFullText = WorldSavedValue(handle, "onlineFullText", false)
    val studyTab = WorldSavedValue(handle, "studyTab", 0)
    val studyCategory = WorldSavedValue(handle, "studyCategory", "all")
    val studyOptions = WorldSavedValue(handle, "studyOptions", false)
    val writingOptions = WorldSavedValue(handle, "writingOptions", false)
    val notebookEntry = WorldSavedValue(handle, "notebookEntry", "")
    val notebookBook = WorldSavedValue(handle, "notebookBook", 0L)
    val notebookOverview = WorldSavedValue(handle, "notebookOverview", false)
    private val notebookDeckPositions = WorldSavedValue(handle, "notebookDeckPositions", arrayListOf<String>())
    fun notebookDeckKey(book: Long): String = notebookDeckPositions.value.firstOrNull { it.startsWith("$book|") }?.substringAfter('|').orEmpty()
    fun saveNotebookDeck(book: Long, key: String) {
        if (!key.matches(Regex("(?:serial|writing|note|look):[1-9][0-9]*"))) return
        notebookDeckPositions.value = ArrayList((notebookDeckPositions.value.filterNot { it.startsWith("$book|") } + "$book|$key").takeLast(40))
    }
    val notebookSerialEditor = mutableStateOf<NotebookSerialDraft?>(null)
    val notebookSerialPick = mutableStateOf<com.cleo.cleos.data.db.WorldWritingEntity?>(null)
    val notebookQuery = WorldSavedValue(handle, "notebookQuery", "")
    val notebookFilter = WorldSavedValue(handle, "notebookFilter", "all")
    val albumOptions = WorldSavedValue(handle, "albumOptions", false)
    val albumExpanded = WorldSavedValue(handle, "albumExpanded", 0L)
    val outsideManaging = WorldSavedValue(handle, "outsideManaging", false)
    val outsideFilter = WorldSavedValue(handle, "outsideFilter", "")
    val outsideSelected = WorldSavedValue(handle, "outsideSelected", arrayListOf<Long>())
    val outsideExpanded = WorldSavedValue(handle, "outsideExpanded", 0L)
    val outsideTab = WorldSavedValue(handle, "outsideTab", 0)
    val outsideOptions = WorldSavedValue(handle, "outsideOptions", false)
    val outsideBrowseSource = WorldSavedValue(handle, "outsideBrowseSource", "")
    val outsideSource = WorldSavedValue(handle, "outsideSource", "un")
    private val outsideFeedIndex = WorldSavedValue(handle, "outsideFeedIndex", 0)
    private val outsideFeedOffset = WorldSavedValue(handle, "outsideFeedOffset", 0)
    private val outsideTrailIndex = WorldSavedValue(handle, "outsideTrailIndex", 0)
    private val outsideTrailOffset = WorldSavedValue(handle, "outsideTrailOffset", 0)
    private val outsideNoteIndex = WorldSavedValue(handle, "outsideNoteIndex", 0)
    private val outsideNoteOffset = WorldSavedValue(handle, "outsideNoteOffset", 0)
    fun outsideScroll(tab: Int): Pair<Int, Int> = when (tab) {
        1 -> outsideTrailIndex.value to outsideTrailOffset.value
        2 -> outsideNoteIndex.value to outsideNoteOffset.value
        else -> outsideFeedIndex.value to outsideFeedOffset.value
    }
    fun saveOutsideScroll(tab: Int, index: Int, offset: Int) {
        val (row, pixels) = when (tab) {
            1 -> outsideTrailIndex to outsideTrailOffset
            2 -> outsideNoteIndex to outsideNoteOffset
            else -> outsideFeedIndex to outsideFeedOffset
        }
        row.value = index.coerceAtLeast(0); pixels.value = offset.coerceAtLeast(0)
    }
    val articleId = WorldSavedValue(handle, "article", 0L)
    val journalId = WorldSavedValue(handle, "journal", 0L)
    val journeyOpen = WorldSavedValue(handle, "journey", false)
    val journeyTopicId = WorldSavedValue(handle, "journeyTopic", 0L)
    val journeyRecent = WorldSavedValue(handle, "journeyRecent", false)
    val journeyIndex = WorldSavedValue(handle, "journeyIndex", 0)
    val journeyOffset = WorldSavedValue(handle, "journeyOffset", 0)
    val journeyLibraryIndex = WorldSavedValue(handle, "journeyLibraryIndex", 0)
    val journeyLibraryOffset = WorldSavedValue(handle, "journeyLibraryOffset", 0)
    val journeyEditor = mutableStateOf<WorldJourneyDraft?>(null)
    fun openJourneyTopic(id: Long) {
        if (journeyTopicId.value != id || journeyRecent.value) { journeyIndex.value = 0; journeyOffset.value = 0 }
        journeyTopicId.value = id; journeyRecent.value = false; journeyOpen.value = true
    }
    fun openRecentJourney() {
        if (!journeyRecent.value) { journeyIndex.value = 0; journeyOffset.value = 0 }
        journeyTopicId.value = 0; journeyRecent.value = true
    }
    fun backJourney(): Boolean {
        journeyEditor.value?.let { if (!it.saving) journeyEditor.value = null; return true }
        if (journeyTopicId.value != 0L || journeyRecent.value) {
            journeyTopicId.value = 0; journeyRecent.value = false; journeyIndex.value = 0; journeyOffset.value = 0; return true
        }
        return false
    }
    val sourceOffset = WorldSavedValue(handle, "offset", 0)
    private val sourceViewId = WorldSavedValue(handle, "sourceViewId", 0L)
    private val sourceViewStart = WorldSavedValue(handle, "sourceViewStart", 0)
    private val sourceViewVersion = WorldSavedValue(handle, "sourceViewVersion", "")
    private val sourceViewIndex = WorldSavedValue(handle, "sourceViewIndex", 0)
    private val sourceViewPixels = WorldSavedValue(handle, "sourceViewPixels", 0)
    private val sourceViewReady = WorldSavedValue(handle, "sourceViewReady", false)
    fun openSource(id: Long, offset: Int) {
        if (id != sourceViewId.value || offset != sourceViewStart.value || offset > 0) sourceViewReady.value = false
        articleId.value = id; sourceOffset.value = offset
    }
    fun prepareSourceScroll(id: Long, version: String, start: Int) {
        if (sourceViewId.value != id || sourceViewVersion.value != version || sourceViewStart.value != start) {
            sourceViewId.value = id; sourceViewVersion.value = version; sourceViewStart.value = start
            sourceViewReady.value = false; sourceViewIndex.value = 0; sourceViewPixels.value = 0
        }
    }
    fun sourceScroll(): Pair<Int, Int>? = if (sourceViewReady.value) sourceViewIndex.value to sourceViewPixels.value else null
    fun saveSourceScroll(index: Int, pixels: Int) {
        sourceViewIndex.value = index.coerceAtLeast(0); sourceViewPixels.value = pixels.coerceAtLeast(0); sourceViewReady.value = true
    }
    val lookupOpen = WorldSavedValue(handle, "lookup", false)
    val lookupSeed = WorldSavedValue(handle, "seed", "")
    val lookupQuery = WorldSavedValue(handle, "lookupQuery", "")
    // Large drafts stay in memory across font/activity changes, never in Binder bundles.
    val pasteOpen = mutableStateOf(false)
    val pasteTitle = mutableStateOf("")
    val pasteSource = mutableStateOf("")
    val pasteBody = mutableStateOf("")
    val pastePreview = mutableStateOf(false)
    val pasteSaving = mutableStateOf(false)
    val pasteError = mutableStateOf<String?>(null)
    var pasteRevision: Long = 0L
    fun openPaste(revision: Long) {
        if (pasteRevision != revision) pastePreview.value = false
        pasteRevision = revision; pasteOpen.value = true
    }
    fun clearPaste() {
        pasteOpen.value = false; pasteTitle.value = ""; pasteSource.value = ""; pasteBody.value = ""; pastePreview.value = false
        pasteError.value = null; pasteSaving.value = false
    }
    val depthRequested = WorldSavedValue(handle, "depth", true)
    fun openLookup(seed: String) {
        val end = minOf(seed.length, WorldTopicSearch.MAX_QUERY)
        lookupSeed.value = seed
        lookupQuery.value = seed.take(if (WorldReading.boundary(seed, end)) end else end - 1)
            .replace(Regex("[\\r\\n\\t]+"), " ").trim()
        lookupOpen.value = true
    }
    fun select(id: Long) {
        if (selectedId.value != id) {
            sourceViewId.value = 0; sourceViewVersion.value = ""; sourceViewStart.value = 0
            sourceViewReady.value = false; sourceViewIndex.value = 0; sourceViewPixels.value = 0
            pagePositions.values.forEach { (row, pixels) -> row.value = 0; pixels.value = 0 }
            articleId.value = 0L; journalId.value = 0L; journeyOpen.value = false; lookupOpen.value = false; adding.value = false; onlineOpen.value = false
            selectedId.value = id
            journeyTopicId.value = 0; journeyRecent.value = false; journeyEditor.value = null
            journeyIndex.value = 0; journeyOffset.value = 0; journeyLibraryIndex.value = 0; journeyLibraryOffset.value = 0
            onlineQuery.value = ""
            onlineFullText.value = false
            studyTab.value = 0; studyCategory.value = "all"; studyOptions.value = false
            writingOptions.value = false
            notebookEntry.value = ""
            notebookBook.value = 0L; notebookSerialEditor.value = null; notebookSerialPick.value = null
            notebookOverview.value = false; notebookDeckPositions.value = arrayListOf()
            notebookQuery.value = ""; notebookFilter.value = "all"; albumOptions.value = false; albumExpanded.value = 0L
            outsideManaging.value = false; outsideFilter.value = ""; outsideSelected.value = arrayListOf(); outsideExpanded.value = 0L
            outsideTab.value = 0; outsideOptions.value = false; outsideBrowseSource.value = ""; outsideSource.value = "un"
            (0..2).forEach { saveOutsideScroll(it, 0, 0) }
            lookupQuery.value = ""; lookupSeed.value = ""
            clearPaste()
        }
    }
}

internal class WorldSavedValue<T>(private val handle: SavedStateHandle, private val key: String, initial: T) : MutableState<T> {
    private val state = mutableStateOf(handle.get<T>(key) ?: initial)
    override var value: T
        get() = state.value
        set(value) { state.value = value; handle[key] = value }
    override fun component1(): T = value
    override fun component2(): (T) -> Unit = { value = it }
}
