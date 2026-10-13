package com.cleo.cleos.glass

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates

/** A dialog/popup has a separate root; its backdrop still belongs to the main window. */
internal fun backdropOffset(source: LayoutCoordinates, target: LayoutCoordinates): Offset? {
    if (!source.isAttached || !target.isAttached) return null
    val offset = try {
        if (source.findRootCoordinates() === target.findRootCoordinates()) {
            source.localPositionOf(target, Offset.Zero)
        } else {
            target.localToScreen(Offset.Zero) - source.localToScreen(Offset.Zero)
        }
    } catch (_: IllegalArgumentException) {
        return null
    } catch (_: IllegalStateException) {
        return null
    }
    return offset.takeIf { it.x.isFinite() && it.y.isFinite() }
}
