package com.pes2017.recomp

/**
 * What the pad knows about the title's own in-match menu -- the sheet START
 * opens over a running match (Resume, Game Plan, Substitutions, the way out).
 *
 * The engine cannot answer this quickly. While the sheet is up the title keeps
 * naming the flow it was already in, and the name it returns to when the sheet
 * closes is not announced until the title is actually back in play, seconds
 * later. Waiting for that is what left the switch on MENU after Cross.
 */
enum class StickSheet {
    /** No sheet the pad opened: the engine's answer is the whole rule. */
    ABSENT,

    /** The sheet is up: MENU, whatever flow name the engine is still saying. */
    OPEN,

    /**
     * The player has just left the sheet -- Cross on Resume, or START again.
     *
     * This is the one thing the pad knows and the engine does not: the match is
     * coming back, so the switch goes to MATCH on this frame rather than on the
     * next phase sample. It outranks the engine only until the engine says
     * something *different* from what it said while the sheet was open, which
     * is when [TouchOverlayView] drops it and the flow is the rule again.
     */
    RESUMED,
}

/**
 * Where the MATCH/MENU switch above the movement socket belongs when the player
 * has asked it to follow the game (`Prefs.stickAuto`), instead of tapping it.
 *
 * The rule is one sentence: **MATCH while a match is running, MENU in the
 * title's menus, whenever the game is paused, and while the title's own
 * in-match menu is open** (a paused match is still the match flow, so the pause
 * has to be a source of its own, and the in-match menu -- Game Plan,
 * Substitutions, the pause sheet START opens -- is a menu even though the engine
 * still calls the flow `Match/...`).
 *
 * Two sources are read in a fixed order, because they answer at different
 * speeds. The engine's *named flow* (`MatchState`, pushed the moment the title
 * enters a procedure) is the game's own state and outranks the *phase*, which
 * is the last name the title said and is sampled twice a second. A state
 * neither has named yet is not an answer, so auto leaves the switch alone
 * there rather than guessing: a build whose phase signal never arrives must
 * behave like Manual, not like a pad that can never sprint.
 *
 * Pure and free of `Context`, so `StickSwitchTest` checks every case without a
 * device -- the same arrangement `GamePhase`/`PhaseMask` have.
 */
object StickSwitch {

    /** No tap override: auto owns the switch. */
    const val NO_OVERRIDE = Int.MIN_VALUE

    /**
     * The stage auto wants, or `null` to leave the player's own switch alone.
     *
     * `sheet` is the title's sheet as the pad followed it (see [StickSheet]) and
     * `matchState` is the flow the engine named, which outranks the sampled
     * `phase`.
     *
     * `override` is the state a tap on the pill was made in ([key]): while the
     * game is still in that state the tap stands, and the moment it changes --
     * kick-off, a pause, the title's menu opening or closing, another screen --
     * auto takes the switch back.
     */
    fun stage(
        auto: Boolean,
        phase: GamePhase,
        paused: Boolean,
        sheet: StickSheet,
        matchState: MatchState = MatchState.UNKNOWN,
        override: Int = NO_OVERRIDE,
    ): Int? {
        if (!auto) return null
        if (override != NO_OVERRIDE && override == key(phase, paused, sheet)) return null
        // A sheet is a menu even while the flow name still says match.
        if (paused || sheet == StickSheet.OPEN) return Prefs.STAGE_TWO
        // The player has just resumed: MATCH on this frame. The engine's own
        // answer is still the sheet's for as long as the title takes to walk
        // back into play, and waiting for it is the delay this removes.
        if (sheet == StickSheet.RESUMED) return Prefs.STAGE_ONE
        // The flow the engine named: pushed, one event per change.
        return when (matchState) {
            MatchState.LOADING, MatchState.IN_PLAY -> Prefs.STAGE_ONE
            MatchState.MENUS -> Prefs.STAGE_TWO
            // Nothing named yet: the last flow name is all there is.
            MatchState.UNKNOWN -> when (phase) {
                GamePhase.MATCH -> Prefs.STAGE_ONE
                GamePhase.MENUS -> Prefs.STAGE_TWO
                GamePhase.UNKNOWN -> null
            }
        }
    }

    /**
     * The sheet's next state from one key press, with no clock and no phase.
     *
     * START toggles it: open in a match, and the title's own resume when it is
     * already up. Cross (A) is the sheet's Resume while the sheet is untouched,
     * because the sheet opens with **Resume** highlighted; once a thumb has
     * navigated (the stick, the d-pad, the pill), Cross selects another row
     * instead and closes nothing. Nothing here reads a button on its own: a
     * press counts only together with the state it was made in.
     */
    fun sheetAfter(
        current: StickSheet,
        startPressed: Boolean = false,
        cancelPressed: Boolean = false,
        navigated: Boolean = false,
    ): StickSheet = when {
        startPressed -> if (current == StickSheet.OPEN) StickSheet.RESUMED else StickSheet.OPEN
        cancelPressed && current == StickSheet.OPEN && !navigated -> StickSheet.RESUMED
        else -> current
    }

    /**
     * Whether the engine's answer says the title is still in a match flow.
     *
     * The named flow wins where there is one, because it is pushed; the sampled
     * phase is the fallback for a build that has not named a flow yet.
     */
    fun inMatchFlow(phase: GamePhase, matchState: MatchState): Boolean = when (matchState) {
        MatchState.LOADING, MatchState.IN_PLAY -> true
        MatchState.MENUS -> false
        MatchState.UNKNOWN -> phase == GamePhase.MATCH
    }

    /**
     * The state a tap was made in: one number per (phase, paused, sheet)
     * combination. A tap with the pad editor open, a tap on the pitch and a tap
     * inside the title's pause sheet are different states, so leaving one does
     * not keep an override alive.
     */
    fun key(phase: GamePhase, paused: Boolean, sheet: StickSheet): Int =
        (phase.ordinal * 2 + if (paused) 1 else 0) * StickSheet.entries.size + sheet.ordinal
}
