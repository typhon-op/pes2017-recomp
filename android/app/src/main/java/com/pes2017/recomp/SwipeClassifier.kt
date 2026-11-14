package com.pes2017.recomp

import kotlin.math.abs
import kotlin.math.max

/** A direction a swipe can be read as. */
internal enum class Swipe { NONE, UP, DOWN, LEFT, RIGHT }

/**
 * Reads a finger's travel as one of four directions.
 *
 * This is the gesture for controls that have no bit of their own: a flick up for
 * a through ball, and nothing at all while the finger is travelling. Putting it
 * on an action button instead would mean the button's own bit had already
 * reached the guest by the time the direction was known, which is the duplicate
 * command this exists to avoid.
 *
 * The direction is decided when the finger lifts, from where it ended up rather
 * than where it wandered: jitter cannot change the answer mid-gesture, and the
 * same flick reads the same at any sampling rate. A thumb that went evenly up
 * and right gets neither, because guessing between them is worse than doing
 * nothing.
 *
 * Free of Android types, so [SwipeClassifierTest] checks it without a device.
 */
internal class SwipeClassifier(
    /** How far the finger has to end up from where it started, in pixels. */
    private val minTravelPx: Float,
    /** A drag longer than this is a slide across the pad, not a flick. */
    private val maxDurationMs: Long = 300L,
    /** How far the winning axis has to beat the other one. */
    private val dominance: Float = 1.30f,
) {
    private class Gesture(val startX: Float, val startY: Float, val startMs: Long) {
        var lastX = startX
        var lastY = startY
    }

    private val gestures = HashMap<Int, Gesture>()

    fun begin(pointerId: Int, x: Float, y: Float, nowMs: Long) {
        gestures[pointerId] = Gesture(x, y, nowMs)
    }

    fun move(pointerId: Int, x: Float, y: Float) {
        val gesture = gestures[pointerId] ?: return
        gesture.lastX = x
        gesture.lastY = y
    }

    /** The direction this pointer's gesture made, decided once, as it lifts. */
    fun end(pointerId: Int, nowMs: Long): Swipe {
        val gesture = gestures.remove(pointerId) ?: return Swipe.NONE
        if (nowMs - gesture.startMs > maxDurationMs) return Swipe.NONE

        val dx = gesture.lastX - gesture.startX
        val dy = gesture.lastY - gesture.startY
        val ax = abs(dx)
        val ay = abs(dy)
        if (max(ax, ay) < minTravelPx) return Swipe.NONE

        return when {
            ax >= ay * dominance -> if (dx > 0f) Swipe.RIGHT else Swipe.LEFT
            ay >= ax * dominance -> if (dy > 0f) Swipe.DOWN else Swipe.UP
            else -> Swipe.NONE
        }
    }

    /** The gesture was taken away (another finger claimed it, or the pad hid). */
    fun cancel(pointerId: Int) {
        gestures.remove(pointerId)
    }

    fun busy(pointerId: Int): Boolean = gestures.containsKey(pointerId)

    fun clear() {
        gestures.clear()
    }
}
