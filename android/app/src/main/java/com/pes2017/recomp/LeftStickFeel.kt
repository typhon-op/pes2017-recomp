package com.pes2017.recomp

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The left stick's feel: what a thumb's throw becomes before it is sent, and
 * the numbers the customizations screen edits.
 *
 * The shipped stick is the reference, and it is not a guess: the build sent the
 * thumb's throw straight through -- `Prefs.DEFAULT_STICK_DEADZONE` 0,
 * `DEFAULT_STICK_CURVE` 1, no sensitivity scale, no smoothing -- and let the
 * title's own ~14% floor be the only deadzone. Sprint came from [0.92] of the
 * throw with the release at [0.82]. The Default profile below is exactly those
 * numbers, and [shape] short-circuits to the same arithmetic when it sees them,
 * so choosing Default -- or Reset to Default -- cannot change a single frame.
 *
 * The other three presets are new values on the same pipeline. They are not a
 * redefinition of Default, and Reset never selects one of them.
 *
 * Pure arithmetic and no Android types, so `LeftStickFeelTest` can walk it
 * without a device.
 */
object LeftStickFeel {

    // ---- the stored names ---------------------------------------------------

    const val PRESET_DEFAULT = "default"
    const val PRESET_BALANCED = "balanced"
    const val PRESET_TIGHT = "tight"
    const val PRESET_QUICK = "quick"
    const val PRESET_CUSTOM = "custom"

    const val CURVE_LINEAR = "linear"
    const val CURVE_SOFT = "soft"
    const val CURVE_SNAPPY = "snappy"

    const val SMOOTHING_OFF = "off"
    const val SMOOTHING_LOW = "low"
    const val SMOOTHING_MEDIUM = "medium"

    // ---- the ranges, as the screen presents them ----------------------------

    /** 0-25%, which is the title's own floor and a little past it. */
    const val DEADZONE_MAX = 0.25f

    /** 70-140%: how much of the throw reaches full pace. */
    const val SENSITIVITY_MIN = 0.70f
    const val SENSITIVITY_MAX = 1.40f

    /** 70-95% of the throw, the outer ring. */
    const val SPRINT_MIN = 0.70f
    const val SPRINT_MAX = 0.95f

    /**
     * Percentage points between the custom ring's on and off points. The
     * shipped ring keeps its own wider gap (see [sprintRelease]): changing it
     * would change when a run starts, and Default may not do that.
     */
    const val SPRINT_HYSTERESIS = 0.04f

    /** The shipped latch: a run starts at 92% of the throw... */
    const val SHIPPED_SPRINT = 0.92f

    /** ...and lets go under 82%, a ten-point gap. */
    const val SHIPPED_SPRINT_RELEASE = 0.82f

    /**
     * One complete configuration. [preset] says where the numbers came from;
     * [PRESET_CUSTOM] means the player moved one of them.
     */
    data class Settings(
        val preset: String = PRESET_DEFAULT,
        val deadzone: Float = 0f,
        val sensitivity: Float = 1f,
        val curve: String = CURVE_LINEAR,
        val smoothing: String = SMOOTHING_OFF,
        val sprint: Float = SHIPPED_SPRINT,
    )

    /** The four presets, with the values the screen offers. */
    fun preset(name: String): Settings = when (presetName(name)) {
        PRESET_BALANCED -> Settings(PRESET_BALANCED, 0.10f, 1.00f, CURVE_LINEAR, SMOOTHING_OFF, 0.82f)
        PRESET_TIGHT -> Settings(PRESET_TIGHT, 0.12f, 0.90f, CURVE_SOFT, SMOOTHING_LOW, 0.88f)
        PRESET_QUICK -> Settings(PRESET_QUICK, 0.08f, 1.20f, CURVE_SNAPPY, SMOOTHING_OFF, 0.75f)
        PRESET_CUSTOM -> Settings(PRESET_CUSTOM)
        else -> Settings()
    }

    private val PRESETS = setOf(PRESET_DEFAULT, PRESET_BALANCED, PRESET_TIGHT, PRESET_QUICK, PRESET_CUSTOM)
    private val CURVES = setOf(CURVE_LINEAR, CURVE_SOFT, CURVE_SNAPPY)
    private val SMOOTHING = setOf(SMOOTHING_OFF, SMOOTHING_LOW, SMOOTHING_MEDIUM)

    /** An unknown or missing name is Custom: the player's numbers are kept. */
    fun presetName(name: String?): String = if (name != null && name in PRESETS) name else PRESET_CUSTOM

    fun curveName(name: String?): String = if (name != null && name in CURVES) name else CURVE_LINEAR

    fun smoothingName(name: String?): String =
        if (name != null && name in SMOOTHING) name else SMOOTHING_OFF

