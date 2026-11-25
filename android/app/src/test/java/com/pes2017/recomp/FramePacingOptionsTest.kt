package com.pes2017.recomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frame pacing as both Graphics & performance pages use it: one set of option
 * lists, one pair of storage keys, and no path from a pacing change to the
 * quality preset.
 *
 * The storage here is a map standing in for SharedPreferences (which needs an
 * Android runtime). It is read and written through the same
 * [FramePacingOptions.read]/[FramePacingOptions.withMode] code [Prefs] and the
 * two pages use, so "one menu sees the other's change" is checked without a
 * device.
 */
class FramePacingOptionsTest {

    @Test
    fun modesAreAutoSmoothAndCompatibility() {
        assertEquals(
            listOf(
                FramePacingOptions.MODE_AUTO,
                FramePacingOptions.MODE_SMOOTH,
                FramePacingOptions.MODE_COMPAT,
            ),
            FramePacingOptions.MODES.toList(),
        )
        assertEquals(
            listOf(FramePacingOptions.REFRESH_SYSTEM, FramePacingOptions.REFRESH_PREFER60),
            FramePacingOptions.REFRESHES.toList(),
        )
    }

    @Test
    fun blankAndUnknownValuesFallBackToTheDefaults() {
        assertEquals(FramePacingOptions.MODE_AUTO, FramePacingOptions.normalizeMode(null))
        assertEquals(FramePacingOptions.MODE_AUTO, FramePacingOptions.normalizeMode(""))
        assertEquals(FramePacingOptions.MODE_AUTO, FramePacingOptions.normalizeMode("manual"))
        assertEquals(FramePacingOptions.MODE_SMOOTH, FramePacingOptions.normalizeMode("smooth"))
        assertEquals(FramePacingOptions.MODE_COMPAT, FramePacingOptions.normalizeMode("compatibility"))
        assertEquals(FramePacingOptions.REFRESH_SYSTEM, FramePacingOptions.normalizeRefresh(""))
        assertEquals(FramePacingOptions.REFRESH_SYSTEM, FramePacingOptions.normalizeRefresh("59"))
        assertEquals(FramePacingOptions.REFRESH_PREFER60, FramePacingOptions.normalizeRefresh("prefer60"))
    }

    @Test
    fun eachPressMovesOneModeAndAFullTurnReturns() {
        var mode = FramePacingOptions.MODE_AUTO
        mode = FramePacingOptions.stepMode(mode, 1)
        assertEquals(FramePacingOptions.MODE_SMOOTH, mode)
        mode = FramePacingOptions.stepMode(mode, 1)
        assertEquals(FramePacingOptions.MODE_COMPAT, mode)
        mode = FramePacingOptions.stepMode(mode, 1)
        assertEquals(FramePacingOptions.MODE_AUTO, mode)
        // And backwards.
        assertEquals(FramePacingOptions.MODE_COMPAT, FramePacingOptions.stepMode(mode, -1))
        assertEquals(FramePacingOptions.MODE_SMOOTH, FramePacingOptions.stepMode(FramePacingOptions.MODE_COMPAT, -1))
        // A full turn from every start is the start again.
        for (start in FramePacingOptions.MODES) {
            var value = start
            repeat(FramePacingOptions.MODES.size) { value = FramePacingOptions.stepMode(value, 1) }
            assertEquals(start, value)
        }
    }

    @Test
    fun eachPressMovesOneRefreshChoiceAndAFullTurnReturns() {
        var refresh = FramePacingOptions.REFRESH_SYSTEM
        refresh = FramePacingOptions.stepRefresh(refresh, 1)
        assertEquals(FramePacingOptions.REFRESH_PREFER60, refresh)
        refresh = FramePacingOptions.stepRefresh(refresh, 1)
        assertEquals(FramePacingOptions.REFRESH_SYSTEM, refresh)
        assertEquals(FramePacingOptions.REFRESH_PREFER60, FramePacingOptions.stepRefresh(refresh, -1))
    }

