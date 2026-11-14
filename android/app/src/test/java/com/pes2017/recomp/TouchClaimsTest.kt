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
 */
class TouchClaimsTest {

    private fun control(
        id: String = "a",
        kind: ControlKind = ControlKind.BUTTON,
        toggle: Boolean = false,
    ) = ControlElement(id, kind, 0x1000, "A", 0.5f, 0.5f, 68f, toggle = toggle)

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
        val sprint = control("rb", toggle = true)

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
        claims.press(1, control("rb", toggle = true))
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
        val stick = control("stick", ControlKind.STICK)

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
        val b = control("b")

        claims.press(4, a)
        claims.press(5, b)
        assertEquals("a", claims.elementFor(4)?.id)
        assertEquals("b", claims.elementFor(5)?.id)
        assertNull(claims.elementFor(6))

        claims.release(4)
        assertNull(claims.elementFor(4))
    }
}
