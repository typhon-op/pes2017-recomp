package com.pes2017.recomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The skill wheel's gesture, walked without a phone.
 *
 * The bug this replaces was a stored `open` flag flipped by a posted runnable:
 * anything that removed that callback -- a redraw, a second finger, a cancelled
 * message -- could leave the wheel gone while the thumb was still rolling
 * through skills. Visibility is derived now, so the tests are mostly about the
 * wheel staying open while the thumb keeps moving and closing only when the
 * owner lets go.
 */
class WheelGestureTest {

    private val radius = 90f

    /** A point [sector]'s direction, far enough out to leave the deadzone. */
    private fun at(sector: Int, distance: Float = radius): Pair<Float, Float> {
        val degrees = Math.toRadians(sector * 45.0)
        return (kotlin.math.cos(degrees) * distance).toFloat() to
            (-kotlin.math.sin(degrees) * distance).toFloat()
    }

    @Test
    fun theWheelOpensOnTheHoldAndNotBefore() {
        val wheel = WheelGesture()
        assertEquals(-1, wheel.pointer)
        assertFalse(wheel.isOpen(0L))
        assertTrue(wheel.press(7, 400f, 300f, 1_000L))
        assertFalse("a tap must not open the ring", wheel.isOpen(1_000L))
        assertFalse(wheel.isOpen(1_000L + TrickWheel.HOLD_MS - 1))
        assertTrue(wheel.isOpen(1_000L + TrickWheel.HOLD_MS))
        assertEquals(7, wheel.pointer)
        assertEquals(400f, wheel.originX, 0f)
        assertEquals(300f, wheel.originY, 0f)
    }

    @Test
    fun continuousRollingKeepsItOpenAndFollowsEverySector() {
        val wheel = WheelGesture()
        wheel.press(1, 500f, 500f, 0L)
        // Two full laps at 60 samples a second: the old timer-driven visibility
        // dropped out during exactly this, so the assertion is made at every
        // single sample, not only at the end.
        for (lap in 0 until 2) {
            for (step in 0 until 8 * 6) {
                val now = TrickWheel.HOLD_MS + step * 16L + lap * 8 * 6 * 16L
                val sector = (step / 6) % TrickWheel.SECTORS
                val (dx, dy) = at(sector)
                wheel.aim(dx, dy, radius)
                assertEquals("sector follows the thumb", sector, wheel.sector)
                assertTrue("open at ${now}ms", wheel.isOpen(now))
                assertEquals("still the same owner", 1, wheel.pointer)
            }
        }
    }

    @Test
    fun aDirectionChangeIsImmediateAndNeverClosesTheRing() {
        val wheel = WheelGesture()
        wheel.press(3, 200f, 200f, 0L)
        val now = TrickWheel.HOLD_MS + 500L
        val (rightX, rightY) = at(0)
        wheel.aim(rightX, rightY, radius)
        assertEquals(0, wheel.sector)
        assertTrue(wheel.isOpen(now))
        // Straight across to the opposite sector, then to each diagonal in turn.
        val (leftX, leftY) = at(4)
        wheel.aim(leftX, leftY, radius)
        assertEquals(4, wheel.sector)
        assertTrue(wheel.isOpen(now))
        for (sector in intArrayOf(7, 1, 5, 3, 6, 2)) {
            val (dx, dy) = at(sector)
            wheel.aim(dx, dy, radius)
            assertEquals(sector, wheel.sector)
            assertTrue(wheel.isOpen(now))
        }
    }

    @Test
    fun theDeadzoneChoosesNothingButDoesNotClose() {
        val wheel = WheelGesture()
        wheel.press(1, 100f, 100f, 0L)
        val now = TrickWheel.HOLD_MS + 10L
        wheel.aim(0f, 0f, radius)
        assertEquals(-1, wheel.sector)
        assertTrue("the ring is still up, showing no choice", wheel.isOpen(now))
        // Just inside the deadzone edge: still no sector.
        wheel.aim(radius * TrickWheel.DEADZONE * 0.9f, 0f, radius)
        assertEquals(-1, wheel.sector)
        wheel.aim(radius * TrickWheel.DEADZONE * 1.1f, 0f, radius)
        assertEquals(0, wheel.sector)
    }

