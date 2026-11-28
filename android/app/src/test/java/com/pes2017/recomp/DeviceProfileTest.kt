package com.pes2017.recomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The graphics presets: the three that existed, and Lowest, the one for the
 * Helio G85 class of phone.
 *
 * The point of these checks is that adding a tier did not change anything an
 * existing phone does: the detected tier, the values each preset writes, and
 * which settings a preset is allowed to touch at all. Nothing here runs on a
 * phone -- the preset table is arithmetic and a table, so it is checked as one.
 */
class DeviceProfileTest {

    private fun preset(tier: DeviceProfile.Tier) = DeviceProfile.preset(tier)

    private fun Specs(gpu: String, memoryMB: Long = 3600, fast: Int = 2, mhz: Int = 2000) =
        DeviceProfile.Specs(gpu, "test SoC", memoryMB, fast, mhz)

    @Test
    fun theTiersAreOrderedWeakestFirst() {
        // classify() takes the minimum of the three answers, so the enum's order is
        // what makes "the weakest wins" true.
        assertTrue(DeviceProfile.Tier.LOWEST < DeviceProfile.Tier.LOW)
        assertTrue(DeviceProfile.Tier.LOW < DeviceProfile.Tier.MEDIUM)
        assertTrue(DeviceProfile.Tier.MEDIUM < DeviceProfile.Tier.HIGH)
    }

    @Test
    fun theThreeExistingPresetsAreUnchanged() {
        val high = preset(DeviceProfile.Tier.HIGH)
        assertEquals(75, high.sceneScalePercent)
        assertTrue(high.multisampling)
        assertFalse(high.fsr)
        assertEquals(60, high.fpsLimit)
        assertFalse(high.lowMemory)
        assertTrue(high.heatScale)
        assertFalse(high.loadScale)
        assertEquals(4, high.maxAniso)

        val medium = preset(DeviceProfile.Tier.MEDIUM)
        assertEquals(75, medium.sceneScalePercent)
        assertTrue(medium.multisampling)
        assertTrue(medium.fsr)
        assertEquals(60, medium.fpsLimit)
        assertFalse(medium.lowMemory)
        assertTrue(medium.heatScale)
        assertFalse(medium.loadScale)
        assertEquals(4, medium.maxAniso)

        val low = preset(DeviceProfile.Tier.LOW)
        assertEquals(60, low.sceneScalePercent)
        assertFalse(low.multisampling)
        assertFalse(low.fsr)
        assertEquals(30, low.fpsLimit)
        assertFalse(low.lowMemory)
        assertTrue(low.heatScale)
        assertTrue(low.loadScale)
        assertEquals(2, low.maxAniso)

        // The three state the crowd -- on, which is the toggle's own default, so a
        // switch away from Lowest puts it back -- and none of them pins the internal
        // multiplier: that stays whatever the player chose.
        for (p in listOf(high, medium, low)) {
            assertTrue(p.crowd)
            assertEquals(0, p.renderScale)
        }
    }

    @Test
    fun lowestIsNoMoreExpensiveThanLowOnEveryLever() {
        val low = preset(DeviceProfile.Tier.LOW)
        val lowest = preset(DeviceProfile.Tier.LOWEST)
        assertTrue(lowest.sceneScalePercent <= low.sceneScalePercent)
        assertTrue(lowest.maxAniso <= low.maxAniso)
        assertTrue(lowest.fpsLimit <= low.fpsLimit)
        // Booleans that cost GPU work: off is cheaper, and Lowest must not turn on
        // what Low has off.
        assertFalse(lowest.multisampling)
        assertFalse(lowest.fsr)
        // The rules that give ground under heat and under a load spike stay on: on a
        // G52 they are what keeps the frame time steady rather than the picture small.
        assertTrue(lowest.heatScale)
        assertTrue(lowest.loadScale)
        // Shadows off (the vertex shadow buffer), crowd off, and the smallest
        // internal multiplier: the three levers Low leaves alone.
        assertTrue(lowest.lowMemory)
        assertFalse(lowest.crowd)
        assertEquals(1, lowest.renderScale)
    }

