package com.pes2017.recomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The auto switch's arithmetic, without a view or a device: which half of the
 * MATCH/MENU switch the game's state asks for, and when a tap outranks it.
 */
class StickSwitchTest {

    private fun stage(
        phase: GamePhase,
        paused: Boolean = false,
        auto: Boolean = true,
        override: Int = StickSwitch.NO_OVERRIDE,
    ): Int? = StickSwitch.stage(auto, phase, paused, override)

    @Test
    fun manualLeavesThePlayersSwitchAlone() {
        // Off is off: every state returns nothing, so the pill the player set
        // stays exactly where they set it.
        for (phase in GamePhase.entries) {
            assertNull(stage(phase, auto = false))
            assertNull(stage(phase, paused = true, auto = false))
        }
    }

    @Test
    fun aRunningMatchGetsTheSprintHalf() {
        assertEquals(Prefs.STAGE_ONE, stage(GamePhase.MATCH))
    }

    @Test
    fun theMenusGetTheHalfThatSendsNoR1() {
        assertEquals(Prefs.STAGE_TWO, stage(GamePhase.MENUS))
    }

    @Test
    fun anUnknownPhaseLeavesTheSwitchAlone() {
        // An engine that never reports a phase must behave like Manual, not like
        // a pad that can never sprint: a guess here would take the run away for
        // good on a build whose signal is broken.
        assertNull(stage(GamePhase.UNKNOWN))
        // A pause is still known, and still means MENU, whatever the phase says.
        assertEquals(Prefs.STAGE_TWO, stage(GamePhase.UNKNOWN, paused = true))
    }

    @Test
    fun pausingAMatchSwitchesToTheMenuHalf() {
        // The title keeps its match flow while our pause menu is up, so the
        // pause is what has to win over the phase.
        assertEquals(Prefs.STAGE_TWO, stage(GamePhase.MATCH, paused = true))
    }

    @Test
    fun pausingInTheMenusStaysOnTheMenuHalf() {
        assertEquals(Prefs.STAGE_TWO, stage(GamePhase.MENUS, paused = true))
    }

    @Test
    fun aTapStandsUntilTheGameMovesOn() {
        // A thumb that flips to MENU mid-match to dribble slowly keeps it while
        // the match carries on...
        val tappedInPlay = StickSwitch.key(GamePhase.MATCH, paused = false)
        assertNull(stage(GamePhase.MATCH, override = tappedInPlay))
        // ...and auto has the switch back at the next kick-off or pause.
        assertEquals(Prefs.STAGE_TWO, stage(GamePhase.MATCH, paused = true, override = tappedInPlay))
        // A different state is not the one the tap was made in: auto answers
        // again, and in the title's menus that answer is MENU, not nothing.
        assertEquals(Prefs.STAGE_TWO, stage(GamePhase.MENUS, override = tappedInPlay))
    }

    @Test
    fun aTapInThePauseMenuDoesNotSurviveResuming() {
        val tappedPaused = StickSwitch.key(GamePhase.MATCH, paused = true)
        assertNull(stage(GamePhase.MATCH, paused = true, override = tappedPaused))
        assertEquals(Prefs.STAGE_ONE, stage(GamePhase.MATCH, paused = false, override = tappedPaused))
    }

    @Test
    fun theKeyTellsEveryStateApart() {
        val keys = HashSet<Int>()
        for (phase in GamePhase.entries) {
            keys += StickSwitch.key(phase, paused = false)
            keys += StickSwitch.key(phase, paused = true)
        }
        assertEquals(GamePhase.entries.size * 2, keys.size)
        assertNotEquals(StickSwitch.key(GamePhase.MATCH, true), StickSwitch.key(GamePhase.MENUS, false))
    }
}
