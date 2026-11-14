package com.pes2017.recomp

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app-side copies of runtime/stick_curve.h and runtime/input_stick_arrows.h. */
class TouchMathTest {

    // ---- stick curve ------------------------------------------------------

    private fun shape(x: Float, y: Float, deadzone: Float = 0.08f, curve: Float = 1.45f) =
        StickShape.apply(x, y, deadzone, curve)

    @Test
    fun insideTheDeadzoneSendsNothing() {
        val out = shape(0.05f, 0.05f)
        assertEquals(0f, out[0], 0f)
        assertEquals(0f, out[1], 0f)
    }

    @Test
    fun aRestingThumbIsExactlyZeroRatherThanNearly() {
        assertEquals(0f, shape(0f, 0f)[0], 0f)
        assertEquals(0f, shape(0f, 0f)[1], 0f)
    }

    @Test
    fun fullThrowStillReachesTheRim() {
        val out = shape(1f, 0f)
        assertEquals(1f, out[0], 0.001f)
        assertEquals(0f, out[1], 0.001f)
    }

    @Test
    fun theDeadzoneIsNotLeftBehindAtTheTop() {
        // Just inside the deadzone sends nothing; just outside it sends
        // something small rather than skipping to the middle.
        assertEquals(0f, shape(0.079f, 0f)[0], 0f)
        assertTrue(shape(0.10f, 0f)[0] < 0.10f)
    }

    @Test
    fun shapeIsRadialAndNotPerAxis() {
        // The same throw along the diagonal keeps its direction.
        val out = shape(0.5f, 0.5f)
        assertEquals(1f, out[0] / out[1], 0.001f)
    }

    @Test
    fun aCurveAboveOneIsFinerAroundTheMiddle() {
        val linear = StickShape.apply(0.5f, 0f, 0.08f, 1f)[0]
        val curved = StickShape.apply(0.5f, 0f, 0.08f, 1.45f)[0]
        assertTrue(curved < linear)
    }

    @Test
    fun aCurveBelowOneReachesTheEdgeSooner() {
        val linear = StickShape.apply(0.5f, 0f, 0.08f, 1f)[0]
        val quicker = StickShape.apply(0.5f, 0f, 0.08f, 0.7f)[0]
        assertTrue(quicker > linear)
    }

    @Test
    fun noDeadzoneAndAStraightCurveChangeNothing() {
        val out = shape(0.35f, -0.25f, 0f, 1f)
        assertEquals(0.35f, out[0], 0.001f)
        assertEquals(-0.25f, out[1], 0.001f)
    }

    @Test
    fun shapingNeverGrowsTheThrowPastTheRim() {
        val out = shape(1.4f, 0f)
        assertTrue(hypot(out[0], out[1]) <= 1.001f)
    }

    // ---- stick standing in for the d-pad ----------------------------------

    @Test
    fun theMiddleSendsNoArrow() {
        assertEquals(0, StickArrows().bits(0f, 0f))
    }

    @Test
    fun aPushPastThePressPointSetsTheArm() {
        assertEquals(NativeBridge.UP, StickArrows().bits(0f, 0.65f))
        assertEquals(NativeBridge.DOWN, StickArrows().bits(0f, -0.65f))
        assertEquals(NativeBridge.LEFT, StickArrows().bits(-0.65f, 0f))
        assertEquals(NativeBridge.RIGHT, StickArrows().bits(0.65f, 0f))
    }

    @Test
    fun aThumbInsideThePressPointIsNotAnArrow() {
        assertEquals(0, StickArrows().bits(0f, 0.55f))
    }

    @Test
    fun theArmIsKeptBetweenPressAndRelease() {
        val arrows = StickArrows()
        assertEquals(NativeBridge.UP, arrows.bits(0f, 0.65f))
        // Back inside the press point but outside the release ring: still up,
        // or a thumb resting on the threshold would chatter the bit.
        assertEquals(NativeBridge.UP, arrows.bits(0f, 0.50f))
        assertEquals(0, arrows.bits(0f, 0.40f))
    }

    @Test
    fun swingingTheOtherWayLetsGoOfTheOldArm() {
        val arrows = StickArrows()
        assertEquals(NativeBridge.UP, arrows.bits(0f, 0.65f))
        assertEquals(NativeBridge.DOWN, arrows.bits(0f, -0.65f))
    }

    @Test
    fun aDiagonalHoldsBothArmsItIsPast() {
        val bits = StickArrows().bits(0.65f, 0.65f)
        assertEquals(NativeBridge.RIGHT or NativeBridge.UP, bits)
    }

    @Test
    fun clearingLetsGoOfEveryArm() {
        val arrows = StickArrows()
        arrows.bits(0.65f, 0.65f)
        assertTrue(arrows.any())
        arrows.clear()
        assertFalse(arrows.any())
        assertEquals(0, arrows.bits(0f, 0f))
    }

    @Test
    fun twoLatchesDoNotShareState() {
        val one = StickArrows()
        val two = StickArrows()
        one.bits(0f, 0.65f)
        assertEquals(0, two.bits(0f, 0f))
    }
}
