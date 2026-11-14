package com.pes2017.recomp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a control belongs on screen: the player's own switch and the phase the
 * runtime reports, together.
 *
 * A build that cannot tell what the title is doing reports [GamePhase.UNKNOWN],
 * and the menus' controls stay where the player put them rather than vanishing
 * because of a guess.
 */
class ControlLayoutPhaseTest {

    private fun control(
        id: String = "dpad",
        kind: ControlKind = ControlKind.DPAD,
        visible: Boolean = true,
        shownWhen: PhaseMask = PhaseMask.MENUS,
    ) = ControlElement(id, kind, 0, "D-PAD", 0.25f, 0.6f, 110f, visible = visible, shownWhen = shownWhen)

    @Test
    fun aMenusControlIsOnScreenOverTheMenusAndOffThePitch() {
        val dpad = control()

        assertTrue(dpad.shownIn(GamePhase.MENUS))
        assertFalse(dpad.shownIn(GamePhase.MATCH))
    }

    @Test
    fun anUnknownPhaseKeepsTheMenusControls() {
        // No addresses for the title's client states: hiding the d-pad would be
        // hiding it on a pitch the app cannot recognise.
        assertTrue(control().shownIn(GamePhase.UNKNOWN))
        assertFalse(control(shownWhen = PhaseMask.MATCH).shownIn(GamePhase.UNKNOWN))
    }

    @Test
    fun aMatchControlIsTheOtherWayRound() {
        val hud = control(id = "hud", kind = ControlKind.LOOK, shownWhen = PhaseMask.MATCH)

        assertFalse(hud.shownIn(GamePhase.MENUS))
        assertTrue(hud.shownIn(GamePhase.MATCH))
    }

    @Test
    fun alwaysIsAlways() {
        val start = control(id = "start", kind = ControlKind.BUTTON, shownWhen = PhaseMask.ALWAYS)

        for (phase in GamePhase.entries) assertTrue(start.shownIn(phase))
    }

    @Test
    fun thePlayersSwitchStillCounts() {
        val hidden = control(visible = false)

        assertFalse(hidden.shownIn(GamePhase.MENUS))
        assertFalse(hidden.shownIn(GamePhase.UNKNOWN))
    }

    @Test
    fun theDefaultLayoutHasAMenusDpadThatIsOffThePitch() {
        val layout = ControlLayout.defaults(800f, 360f)
        val dpad = layout.find("dpad") ?: error("the default pad has a d-pad")

        assertTrue(dpad.kind == ControlKind.DPAD)
        assertTrue("it is there for the menus", dpad.shownIn(GamePhase.MENUS))
        assertFalse("and not over the pitch, where the stick stands in", dpad.shownIn(GamePhase.MATCH))
    }
}
