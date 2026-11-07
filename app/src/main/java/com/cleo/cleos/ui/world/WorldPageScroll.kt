package com.cleo.cleos.ui.world

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collect

/** Restore only after Room's first emission; temporary empty lists must not erase the anchor. */
@Composable
internal fun worldPageScroll(navigation: WorldNavigationModel, page: String, owner: Long?, ready: Boolean): LazyListState {
    val state = rememberLazyListState()
    LaunchedEffect(owner, page, ready) {
        if (owner == null || !ready || navigation.selectedId.value != owner) return@LaunchedEffect
        val (index, offset) = navigation.pageScroll(page)
        state.scrollToItem(index, offset)
        snapshotFlow { Triple(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, state.layoutInfo.totalItemsCount) }
            .collect { (row, pixels, count) ->
                if (count > 0 && navigation.selectedId.value == owner) navigation.savePageScroll(page, row, pixels)
            }
    }
    return state
}
