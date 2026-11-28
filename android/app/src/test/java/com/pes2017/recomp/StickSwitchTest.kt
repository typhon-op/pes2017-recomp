package com.pes2017.recomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto switch's arithmetic, without a view or a device: which half of the
 * MATCH/MENU switch the game's state asks for, when a tap outranks it, and the
 * title's sheet as the pad follows it.
 */
class StickSwitchTest {

    private fun stage(
        phase: GamePhase,
        paused: Boolean = false,
        sheet: StickSheet = StickSheet.ABSENT,
        matchState: MatchState = MatchState.UNKNOWN,
        auto: Boolean = true,
        override: Int = StickSwitch.NO_OVERRIDE,
    ): Int? = StickSwitch.stage(auto, phase, paused, sheet, matchState, override)

    @Test
    fun manualLeavesThePlayersSwitchAlone() {
        // Off is off: every state returns nothing, so the pill the player set
        // stays exactly where they set it.
        for (phase in GamePhase.entries) {
            for (paused in listOf(false, true)) {
                for (sheet in StickSheet.entries) {
                    for (state in MatchState.entries) {
                        assertNull(
                            stage(
                                phase, paused = paused, sheet = sheet,
                                matchState = state, auto = false,
                            ),
                        )
                    }
                }
            }
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
    fun theTitlesInMatchMenuGetsTheMenuHalf() {
        // Game Plan, Substitutions and the START pause sheet are menus inside
        // the match flow: the engine still says MATCH, so this has to win.
        assertEquals(Prefs.STAGE_TWO, stage(GamePhase.MATCH, sheet = StickSheet.OPEN))
        // START resumes: the same state without the sheet is the match again.
        assertEquals(Prefs.STAGE_ONE, stage(GamePhase.MATCH, sheet = StickSheet.ABSENT))
    }

    @Test
    fun aPausedMatchInsideTheTitlesMenuStaysOnTheMenuHalf() {
        assertEquals(
            Prefs.STAGE_TWO,
            stage(GamePhase.MATCH, paused = true, sheet = StickSheet.OPEN),
        )
    }

    @Test
    fun anUnknownPhaseLeavesTheSwitchAlone() {
        // An engine that never reports a phase must behave like Manual, not like
        // a pad that can never sprint: a guess here would take the run away for
        // good on a build whose signal is broken.
        assertNull(stage(GamePhase.UNKNOWN))
        // A pause is still known, and still means MENU, whatever the phase says.
        assertEquals(Prefs.STAGE_TWO, stage(GamePhase.UNKNOWN, paused = true))
        // A sheet the pad opened is a menu too, and MENU is what auto answers.
        assertEquals(Prefs.STAGE_TWO, stage(GamePhase.UNKNOWN, sheet = StickSheet.OPEN))
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
        val tappedInPlay = StickSwitch.key(GamePhase.MATCH, paused = false, sheet = StickSheet.ABSENT)
        assertNull(stage(GamePhase.MATCH, override = tappedInPlay))
        // ...and auto has the switch back at the next kick-off or pause.
        assertEquals(Prefs.STAGE_TWO, stage(GamePhase.MATCH, paused = true, override = tappedInPlay))
        // A different state is not the one the tap was made in: auto answers
        // again, and in the title's menus that answer is MENU, not nothing.
        assertEquals(Prefs.STAGE_TWO, stage(GamePhase.MENUS, override = tappedInPlay))
    }

    @Test
    fun aTapInThePauseMenuDoesNotSurviveResuming() {
        val tappedPaused = StickSwitch.key(GamePhase.MATCH, paused = true, sheet = StickSheet.ABSENT)
        assertNull(stage(GamePhase.MATCH, paused = true, override = tappedPaused))
        assertEquals(Prefs.STAGE_ONE, stage(GamePhase.MATCH, paused = false, override = tappedPaused))
    }

    @Test
    fun aTapInTheTitlesMenuDoesNotSurviveStartResuming() {
        // A thumb that flips to MATCH inside Game Plan keeps it for as long as
        // Game Plan is open; START closing the sheet is the state change that
        // gives auto the switch back.
        val tappedInTitleMenu =
            StickSwitch.key(GamePhase.MATCH, paused = false, sheet = StickSheet.OPEN)
        assertNull(stage(GamePhase.MATCH, sheet = StickSheet.OPEN, override = tappedInTitleMenu))
        assertEquals(
            Prefs.STAGE_ONE,
            stage(GamePhase.MATCH, sheet = StickSheet.RESUMED, override = tappedInTitleMenu),
        )
    }

    // ---- the title's own in-match menu ----------------------------------

    @Test
    fun startOpensAndClosesTheTitlesSheet() {
        assertEquals(StickSheet.OPEN, StickSwitch.sheetAfter(StickSheet.ABSENT, startPressed = true))
        assertEquals(StickSheet.RESUMED, StickSwitch.sheetAfter(StickSheet.OPEN, startPressed = true))
        // A press with nothing to open or close changes nothing.
        assertEquals(StickSheet.ABSENT, StickSwitch.sheetAfter(StickSheet.ABSENT))
    }

    @Test
    fun crossOnAnUntouchedSheetIsItsResume() {
        // The sheet opens with Resume highlighted, so A selects it and the
        // match is back -- the exit a START-only latch never saw.
        assertEquals(StickSheet.RESUMED, StickSwitch.sheetAfter(StickSheet.OPEN, cancelPressed = true))
        // Cross with no sheet up is a pass, not a resume.
        assertEquals(
            StickSheet.ABSENT,
            StickSwitch.sheetAfter(StickSheet.ABSENT, cancelPressed = true),
        )
    }

    @Test
    fun crossAfterNavigatingSelectsARowInsteadOfResuming() {
        assertEquals(
            StickSheet.OPEN,
            StickSwitch.sheetAfter(StickSheet.OPEN, cancelPressed = true, navigated = true),
        )
    }

    @Test
    fun aResumeIsTheMatchAgainOnTheSameFrame() {
        // The reported bug, step by step: Star opens the sheet in a match and
        // the switch goes to MENU; Cross resumes and the title is back in play,
        // but the flow name it was naming is the sheet's own, and the switch
        // sat on MENU until a sample came round seconds later. Nothing here
        // waits: the press itself is the answer, in the state it arrives in.
        val opened = StickSwitch.sheetAfter(StickSheet.ABSENT, startPressed = true)
        assertEquals(Prefs.STAGE_TWO, stage(GamePhase.MATCH, sheet = opened))
        val resumed = StickSwitch.sheetAfter(opened, cancelPressed = true)
        assertEquals(Prefs.STAGE_ONE, stage(GamePhase.MATCH, sheet = resumed))

        // The same press, with the engine still naming the sheet's own flow --
        // and even with the coarser phase still reading the menus. MATCH now.
        assertEquals(
            Prefs.STAGE_TWO,
            stage(GamePhase.MENUS, sheet = opened, matchState = MatchState.MENUS),
        )
        assertEquals(
            Prefs.STAGE_ONE,
            stage(GamePhase.MENUS, sheet = resumed, matchState = MatchState.MENUS),
        )
        // ...and with the match flow already pushed: also MATCH, with no
        // change needed to arrive at it.
        assertEquals(
            Prefs.STAGE_ONE,
            stage(GamePhase.MATCH, sheet = resumed, matchState = MatchState.IN_PLAY),
        )
    }

    @Test
    fun theNamedFlowOutranksASampledPhase() {
        // Pushed the moment the title enters a flow, so it is the fresher of
        // the two answers wherever both exist -- in both directions.
        assertEquals(
            Prefs.STAGE_ONE,
            stage(GamePhase.MENUS, matchState = MatchState.IN_PLAY),
        )
        assertEquals(
            Prefs.STAGE_TWO,
            stage(GamePhase.MATCH, matchState = MatchState.MENUS),
        )
        // A match still loading is the match flow, not a menu.
        assertEquals(
            Prefs.STAGE_ONE,
            stage(GamePhase.MENUS, matchState = MatchState.LOADING),
        )
    }

    @Test
    fun aResumeOutranksNothingButTheStaleAnswer() {
        // While the sheet is up the switch is MENU even after a match flow has
        // been pushed: the pad's own knowledge wins there.
        assertEquals(
            Prefs.STAGE_TWO,
            stage(GamePhase.MATCH, sheet = StickSheet.OPEN, matchState = MatchState.IN_PLAY),
        )
        // A pause still means MENU, resumed or not.
        assertEquals(
            Prefs.STAGE_TWO,
            stage(GamePhase.MATCH, paused = true, sheet = StickSheet.RESUMED),
        )
    }

    @Test
    fun aSheetIsTheMatchFlowOrAMenu() {
        assertTrue(StickSwitch.inMatchFlow(GamePhase.MATCH, MatchState.UNKNOWN))
        assertFalse(StickSwitch.inMatchFlow(GamePhase.MENUS, MatchState.UNKNOWN))
        assertTrue(StickSwitch.inMatchFlow(GamePhase.MENUS, MatchState.IN_PLAY))
        assertFalse(StickSwitch.inMatchFlow(GamePhase.MATCH, MatchState.MENUS))
    }

    @Test
    fun theKeyTellsEveryStateApart() {
        val keys = HashSet<Int>()
        for (phase in GamePhase.entries) {
            for (paused in listOf(false, true)) {
                for (sheet in StickSheet.entries) {
                    keys += StickSwitch.key(phase, paused, sheet)
                }
            }
        }
        assertEquals(GamePhase.entries.size * 2 * StickSheet.entries.size, keys.size)
        assertNotEquals(
            StickSwitch.key(GamePhase.MATCH, true, StickSheet.ABSENT),
            StickSwitch.key(GamePhase.MATCH, false, StickSheet.OPEN),
        )
    }
}
