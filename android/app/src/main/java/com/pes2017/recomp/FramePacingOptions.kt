package com.pes2017.recomp

/**
 * The frame-pacing choices and the two keys they live under.
 *
 * Both Graphics & performance pages build their rows from this object: the
 * launcher's Settings tab ([SettingsActivity]) and the in-match pause menu
 * ([QuickGraphics]). They share [Prefs] storage and these keys, so a change in
 * one page is the value the other page shows the next time it is opened.
 *
 * Pacing is deliberately not part of a quality preset: [DeviceProfile.preset]
 * never reads or writes these keys, and changing one never marks the preset
 * Custom or rewrites a setting the preset owns.
 */
object FramePacingOptions {

    /** Auto: the best pacing this phone supports (the default). */
    const val MODE_AUTO = "auto"

    /** Smooth 30 FPS: display timing when offered, otherwise phase-locked FIFO. */
    const val MODE_SMOOTH = "smooth"

    /** The original presentation behaviour, exactly as before frame pacing. */
    const val MODE_COMPAT = "compatibility"

    val MODES = arrayOf(MODE_AUTO, MODE_SMOOTH, MODE_COMPAT)

    /** Leave the existing refresh-rate settings to choose the panel mode. */
    const val REFRESH_SYSTEM = "system"

    /** Ask for 60 Hz so a 30 FPS match lands on every second refresh. */
    const val REFRESH_PREFER60 = "prefer60"

    val REFRESHES = arrayOf(REFRESH_SYSTEM, REFRESH_PREFER60)

    /** The SharedPreferences key names [Prefs] reads and writes. */
    const val KEY_MODE = "framePacing"
    const val KEY_REFRESH = "framePacingRefresh"

    /** Both saved values at once; neither is a quality-preset setting. */
    data class Values(val mode: String, val refresh: String)

    fun normalizeMode(value: String?): String =
        if (value != null && MODES.contains(value)) value else MODE_AUTO

    fun normalizeRefresh(value: String?): String =
        if (value == REFRESH_PREFER60) REFRESH_PREFER60 else REFRESH_SYSTEM

    /** What a page shows for the two stored strings; blank/older values fall back to Auto/System. */
    fun read(mode: String?, refresh: String?): Values =
        Values(normalizeMode(mode), normalizeRefresh(refresh))

    fun withMode(values: Values, mode: String): Values =
        values.copy(mode = normalizeMode(mode))

    fun withRefresh(values: Values, refresh: String): Values =
        values.copy(refresh = normalizeRefresh(refresh))

    /** One press along the three modes, wrapping both ways. */
    fun stepMode(value: String, delta: Int): String = step(MODES, normalizeMode(value), delta)

    /** One press along the two refresh choices, wrapping both ways. */
    fun stepRefresh(value: String, delta: Int): String =
        step(REFRESHES, normalizeRefresh(value), delta)

    private fun step(values: Array<String>, value: String, delta: Int): String {
        val current = values.indexOf(value).coerceAtLeast(0)
        return values[((current + delta) % values.size + values.size) % values.size]
    }

    fun modeLabel(mode: String): Int = when (normalizeMode(mode)) {
        MODE_SMOOTH -> R.string.pacing_smooth
        MODE_COMPAT -> R.string.pacing_compatibility
        else -> R.string.pacing_auto
    }

    fun refreshLabel(refresh: String): Int =
        if (normalizeRefresh(refresh) == REFRESH_PREFER60) R.string.pacing_refresh_prefer60
        else R.string.pacing_refresh_system

    /**
     * What a 30 FPS match asks the panel for. System default leaves the
     * existing choices above alone: a 30 Hz mode when "match the screen to the
     * frame limit" is on, or the 60 Hz lock. Prefer 60 Hz keeps 30 FPS on every
     * second refresh of a 60 Hz screen, the pattern display-timing pacing
     * schedules on.
     */
    fun requests60For30(refresh: String, matchRefresh: Boolean, prefer60Hz: Boolean): Boolean =
        when (normalizeRefresh(refresh)) {
            REFRESH_PREFER60 -> true
            else -> !matchRefresh && prefer60Hz
        }

    /** `Surface.setFrameRate` arrived in Android 11 (API 30). */
    fun surfaceFrameRateSupported(apiLevel: Int): Boolean = apiLevel >= 30

    /** A panel can be asked for a mode from Android 6 (API 23); the app's floor is 8.0. */
    fun refreshSupported(apiLevel: Int): Boolean = apiLevel >= 23
}
