package com.pes2017.recomp

import kotlin.math.hypot


/**
 * The gestures the pad has to remember between touch samples: the sprint
 * latch, the pass double tap, and what a bound swipe does when it is more than
 * one bit. All of it is arithmetic on numbers the overlay already has, so it
 * lives here rather than in it, and a test can check every edge without a
 * phone.
 */

/**
 * A button a swipe can hold beside its primary bit. Bumpers are pad bits;
 * triggers are the two analogue axes, which is why they are their own names
 * rather than bits.
 */
enum class SwipeModifier {
    NONE,
    LB,
    RB,
    LT,
    RT,
    ;

    /** The pad bit to hold, or 0 for none and for the triggers. */
    val bit: Int
        get() = when (this) {
            LB -> NativeBridge.LB
            RB -> NativeBridge.RB
            else -> 0
        }

    val isTrigger: Boolean get() = this == LT || this == RT
}

/**
 * A stick a swipe can throw instead of pressing a button.
 *
 * Part of PES 2017's vocabulary is stick travel rather than a button: the
 * manual pass flick, a knock-on, the right stick's skill moves. The pad can
 * send one as a full deflection of that stick, held for the action's dwell and
 * then returned to neutral, which is what the title reads as a single flick.
 * Y is the guest's, so up is positive.
 */
enum class SwipeFlick(val x: Float, val y: Float) {
    NONE(0f, 0f),
    LS_UP(0f, 1f),
    LS_DOWN(0f, -1f),
    LS_LEFT(-1f, 0f),
    LS_RIGHT(1f, 0f),
    RS_UP(0f, 1f),
    RS_DOWN(0f, -1f),
    RS_LEFT(-1f, 0f),
    RS_RIGHT(1f, 0f),
    ;

    /** The left stick rather than the right one. */
    val isLeft: Boolean
        get() = this == LS_UP || this == LS_DOWN || this == LS_LEFT || this == LS_RIGHT

    val bound: Boolean get() = this != NONE
}

/**
 * What one swipe direction sends.
 *
 * A plain bit is the original binding. A modifier is held slightly before the
 * primary bit and released with it, which is how PES 2017's combinations are
 * performed (LB + pass, R2 during the shot gauge, and so on). A double tap is
 * two quick presses of the primary bit, for the commands the title reads as a
 * repeated tap (the low cross, the knock-on). A flick is a full throw of one
 * stick, or of one stick while a modifier is held.
 *
 * [holdMs] is how long the action is held before it is let go: the default is
 * the poll-safe beat the sequences have always used, and a binding may ask for
 * a longer one where the title needs the input to stay down.
 */
data class SwipeAction(
    val bit: Int = 0,
    val modifier: SwipeModifier = SwipeModifier.NONE,
    val doubleTap: Boolean = false,
    val flick: SwipeFlick = SwipeFlick.NONE,
    val holdMs: Long = SwipeSequence.HOLD_MS,
) {
    val bound: Boolean get() = bit != 0 || flick != SwipeFlick.NONE

    companion object {
        val NONE = SwipeAction()

        fun of(bit: Int): SwipeAction = SwipeAction(bit = bit)

        fun held(modifier: SwipeModifier, bit: Int): SwipeAction =
            SwipeAction(bit = bit, modifier = modifier)

        fun double(bit: Int): SwipeAction = SwipeAction(bit = bit, doubleTap = true)

        /** A full throw of one stick. */
        fun flick(target: SwipeFlick): SwipeAction = SwipeAction(flick = target)

        /** A full throw of one stick while [modifier] is held. */
        fun heldFlick(modifier: SwipeModifier, target: SwipeFlick): SwipeAction =
            SwipeAction(modifier = modifier, flick = target)
    }
}

/** One timed press of the pad that a compound swipe performs. */
data class SwipeStep(
    val delayMs: Long,
    val buttons: Int,
    val leftTrigger: Boolean = false,
    val rightTrigger: Boolean = false,
    /** A stick this step holds at full deflection, or [SwipeFlick.NONE]. */
    val flick: SwipeFlick = SwipeFlick.NONE,
)

