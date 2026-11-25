package com.pes2017.recomp

/**
 * The native skill table (runtime/skill_moves.h) as the app names it.
 *
 * A move index is stored in a layout file and handed to
 * [NativeBridge.nativeSkillPlay], so the order here is the order there: a move
 * may be appended, never inserted or shuffled. The C++ side refuses an index
 * it does not know, and logs it, rather than reading past the table.
 *
 * The catalogue is the verified PES 2017 set (docs/skill-moves.md), not every
 * name a guide uses. Several names share one entry because their right-stick
 * halves are identical and only the player's left stick differs; the entry's
 * name lists the family. A move that ends by waiting for a left-stick
 * direction says so, because the player supplies that part: the wheel only
 * plays the right-stick (or button) half.
 *
 * None of these has been played inside the title. The mappings match the
 * game's command list; the frame counts are provisional until the device
 * checklist in docs/skill-moves.md is filled in. tests/skill_mirror_test.cpp
 * compares COUNT, LEAVES_EXIT, HINTS and ABILITIES here against the runtime's
 * table, so a change on one side that is not made on the other fails ctest.
 */
object SkillMoves {

    /** The number of moves the native table carries (8 original + 7 verified). */
    const val COUNT = 15

    private val NAMES = arrayOf(
        "DOUBLE TOUCH",
        "ROULETTE",
        "RAINBOW FLICK",
        "DRAG BACK",
        "BODY FEINT",
        "STEP OVER",
        "RONALDO CHOP",
        "ELASTICO",
        "INSIDE FEINT",
        "HOCUS POCUS",
        "REVERSE ELASTICO",
        "REVERSE STEP OVER",
        "MULTIPLE STEP OVERS",
        "SOMBRERO",
        "NUTMEG",
    )

    /** Two or three letters, for the ring where a full name will not fit. */
    private val SHORT = arrayOf(
        "DBL", "ROUL", "RBNB", "DRAG", "FEINT", "STEP", "CHOP", "ELAS",
        "INSD", "HOCUS", "R.ELAS", "R.STEP", "M.STEP", "SOMB", "NUTM",
    )

    /**
     * Whether the gesture continues into a direction the player picks. The
     * wheel plays the first half; the left thumb supplies the rest.
     */
    private val LEAVES_EXIT = booleanArrayOf(
        true,   // double touch: then push the stick to the exit
        true,   // roulette
        false,  // rainbow: two R3 clicks, nothing after it
        true,   // drag back: then LS left
        false,  // body feint: the flick is the whole move
        true,   // step over: then LS down-right
        true,   // ronaldo chop
        true,   // elastico
        true,   // inside feint family: LS picks bounce / draw / L-feint
        true,   // hocus pocus
        true,   // reverse elastico
        false,  // reverse step over: a closed quarter circle
        false,  // multiple step overs: a closed half circle
        true,   // sombrero: hold R3 while the LS goes toward the opponent
        true,   // nutmeg: then LS toward the opponent
    )

    /**
     * What the player supplies after (or during) the played half, and the
     * ability the title needs. Mirrors `exitHint`/`ability` in
     * runtime/skill_moves.h, which is authoritative and also prints both to
     * logcat. Keep the two in step: the rainbow and the two closed circles
     * (2, 11, 12) have no exit, and the empty ability means "no gate".
     */
    private val HINTS = arrayOf(
        "then LS right (drag back: left, inside: up, behind: up-left, Neymar: down)",
        "then LS down-right / right / up-right (spin: up or down)",
        "",
        "then LS left",
        "none needed (steer as normal)",
        "then LS down-right",
        "then LS down / down-right / up-right / up",
        "then LS up-right (diagonal take: down-right)",
        "then LS right (bounce / drag close), up (draw close), down (L-feint)",
        "then LS down-right",
        "then LS down-right",
        "",
        "",
        "hold R3 while pushing LS toward the opponent (stationary)",
        "then LS toward the opponent (Riquelme: up-left)",
    )

    private val ABILITIES = arrayOf(
        "", "", "", "", "", "", "",
        "Flip Flap",
        "Scotch Move for the bounce / Scotch",
        "Flip Flap",
        "Flip Flap",
        "", "",
        "Sombrero",
        "",
    )

    fun valid(index: Int): Boolean = index in NAMES.indices

    /** The left-stick half still owed by the player, or "" when there is none. */
    fun hint(index: Int): String = HINTS.getOrNull(index) ?: ""

    /** The ability the title requires, or "" for no gate. */
    fun ability(index: Int): String = ABILITIES.getOrNull(index) ?: ""

    fun name(index: Int): String = NAMES.getOrNull(index) ?: "—"

    fun short(index: Int): String = SHORT.getOrNull(index) ?: ""

    fun leavesExit(index: Int): Boolean = LEAVES_EXIT.getOrNull(index) == true
}