    @Test
    fun bothMenusReadAndWriteTheSameKeys() {
        // The shared map is what Prefs persists.
        val store = HashMap<String, String>()
        store["performancePreset"] = DeviceProfile.Tier.HIGH.name
        store["sceneScale"] = "75"

        // Main menu writes Smooth and Prefer 60.
        val mainMenu = FramePacingOptions.read(
            store[FramePacingOptions.KEY_MODE],
            store[FramePacingOptions.KEY_REFRESH],
        )
        val written = FramePacingOptions.withRefresh(
            FramePacingOptions.withMode(mainMenu, FramePacingOptions.MODE_SMOOTH),
            FramePacingOptions.REFRESH_PREFER60,
        )
        store[FramePacingOptions.KEY_MODE] = written.mode
        store[FramePacingOptions.KEY_REFRESH] = written.refresh

        // In-game opens later and sees exactly those two values.
        val inGame = FramePacingOptions.read(
            store[FramePacingOptions.KEY_MODE],
            store[FramePacingOptions.KEY_REFRESH],
        )
        assertEquals(FramePacingOptions.MODE_SMOOTH, inGame.mode)
        assertEquals(FramePacingOptions.REFRESH_PREFER60, inGame.refresh)

        // And the in-game page's change is what the main menu reads next.
        val changed = FramePacingOptions.withMode(inGame, FramePacingOptions.MODE_COMPAT)
        store[FramePacingOptions.KEY_MODE] = changed.mode
        store[FramePacingOptions.KEY_REFRESH] = changed.refresh
        val mainMenuAgain = FramePacingOptions.read(
            store[FramePacingOptions.KEY_MODE],
            store[FramePacingOptions.KEY_REFRESH],
        )
        assertEquals(FramePacingOptions.MODE_COMPAT, mainMenuAgain.mode)
        assertEquals(FramePacingOptions.REFRESH_PREFER60, mainMenuAgain.refresh)
    }

    @Test
    fun aPacingChangeLeavesThePresetAndItsSettingsAlone() {
        val store = HashMap<String, String>()
        store["performancePreset"] = DeviceProfile.Tier.MEDIUM.name
        store["sceneScale"] = "75"
        store["msaa"] = "true"
        store["fpsLimit"] = "60"

        val before = store.toMap()
        // Every combination through the shared codec writes only the two keys.
        for (mode in FramePacingOptions.MODES) {
            for (refresh in FramePacingOptions.REFRESHES) {
                val values = FramePacingOptions.read(store[FramePacingOptions.KEY_MODE],
                    store[FramePacingOptions.KEY_REFRESH])
                val next = FramePacingOptions.withRefresh(FramePacingOptions.withMode(values, mode), refresh)
                store[FramePacingOptions.KEY_MODE] = next.mode
                store[FramePacingOptions.KEY_REFRESH] = next.refresh
            }
        }
        assertEquals(DeviceProfile.Tier.MEDIUM.name, store["performancePreset"])
        assertEquals("75", store["sceneScale"])
        assertEquals("true", store["msaa"])
        assertEquals("60", store["fpsLimit"])
        // Nothing outside the pacing pair changed value.
        for ((key, value) in before) assertEquals(value, store[key])
        assertFalse(store.containsKey("CUSTOM"))
    }

    @Test
    fun compatibilityAndSmoothSwitchBackAndForth() {
        var values = FramePacingOptions.read(null, null)
        assertEquals(FramePacingOptions.MODE_AUTO, values.mode)
        values = FramePacingOptions.withMode(values, FramePacingOptions.MODE_COMPAT)
        assertEquals(FramePacingOptions.MODE_COMPAT, values.mode)
        values = FramePacingOptions.withMode(values, FramePacingOptions.MODE_SMOOTH)
        assertEquals(FramePacingOptions.MODE_SMOOTH, values.mode)
        // The refresh choice survives either switch.
        values = FramePacingOptions.withRefresh(values, FramePacingOptions.REFRESH_PREFER60)
        values = FramePacingOptions.withMode(values, FramePacingOptions.MODE_COMPAT)
        assertEquals(FramePacingOptions.REFRESH_PREFER60, values.refresh)
    }

    @Test
    fun prefer60BeatsTheMatchRefreshChoiceFor30FpsOnly() {
        assertTrue(
            FramePacingOptions.requests60For30(
                FramePacingOptions.REFRESH_PREFER60, matchRefresh = true, prefer60Hz = false,
            ),
        )
        assertTrue(
            FramePacingOptions.requests60For30(
                FramePacingOptions.REFRESH_PREFER60, matchRefresh = true, prefer60Hz = true,
            ),
        )
        // System default preserves the existing behaviour:
        assertFalse(
            FramePacingOptions.requests60For30(
                FramePacingOptions.REFRESH_SYSTEM, matchRefresh = true, prefer60Hz = true,
            ),
        )
        assertTrue(
            FramePacingOptions.requests60For30(
                FramePacingOptions.REFRESH_SYSTEM, matchRefresh = false, prefer60Hz = true,
            ),
        )
        assertFalse(
            FramePacingOptions.requests60For30(
                FramePacingOptions.REFRESH_SYSTEM, matchRefresh = false, prefer60Hz = false,
            ),
        )
    }

    @Test
    fun unsupportedApisAreReportedNotPretended() {
        assertFalse(FramePacingOptions.surfaceFrameRateSupported(29))
        assertTrue(FramePacingOptions.surfaceFrameRateSupported(30))
        // The app's minSdk is 26, so the refresh preference is always supported
        // in practice; the helpers still name the floor rather than assume it.
        assertFalse(FramePacingOptions.refreshSupported(22))
        assertTrue(FramePacingOptions.refreshSupported(23))
    }
}
