package com.pes2017.recomp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot

class TouchOverlayManager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- State & Constants ---
    private var isSprinting = false
    private var inPossession = true // Controlled by game state memory hook

    // Pointer tracking to prevent multi-touch deadlocks between Left Stick & Action Cluster
    private var leftStickPointerId = -1
    private var actionPointerId = -1

    // Fake Shot tracking (Shoot -> Pass drag)
    private var shootTouchStartX = 0f
    private var shootTouchStartY = 0f
    private var shootTouchStartTime = 0L
    private val fakeShotWindowMs = 250L
    private val dragThresholdPx = 40f

    // Swipe tracking for Action Cluster (Shoot, Cross, Skill)
    private var actionTouchStartX = 0f
    private var actionTouchStartY = 0f

    // Paint for visual feedback & action toasts
    private val paint = Paint().apply {
        isAntiAlias = true
        textSize = 36f
        color = Color.WHITE
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actionIndex = event.actionIndex
        val pointerId = event.getPointerId(actionIndex)
        val actionMasked = event.actionMasked

        when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)

                // Divide screen: Left 45% = Analog Stick / Movement, Right 55% = Action Cluster
                if (x < width * 0.45f && leftStickPointerId == -1) {
                    leftStickPointerId = pointerId
                    handleLeftStickDown(x, y)
                } else if (x >= width * 0.45f && actionPointerId == -1) {
                    actionPointerId = pointerId
                    handleActionClusterDown(x, y)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                // Process active pointer movements independently to avoid deadlock
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)

                    if (id == leftStickPointerId) {
                        handleLeftStickMove(x, y)
                    } else if (id == actionPointerId) {
                        handleActionClusterMove(x, y)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (pointerId == leftStickPointerId) {
                    leftStickPointerId = -1
                    handleLeftStickUp()
                } else if (pointerId == actionPointerId) {
                    actionPointerId = -1
                    handleActionClusterUp(event.x, event.y)
                }
            }
        }
        invalidate()
        return true
    }

    private fun handleLeftStickDown(x: Float, y: Float) {
        // Activate left stick anchoring & evaluate outer-ring sprint threshold
        isSprinting = true // Trigger RB / 0x0200 latch when pushed to perimeter edge
        try {
            nativeSetAxisSlab(0, x, y)
        } catch (_: UnsatisfiedLinkError) {}
    }

    private fun handleLeftStickMove(x: Float, y: Float) {
        try {
            nativeSetAxisSlab(0, x, y)
        } catch (_: UnsatisfiedLinkError) {}
    }

    private fun handleLeftStickUp() {
        isSprinting = false
        try {
            nativeReleaseAxis(0)
        } catch (_: UnsatisfiedLinkError) {}
    }

    private fun handleActionClusterDown(x: Float, y: Float) {
        actionTouchStartX = x
        actionTouchStartY = y
        shootTouchStartX = x
        shootTouchStartY = y
        shootTouchStartTime = System.currentTimeMillis()
    }

    private fun handleActionClusterMove(x: Float, y: Float) {
        // Track live movement for gesture distance checks
    }

    private fun handleActionClusterUp(x: Float, y: Float) {
        val duration = System.currentTimeMillis() - shootTouchStartTime
        val deltaX = x - shootTouchStartX
        val deltaY = y - shootTouchStartY
        val distance = hypot(deltaX.toDouble(), deltaY.toDouble()).toFloat()

        // 1. Check for Shoot-to-Pass Fake Shot Drag
        if (duration <= fakeShotWindowMs && distance > dragThresholdPx) {
            val angle = atan2(deltaY.toDouble(), deltaX.toDouble()) * (180.0 / Math.PI)
            // If swiped/dragged towards Pass button zone, trigger Fake Shot macro (Square -> Cross)
            if (angle in 135.0..225.0 || angle in -45.0..45.0) {
                triggerFakeShotMacro()
                return
            }
        }

        // 2. Cardinal Snapping Swipe Resolution (4 Distinct Swipes per Action Button)
        if (distance > dragThresholdPx) {
            resolveCardinalSwipe(deltaX, deltaY)
        } else {
            // Standard Tap Action
            triggerStandardTapAction(shootTouchStartX, shootTouchStartY)
        }
    }

    private fun resolveCardinalSwipe(dx: Float, dy: Float) {
        val angle = atan2(dy.toDouble(), dx.toDouble()) * (180.0 / Math.PI)
        val dir = when {
            angle >= -45.0 && angle < 45.0 -> "RIGHT"
            angle >= 45.0 && angle < 135.0 -> "DOWN"
            (angle >= 135.0 || angle < -135.0) -> "LEFT"
            angle >= -135.0 && angle < -45.0 -> "UP"
            else -> "RIGHT"
        }
        try {
            nativeTriggerActionSwipe(dir)
        } catch (_: UnsatisfiedLinkError) {}
    }

    private fun triggerFakeShotMacro() {
        // Native JNI hook execution for Shoot followed instantly by Pass
        try {
            nativeSendButtonPress("SHOOT")
        } catch (_: UnsatisfiedLinkError) {}
        postDelayed({
            try {
                nativeSendButtonRelease("SHOOT")
                nativeSendButtonPress("PASS")
            } catch (_: UnsatisfiedLinkError) {}
        }, 50)
        postDelayed({
            try {
                nativeSendButtonRelease("PASS")
            } catch (_: UnsatisfiedLinkError) {}
        }, 100)
        showActionToast("Fake Shot!")
    }

    private fun triggerStandardTapAction(x: Float, y: Float) {
        // Standard button tap mapping based on InPossession state
        if (inPossession) {
            try {
                nativeSendButtonPress("SHOOT")
            } catch (_: UnsatisfiedLinkError) {}
            postDelayed({
                try {
                    nativeSendButtonRelease("SHOOT")
                } catch (_: UnsatisfiedLinkError) {}
            }, 50)
        } else {
            try {
                nativeSendButtonPress("TACKLE")
            } catch (_: UnsatisfiedLinkError) {}
            postDelayed({
                try {
                    nativeSendButtonRelease("TACKLE")
                } catch (_: UnsatisfiedLinkError) {}
            }, 50)
        }
    }

    private fun showActionToast(message: String) {
        // Renders top-center universal action toast overlay at Y = 10%
    }

    // --- Native JNI Link Stubs ---
    private external fun nativeSetAxisSlab(stickId: Int, x: Float, y: Float)
    private external fun nativeReleaseAxis(stickId: Int)
    private external fun nativeSendButtonPress(buttonName: String)
    private external fun nativeSendButtonRelease(buttonName: String)
    private external fun nativeTriggerActionSwipe(direction: String)
}