    /** Every loaded or saved setting is pulled into its range here. */
    fun sanitize(settings: Settings): Settings = Settings(
        preset = presetName(settings.preset),
        deadzone = settings.deadzone.coerceIn(0f, DEADZONE_MAX),
        sensitivity = settings.sensitivity.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX),
        curve = curveName(settings.curve),
        smoothing = smoothingName(settings.smoothing),
        sprint = settings.sprint.coerceIn(SPRINT_MIN, SPRINT_MAX),
    )

    /**
     * The curve's exponent on what is left after the deadzone. 1 is a straight
     * line; above 1 gives the middle of the throw finer control (the soft
     * dribble), below 1 answers sooner (the snappy stick).
     */
    fun exponent(curve: String): Float = when (curveName(curve)) {
        CURVE_SOFT -> 1.45f
        CURVE_SNAPPY -> 0.72f
        else -> 1f
    }

    /** Turns gentler than 45 degrees are the thumb's, not a spike. */
    private const val SMOOTH_AFTER_RADIANS = 0.7854f

    /** How much of the previous direction a sharp turn keeps for one sample. */
    fun smoothingRetain(smoothing: String): Float = when (smoothingName(smoothing)) {
        SMOOTHING_LOW -> 0.30f
        SMOOTHING_MEDIUM -> 0.55f
        else -> 0f
    }

    /**
     * Where the run lets go. The Default profile keeps the shipped latch's own
     * ten-point gap, because that is the behaviour being preserved; every other
     * profile -- including a Custom one that happens to sit at 92% -- uses the
     * [SPRINT_HYSTERESIS] the customization screen promises.
     */
    fun sprintRelease(settings: Settings): Float {
        val s = sanitize(settings)
        if (s.preset == PRESET_DEFAULT) return SHIPPED_SPRINT_RELEASE
        return (s.sprint - SPRINT_HYSTERESIS).coerceAtLeast(0.05f)
    }

    /**
     * The thumb's throw as the deflection that is sent.
     *
     * [shapedByEngine] is true when a runtime that understands
     * `runtime/stick_curve.h` has already applied the deadzone and the curve
     * (the app only does it itself for the packaged engine, which predates
     * those entry points). Sensitivity is applied here either way: it is not
     * part of the engine's shape.
     *
     * The Default numbers take the same branch the build always took, so the
     * result is the thumb's own throw (clamped at the rim) with no lift and no
     * curve.
     */
    fun shape(x: Float, y: Float, settings: Settings, shapedByEngine: Boolean = false): FloatArray {
        val s = sanitize(settings)
        val magnitude = hypot(x, y)
        if (magnitude < 1e-6f) return floatArrayOf(0f, 0f)

        val plain = s.deadzone <= 0f && s.curve == CURVE_LINEAR
        if (shapedByEngine || plain) {
            // The console's mapping, unchanged: the thumb's throw is the
            // deflection, and the title's floor is the only deadzone. When the
            // engine did the shaping, what arrives is already that throw.
            val out = (min(magnitude, 1f) * s.sensitivity).coerceAtMost(1f)
            val k = out / magnitude
            return floatArrayOf(x * k, y * k)
        }

        // Past the deadzone the remaining travel is rescaled from zero to the
        // rim, then lifted to the first value the title acts on, so the two
        // deadzones are never added into dead travel the thumb can feel.
        if (!(magnitude > s.deadzone)) return floatArrayOf(0f, 0f)
        val reach = min(magnitude, 1f)
        val t = ((reach - s.deadzone) / (1f - s.deadzone)).coerceAtLeast(0f)
        val shaped = StickShape.GUEST_FLOOR + t.pow(exponent(s.curve)) * (1f - StickShape.GUEST_FLOOR)
        val out = (shaped * s.sensitivity).coerceAtMost(1f)
        val k = out / magnitude
        val sx = x * k
        val sy = y * k
        if (hypot(sx, sy) < 0.002f) return floatArrayOf(0f, 0f)
        return floatArrayOf(sx, sy)
    }

    /**
     * Damp a direction spike without touching the pace.
     *
     * Only a turn sharper than 45 degrees is blended towards the previous
     * direction, and only by [smoothingRetain]; a steady push and a gradual
     * curve pass through untouched. The magnitude is always the new sample's,
     * so the stick never accelerates or slows because of this.
     */
    fun smooth(prevX: Float, prevY: Float, x: Float, y: Float, smoothing: String): FloatArray {
        val retain = smoothingRetain(smoothing)
        if (retain <= 0f) return floatArrayOf(x, y)
        val magnitude = hypot(x, y)
        if (magnitude < 0.002f) return floatArrayOf(0f, 0f)
        if (hypot(prevX, prevY) < 0.002f) return floatArrayOf(x, y)

        // The signed shortest turn from the last direction to this one.
        // Working in angle, not by blending the two vectors, is what makes a
        // straight reversal damp too: opposite vectors average back to one of
        // themselves, so a vector blend would pass a spike through untouched.
        val turn = atan2(prevX * y - prevY * x, prevX * x + prevY * y)
        if (abs(turn) <= SMOOTH_AFTER_RADIANS) return floatArrayOf(x, y)

        // `turn` is new-minus-previous, so damping keeps the new angle short of
        // the previous one by the retained share of the turn.
        val angle = atan2(y, x) - turn * retain
        return floatArrayOf(cos(angle) * magnitude, sin(angle) * magnitude)
    }

    /**
     * The angle smoothing's memory: the direction it sent last, so a spike is
     * measured against what the player was just doing. One per stick, reset
     * whenever the thumb lifts.
     */
    class Smoother {
        private var x = 0f
        private var y = 0f

        fun reset() {
            x = 0f
            y = 0f
        }

        fun apply(x: Float, y: Float, smoothing: String): FloatArray {
            val out = smooth(this.x, this.y, x, y, smoothing)
            this.x = out[0]
            this.y = out[1]
            return out
        }
    }
}
