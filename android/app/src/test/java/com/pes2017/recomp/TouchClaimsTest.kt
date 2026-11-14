package com.pes2017.recomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pointer bookkeeping behind the on-screen pad: a control is held while any
 * finger is on it, a stick keeps its deflection until the last finger lifts,
 * and a toggle is a tap rather than a hold.
 *
 * The press bits are the other half of it: holds are what the guest samples,
 * presses are what it can miss, so every new press leaves a bit for the overlay
 * to send as a pulse.
 */
class TouchClaimsTest {

    private companion object {
        // The XInput bits these elements carry, as NativeBridge names them.
        const val A = 0x1000
        const val B = 0x2000
        const val X = 0x4000
    }

    private fun control(
        id: String = "a",
        kind: ControlKind = ControlKind.BUTTON,
        value: Int = A,
        toggle: Boolean = false,
    ) = ControlElement(id, kind, value, "A", 0.5f, 0.5f, 68f, toggle = toggle)

    @Test
    fun twoFingersOnOneButtonStayDownUntilBothLift() {
        val claims = TouchClaims()
        val a = control()

        assertTrue(claims.press(1, a))
        assertTrue(claims.press(2, a))
        assertTrue("both fingers are on it", claims.isOn("a"))
        assertEquals(2, claims.holdCount("a"))

        val first = claims.release(1) ?: error("the first finger had a claim")
        assertEquals(a, first.element)
        assertFalse("one finger is still on it", first.lastClaim)
        assertTrue("a button must not come up under the other thumb", claims.isOn("a"))

        val second = claims.release(2) ?: error("the second finger had a claim")
        assertTrue(second.lastClaim)
        assertFalse(claims.isOn("a"))
        assertEquals(0, claims.holdCount("a"))
    }

    @Test
    fun releasingAnUnknownPointerDoesNothing() {
        val claims = TouchClaims()
        claims.press(1, control())

        assertNull(claims.release(99))
        assertTrue(claims.isOn("a"))
        assertEquals(1, claims.holdCount("a"))
    }

    @Test
    fun pressingTwiceFromOnePointerDoesNotLeakACount() {
        val claims = TouchClaims()
        val a = control()
        claims.press(1, a)
        claims.press(1, a)          // a missed ACTION_UP, not a second finger

        assertEquals(1, claims.holdCount("a"))
        assertTrue(claims.release(1)!!.lastClaim)
        assertFalse(claims.isOn("a"))
    }

    @Test
    fun aToggleIsATapAndNotAHold() {
        val claims = TouchClaims()
        val sprint = control("rb", value = 0x0100, toggle = true)

        assertTrue(claims.press(1, sprint))
        assertTrue("a tap latches it", claims.isOn("rb"))
        assertEquals(0, claims.holdCount("rb"))
        assertTrue(claims.release(1)!!.lastClaim)
        assertTrue("lifting the finger does not unlatch it", claims.isOn("rb"))

        claims.press(2, sprint)
        assertFalse("the next tap lets go", claims.isOn("rb"))
    }

    @Test
    fun togglesSurviveClearMomentaryButNotClearAll() {
        val claims = TouchClaims()
        claims.press(1, control("rb", value = 0x0100, toggle = true))
        claims.release(1)
        claims.press(2, control())

        claims.clearMomentary()
        assertTrue(claims.isOn("rb"))
        assertFalse(claims.isOn("a"))

        claims.clearAll()
        assertFalse(claims.isOn("rb"))
    }

    @Test
    fun twoFingersOnOneStickKeepItHeld() {
        val claims = TouchClaims()
        val stick = control("stick", ControlKind.STICK, value = 0)

        claims.press(1, stick)
        claims.press(2, stick)
        assertEquals(2, claims.stickHoldCount("stick"))

        val first = claims.release(1) ?: error("the first finger had a claim")
        assertFalse("the other thumb is still steering", first.lastClaim)
        assertEquals(1, claims.stickHoldCount("stick"))

        val second = claims.release(2) ?: error("the second finger had a claim")
        assertTrue(second.lastClaim)
        assertEquals(0, claims.stickHoldCount("stick"))
    }

    @Test
    fun aPointerIdCanBeReusedAfterItLifts() {
        val claims = TouchClaims()
        val a = control()

        claims.press(0, a)
        assertTrue(claims.release(0)!!.lastClaim)
        claims.press(0, a)
        assertEquals(1, claims.holdCount("a"))
        assertTrue(claims.isOn("a"))
    }

    @Test
    fun elementForFollowsThePointer() {
        val claims = TouchClaims()
        val a = control("a")
        val b = control("b", value = B)

        claims.press(4, a)
        claims.press(5, b)
        assertEquals("a", claims.elementFor(4)?.id)
        assertEquals("b", claims.elementFor(5)?.id)
        assertNull(claims.elementFor(6))

        claims.release(4)
        assertNull(claims.elementFor(4))
    }

    // ---- the press bits the guest may otherwise miss ------------------------

    @Test
    fun everyPressLeavesItsBitForOneTake() {
        val claims = TouchClaims()
        val b = control("b", value = B)

        claims.press(1, b)
        assertEquals(B, claims.takeNewPresses())
        assertEquals("a take forgets", 0, claims.takeNewPresses())

        claims.release(1)
        claims.press(2, b)
        assertEquals(B, claims.takeNewPresses())
    }

    @Test
    fun aSecondFingerOnAHeldButtonIsStillATap() {
        val claims = TouchClaims()
        val b = control("b", value = B)

        claims.press(1, b)
        assertEquals(B, claims.takeNewPresses())

        // The thumb stays down; another finger taps the same button. The hold
        // does not change, but the guest must still see a second press.
        claims.press(2, b)
        assertTrue(claims.isOn("b"))
        assertEquals(B, claims.takeNewPresses())

        claims.release(2)
        assertTrue("the first finger still holds it", claims.isOn("b"))
        assertEquals("letting go is not a press", 0, claims.takeNewPresses())
    }

    @Test
    fun differentButtonsPulseTogether() {
        val claims = TouchClaims()

        claims.press(1, control("b", value = B))
        claims.press(2, control("x", value = X))

        assertEquals(B or X, claims.takeNewPresses())
    }

    @Test
    fun sticksAndTogglesDoNotPulse() {
        val claims = TouchClaims()

        claims.press(1, control("stick", ControlKind.STICK, value = 0))
        claims.press(2, control("rb", value = 0x0100, toggle = true))

        assertEquals("neither a stick nor a latch is a button press", 0, claims.takeNewPresses())
    }

    @Test
    fun clearMomentaryDropsAPressThatWasOnItsWay() {
        val claims = TouchClaims()
        claims.press(1, control("b", value = B))

        claims.clearMomentary()
        assertEquals(0, claims.takeNewPresses())
    }
}
