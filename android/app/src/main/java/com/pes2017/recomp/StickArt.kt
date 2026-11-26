package com.pes2017.recomp

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.hypot
import kotlin.math.max

/**
 * The one drawing of a stick: the metallic socket, the Xbox 360 notch ring, the
 * deflection line, the thumb cap with its dish and its four grip dots.
 *
 * It lives here, outside the overlay, because there are now two places that have
 * to show the same stick and "looks the same" is not a property a copied paint
 * routine keeps: the match pad ([TouchOverlayView]) and the Left stick preview
 * page ([LeftStickPreviewView]) call this one function with the same numbers.
 * The preview is therefore the real stick at a larger radius, not an
 * illustration of one.
 *
 * Coordinates are in the view's pixels; [r] is the socket radius the pad would
 * draw at, and [travel] is the share of it the knob moves through (0.62 on the
 * pad). Nothing here reads preferences, input or state: the caller supplies
 * what the stick is doing.
 */
object StickArt {

    /** The run's amber-blue, the same the pad's sprint halo uses. */
    private const val SPRINT_GLOW = 0xFF3B82F6.toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val notch = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val dimple = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Draws one stick.
     *
     * @param deflection the stick's -1..1 vector, or null when no finger holds it.
     * @param knobRaw a two-stage stick's unclamped throw, so the knob can be
     *   pinned at its rim while the deflection has stopped growing.
     * @param sprintHalo whether the run's halo belongs around this socket.
     * @param label what the idle knob says ("LS", "RS").
     */
    fun draw(
        canvas: Canvas,
        density: Float,
        cx: Float,
        cy: Float,
        r: Float,
        alpha: Float,
        faded: Boolean,
        deflection: FloatArray?,
        knobRaw: FloatArray? = null,
        twoStaged: Boolean = false,
        travel: Float = 0.62f,
        sprintHalo: Boolean = false,
        label: String = "",
    ) {
        if (r <= 0f) return
        val fadeFactor = if (faded) 0.45f else 1f
        val isDeflected = deflection != null

        // 1. Outer socket base (metallic dish)
        fill.color = Color.argb((alpha * fadeFactor * 75).toInt().coerceIn(0, 255), 16, 20, 26)
        canvas.drawCircle(cx, cy, r, fill)

        // Outer socket rim stroke
        stroke.color = Color.argb((alpha * fadeFactor * 130).toInt().coerceIn(0, 255), 45, 55, 70)
        stroke.strokeWidth = max(1.5f, r * 0.035f)
        canvas.drawCircle(cx, cy, r - stroke.strokeWidth * 0.5f, stroke)

        // Sprint's halo: two rings just outside the socket while a run is on.
        if (sprintHalo) {
            stroke.color = Color.argb(
                (alpha * fadeFactor * 95).toInt().coerceIn(0, 255),
                Color.red(SPRINT_GLOW), Color.green(SPRINT_GLOW), Color.blue(SPRINT_GLOW),
            )
            stroke.strokeWidth = max(3f, r * 0.13f)
            canvas.drawCircle(cx, cy, r * 1.02f, stroke)
            stroke.color = Color.argb(
                (alpha * fadeFactor * 225).toInt().coerceIn(0, 255),
                Color.red(SPRINT_GLOW), Color.green(SPRINT_GLOW), Color.blue(SPRINT_GLOW),
            )
            stroke.strokeWidth = max(2f, r * 0.05f)
            canvas.drawCircle(cx, cy, r * 1.07f, stroke)
        }

        // 4 Cardinal Guide Notches on the outer socket perimeter (Xbox stick housing style)
        val notchLen = r * 0.10f
        notch.color = Color.argb((alpha * fadeFactor * 160).toInt().coerceIn(0, 255), 70, 85, 105)
        notch.strokeWidth = max(1.5f, 2f * density)
        canvas.drawLine(cx, cy - r, cx, cy - r + notchLen, notch)
        canvas.drawLine(cx, cy + r - notchLen, cx, cy + r, notch)
        canvas.drawLine(cx - r, cy, cx - r + notchLen, cy, notch)
        canvas.drawLine(cx + r - notchLen, cy, cx + r, cy, notch)

        // 2. Knob position. A two-stage stick follows the raw throw and pins at
        // its own rim through the ring, so the hand can see it is past the
        // socket even though the deflection has stopped growing.
        val knob = if (twoStaged) knobRaw ?: deflection else deflection
        val knobLength = knob?.let { hypot(it[0], it[1]) } ?: 0f
        val knobPin = if (knobLength > 1f) 1f / knobLength else 1f
        val knobX = cx + (knob?.get(0) ?: 0f) * knobPin * r * travel
        val knobY = cy - (knob?.get(1) ?: 0f) * knobPin * r * travel

        // Directional deflection vector glow
        if (isDeflected) {
            val stickDx = knobX - cx
            val stickDy = knobY - cy
            val dist = hypot(stickDx, stickDy)
            if (dist > 4f * density) {
                // Outer subtle radiant trail
                notch.color = Color.argb((alpha * fadeFactor * 90).toInt().coerceIn(0, 255), 34, 197, 94)
                notch.strokeWidth = max(3f, 4.5f * density)
                canvas.drawLine(cx, cy, knobX, knobY, notch)

                // High-contrast inner direction line
                notch.color = Color.argb((alpha * fadeFactor * 210).toInt().coerceIn(0, 255), 255, 255, 255)
                notch.strokeWidth = max(1.5f, 1.8f * density)
                canvas.drawLine(cx, cy, knobX, knobY, notch)
            }
        }

        // 3. Xbox 360 Thumbstick Cap / Knob
        val knobR = r * 0.38f

        // Outer rubber traction ring
        fill.color = Color.argb(
            (alpha * fadeFactor * (if (isDeflected) 240 else 210)).toInt().coerceIn(0, 255), 36, 42, 52,
        )
        canvas.drawCircle(knobX, knobY, knobR, fill)

        // Raised outer rim stroke
        stroke.color = Color.argb(
            (alpha * fadeFactor * (if (isDeflected) 220 else 160)).toInt().coerceIn(0, 255), 68, 78, 94,
        )
        stroke.strokeWidth = max(1.5f, knobR * 0.08f)
        canvas.drawCircle(knobX, knobY, knobR - stroke.strokeWidth * 0.5f, stroke)

        // Inner concave depression bowl
        val dishR = knobR * 0.72f
        fill.color = Color.argb((alpha * fadeFactor * 240).toInt().coerceIn(0, 255), 20, 24, 30)
        canvas.drawCircle(knobX, knobY, dishR, fill)

        // Inner dish bevel
        stroke.color = Color.argb((alpha * fadeFactor * 100).toInt().coerceIn(0, 255), 12, 14, 18)
        stroke.strokeWidth = max(1f, 1.5f * density)
        canvas.drawCircle(knobX, knobY, dishR, stroke)

        // 4. Center label when idle ("LS" or "RS")
        if (!isDeflected && label.isNotEmpty()) {
            this.label.textSize = dishR * 0.34f
            this.label.color = Color.argb((alpha * fadeFactor * 130).toInt().coerceIn(0, 255), 148, 163, 184)
            canvas.drawText(label, knobX, knobY - (this.label.descent() + this.label.ascent()) * 0.5f, this.label)
        }

        // 5. The 4 Signature Xbox Rubber Grip Dimples/Dots (Top, Bottom, Left, Right)
        val dotOffset = knobR * 0.44f
        val dotRadius = max(2f, 2.8f * density)
        dimple.style = Paint.Style.FILL
        dimple.color = Color.argb(
            (alpha * fadeFactor * (if (isDeflected) 230 else 170)).toInt().coerceIn(0, 255), 88, 100, 118,
        )
        canvas.drawCircle(knobX, knobY - dotOffset, dotRadius, dimple)
        canvas.drawCircle(knobX, knobY + dotOffset, dotRadius, dimple)
        canvas.drawCircle(knobX - dotOffset, knobY, dotRadius, dimple)
        canvas.drawCircle(knobX + dotOffset, knobY, dotRadius, dimple)
    }
}