    @Test
    fun aSecondThumbCannotStealAnOpenWheel() {
        val wheel = WheelGesture()
        wheel.press(1, 300f, 300f, 0L)
        val now = TrickWheel.HOLD_MS + 200L
        assertFalse("the owner keeps it", wheel.press(2, 700f, 700f, now))
        assertEquals(1, wheel.pointer)
        assertEquals(300f, wheel.originX, 0f)
        assertEquals(300f, wheel.originY, 0f)
        // The stranger's lift is not the wheel's lift.
        assertNull(wheel.release(2, now + 10L))
        assertEquals(1, wheel.pointer)
        assertTrue(wheel.isOpen(now + 10L))
        // The owner is still the only thumb it listens to: the view only aims
        // with samples whose id == wheel.pointer, so the stranger's movement
        // never reaches aim(). The owner's own does.
        wheel.aim(-radius, 0f, radius)
        assertEquals(4, wheel.sector)
        assertTrue(wheel.isOpen(now + 10L))
    }

    @Test
    fun aSecondThumbBeforeTheHoldReplacesThePress() {
        val wheel = WheelGesture()
        wheel.press(1, 300f, 300f, 0L)
        // Nothing has been chosen yet, so the newest finger is the gesture.
        assertTrue(wheel.press(2, 600f, 400f, 50L))
        assertEquals(2, wheel.pointer)
        assertEquals(600f, wheel.originX, 0f)
        assertEquals(400f, wheel.originY, 0f)
        assertFalse(wheel.isOpen(50L))
        assertTrue(wheel.isOpen(50L + TrickWheel.HOLD_MS))
    }

    @Test
    fun theOwnersLiftClosesItAndReportsTheMove() {
        val wheel = WheelGesture()
        wheel.press(4, 250f, 250f, 1_000L)
        val (dx, dy) = at(2)
        wheel.aim(dx, dy, radius)
        val done = wheel.release(4, 1_000L + TrickWheel.HOLD_MS + 30L)
        assertNotNull(done)
        assertTrue("held, so the move plays", done!!.wasHold)
        assertEquals(2, done.sector)
        assertEquals(-1, wheel.pointer)
        assertEquals(-1, wheel.sector)
        assertFalse(wheel.isOpen(1_000L + TrickWheel.HOLD_MS + 40L))
    }

    @Test
    fun aQuickTapClosesWithNoMove() {
        val wheel = WheelGesture()
        wheel.press(5, 250f, 250f, 1_000L)
        val (dx, dy) = at(1)
        wheel.aim(dx, dy, radius)
        val done = wheel.release(5, 1_000L + TrickWheel.HOLD_MS - 1)
        assertNotNull(done)
        assertFalse("a tap must not play a skill", done!!.wasHold)
        assertEquals(-1, wheel.pointer)
        assertFalse(wheel.isOpen(1_000L + TrickWheel.HOLD_MS))
    }

    @Test
    fun cancelClearsEverythingAndAFreshPressStartsOver() {
        val wheel = WheelGesture()
        wheel.press(1, 250f, 250f, 0L)
        wheel.aim(radius, 0f, radius)
        assertTrue(wheel.isOpen(TrickWheel.HOLD_MS))
        wheel.cancel()
        assertEquals(-1, wheel.pointer)
        assertEquals(-1, wheel.sector)
        assertFalse(wheel.isOpen(TrickWheel.HOLD_MS + 10_000L))
        // A lift after a cancel is not a release of anything.
        assertNull(wheel.release(1, TrickWheel.HOLD_MS + 20L))
        // The next press is a new gesture, with its own hold clock.
        assertTrue(wheel.press(1, 400f, 400f, 5_000L))
        assertFalse(wheel.isOpen(5_000L))
        assertTrue(wheel.isOpen(5_000L + TrickWheel.HOLD_MS))
    }

    @Test
    fun aReleaseBeforeAnyPressIsIgnored() {
        val wheel = WheelGesture()
        assertNull(wheel.release(1, 10L))
        assertEquals(-1, wheel.pointer)
        assertFalse(wheel.isOpen(10L))
    }

    @Test
    fun aimingWithoutAPressDoesNothing() {
        val wheel = WheelGesture()
        wheel.aim(radius, 0f, radius)
        assertEquals(-1, wheel.sector)
    }

    @Test
    fun theHoldClockIsStableAcrossALongRoll() {
        val wheel = WheelGesture()
        wheel.press(1, 0f, 0f, 123L)
        // A minute of continuous rolling: still open, still the same gesture.
        for (t in 123L + TrickWheel.HOLD_MS until 60_000L step 4L) {
            wheel.aim(radius, 0f, radius)
            assertTrue(wheel.isOpen(t))
        }
        assertEquals(TrickWheel.HOLD_MS, TrickWheel.HOLD_MS)
        assertEquals(60_000L - 123L, wheel.heldFor(60_000L))
    }
}
