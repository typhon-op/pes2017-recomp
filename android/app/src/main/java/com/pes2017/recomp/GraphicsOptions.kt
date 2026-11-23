package com.pes2017.recomp

/**
 * The options the Graphics & performance page offers, and the arithmetic that
 * walks them. Build 24 had these three lists; the page's controls step through
 * them one option a press and wrap from the last back to the first, so 3x reads
 * 1x next, 100% reads 50% next and 60 FPS reads 30 FPS next.
 *
 * Kept apart from the screens so the same lists and the same wraparound are used
 * by the in-match page (QuickGraphics), the launcher's Settings tab
 * (SettingsActivity) and the saved values (Prefs) -- there is only one answer to
 * "what comes after 60 FPS".
 */
object GraphicsOptions {

    /** Internal resolution, PES17_SCALE: the console's frame, twice it, three times it. */
    val INTERNAL_SCALE = intArrayOf(1, 2, 3)

    /** Scene resolution, PES17_SCENE_SCALE: the four sizes Build 24 offered. */
    val SCENE_PERCENT = intArrayOf(50, 60, 75, 100)

    /**
     * Frame rate limit, PES17_FPS_LIMIT: 30 to 60 in Build 24's own steps of 5.
     * Every intermediate value stays: 30, 35, 40, 45, 50, 55, 60.
     */
    val FPS_LIMIT = intArrayOf(30, 35, 40, 45, 50, 55, 60)

    /**
     * Anisotropic filtering cap, PES17_MAX_ANISO: how many texture samples the
     * title's own request may become. Android already caps it at 4x, so the steps
     * above 4 only undo that on a phone with room to spare; 2 and 1 are for a GPU
     * that is filling the frame with the pitch, where the far end is what is being
     * drawn (docs/performance.md).
     */
    val MAX_ANISO = intArrayOf(1, 2, 4, 8, 16)

    /**
     * [value] as one of [options]. A saved value that is not on the list (an older
     * build's 55% scene, or a hand-written number) becomes the next option up, so
     * what the page shows is one of the four or seven choices and is exactly what
     * is handed to the renderer.
     */
    fun normalize(options: IntArray, value: Int): Int =
        options.firstOrNull { it >= value } ?: options.last()

    /**
     * One press further along [options], wrapping at both ends: a step of 1 from
     * the last option is the first, and a step of -1 from the first is the last.
     * The index of the current value is found through [normalize], so a stored
     * value that is not on the list still moves by exactly one option.
     */
    fun step(options: IntArray, value: Int, delta: Int = 1): Int {
        val current = options.indexOf(normalize(options, value))
        val size = options.size
        return options[((current + delta) % size + size) % size]
    }
}
