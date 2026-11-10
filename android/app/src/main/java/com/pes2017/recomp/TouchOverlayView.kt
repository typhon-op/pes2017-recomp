package com.pes2017.recomp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The on-screen pad: what it looks like, what a finger on it means, and the
 * editor that lets the player move it about.
 *
 * Two jobs in one view because they are the same geometry seen twice. In
 * play it reads fingers and pushes a controller state at the runtime; in
 * edit it reads fingers and moves the controls themselves. Nothing here
 * talks to the guest directly -- it all goes through NativeBridge, which is
 * the only thing the runtime sees.
 */
class TouchOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val prefs by lazy { Prefs(context) }

    var layout: ControlLayout = ControlLayout.defaults()
        set(value) {
            field = value
            releaseEverything()
            invalidate()
        }

    /** Told when the selection changes so the editor's panel can follow. */
    var onSelectionChanged: ((ControlElement?) -> Unit)? = null

    /** Told when a control was moved or resized, so the layout can be saved. */
    var onLayoutEdited: (() -> Unit)? = null

    var editing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            releaseEverything()
            selected = if (value) selected else null
            invalidate()
        }

    var selected: ControlElement? = null
        private set

    /** PlayStation names and symbols (Cross, Circle, L1, OPTIONS) instead of Xbox ones (A, B, LB, START). */
    var playStation: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Defensive mode state: when defending, actions adapt to PRESSURE, TACKLE, CLEAR, GK RUSH. */
    var isDefending: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            TouchEventBus.postStateChange(value, inMenuMode)
            invalidate()
        }

    /** Contextual Menu D-Pad mode: switches analog stick to crisp discrete D-Pad cardinal outputs. */
    var inMenuMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            releaseEverything()
            TouchEventBus.postStateChange(isDefending, value)
            invalidate()
        }

    /** Snapping makes a tidy row possible with a thumb; the editor offers it. */
    var snapToGrid: Boolean = false

    private val density = resources.displayMetrics.density

    // ---- what is being touched right now ----------------------------------
    private val claims = HashMap<Int, ControlElement>()
    private val stickPositions = HashMap<String, FloatArray>()   // id -> normalised x, y
    private val floatingStickCenters = HashMap<String, FloatArray>() // id -> dynamic cx, cy (px)
    val activeTouchPoints = HashMap<Int, FloatArray>() // pointerId -> [x, y] for HUD tracker
    private val held = HashSet<String>()
    private val toggled = HashSet<String>()
    private val buttonDownCoords = HashMap<Int, FloatArray>()
    private val activeFlickMods = HashSet<String>()
    private val buttonPressStartTimes = HashMap<String, Long>()
    val activeButtonDirections = HashMap<String, Int>() // id -> 0: none, 1: UP, 2: DOWN, 3: LEFT, 4: RIGHT
    val flickRipples = ArrayList<FloatArray>() // [x, y, dx, dy, lifetime]
    val skillFlickVector = FloatArray(4) // [originX, originY, curX, curY]
    var skillFlickActive = false
    var actionToastText: String = ""
    var actionToastTime: Long = 0L
    var teammateRunActive = false
    var teammateRunTime: Long = 0L
    private var autoSprintLatched = false
    private var lastLeftTapTime = 0L
    private var lastPassTapTime = 0L
    private var shotFakeActive = false
    private var lookPointer = -1
    private var lookLastX = 0f
    private var lookLastY = 0f

    fun showActionBadge(badge: String) {
        actionToastText = badge
        actionToastTime = System.currentTimeMillis()
        TouchEventBus.postToast(badge)
        invalidate()
    }

    // ---- editing -----------------------------------------------------------
    private var dragPointer = -1
    private var dragElement: ControlElement? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var dragMoved = false
    private var pinchStartDistance = 0f
    private var pinchStartSize = 0f
    private var pinching = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val subText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val notchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val dimplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 8f * density), 0f)
    }
    private val path = Path()
    private val box = RectF()
    private val tempRect = RectF()

    init {
        isFocusable = false
        isClickable = false
        setWillNotDraw(false)
    }

    // ---- geometry ----------------------------------------------------------

    private fun centreX(element: ControlElement): Float {
        val floating = floatingStickCenters[element.id]
        if (floating != null && !editing) return floating[0]
        return element.xFrac * width
    }

    private fun centreY(element: ControlElement): Float {
        val floating = floatingStickCenters[element.id]
        if (floating != null && !editing) return floating[1]
        return element.yFrac * height
    }

    private fun radius(element: ControlElement) = element.sizeDp * density * 0.5f

    /** The look area is a rectangle; everything else is a circle. */
    private fun rectOf(element: ControlElement, into: RectF) {
        val halfWidth = element.sizeDp * density * 0.5f
        val halfHeight = halfWidth * 0.62f
        into.set(
            centreX(element) - halfWidth, centreY(element) - halfHeight,
            centreX(element) + halfWidth, centreY(element) + halfHeight,
        )
    }

    private fun hit(element: ControlElement, x: Float, y: Float): Boolean {
        if (element.kind == ControlKind.LOOK) {
            rectOf(element, box)
            return box.contains(x, y)
        }
        // A touch a little outside still counts: glass has no edges to feel,
        // and a button that needs to be hit exactly is a button that gets
        // missed in a firefight.
        val slack = 6f * density
        return hypot(x - centreX(element), y - centreY(element)) <= radius(element) + slack
    }

    /** Buttons first, the look area last: it sits under everything. */
    private fun elementAt(x: Float, y: Float, includeHidden: Boolean = false): ControlElement? {
        for (element in layout.elements) {
            if (element.kind == ControlKind.LOOK) continue
            if (!element.visible && !includeHidden) continue
            if (hit(element, x, y)) return element
        }
        for (element in layout.elements) {
            if (element.kind != ControlKind.LOOK) continue
            if (!element.visible && !includeHidden) continue
            if (hit(element, x, y)) return element
        }
        return null
    }

    // ---- the state the guest sees -------------------------------------------

    private fun pushState() {
        var buttons = 0
        var leftTrigger = 0
        var rightTrigger = 0
        var leftX = 0f
        var leftY = 0f
        var rightX = 0f
        var rightY = 0f

        for (element in layout.elements) {
            if (!element.visible) continue
            val on = held.contains(element.id) || toggled.contains(element.id)
            when (element.kind) {
                ControlKind.BUTTON -> if (on) {
                    buttons = buttons or (element.value and 0xFFFF)
                    // Multi-Action Merged Button support (e.g. super_cancel triggering RT)
                    if (element.id == "super_cancel") {
                        rightTrigger = 255
                    }
                }
                ControlKind.TRIGGER -> if (on) {
                    if (element.value == 0) leftTrigger = 255 else rightTrigger = 255
                }
                ControlKind.STICK -> {
                    val position = stickPositions[element.id]
                    if (position != null) {
                        if (element.value == 0) {
                            if (inMenuMode) {
                                val px = position[0]
                                val py = position[1]
                                val deadzone = 0.35f
                                if (px > deadzone) buttons = buttons or NativeBridge.RIGHT
                                else if (px < -deadzone) buttons = buttons or NativeBridge.LEFT
                                if (py > deadzone) buttons = buttons or NativeBridge.UP
                                else if (py < -deadzone) buttons = buttons or NativeBridge.DOWN
                            } else {
                                leftX += position[0]
                                leftY += position[1]
                            }
                        } else {
                            rightX += position[0]
                            rightY += position[1]
                        }
                    }
                }
                ControlKind.LOOK -> Unit
            }
        }

        // Auto-sprint integration: when outer-ring deflection threshold is reached,
        // latch Xbox RB (0x0200) sprint bitmask simultaneously
        if (autoSprintLatched && layout.autoSprintEnabled) {
            buttons = buttons or NativeBridge.RB
        }

        for (mod in activeFlickMods) {
            when (mod) {
                "lb" -> buttons = buttons or NativeBridge.LB
                "rt" -> rightTrigger = 255
                "rs_up" -> rightY = 1.0f
                "rs_down" -> rightY = -1.0f
                "rs_right" -> rightX = 1.0f
                "rs_left" -> rightX = -1.0f
            }
        }

        // A macro is playing: the fingers are not heard, so a run repeats exactly.
        if (Macro.playing) return
        val lx = leftX.coerceIn(-1f, 1f); val ly = leftY.coerceIn(-1f, 1f)
        val rx = rightX.coerceIn(-1f, 1f); val ry = rightY.coerceIn(-1f, 1f)
        Macro.record(Macro.TOUCH, buttons, leftTrigger, rightTrigger, lx, ly, rx, ry)
        NativeBridge.nativeTouchState(buttons, leftTrigger, rightTrigger, lx, ly, rx, ry)
    }

    /** Everything up: called when the pad is hidden, edited, or paused. */
    fun releaseEverything() {
        claims.clear()
        stickPositions.clear()
        floatingStickCenters.clear()
        activeTouchPoints.clear()
        buttonDownCoords.clear()
        activeFlickMods.clear()
        buttonPressStartTimes.clear()
        activeButtonDirections.clear()
        skillFlickActive = false
        shotFakeActive = false
        teammateRunActive = false
        autoSprintLatched = false
        held.clear()
        isDefending = false
        // Toggles survive a pause -- a player who was aiming down sights
        // before the phone rang is still aiming when they come back -- but
        // not the editor, which would otherwise leave a bit stuck down.
        if (editing) toggled.clear()
        lookPointer = -1
        if (NativeBridge.isLoaded()) {
            NativeBridge.nativeLookEnd()
            pushState()
        }
        invalidate()
    }

    // ---- touches --------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editing) return editTouch(event)
        return playTouch(event)
    }

    private fun playTouch(event: MotionEvent): Boolean {
        var changed = false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                changed = press(event.getPointerId(index), event.getX(index), event.getY(index))
            }

            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val id = event.getPointerId(index)
                    val x = event.getX(index)
                    val y = event.getY(index)
                    activeTouchPoints[id] = floatArrayOf(x, y)
                    if (id == lookPointer) {
                        for (h in 0 until event.historySize) {
                            sendLook(event.getHistoricalX(index, h), event.getHistoricalY(index, h))
                        }
                        sendLook(x, y)
                        continue
                    }
                    val element = claims[id] ?: continue
                    when (element.kind) {
                        ControlKind.STICK -> { moveStick(element, x, y); changed = true }
                        ControlKind.BUTTON -> {
                            val down = buttonDownCoords[id]
                            if (down != null) {
                                val deltaX = x - down[0]
                                val deltaY = y - down[1]
                                val dist = hypot(deltaX, deltaY)

                                // Loose Radial Distance Tolerance (25px <= deltaR <= 70px)
                                val minThreshold = 25f * density
                                if (dist >= minThreshold) {
                                    val absX = kotlin.math.abs(deltaX)
                                    val absY = kotlin.math.abs(deltaY)
                                    // Hard 4-Direction Sector Snap
                                    val dir = when {
                                        absY > absX && deltaY < 0 -> 1 // UP
                                        absY > absX && deltaY > 0 -> 2 // DOWN
                                        absX >= absY && deltaX < 0 -> 3 // LEFT
                                        absX >= absY && deltaX > 0 -> 4 // RIGHT
                                        else -> 0
                                    }

                                    if (dir != 0 && activeButtonDirections[element.id] != dir) {
                                        activeButtonDirections[element.id] = dir
                                        triggerHaptic()
                                        changed = true

                                        if (element.id == "skill") {
                                            when (dir) {
                                                1 -> showActionBadge("DOUBLE TOUCH")
                                                2 -> showActionBadge("MARSEILLE TURN")
                                                4 -> showActionBadge("STEP-OVER")
                                                3 -> showActionBadge("DRAG BACK")
                                            }
                                        } else if (element.id == "a") {
                                            when (dir) {
                                                1 -> {
                                                    activeFlickMods.add("lb")
                                                    showActionBadge("LOFTED PASS")
                                                }
                                                2 -> {
                                                    activeFlickMods.add("lb")
                                                    teammateRunActive = true
                                                    teammateRunTime = System.currentTimeMillis()
                                                    showActionBadge("GIVE & GO / MANUAL RUN")
                                                }
                                                3, 4 -> {
                                                    activeFlickMods.add("rt")
                                                    showActionBadge("DRIVEN PASS")
                                                }
                                            }
                                        } else if (element.id == "x") {
                                            when (dir) {
                                                1 -> {
                                                    activeFlickMods.add("lb")
                                                    showActionBadge("CHIP SHOT")
                                                }
                                                2 -> {
                                                    activeFlickMods.add("rt")
                                                    showActionBadge("FINESSE SHOT")
                                                }
                                                3, 4 -> {
                                                    activeFlickMods.add("rt")
                                                    showActionBadge("STUNNING SHOT")
                                                }
                                            }
                                        } else if (element.id == "b") {
                                            when (dir) {
                                                1 -> showActionBadge("WHIPPED CROSS")
                                                2 -> showActionBadge("LOW CROSS")
                                                3, 4 -> showActionBadge("EARLY CROSS")
                                            }
                                        } else if (element.id == "y") {
                                            when (dir) {
                                                1 -> {
                                                    activeFlickMods.add("lb")
                                                    showActionBadge("LOFTED THROUGH PASS")
                                                }
                                                2, 3, 4 -> showActionBadge("THROUGH PASS")
                                            }
                                        }
                                    }
                                } else {
                                    activeButtonDirections[element.id] = 0
                                }
                            }
                        }
                        else -> Unit
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                changed = release(event.getPointerId(index))
            }

            MotionEvent.ACTION_CANCEL -> {
                releaseEverything()
                return true
            }
        }
        if (changed) {
            pushState()
            invalidate()
        }
        return true
    }

    private fun triggerHaptic() {
        if (!prefs.vibration) return
        try {
            performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
            )
        } catch (_: Throwable) {}
    }

    private fun press(pointerId: Int, x: Float, y: Float): Boolean {
        activeTouchPoints[pointerId] = floatArrayOf(x, y)
        var element = elementAt(x, y)
        if (element == null && x < width * 0.45f) {
            val stick = layout.find("stick")
            if (stick != null && stick.visible && !claims.values.contains(stick)) {
                floatingStickCenters[stick.id] = floatArrayOf(x, y)
                element = stick

                val now = System.currentTimeMillis()
                if (now - lastLeftTapTime < 300) {
                    triggerHaptic()
                    TouchEventBus.postGesture(TouchEventBus.GestureType.QUICK_STOP, "Face Goal")
                    showActionBadge("QUICK STOP")
                    held.add("lb")
                    postDelayed({
                        held.remove("lb")
                        pushState()
                        invalidate()
                    }, 120)
                }
                lastLeftTapTime = now
            }
        }
        if (element == null) return false

        if (element.kind == ControlKind.LOOK) {
            lookPointer = pointerId
            lookLastX = x
            lookLastY = y
            return false
        }
        triggerHaptic()
        claims[pointerId] = element
        when (element.kind) {
            ControlKind.STICK -> moveStick(element, x, y)
            ControlKind.BUTTON, ControlKind.TRIGGER -> {
                buttonDownCoords[pointerId] = floatArrayOf(x, y)
                buttonPressStartTimes[element.id] = System.currentTimeMillis()
                if (element.toggle) {
                    if (!toggled.remove(element.id)) toggled.add(element.id)
                } else {
                    held.add(element.id)
                }

                when (element.id) {
                    "a" -> {
                        val now = System.currentTimeMillis()
                        if (now - lastPassTapTime < 280) {
                            triggerHaptic()
                            if (isDefending) {
                                TouchEventBus.postGesture(TouchEventBus.GestureType.TEAMMATE_PRESS, "Teammate Press")
                                showActionBadge("TEAMMATE PRESS LOCK")
                                held.add("x")
                                postDelayed({
                                    held.remove("x")
                                    pushState()
                                    invalidate()
                                }, 350)
                            } else {
                                TouchEventBus.postGesture(TouchEventBus.GestureType.GIVE_AND_GO, "Give & Go")
                                showActionBadge("GIVE & GO / MANUAL RUN")
                                teammateRunActive = true
                                teammateRunTime = System.currentTimeMillis()
                                held.add("lb")
                                postDelayed({
                                    held.remove("lb")
                                    pushState()
                                    invalidate()
                                }, 250)
                                postDelayed({
                                    teammateRunActive = false
                                    invalidate()
                                }, 1200)
                            }
                        } else {
                            showActionBadge(if (isDefending) "PRESS" else "PASS")
                        }
                        lastPassTapTime = now
                    }
                    "x" -> showActionBadge(if (isDefending) "SLIDING TACKLE" else "SHOOT")
                    "b" -> showActionBadge(if (isDefending) "CLEAR" else "CROSS")
                    "y" -> showActionBadge(if (isDefending) "GK RUSH" else "THROUGH PASS")
                }

                if (element.id.equals("rt", ignoreCase = true) || element.id.equals("lb", ignoreCase = true)) {
                    isDefending = true
                }
            }
            ControlKind.LOOK -> Unit
        }
        return true
    }

    private fun release(pointerId: Int): Boolean {
        activeTouchPoints.remove(pointerId)
        if (pointerId == lookPointer) {
            lookPointer = -1
            NativeBridge.nativeLookEnd()
            return false
        }
        val element = claims.remove(pointerId) ?: return false
        when (element.kind) {
            ControlKind.STICK -> {
                stickPositions.remove(element.id)
                floatingStickCenters.remove(element.id)
                if (element.value == 0) autoSprintLatched = false
            }
            else -> {
                val down = buttonDownCoords.remove(pointerId)
                // Execute Isolated 4-Way Skill Flick upon ACTION_UP with distance gating
                if (element.id == "skill" && down != null) {
                    val lastPt = activeTouchPoints[pointerId] ?: down
                    val deltaX = lastPt[0] - down[0]
                    val deltaY = lastPt[1] - down[1]
                    val dist = hypot(deltaX, deltaY)
                    if (dist >= 30f * density) {
                        triggerHaptic()
                        val absX = kotlin.math.abs(deltaX)
                        val absY = kotlin.math.abs(deltaY)
                        if (absY > absX) {
                            if (deltaY < 0) {
                                // Flick Up: Double Touch (RS Up)
                                activeFlickMods.add("rs_up")
                                showActionBadge("STEP 1: SETUP ➔ DOUBLE TOUCH")
                            } else {
                                // Flick Down: Marseille Turn (RS Down)
                                activeFlickMods.add("rs_down")
                                showActionBadge("STEP 1: SETUP ➔ MARSEILLE TURN")
                            }
                        } else {
                            if (deltaX > 0) {
                                // Flick Forward / Right: Step-Over / Scissors (RS Right)
                                activeFlickMods.add("rs_right")
                                showActionBadge("STEP 1: SETUP ➔ STEP-OVER")
                            } else {
                                // Flick Back / Left: Drag Back / L-Feint (RS Left)
                                activeFlickMods.add("rs_left")
                                showActionBadge("STEP 1: SETUP ➔ DRAG BACK")
                            }
                        }
                        postDelayed({
                            activeFlickMods.remove("rs_up")
                            activeFlickMods.remove("rs_down")
                            activeFlickMods.remove("rs_right")
                            activeFlickMods.remove("rs_left")
                            skillFlickActive = false
                            pushState()
                            invalidate()
                        }, 250)
                    }
                    skillFlickActive = false
                }

                held.remove(element.id)
                buttonPressStartTimes.remove(element.id)
                activeButtonDirections.remove(element.id)
                if (element.id.equals("rt", ignoreCase = true) || element.id.equals("lb", ignoreCase = true)) {
                    val defStillHeld = held.contains("rt") || held.contains("lb")
                    if (!defStillHeld) isDefending = false
                }
                if (held.isEmpty()) {
                    activeFlickMods.clear()
                    shotFakeActive = false
                }
            }
        }
        return true
    }

    private fun sendLook(x: Float, y: Float) {
        val dx = x - lookLastX
        val dy = y - lookLastY
        lookLastX = x
        lookLastY = y
        if (dx != 0f || dy != 0f) NativeBridge.nativeLookDelta(dx, dy)
    }

    private fun moveStick(element: ControlElement, x: Float, y: Float) {
        val r = radius(element)
        var dx = (x - centreX(element)) / r
        // The guest's Y points up; the screen's points down.
        var dy = -(y - centreY(element)) / r
        val length = hypot(dx, dy)
        if (length < layout.deadzoneThreshold) {
            stickPositions.remove(element.id)
            if (element.value == 0) autoSprintLatched = false
            return
        }
        val clampedLength = if (length > 1f) 1f else length
        val normX = if (length > 1f) dx / length else dx
        val normY = if (length > 1f) dy / length else dy
        stickPositions[element.id] = floatArrayOf(normX * clampedLength, normY * clampedLength)

        // eFootball Outer-Ring Auto-Sprint: compare magnitude with user sprint threshold
        if (element.value == 0 && layout.autoSprintEnabled) {
            val wasSprint = autoSprintLatched
            autoSprintLatched = clampedLength >= layout.sprintThreshold
            if (!wasSprint && autoSprintLatched) {
                triggerHaptic()
            }
        }
    }

    // ---- editing ----------------------------------------------------------------

    fun select(element: ControlElement?) {
        selected = element
        onSelectionChanged?.invoke(element)
        invalidate()
    }

    private fun editTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                // Hidden controls are still reachable in the editor: turning
                // one back on is impossible otherwise.
                val element = elementAt(x, y, includeHidden = true)
                if (element != null) {
                    select(element)
                    dragPointer = event.getPointerId(0)
                    dragElement = element
                    dragOffsetX = centreX(element) - x
                    dragOffsetY = centreY(element) - y
                    dragMoved = false
                } else {
                    select(null)
                    dragElement = null
                    dragPointer = -1
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Two fingers on the selected control resize it.
                val element = dragElement ?: selected
                if (element != null && event.pointerCount == 2) {
                    pinching = true
                    pinchStartDistance = max(1f, spread(event))
                    pinchStartSize = element.sizeDp
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val element = dragElement ?: selected ?: return true
                if (pinching && event.pointerCount >= 2) {
                    val scale = spread(event) / pinchStartDistance
                    element.sizeDp = (pinchStartSize * scale)
                        .coerceIn(minimumSize(element), maximumSize(element))
                    dragMoved = true
                    onSelectionChanged?.invoke(element)
                    invalidate()
                    return true
                }
                val index = event.findPointerIndex(dragPointer)
                if (index < 0) return true
                var x = event.getX(index) + dragOffsetX
                var y = event.getY(index) + dragOffsetY
                if (snapToGrid) {
                    val step = 24f * density
                    x = (x / step).roundToInt() * step
                    y = (y / step).roundToInt() * step
                }
                // Kept on screen, with a margin so nothing hides under the
                // gesture bar or a punch-hole camera.
                val margin = 8f * density
                element.xFrac = (x.coerceIn(margin, width - margin)) / max(1, width)
                element.yFrac = (y.coerceIn(margin, height - margin)) / max(1, height)
                dragMoved = true
                invalidate()
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) pinching = false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMoved) {
                    // Dropped on (or grown into) a neighbour: step it aside.
                    (dragElement ?: selected)?.let { keepApart(it) }
                    onLayoutEdited?.invoke()
                }
                dragPointer = -1
                dragElement = null
                pinching = false
                dragMoved = false
            }
        }
        return true
    }

    /**
     * Moves [element] off any shown control it covers, so two controls never
     * share a touch: each pass pushes it straight away from the nearest overlap
     * until there is a small gap (more than the touch slack in [hit]), keeping it
     * on screen. A crowded spot can leave it touching after the last pass; that
     * is rare, and the player can still move it by hand.
     */
    fun keepApart(element: ControlElement) {
        if (width == 0 || height == 0 || element.kind == ControlKind.LOOK) return
        val gap = 14f * density
        val margin = 8f * density
        repeat(24) {
            var moved = false
            for (other in layout.elements) {
                if (other === element || !other.visible || other.kind == ControlKind.LOOK) continue
                val dx = centreX(element) - centreX(other)
                val dy = centreY(element) - centreY(other)
                val distance = hypot(dx, dy)
                val needed = radius(element) + radius(other) + gap
                if (distance >= needed) continue
                // Exactly on top: push towards the screen's middle, which has room.
                val ux = if (distance > 0.5f) dx / distance else if (centreX(element) > width / 2f) -1f else 1f
                val uy = if (distance > 0.5f) dy / distance else 0f
                val push = needed - distance
                val r = radius(element) + margin
                val x = (centreX(element) + ux * push).coerceIn(r, max(r, width - r))
                val y = (centreY(element) + uy * push).coerceIn(r, max(r, height - r))
                element.xFrac = x / width
                element.yFrac = y / height
                moved = true
            }
            if (!moved) {
                invalidate()
                return
            }
        }
        invalidate()
    }

    private fun minimumSize(element: ControlElement): Float =
        if (element.kind == ControlKind.LOOK) 120f else 36f

    private fun maximumSize(element: ControlElement): Float =
        if (element.kind == ControlKind.LOOK) 900f else 200f

    private fun spread(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 1f
        return hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
    }

    // ---- drawing ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        for (element in layout.elements) {
            if (!element.visible && !editing) continue
            val faded = editing && !element.visible
            val alpha = if (editing) max(0.28f, element.alpha) else element.alpha
            when (element.kind) {
                ControlKind.LOOK -> drawLook(canvas, element, faded)
                ControlKind.STICK -> drawStick(canvas, element, alpha, faded)
                ControlKind.BUTTON, ControlKind.TRIGGER -> drawButton(canvas, element, alpha, faded)
            }
            if (editing && element === selected) drawSelection(canvas, element)
        }

        // 1. Draw touch feedback tracker overlay (glow rings under active fingers with micro-expansion)
        if (!editing) {
            val pulseRadius = (28f + (kotlin.math.sin(System.currentTimeMillis() * 0.008) * 3f).toFloat()) * density
            for (entry in activeTouchPoints.values) {
                val tx = entry[0]
                val ty = entry[1]
                fill.color = Color.argb(40, 56, 189, 248)
                canvas.drawCircle(tx, ty, pulseRadius, fill)
                stroke.color = Color.argb(130, 56, 189, 248)
                stroke.strokeWidth = 1.5f * density
                canvas.drawCircle(tx, ty, pulseRadius, stroke)
            }
        }

        // 1c. Draw Teammate Run Target Indicator (On-Pitch Layer) during Give-and-Go
        if (teammateRunActive) {
            val runAge = (System.currentTimeMillis() - teammateRunTime).toFloat() / 1200f
            if (runAge < 1f) {
                val chevAlpha = ((1f - runAge) * 230).toInt().coerceIn(0, 255)
                val chevX = width * 0.65f + (runAge * 50f * density)
                val chevY = height * 0.42f
                val chevSize = 16f * density

                stroke.color = Color.argb(chevAlpha, 34, 197, 94)
                stroke.strokeWidth = 3f * density
                canvas.drawLine(chevX - chevSize, chevY - chevSize * 0.8f, chevX, chevY, stroke)
                canvas.drawLine(chevX - chevSize, chevY + chevSize * 0.8f, chevX, chevY, stroke)

                text.textSize = 10f * density
                text.color = Color.argb(chevAlpha, 34, 197, 94)
                canvas.drawText("RUN", chevX - chevSize * 0.5f, chevY - chevSize * 1.2f, text)
                invalidate()
            }
        }

        // 1b. Draw localized vector glow trail on the SKILL button face
        if (skillFlickActive) {
            stroke.color = Color.argb(220, 245, 158, 11)
            stroke.strokeWidth = 3f * density
            canvas.drawLine(skillFlickVector[0], skillFlickVector[1], skillFlickVector[2], skillFlickVector[3], stroke)
            fill.color = Color.argb(255, 251, 191, 36)
            canvas.drawCircle(skillFlickVector[2], skillFlickVector[3], 5f * density, fill)
        }

        // 2. Draw directional micro-flick ripples & trails
        val iterator = flickRipples.iterator()
        while (iterator.hasNext()) {
            val rip = iterator.next()
            val startX = rip[0]
            val startY = rip[1]
            val dx = rip[2]
            val dy = rip[3]
            var life = rip[4]

            if (life <= 0f) {
                iterator.remove()
                continue
            }

            val alpha = (life * 220).toInt().coerceIn(0, 255)
            notchPaint.color = Color.argb(alpha, 56, 189, 248)
            notchPaint.strokeWidth = max(2f, 3.5f * density)
            canvas.drawLine(startX, startY, startX + dx, startY + dy, notchPaint)

            fill.color = Color.argb(alpha, 255, 255, 255)
            canvas.drawCircle(startX + dx, startY + dy, 4.5f * density, fill)

            rip[4] -= 0.12f
            invalidate()
        }

        // 3. Universal Action Toast Overlay (Top-Center HUD at Y = 10% screen height, 600ms animated fade)
        val now = System.currentTimeMillis()
        if (actionToastText.isNotEmpty() && now - actionToastTime < 600) {
            val age = (now - actionToastTime).toFloat() / 600f
            val toastAlpha = ((1f - age) * 255).toInt().coerceIn(0, 255)

            val badgeX = width * 0.5f
            val badgeY = height * 0.10f
            val badgeW = max(130f * density, (actionToastText.length * 9.5f + 28f) * density)
            val badgeH = 26f * density

            tempRect.set(badgeX - badgeW * 0.5f, badgeY - badgeH * 0.5f, badgeX + badgeW * 0.5f, badgeY + badgeH * 0.5f)
            fill.color = Color.argb((toastAlpha * 0.85f).toInt(), 15, 23, 42)
            canvas.drawRoundRect(tempRect, 14f * density, 14f * density, fill)

            stroke.color = Color.argb((toastAlpha * 0.90f).toInt(), 56, 189, 248)
            stroke.strokeWidth = 1.2f * density
            canvas.drawRoundRect(tempRect, 14f * density, 14f * density, stroke)

            text.textSize = 12f * density
            text.color = Color.argb(toastAlpha, 241, 245, 249)
            canvas.drawText(actionToastText, badgeX, badgeY - (text.descent() + text.ascent()) * 0.5f, text)
            invalidate()
        }
    }

    private fun shade(alpha: Float, pressed: Boolean, faded: Boolean): Int {
        val a = (alpha * (if (faded) 0.45f else 1f) * 255).toInt().coerceIn(0, 255)
        return if (pressed) Color.argb(min(255, a + 70), 255, 255, 255)
        else Color.argb(a, 235, 238, 242)
    }

    private fun isOn(element: ControlElement): Boolean =
        held.contains(element.id) || toggled.contains(element.id)

    companion object {
        // The Xbox 360 pad colours, because the game's own prompts use them
        private const val COLOR_A = 0xFF107C10.toInt()      // Emerald Green
        private const val COLOR_A_GLOW = 0xFF22C55E.toInt()
        private const val COLOR_B = 0xFFB91C1C.toInt()      // Ruby Red
        private const val COLOR_B_GLOW = 0xFFEF4444.toInt()
        private const val COLOR_X = 0xFF1D4ED8.toInt()      // Royal Blue
        private const val COLOR_X_GLOW = 0xFF3B82F6.toInt()
        private const val COLOR_Y = 0xFFD97706.toInt()      // Amber Yellow
        private const val COLOR_Y_GLOW = 0xFFF59E0B.toInt()
        private const val COLOR_RT = 0xFFE11D48.toInt()     // Right trigger
        private const val COLOR_RT_GLOW = 0xFFFB7185.toInt()
        private const val COLOR_LT = 0xFF0EA5E9.toInt()     // Left trigger
        private const val COLOR_LT_GLOW = 0xFF38BDF8.toInt()
        private const val COLOR_BUMPER_GLOW = 0xFF94A3B8.toInt()

        // The PlayStation symbol colours: blue cross, red circle, pink square, green triangle
        private const val PS_CROSS = 0xFF3B5BA9.toInt()
        private const val PS_CROSS_GLOW = 0xFF7DA2F0.toInt()
        private const val PS_CIRCLE = 0xFFB4232F.toInt()
        private const val PS_CIRCLE_GLOW = 0xFFF0626C.toInt()
        private const val PS_SQUARE = 0xFF9D3F8C.toInt()
        private const val PS_SQUARE_GLOW = 0xFFE58FD2.toInt()
        private const val PS_TRIANGLE = 0xFF1E8C6B.toInt()
        private const val PS_TRIANGLE_GLOW = 0xFF4FD1A5.toInt()
    }

    private fun drawButton(canvas: Canvas, element: ControlElement, alpha: Float, faded: Boolean) {
        val r = radius(element)
        val cx = centreX(element)
        val cy = centreY(element)
        val pressed = isOn(element)
        val fadeFactor = if (faded) 0.45f else 1f

        val id = element.id.lowercase()
        val isA = id == "a"
        val isB = id == "b"
        val isX = id == "x"
        val isY = id == "y"
        val isFaceButton = isA || isB || isX || isY
        val isRT = id == "rt"
        val isLT = id == "lt"
        val isBumper = id == "lb" || id == "rb"
        val isThumbClick = id == "l3" || id == "r3"
        val isMenu = id == "start" || id == "back"

        val label = ControlLayout.labelFor(element, playStation)
        val coreColor = when {
            isA -> if (playStation) PS_CROSS else COLOR_A
            isB -> if (playStation) PS_CIRCLE else COLOR_B
            isX -> if (playStation) PS_SQUARE else COLOR_X
            isY -> if (playStation) PS_TRIANGLE else COLOR_Y
            isRT -> COLOR_RT
            isLT -> COLOR_LT
            isBumper -> 0xFF334155.toInt()
            else -> 0xFF1E242C.toInt()
        }

        val glowColor = when {
            isA -> if (playStation) PS_CROSS_GLOW else COLOR_A_GLOW
            isB -> if (playStation) PS_CIRCLE_GLOW else COLOR_B_GLOW
            isX -> if (playStation) PS_SQUARE_GLOW else COLOR_X_GLOW
            isY -> if (playStation) PS_TRIANGLE_GLOW else COLOR_Y_GLOW
            isRT -> COLOR_RT_GLOW
            isLT -> COLOR_LT_GLOW
            isBumper -> COLOR_BUMPER_GLOW
            else -> 0xFF94A3B8.toInt()
        }

        // Refined pressed-state aura feedback
        if (pressed) {
            fill.color = Color.argb(
                (alpha * fadeFactor * 90).toInt().coerceIn(0, 255),
                Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor),
            )
            canvas.drawCircle(cx, cy, r * 1.08f, fill)
        }

        if (isFaceButton) {
            // Modernized eFootball-style Frosted Action Button
            // 1. Dark high-contrast outer backdrop for readability on bright turf
            fill.color = Color.argb(
                (alpha * fadeFactor * 180).toInt().coerceIn(0, 255),
                12, 16, 24,
            )
            canvas.drawCircle(cx, cy, r, fill)

            // 2. Subtle, clean accent-colored outer border
            stroke.color = Color.argb(
                (alpha * fadeFactor * (if (pressed) 255 else 175)).toInt().coerceIn(0, 255),
                Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor),
            )
            stroke.strokeWidth = max(1.5f, r * 0.045f)
            canvas.drawCircle(cx, cy, r - stroke.strokeWidth * 0.5f, stroke)

            // Perimeter Power Fill Arc: circular charge bar filling based on hold duration (0 to 800ms)
            val startTime = buttonPressStartTimes[element.id]
            if (pressed && startTime != null) {
                val holdDuration = System.currentTimeMillis() - startTime
                val chargeRatio = (holdDuration / 800f).coerceIn(0f, 1f)
                if (chargeRatio > 0.05f) {
                    val sweepAngle = chargeRatio * 360f
                    val arcPaint = stroke
                    val origColor = arcPaint.color
                    val origWidth = arcPaint.strokeWidth
                    arcPaint.strokeWidth = max(2.5f, r * 0.08f)
                    arcPaint.color = when {
                        chargeRatio < 0.5f -> Color.argb(240, 34, 197, 94)    // Green
                        chargeRatio < 0.85f -> Color.argb(240, 234, 179, 8)   // Yellow
                        else -> Color.argb(240, 239, 68, 68)                  // Red
                    }
                    tempRect.set(cx - r + arcPaint.strokeWidth * 0.5f, cy - r + arcPaint.strokeWidth * 0.5f, cx + r - arcPaint.strokeWidth * 0.5f, cy + r - arcPaint.strokeWidth * 0.5f)
                    canvas.drawArc(tempRect, -90f, sweepAngle, false, arcPaint)
                    arcPaint.color = origColor
                    arcPaint.strokeWidth = origWidth
                    invalidate()
                }
            }

            // 3. Modern dark translucent core tinted with accent color
            val coreR = r * 0.90f
            val tintAlpha = if (pressed) 140 else 55
            fill.color = Color.argb(
                (alpha * fadeFactor * tintAlpha).toInt().coerceIn(0, 255),
                Color.red(coreColor), Color.green(coreColor), Color.blue(coreColor),
            )
            canvas.drawCircle(cx, cy, coreR, fill)

            // 4. Restrained modern specular top rim highlight (subtle crest, replacing heavy gloss arc)
            highlightPaint.color = Color.argb(
                (alpha * fadeFactor * (if (pressed) 90 else 50)).toInt().coerceIn(0, 255),
                255, 255, 255,
            )
            highlightPaint.strokeWidth = max(1.0f, coreR * 0.04f)
            val arcInset = coreR * 0.10f
            tempRect.set(cx - coreR + arcInset, cy - coreR + arcInset * 0.4f, cx + coreR - arcInset, cy + coreR - arcInset * 1.6f)
            canvas.drawArc(tempRect, -150f, 120f, false, highlightPaint)

            // 4. Just the pad's own name, as on a real controller: the
            //    PlayStation symbol, or the Xbox letter.
            val ink = Color.argb((alpha * fadeFactor * 255).toInt().coerceIn(0, 255), 255, 255, 255)
            val shadow = Color.argb((alpha * fadeFactor * 140).toInt().coerceIn(0, 255), 0, 0, 0)
            val actionSubtitle = when {
                isDefending && element.sub2.isNotEmpty() -> element.sub2
                element.sub.isNotEmpty() -> element.sub
                id == "a" -> if (isDefending) "PRESSURE" else "PASS"
                id == "x" -> if (isDefending) "TACKLE" else "SHOOT"
                id == "b" -> if (isDefending) "CLEAR" else "CROSS"
                id == "y" -> if (isDefending) "GK RUSH" else "THROUGH"
                else -> ""
            }

            val glyphR = coreR * 0.90f
            if (playStation) {
                val size = glyphR * (if (actionSubtitle.isNotEmpty()) 0.35f else 0.40f)
                val symY = if (actionSubtitle.isNotEmpty()) cy - glyphR * 0.12f else cy
                drawPsSymbol(canvas, id, cx, symY + 1.2f * density, size, shadow)
                drawPsSymbol(canvas, id, cx, symY, size, ink)
            } else {
                text.textSize = glyphR * (if (actionSubtitle.isNotEmpty()) 0.78f else 0.95f)
                val baseLetterY = if (actionSubtitle.isNotEmpty()) cy - glyphR * 0.10f else cy
                val letterY = baseLetterY - (text.descent() + text.ascent()) * 0.5f
                text.color = shadow
                canvas.drawText(label, cx, letterY + 1.2f * density, text)
                text.color = ink
                canvas.drawText(label, cx, letterY, text)
            }

            if (actionSubtitle.isNotEmpty()) {
                subText.textSize = max(8f, glyphR * 0.28f)
                val subY = cy + glyphR * 0.58f
                subText.color = Color.argb((alpha * fadeFactor * 140).toInt().coerceIn(0, 255), 0, 0, 0)
                canvas.drawText(actionSubtitle, cx, subY + 1f * density, subText)
                subText.color = Color.argb((alpha * fadeFactor * 220).toInt().coerceIn(0, 255), 241, 245, 249)
                canvas.drawText(actionSubtitle, cx, subY, subText)
            }

            // Directional Arrow Feedback: 4 clean directional arrows (UP, DOWN, LEFT, RIGHT)
            val activeDir = activeButtonDirections[element.id] ?: 0
            val arrowDist = r * 0.82f
            val arrowLen = 6f * density

            fun drawArrow(arrowDir: Int, ax: Float, ay: Float, ddx: Float, ddy: Float) {
                val isActive = activeDir == arrowDir
                val arrowAlpha = if (isActive) 255 else (alpha * fadeFactor * 70).toInt().coerceIn(0, 255)
                notchPaint.color = if (isActive) Color.argb(arrowAlpha, 56, 189, 248) else Color.argb(arrowAlpha, 148, 163, 184)
                notchPaint.strokeWidth = if (isActive) 2.5f * density else 1.2f * density
                canvas.drawLine(ax - ddy * arrowLen * 0.6f, ay - ddx * arrowLen * 0.6f, ax + ddx * arrowLen, ay + ddy * arrowLen, notchPaint)
                canvas.drawLine(ax + ddy * arrowLen * 0.6f, ay + ddx * arrowLen * 0.6f, ax + ddx * arrowLen, ay + ddy * arrowLen, notchPaint)
            }

            // UP (1)
            drawArrow(1, cx, cy - arrowDist, 0f, -1f)
            // DOWN (2)
            drawArrow(2, cx, cy + arrowDist, 0f, 1f)
            // LEFT (3)
            drawArrow(3, cx - arrowDist, cy, -1f, 0f)
            // RIGHT (4)
            drawArrow(4, cx + arrowDist, cy, 1f, 0f)
        } else if (isRT || isLT || isBumper) {
            // eFootball Frosted Shoulder & Dash Buttons (RB DASH / RT MATCH UP / LB SWITCH / LT)
            fill.color = Color.argb((alpha * fadeFactor * 180).toInt().coerceIn(0, 255), 12, 16, 24)
            canvas.drawCircle(cx, cy, r, fill)

            stroke.color = Color.argb(
                (alpha * fadeFactor * (if (pressed) 255 else 175)).toInt().coerceIn(0, 255),
                Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor),
            )
            stroke.strokeWidth = max(1.5f, r * 0.045f)
            canvas.drawCircle(cx, cy, r - stroke.strokeWidth * 0.5f, stroke)

            val coreR = r * 0.90f
            val tintAlpha = if (pressed) 140 else 55
            fill.color = Color.argb(
                (alpha * fadeFactor * tintAlpha).toInt().coerceIn(0, 255),
                Color.red(coreColor), Color.green(coreColor), Color.blue(coreColor),
            )
            canvas.drawCircle(cx, cy, coreR, fill)

            // Specular top crest
            highlightPaint.color = Color.argb((alpha * fadeFactor * (if (pressed) 90 else 50)).toInt().coerceIn(0, 255), 255, 255, 255)
            highlightPaint.strokeWidth = max(1.0f, coreR * 0.04f)
            val arcInset = coreR * 0.10f
            tempRect.set(cx - coreR + arcInset, cy - coreR + arcInset * 0.4f, cx + coreR - arcInset, cy + coreR - arcInset * 1.6f)
            canvas.drawArc(tempRect, -150f, 120f, false, highlightPaint)

            val actionSubtitle = when {
                isDefending && element.sub2.isNotEmpty() -> element.sub2
                element.sub.isNotEmpty() -> element.sub
                id == "rb" -> "DASH"
                id == "rt" -> "MATCH UP"
                id == "lb" -> "SWITCH"
                else -> ""
            }

            val ink = Color.argb((alpha * fadeFactor * 255).toInt().coerceIn(0, 255), 255, 255, 255)
            val shadow = Color.argb((alpha * fadeFactor * 140).toInt().coerceIn(0, 255), 0, 0, 0)
            text.textSize = coreR * (if (actionSubtitle.isNotEmpty()) 0.65f else 0.82f)
            val baseLetterY = if (actionSubtitle.isNotEmpty()) cy - coreR * 0.10f else cy
            val letterY = baseLetterY - (text.descent() + text.ascent()) * 0.5f
            text.color = shadow
            canvas.drawText(label, cx, letterY + 1.2f * density, text)
            text.color = ink
            canvas.drawText(label, cx, letterY, text)

            if (actionSubtitle.isNotEmpty()) {
                subText.textSize = max(7.5f, coreR * 0.25f)
                val subY = cy + coreR * 0.58f
                subText.color = Color.argb((alpha * fadeFactor * 140).toInt().coerceIn(0, 255), 0, 0, 0)
                canvas.drawText(actionSubtitle, cx, subY + 1f * density, subText)
                subText.color = Color.argb((alpha * fadeFactor * 220).toInt().coerceIn(0, 255), 241, 245, 249)
                canvas.drawText(actionSubtitle, cx, subY, subText)
            }
        } else if (isThumbClick) {
            // Thumbstick Click (L3 / R3)
            val fillA = (alpha * fadeFactor * (if (pressed) 180 else 80)).toInt().coerceIn(0, 255)
            fill.color = Color.argb(fillA, 24, 30, 38)
            canvas.drawCircle(cx, cy, r, fill)

            stroke.color = Color.argb(
                (alpha * fadeFactor * (if (pressed) 230 else 130)).toInt().coerceIn(0, 255),
                100, 116, 139,
            )
            stroke.strokeWidth = max(1.5f, r * 0.05f)
            canvas.drawCircle(cx, cy, r - stroke.strokeWidth * 0.5f, stroke)

            val tag = element.label

            text.textSize = r * 0.50f
            text.color = Color.WHITE
            canvas.drawText(tag, cx, cy - (text.descent() + text.ascent()) * 0.5f, text)
        } else if (isMenu) {
            // Start (▶) / Back (◀◀)
            val fillA = (alpha * fadeFactor * (if (pressed) 180 else 75)).toInt().coerceIn(0, 255)
            fill.color = Color.argb(fillA, 22, 27, 34)
            canvas.drawCircle(cx, cy, r, fill)

            stroke.color = Color.argb(
                (alpha * fadeFactor * (if (pressed) 230 else 120)).toInt().coerceIn(0, 255),
                100, 116, 139,
            )
            stroke.strokeWidth = max(1.5f, r * 0.045f)
            canvas.drawCircle(cx, cy, r - stroke.strokeWidth * 0.5f, stroke)

            text.textSize = r * (if (label.length > 5) 0.26f else 0.32f)
            text.color = Color.WHITE
            canvas.drawText(label, cx, cy - (text.descent() + text.ascent()) * (if (element.sub.isEmpty()) 0.5f else 1.1f), text)
            if (element.sub.isNotEmpty()) {
                subText.textSize = r * 0.24f
                subText.color = Color.argb((alpha * fadeFactor * 200).toInt().coerceIn(0, 255), 148, 163, 184)
                canvas.drawText(element.sub, cx, cy + r * 0.45f, subText)
            }
        } else {
            // Fallback general button
            fill.color = Color.argb((alpha * fadeFactor * (if (pressed) 160 else 75)).toInt().coerceIn(0, 255), 18, 22, 28)
            canvas.drawCircle(cx, cy, r, fill)
            stroke.color = shade(alpha, pressed, faded)
            stroke.strokeWidth = max(1.5f, r * 0.055f)
            canvas.drawCircle(cx, cy, r - stroke.strokeWidth * 0.5f, stroke)
            text.color = shade(alpha, pressed, faded)
            text.textSize = r * (if (element.label.length > 2) 0.52f else 0.8f)
            canvas.drawText(element.label, cx, cy - (text.descent() + text.ascent()) * 0.5f, text)
        }
    }

    /** The PlayStation face symbol for a face button's id, drawn as lines so no font is needed. */
    private fun drawPsSymbol(canvas: Canvas, id: String, cx: Float, cy: Float, size: Float, color: Int) {
        highlightPaint.color = color
        highlightPaint.strokeWidth = max(1.5f, size * 0.32f)
        when (id) {
            "a" -> {
                canvas.drawLine(cx - size, cy - size, cx + size, cy + size, highlightPaint)
                canvas.drawLine(cx - size, cy + size, cx + size, cy - size, highlightPaint)
            }
            "b" -> canvas.drawCircle(cx, cy, size, highlightPaint)
            "x" -> canvas.drawRect(cx - size * 0.9f, cy - size * 0.9f, cx + size * 0.9f, cy + size * 0.9f, highlightPaint)
            "y" -> {
                path.reset()
                path.moveTo(cx, cy - size * 1.05f)
                path.lineTo(cx + size * 1.1f, cy + size * 0.85f)
                path.lineTo(cx - size * 1.1f, cy + size * 0.85f)
                path.close()
                canvas.drawPath(path, highlightPaint)
            }
        }
    }

    private fun drawStick(canvas: Canvas, element: ControlElement, alpha: Float, faded: Boolean) {
        val r = radius(element)
        val cx = centreX(element)
        val cy = centreY(element)
        val fadeFactor = if (faded) 0.45f else 1f
        val position = stickPositions[element.id]
        val isDeflected = position != null

        // 1. Outer socket base (metallic dish)
        fill.color = Color.argb((alpha * fadeFactor * 75).toInt().coerceIn(0, 255), 16, 20, 26)
        canvas.drawCircle(cx, cy, r, fill)

        // Outer socket rim stroke & Left Stick Sprint Edge-Glow
        if (autoSprintLatched && element.value == 0) {
            val pulse = (kotlin.math.sin(System.currentTimeMillis() * 0.015) * 40).toInt()
            val glowA = (215 + pulse).coerceIn(160, 255)
            stroke.color = Color.argb(glowA, 245, 158, 11) // Vibrant amber sprint glow
            stroke.strokeWidth = max(3f, r * 0.07f)
            canvas.drawCircle(cx, cy, r - stroke.strokeWidth * 0.5f, stroke)
            invalidate()
        } else {
            stroke.color = Color.argb((alpha * fadeFactor * 130).toInt().coerceIn(0, 255), 45, 55, 70)
            stroke.strokeWidth = max(1.5f, r * 0.035f)
            canvas.drawCircle(cx, cy, r - stroke.strokeWidth * 0.5f, stroke)
        }

        // 4 Cardinal Guide Notches on the outer socket perimeter (Xbox stick housing style)
        val notchLen = r * 0.10f
        notchPaint.color = Color.argb((alpha * fadeFactor * 160).toInt().coerceIn(0, 255), 70, 85, 105)
        notchPaint.strokeWidth = max(1.5f, 2f * density)
        // Top
        canvas.drawLine(cx, cy - r, cx, cy - r + notchLen, notchPaint)
        // Bottom
        canvas.drawLine(cx, cy + r - notchLen, cx, cy + r, notchPaint)
        // Left
        canvas.drawLine(cx - r, cy, cx - r + notchLen, cy, notchPaint)
        // Right
        canvas.drawLine(cx + r - notchLen, cy, cx + r, cy, notchPaint)

        // 2. Knob position
        val knobX = cx + (position?.get(0) ?: 0f) * r * 0.62f
        val knobY = cy - (position?.get(1) ?: 0f) * r * 0.62f

        // Directional deflection vector glow
        if (isDeflected) {
            val stickDx = knobX - cx
            val stickDy = knobY - cy
            val dist = hypot(stickDx, stickDy)
            if (dist > 4f * density) {
                // Outer subtle radiant trail
                notchPaint.color = Color.argb((alpha * fadeFactor * 90).toInt().coerceIn(0, 255), 34, 197, 94)
                notchPaint.strokeWidth = max(3f, 4.5f * density)
                canvas.drawLine(cx, cy, knobX, knobY, notchPaint)

                // High-contrast inner direction line
                notchPaint.color = Color.argb((alpha * fadeFactor * 210).toInt().coerceIn(0, 255), 255, 255, 255)
                notchPaint.strokeWidth = max(1.5f, 1.8f * density)
                canvas.drawLine(cx, cy, knobX, knobY, notchPaint)
            }
        }

        // 3. Xbox 360 Thumbstick Cap / Knob
        val knobR = r * 0.38f

        // Outer rubber traction ring
        fill.color = Color.argb((alpha * fadeFactor * (if (isDeflected) 240 else 210)).toInt().coerceIn(0, 255), 36, 42, 52)
        canvas.drawCircle(knobX, knobY, knobR, fill)

        // Raised outer rim stroke
        stroke.color = Color.argb((alpha * fadeFactor * (if (isDeflected) 220 else 160)).toInt().coerceIn(0, 255), 68, 78, 94)
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
        if (!isDeflected) {
            subText.textSize = dishR * 0.34f
            subText.color = Color.argb((alpha * fadeFactor * 130).toInt().coerceIn(0, 255), 148, 163, 184)
            canvas.drawText(ControlLayout.labelFor(element, playStation), knobX, knobY - (subText.descent() + subText.ascent()) * 0.5f, subText)
        }

        // 5. The 4 Signature Xbox Rubber Grip Dimples/Dots (Top, Bottom, Left, Right)
        val dotOffset = knobR * 0.44f
        val dotRadius = max(2f, 2.8f * density)

        dimplePaint.style = Paint.Style.FILL
        dimplePaint.color = Color.argb((alpha * fadeFactor * (if (isDeflected) 230 else 170)).toInt().coerceIn(0, 255), 88, 100, 118)

        // Top dot
        canvas.drawCircle(knobX, knobY - dotOffset, dotRadius, dimplePaint)
        // Bottom dot
        canvas.drawCircle(knobX, knobY + dotOffset, dotRadius, dimplePaint)
        // Left dot
        canvas.drawCircle(knobX - dotOffset, knobY, dotRadius, dimplePaint)
        // Right dot
        canvas.drawCircle(knobX + dotOffset, knobY, dotRadius, dimplePaint)
    }

    private fun drawLook(canvas: Canvas, element: ControlElement, faded: Boolean) {
        // Invisible while playing: it is a region, not a control.
        if (!editing) return
        rectOf(element, box)
        dashed.color = if (faded) Color.argb(90, 120, 170, 255) else Color.argb(170, 120, 170, 255)
        canvas.drawRoundRect(box, 14f * density, 14f * density, dashed)
        text.color = dashed.color
        text.textSize = 15f * density
        canvas.drawText(element.label, box.centerX(), box.centerY(), text)
    }

    private fun drawSelection(canvas: Canvas, element: ControlElement) {
        stroke.color = Color.argb(230, 120, 200, 120)
        stroke.strokeWidth = 2.5f * density
        if (element.kind == ControlKind.LOOK) {
            rectOf(element, box)
            box.inset(-6f * density, -6f * density)
            canvas.drawRoundRect(box, 16f * density, 16f * density, stroke)
        } else {
            canvas.drawCircle(centreX(element), centreY(element), radius(element) + 7f * density, stroke)
        }
    }

    /** Used by the editor's panel to keep the drawing in step with a slider. */
    fun refresh() = invalidate()
}
