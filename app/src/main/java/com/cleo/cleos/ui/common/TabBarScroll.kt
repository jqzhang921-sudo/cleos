package com.cleo.cleos.ui.common

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal class TabBarScrollBehavior(thresholdPx: Float) {
    private val policy = TabBarScrollPolicy(thresholdPx)
    var hidden by mutableStateOf(false)
        private set

    fun show() { policy.show(); hidden = false }
    fun scroll(y: Float, atTop: Boolean) {
        policy.scroll(y, atTop)
        hidden = policy.hidden
    }
}

/** Only the currently shown, non-chat tab receives a behavior. Warmed pages cannot change it. */
internal val LocalTabBarScrollBehavior = compositionLocalOf<TabBarScrollBehavior?> { null }

/** Attach outside the primary vertical scroll container; never consumes its scroll. */
internal fun Modifier.tabBarScroll(state: ScrollableState): Modifier = composed {
    val behavior = LocalTabBarScrollBehavior.current
    if (behavior == null) return@composed this
    LaunchedEffect(behavior, state) {
        // A new destination/view starts with navigation available, even at a restored offset.
        behavior.show()
        snapshotFlow { !state.canScrollBackward }.collect { atTop ->
            if (atTop) behavior.show()
        }
    }
    val connection = remember(behavior, state) {
        object : NestedScrollConnection {
            private var userFling = false
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput || userFling) {
                    behavior.scroll(consumed.y, atTop = !state.canScrollBackward)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                userFling = abs(available.y) > abs(available.x)
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                userFling = false
                return Velocity.Zero
            }
        }
    }
    nestedScroll(connection)
}

/** A primary page list, sharing the same state for scrolling and the at-top check. */
@Composable
internal fun TabScrollColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier.tabBarScroll(state), state = state, contentPadding = contentPadding,
        verticalArrangement = verticalArrangement, horizontalAlignment = horizontalAlignment,
        content = content,
    )
}