/**
 * A compound swipe as the steps the overlay schedules.
 *
 * A modifier is pressed a frame before the button (the title reads the modifier
 * first, as a hand does), both are held for a beat -- long enough for the
 * title's next poll -- and the last step clears everything. The double tap is
 * two presses with a gap and a clearing step at the end. Every sequence ends
 * with an all-clear step, which is what makes a cancelled gesture safe: the
 * overlay can also call [ComboHold.clear] at any point and nothing is left
 * down.
 */
object SwipeSequence {

    /**
     * Every beat in a sequence is the same, and short: 50 ms is two or three
     * polls at the title's frame rate, which is enough for a press to be seen and
     * short enough that a queued sequence of them still reads as one flick. The
     * old timings (30/120/90) were tuned by feel and stacked up when two flicks
     * arrived together; one dwell makes the queue's arithmetic predictable.
     */
    const val MODIFIER_LEAD_MS = 50L

    /** How long a step is held: a poll-safe dwell at the title's frame rate. */
    const val HOLD_MS = 50L

    /** The gap between a double tap's two presses. */
    const val DOUBLE_TAP_GAP_MS = 50L

    /** The shortest and longest dwell a binding may ask for, in milliseconds. */
    const val MIN_HOLD_MS = 30L
    const val MAX_HOLD_MS = 600L

    fun steps(action: SwipeAction): List<SwipeStep> {
        if (!action.bound) return emptyList()

        val hold = action.holdMs.coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)

        // A flick of a stick: the modifier (if any) leads it, the throw is held
        // for the action's dwell, and the last step puts the stick back to
        // neutral. Nothing else is pressed unless the binding says so.
        if (action.flick != SwipeFlick.NONE && action.bit == 0) {
            if (action.modifier == SwipeModifier.NONE) {
                return listOf(
                    SwipeStep(0L, 0, flick = action.flick),
                    SwipeStep(hold, 0),
                )
            }
            val left = action.modifier == SwipeModifier.LT
            val right = action.modifier == SwipeModifier.RT
            return listOf(
                SwipeStep(0L, action.modifier.bit, left, right),
                SwipeStep(MODIFIER_LEAD_MS, action.modifier.bit, left, right, action.flick),
                SwipeStep(MODIFIER_LEAD_MS + hold, 0),
            )
        }

        if (action.doubleTap) {
            return listOf(
                SwipeStep(0L, action.bit),
                SwipeStep(DOUBLE_TAP_GAP_MS, 0),
                SwipeStep(DOUBLE_TAP_GAP_MS * 2, action.bit),
                SwipeStep(DOUBLE_TAP_GAP_MS * 3, 0),
            )
        }

        return when (action.modifier) {
            SwipeModifier.NONE -> listOf(
                SwipeStep(0L, action.bit),
                SwipeStep(hold, 0),
            )
            SwipeModifier.LB, SwipeModifier.RB -> listOf(
                SwipeStep(0L, action.modifier.bit),
                SwipeStep(MODIFIER_LEAD_MS, action.modifier.bit or action.bit),
                SwipeStep(MODIFIER_LEAD_MS + hold, 0),
            )
            SwipeModifier.LT -> listOf(
                SwipeStep(0L, 0, leftTrigger = true),
                SwipeStep(MODIFIER_LEAD_MS, action.bit, leftTrigger = true),
                SwipeStep(MODIFIER_LEAD_MS + hold, 0),
            )
            SwipeModifier.RT -> listOf(
                SwipeStep(0L, 0, rightTrigger = true),
                SwipeStep(MODIFIER_LEAD_MS, action.bit, rightTrigger = true),
                SwipeStep(MODIFIER_LEAD_MS + hold, 0),
            )
        }
    }
}

/**
 * What a compound swipe is holding right now. The overlay owns one, applies
 * each step to it and adds it to the state it pushes; [clear] is the one call
 * every cancellation makes, so a bumper or trigger cannot be left down.
 */
class ComboHold {
    var buttons = 0
        private set
    var leftTrigger = false
        private set
    var rightTrigger = false
        private set
    /** The stick a flick step is holding at full deflection, or NONE. */
    var flick = SwipeFlick.NONE
        private set

    val any: Boolean get() = buttons != 0 || leftTrigger || rightTrigger || flick != SwipeFlick.NONE