    @Test
    fun everyPresetValueIsOneThePageOffers() {
        // A preset writes values the pages' own steppers must land on: a preset value
        // that is not an option would show as the next option up and apply as itself.
        for (tier in DeviceProfile.Tier.values()) {
            val p = preset(tier)
            assertTrue("${tier} scene ${p.sceneScalePercent}",
                GraphicsOptions.SCENE_PERCENT.contains(p.sceneScalePercent))
            assertTrue("${tier} aniso ${p.maxAniso}", GraphicsOptions.MAX_ANISO.contains(p.maxAniso))
            assertTrue("${tier} fps ${p.fpsLimit}", GraphicsOptions.FPS_LIMIT.contains(p.fpsLimit))
            assertTrue("${tier} scale ${p.renderScale}",
                p.renderScale == 0 || GraphicsOptions.INTERNAL_SCALE.contains(p.renderScale))
        }
    }

    @Test
    fun lowestUsesTheExisting30FpsLimitAndPacingUnchanged() {
        val lowest = preset(DeviceProfile.Tier.LOWEST)
        // 30 is the first entry of the limit the pacer already handles, and the
        // lowest rate the pacing plans were measured and written for (pacing.h:
        // 30 presents in order, every second boundary).
        assertEquals(30, lowest.fpsLimit)
        assertEquals(GraphicsOptions.FPS_LIMIT.first(), lowest.fpsLimit)
        // A preset never touches the pacing keys (FramePacingOptions), so nothing
        // here forces Smooth or Compatibility: the player's pacing choice stands.
        assertFalse(FramePacingOptions.MODES.isEmpty())
    }

    @Test
    fun lowestKeepsASceneBigEnoughToStayReadable() {
        // The floor of the scene slider, not below it: the picture is the console's
        // frame at half scale, which is what the page already offers and the smallest
        // size the renderer has been exercised at.
        assertEquals(GraphicsOptions.SCENE_PERCENT.first(), preset(DeviceProfile.Tier.LOWEST).sceneScalePercent)
        assertEquals(GraphicsOptions.INTERNAL_SCALE.first(), preset(DeviceProfile.Tier.LOWEST).renderScale)
        assertEquals(GraphicsOptions.MAX_ANISO.first(), preset(DeviceProfile.Tier.LOWEST).maxAniso)
    }

    @Test
    fun theHelioG85StillDetectsLowUnchanged() {
        // Lowest is chosen by the player, never detected: a G85 (Mali-G52 MC2, 2 fast
        // cores, ~3.6 GB visible) classified as Low before this tier existed and still
        // does, so no phone is moved onto a new preset by installing an update.
        val g85 = Specs(gpu = "Mali-G52 MC2")
        assertEquals(DeviceProfile.Tier.LOW, DeviceProfile.classify(g85).tier)
        // And a phone that already sat below it -- 3 GB, 1.8 GHz -- is still Low.
        val small = Specs(gpu = "Mali-G52 MC2", memoryMB = 2800, fast = 1, mhz = 1800)
        assertEquals(DeviceProfile.Tier.LOW, DeviceProfile.classify(small).tier)
    }

    @Test
    fun noPhoneIsEverDetectedAsLowest() {
        val gpus = listOf("Mali-G52 MC2", "Mali-G57 MC2", "Adreno (TM) 610", "PowerVR Rogue GE8320", "", "Mali-G710 MC10")
        for (gpu in gpus) {
            for (memory in listOf(2000L, 2800L, 3600L, 5500L, 8000L)) {
                for (fast in listOf(1, 2, 4, 8)) {
                    val tier = DeviceProfile.classify(Specs(gpu, memory, fast, 1800)).tier
                    assertTrue("$gpu / ${memory}MB / $fast fast cores detected as $tier",
                        tier != DeviceProfile.Tier.LOWEST)
                }
            }
        }
    }

    @Test
    fun lowestIsNotTiedToAMaliGpu() {
        // The preset is a table of settings, not a vendor rule: nothing in it reads a
        // GPU name, so the same values apply on an Adreno 610 or a PowerVR. This
        // checks the table is vendor-free by asking for it with no GPU involved at all.
        val p = preset(DeviceProfile.Tier.LOWEST)
        assertEquals(DeviceProfile.preset(DeviceProfile.Tier.LOWEST), p)
        // And a weak Adreno still classifies where it did, so a player there can pick
        // Lowest with the same meaning it has on Mali.
        val adreno = Specs(gpu = "Adreno (TM) 610", memoryMB = 3000)
        assertEquals(DeviceProfile.Tier.LOW, DeviceProfile.classify(adreno).tier)
    }

