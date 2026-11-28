package com.pes2017.recomp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The left stick, on a page of its own, at the size the match pad draws it.
 *
 * It is the same stick the match pad draws -- [StickArt] is the one drawing of
 * it, with the same socket, notches, cap, dish and grip dots -- and the same
 * **size**: [stickRadiusPx] is the layout's own `stick` element fitted by
 * [ScreenFit] and scaled by this device's density, which is exactly what the
 * pad's geometry does. Resizing the stick in Edit Controls therefore resizes
 * this page's stick, and there is no second scale anywhere.
 *
 * A thumb on it goes through the very arithmetic the pad uses (`StickVector`
 * then `LeftStickFeel.shape`, including the selected profile's deadzone, curve,
 * sensitivity and smoothing) and the same sprint latch, so the output percentage
 * and the walk/run state are what a match would receive rather than an
 * illustration.
 *
 * It shares no touch stream with the game: it is a view on a separate activity
 * and never calls NativeBridge, so dragging it cannot press anything.
 */
class LeftStickPreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var settings: LeftStickFeel.Settings = LeftStickFeel.Settings()
        set(value) {
            field = LeftStickFeel.sanitize(value)
            smoother.reset()
            invalidate()
        }

    /**
     * The socket's radius in pixels, from the layout the match draws
     * ([StickPreview.radiusPx]). Zero draws nothing: a page with no stick is a
     * page with nothing to say.
     */
    var stickRadiusPx: Float = 0f
        set(value) {
            field = value.coerceAtLeast(0f)
            invalidate()
        }

    private val smoother = LeftStickFeel.Smoother()
    private val sprintLatch = SprintLatch()

    /** The finger that owns the preview: a second one cannot move the stick. */
    private var pointerId = -1
    private var rawX = 0f
    private var rawY = 0f
    private var outX = 0f
    private var outY = 0f
    private var touched = false

    private val density = resources.displayMetrics.density
    private val ink = WrapperTheme.color(context, R.attr.pesText, Color.WHITE)
    private val muted = WrapperTheme.color(context, R.attr.pesMuted, Color.GRAY)
    private val readout = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        textAlign = Paint.Align.CENTER
        textSize = 15f * density
        isFakeBoldText = true
    }
    private val detail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = muted
        textAlign = Paint.Align.CENTER
        textSize = 12f * density
    }

    init {
        isClickable = true
        contentDescription = context.getString(R.string.left_stick_preview_page_hint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (radius() <= 0f) return false
                pointerId = event.getPointerId(0)
                touched = true
                sample(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pointerId < 0) return false
                val index = event.findPointerIndex(pointerId)
                if (index < 0) return true
                // A page like this is felt, not scribbled on: use every batched
                // sample so a quick circle still comes out round.
                for (h in 0 until event.historySize) {
                    sample(event.getHistoricalX(index, h), event.getHistoricalY(index, h), false)
                }
                sample(event.getX(index), event.getY(index))
                return true
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val index = event.actionIndex
                if (event.getPointerId(index) != pointerId) return true
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                centre()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                centre()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** One finger position, as the pad reads it: fixed socket, guest-up Y. */
    private fun sample(x: Float, y: Float, redraw: Boolean = true) {
        val r = radius()
        if (r <= 0f) return
        val vector = StickVector.of(width / 2f, (height - labelBand()) / 2f, x, y, r)
        rawX = vector[0]
        rawY = vector[1]
        val shaped = LeftStickFeel.shape(rawX, rawY, settings)
        val smooth = if (hypot(shaped[0], shaped[1]) < 0.002f) {
            smoother.reset()
            shaped
        } else {
            smoother.apply(shaped[0], shaped[1], settings.smoothing)
        }
        outX = smooth[0]
        outY = smooth[1]
        // The run is the pad's own latch, with the selected profile's ring: at
        // the ring the match stick holds RB until the thumb comes back inside
        // the release point, exactly as here.
        sprintLatch.thresholds(settings.sprint, LeftStickFeel.sprintRelease(settings))
        sprintLatch.update(rawX, rawY)
        if (redraw) invalidate()
    }

    private fun centre() {
        pointerId = -1
        touched = false
        rawX = 0f
        rawY = 0f
        outX = 0f
        outY = 0f
        smoother.reset()
        sprintLatch.reset()
        invalidate()
    }

    /** Space under the stick for the readout, so the two never overlap. */
    private fun labelBand(): Float = 58f * density

    private fun radius(): Float = stickRadiusPx

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = (height - labelBand()) / 2f
        val r = radius()
        if (r <= 0f) return

        // The pad's own stick, at this page's radius and full opacity.
        StickArt.draw(
            canvas = canvas,
            density = density,
            cx = cx,
            cy = cy,
            r = r,
            alpha = 1f,
            faded = false,
            deflection = if (touched) floatArrayOf(outX, outY) else null,
            label = "LS",
        )

        val magnitude = (hypot(outX, outY) * 100f).roundToInt().coerceIn(0, 100)
        val state = when {
            !touched || magnitude == 0 -> context.getString(R.string.left_stick_state_idle)
            sprintLatch.active -> context.getString(R.string.left_stick_state_run)
            else -> context.getString(R.string.left_stick_state_walk)
        }
        val baseline = cy + r + 22f * density
        canvas.drawText(context.getString(R.string.left_stick_preview_readout, magnitude, state), cx, baseline, readout)
        canvas.drawText(
            context.getString(
                R.string.left_stick_preview_thresholds,
                (settings.sprint * 100f).roundToInt(),
                (LeftStickFeel.sprintRelease(settings) * 100f).roundToInt(),
            ),
            cx, baseline + 18f * density, detail,
        )
    }
}
