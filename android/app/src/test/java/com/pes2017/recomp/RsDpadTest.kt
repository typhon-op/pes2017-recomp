package com.pes2017.recomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The RS d-pad's eight directions and the values they send.
 *
 * The overlay itself needs a phone, but this mapping is pure and it is the whole
 * translation from a thumb's place in the socket to `nativeTouchState`'s right
 * stick. A wrong sign or a bent diagonal here is a right-stick command the
 * title never receives, and no amount of play-testing names which entry is
 * wrong -- so the requested table is pinned value by value.
 */
class RsDpadTest {

    private val radius = 42f     // an 84 dp socket

    private fun at(ux: Float, uy: Float) = RsDpad.of(ux * radius, uy * radius, radius)

    @Test
    fun theEightDirectionsSendTheSpecifiedAxes() {
        assertEquals(RsDpad.Direction.UP, at(0f, -1f))
        assertEquals(0f, RsDpad.Direction.UP.x, 0f)
        assertEquals(-1f, RsDpad.Direction.UP.y, 0f)

        assertEquals(RsDpad.Direction.DOWN, at(0f, 1f))
        assertEquals(0f, RsDpad.Direction.DOWN.x, 0f)
        assertEquals(1f, RsDpad.Direction.DOWN.y, 0f)

        assertEquals(RsDpad.Direction.LEFT, at(-1f, 0f))
        assertEquals(-1f, RsDpad.Direction.LEFT.x, 0f)
        assertEquals(0f, RsDpad.Direction.LEFT.y, 0f)

        assertEquals(RsDpad.Direction.RIGHT, at(1f, 0f))
        assertEquals(1f, RsDpad.Direction.RIGHT.x, 0f)
        assertEquals(0f, RsDpad.Direction.RIGHT.y, 0f)

        assertEquals(RsDpad.Direction.UP_LEFT, at(-1f, -1f))
        assertEquals(-1f, RsDpad.Direction.UP_LEFT.x, 0f)
        assertEquals(-1f, RsDpad.Direction.UP_LEFT.y, 0f)

        assertEquals(RsDpad.Direction.UP_RIGHT, at(1f, -1f))
        assertEquals(1f, RsDpad.Direction.UP_RIGHT.x, 0f)
        assertEquals(-1f, RsDpad.Direction.UP_RIGHT.y, 0f)

        assertEquals(RsDpad.Direction.DOWN_LEFT, at(-1f, 1f))
        assertEquals(-1f, RsDpad.Direction.DOWN_LEFT.x, 0f)
        assertEquals(1f, RsDpad.Direction.DOWN_LEFT.y, 0f)

        assertEquals(RsDpad.Direction.DOWN_RIGHT, at(1f, 1f))
        assertEquals(1f, RsDpad.Direction.DOWN_RIGHT.x, 0f)
        assertEquals(1f, RsDpad.Direction.DOWN_RIGHT.y, 0f)
    }

    @Test
    fun theCentreIsNeutral() {
        // Resting, nudged, and the exact centre: all (0, 0), so a thumb that
        // lands on the d-pad does not move the right stick until it pushes.
        for (share in listOf(0f, 0.1f, 0.29f)) {
            assertEquals(share.toString(), RsDpad.Direction.NONE, at(share, 0f))
            assertEquals(share.toString(), RsDpad.Direction.NONE, at(0f, -share))
        }
        // The resting zone is a circle, not a square: a diagonal has to be as
        // far out as an axis before it counts (0.2, 0.2 is 0.283 R).
        assertEquals(RsDpad.Direction.NONE, at(0.2f, 0.2f))
        assertEquals(RsDpad.Direction.NONE, at(-0.2f, 0.2f))
        assertEquals(0f, RsDpad.Direction.NONE.x, 0f)
        assertEquals(0f, RsDpad.Direction.NONE.y, 0f)
    }

    @Test
    fun theEightWedgesAreEqual() {
        // Just inside each wedge, slightly off the axis: still that direction.
        assertEquals(RsDpad.Direction.RIGHT, at(1f, -0.30f))
        assertEquals(RsDpad.Direction.UP_RIGHT, at(1f, -0.60f))
        assertEquals(RsDpad.Direction.UP, at(0.30f, -1f))
        assertEquals(RsDpad.Direction.UP_LEFT, at(-0.60f, -1f))
        assertEquals(RsDpad.Direction.LEFT, at(-1f, 0.30f))
        assertEquals(RsDpad.Direction.DOWN_LEFT, at(-1f, 0.60f))
        assertEquals(RsDpad.Direction.DOWN, at(-0.30f, 1f))
        assertEquals(RsDpad.Direction.DOWN_RIGHT, at(0.60f, 1f))
    }

    @Test
    fun aThumbPastTheRimIsStillRead() {
        // A finger dragged off the control keeps its direction: the answer is an
        // angle, and clamping it at the rim would leave the stick neutral under
        // a thumb that is still pushing.
        assertEquals(RsDpad.Direction.UP, at(0f, -3f))
        assertEquals(RsDpad.Direction.DOWN_RIGHT, at(3f, 3f))
    }

    @Test
    fun everyDirectionIsInTheDrawOrderOnce() {
        assertEquals(8, RsDpad.DIRECTIONS.size)
        assertEquals(RsDpad.DIRECTIONS.size, RsDpad.DIRECTIONS.toSet().size)
        assertTrue(RsDpad.DIRECTIONS.none { it == RsDpad.Direction.NONE })
        // Every drawn direction states at least one axis, and no two of them
        // state the same pair.
        val pairs = RsDpad.DIRECTIONS.map { it.x to it.y }
        assertEquals(pairs.size, pairs.toSet().size)
        assertTrue(pairs.all { it.first != 0f || it.second != 0f })
    }
}
