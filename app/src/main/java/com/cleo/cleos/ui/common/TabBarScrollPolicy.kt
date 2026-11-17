package com.cleo.cleos.ui.common

/** Accumulates actual vertical travel, not finger jitter or unconsumed overscroll. */
internal class TabBarScrollPolicy(private val threshold: Float) {
    init { require(threshold > 0f && threshold.isFinite()) }

    var hidden = false
        private set
    private var travel = 0f

    fun show() {
        hidden = false
        travel = 0f
    }

    fun scroll(consumedY: Float, atTop: Boolean) {
        if (atTop) {
            show()
            return
        }
        if (!consumedY.isFinite() || consumedY == 0f) return
        if (travel * consumedY < 0f) travel = 0f
        travel = (travel + consumedY).coerceIn(-threshold, threshold)
        if (travel <= -threshold) hidden = true
        if (travel >= threshold) hidden = false
    }
}
