package com.pes2017.recomp

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
 * A phase the engine has not named yet is *not* an answer, so auto leaves the
 * switch alone there rather than guessing: a build whose phase signal never
 * arrives must behave like Manual, not like a pad that can never sprint.
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
     * `titleMenu` is the title's own in-match menu, followed from START presses
     * by TouchOverlayView: the engine keeps reporting `Match/...` while it is
     * open, so without it auto would stay on the sprint stick in Game Plan and
     * Substitutions.
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
        titleMenu: Boolean,
        override: Int,
    ): Int? {
        if (!auto) return null
        if (override != NO_OVERRIDE && override == key(phase, paused, titleMenu)) return null
        if (paused || titleMenu) return Prefs.STAGE_TWO
        return when (phase) {
            GamePhase.MATCH -> Prefs.STAGE_ONE
            GamePhase.MENUS -> Prefs.STAGE_TWO
            // Nothing named yet: leave the player's switch exactly as it is.
            GamePhase.UNKNOWN -> null
        }
    }

    /**
     * The state a tap was made in: one number per (phase, paused, title menu)
     * combination. A tap with the pad editor open, a tap on the pitch and a tap
     * inside the title's pause sheet are different states, so leaving one does
     * not keep an override alive.
     */
    fun key(phase: GamePhase, paused: Boolean, titleMenu: Boolean): Int =
        (phase.ordinal * 2 + if (paused) 1 else 0) * 2 + if (titleMenu) 1 else 0
}