    /**
     * The Snapdragon path, checked before anything low-end is allowed in: a high-end
     * Adreno is detected High and gets the High preset, whose values are the ones the
     * port was tuned on. Nothing Lowest introduced may reach it.
     */
    @Test
    fun aHighEndSnapdragonKeepsTheHighPreset() {
        // 8 Gen 2 / 8 Gen 3 class: Adreno 740, four fast cores, 12 GB.
        for (gpu in listOf("Adreno (TM) 740", "Adreno (TM) 750", "Adreno (TM) 650")) {
            val result = DeviceProfile.classify(Specs(gpu, memoryMB = 11_000, fast = 4, mhz = 3200))
            assertEquals("$gpu", DeviceProfile.Tier.HIGH, result.tier)
            assertEquals("$gpu", "GPU", result.reason)
        }
        val high = preset(DeviceProfile.Tier.HIGH)
        assertEquals(75, high.sceneScalePercent)
        assertTrue(high.multisampling)
        assertFalse(high.fsr)
        assertEquals(60, high.fpsLimit)
        assertFalse(high.lowMemory)
        assertTrue(high.heatScale)
        assertFalse(high.loadScale)
        assertEquals(4, high.maxAniso)
        // The two levers Lowest pulls are not pulled here: crowd on, multiplier unpinned.
        assertTrue(high.crowd)
        assertEquals(0, high.renderScale)
    }

    @Test
    fun everyAdrenoModelKeepsTheTierItHadBeforeLowest() {
        // The Adreno column of the GPU table, model by model: a low-end change must not
        // move a Snapdragon up or down. These are the values in gpuTier, restated so a
        // change to either shows up here.
        val expected = mapOf(
            "Adreno (TM) 610" to DeviceProfile.Tier.LOW,    // 680, 695, 750G
            "Adreno (TM) 619" to DeviceProfile.Tier.LOW,
            "Adreno (TM) 640" to DeviceProfile.Tier.MEDIUM, // 855
            "Adreno (TM) 642L" to DeviceProfile.Tier.MEDIUM,
            "Adreno (TM) 644" to DeviceProfile.Tier.MEDIUM,
            "Adreno (TM) 650" to DeviceProfile.Tier.HIGH,   // 865
            "Adreno (TM) 660" to DeviceProfile.Tier.HIGH,   // 888
            "Adreno (TM) 710" to DeviceProfile.Tier.MEDIUM,
            "Adreno (TM) 725" to DeviceProfile.Tier.MEDIUM,
            "Adreno (TM) 730" to DeviceProfile.Tier.HIGH,
            "Adreno (TM) 740" to DeviceProfile.Tier.HIGH,
            "Adreno (TM) 750" to DeviceProfile.Tier.HIGH,
        )
        for ((gpu, tier) in expected) {
            // Only the GPU decides here: memory and CPU are generous, so the answer is
            // the table's own.
            val result = DeviceProfile.classify(Specs(gpu, memoryMB = 11_000, fast = 4, mhz = 3200))
            assertEquals(gpu, tier, result.tier)
            assertTrue(gpu, result.reason == "GPU")
        }
    }

    @Test
    fun onlyLowestTurnsTheCrowdOff() {
        // Turning the spectators off is something only the player's choice of Lowest
        // does. Every other preset -- which is every device's default -- leaves them on
        // exactly as the toggle's own default does, so no Adreno phone loses them.
        for (tier in DeviceProfile.Tier.values()) {
            assertEquals("$tier", tier != DeviceProfile.Tier.LOWEST, preset(tier).crowd)
        }
    }

