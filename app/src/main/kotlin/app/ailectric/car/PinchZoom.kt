package app.ailectric.car

import kotlin.math.ln

/**
 * Turns the host's scale events into map zoom steps. A pinch sends many events in quick
 * succession, each taken at face value adds up to several zoom levels within a moment. A lone
 * event is a double tap and keeps its full step.
 */
class PinchZoom(private val now: () -> Long = System::currentTimeMillis) {
    private var lastScaleAt: Long? = null

    /** A pinch also reports finger movement as scroll, which would shift the map. */
    val isPinching: Boolean get() = lastScaleAt?.let { now() - it < GESTURE_GAP_MS } ?: false

    fun zoomDelta(scaleFactor: Float): Double {
        val pinching = isPinching
        lastScaleAt = now()
        // Levels are powers of two. A host may report a double tap out as a negative factor.
        val levels = if (scaleFactor > 0) ln(scaleFactor.toDouble()) / ln(2.0) else -1.0
        return if (pinching) (levels * PINCH_DAMPING).coerceIn(-MAX_PINCH_STEP, MAX_PINCH_STEP) else levels
    }

    private companion object {
        const val GESTURE_GAP_MS = 300L
        const val PINCH_DAMPING = 0.5
        const val MAX_PINCH_STEP = 0.1
    }
}
