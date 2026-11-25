package com.pes2017.recomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The left stick's feel (LeftStickFeel).
 *
 * The most important checks are the Default ones: the profile must be the
 * shipped stick -- no host deadzone, no curve, no smoothing, the thumb's throw
 * as the deflection -- and the shipped sprint latch (92/82) must survive.
 * The rest pin the presets' exact numbers, the ranges, the curves' shape and
 * the smoothing's promise that pace is never touched.
 */
class LeftStickFeelTest {

    private fun near(a: Float, b: Float, tolerance: Float = 1e-4f) = kotlin.math.abs(a - b) <= tolerance

    // ---- Default is the shipped stick -----------------------------------------

    @Test
    fun defaultIsTheShippedStick() {
        val s = LeftStickFeel.preset(LeftStickFeel.PRESET_DEFAULT)
        assertEquals(0f, s.deadzone, 0f)
        assertEquals(1f, s.sensitivity, 0f)
        assertEquals(LeftStickFeel.CURVE_LINEAR, s.curve)
        assertEquals(LeftStickFeel.SMOOTHING_OFF, s.smoothing)
        assertEquals(0.92f, s.sprint, 0f)
        assertEquals(0.92f, LeftStickFeel.SHIPPED_SPRINT, 0f)
        assertEquals(0.82f, LeftStickFeel.sprintRelease(s), 0f)
    }

    @Test
    fun defaultShapingIsTheThumb() {
        val s = LeftStickFeel.preset(LeftStickFeel.PRESET_DEFAULT)
        for ((x, y) in listOf(0.33f to -0.51f, 0.05f to 0.05f, -0.9f to 0.2f)) {
            val out = LeftStickFeel.shape(x, y, s)
            assertEquals(x, out[0], 1e-5f)
            assertEquals(y, out[1], 1e-5f)
        }
        val rim = LeftStickFeel.shape(0.8f, 0.8f, s)       // past the rim
        assertTrue(hypot(rim[0], rim[1]) <= 1.0001f)
        assertTrue(near(atan2(rim[1], rim[0]), atan2(0.8f, 0.8f)))
    }

    @Test
    fun defaultHasNoSmoothingEvenOnAReversal() {
        val s = LeftStickFeel.preset(LeftStickFeel.PRESET_DEFAULT)
        val out = LeftStickFeel.smooth(1f, 0f, -1f, 0f, s.smoothing)
        assertEquals(-1f, out[0], 0f)
        assertEquals(0f, out[1], 0f)
    }

    // ---- the golden sweep: Default is byte-for-byte the shipped path ----------

    /**
     * The shipped arithmetic, exactly as `TouchOverlayView` called it before
     * this screen existed: the stored defaults (no host deadzone, no curve) and
     * the title's own floor. This is the oracle the Default profile must match.
     */
    private fun shipped(x: Float, y: Float): FloatArray = StickShape.apply(
        x, y, Prefs.DEFAULT_STICK_DEADZONE, Prefs.DEFAULT_STICK_CURVE, StickShape.GUEST_FLOOR,
    )

    /**
     * Every point of the grid, plus the awkward ones: the centre, the rim, a
     * throw past the rim, and two sub-pixel wobbles the shipped path discarded.
     */
    private val GOLDEN_POINTS: List<Pair<Float, Float>> = buildList {
        for (i in -24..24) for (j in -24..24) add(i * 0.05f to j * 0.05f)
        add(0f to 0f)
        add(1f to 0f)
        add(-1f to 0f)
        add(0f to 1f)
        add(0f to -1f)
        add(1.2f to 1.2f)     // past the rim: the throw is clamped, the angle kept
        add(-1.5f to 0.3f)
        add(1e-7f to 0f)      // nothing at all
        add(0.001f to 0f)     // sub-pixel wobble, discarded by the shipped path
        add(0.0009f to -0.0009f)
        add(0.0019f to 0f)
    }