    fun apply(step: SwipeStep) {
        buttons = step.buttons
        leftTrigger = step.leftTrigger
        rightTrigger = step.rightTrigger
        flick = step.flick
    }

    fun clear() {
        buttons = 0
        leftTrigger = false
        rightTrigger = false
        flick = SwipeFlick.NONE
    }
}

/**
 * Whether a lifted gesture still has something to send.
 *
 * Button swipes are direct directional commands now: there is no second
 * gesture competing for the same drag, so the only question is whether the
 * direction the thumb ended on was bound at all.
 */
object SwipePriority {
    fun sendsSwipe(action: SwipeAction): Boolean = action.bound
}

/**
 * The team mentality buttons' gate.
 *
 * One tap sends one complete sequence -- two presses of a d-pad arm, a beat
 * apart -- and the title reads that as one step of mentality. Two things must
 * therefore never happen: a sequence starting again on the finger's own bounce
 * (the pad sends on the lift, so a hold is already one sequence, not a repeat),
 * and two sequences overlapping, which the title would read as four presses and
 * two steps.
 *
 * The lock is a deadline rather than a queue: a tap that arrives while a
 * sequence is still playing is dropped, because a queued second step is a
 * mentality change the player did not see themselves ask for. [SEQUENCE_MS] is
 * the length of `SwipeSequence`'s double tap; [LOCKOUT_MS] leaves a guest frame
 * or two on top of it.
 *
 * Free of Android types, so [PadGesturesTest] can walk it without a clock.
 */
class MentalityTaps(private val lockoutMs: Long = LOCKOUT_MS) {
    private var busyUntil = Long.MIN_VALUE

    /** Whether a tap at [nowMs] may start a sequence, and blocks the next one. */
    fun accept(nowMs: Long): Boolean {
        if (nowMs < busyUntil) return false
        busyUntil = nowMs + lockoutMs
        return true
    }

    /** Whether a sequence is still inside its lock, for the drawing and the tests. */
    fun busy(nowMs: Long): Boolean = nowMs < busyUntil

    /** A pause, the editor, a phase change: nothing is playing any more. */
    fun reset() {
        busyUntil = Long.MIN_VALUE
    }

    companion object {
        /** The double tap's own length: press, gap, press, clear. */
        const val SEQUENCE_MS = SwipeSequence.DOUBLE_TAP_GAP_MS * 3

        /** How long after a tap a second one is dropped rather than queued. */
        const val LOCKOUT_MS = SEQUENCE_MS + 120L
    }
}

/**
 * Two taps of the same button, close enough together to be the title's own
 * double tap.
 *
 * The single tap is never disturbed: the first press sends its bit as always,
 * and the second press is a press of its own. What the pair adds is the
 * compound sequence -- for pass that is L1 + pass, the one-two -- so a slower
 * second tap simply plays as two ordinary passes instead of nothing at all.
 *
 * One instance per control, because a pair belongs to the button, not to the
 * finger: lifting one thumb and landing another on pass inside the window is
 * still two taps of pass.
 */
class DoubleTap(private val windowMs: Long = WINDOW_MS) {
    private var lastUpAt = Long.MIN_VALUE

    /**
     * One press at [nowMs]. Returns true when this press completes a pair, in
     * which case the caller also sends the compound sequence. A completed pair
     * is consumed, so three taps are a pair and a single rather than two pairs.
     */
    fun press(nowMs: Long): Boolean {
        val paired = lastUpAt != Long.MIN_VALUE && nowMs - lastUpAt in 0..windowMs
        if (paired) lastUpAt = Long.MIN_VALUE
        return paired
    }

    /** One lift: the start of the window the next press is measured against. */
    fun release(nowMs: Long) {
        lastUpAt = nowMs
    }

    fun reset() {
        lastUpAt = Long.MIN_VALUE
    }

    companion object {
        /** A fifth of a second: the gap a thumb makes when it means to tap twice. */
        const val WINDOW_MS = 220L
    }
}

