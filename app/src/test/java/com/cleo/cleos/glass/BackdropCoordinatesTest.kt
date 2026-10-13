package com.cleo.cleos.glass

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class BackdropCoordinatesTest {
    private fun packed(value: Offset): Long = (value.x.toRawBits().toLong() shl 32) or (value.y.toRawBits().toLong() and 0xffffffffL)
    private fun coordinates(parent: LayoutCoordinates? = null, attached: Boolean = true, screen: Offset = Offset.Zero,
        local: Offset = Offset.Zero, failure: RuntimeException? = null, forbidLocal: Boolean = false): LayoutCoordinates =
        Proxy.newProxyInstance(LayoutCoordinates::class.java.classLoader, arrayOf(LayoutCoordinates::class.java)) { proxy, method, args ->
            when {
                method.name == "isAttached" -> attached
                method.name == "getParentLayoutCoordinates" || method.name == "getParentCoordinates" -> parent
                method.name.startsWith("localPositionOf") -> {
                    check(!forbidLocal) { "Cross-root localPositionOf must never be used" }
                    failure?.let { throw it }; packed(local)
                }
                method.name.startsWith("localToScreen") -> { failure?.let { throw it }; packed(screen) }
                method.name == "equals" -> proxy === args?.get(0)
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "toString" -> "coordinates"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as LayoutCoordinates

    @Test fun separatePopupRootUsesScreenCoordinatesWithoutHierarchyException() {
        val page = coordinates(screen = Offset(25f, 50f), forbidLocal = true)
        val popup = coordinates(screen = Offset(100f, 150f), forbidLocal = true)
        assertEquals(Offset(75f, 100f), backdropOffset(page, popup))
    }

    @Test fun sharedRootUsesLocalTransformRatherThanScreenTranslation() {
        val root = coordinates()
        val source = coordinates(root, screen = Offset(25f, 50f), local = Offset(7f, 9f))
        val target = coordinates(root, screen = Offset(100f, 150f))
        assertEquals(Offset(7f, 9f), backdropOffset(source, target))
    }

    @Test fun detachedAndNonFiniteCoordinatesSkipTheBackdrop() {
        assertNull(backdropOffset(coordinates(attached = false), coordinates()))
        assertNull(backdropOffset(coordinates(), coordinates(attached = false)))
        assertNull(backdropOffset(coordinates(), coordinates(screen = Offset(Float.NaN, 1f))))
    }

    @Test fun coordinatesLostDuringTransitionDoNotCrashTheDraw() {
        val root = coordinates()
        assertNull(backdropOffset(coordinates(root, failure = IllegalArgumentException("layouts are not part of the same hierarchy")), coordinates(root)))
        assertNull(backdropOffset(coordinates(failure = IllegalStateException("detached")), coordinates()))
    }
}