    /**
     * The proof that Default *is* the shipped stick, without a device.
     *
     * The whole grid goes through both paths -- the old `StickShape.apply` call
     * and the Default profile's `shape` plus the (identity) smoothing the send
     * path now always runs -- and every component must be equal to the last
     * bit, not merely close. A tolerance here would hide the one thing this
     * profile promises: that choosing Default changes no frame.
     */
    @Test
    fun defaultIsBitIdenticalToTheShippedShapeOnAGoldenGrid() {
        val s = LeftStickFeel.preset(LeftStickFeel.PRESET_DEFAULT)
        assertEquals(0f, s.deadzone, 0f)
        assertEquals(1f, s.sensitivity, 0f)
        assertEquals(Prefs.DEFAULT_STICK_DEADZONE, s.deadzone, 0f)
        assertEquals(Prefs.DEFAULT_STICK_CURVE, LeftStickFeel.exponent(s.curve), 0f)

        val smoother = LeftStickFeel.Smoother()
        var compared = 0
        for ((x, y) in GOLDEN_POINTS) {
            val golden = shipped(x, y)
            val shaped = LeftStickFeel.shape(x, y, s)
            assertEquals("shape x at ($x, $y)", golden[0], shaped[0], 0f)
            assertEquals("shape y at ($x, $y)", golden[1], shaped[1], 0f)

            val sent = smoother.apply(shaped[0], shaped[1], s.smoothing)
            assertEquals("sent x at ($x, $y)", golden[0], sent[0], 0f)
            assertEquals("sent y at ($x, $y)", golden[1], sent[1], 0f)
            compared++
        }
        assertTrue("the sweep covered a real grid, not a handful of points", compared >= 2400)
    }

    @Test
    fun theGoldenGridStillFindsTheOldSubPixelCut() {
        val s = LeftStickFeel.preset(LeftStickFeel.PRESET_DEFAULT)
        // 0.001 is a nudge the shipped path never sent, and Default must not
        // start sending it just because a profile now sits in front.
        assertEquals(0f, shipped(0.001f, 0f)[0], 0f)
        assertEquals(0f, LeftStickFeel.shape(0.001f, 0f, s)[0], 0f)
        // Past the cut both paths send the thumb's own throw.
        assertEquals(shipped(0.01f, 0f)[0], LeftStickFeel.shape(0.01f, 0f, s)[0], 0f)
    }

    // ---- the presets' exact values --------------------------------------------

    @Test
    fun balancedIsTenOneHundredLinearOffEightyTwo() {
        val s = LeftStickFeel.preset(LeftStickFeel.PRESET_BALANCED)
        assertEquals(0.10f, s.deadzone, 0f)
        assertEquals(1.00f, s.sensitivity, 0f)
        assertEquals(LeftStickFeel.CURVE_LINEAR, s.curve)
        assertEquals(LeftStickFeel.SMOOTHING_OFF, s.smoothing)
        assertEquals(0.82f, s.sprint, 0f)
        assertEquals(0.78f, LeftStickFeel.sprintRelease(s), 1e-4f)
    }

    @Test
    fun tightDribbleIsTwelveNinetySoftLowEightyEight() {
        val s = LeftStickFeel.preset(LeftStickFeel.PRESET_TIGHT)
        assertEquals(0.12f, s.deadzone, 0f)
        assertEquals(0.90f, s.sensitivity, 0f)
        assertEquals(LeftStickFeel.CURVE_SOFT, s.curve)
        assertEquals(LeftStickFeel.SMOOTHING_LOW, s.smoothing)
        assertEquals(0.88f, s.sprint, 0f)
        assertEquals(0.84f, LeftStickFeel.sprintRelease(s), 1e-4f)
    }

    @Test
    fun quickSprintIsEightOneTwentySnappyOffSeventyFive() {
        val s = LeftStickFeel.preset(LeftStickFeel.PRESET_QUICK)
        assertEquals(0.08f, s.deadzone, 0f)
        assertEquals(1.20f, s.sensitivity, 0f)
        assertEquals(LeftStickFeel.CURVE_SNAPPY, s.curve)
        assertEquals(LeftStickFeel.SMOOTHING_OFF, s.smoothing)
        assertEquals(0.75f, s.sprint, 0f)
        assertEquals(0.71f, LeftStickFeel.sprintRelease(s), 1e-4f)
    }

