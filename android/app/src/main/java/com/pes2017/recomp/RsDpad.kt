package com.pes2017.recomp

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The RS d-pad: eight directions from one socket, written as the right stick's
 * axes.
 *
 * The existing d-pad ([ControlKind.DPAD]) is a four-way button control: an arm
 * is one of the title's four d-pad bits, and the menus read it. This is the
 * other half of the same idea, for the stick the title reads as an axis pair:
 * pressing a direction states the right stick, and the finger can slide from
 * one direction to the next without lifting, because each sample replaces the
 * last.
 *
 * The table is the one the branch was specified with, unchanged:
 *
 * | direction  | RS X | RS Y |
 * |------------|------|------|
 * | up         |  0   |  -1  |
 * | down       |  0   |  +1  |
 * | left       | -1   |   0  |
 * | right      | +1   |   0  |
 * | up-left    | -1   |  -1  |
 * | up-right   | +1   |  -1  |
 * | down-left  | -1   |  +1  |
 * | down-right | +1   |  +1  |
 * | neutral    |  0   |   0  |
 *
 * Note on the sign convention, because the two halves of this codebase do not
 * agree: [StickVector] says "the guest's Y points up", and a thumb pushed up the
 * screen leaves the left stick at +1 (that is the console's own axis). This
 * table is written with the screen's Y (up is negative), which is how it was
 * requested. The values are therefore sent exactly as specified; if the title
 * plays the down-flick for an up press on a device, the one-line change is the
 * `y` of these constants (see docs/touch-controls.md).
 */
object RsDpad {

    /**
     * One of the eight directions, with the axes it states. [NONE] is the
     * centre of the socket and every release: a neutral right stick, never a
     * held one.
     */
    enum class Direction(val x: Float, val y: Float) {
        NONE(0f, 0f),
        UP(0f, -1f),
        DOWN(0f, 1f),
        LEFT(-1f, 0f),
        RIGHT(1f, 0f),
        UP_LEFT(-1f, -1f),
        UP_RIGHT(1f, -1f),
        DOWN_LEFT(-1f, 1f),
        DOWN_RIGHT(1f, 1f),
    }

    /**
     * How far from the socket's centre a thumb has to be for a direction to
     * count, as a share of the radius. The same resting zone the four-way
     * d-pad uses, so the middle of the socket is "nothing" on both.
     */
    const val DEADZONE = 0.30f

    /**
     * Where an axis stops dominating and the direction becomes a diagonal:
     * tan(22.5 degrees), so the eight sectors are equal 45-degree wedges rather
     * than four axes with narrow corners between them. A thumb held between up
     * and right is up-right for a third of the wedge either side of the exact
     * diagonal, which is what makes a diagonal reachable without aiming.
     */
    const val DIAGONAL_RATIO = 0.41421356f

    /**
     * The direction a thumb at ([dx], [dy]) from the socket's centre is asking
     * for. The offset is in pixels, [radius] is the drawn socket's radius.
     *
     * Nothing is clamped: a thumb dragged past the rim is still read, and the
     * eight sectors are an angle, so the answer does not change at the edge.
     */
    fun of(dx: Float, dy: Float, radius: Float): Direction {
        if (radius <= 0f) return Direction.NONE
        val ux = dx / radius
        val uy = dy / radius
        if (hypot(ux, uy) < DEADZONE) return Direction.NONE
        val ax = abs(ux)
        val ay = abs(uy)
        val major = max(ax, ay)
        if (major <= 0f) return Direction.NONE
        val right = ux > 0f
        val down = uy > 0f
        if (min(ax, ay) >= major * DIAGONAL_RATIO) {
            return when {
                right && down -> Direction.DOWN_RIGHT
                right -> Direction.UP_RIGHT
                down -> Direction.DOWN_LEFT
                else -> Direction.UP_LEFT
            }
        }
        return if (ax >= ay) {
            if (right) Direction.RIGHT else Direction.LEFT
        } else {
            if (down) Direction.DOWN else Direction.UP
        }
    }

    /** The four axes and the four diagonals, in the order the pad draws them. */
    val DIRECTIONS: List<Direction> = listOf(
        Direction.UP, Direction.UP_RIGHT, Direction.RIGHT, Direction.DOWN_RIGHT,
        Direction.DOWN, Direction.DOWN_LEFT, Direction.LEFT, Direction.UP_LEFT,
    )
}
