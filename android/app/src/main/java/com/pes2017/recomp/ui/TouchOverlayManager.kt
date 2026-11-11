package com.pes2017.recomp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.pes2017.recomp.TouchEventBus
import kotlin.math.atan2
import kotlin.math.hypot

class TouchOverlayManager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- State & Constants ---
    private var isSprinting = false
    private var inPossession = true
    private var inMenuMode = false

    // Pointer tracking
    private var leftStickPointerId = -1
    private var actionPointerId = -1

    // Fake Shot tracking
    private var shootTouchStartX = 0f
    private var shootTouchStartY = 0f
    private var shootTouchStartTime = 0L
    private val fakeShotWindowMs = 250L
    private val dragThresholdPx = 40f

    // Swipe tracking for Action Cluster
    private var actionTouchStartX = 0f
    private var actionTouchStartY = 0f

    private val paint = Paint().apply {
        isAntiAlias = true
        textSize = 36f
        color = Color.WHITE
    }

    init {
        // Listen to UI/State updates from TouchEventBus
        TouchEventBus.registerStateListener { state ->
            inMenuMode = state.inMenuMode
            inPossession = !state.isDefending
            invalidate()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actionMasked = event.actionMasked
        val actionIndex = event.actionIndex
        val pointerId = event.getPointerId(actionIndex)

        when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)

                if (x < width * 0.45f && leftStickPointerId == -1) {
                    leftStickPointerId = pointerId
                    if (inMenuMode) handleMenuDPadDown(x, y) else handleLeftStickDown(x, y)
                } else if (x >= width * 0.45f && actionPointerId == -1) {
                    actionPointerId = pointerId
                    handleActionClusterDown(x, y)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)

                    if (id == leftStickPointerId) {
                        if (inMenuMode) handleMenuDPadMove(x, y) else handleLeftStickMove(x, y)
                    } else if (id == actionPointerId) {
                        handleActionClusterMove(x, y)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val pointerIndex = event.findPointerIndex(pointerId)
                val x = if (pointerIndex >= 0) event.getX(pointerIndex) else 0f
                val y = if (pointerIndex >= 0) event.getY(pointerIndex) else 0f

                if (pointerId == leftStickPointerId) {
                    leftStickPointerId = -1
                    if (inMenuMode) handleMenuDPadUp() else handleLeftStickUp()
                } else if (pointerId == actionPointerId) {
                    actionPointerId = -1
                    handleActionClusterUp(x, y)
                }
            }
        }
        invalidate()
        return true
    }

    private fun handleLeftStickDown(x: Float, y: Float) {
        isSprinting = true
        try { nativeSetAxisSlab(0, x, y) } catch (_: UnsatisfiedLinkError) {}
    }

    private fun handleLeftStickMove(x: Float, y: Float) {
        try { nativeSetAxisSlab(0, x, y) } catch (_: UnsatisfiedLinkError) {}
    }

    private fun handleLeftStickUp() {
        isSprinting = false
        try { nativeReleaseAxis(0) } catch (_: UnsatisfiedLinkError) {}
    }

    // --- Menu D-Pad Handlers ---
    private fun handleMenuDPadDown(x: Float, y: Float) {
        // Map discrete directional sectors to D-Pad SDL buttons
        evaluateMenuDirection(x, y)
    }

    private fun handleMenuDPadMove(x: Float, y: Float) {
        evaluateMenuDirection(x, y)
    }

    private fun handleMenuDPadUp() {
        try {
            nativeSendButtonRelease("DPAD_UP")
            nativeSendButtonRelease("DPAD_DOWN")
            nativeSendButtonRelease("DPAD_LEFT")
            nativeSendButtonRelease("DPAD_RIGHT")
        } catch (_: UnsatisfiedLinkError) {}
    }

    private fun evaluateMenuDirection(x: Float, y: Float) {
        // Calculate D-pad offsets relative to touch center
        val centerX = width * 0.225f
        val centerY = height * 0.75f
        val dx = x - centerX
        val dy = y - centerY
        if (hypot(dx.toDouble(), dy.toDouble()) < 20f) return

        val angle = atan2(dy.toDouble(), dx.toDouble()) * (180.0 / Math.PI)
        try {
            nativeSendButtonRelease("DPAD_UP")
            nativeSendButtonRelease("DPAD_DOWN")
            nativeSendButtonRelease("DPAD_LEFT")
            nativeSendButtonRelease("DPAD_RIGHT")

            when {
                angle >= -135.0 && angle < -45.0 -> nativeSendButtonPress("DPAD_UP")
                angle >= 45.0 && angle < 135.0 -> nativeSendButtonPress("DPAD_DOWN")
                angle >= -45.0 && angle < 45.0 -> nativeSendButtonPress("DPAD_RIGHT")
                else -> nativeSendButtonPress("DPAD_LEFT")
            }
        } catch (_: UnsatisfiedLinkError) {}
    }

    private fun handleActionClusterDown(x: Float, y: Float) {
        actionTouchStartX = x
        actionTouchStartY = y
        shootTouchStartX = x
        shootTouchStartY = y
        shootTouchStartTime = System.currentTimeMillis()
    }

    private fun handleActionClusterMove(x: Float, y: Float) {}

    private fun handleActionClusterUp(x: Float, y: Float) {
        val duration = System.currentTimeMillis() - shootTouchStartTime
        val deltaX = x - shootTouchStartX
        val deltaY = y - shootTouchStartY
        val distance = hypot(deltaX.toDouble(), deltaY.toDouble()).toFloat()

        if (duration <= fakeShotWindowMs && distance > dragThresholdPx) {
            val angle = atan2(deltaY.toDouble(), deltaX.toDouble()) * (180.0 / Math.PI)
            // Drag towards Pass button zone (Leftward drag)
            if (angle in 135.0..225.0) {
                triggerFakeShotMacro()
                return
            }
        }

        if (distance > dragThresholdPx) {
            resolveCardinalSwipe(deltaX, deltaY)
        } else {
            triggerStandardTapAction()
        }
    }

    private fun resolveCardinalSwipe(dx: Float, dy: Float) {
        val angle = atan2(dy.toDouble(), dx.toDouble()) * (180.0 / Math.PI)
        val dir = when {
            angle >= -45.0 && angle < 45.0 -> "RIGHT"
            angle >= 45.0 && angle < 135.0 -> "DOWN"
            (angle >= 135.0 || angle < -135.0) -> "LEFT"
            else -> "UP"
        }
        try { nativeTriggerActionSwipe(dir) } catch (_: UnsatisfiedLinkError) {}
    }

    private fun triggerFakeShotMacro() {
        try {
            nativeSendButtonPress("SHOOT")
            postDelayed({
                nativeSendButtonRelease("SHOOT")
                nativeSendButtonPress("PASS")
            }, 50)
            postDelayed({ nativeSendButtonRelease("PASS") }, 100)
            TouchEventBus.postToast("Fake Shot!")
        } catch (_: UnsatisfiedLinkError) {}
    }

    private fun triggerStandardTapAction() {
        val btn = if (inPossession) "SHOOT" else "TACKLE"
        try {
            nativeSendButtonPress(btn)
            postDelayed({ nativeSendButtonRelease(btn) }, 50)
        } catch (_: UnsatisfiedLinkError) {}
    }

    // --- Native JNI Link Stubs ---
    private external fun nativeSetAxisSlab(stickId: Int, x: Float, y: Float)
    private external fun nativeReleaseAxis(stickId: Int)
    private external fun nativeSendButtonPress(buttonName: String)
    private external fun nativeSendButtonRelease(buttonName: String)
    private external fun nativeTriggerActionSwipe(direction: String)
}