    // ---- ranges ----------------------------------------------------------------

    @Test
    fun everyLoadedValueIsPulledIntoItsRange() {
        val wild = LeftStickFeel.sanitize(
            LeftStickFeel.Settings("nonsense", 0.9f, 2f, "curvy", "smoothest", 0.5f),
        )
        assertEquals(LeftStickFeel.PRESET_CUSTOM, wild.preset)
        assertEquals(LeftStickFeel.DEADZONE_MAX, wild.deadzone, 0f)
        assertEquals(LeftStickFeel.SENSITIVITY_MAX, wild.sensitivity, 0f)
        assertEquals(LeftStickFeel.CURVE_LINEAR, wild.curve)
        assertEquals(LeftStickFeel.SMOOTHING_OFF, wild.smoothing)
        assertEquals(LeftStickFeel.SPRINT_MIN, wild.sprint, 0f)

        val low = LeftStickFeel.sanitize(
            LeftStickFeel.Settings("custom", -1f, 0.1f, "soft", "low", 0.99f),
        )
        assertEquals(0f, low.deadzone, 0f)
        assertEquals(LeftStickFeel.SENSITIVITY_MIN, low.sensitivity, 0f)
        assertEquals(LeftStickFeel.SPRINT_MAX, low.sprint, 0f)
    }

    @Test
    fun aCustomProfileGetsTheFourPointLatchEvenAtNinetyTwo() {
        val custom = LeftStickFeel.Settings(
            LeftStickFeel.PRESET_CUSTOM, 0f, 1f, LeftStickFeel.CURVE_LINEAR,
            LeftStickFeel.SMOOTHING_OFF, 0.92f,
        )
        assertEquals(0.88f, LeftStickFeel.sprintRelease(custom), 1e-4f)
    }

    // ---- the deadzone and the curve -------------------------------------------

    @Test
    fun theDeadzoneIsSilentAndTheTravelBeyondItIsRescaled() {
        val s = LeftStickFeel.preset(LeftStickFeel.PRESET_BALANCED)   // 10%
        val inside = LeftStickFeel.shape(0.05f, 0.05f, s)             // ~7%
        assertEquals(0f, inside[0], 0f)
        assertEquals(0f, inside[1], 0f)

        val edge = LeftStickFeel.shape(0.101f, 0f, s)
        assertTrue("just past the deadzone the stick starts at the title's floor",
            edge[0] >= StickShape.GUEST_FLOOR - 1e-4f)
        val rim = LeftStickFeel.shape(1f, 0f, s)
        assertTrue(near(rim[0], 1f, 2e-3f))
    }

    @Test
    fun theDeadzoneEdgeIsMonotonic() {
        val s = LeftStickFeel.preset(LeftStickFeel.PRESET_TIGHT)
        var last = -1f
        var x = 0f
        while (x <= 1f) {
            val out = LeftStickFeel.shape(x, 0f, s)
            assertTrue("no sudden drop at $x", out[0] >= last - 1e-5f)
            last = out[0]
            x += 0.005f
        }
    }

    @Test
    fun theCurvesOrderAndEndpoints() {
        val soft = LeftStickFeel.Settings(
            LeftStickFeel.PRESET_CUSTOM, 0f, 1f, LeftStickFeel.CURVE_SOFT,
            LeftStickFeel.SMOOTHING_OFF, 0.92f,
        )
        val linear = soft.copy(curve = LeftStickFeel.CURVE_LINEAR)
        val snappy = soft.copy(curve = LeftStickFeel.CURVE_SNAPPY)
        val mid = 0.5f
        val softOut = LeftStickFeel.shape(mid, 0f, soft)[0]
        val linearOut = LeftStickFeel.shape(mid, 0f, linear)[0]
        val snappyOut = LeftStickFeel.shape(mid, 0f, snappy)[0]
        assertTrue("soft is gentler than linear", softOut < linearOut)
        assertTrue("snappy answers sooner than linear", snappyOut > linearOut)
        for (s in listOf(soft, linear, snappy)) {
            val top = LeftStickFeel.shape(1f, 0f, s)
            assertTrue("every curve reaches the rim", near(top[0], 1f, 2e-3f))
        }
    }