/**
 * The movement stick's sprint latch: pushing the stick past [pressAt] of its
 * throw starts a run, and it stays running until the thumb comes back inside
 * [releaseAt]. The two points are apart on purpose -- one threshold with a
 * thumb balanced on it chatters, and a run that flickers is worse than none.
 *
 * Only the match stick reads this: the MENU stage, Stick 2 and the skill stick
 * never sprint, and the pad only asks while a match is on screen, so a run can
 * never press R1 at the title's menus. The two points are the shipped 92/82
 * until the left-stick customization names others. Free of Android types, so
 * [PadGesturesTest] can walk it without a phone.
 */
class SprintLatch(
    pressAt: Float = 0.92f,
    releaseAt: Float = 0.82f,
) {
    /** Whether the movement stick is running right now. */
    var active = false
        private set

    private var press = 0f
    private var release = 0f

    init {
        thresholds(pressAt, releaseAt)
    }

    /**
     * Move the ring, as the left-stick customization does. The latched state is
     * kept: a run in progress continues until the thumb comes back inside the
     * new release point, exactly as it would have under the old one.
     */
    fun thresholds(pressAt: Float, releaseAt: Float) {
        press = pressAt.coerceIn(0.1f, 1f)
        release = releaseAt.coerceIn(0f, press - 0.01f)
    }

    /** One sample of the stick's throw, from -1..1 on each axis. Returns [active]. */
    fun update(x: Float, y: Float): Boolean {
        val magnitude = hypot(x, y)
        active = if (active) magnitude >= release else magnitude >= press
        return active
    }

    fun reset() {
        active = false
    }
}

/**
 * Which sector of the skill wheel a thumb is over, and when the wheel is open
 * at all.
 *
 * Eight sectors, 45 degrees each, starting at the right and running the way a
 * clock face's numbers do the other way: sector 0 right, 1 up-right, 2 up, 3
 * up-left, 4 left, 5 down-left, 6 down, 7 down-right. The screen's y grows
 * downward, so the angle is taken from -dy. The deadzone is what makes a
 * quick tap nothing at all: inside a quarter of the radius the thumb has not
 * said which move it wants, and the hold threshold is what keeps an ordinary
 * press from opening the wheel in the first place.
 */
object TrickWheel {
    /** How long the thumb has to stay down before the wheel opens. */
    const val HOLD_MS = 120L

    /** The fraction of the radius that is "no move chosen". */
    const val DEADZONE = 0.25f

    const val SECTORS = 8

    /** Degrees per sector. */
    private const val STEP_DEGREES = 360.0 / SECTORS

    /**
     * The sector under a thumb that is [dx], [dy] from where it landed, or -1
     * for the deadzone and for a radius too small to mean anything.
     */
    fun sector(dx: Float, dy: Float, radius: Float): Int {
        if (radius <= 0f) return -1
        if (dx * dx + dy * dy < (radius * DEADZONE) * (radius * DEADZONE)) return -1
        val degrees = Math.toDegrees(kotlin.math.atan2(-dy.toDouble(), dx.toDouble()))
        val fromRight = ((degrees % 360.0) + 360.0) % 360.0
        return (((fromRight + STEP_DEGREES / 2.0) / STEP_DEGREES).toInt()) % SECTORS
    }

    /** Whether a thumb held this long has become a wheel rather than a tap. */
    fun isHold(heldMs: Long): Boolean = heldMs >= HOLD_MS
}

/**
 * The shortest gap between two presses of the same button for the second to be
 * a press of its own rather than the bounce of the first. Two taps of sprint --
 * the speed burst -- are a fifth of a second apart and never touch this; a
 * chattering switch, or one press delivered twice by a driver, does.
 *
 * Only the press *pulse* is gated, never the hold: a suppressed pulse still
 * holds its bit, so a bounce can only cost a tap that was one edge anyway.
 */
class TapCadence(private val minGapMs: Long = 40L) {
    private var lastAt = Long.MIN_VALUE

    /** Whether a press at [nowMs] should also leave a pulse for the guest. */
    fun accept(nowMs: Long): Boolean {
        if (lastAt != Long.MIN_VALUE && nowMs - lastAt < minGapMs) return false
        lastAt = nowMs
        return true
    }

    fun reset() {
        lastAt = Long.MIN_VALUE
    }
}
