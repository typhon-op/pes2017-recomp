package com.pes2017.recomp

import kotlin.math.hypot

/**
 * What the right stick sends, whether the flick-skill switch is on or off.
 *
 * A flick is a gesture read from RS's own thumb, never a replacement for RS:
 * the press still deflects the stick (TouchOverlayView.moveStick), the flick's
 * travel is written back into that same deflection (sendSkill), and the runtime
 * overlays its sequences (nativeSkillTouch/nativeSkillPlay) on top of the
 * analogue axes for the frames they run. That is why nothing here takes the
 * switch as an argument: the axes go out either way.
 *
 * Before tfix5 the flick path sent only the pulses and pushState skipped RS's
 * axes, so a flick build had a dead stick. Keeping the sum, the clamp and the
 * flick's travel in one place lets a JVM test pin that arithmetic down
 * (RightStickTest) instead of leaving it to a phone.
 */
class RightStick {

    private var x = 0f
    private var y = 0f

    /** A new sample of the state: one per pushState. */
    fun reset() {
        x = 0f
        y = 0f
    }

    /** One finger on the stick. Fingers on the same stick add, as they always have. */
    fun add(dx: Float, dy: Float) {
        x += dx
        y += dy
    }

    /** The horizontal axis the title is told: the summed throw, clamped to -1..1. */
    fun rightX(): Float = clamp(x)

    /** The vertical axis the title is told. The guest's Y points up. */
    fun rightY(): Float = clamp(y)

    companion object {

        fun clamp(value: Float): Float = value.coerceIn(-1f, 1f)

        /**
         * A flick's travel from where the thumb landed, in throws.
         *
         * The screen's Y points down and the guest's up, so the vertical is
         * negated here rather than at each call. A thumb can travel further than
         * the socket is wide: the length cap keeps the knob on its rim and the
         * classifier's travel in range, which is why this is a length and not a
         * per-axis clamp.
         */
        fun flickTravel(
            originX: Float,
            originY: Float,
            x: Float,
            y: Float,
            radius: Float,
        ): FloatArray {
            if (radius <= 0f) return floatArrayOf(0f, 0f)
            var dx = (x - originX) / radius
            var dy = -(y - originY) / radius
            val length = hypot(dx, dy)
            if (length > MAX_TRAVEL) {
                dx = dx / length * MAX_TRAVEL
                dy = dy / length * MAX_TRAVEL
            }
            return floatArrayOf(dx, dy)
        }

        /** The furthest a flick is read, in socket radii. */
        const val MAX_TRAVEL = 1.5f
    }
}