    @Test
    fun sensitivityReachesFullPaceSooner() {
        val quick = LeftStickFeel.Settings(
            LeftStickFeel.PRESET_CUSTOM, 0f, 1.4f, LeftStickFeel.CURVE_LINEAR,
            LeftStickFeel.SMOOTHING_OFF, 0.92f,
        )
        assertEquals(1f, LeftStickFeel.shape(0.72f, 0f, quick)[0], 1e-4f)
        val lazy = quick.copy(sensitivity = 0.7f)
        assertEquals(0.7f, LeftStickFeel.shape(1f, 0f, lazy)[0], 1e-4f)
    }

    // ---- smoothing -------------------------------------------------------------

    @Test
    fun smoothingKeepsThePaceAndOnlyDampsSharpTurns() {
        val low = LeftStickFeel.SMOOTHING_LOW
        val steady = LeftStickFeel.smooth(0.6f, 0f, 0.55f, 0.1f, low)   // a small turn
        assertEquals(0.55f, steady[0], 1e-4f)
        assertEquals(0.1f, steady[1], 1e-4f)

        val reversed = LeftStickFeel.smooth(1f, 0f, -0.5f, 0f, low)     // a sharp reversal
        assertTrue("the direction is pulled towards the previous one", reversed[0] > -0.5f)
        assertEquals("the pace is the sample's", 0.5f, hypot(reversed[0], reversed[1]), 1e-4f)
    }

    @Test
    fun smoothingConvergesInAFewSamples() {
        val smoother = LeftStickFeel.Smoother()
        smoother.apply(1f, 0f, LeftStickFeel.SMOOTHING_MEDIUM)          // start pointing right
        var out = floatArrayOf(1f, 0f)
        repeat(8) { out = smoother.apply(-1f, 0f, LeftStickFeel.SMOOTHING_MEDIUM) }
        assertEquals(-1f, out[0], 5e-2f)
        assertTrue(kotlin.math.abs(out[1]) < 1e-3f)
    }

    @Test
    fun aLiftedThumbClearsTheMemory() {
        val smoother = LeftStickFeel.Smoother()
        smoother.apply(1f, 0f, LeftStickFeel.SMOOTHING_MEDIUM)
        smoother.apply(0f, 0f, LeftStickFeel.SMOOTHING_MEDIUM)
        val out = smoother.apply(-1f, 0f, LeftStickFeel.SMOOTHING_MEDIUM)
        assertEquals(-1f, out[0], 1e-4f)
    }

    // ---- the path a runtime that shapes for itself takes ----------------------

    @Test
    fun engineShapedInputOnlyGetsSensitivity() {
        val s = LeftStickFeel.Settings(
            LeftStickFeel.PRESET_CUSTOM, 0.2f, 1.2f, LeftStickFeel.CURVE_SOFT,
            LeftStickFeel.SMOOTHING_LOW, 0.92f,
        )
        val out = LeftStickFeel.shape(0.5f, 0f, s, shapedByEngine = true)
        assertEquals(0.6f, out[0], 1e-4f)   // the engine did the deadzone and curve
        val capped = LeftStickFeel.shape(0.9f, 0f, s, shapedByEngine = true)
        assertEquals(1f, capped[0], 1e-4f)
    }

    @Test
    fun namesFallBackWithoutLosingTheProfile() {
        assertEquals(LeftStickFeel.PRESET_CUSTOM, LeftStickFeel.presetName(null))
        assertEquals(LeftStickFeel.PRESET_CUSTOM, LeftStickFeel.presetName("balancedish"))
        assertEquals(LeftStickFeel.CURVE_LINEAR, LeftStickFeel.curveName(""))
        assertEquals(LeftStickFeel.SMOOTHING_OFF, LeftStickFeel.smoothingName("high"))
        assertFalse(LeftStickFeel.preset(LeftStickFeel.PRESET_CUSTOM).deadzone > 0f)
    }
}