    @Test
    fun anUnknownGpuKeepsTheDefaultTier() {
        // A GPU the table has never seen is not assumed cheap: it gets the Medium
        // guess, the same as before Lowest existed, and the Settings line says why.
        val result = DeviceProfile.classify(Specs("Some New GPU", memoryMB = 11_000, fast = 4, mhz = 3200))
        assertEquals(DeviceProfile.Tier.MEDIUM, result.tier)
        assertEquals("GPU not in the table", result.reason)
        // Never the Lowest preset, and never its values.
        assertTrue(result.tier != DeviceProfile.Tier.LOWEST)
        assertTrue(preset(result.tier).crowd)
        assertEquals(0, preset(result.tier).renderScale)
    }

    /**
     * The switch this was reported for: a player on 2x picks Lowest, then goes back to
     * Medium. The crowd must come back and the multiplier must be 2x again, not the 1x
     * Lowest pinned.
     */
    @Test
    fun switchingFromLowestToMediumPutsTheCrowdBackAndRestoresTheMultiplier() {
        val lowest = preset(DeviceProfile.Tier.LOWEST)
        val medium = preset(DeviceProfile.Tier.MEDIUM)

        val pinned = DeviceProfile.scaleShift(lowest, currentScale = 2, pinnedBefore = 0)
        assertEquals(1, pinned.scale)
        assertEquals(2, pinned.pinnedBefore)
        assertFalse(lowest.crowd)

        assertTrue(medium.crowd)
        val restored = DeviceProfile.scaleShift(medium, pinned.scale, pinned.pinnedBefore)
        assertEquals(2, restored.scale)
        assertEquals(0, restored.pinnedBefore)

        // High does the same, and both put the crowd back.
        assertTrue(preset(DeviceProfile.Tier.HIGH).crowd)
        assertEquals(2, DeviceProfile.scaleShift(preset(DeviceProfile.Tier.HIGH), 1, 2).scale)
    }


    @Test
    fun aPresetThatDoesNotPinLeavesThePlayersMultiplierAlone() {
        // Medium chosen with no pin behind it: a chosen 3x stays 3x, and so does 1x.
        assertEquals(DeviceProfile.ScaleShift(3, 0), DeviceProfile.scaleShift(preset(DeviceProfile.Tier.MEDIUM), 3, 0))
        assertEquals(DeviceProfile.ScaleShift(1, 0), DeviceProfile.scaleShift(preset(DeviceProfile.Tier.LOW), 1, 0))
        assertEquals(DeviceProfile.ScaleShift(2, 0), DeviceProfile.scaleShift(preset(DeviceProfile.Tier.HIGH), 2, 0))
    }

    @Test
    fun choosingLowestTwiceRemembersTheFirstValueItOverrode() {
        // Pressing Lowest again must not remember its own 1x as the value to restore.
        val first = DeviceProfile.scaleShift(preset(DeviceProfile.Tier.LOWEST), 2, 0)
        val again = DeviceProfile.scaleShift(preset(DeviceProfile.Tier.LOWEST), first.scale, first.pinnedBefore)
        assertEquals(1, again.scale)
        assertEquals(2, again.pinnedBefore)
    }

    @Test
    fun lowestWithNoStoredChoiceLeavesNothingToRestore() {
        // A fresh install is already at 1x: the pin remembers nothing, and the next
        // preset keeps 1x rather than inventing a value.
        val pinned = DeviceProfile.scaleShift(preset(DeviceProfile.Tier.LOWEST), 1, 0)
        assertEquals(1, pinned.scale)
        assertEquals(0, pinned.pinnedBefore)
        assertEquals(1, DeviceProfile.scaleShift(preset(DeviceProfile.Tier.MEDIUM), pinned.scale, pinned.pinnedBefore).scale)
    }

    @Test
    fun everyRestoredMultiplierIsAnOptionThePageOffers() {
        for (tier in DeviceProfile.Tier.values()) {
            for (current in GraphicsOptions.INTERNAL_SCALE) {
                for (before in 0 until 4) {
                    val shift = DeviceProfile.scaleShift(preset(tier), current, before)
                    assertTrue("$tier from $current with $before -> ${shift.scale}",
                        GraphicsOptions.INTERNAL_SCALE.contains(shift.scale))
                    assertTrue(shift.pinnedBefore == 0 || GraphicsOptions.INTERNAL_SCALE.contains(shift.pinnedBefore))
                }
            }
        }
    }
}
