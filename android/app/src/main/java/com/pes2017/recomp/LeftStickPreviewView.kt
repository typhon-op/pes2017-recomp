package com.pes2017.recomp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The left stick, drawn small, so a setting can be felt before a match.
 *
 * The thumb is the player's finger on the drawing; the circles are the profile:
 * the inner one is the deadzone, the amber ring is where a run starts in MATCH
 * mode and the dotted one just inside it is where the run lets go. The green
 * line is what the stick will actually send -- the direction and length after
 * the deadzone, the curve, the sensitivity and the smoothing.
 *
 * It is a view on its own screen, not an overlay: it shares no touch stream
 * with the match pad and cannot press anything in the game.
 */
class LeftStickPreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var settings: LeftStickFeel.Settings = LeftStickFeel.Settings()
        set(value) {
            field = LeftStickFeel.sanitize(value)
            recompute()
            invalidate()
        }

    private val smoother = LeftStickFeel.Smoother()
    private var rawX = 0f
    private var rawY = 0f
    private var outX = 0f
    private var outY = 0f
    private var touched = false

    private val density = resources.displayMetrics.density
    private val ink = WrapperTheme.color(context, R.attr.pesText, Color.WHITE)
    private val muted = WrapperTheme.color(context, R.attr.pesMuted, Color.GRAY)

    private val socket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = muted
    }
    private val zone = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = withAlpha(muted, 30)
    }
    private val deadzone = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = muted
        pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = RING
    }
    private val release = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = withAlpha(RING, 150)
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 4f * density), 0f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    private val response = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = RESPONSE
    }
    private val responseDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RESPONSE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        textSize = 11f * density
    }

    init {
        isClickable = true
        contentDescription = context.getString(R.string.left_stick_preview_hint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val radius = radius()
                if (radius <= 0f) return false
                touched = true
                rawX = ((event.x - width / 2f) / radius).coerceIn(-1.15f, 1.15f)
                rawY = (-(event.y - height / 2f) / radius).coerceIn(-1.15f, 1.15f)
                recompute()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touched = false
                rawX = 0f
                rawY = 0f
                smoother.reset()
                recompute()
                invalidate()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun recompute() {
        val shaped = LeftStickFeel.shape(rawX, rawY, settings)
        val smooth = if (hypot(shaped[0], shaped[1]) < 0.002f) {
            smoother.reset()
            shaped
        } else {
            smoother.apply(shaped[0], shaped[1], settings.smoothing)
        }
        outX = smooth[0]
        outY = smooth[1]
    }

    private fun radius(): Float = min(width, height) / 2f - 26f * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = radius()
        if (r <= 0f) return

        canvas.drawCircle(cx, cy, r, socket)
        canvas.drawCircle(cx, cy, r * settings.deadzone, zone)
        canvas.drawCircle(cx, cy, r * settings.deadzone, deadzone)
        canvas.drawCircle(cx, cy, r * settings.sprint, ring)
        canvas.drawCircle(cx, cy, r * LeftStickFeel.sprintRelease(settings), release)

        if (touched) {
            canvas.drawLine(cx, cy, cx + outX * r, cy - outY * r, response)
            canvas.drawCircle(cx + outX * r, cy - outY * r, 4f * density, responseDot)
            canvas.drawCircle(cx + rawX * r, cy - rawY * r, 9f * density, dot)
        } else {
            canvas.drawCircle(cx, cy, 9f * density, dot)
        }

        val magnitude = (hypot(outX, outY) * 100f).roundToInt().coerceIn(0, 100)
        val sprinting = hypot(rawX, rawY) >= settings.sprint
        val text = context.getString(
            R.string.left_stick_preview_readout,
            magnitude,
            if (sprinting) context.getString(R.string.left_stick_preview_run)
            else context.getString(R.string.left_stick_preview_walk),
        )
        canvas.drawText(text, 10f * density, height - 8f * density, label)
    }

    private companion object {
        /** The run's amber, the same the pad's sprint halo uses. */
        const val RING = 0xFFFBBF24.toInt()

        /** What the stick sends: the pad's own accent green. */
        const val RESPONSE = 0xFF34D399.toInt()

        fun withAlpha(color: Int, alpha: Int): Int =
            (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)
    }
}
