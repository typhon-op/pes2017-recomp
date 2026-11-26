package com.pes2017.recomp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.Toast
import kotlin.math.abs
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

    /** The one path every buzz goes through (HapticsPolicy's switch lives there). */
    private val haptics by lazy { Haptics(context, prefs) }

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

    /** Called just before an editor gesture changes the layout, for undo. */
    var onEditStarted: (() -> Unit)? = null

    var editing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            releaseEverything()
            selected = if (value) selected else null
            invalidate()
            refreshDpad()
        }

    var selected: ControlElement? = null
        private set

    /** PlayStation names and symbols (Cross, Circle, L1, OPTIONS) instead of Xbox ones (A, B, LB, START). */
    var playStation: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Snapping makes a tidy row possible with a thumb; the editor offers it. */
    var snapToGrid: Boolean = false

    private val density = resources.displayMetrics.density

    // ---- what is being touched right now ----------------------------------
    // A pointer is claimed by one control for as long as it is down, so a
    // thumb that slides off the fire button keeps firing -- which is what
    // every phone shooter does, and what a player expects. The claims are
    // counted (TouchClaims): two fingers on one button hold it between them,
    // and it is only let go when the last of them lifts.
    private val claims = TouchClaims()
    private val stickPositions = HashMap<String, FloatArray>()   // id -> normalised x, y

    // The floating movement stick (Prefs.stickMode): the socket is wherever the
    // thumb landed, and it stays there for the whole push -- the thumb moves
    // inside it, the socket does not chase the thumb. Only the movement stick
    // floats: the skill stick is a flick (runtime/skill_stick.h) and the rest of
    // the pad is aimed at, not found.
    private var floatPointer = -1
    private var floatOriginX = 0f
    private var floatOriginY = 0f

    // Where each finger landed: a stance tap is measured from there.
    private val pressAt = HashMap<Int, FloatArray>()

    // The pass button's double tap: the second tap of a pair inside the window
    // also sends L1 + pass, the one-two. A single tap is untouched, so an
    // ordinary pass still plays as one.
    private val passDoubleTap = DoubleTap()

    // The swipe a thumb has just released, per control: its trail flies out and
    // fades over SWIPE_TRAIL_MS, which is the feedback that says the flick was
    // read rather than dropped.
    private class SwipeTrail(val direction: Swipe, val startedAt: Long)
    private val swipeTrails = HashMap<String, SwipeTrail>()

    // The absolute skill stick is polled by the title, so the last sample is held
    // for a moment after the thumb lifts: a flick that begins and ends between
    // two polls is otherwise never seen.
    private val skillReleaseUntil = HashMap<String, Long>()


    /**
     * The two-stage stick's stage per finger, when the switch above the socket
     * has picked it (TouchMath.TwoStageStick). Kept beside the latch it stands in
     * for rather than inside it: the two modes are different gestures, not two
     * settings of one.
     */
    private val twoStage = TwoStageStick()

    /** The left stick's angle smoothing memory (LeftStickFeel). */
    private val leftSmoothing = LeftStickFeel.Smoother()

    /** The finger whose throw is in the sprint ring, and whether it is in it. */
    private var twoStagePointer = -1
    private var twoStageSprint = false

    /** Whether the movement stick is out far enough to run (see SprintLatch). */
    private val sprintLatch = SprintLatch()

    /**
     * The state a tap on the MATCH/MENU pill was made in, while auto is on: the
     * tap stands until the game changes state (StickSwitch.key).
     */
    private var stickOverride = StickSwitch.NO_OVERRIDE

    /**
     * Whether the state the pad last sent held RB, from any source: the Sprint
     * control, one of the latches, a compound sequence or the MATCH stick's own
     * rim. Drawing cannot see a bit no thumb is holding, and the MATCH stick's
     * sprint halo reads this, so the light is exactly the run the title is
     * being told about rather than a guess from the thumb's place.
     */
    private var sprintHeld = false

    /**
     * The team mentality pair's lock: one tap, one double press of the d-pad, and
     * a second tap inside it is dropped rather than queued (PadGestures.kt).
     */
    private val mentality = MentalityTaps()

    /** The unclamped throw of a two-stage stick, so the ring and the knob can be drawn. */
    private val stickRaw = HashMap<String, FloatArray>()

    // A compound swipe's held modifier and button, and the steps still to run.
    // The compound sequence being held right now, and the queue of the ones the
    // player has already asked for. Queued rather than raced: two flicks in a row
    // are two commands the title hears in order, never two timers overwriting
    // each other on the same d-pad.
    private val combo = ComboHold()
    private val macro = MacroSequencer()
    private val macroPump = Runnable { pumpMacro() }
    // Every source that wants a virtual button down, merged instead of written
    // over, so one ending can never release another's bit.
    private val mixer = HoldMixer()

    // The direction a thumb on an action button is heading for right now, for
    // the arrows under it and the floating action name. The gesture itself is
    // still sent on the lift; this is what the thumb can see in the meantime.
    private val liveSwipes = HashMap<Int, Swipe>()

    // The modifier of the live direction, held alongside the button that is
    // already down. A charged chip shot is LB arriving while X is held, so the
    // swipe does not have to be a separate sequence after the lift. One at a
    // time: a second thumb's gesture does not steal the first one's modifier.
    private var liveSwipePointer = -1
    private var liveSwipeButtonBits = 0
    private var liveSwipeLeft = false
    private var liveSwipeRight = false
    private var liveSwipeSince = 0L

    /**
     * The button bit carried past the finger's lift.
     *
     * A modifier swipe on an action button is one held pair -- RT and shoot --
     * and the press the thumb has already made is the button half of it. When
     * the direction is only read as the thumb leaves, that press is still what
     * the title is charging: the modifier is added beside it and this carries
     * the button through the same short tail. Replaying the lift through the
     * fallback sequence instead lifts the button first, which the title reads
     * as the ordinary shot the player did not ask for.
     */
    private var liveSwipeHoldBits = 0

    /**
     * Which hold the live modifier belongs to. A tail posted by one gesture
     * must not clear the modifier a newer gesture has already armed, so every
     * hold that comes on takes a new epoch and a tail only acts on its own.
     */
    private var liveSwipeEpoch = 0
    private var liveSwipeTailEpoch = 0

    /**
     * The modifier this gesture armed, and when it first armed it: kept apart
     * from the bits sent right now. A direction that reads as NONE for a moment
     * -- the thumb crossing back over the travel threshold, a diagonal exactly
     * between two arms -- lets go of the bits but not of the clock. The title
     * has already polled the modifier down, and the reading that comes back a
     * sample later is the same gesture, not a new one that has to start again.
     */
    private var liveSwipeArmedAt = 0L
    private var liveSwipeArmedButtons = 0
    private var liveSwipeArmedLeft = false
    private var liveSwipeArmedRight = false

    /** What the pad last told the title, so logcat shows a dead stick or a lost flick. */
    private val trace = TouchTraceGate()

    /**
     * The tail of a live swipe's modifier. A lift that relied on a modifier that
     * was already down does not replay the timed sequence -- the title has the
     * combination -- but it must not undo it in the same push either: this lets
     * go one beat after the button under it (LIVE_MODIFIER_TAIL_MS).
     */
    private val liveSwipeTail = Runnable {
        // An older hold's tail outliving a newer swipe's modifier: clearing it
        // here would lift the RT the new gesture is relying on, one frame
        // before the button it belongs to. The newer hold has its own tail --
        // but the press this tail carried is not the newer hold's, so it is let
        // go even then: a button with no finger on it must never stay down.
        if (liveSwipeTailEpoch != liveSwipeEpoch) {
            if (liveSwipeHoldBits == 0) return@Runnable
            liveSwipeHoldBits = 0
        } else {
            clearLiveSwipe()
        }
        if (NativeBridge.isLoaded()) pushState()
        invalidate()
    }

    // Reused by animateEffects: a fresh list per draw was one allocation a frame,
    // which is exactly what this pass is here to stop.
    private val finishedAnims = ArrayList<String>(4)

    // A label's width per text-size unit, measured once per string.
    private val textRatios = HashMap<String, Float>()

    // Look is a gesture rather than a hold, so each finger on it is sampled on
    // its own: with one slot for "the look pointer", a second thumb landing on
    // the look area re-based the first one and the view jumped.
    private val lookPointers = HashMap<Int, FloatArray>()        // id -> last x, y

    // The skill stick, read as a flick: the direction is the thumb's travel from
    // where it landed, not its distance from the socket's centre. On glass the
    // centre is only a point on a path, so a stick placed there can never say
    // "left, then right" -- it says "round", and a Double Touch resolves as a
    // Roulette. runtime/skill_stick.h has the whole argument.
    private var skillPointer = -1
    private var skillOriginX = 0f
    private var skillOriginY = 0f

    // The skill wheel: a hold, then a sector, then the move on the lift. The
    // origin is where the thumb landed, not where the button is drawn, so the
    // ring comes to the thumb rather than making it reach for a circle. All of
    // it lives in WheelGesture, whose open/closed state is *derived* from the
    // owning finger and the clock: there is no one-shot "open" flag a dropped
    // runnable can lose, which is what used to make the ring disappear while a
    // thumb was still rolling through sectors.
    private val wheel = WheelGesture()
    // A repaint when the hold threshold passes. It changes no state, so a late
    // or dropped tick can only delay the first repaint, never close the wheel;
    // the next touch sample draws it from `wheel.isOpen` anyway.
    private val wheelTick = Runnable { invalidate() }

    // Whether the Match flow has reached kick-off. The engine calls the whole
    // Match/... flow a match, so the lineups and the walk-out read as MATCH as
    // well; a START in that window is the title's skip, not its pause sheet.
    // Real steering marks the kick-off: the stick must stay deflected for a
    // moment, so a nudge through a lineup screen is not mistaken for play. A
    // physical pad's left stick counts too, and a long time fallback covers a
    // match played without either.
    private var kickOffPending = false
    private var matchEnteredAt = 0L
    private var steerHeldSince = 0L
    private var padSteerSince = 0L
    private var padSteerAt = 0L
    private var padX = 0f
    private var padY = 0f
    private val kickOffProbe = Runnable { probeKickOff() }
    private val padKickOffProbe = Runnable { probePadKickOff() }

    // Sprint's press pulse is gated: two taps are the speed burst, a bounce of
    // one tap is not. The hold itself is never gated.
    private val burstCadence = TapCadence()

    /**
     * The flick reading, and only when this build's runtime has it. The apk
     * packages a libpes17.so built elsewhere, and an older one has no
     * skill-stick calls at all -- handing it the right stick would be a dead
     * stick rather than a fix.
     */
    private val flicking get() = prefs.flickSkillStick && NativeBridge.skillStick

    /** A palette button or the wheel needs the runtime that plays moves. */
    private fun available(element: ControlElement) =
        (element.kind != ControlKind.SKILL && element.kind != ControlKind.TRICK) ||
            NativeBridge.skillStick

    /** The eFootball-style floating movement stick, unless the player fixed it. */
    private val floatingStick get() = prefs.stickMode == Prefs.STICK_MOVE

    // A trigger stays down for a moment after the finger leaves. Lifting RT for
    // the beat it takes to tap a button used to drop the sprint on exactly the
    // frame the tap landed; 80 ms is longer than a tap and shorter than the
    // animation that would notice, and it applies to LT and RT alike.
    private val triggerGraceMs = 80L
    private val triggerReleaseAt = HashMap<String, Long>()        // id -> uptime it lets go

    // A d-pad arm is a hold like a button, but it can change without the finger
    // lifting: sliding from up to right is one gesture and two states.
    private val dpadBits = HashMap<Int, Int>()                    // pointer id -> arms held

    // The d-pad's idle clock. In a match it fades out after four quiet seconds
    // and comes back on a touch; in the menus it is navigation and stays put.
    private val dpadAutoHide = DpadAutoHide()

    private val dpadTick = object : Runnable {
        override fun run() {
            if (!dpadAutoHideApplies()) return
            val now = SystemClock.uptimeMillis()
            // Never fade from under a finger: a held arm is still a command.
            // Nothing is scheduled while it is held -- the loop stops here rather
            // than waking every half second to say the same thing -- because
            // letting go re-arms it through wakeDpad().
            if (dpadBits.isNotEmpty()) return
            dpadAutoHide.tick(now)
            invalidate()
            when {
                dpadAutoHide.hidden -> Unit
                dpadAutoHide.fading(now) -> postDelayed(this, 32L)
                else -> postDelayed(this, dpadAutoHide.idleRemaining(now).coerceAtLeast(16L))
            }
        }
    }

    // A swipe is read when the finger lifts and sent as one pulse: it has no bit
    // to hold, and a flick is over before the title's next poll anyway. Every
    // button -- the action four, the tactical five-ways and C1..C10 -- reads
    // through this one classifier, so their travel threshold and lack of a time
    // limit are the same by construction, not by three copies agreeing.
    private val swipeClassifier = SwipeClassifier.forPadButtons(density)

    // The left stick standing in for the d-pad, for an engine that cannot do it
    // itself (TouchMath.kt). Carried across pushes, because its hysteresis is
    // what stops a thumb on a threshold chattering the four bits.
    private val stickArrows = StickArrows()

    // A press that goes down and up between two of the title's polls. An engine
    // with nativeTouchPulse carries it on the next poll; one built before that
    // is given the bit held for a frame instead, and let go by a callback so the
    // hold cannot outlive the tap it belongs to.
    private var pulseBits = 0
    private var pulseUntil = 0L
    // One guest frame at the 30 fps the title runs at: long enough for a poll
    // to land on the held bit, short enough that a tap is not a press.
    private val pulseFrameMs = 34L
    private val pulseClear = Runnable {
        pulseBits = 0
        pulseUntil = 0L
        if (isAttachedToWindow) pushState()
    }
    private var pendingSwipes = 0

    /**
     * What the title is doing, as the runtime reports it. Controls marked for a
     * phase come and go with it; one that is about to leave the screen is let go
     * first, so a menu cannot keep a direction held into a match.
     */
    var gamePhase: GamePhase = GamePhase.UNKNOWN
        set(value) {
            if (field == value) return
            field = value
            // A different flow is not the title's in-match menu the switch was
            // following: START's latch belongs to one match and goes with it.
            titleMenu = false
            // Entering the Match flow is not the kick-off: the lineups, the
            // walk-out and the pre-match cutscenes are inside it too, and START
            // there skips them rather than opening the pause sheet. The switch
            // must stay on MATCH through those, or it is already on MENU when
            // the whistle goes and the next START flips it the wrong way.
            kickOffPending = value == GamePhase.MATCH
            if (kickOffPending) matchEnteredAt = SystemClock.uptimeMillis()
            steerHeldSince = 0L
            padSteerSince = 0L
            removeCallbacks(kickOffProbe)
            removeCallbacks(padKickOffProbe)
            // Everything the pad was holding is let go across a phase change, the
            // two-stage stick's ring included: RB means "next" in the title's
            // menus, so a sprint still held while the match ended would scroll a
            // menu once, or leave R1 down under it.
            releaseEverything()
            invalidate()
            // Entering or leaving a match starts the d-pad's idle clock fresh.
            refreshDpad()
            // ...and it is one of the two things that can move the movement
            // stick's switch while auto is on; the pause is the other.
            reconsiderStick()
        }

    /**
     * Which of the player's two match pads is out (Prefs.defendingPad). A control
     * arranged for one stance comes and goes with it; one arranged for both is
     * unaffected.
     */
    var stance: PadStance = PadStance.ATTACK
        set(value) {
            if (field == value) return
            field = value
            invalidate()
            refreshDpad()
        }

    /**
     * Whether the app has paused the guest: the pause menu, the pad editor, the
     * way out. Not the same as the phase -- the title keeps its match flow while
     * our menu is up -- so it is a source of its own for auto mode.
     */
    var paused: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            reconsiderStick()
        }

    /**
     * Whether the title's own in-match menu is open. The engine keeps naming
     * the flow `Match/...` while Game Plan, Substitutions or the START pause
     * sheet is up, so the phase cannot answer this: START does. A press in a
     * running match opens the menu (MENU stick), the next START -- the title's
     * own resume -- closes it (MATCH stick). A phase change clears it, and the
     * app's own pause does not: closing that lands back in the title's menu,
     * where the MENU stick is still the right one.
     */
    var titleMenu: Boolean = false
        private set

    /**
     * One START press, from the on-screen button (press) or a physical pad
     * (GameActivity.dispatchKeyEvent). Only a running match has a menu to open,
     * so anywhere else this is just the title's own Start and changes nothing;
     * it never touches the phase or the pause.
     */
    fun noteStartPressed() {
        if (editing || paused) return
        if (gamePhase != GamePhase.MATCH) {
            // Defensive: a latch from a previous match cannot survive a screen
            // that is no longer a match even if the phase was not rebuilt.
            if (titleMenu) {
                titleMenu = false
                reconsiderStick()
                invalidate()
            }
            return
        }
        if (kickOffPending) {
            // Holding a stick at this moment is live play, even if no MOVE has
            // arrived since the hold began (a still thumb sends nothing).
            if (steeringSustained() || padSteeringSustained()) {
                noteKickOff("a stick held at START")
            } else if (SystemClock.uptimeMillis() - matchEnteredAt < KICK_OFF_FALLBACK_MS) {
                // The title's own START before kick-off: it skips the pre-match,
                // so it is not the pause sheet and must not move the switch. The
                // press itself is already on its way to the title untouched.
                reconsiderStick()
                invalidate()
                return
            } else {
                kickOffPending = false
                removeCallbacks(kickOffProbe)
                removeCallbacks(padKickOffProbe)
            }
        }
        titleMenu = !titleMenu
        // The new stage lets go of the run and the two-stage dwell itself; the
        // title stops the match behind its menu, so nothing else is held on
        // purpose. The START finger's own claim is left alone: it is the press
        // that opened the menu and must still reach the title.
        reconsiderStick()
        invalidate()
    }

    private var stanceToast: Toast? = null

    /**
     * Flips the pad and remembers it. Deliberately does not call
     * `releaseEverything`: another finger may be holding a button, and a flip
     * only changes what the labels say, never which bit that finger holds. A
     * control arranged for the other stance simply stops being sent, because
     * `pushState` skips what is not on screen, and its claim goes when the
     * finger lifts.
     */
    fun switchStance(value: PadStance) {
        if (stance == value) return
        // A flip is a new pad: a latch set on the attacking pad must not keep a
        // bit down on the defending one, where it may not even be drawn.
        claims.clearLatches()
        stance = value
        if (NativeBridge.isLoaded()) pushState()
        prefs.defendingPad = value == PadStance.DEFEND
        stanceToast?.cancel()
        stanceToast = Toast.makeText(
            context,
            if (value == PadStance.DEFEND) R.string.stance_toast_defend else R.string.stance_toast_attack,
            Toast.LENGTH_SHORT,
        ).also { it.show() }
    }

    /**
     * The pause menu's Auto/Manual row was changed: work the switch out again
     * from the state the game is in now, and redraw the pill.
     */
    fun stickModeChanged() {
        reconsiderStick()
        invalidate()
    }

    /**
     * A physical pad's left stick, from GameActivity's motion events. The
     * screen is hidden while a pad drives, so its steering cannot come through
     * the touch path -- without this a pad player's match would not count as
     * kicked off until the time fallback.
     */
    fun notePadStick(x: Float, y: Float) {
        padX = x
        padY = y
        if (!kickOffPending) return
        if (!padSteeringMatch()) {
            padSteerSince = 0L
            removeCallbacks(padKickOffProbe)
            return
        }
        val now = SystemClock.uptimeMillis()
        padSteerAt = now
        if (padSteerSince == 0L) {
            padSteerSince = now
            removeCallbacks(padKickOffProbe)
            postDelayed(padKickOffProbe, KICK_OFF_STEER_MS)
        } else if (now - padSteerSince >= KICK_OFF_STEER_MS) {
            noteKickOff("a pad's stick")
        }
    }

    /** The touch stick is deflected far enough to be play, not a menu nudge. */
    private fun steeringMatch(): Boolean {
        if (gamePhase != GamePhase.MATCH || titleMenu || paused || editing) return false
        val p = stickPositions[MOVE_STICK] ?: return false
        return hypot(p[0], p[1]) >= KICK_OFF_STEER
    }

    /** That deflection has lasted long enough to be steering a player. */
    private fun steeringSustained(): Boolean =
        steerHeldSince != 0L && steeringMatch() &&
            SystemClock.uptimeMillis() - steerHeldSince >= KICK_OFF_STEER_MS

    private fun padSteeringMatch(): Boolean =
        kickOffPending && !titleMenu && !paused && !editing &&
            hypot(padX, padY) >= KICK_OFF_STEER

    private fun padSteeringSustained(): Boolean {
        if (padSteerSince == 0L || !padSteeringMatch()) return false
        val now = SystemClock.uptimeMillis()
        return now - padSteerSince >= KICK_OFF_STEER_MS &&
            now - padSteerAt <= PAD_STEER_FRESH_MS
    }

    private fun probeKickOff() {
        if (!kickOffPending) return
        if (steeringSustained()) noteKickOff("the match stick held")
        else steerHeldSince = 0L
    }

    private fun probePadKickOff() {
        if (!kickOffPending) return
        if (padSteeringSustained()) noteKickOff("a pad's stick")
        else padSteerSince = 0L
    }

    /**
     * The match is being played. Done once per Match flow: the switch goes to
     * MATCH and a pre-match tap on the pill stops standing, because kick-off is
     * exactly the state change it was made to wait for.
     */
    private fun noteKickOff(because: String) {
        if (!kickOffPending) return
        kickOffPending = false
        steerHeldSince = 0L
        padSteerSince = 0L
        removeCallbacks(kickOffProbe)
        removeCallbacks(padKickOffProbe)
        if (stickOverride == StickSwitch.key(GamePhase.MATCH, paused = false, titleMenu = false)) {
            stickOverride = StickSwitch.NO_OVERRIDE
        }
        Log.i(TAG, "stick: kick-off seen ($because)")
        reconsiderStick()
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

    // The short press light. Only the ids that have been touched since the view
    // was made are in these maps, so an idle pad draws exactly what it drew
    // before the theme existed.
    private val pressAnimAt = HashMap<String, Long>()
    private val releaseAnimAt = HashMap<String, Long>()
    private var drawWorkNanos = 0L
    private var drawSamples = 0
    private var drawSlowFrames = 0

    /** Set when the pad's own light has cost too much for three samples in a row. */
    private var effectsDegraded = false

    /** True while an off control is being drawn, so its glow can be forced to none. */
    private var drawingOff = false

    private val effects: Boolean
        get() = !effectsDegraded && ControlTheme.animated(prefs.controlFx)
    private val box = RectF()
    private val tempRect = RectF()

    init {
        isFocusable = false
        isClickable = false
        setWillNotDraw(false)
    }

    // ---- geometry ----------------------------------------------------------

    // The window minus what a control may not sit under: the cutout, the rounded
    // corners, the gesture bar. Recomputed only when the window or the insets
    // change, because every hit test and every drawn pixel reads it.
    private var safeInsets = SafeAreaDp.NONE
    private var screenFit = ScreenFit(1f, 1f, SafeAreaDp.NONE)

    // Stance-aware: a control the player gave its own defending-pad place is
    // drawn, hit-tested and edited there while that pad is out. Fitted to the
    // safe area, so a control on a notched phone is still a control a thumb can
    // reach, and the same layout is unchanged on a phone without one.
    private fun centreX(element: ControlElement): Float = geometryFor(element).x

    private fun centreY(element: ControlElement): Float = geometryFor(element).y

    private fun radius(element: ControlElement): Float = geometryFor(element).r

    /**
     * A control's fitted place and size in pixels, kept between frames.
     *
     * The fitting is cheap per control but it was being asked for four or five
     * times per control per frame -- once for the circle, again for its centre,
     * again for the hit test -- and each answer re-derived the safe size. One
     * entry per control id remembers the numbers and, more importantly, what they
     * were derived from: the fraction, the size, the pad, the window and the
     * insets. A drag, a pinch, a stance flip, a rotation or a new cutout all
     * change one of those, so a stale entry cannot outlive its inputs.
     */
    private class Geometry {
        var valid = false
        var xFrac = 0f
        var yFrac = 0f
        var sizeDp = 0f
        var stance = PadStance.ATTACK
        var width = 0
        var height = 0
        var insets: SafeAreaDp = SafeAreaDp.NONE
        var x = 0f
        var y = 0f
        var r = 0f
    }

    private val fittedGeometry = HashMap<String, Geometry>()

    private fun geometryFor(element: ControlElement): Geometry {
        var geometry = fittedGeometry[element.id]
        if (geometry == null) {
            geometry = Geometry()
            fittedGeometry[element.id] = geometry
        }
        val xFrac = element.xFracOn(stance)
        val yFrac = element.yFracOn(stance)
        val sizeDp = element.sizeOn(stance)
        if (!geometry.valid || geometry.xFrac != xFrac || geometry.yFrac != yFrac ||
            geometry.sizeDp != sizeDp || geometry.stance != stance ||
            geometry.width != width || geometry.height != height ||
            geometry.insets !== safeInsets
        ) {
            val fitted = screenFit.sizeDp(sizeDp)
            geometry.x = screenFit.xDp(xFrac, fitted) * density
            geometry.y = screenFit.yDp(yFrac, fitted) * density
            geometry.r = fitted * density * 0.5f
            geometry.xFrac = xFrac
            geometry.yFrac = yFrac
            geometry.sizeDp = sizeDp
            geometry.stance = stance
            geometry.width = width
            geometry.height = height
            geometry.insets = safeInsets
            geometry.valid = true
        }
        return geometry
    }

    private fun rebuildFit() {
        if (width <= 0 || height <= 0) return
        screenFit = ScreenFit(
            max(1f, width / density), max(1f, height / density), safeInsets,
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildFit()
    }

    /**
     * Records where the window's unusable edges are and consumes nothing: the
     * match draws into the cutout on purpose, so the insets are the pad's
     * business only. A control's position in the layout file is untouched by
     * this -- fitting is a view, never a save.
     */
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        var left = insets.systemWindowInsetLeft
        var top = insets.systemWindowInsetTop
        var right = insets.systemWindowInsetRight
        var bottom = insets.systemWindowInsetBottom
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val safe = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            left = safe.left
            top = safe.top
            right = safe.right
            bottom = safe.bottom
        }
        val scale = if (density > 0f) density else 1f
        val updated = SafeAreaDp(left / scale, top / scale, right / scale, bottom / scale)
        if (updated != safeInsets) {
            safeInsets = updated
            rebuildFit()
            invalidate()
        }
        return super.onApplyWindowInsets(insets)
    }

    /** How far the knob travels inside its socket, as a fraction of the radius. */
    private val stickTravel = 0.62f

    /**
     * How far outside its socket a movement stick can still be grabbed, as a
     * fraction of the radius.
     *
     * The base is the socket's centre and never moves, so a thumb landing wide
     * of the circle is read as a push from the centre -- the deflection the hand
     * meant, rather than a refusal. Wider than a button's slack on purpose: a
     * stick is the control a thumb finds without looking.
     */
    private val stickGrab = 1.35f

    /** The look area is a rectangle; everything else is a circle. */
    private fun rectOf(element: ControlElement, into: RectF) {
        val halfWidth = element.sizeOn(stance) * density * 0.5f
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
        // A touch a little outside still counts in play: glass has no edges to
        // feel, and a button that needs to be hit exactly is a button that gets
        // missed in a firefight. The editor grips harder -- a spare button hidden
        // under a neighbour has to be selectable -- so its handles do not overlap.
        val slack = (if (editing) 2f else 6f) * density
        // The two mentality buttons are pills, and their hit area follows the
        // shape they are drawn as: two stacked circles would overlap each other
        // and claim a thumb-sized patch of the pitch above and below them.
        if (element.kind == ControlKind.MENTALITY) {
            val halfWidth = radius(element) + slack
            val halfHeight = radius(element) * 0.48f + slack
            box.set(
                centreX(element) - halfWidth, centreY(element) - halfHeight,
                centreX(element) + halfWidth, centreY(element) + halfHeight,
            )
            return box.contains(x, y)
        }
        // A movement stick is grabbed wider than it is drawn: the base is fixed
        // at the socket's centre, so the press reads as a push from there. That
        // is true in both stages -- a two-stage socket that could only be
        // grabbed inside its rim lost every thumb that landed a little wide,
        // and the press fell through to the pitch as a camera drag. The ring is
        // kept out of a press by the sample itself (moveStick's `fromPress`),
        // not by refusing the touch.
        val reach = if (element.kind == ControlKind.STICK && !editing) {
            radius(element) * stickGrab
        } else {
            radius(element)
        }
        return hypot(x - centreX(element), y - centreY(element)) <= reach + slack
    }

    /** Buttons first, the look area last: it sits under everything. */
    private fun elementAt(x: Float, y: Float, includeHidden: Boolean = false): ControlElement? {
        // The editor sees everything, whatever the title is doing: a control
        // that is not on screen right now still has to be movable.
        val showAll = includeHidden || editing
        // Nearest centre wins, not first in the list. The touch slack makes the
        // hit areas of two neighbours overlap by a few dp; the thumb that lands
        // in that strip means the control it is closest to, and the answer no
        // longer depends on the order the file happens to list them in.
        var nearest: ControlElement? = null
        var nearestDistance = Float.MAX_VALUE
        for (element in layout.elements) {
            if (element.kind == ControlKind.LOOK) continue
            if (!available(element)) continue
            if (!showAll && !element.shownIn(gamePhase, stance)) continue
            // A faded d-pad is gone as far as a touch is concerned: the tap
            // falls through to the pitch, and the press that wakes it wakes it.
            if (element.kind == ControlKind.DPAD && !showAll && dpadIsHidden()) continue
            if (!hit(element, x, y)) continue
            val distance = hypot(centreX(element) - x, centreY(element) - y)
            if (distance < nearestDistance) {
                nearest = element
                nearestDistance = distance
            }
        }
        if (nearest != null) return nearest
        // A floating movement stick is where the thumb lands, not where it was
        // drawn: in play its half of the screen is its own. Every control that
        // shares that half has been looked for already, so a shoulder button at
        // the top of it still wins.
        if (!showAll && floatingStick && x < width * FLOAT_ZONE) {
            layout.elements.firstOrNull {
                it.kind == ControlKind.STICK && it.value == 0 && it.shownIn(gamePhase, stance)
            }?.let { return it }
        }
        for (element in layout.elements) {
            if (element.kind != ControlKind.LOOK) continue
            if (!showAll && !element.shownIn(gamePhase, stance)) continue
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

        val now = SystemClock.uptimeMillis()
        // A grace that is over is let go here as well as by its own callback, so
        // a push for any other reason cannot keep a trigger down for longer.
        triggerReleaseAt.entries.removeAll { it.value <= now }

        for (element in layout.elements) {
            if (!element.shownIn(gamePhase, stance)) continue
            // Fingers only: a latch is a source of its own and is merged below,
            // so a latched button is not also counted as a thumb on the glass.
            val on = claims.isHeld(element.id)
            when (element.kind) {
                ControlKind.BUTTON -> if (on) buttons = buttons or element.value
                ControlKind.DPAD -> buttons = buttons or dpadMaskFor(element)
                ControlKind.TRIGGER -> {
                    val grace = triggerReleaseAt[element.id]
                    if (on || (grace != null && now < grace)) {
                        if (element.value == 0) leftTrigger = 255 else rightTrigger = 255
                    }
                }
                ControlKind.STICK -> {
                    val position = stickPositions[element.id]
                    // The right stick is always an analogue stick: its thumb's
                    // deflection is thumbRX/thumbRY, the same pair a physical
                    // pad's right stick writes. A skill flick rides on top of
                    // it in the runtime (g_skillPulses overrides for the few
                    // frames a sequence runs), so the game has a genuine right
                    // stick the whole time rather than only a pulse.
                    if (position != null) {
                        if (element.value == 0) {
                            // Two left sticks state one axis between them: the
                            // further-deflected one wins rather than the two
                            // adding up, so a thumb on each cannot ask for more
                            // deflection than the axis can carry.
                            if (hypot(position[0], position[1]) > hypot(leftX, leftY)) {
                                leftX = position[0]
                                leftY = position[1]
                            }
                        } else {
                            rightX += position[0]
                            rightY += position[1]
                        }
                    }
                }
                ControlKind.SWIPE -> Unit   // sent when the finger lifts, as a pulse
                ControlKind.SKILL -> Unit   // fires once on the press, not as state
                ControlKind.TRICK -> Unit   // fires once on the lift, not as state
                // The switch sends nothing and holds nothing: it changes which
                // throw the left stick reads with, from the next sample on.
                ControlKind.STICK_MODE -> Unit
                // The mentality pair sends nothing while a finger is on it: its
                // double press is read on the lift, and holds no bit at all.
                ControlKind.MENTALITY -> Unit
                ControlKind.CUSTOM -> Unit  // a spare: only its swipes send
                // A latched toggle holds its command with no finger on it, so
                // its bits are read from the latch rather than from a hold.
                ControlKind.LATCH -> Unit  // latched: merged by source below
                ControlKind.TACTICAL -> Unit  // a five-way button: swipes send
                ControlKind.LOOK -> Unit    // sent as deltas, not as a stick
            }
        }
        // A press that went down and up between two of the title's polls has no
        // other way to be seen, so its bits wait in the bridge for the next
        // poll. Taken before the macro check: a macro run drops the fingers,
        // and with them the taps they made while it played.
        val pressed = claims.takeNewPresses() or pendingSwipes
        pendingSwipes = 0
        // A macro is playing: the fingers are not heard, so a run repeats exactly.
        // A flick left over from before it started is let go for the same reason.
        if (Macro.playing) {
            if (NativeBridge.skillStick) NativeBridge.nativeSkillEnd()
            return
        }
        val rawLx = leftX.coerceIn(-1f, 1f); val rawLy = leftY.coerceIn(-1f, 1f)
        var lx = rawLx; var ly = rawLy
        var rx = rightX.coerceIn(-1f, 1f); var ry = rightY.coerceIn(-1f, 1f)

        // Two things the runtime does for itself when it was built with them:
        // shaping the throw (runtime/stick_curve.h) and standing the left stick
        // in for the d-pad (runtime/input_stick_arrows.h). The engine this apk
        // is packaged with predates both, so the app does them here and the
        // runtime's copies are never asked. Whichever side has it, it is done
        // once -- the check is the same one that keeps those calls from being
        // made at all.
        // The left stick's feel (LeftStickFeel). Its Default numbers are the
        // ones this build always sent -- no host deadzone, no curve, no
        // smoothing, the thumb's throw as the deflection -- so an untouched
        // profile takes the identical arithmetic. The deadzone and curve are
        // done here only for the packaged engine; a runtime that has
        // stick_curve.h is given them in nativeStickSettings instead.
        val feel = prefs.leftStickFeel()
        val engineShaped = NativeBridge.stickSettings
        val left = LeftStickFeel.shape(lx, ly, feel, shapedByEngine = engineShaped)
        lx = left[0]; ly = left[1]
        if (!engineShaped) {
            val right = StickShape.apply(rx, ry, prefs.rightStickDeadzone, prefs.rightStickCurve)
            rx = right[0]; ry = right[1]
        }
        // Angle smoothing moves the direction only, and only on a sharp turn:
        // a one-frame spike is damped while a steady push keeps its pace and a
        // deliberate reversal still arrives in a frame or two.
        if (combo.flick == SwipeFlick.NONE) {
            val smoothed = leftSmoothing.apply(lx, ly, feel.smoothing)
            lx = smoothed[0]; ly = smoothed[1]
        } else {
            leftSmoothing.reset()
        }
        // A swiped stick throw: a synthetic full deflection, so it goes on after
        // the shaping -- a deadzone in front of a command the player asked for
        // by name would swallow it. It is a whole throw rather than an addition
        // to the thumb's: the title reads the stick's angle, and two sources
        // adding up would send a diagonal nobody asked for.
        if (combo.flick != SwipeFlick.NONE) {
            if (combo.flick.isLeft) {
                lx = combo.flick.x
                ly = combo.flick.y
            } else {
                rx = combo.flick.x
                ry = combo.flick.y
            }
        }
        // Sprint from the MATCH stick's rim: a thumb all the way out runs until
        // it comes back inside the latch's release point. The switch above the
        // socket is the authority, not a guess at what the title is doing -- the
        // rim runs in the MATCH half only, and the MENU half sends no RB at all,
        // so the same toggle that reads menus also decides whether a run can
        // start. It is still only the first stick (`"stick"`): Stick 2 and the
        // skill stick keep their own throws.
        val sprintThrow = stickPositions[MOVE_STICK]
        if (prefs.stickStage == Prefs.STAGE_ONE && sprintThrow != null) {
            // The ring is the profile's, with the shipped latch kept as its own
            // Default (see LeftStickFeel.sprintRelease). Read from the raw
            // throw, so a curve or a deadzone never moves where a run starts.
            sprintLatch.thresholds(feel.sprint, LeftStickFeel.sprintRelease(feel))
            sprintLatch.update(sprintThrow[0], sprintThrow[1])
        } else {
            sprintLatch.reset()
        }
        // The rim's run is let go for as long as a swipe is holding RT beside
        // its button. RB + RT is the title's super cancel, and it throws away
        // the shot the flick is asking for: a thumb sprinting at the rim and
        // flicking shoot down sends RB, then RT, then X, and the title reads
        // that pair as "cancel" instead of a finesse shot -- which is why the
        // first attempt did nothing and a later one, taken off the rim, worked.
        // The player asked for a finesse, not for a cancel, and the latch starts
        // the run again on the push after the pair ends, so nothing is lost.
        val rimRuns = sprintLatch.active && !liveSwipeRight
        mixer.set(HoldSource.STICK_SPRINT, if (rimRuns) NativeBridge.RB else 0)

        // Every source that wants a button down, merged rather than overwritten.
        // A source states the whole truth each frame, so nothing has to be
        // reference-counted across frames -- and because the sources are separate,
        // SW2's LT sequence finishing cannot lift the LT that the jockey has
        // latched, and two latches on one bit both have to be let go.
        mixer.release(HoldSource.JOCKEY_TOGGLE)
        mixer.release(HoldSource.TG1_TOGGLE)
        mixer.release(HoldSource.TG2_TOGGLE)
        mixer.release(HoldSource.TG3_TOGGLE)
        mixer.release(HoldSource.TG4_TOGGLE)
        mixer.release(HoldSource.TG5_TOGGLE)
        mixer.release(HoldSource.TG6_TOGGLE)
        var heldButtons = buttons
        var heldLeft = leftTrigger != 0
        var heldRight = rightTrigger != 0
        for (id in claims.latchIds()) {
            // A latch on a control the player has switched off, or that belongs to
            // the other pad or phase, sends nothing: the switch means off, latch
            // or no latch. The latch itself is kept, so switching it back on
            // resumes the hold rather than silently dropping it.
            val latched = layout.find(id)
            if (latched == null || !latched.shownIn(gamePhase, stance)) continue
            val hold = claims.latchedHold(id)
            when (HoldMixer.sourceFor(id)) {
                HoldSource.JOCKEY_TOGGLE, HoldSource.TG1_TOGGLE, HoldSource.TG2_TOGGLE,
                HoldSource.TG3_TOGGLE, HoldSource.TG4_TOGGLE, HoldSource.TG5_TOGGLE,
                HoldSource.TG6_TOGGLE ->
                    mixer.set(HoldMixer.sourceFor(id), hold.buttons, hold.leftTrigger, hold.rightTrigger)
                // A spare or a plain button switched to latching has no source of
                // its own: it counts as the player's own hold, and ends with the pad.
                else -> {
                    heldButtons = heldButtons or hold.buttons
                    heldLeft = heldLeft || hold.leftTrigger
                    heldRight = heldRight || hold.rightTrigger
                }
            }
        }
        mixer.set(HoldSource.PHYSICAL_TOUCH, heldButtons, heldLeft, heldRight)
        mixer.set(
            HoldSource.MACRO_SEQUENCE, combo.buttons, combo.leftTrigger, combo.rightTrigger,
        )
        // The modifier of a live swipe, held while the thumb is still charging
        // the button -- and, for a direction only read at the lift, the button
        // itself carried one beat past the finger so the title polls the pair.
        mixer.set(
            HoldSource.SWIPE_HOLD, liveSwipeButtonBits or liveSwipeHoldBits,
            liveSwipeLeft, liveSwipeRight,
        )
        buttons = mixer.buttons
        leftTrigger = if (mixer.leftTrigger) 255 else 0
        rightTrigger = if (mixer.rightTrigger) 255 else 0

        if (!NativeBridge.dpadSynthesis) {
            if (dpadOnScreen() || gamePhase == GamePhase.MATCH) {
                // The player's own d-pad is on screen, or there is a match in
                // progress and nothing left to navigate: the same four bits
                // from two places is how a menu scrolls twice per press, and
                // an arrow pressed by a sprinting thumb is a tactic nobody
                // asked for.
                stickArrows.clear()
            } else {
                // The raw throw, not the shaped one: the press point is 0.60 of
                // the socket as the engine measures it, and a menu reached at
                // three-quarters of the way out is a menu with a thumb through
                // it.
                buttons = buttons or stickArrows.bits(rawLx, rawLy)
            }
        }

        // A tap that began and ended between two polls has no other way to be
        // seen.
        if (pressed != 0) {
            if (NativeBridge.touchPulse) {
                NativeBridge.nativeTouchPulse(pressed)
            } else {
                pulseBits = pulseBits or pressed
                pulseUntil = now + pulseFrameMs
                removeCallbacks(pulseClear)
                postDelayed(pulseClear, pulseFrameMs)
            }
        }
        if (now < pulseUntil) buttons = buttons or pulseBits

        // The run the title is being told about, for the MATCH stick's halo.
        // Taken from the merged state, so a latched or commanded RB lights it
        // the same way a thumb on the Sprint control does.
        sprintHeld = (buttons and NativeBridge.RB) != 0

        trace.state(now, buttons, leftTrigger, rightTrigger, lx, ly, rx, ry)?.let {
            Log.i(TouchTraceGate.TAG, it)
        }
        Macro.record(Macro.TOUCH, buttons, leftTrigger, rightTrigger, lx, ly, rx, ry)
        NativeBridge.nativeTouchState(buttons, leftTrigger, rightTrigger, lx, ly, rx, ry)
    }

    /** Everything up: called when the pad is hidden, edited, or paused. */
    fun releaseEverything() {
        stickPositions.clear()
        floatPointer = -1
        pressAt.clear()
        liveSwipes.clear()
        clearLiveSwipe()
        passDoubleTap.reset()
        swipeTrails.clear()
        skillReleaseUntil.clear()
        resetTwoStage()
        sprintLatch.reset()
        leftSmoothing.reset()
        sprintHeld = false
        cancelCombo()
        // Everything up is everything up, latches included. A pause, a reset or
        // the editor is exactly when a held bit would be invisible to the player
        // and stuck in the game, so a latch does not outlive the pad it was set
        // on: the jockey button starts off again on the next kick-off.
        claims.clearAll()
        dpadBits.clear()
        swipeClassifier.clear()
        pendingSwipes = 0
        lookPointers.clear()
        skillPointer = -1
        removeCallbacks(wheelTick)
        wheel.cancel()
        burstCadence.reset()
        mentality.reset()
        trace.reset()
        // Everything up means everything: a trigger does not outlive the pad,
        // and neither does a tap still riding out its frame or an arm the stick
        // was holding down.
        triggerReleaseAt.clear()
        stickArrows.clear()
        pulseBits = 0
        pulseUntil = 0L
        steerHeldSince = 0L
        padSteerSince = 0L
        removeCallbacks(kickOffProbe)
        removeCallbacks(padKickOffProbe)
        removeCallbacks(pulseClear)
        if (NativeBridge.isLoaded()) {
            NativeBridge.nativeLookEnd()
            if (NativeBridge.skillStick) NativeBridge.nativeSkillEnd()
            pushState()
        }
        syncDpadSynthesis()
        invalidate()
    }

    /**
     * The stick stands in for the d-pad only while no d-pad of the player's own
     * is on screen: the same four bits from two places is how a menu ends up
     * scrolling twice per press.
     */
    private fun syncDpadSynthesis() {
        if (!NativeBridge.isLoaded()) return
        // Through the safe wrapper: it answers the "does this engine have it"
        // question once, catches a missing symbol instead of letting it leave a
        // touch event, and reports false so the stand-in in pushState keeps
        // working. Nothing about the d-pad's bits or drawing changes here.
        NativeBridge.setDpadSynthesis(!dpadOnScreen())
    }

    /** Whether a d-pad of the player's own is on screen in this phase. */
    private fun dpadOnScreen(): Boolean =
        layout.elements.any { it.kind == ControlKind.DPAD && it.shownIn(gamePhase, stance) }

    // ---- the d-pad's idle fade ----------------------------------------------

    /** Only a match with the preference on and a d-pad on screen has a clock. */
    private fun dpadAutoHideApplies(): Boolean =
        prefs.dpadAutoHide && !editing && gamePhase == GamePhase.MATCH && dpadOnScreen()

    /** Visibility of the d-pad right now, 1 when it is not fading at all. */
    private fun dpadAlpha(): Float =
        if (dpadAutoHideApplies()) dpadAutoHide.alpha(SystemClock.uptimeMillis()) else 1f

    /** Whether a touch should miss the d-pad because it has faded out. */
    private fun dpadIsHidden(): Boolean =
        dpadAutoHideApplies() && dpadAutoHide.hidden

    /** A generous ring around the d-pad: anywhere a thumb aiming at it lands. */
    private fun nearDpad(x: Float, y: Float): Boolean {
        val dpad = layout.find("dpad") ?: return false
        val reach = radius(dpad) * 1.6f + 12f * density
        return hypot(x - centreX(dpad), y - centreY(dpad)) <= reach
    }

    /**
     * Starts the idle countdown from fully visible. Called when the pad comes
     * on screen, when the phase or stance changes, and when the d-pad is
     * touched; not from drawing, or the clock would never run.
     */
    private fun refreshDpad() {
        removeCallbacks(dpadTick)
        val now = SystemClock.uptimeMillis()
        dpadAutoHide.reset(now)
        if (dpadAutoHideApplies()) postDelayed(dpadTick, DpadAutoHide.IDLE_MS)
        invalidate()
    }

    /** A touch on or beside the d-pad: instantly opaque again, clock restarted. */
    private fun wakeDpad() {
        if (!dpadAutoHideApplies()) return
        dpadAutoHide.reset(SystemClock.uptimeMillis())
        removeCallbacks(dpadTick)
        postDelayed(dpadTick, DpadAutoHide.IDLE_MS)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshDpad()
        // A fresh pad has no phase yet: with auto on, the switch starts on MENU,
        // which is where launch and an activity rebuild both land.
        reconsiderStick()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(dpadTick)
        // The wheel's one redraw tick goes with the view. Its visibility is
        // derived, so a stale tick cannot pin it open; what must not survive is
        // a posted callback holding the detached view alive.
        removeCallbacks(wheelTick)
        wheel.cancel()
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
                    // Sliding a thumb into the faded d-pad's area wakes it: the
                    // reach and the tap are the same intent, and making the
                    // player lift and press again is the version they notice.
                    if (!editing && dpadIsHidden() && nearDpad(x, y)) wakeDpad()
                    if (lookPointers.containsKey(id)) {
                        // Every sample between frames matters for a smooth
                        // turn, so the batched history is used too.
                        for (h in 0 until event.historySize) {
                            sendLook(id, event.getHistoricalX(index, h), event.getHistoricalY(index, h))
                        }
                        sendLook(id, x, y)
                        continue
                    }
                    val element = claims.elementFor(id) ?: continue
                    when (element.kind) {
                        ControlKind.STICK -> {
                            if (id == skillPointer) {
                                // Every sample between frames matters here too:
                                // a flick that turns inside one batched event is
                                // two directions to the title, not one.
                                for (h in 0 until event.historySize) {
                                    sendSkill(event.getHistoricalX(index, h), event.getHistoricalY(index, h))
                                }
                                sendSkill(x, y)
                                // The same thumb is also the pad's own stick: it
                                // has to move, or the knob under the finger sits
                                // at centre while the shot is being aimed.
                                moveStick(element, x, y, id)
                            } else {
                                moveStick(element, x, y, id)
                                // A sustained deflection of the match stick is
                                // the kick-off. One sample is not: a thumb
                                // nudging a lineup screen crosses it too, and
                                // moving through the title's menu is not play.
                                // The probe covers a thumb that holds still.
                                if (kickOffPending && element.id == MOVE_STICK) {
                                    if (!steeringMatch()) {
                                        steerHeldSince = 0L
                                        removeCallbacks(kickOffProbe)
                                    } else {
                                        val now = SystemClock.uptimeMillis()
                                        if (steerHeldSince == 0L) {
                                            steerHeldSince = now
                                            removeCallbacks(kickOffProbe)
                                            postDelayed(kickOffProbe, KICK_OFF_STEER_MS)
                                        } else if (now - steerHeldSince >= KICK_OFF_STEER_MS) {
                                            noteKickOff("the match stick held")
                                        }
                                    }
                                }
                                changed = true
                            }
                        }
                        ControlKind.DPAD -> {
                            // Changing direction under a held finger is the
                            // point of a d-pad: the new arm replaces the old
                            // one immediately, and nothing waits for a lift.
                            val arm = dpadArm(element, x, y)
                            if (dpadBits[id] != arm) { dpadBits[id] = arm; changed = true }
                        }
                        ControlKind.SWIPE -> {
                            feedSwipeHistory(event, index)
                            swipeClassifier.move(id, x, y)
                            val peek = swipeClassifier.peek(id)
                            if (peek != (liveSwipes[id] ?: Swipe.NONE)) {
                                liveSwipes[id] = peek
                                if (peek != Swipe.NONE) triggerHaptic()
                                changed = true
                            }
                        }
                        ControlKind.BUTTON, ControlKind.CUSTOM, ControlKind.TACTICAL -> {
                            // The same four directions, on the control the thumb
                            // is already on. Only the drawing reads the peek;
                            // the bit is sent when the finger lifts. The redraw
                            // is part of the change: without it the arrows and
                            // the action name only appeared after the lift,
                            // which made a working swipe look ignored.
                            feedSwipeHistory(event, index)
                            swipeClassifier.move(id, x, y)
                            val peek = swipeClassifier.peek(id)
                            if (peek != (liveSwipes[id] ?: Swipe.NONE)) {
                                liveSwipes[id] = peek
                                updateLiveSwipe(id, element, peek)
                                // A direction lock ticks once, so the flick is
                                // known to have registered before the thumb
                                // even lifts.
                                if (peek != Swipe.NONE) triggerHaptic()
                                changed = true
                            }
                        }
                        ControlKind.TRICK -> {
                            if (id != wheel.pointer) continue
                            val now = SystemClock.uptimeMillis()
                            // The sector follows the thumb from the moment it
                            // lands, so the ring is already showing the right
                            // move when it opens; only a change after that is
                            // worth a tick.
                            val before = wheel.sector
                            wheel.aim(x - wheel.originX, y - wheel.originY, radius(element))
                            if (wheel.sector != before) {
                                if (wheel.isOpen(now) && wheel.sector >= 0) triggerHaptic()
                                changed = true
                            }
                            // Opening is a repaint as well: if the hold ran out
                            // between two samples the ring appears on this one
                            // instead of waiting for a callback.
                            if (wheel.isOpen(now)) changed = true
                        }
                        else -> Unit
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                changed = release(event.getPointerId(index), event.getX(index), event.getY(index))
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

    /** One tick, through the app's one haptics policy. */
    private fun triggerHaptic(event: HapticEvent = HapticEvent.TAP) {
        haptics.tick(this, event)
    }

    private fun press(pointerId: Int, x: Float, y: Float): Boolean {
        // A tap on or beside the hidden d-pad brings it back. A tap squarely on
        // where it sits only reveals it (no arm fires from an invisible
        // control); one in the wider ring still reaches its neighbour, so the
        // wake zone never swallows a pass or a camera drag.
        if (!editing && dpadIsHidden() && nearDpad(x, y)) {
            wakeDpad()
            val dpad = layout.find("dpad")
            if (dpad != null && hit(dpad, x, y)) return false
        }
        val element = elementAt(x, y) ?: return false
        if (element.kind == ControlKind.LOOK) {
            lookPointers[pointerId] = floatArrayOf(x, y)
            return false
        }
        triggerHaptic()
        pressAt[pointerId] = floatArrayOf(x, y)
        markPressed(element)
        // START is the title's pause/menu key. Follow it while the switch is on
        // auto, so the title's in-match menu gets the MENU stick even though
        // the engine still calls the match flow a match.
        if (element.kind == ControlKind.BUTTON && element.value == NativeBridge.START &&
            !element.toggle
        ) {
            // A toggle that holds START is a latch, not the player pressing the
            // title's pause key, so it must not move the switch either way.
            noteStartPressed()
        }
        if (element.kind == ControlKind.DPAD) wakeDpad()
        // Pressed again inside its grace, a trigger is held, not released.
        if (element.kind == ControlKind.TRIGGER) triggerReleaseAt.remove(element.id)
        // A bounce of the sprint button is still a hold; it just does not need
        // a pulse as well, which is what a double tap is built out of.
        val pulse = element.kind != ControlKind.BUTTON || element.value != NativeBridge.RB ||
            burstCadence.accept(SystemClock.uptimeMillis())
        // Recorded, not assigned: a second finger on the same control must not
        // take it from the first, and the control stays down until both lift.
        claims.press(pointerId, element, pulse)
        when (element.kind) {
            ControlKind.STICK -> when {
                // The movement stick, floating: its socket is the thumb's
                // landing point, and it starts centred under it.
                element.value == 0 && floatingStick -> {
                    // A second thumb on the stick shares the socket the first
                    // one placed, rather than teleporting it out from under it.
                    if (floatPointer < 0) {
                        floatPointer = pointerId
                        floatOriginX = x
                        floatOriginY = y
                    }
                    stickPositions[element.id] = floatArrayOf(0f, 0f)
                }
                element.value != 0 && flicking -> {
                    // The flick is measured from here: wherever the thumb found
                    // the glass is the origin, so there is no centre to hunt for
                    // between one move and the next. The same thumb also states
                    // the stick's own deflection, so the right stick answers as
                    // an analogue stick while the flick classifier watches for
                    // the moves.
                    skillPointer = pointerId
                    skillOriginX = x
                    skillOriginY = y
                    moveStick(element, x, y, pointerId, fromPress = true)
                    if (NativeBridge.skillStick) NativeBridge.nativeSkillTouch(0f, 0f)
                }
                else -> moveStick(element, x, y, pointerId, fromPress = true)
            }
            ControlKind.DPAD -> dpadBits[pointerId] = dpadArm(element, x, y)
            ControlKind.SWIPE -> swipeClassifier.begin(
                pointerId, x, y, SystemClock.uptimeMillis(), SwipeClassifier.CARDINAL_DOMINANCE,
            )
            ControlKind.BUTTON -> if (!element.toggle) {
                swipeClassifier.begin(
                    pointerId, x, y, SystemClock.uptimeMillis(), SwipeClassifier.CARDINAL_DOMINANCE,
                )
                // Double-tapping pass is the one-two. The single tap is still a
                // single pass: the pair only adds L1 + pass on the second press,
                // so a tap that misses the window is simply two passes.
                if (element.role == ControlRole.PASS &&
                    passDoubleTap.press(SystemClock.uptimeMillis()) &&
                    NativeBridge.isLoaded()
                ) {
                    emitSwipe(SwipeAction.held(SwipeModifier.LB, element.value))
                    triggerHaptic()
                }
            }
            // A spare button holds no bit of its own: its centre tap and its four
            // swipes are all read on the lift, like any other swipe -- and with
            // the same nearest-axis rule as the action buttons, so C1..C10 feel
            // identical to pass, shoot, cross and through.
            ControlKind.CUSTOM -> swipeClassifier.begin(
                pointerId, x, y, SystemClock.uptimeMillis(), SwipeClassifier.CARDINAL_DOMINANCE,
            )
            // The tactical button reads its four arms on the lift, like the
            // patch does; its centre tap is the fifth action and is bound the
            // same way, so nothing is sent while the thumb is down.
            ControlKind.TACTICAL -> swipeClassifier.begin(
                pointerId, x, y, SystemClock.uptimeMillis(), SwipeClassifier.CARDINAL_DOMINANCE,
            )
            // A toggle latches in TouchClaims itself, so there is nothing to
            // send here: its command is held from the next push onwards. The
            // two custom toggles may also name a saved layout: engaging one
            // puts that pad on screen, which is a mapping switch a thumb can
            // perform without opening the editor.
            ControlKind.LATCH -> if (claims.isToggled(element.id) &&
                element.toggleProfile.isNotEmpty()
            ) {
                switchToProfile(element.toggleProfile)
            }
            // One tap, one move: the runtime plays it on the skill stick and
            // holds the stick until its last frame, so nothing here has to.
            ControlKind.SKILL -> if (NativeBridge.skillStick) NativeBridge.nativeSkillPlay(element.value)
            // The wheel opens on a hold, and the move is chosen by where the
            // thumb is when it lifts: nothing to send while it is down.
            ControlKind.TRICK -> {
                // A wheel already open owns the gesture. A second thumb landing
                // on the button must not close it or move its origin out from
                // under the first; WheelGesture says whether this press took it.
                if (wheel.press(pointerId, x, y, SystemClock.uptimeMillis())) {
                    removeCallbacks(wheelTick)
                    postDelayed(wheelTick, TrickWheel.HOLD_MS)
                    invalidate()
                }
            }
            else -> Unit
        }
        return true
    }

    private fun release(pointerId: Int, x: Float, y: Float): Boolean {
        if (lookPointers.remove(pointerId) != null) {
            // A turn ends when the last finger on the look area lifts.
            if (lookPointers.isEmpty()) NativeBridge.nativeLookEnd()
            return false
        }
        val released = claims.release(pointerId) ?: return false
        // The lift's own coordinates are the last sample of a gesture. A fast
        // flick can travel the whole way inside the UP event -- the thumb leaves
        // the glass between two move batches -- and a classifier read from the
        // last MOVE alone called that a tap and dropped the shot.
        swipeClassifier.move(pointerId, x, y)
        markReleased(released.element)
        val pressedAt = pressAt.remove(pointerId)
        dpadBits.remove(pointerId)
        // The stage belongs to this finger and goes with it; another thumb's
        // share of the same socket is not disturbed.
        twoStage.release(pointerId)
        if (pointerId == twoStagePointer) {
            twoStagePointer = -1
            twoStageSprint = false
            stickRaw.remove(released.element.id)
        }
        // The lift counts as activity too: a long hold should not make the
        // d-pad vanish the instant it is let go.
        if (released.element.kind == ControlKind.DPAD) wakeDpad()
        // A steering run ends with the thumb; a new one starts its own clock.
        if (released.element.kind == ControlKind.STICK && released.element.id == MOVE_STICK) {
            steerHeldSince = 0L
            removeCallbacks(kickOffProbe)
        }
        liveSwipes.remove(pointerId)
        // The two-stage switch: one tap, one flip, and a thumb that slid off it
        // said something else. Nothing is sent -- the mode changes what the left
        // stick reads on its next sample.
        if (released.element.kind == ControlKind.STICK_MODE) {
            val start = pressedAt
            val tapSlop = 18f * density
            if (start == null || hypot(x - start[0], y - start[1]) <= tapSlop) {
                // With auto on, a tap is an override the player asked for: it
                // stands until the game changes state, and auto has the switch
                // back after that. Auto itself is turned off in the pause menu,
                // so a stray tap can never quietly take the switch over.
                if (prefs.stickAuto) stickOverride = StickSwitch.key(gamePhase, paused, titleMenu)
                val stage =
                    if (prefs.stickStage == Prefs.STAGE_TWO) Prefs.STAGE_ONE else Prefs.STAGE_TWO
                setStickStage(stage, automatic = false)
            }
        }
        // The team mentality pair: one tap sends the title's own double press of
        // the d-pad. A thumb that slid off said something else, a hold sends
        // nothing until it lifts, and a second tap while the first sequence is
        // still playing is dropped rather than queued twice.
        if (released.element.kind == ControlKind.MENTALITY) {
            val start = pressedAt
            val tapSlop = 18f * density
            val now = SystemClock.uptimeMillis()
            if ((start == null || hypot(x - start[0], y - start[1]) <= tapSlop) &&
                mentality.accept(now)
            ) {
                triggerHaptic()
                macro.enqueue(SwipeAction.double(released.element.value), now)
                scheduleMacro()
            }
        }
        if (released.element.kind == ControlKind.BUTTON) {
            if (released.element.role == ControlRole.PASS) {
                passDoubleTap.release(SystemClock.uptimeMillis())
            }
            // A swipe on the button the thumb is already on: the held bit has
            // been sent all along, and a bound direction is what the lift adds.
            finishSwipe(pointerId, released.element, released.lastClaim)
        }
        if (released.element.kind == ControlKind.CUSTOM ||
            released.element.kind == ControlKind.TACTICAL
        ) {
            // Nothing was held, so the tap itself is the fifth action: an unbound
            // direction on a spare or a tactical button sends `swipeTap`, which
            // is how one press of it plays a command.
            finishSwipe(pointerId, released.element, released.lastClaim)
        }
        if (released.element.kind == ControlKind.SWIPE) {
            // One gesture, one action. Another finger still on the patch keeps
            // its own gesture, but this one is over and must not fire on top.
            val direction = if (released.lastClaim) {
                swipeClassifier.end(pointerId, SystemClock.uptimeMillis())
            } else {
                swipeClassifier.cancel(pointerId)
                Swipe.NONE
            }
            val action = released.element.swipeAction(direction)
            Log.i(TouchTraceGate.TAG, TouchTraceGate.swipe(released.element.id, direction, action))
            if (SwipePriority.sendsSwipe(action)) emitSwipe(action)
            noteSwipeTrail(released.element, direction)
        }
        if (released.element.kind == ControlKind.TRICK) {
            // Only the owning finger's lift may close the wheel; a second
            // thumb's lift is not a lift of the wheel. A tap does nothing at
            // all: only a wheel that was actually held plays a move.
            val done = wheel.release(pointerId, SystemClock.uptimeMillis())
            if (done != null) {
                removeCallbacks(wheelTick)
                if (done.wasHold) {
                    val move = released.element.trickMove(done.sector)
                    if (move >= 0 && NativeBridge.skillStick) NativeBridge.nativeSkillPlay(move)
                }
                invalidate()
            }
        }
        // Another finger still on the stick keeps its deflection; the last one
        // to lift centres it.
        if (released.element.kind == ControlKind.STICK) {
            skillReleaseUntil.remove(released.element.id)
            if (released.lastClaim) {
                // The absolute skill stick is read by polling, so lifting in the
                // same frame as a flick can leave the last sample unseen: the
                // last deflection is held for 90 ms, and a fresh press cancels
                // it. The flick path has its own release, so it is not held.
                if (!flicking && released.element.value != 0) {
                    val id = released.element.id
                    val until = SystemClock.uptimeMillis() + SKILL_RELEASE_HOLD_MS
                    skillReleaseUntil[id] = until
                    postDelayed({
                        if (skillReleaseUntil[id] != until) return@postDelayed
                        skillReleaseUntil.remove(id)
                        stickPositions.remove(id)
                        if (NativeBridge.isLoaded()) pushState()
                        invalidate()
                    }, SKILL_RELEASE_HOLD_MS)
                } else {
                    stickPositions.remove(released.element.id)
                }
            }
            // The socket goes back to where the layout put it when the last
            // floating thumb lifts.
            if (pointerId == floatPointer) floatPointer = -1
            // A flick belongs to the finger that made it, not to the control: a
            // second thumb left on the stick must not inherit the gesture.
            if (pointerId == skillPointer) {
                skillPointer = -1
                if (NativeBridge.skillStick) NativeBridge.nativeSkillEnd()
            }
        }
        // A latched trigger is not waiting to be let go: its latch owns it, and
        // a release grace here would fight the second tap that unlatches it.
        if (released.element.kind == ControlKind.TRIGGER && released.lastClaim &&
            !released.element.toggle
        ) {
            val id = released.element.id
            val until = SystemClock.uptimeMillis() + triggerGraceMs
            triggerReleaseAt[id] = until
            // Without this the guest would keep seeing the trigger down: it
            // holds the last state it was given, so the release has to be sent.
            postDelayed({
                if (triggerReleaseAt[id] != until) return@postDelayed   // pressed again since
                triggerReleaseAt.remove(id)
                if (NativeBridge.isLoaded()) pushState()
                invalidate()
            }, triggerGraceMs)
        }
        return true
    }

    /**
     * Which arm of a d-pad a touch is on, or 0 in its middle. The nearer axis
     * wins, so a thumb between two arms goes the way it is actually leaning
     * rather than whichever arm was tested first.
     */
    private fun dpadArm(element: ControlElement, x: Float, y: Float): Int {
        val dx = x - centreX(element)
        val dy = y - centreY(element)
        if (hypot(dx, dy) < radius(element) * 0.30f) return 0
        return if (abs(dx) > abs(dy)) {
            if (dx > 0f) NativeBridge.RIGHT else NativeBridge.LEFT
        } else {
            if (dy > 0f) NativeBridge.DOWN else NativeBridge.UP
        }
    }

    /**
     * Sends one bound swipe. A plain bit rides the existing press pulse, which
     * is one poll wide. A modifier or a double tap is a short sequence of held
     * states (SwipeSequence): each step is scheduled, applied to [combo] and
     * pushed, and the last step clears everything.
     */
    private fun emitSwipe(action: SwipeAction) {
        if (!action.bound) return
        // A plain tap-sized bit rides the press pulse. Anything with a shape to
        // it -- a held modifier, a double tap, a stick throw, or a dwell the
        // binding asked for -- is a sequence of held states.
        if (action.modifier == SwipeModifier.NONE && !action.doubleTap &&
            action.flick == SwipeFlick.NONE && action.holdMs == SwipeSequence.HOLD_MS
        ) {
            pendingSwipes = pendingSwipes or action.bit
            return
        }
        // Queued behind whatever is still playing, with a beat between the two,
        // so the tail of one sequence and the head of the next cannot interleave.
        macro.enqueue(action, SystemClock.uptimeMillis())
        scheduleMacro()
    }

    /** One timer for the whole queue: it wakes for the next step and no more. */
    private fun scheduleMacro() {
        removeCallbacks(macroPump)
        val due = macro.nextDueMs() ?: return
        postDelayed(macroPump, (due - SystemClock.uptimeMillis()).coerceAtLeast(0L))
    }

    /**
     * Applies every step that has come due, in order, then waits for the next.
     * Steps are whole held states, so the last one due is the state now; the
     * sequence's own final step clears everything, which is why nothing can be
     * left down when the queue empties.
     */
    private fun pumpMacro() {
        val due = macro.takeDue(SystemClock.uptimeMillis())
        if (due.isNotEmpty()) {
            combo.apply(due[due.size - 1])
            if (NativeBridge.isLoaded()) pushState()
            invalidate()
        }
        if (macro.busy) {
            scheduleMacro()
        } else if (combo.any) {
            combo.clear()
            if (NativeBridge.isLoaded()) pushState()
            invalidate()
        }
    }

    /**
     * Stops a compound swipe and lets go of everything it was holding. Called by
     * every cancellation path -- lift of the next swipe, ACTION_CANCEL, pause,
     * focus loss, the editor, the pad being hidden -- so a modifier or trigger
     * can never be left down.
     */
    private fun cancelCombo() {
        removeCallbacks(macroPump)
        macro.clear()
        combo.clear()
    }

    /** The arms every finger on this d-pad is holding. */
    private fun dpadMaskFor(element: ControlElement): Int {
        var bits = 0
        for ((pointerId, arm) in dpadBits) {
            if (claims.elementFor(pointerId)?.id == element.id) bits = bits or arm
        }
        return bits
    }

    private fun sendLook(pointerId: Int, x: Float, y: Float) {
        val last = lookPointers[pointerId] ?: return
        val dx = x - last[0]
        val dy = y - last[1]
        last[0] = x
        last[1] = y
        if (dx != 0f || dy != 0f) NativeBridge.nativeLookDelta(dx, dy)
    }

    /**
     * The throw a stick is measured against.
     *
     * The whole socket, not the knob's smaller travel: a thumb on the rim is
     * full deflection, which is what lets the sprint latch's 0.92 press point
     * sit where a hand can actually find it instead of beyond the control's own
     * edge. The skill stick is deliberately shorter -- 55% of the socket -- a
     * skill move being a short flick to a sector rather than a pace to hold.
     */
    private fun throwRadius(element: ControlElement): Float {
        val r = radius(element)
        return if (element.value == 0) r else r * SKILL_THROW
    }

    /**
     * Reads the lift as one of four directions and sends what it is bound to.
     * Shared by an action button and a spare: the button keeps its held bit, and
     * both add the direction the thumb ended on.
     */
    private fun finishSwipe(pointerId: Int, element: ControlElement, lastClaim: Boolean) {
        val direction = if (lastClaim) {
            swipeClassifier.end(pointerId, SystemClock.uptimeMillis())
        } else {
            swipeClassifier.cancel(pointerId)
            Swipe.NONE
        }
        val action = element.swipeAction(direction)
        // If the modifier was already held alongside the charging button, the
        // title has the combination and the lift releases it; replaying the
        // sequence would send the same command twice.
        val alreadyHeld = liveSwipeHeldFor(action)
        // An ordinary tap on a button is silent here -- it already has its held
        // bit -- but a flick that charged a modifier and then read as no
        // direction is exactly the dropped shot this log exists for.
        if (direction != Swipe.NONE || hasLiveModifier()) {
            Log.i(TouchTraceGate.TAG, TouchTraceGate.swipe(element.id, direction, action))
        }
        if (carriesHeldButton(element, action)) {
            // The button the thumb pressed is still down in this push, and it is
            // the bit the command wants: the modifier is armed beside it -- it
            // may only have become known as the finger lifted -- and both are
            // held one beat past the lift. That is one held pair, not a lift
            // followed by a fresh press, so a sweep that resolved late plays the
            // controlled shot instead of an ordinary one.
            if (!alreadyHeld) applyLiveSwipe(pointerId, action, push = false)
            liveSwipeHoldBits = action.bit
            holdLiveSwipeThroughTail()
        } else {
            if (!alreadyHeld && SwipePriority.sendsSwipe(action)) emitSwipe(action)
            if (alreadyHeld) {
                // The modifier has been down and the title has seen it, so the
                // sequence is not replayed; it is held one beat past the
                // button's lift instead. Releasing both in this push is what
                // turned a chip shot into an ordinary one whenever the title's
                // poll came after it.
                holdLiveSwipeThroughTail()
            } else {
                clearLiveSwipe(pointerId)
            }
        }
        noteSwipeTrail(element, direction)
    }

    /**
     * Whether this lift can keep the button the thumb pressed and add the
     * modifier to it, rather than replaying the whole combination.
     *
     * Only an action button that is already sending its own bit qualifies: a
     * spare or a tactical button holds nothing, a double tap is by definition a
     * second press, a stick flick is not a button at all, and a direction bound
     * to a different button than the one under the thumb has no held pair to
     * keep.
     */
    private fun carriesHeldButton(element: ControlElement, action: SwipeAction): Boolean =
        element.kind == ControlKind.BUTTON && element.value != 0 &&
            action.bound && !action.doubleTap && action.flick == SwipeFlick.NONE &&
            action.modifier != SwipeModifier.NONE && action.bit == element.value

    /**
     * Keeps the modifier of the live swipe down beside the button that is being
     * held. Only a button with a real held bit can do this: a spare or a
     * tactical button sends nothing until the lift, so a modifier alone there
     * would be a command with no button.
     */
    private fun updateLiveSwipe(pointerId: Int, element: ControlElement, direction: Swipe) {
        if (element.kind != ControlKind.BUTTON) return
        // A second thumb's swipe does not take the first one's modifier; it
        // still gets its own sequence on the lift.
        if (liveSwipePointer >= 0 && liveSwipePointer != pointerId && hasLiveModifier()) return
        val action = if (direction == Swipe.NONE) SwipeAction.NONE else element.swipeAction(direction)
        applyLiveSwipe(pointerId, action)
    }

    /**
     * Holds [action]'s modifier beside whatever button is already down, or lets
     * go when the action has no modifier.
     *
     * The bits alone do not decide when a hold began: a direction that flickers
     * to NONE and back is the same hold, so the arming time is kept across it
     * and only a different modifier, or a different finger, restarts the clock.
     */
    private fun applyLiveSwipe(pointerId: Int, action: SwipeAction, push: Boolean = true) {
        val modifierOnly = action.bound && !action.doubleTap && action.modifier != SwipeModifier.NONE
        val buttons = if (modifierOnly) action.modifier.bit else 0
        val left = modifierOnly && action.modifier == SwipeModifier.LT
        val right = modifierOnly && action.modifier == SwipeModifier.RT
        val disarmed = buttons == 0 && !left && !right
        val unchanged = buttons == liveSwipeButtonBits && left == liveSwipeLeft &&
            right == liveSwipeRight
        if (unchanged && (disarmed || pointerId == liveSwipePointer)) return
        if (disarmed) {
            // The bits go, the clock stays: see liveSwipeArmedAt.
            liveSwipeSince = 0L
        } else {
            // A hold that comes on after being off, or under another finger, is
            // a new hold: the tail of the previous one no longer owns it.
            if (!hasLiveModifier() || pointerId != liveSwipePointer) liveSwipeEpoch++
            val same = buttons == liveSwipeArmedButtons && left == liveSwipeArmedLeft &&
                right == liveSwipeArmedRight
            if (!same || liveSwipeArmedAt == 0L) {
                liveSwipeArmedAt = SystemClock.uptimeMillis()
                liveSwipeArmedButtons = buttons
                liveSwipeArmedLeft = left
                liveSwipeArmedRight = right
            }
            liveSwipePointer = pointerId
            liveSwipeSince = liveSwipeArmedAt
        }
        liveSwipeButtonBits = buttons
        liveSwipeLeft = left
        liveSwipeRight = right
        if (push && NativeBridge.isLoaded()) pushState()
    }

    /** Keeps the live pair down one beat past the lift, for the title's poll. */
    private fun holdLiveSwipeThroughTail() {
        removeCallbacks(liveSwipeTail)
        liveSwipeTailEpoch = liveSwipeEpoch
        postDelayed(liveSwipeTail, LIVE_MODIFIER_TAIL_MS)
    }

    /**
     * Feeds a batched move event's earlier samples to the swipe classifier.
     *
     * The samples before the one this event carries are part of the same
     * travel, so a fast flick no longer reaches the classifier as one jump from
     * where it started to wherever it happened to be when a frame next arrived.
     * What the lift reads is still where the thumb ended up -- a gesture that
     * comes back to where it landed is a tap, however far it wandered -- so the
     * history can only fill in the samples, never invent a direction.
     */
    private fun feedSwipeHistory(event: MotionEvent, index: Int) {
        if (event.historySize <= 0) return
        val id = event.getPointerId(index)
        for (h in 0 until event.historySize) {
            swipeClassifier.move(
                id, event.getHistoricalX(index, h), event.getHistoricalY(index, h),
            )
        }
    }

    private fun hasLiveModifier(): Boolean =
        liveSwipeButtonBits != 0 || liveSwipeLeft || liveSwipeRight

    /** Whether [action]'s modifier has been held long enough for the title to see it. */
    private fun liveSwipeHeldFor(action: SwipeAction): Boolean {
        if (!action.bound || action.doubleTap || action.modifier == SwipeModifier.NONE) return false
        if (liveSwipeSince <= 0L) return false
        val held = when (action.modifier) {
            SwipeModifier.LT -> liveSwipeLeft
            SwipeModifier.RT -> liveSwipeRight
            else -> action.modifier.bit != 0 && (liveSwipeButtonBits and action.modifier.bit) != 0
        }
        if (!held) return false
        return SystemClock.uptimeMillis() - liveSwipeSince >= LIVE_MODIFIER_MIN_MS
    }

    /** Lets go of the live swipe's modifier; the release push carries both bits up. */
    private fun clearLiveSwipe(pointerId: Int = -1) {
        if (pointerId >= 0 && pointerId != liveSwipePointer) return
        removeCallbacks(liveSwipeTail)
        liveSwipePointer = -1
        liveSwipeSince = 0L
        liveSwipeButtonBits = 0
        liveSwipeLeft = false
        liveSwipeRight = false
        liveSwipeHoldBits = 0
        liveSwipeArmedAt = 0L
        liveSwipeArmedButtons = 0
        liveSwipeArmedLeft = false
        liveSwipeArmedRight = false
    }

    /**
     * TG1/TG2's mapping: puts a saved pad on screen with no editor in sight.
     * Everything is let go first -- the latch that asked for the switch
     * included -- so no bit from the old pad can survive into the new one.
     */
    private fun switchToProfile(id: String) {
        val loaded = ControlLayout.loadSlot(context, id)
        if (loaded == null) {
            Toast.makeText(context, R.string.profile_failed, Toast.LENGTH_SHORT).show()
            return
        }
        releaseEverything()
        layout.replaceWith(loaded)
        selected = null
        onSelectionChanged?.invoke(null)
        onLayoutEdited?.invoke()
        Toast.makeText(context, R.string.layout_switched, Toast.LENGTH_SHORT).show()
        invalidate()
    }

    /** Remembers a released direction so its trail can fly out and fade. */
    private fun noteSwipeTrail(element: ControlElement, direction: Swipe) {
        if (direction == Swipe.NONE) return
        swipeTrails[element.id] = SwipeTrail(direction, SystemClock.uptimeMillis())
        invalidate()
    }

    /** Whether this stick is the movement stick held by a floating thumb. */
    private fun isFloating(element: ControlElement): Boolean =
        floatingStick && element.value == 0 && floatPointer >= 0 &&
            claims.elementFor(floatPointer)?.id == element.id

    /** Where a stick's socket is drawn, and measured from. */
    private fun stickBaseX(element: ControlElement): Float =
        if (isFloating(element)) floatOriginX else centreX(element)

    private fun stickBaseY(element: ControlElement): Float =
        if (isFloating(element)) floatOriginY else centreY(element)

    private fun moveStick(
        element: ControlElement, x: Float, y: Float, pointerId: Int, fromPress: Boolean = false,
    ) {
        // The knob's own travel is the throw: pushing it to its rim is full
        // speed, which is the travel the hand can see. The longer throw is kept
        // for players who learned on it (Prefs.travelMatchesKnob). The socket
        // does not move under the thumb: a floating stick is placed where the
        // thumb landed and stays there, so the deflection is always relative to
        // the same base.
        val twoStaged = isTwoStage(element)
        // The two-stage stick keeps the throw past the rim -- the ring lives out
        // there, and a clamp before TwoStageStick sees it would throw the sprint
        // away. Every other stick stops at the socket.
        val limit = if (twoStaged) StickStage.OUTER_RADIUS else 1f
        val raw = StickVector.of(
            stickBaseX(element), stickBaseY(element), x, y, throwRadius(element), limit,
        )
        if (!twoStaged) {
            stickPositions[element.id] = raw
            // Only the movement stick owns the stage: a right-stick move -- or
            // Stick 2's, which has no ring at all -- must not end the movement
            // thumb's sprint.
            if (element.id == MOVE_STICK) {
                stickRaw.remove(element.id)
                twoStageSprint = false
                twoStagePointer = -1
            }
            return
        }
        // A press is read as a thumb on the rim however wide it landed: the
        // ring is a gesture (swipe outward and hold), so starting the dwell
        // clock on the press would let a wide grab sprint without one. The move
        // that follows is a sample like any other and can enter the ring.
        var dx = raw[0]
        var dy = raw[1]
        if (fromPress) {
            val distance = hypot(dx, dy)
            if (distance > StickStage.INNER_RADIUS) {
                val k = StickStage.INNER_RADIUS / distance
                dx *= k
                dy *= k
            }
        }
        // One sample per event: the stage, the dwell and the mapped deflection
        // all come from the same arithmetic the tests pin down.
        val sample = twoStage.sample(pointerId, dx, dy, SystemClock.uptimeMillis())
        stickPositions[element.id] = floatArrayOf(sample.x, sample.y)
        stickRaw[element.id] = raw
        twoStageSprint = sample.sprint
        twoStagePointer = pointerId
    }

    /** Whether this element is the movement stick with the two-stage switch on. */
    private fun isTwoStage(element: ControlElement): Boolean =
        element.kind == ControlKind.STICK && element.id == MOVE_STICK &&
            prefs.stickStage == Prefs.STAGE_TWO

    /**
     * Whether this is the movement stick with the switch left on MATCH -- the
     * only stick a run belongs to. MENU is the same control drawn with the
     * two-stage throw and sends no RB at all, and Stick 2 has no ring, so
     * neither of them may light up for a sprint.
     */
    private fun isMatchStick(element: ControlElement): Boolean =
        element.kind == ControlKind.STICK && element.id == MOVE_STICK &&
            prefs.stickStage == Prefs.STAGE_ONE

    /** Whether a run is on right now: the rim's latch, or the RB the pad sends. */
    private fun sprintRunning(): Boolean = sprintLatch.active || sprintHeld

    /**
     * The one way the MATCH/MENU switch moves: the pill, auto mode, and the pause
     * menu all come through here. A switch changes what the same thumb means, so
     * the run it was holding and the two-stage dwell are let go with it -- RB is
     * the title's R1, and the MENU half must never keep a run down. Nothing else
     * is released: a held pass is not a reason for a stick bookkeeping change.
     */
    private fun setStickStage(stage: Int, automatic: Boolean, because: String = "") {
        if (stage == prefs.stickStage) return
        prefs.stickStage = stage
        resetTwoStage()
        sprintLatch.reset()
        twoStageSprint = false
        twoStagePointer = -1
        stickRaw.clear()
        if (automatic) {
            val name = if (stage == Prefs.STAGE_ONE) "MATCH" else "MENU"
            Log.i(TAG, "stick: auto -> $name ($because)")
        } else {
            triggerHaptic()
        }
        invalidate()
        if (NativeBridge.isLoaded()) pushState()
    }

    /**
     * Auto mode's one decision point: the phase, the pause and a tap the player
     * made, read together. Every input that can change the answer calls this, so
     * the two setters cannot disagree and the switch is never moved twice for
     * one change.
     */
    private fun reconsiderStick() {
        if (!prefs.stickAuto) {
            stickOverride = StickSwitch.NO_OVERRIDE
            return
        }
        // A tap stands until the game itself moves on -- kick-off, a pause,
        // another screen -- and auto has the switch back then.
        if (stickOverride != StickSwitch.NO_OVERRIDE &&
            stickOverride != StickSwitch.key(gamePhase, paused, titleMenu)
        ) stickOverride = StickSwitch.NO_OVERRIDE
        val stage = StickSwitch.stage(prefs.stickAuto, gamePhase, paused, titleMenu, stickOverride)
        Log.i(
            TAG,
            "stick: auto phase=$gamePhase paused=$paused titleMenu=$titleMenu " +
                "override=$stickOverride -> " +
                (stage?.let { if (it == Prefs.STAGE_ONE) "MATCH" else "MENU" } ?: "keep"),
        )
        if (stage == null) return
        setStickStage(
            stage, automatic = true,
            because = when {
                paused -> "paused"
                titleMenu -> "the title's in-match menu"
                gamePhase == GamePhase.MATCH -> "match"
                gamePhase == GamePhase.MENUS -> "menus"
                else -> "no phase yet"
            },
        )
    }

    /** Every stage let go: a lift, a cancel, a phase change, the editor, a pause. */
    private fun resetTwoStage() {
        twoStage.reset()
        twoStagePointer = -1
        twoStageSprint = false
        stickRaw.clear()
    }

    /**
     * One sample of a flicked skill stick: the travel from where the thumb
     * landed, in throws. The direction is the runtime's to quantise, so that a
     * finger, a macro and a test are all read the same way.
     */
    private fun sendSkill(x: Float, y: Float) {
        if (!flicking) return
        val element = claims.elementFor(skillPointer) ?: return
        if (element.kind != ControlKind.STICK) return
        val r = throwRadius(element)
        var dx = (x - skillOriginX) / r
        // The guest's Y points up; the screen's points down.
        var dy = -(y - skillOriginY) / r
        // A thumb can travel further than the socket is wide; the cap keeps the
        // knob on its rim and the classifier's travel in range.
        val length = hypot(dx, dy)
        if (length > 1.5f) { dx = dx / length * 1.5f; dy = dy / length * 1.5f }
        // The knob follows the flick, so the hand can see what it is doing.
        stickPositions[element.id] = floatArrayOf(dx.coerceIn(-1f, 1f), dy.coerceIn(-1f, 1f))
        if (!NativeBridge.skillStick) return
        NativeBridge.nativeSkillTouch(dx, dy)
        invalidate()
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
                    onEditStarted?.invoke()
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
                    pinchStartSize = element.sizeOn(stance)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val element = dragElement ?: selected ?: return true
                if (pinching && event.pointerCount >= 2) {
                    val scale = spread(event) / pinchStartDistance
                    element.setSize(
                        (pinchStartSize * scale).coerceIn(minimumSize(element), maximumSize(element)),
                        stance,
                    )
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
                element.setX((x.coerceIn(margin, width - margin)) / max(1, width), stance)
                element.setY((y.coerceIn(margin, height - margin)) / max(1, height), stance)
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
        fun px(e: ControlElement, st: PadStance) = e.xFracOn(st) * width
        fun py(e: ControlElement, st: PadStance) = e.yFracOn(st) * height
        fun pr(e: ControlElement, st: PadStance) = e.sizeOn(st) * density * 0.5f
        repeat(24) {
            var moved = false
            // Both pads at once. Coordinates are one map now, so a control made
            // clear on the attacking pad must not be left touching on the
            // defending one -- which is exactly the overlap the old second copy
            // could produce.
            for (st in PadStance.values()) {
                if (!element.visibleIn(st)) continue
                for (other in layout.elements) {
                    if (other === element || !other.visibleIn(st) || other.kind == ControlKind.LOOK) continue
                    val dx = px(element, st) - px(other, st)
                    val dy = py(element, st) - py(other, st)
                    val distance = hypot(dx, dy)
                    val needed = pr(element, st) + pr(other, st) + gap
                    if (distance >= needed) continue
                    // Exactly on top: push towards the screen's middle, which has room.
                    val ux = if (distance > 0.5f) dx / distance else if (px(element, st) > width / 2f) -1f else 1f
                    val uy = if (distance > 0.5f) dy / distance else 0f
                    val push = needed - distance
                    val reach = pr(element, st) + margin
                    val x = (px(element, st) + ux * push).coerceIn(reach, max(reach, width - reach))
                    val y = (py(element, st) + uy * push).coerceIn(reach, max(reach, height - reach))
                    element.setX(x / width, stance)
                    element.setY(y / height, stance)
                    moved = true
                }
            }
            if (!moved) {
                invalidate()
                return
            }
        }
        invalidate()
    }

    /** Notes the press for the light, if the pad is drawing any. */
    private fun markPressed(element: ControlElement) {
        if (!ControlTheme.animated(prefs.controlFx)) return
        pressAnimAt[element.id] = SystemClock.uptimeMillis()
        releaseAnimAt.remove(element.id)
    }

    /** Starts the fade after a lift; the control keeps its place and its glyph. */
    private fun markReleased(element: ControlElement) {
        if (!ControlTheme.animated(prefs.controlFx)) return
        releaseAnimAt[element.id] = SystemClock.uptimeMillis()
    }

    /** How lit a control is right now, 0 when the pad is idle and always 0 when it is off. */
    private fun effectAmount(element: ControlElement, now: Long): Float {
        if (!effects) return 0f
        val down = pressAnimAt[element.id] ?: return 0f
        val up = releaseAnimAt[element.id] ?: 0L
        val held = isOn(element)
        val amount = ControlTheme.pressAmount(held, down, up, now)
        // An off control sends nothing, so it must not light up as if it had.
        return ControlOffState.glow(amount, drawingOff, editing)
    }

    /**
     * Whether another frame is worth drawing, and forgets the lights that have
     * finished. Only the view's own short animations ask for frames: there is
     * no continuous loop while the pad is idle.
     */
    private fun animateEffects(now: Long): Boolean {
        if (!effects) return false
        var running = false
        val finished = finishedAnims
        finished.clear()
        for ((id, down) in pressAnimAt) {
            val up = releaseAnimAt[id] ?: 0L
            val since = now - (if (up >= down) up else down)
            if (since >= 0L && since <= ControlTheme.RELEASE_MS + ControlTheme.PULSE_MS) {
                running = true
            } else if (up >= down && up != 0L) {
                finished.add(id)
            }
        }
        for (id in finished) {
            pressAnimAt.remove(id)
            releaseAnimAt.remove(id)
        }
        return running
    }

    /**
     * Keeps the pad from spending the match's frame budget on its own glow. The
     * average is over thirty draws, so one slow frame (a texture upload, a
     * notification) does not turn the light off.
     */
    private fun noteDrawCost(nanos: Long) {
        if (!effects) return
        drawWorkNanos += nanos
        drawSamples++
        if (drawSamples < 30) return
        val average = drawWorkNanos / drawSamples
        drawWorkNanos = 0L
        drawSamples = 0
        if (average <= ControlTheme.FRAME_BUDGET_NS) {
            drawSlowFrames = 0
            return
        }
        drawSlowFrames++
        if (drawSlowFrames >= 3) {
            effectsDegraded = true
            pressAnimAt.clear()
            releaseAnimAt.clear()
            invalidate()
        }
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
        val drawStart = System.nanoTime()
        for (element in layout.elements) {
            // A palette button the runtime cannot play is not drawn at all: a
            // control that does nothing is worse than one that is not there.
            if (!available(element)) continue
            // In the editor everything is drawn, faded when it is not on screen
            // in play -- a control that belongs to the menus has to be findable
            // over the pitch.
            if (!editing && !element.shownIn(gamePhase, stance)) continue
            val faded = editing && !element.shownIn(gamePhase, stance)
            val stanceAlpha = element.alphaOn(stance)
            // An off control is its own state in the editor: dimmed to 0.30,
            // forced flat and badged. In play it is simply not drawn, which the
            // phase check above has already done.
            val off = ControlOffState.isOff(element.visibleIn(stance), editing)
            drawingOff = off
            val alpha = ControlOffState.alpha(stanceAlpha, off, editing)
            // The d-pad's own fade multiplies its layout alpha; everything
            // else is untouched, so no other control can flicker with it.
            val dpadFade =
                if (element.kind == ControlKind.DPAD && !editing) dpadAlpha() else 1f
            if (element.kind == ControlKind.DPAD && dpadFade <= 0.01f) continue
            when (element.kind) {
                ControlKind.LOOK -> drawLook(canvas, element, faded)
                ControlKind.STICK -> drawStick(canvas, element, alpha, faded)
                ControlKind.DPAD -> drawDpad(canvas, element, alpha * dpadFade, faded)
                ControlKind.SWIPE -> drawSwipe(canvas, element, alpha, faded)
                ControlKind.TACTICAL -> drawTactical(canvas, element, alpha, faded)
                ControlKind.BUTTON, ControlKind.TRIGGER, ControlKind.SKILL, ControlKind.CUSTOM,
                ControlKind.LATCH -> drawButton(canvas, element, alpha, faded)
                ControlKind.MENTALITY -> drawMentality(canvas, element, alpha, faded)
                ControlKind.STICK_MODE -> drawStickMode(canvas, element, alpha, faded)
                ControlKind.TRICK -> {
                    drawButton(canvas, element, alpha, faded)
                    // Open is derived every frame from the owning finger and
                    // the clock. Nothing can clear a flag mid-hold, so the ring
                    // stays up for as long as the thumb keeps rolling.
                    val owner = wheel.pointer
                    if (owner >= 0 && claims.elementFor(owner) === element &&
                        wheel.isOpen(SystemClock.uptimeMillis())
                    ) {
                        drawTrickRing(canvas, element, alpha)
                    }
                }
            }
            // The resting light, under everything else the control draws on top.
            drawRestGlow(canvas, element, alpha, faded)
            // The badge goes above the selection ring: it is a state, not a
            // decoration, and a selected off control must still read as off.
            if (ControlOffState.showsBadge(off, editing)) drawOffBadge(canvas, element)
            // The released swipe's trail is drawn over its control, whatever the
            // phase: it is feedback for a gesture that has already been sent.
            drawSwipeTrail(canvas, element)
            // The direction the thumb is holding right now, in words, beside the
            // button. It stays until the finger lifts.
            if (!editing) drawSwipeHud(canvas, element)
        }
        // Effects are pruned here, not only where they are drawn: a trail whose
        // control stopped being on screen would never be reached again, and the
        // animation loop would keep asking for frames for the rest of the match.
        val effectsNow = SystemClock.uptimeMillis()
        pruneSwipeTrails(effectsNow)
        if (swipeTrails.isNotEmpty()) postInvalidateOnAnimation()
        if (animateEffects(effectsNow)) postInvalidateOnAnimation()
        noteDrawCost(System.nanoTime() - drawStart)
    }

    /**
     * The wheel itself: eight wedges around the thumb that opened it, the one
     * under the thumb lit, each labelled with its move's short name. Drawn
     * around the landing point rather than the control, so the ring comes to the
     * thumb and the thumb never has to find a circle.
     */
    private fun drawTrickRing(canvas: Canvas, element: ControlElement, alpha: Float) {
        val cx = wheel.originX
        val cy = wheel.originY
        val r = radius(element)
        val outer = r * 1.95f
        val inner = r * 0.72f
        val previousAlign = text.textAlign
        val previousSize = text.textSize
        text.textAlign = Paint.Align.CENTER
        text.textSize = max(8f, r * 0.30f)
        for (sector in 0 until TrickWheel.SECTORS) {
            // Canvas angles run clockwise from the right and the wheel's
            // sectors run the other way, so this is squarely on the wedge.
            val start = -22.5f - 45f * sector
            val chosen = sector == wheel.sector
            tempRect.set(cx - outer, cy - outer, cx + outer, cy + outer)
            fill.color = if (chosen) {
                Color.argb((alpha * 205f).toInt().coerceIn(0, 255), 56, 189, 248)
            } else {
                Color.argb((alpha * 130f).toInt().coerceIn(0, 255), 15, 23, 42)
            }
            canvas.drawArc(tempRect, start, 45f, true, fill)
            if (chosen) {
                stroke.color = Color.argb(235, 224, 242, 254)
                stroke.strokeWidth = max(1.5f, density * 1.5f)
                canvas.drawArc(tempRect, start, 45f, true, stroke)
            }
            // An empty sector says nothing rather than showing a wrong name.
            val move = element.trickMove(sector)
            if (move < 0) continue
            val radians = Math.toRadians(-45.0 * sector)
            val labelRadius = (inner + outer) * 0.5f
            val lx = cx + (Math.cos(radians) * labelRadius).toFloat()
            val ly = cy + (Math.sin(radians) * labelRadius).toFloat()
            text.color = if (chosen) Color.WHITE else Color.argb(225, 226, 232, 240)
            canvas.drawText(SkillMoves.short(move), lx, ly - (text.descent() + text.ascent()) * 0.5f, text)
        }
        // The deadzone is drawn too: a thumb inside it has chosen nothing.
        fill.color = Color.argb((alpha * 200f).toInt().coerceIn(0, 255), 9, 12, 18)
        canvas.drawCircle(cx, cy, inner, fill)
        text.textAlign = previousAlign
        text.textSize = previousSize
    }

    private fun shade(alpha: Float, pressed: Boolean, faded: Boolean): Int {
        val a = (alpha * (if (faded) 0.45f else 1f) * 255).toInt().coerceIn(0, 255)
        return if (pressed) Color.argb(min(255, a + 70), 255, 255, 255)
        else Color.argb(a, 235, 238, 242)
    }

    private fun isOn(element: ControlElement): Boolean = claims.isOn(element.id)

    companion object {
        /** Logcat tag for the automatic switch: `adb logcat -s PES17Stick`. */
        private const val TAG = "PES17Stick"

        /** How much of the screen's width, from the left, the floating stick owns. */
        private const val FLOAT_ZONE = 0.5f

        /**
         * The movement stick: the one socket the rim latch and the two-stage
         * ring belong to. Stick 2 is a stick of its own, without either.
         */
        private const val MOVE_STICK = "stick"

        /**
         * The skill stick's throw, as a share of its socket. Short enough that a
         * flick reaches the rim, long enough that the eight sectors are not all
         * under the first millimetre of travel.
         */
        private const val SKILL_THROW = 0.55f

        /** How long the last skill-stick sample is held after the thumb lifts. */
        private const val SKILL_RELEASE_HOLD_MS = 90L

        /**
         * How long after entering the Match flow a START still counts as the
         * pre-match skip. Most matches are kicking off well before this, and a
         * match that is played without ever touching the stick is treated as
         * live after it, so the pause sheet can still move the switch.
         */
        private const val KICK_OFF_FALLBACK_MS = 60_000L

        /** How far the stick must be held before it counts as playing. */
        private const val KICK_OFF_STEER = 0.6f

        /** How long it must stay there: a nudge through a lineup is shorter. */
        private const val KICK_OFF_STEER_MS = 600L

        /** A pad axis older than this is a released stick, not a deflection. */
        private const val PAD_STEER_FRESH_MS = 500L

        /** How long a released swipe's trail takes to fly out and fade. */
        private const val SWIPE_TRAIL_MS = 200L

        /**
         * How long a live swipe's modifier is kept down after the button under
         * it lifts. PES reads a finesse shot (R2 + shoot) as the shot comes up,
         * so releasing R2 in the same push as the shot read as an ordinary shot
         * whenever the title's poll landed after both. Two guest frames at 30
         * fps is 67 ms; eighty leaves two polls with the modifier still down.
         */
        private const val LIVE_MODIFIER_TAIL_MS = 80L

        /**
         * How long a live swipe's modifier has to be down before the lift
         * relies on it instead of replaying the timed sequence. One guest frame
         * at 30 fps, so the title has certainly polled while both were held.
         */
        private const val LIVE_MODIFIER_MIN_MS = 40L

        /** The text size a label is measured at once, so its ratio can be reused. */
        private const val FIT_SAMPLE = 100f

        /** The word on an off control's badge, and the red the editor gives it. */
        private const val OFF_BADGE = "OFF"
        private const val COLOR_OFF = 0xFFD32F2F.toInt()

        /** The resting light: a soft red, quiet enough to sit under every idle control. */
        private const val COLOR_REST_GLOW = 0xFFEF4444.toInt()

        // The stance chip: green on the attacking pad, steel blue on the
        // defending one, so which half the thumb is in is legible at a glance.

        // The team mentality pair: green raises, blue lowers. Both are drawn as
        // low-opacity glass, so the pitch under them stays readable.
        private const val COLOR_MENTALITY_ATTACK = 0xFF22C55E.toInt()
        private const val COLOR_MENTALITY_DEFEND = 0xFF38BDF8.toInt()

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

        // A spare (C1..C10) that sends nothing yet: the pad's neutral violet.
        // Once the editor binds it to a face button it takes that button's own
        // colour instead, so a C bound to shoot glows the way shoot does.
        private const val COLOR_SPARE = 0xFF4C1D95.toInt()
        private const val COLOR_SPARE_GLOW = 0xFFA78BFA.toInt()

        /** The latch halo: the one colour that means "this is on and holding". */
        private const val COLOR_LATCH = 0xFF38BDF8.toInt()

        /** The run's light: the blue ring the MATCH stick wears while sprinting. */
        private const val COLOR_SPRINT_GLOW = 0xFF60A5FA.toInt()

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

    /**
     * What a button says in the stance the pad is in: its attacking name, or the
     * line for what it becomes without the ball. The title already reads the bit
     * differently by possession, so nothing here changes what is sent.
     */
    private fun caption(element: ControlElement): String =
        if (stance == PadStance.DEFEND && element.sub2.isNotEmpty()) element.sub2 else element.sub

    /**
     * One of the two team mentality buttons: a glass pill with rounded corners, a
     * thin accent border, and a white arrow and word. Green raises the mentality,
     * blue lowers it, and the body is mostly see-through so the pitch under it
     * stays readable.
     *
     * The shape is a pill rather than a circle because the word is the control:
     * "ATTACKING" over "DEFENSE" stacked one above the other is the whole
     * gesture, and a thumb finds it without having to remember which circle was
     * which.
     */
    private fun drawMentality(
        canvas: Canvas, element: ControlElement, alpha: Float, faded: Boolean,
    ) {
        val r = radius(element)
        val halfWidth = r
        val halfHeight = r * 0.48f
        val cx = centreX(element)
        val cy = centreY(element)
        val fade = if (faded) 0.45f else 1f
        val attacking = element.value == NativeBridge.UP
        val accent = if (attacking) COLOR_MENTALITY_ATTACK else COLOR_MENTALITY_DEFEND
        val pressed = isOn(element)
        val corner = halfHeight * 0.55f

        // The tint first, then a dark wash over it: together a low-opacity colour
        // rather than a coloured button.
        // Readable over a bright pitch, not just over the stands: the pair used
        // to be drawn so faint that a player who had just turned them on could
        // not find them, which is a visibility bug however the state reads. The
        // glass is still see-through, but the word is no longer lost in it.
        box.set(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight)
        fill.color = Color.argb(
            (alpha * fade * (if (pressed) 170f else 120f)).toInt().coerceIn(0, 255),
            Color.red(accent), Color.green(accent), Color.blue(accent),
        )
        canvas.drawRoundRect(box, corner, corner, fill)
        fill.color = Color.argb(
            (alpha * fade * (if (pressed) 150f else 110f)).toInt().coerceIn(0, 255), 8, 12, 18,
        )
        canvas.drawRoundRect(box, corner, corner, fill)

        // A thin, quiet border in the accent, so which of the two is which reads
        // before the word does.
        val border = max(1f, halfHeight * 0.075f)
        box.inset(border * 0.5f, border * 0.5f)
        stroke.strokeWidth = border
        stroke.color = Color.argb(
            (alpha * fade * (if (pressed) 255f else 220f)).toInt().coerceIn(0, 255),
            Color.red(accent), Color.green(accent), Color.blue(accent),
        )
        canvas.drawRoundRect(box, corner, corner, stroke)

        val word = (if (attacking) "\u2191 " else "\u2193 ") +
            element.sub.ifEmpty { element.label }
        text.textSize = fitText(text, word, halfWidth * 1.62f, halfHeight * 1.05f, 6f)
        text.color = Color.argb((alpha * fade * 255f).toInt().coerceIn(0, 255), 255, 255, 255)
        canvas.drawText(word, cx, cy - (text.descent() + text.ascent()) * 0.5f, text)
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
        // A spare holds nothing of its own, so its colour has to come from the
        // command it was given; unbound, it keeps the neutral spare violet.
        val isSpare = element.kind == ControlKind.CUSTOM
        val spareBit = if (isSpare) SwipeLabels.accentBit(element) else 0
        // The direction a thumb on this spare is holding right now: its second
        // "active" state, since nothing of its own is down while the thumb is.
        val spareSwipe = if (isSpare) liveSwipe(element) else Swipe.NONE
        // By side rather than by id: the jockey and close-control toggles are
        // triggers the pad grew later, and each draws as the trigger it holds.
        val isRT = id == "rt" || (element.kind == ControlKind.TRIGGER && element.value != 0)
        val isLT = id == "lt" || (element.kind == ControlKind.TRIGGER && element.value == 0)
        // By bit rather than by id, so the defending switch -- a second LB in
        // reach of the right thumb -- draws like the bumper it is.
        val isBumper = element.kind == ControlKind.BUTTON &&
            (element.value == NativeBridge.LB || element.value == NativeBridge.RB)
        val isThumbClick = id == "l3" || id == "r3"
        val isMenu = id == "start" || id == "back"

        val label = ControlLayout.labelFor(element, playStation)
        val coreColor = when {
            isSpare -> spareCore(spareBit)
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
            isSpare -> spareGlow(spareBit)
            isA -> if (playStation) PS_CROSS_GLOW else COLOR_A_GLOW
            isB -> if (playStation) PS_CIRCLE_GLOW else COLOR_B_GLOW
            isX -> if (playStation) PS_SQUARE_GLOW else COLOR_X_GLOW
            isY -> if (playStation) PS_TRIANGLE_GLOW else COLOR_Y_GLOW
            isRT -> COLOR_RT_GLOW
            isLT -> COLOR_LT_GLOW
            isBumper -> COLOR_BUMPER_GLOW
            else -> 0xFF94A3B8.toInt()
        }

        // The press light: an aura that grows on the press and fades on the
        // lift. With effects off it falls back to the flat disc this control
        // always had, so the pad reads the same, just without the motion.
        val fx = effectAmount(element, SystemClock.uptimeMillis())
        if (fx > 0f) {
            fill.color = Color.argb(
                (alpha * fadeFactor * (70f + 45f * fx)).toInt().coerceIn(0, 255),
                Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor),
            )
            canvas.drawCircle(cx, cy, r * (1.08f + 0.05f * fx), fill)
            if (ControlTheme.pulsed(prefs.controlFx)) {
                stroke.color = Color.argb(
                    (alpha * fadeFactor * 110f * fx).toInt().coerceIn(0, 255),
                    Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor),
                )
                stroke.strokeWidth = max(1f, r * 0.022f)
                canvas.drawCircle(cx, cy, r * (1.16f + 0.12f * fx), stroke)
            }
        } else if (pressed) {
            fill.color = Color.argb(
                (alpha * fadeFactor * 90).toInt().coerceIn(0, 255),
                Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor),
            )
            canvas.drawCircle(cx, cy, r * 1.08f, fill)
        }

        // A spare's locked direction is its other "active" state: a halo in the
        // colour of the command it is about to send, drawn from the moment the
        // axis wins until the finger lifts. The pill above says it in words;
        // this is what the thumb sees without looking away from the button.
        if (spareSwipe != Swipe.NONE) {
            val bit = element.swipeBit(spareSwipe).let { if (it != 0) it else spareBit }
            val accent = spareGlow(bit)
            fill.color = Color.argb(
                (alpha * fadeFactor * 110).toInt().coerceIn(0, 255),
                Color.red(accent), Color.green(accent), Color.blue(accent),
            )
            canvas.drawCircle(cx, cy, r * 1.26f, fill)
            stroke.color = Color.argb(
                (alpha * fadeFactor * 220).toInt().coerceIn(0, 255),
                Color.red(accent), Color.green(accent), Color.blue(accent),
            )
            stroke.strokeWidth = max(1.5f, r * 0.05f)
            canvas.drawCircle(cx, cy, r * 1.34f, stroke)
        }

        val pressScale = ControlTheme.scale(fx)
        val scaled = pressScale < 0.999f
        if (scaled) {
            canvas.save()
            canvas.scale(pressScale, pressScale, cx, cy)
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
            val actionSubtitle = caption(element).ifEmpty {
                when (id) {
                    "a" -> "PASS"
                    "x" -> "SHOOT"
                    "b" -> "CROSS"
                    "y" -> "THROUGH"
                    else -> ""
                }
            }

            val glyphR = coreR * 0.90f
            if (playStation) {
                val size = glyphR * (if (actionSubtitle.isNotEmpty()) 0.35f else 0.40f)
                val symY = if (actionSubtitle.isNotEmpty()) cy - glyphR * 0.12f else cy
                drawPsSymbol(canvas, id, cx, symY + 1.2f * density, size, shadow)
                drawPsSymbol(canvas, id, cx, symY, size, ink)
            } else {
                // Auto-fitted: a renamed button's letter runs out of the circle
                // otherwise, and the glyph is what the thumb aims at.
                text.textSize = fitText(
                    text, label, glyphR * 1.55f,
                    glyphR * (if (actionSubtitle.isNotEmpty()) 0.78f else 0.95f),
                )
                val baseLetterY = if (actionSubtitle.isNotEmpty()) cy - glyphR * 0.10f else cy
                val letterY = baseLetterY - (text.descent() + text.ascent()) * 0.5f
                text.color = shadow
                canvas.drawText(label, cx, letterY + 1.2f * density, text)
                text.color = ink
                canvas.drawText(label, cx, letterY, text)
            }

            if (actionSubtitle.isNotEmpty()) {
                subText.textSize = fitText(
                    subText, actionSubtitle, coreR * 1.5f, max(8f, glyphR * 0.28f),
                )
                val subY = cy + glyphR * 0.58f
                subText.color = Color.argb((alpha * fadeFactor * 140).toInt().coerceIn(0, 255), 0, 0, 0)
                canvas.drawText(actionSubtitle, cx, subY + 1f * density, subText)
                subText.color = Color.argb((alpha * fadeFactor * 220).toInt().coerceIn(0, 255), 241, 245, 249)
                canvas.drawText(actionSubtitle, cx, subY, subText)
            }

            // The four directions this button can swipe, on its rim. Nothing is
            // drawn for a button with no bindings, so no arrow promises a
            // gesture that does nothing.
            drawSwipeArrows(canvas, element, cx, cy, r, alpha * fadeFactor)
        } else if (isSpare) {
            // A spare, drawn as a real button rather than a grey placeholder:
            // the same glass as an action button, tinted with the command it
            // sends, with the four directions it can swipe on its rim. While a
            // direction is being read it wears a halo in that command's colour,
            // which is the only "held" feedback it can have -- nothing of its
            // own goes down until the thumb lifts.
            fill.color = Color.argb(
                (alpha * fadeFactor * 180).toInt().coerceIn(0, 255), 12, 16, 24,
            )
            canvas.drawCircle(cx, cy, r, fill)

            stroke.color = Color.argb(
                (alpha * fadeFactor * (if (pressed) 255 else 175)).toInt().coerceIn(0, 255),
                Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor),
            )
            stroke.strokeWidth = max(1.5f, r * 0.045f)
            canvas.drawCircle(cx, cy, r - stroke.strokeWidth * 0.5f, stroke)

            val coreR = r * 0.90f
            fill.color = Color.argb(
                (alpha * fadeFactor * (if (pressed) 150 else 60)).toInt().coerceIn(0, 255),
                Color.red(coreColor), Color.green(coreColor), Color.blue(coreColor),
            )
            canvas.drawCircle(cx, cy, coreR, fill)

            text.textSize = fitText(
                text, label, coreR * 1.5f, coreR * (if (label.length > 2) 0.52f else 0.72f),
            )
            text.color = Color.argb((alpha * fadeFactor * 250).toInt().coerceIn(0, 255), 255, 255, 255)
            canvas.drawText(label, cx, cy - (text.descent() + text.ascent()) * 0.5f, text)

            drawSwipeArrows(canvas, element, cx, cy, r, alpha * fadeFactor)
        } else if (isRT || isLT) {
            // Xbox Ergonomic Trigger (RT / LT). A latched trigger is lit in R2's
            // red whatever side it holds: it is on with no thumb on it, and the
            // activated face is the one thing that has to read at a glance.
            val activated = element.toggle && claims.isToggled(element.id)
            val face = if (activated) COLOR_RT else coreColor
            val faceGlow = if (activated) COLOR_RT_GLOW else glowColor
            val fillA = (alpha * fadeFactor * (if (pressed) 190 else 95)).toInt().coerceIn(0, 255)
            fill.color = Color.argb(fillA, Color.red(face), Color.green(face), Color.blue(face))
            canvas.drawCircle(cx, cy, r, fill)

            stroke.color = Color.argb(
                (alpha * fadeFactor * (if (pressed) 255 else 170)).toInt().coerceIn(0, 255),
                Color.red(faceGlow), Color.green(faceGlow), Color.blue(faceGlow),
            )
            stroke.strokeWidth = max(2f, r * 0.065f)
            canvas.drawCircle(cx, cy, r - stroke.strokeWidth * 0.5f, stroke)

            // Fitted, because a renamed trigger can say more than "RT": the
            // close-control toggle ships as "Controll".
            text.textSize = fitText(text, label, r * 1.5f, r * 0.58f)
            text.color = Color.WHITE
            canvas.drawText(label, cx, cy - (text.descent() + text.ascent()) * 0.5f, text)


        } else if (isBumper) {
            // Xbox Bumper (LB / RB)
            val fillA = (alpha * fadeFactor * (if (pressed) 180 else 85)).toInt().coerceIn(0, 255)
            fill.color = Color.argb(fillA, 30, 38, 48)
            canvas.drawCircle(cx, cy, r, fill)

            stroke.color = Color.argb(
                (alpha * fadeFactor * (if (pressed) 240 else 140)).toInt().coerceIn(0, 255),
                Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor),
            )
            stroke.strokeWidth = max(1.5f, r * 0.055f)
            canvas.drawCircle(cx, cy, r - stroke.strokeWidth * 0.5f, stroke)

            val bumperCaption = caption(element)
            text.textSize = r * (if (bumperCaption.isEmpty()) 0.52f else 0.46f)
            text.color = Color.WHITE
            val labelY = if (bumperCaption.isEmpty()) cy - (text.descent() + text.ascent()) * 0.5f else cy - r * 0.06f
            canvas.drawText(label, cx, labelY, text)
            if (bumperCaption.isNotEmpty()) {
                subText.textSize = fitText(subText, bumperCaption, r * 1.15f, r * 0.27f)
                subText.color = Color.argb((alpha * fadeFactor * 220).toInt().coerceIn(0, 255), 226, 232, 240)
                canvas.drawText(bumperCaption, cx, cy + r * 0.44f, subText)
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

            text.textSize = fitText(text, label, r * 1.6f, r * (if (label.length > 5) 0.26f else 0.32f))
            text.color = Color.WHITE
            canvas.drawText(label, cx, cy - (text.descent() + text.ascent()) * (if (caption(element).isEmpty()) 0.5f else 1.1f), text)
            if (caption(element).isNotEmpty()) {
                subText.textSize = r * 0.24f
                subText.color = Color.argb((alpha * fadeFactor * 200).toInt().coerceIn(0, 255), 148, 163, 184)
                canvas.drawText(caption(element), cx, cy + r * 0.45f, subText)
            }
        } else {
            // Fallback general button
            fill.color = Color.argb((alpha * fadeFactor * (if (pressed) 160 else 75)).toInt().coerceIn(0, 255), 18, 22, 28)
            canvas.drawCircle(cx, cy, r, fill)
            stroke.color = shade(alpha, pressed, faded)
            stroke.strokeWidth = max(1.5f, r * 0.055f)
            canvas.drawCircle(cx, cy, r - stroke.strokeWidth * 0.5f, stroke)
            text.color = shade(alpha, pressed, faded)
            text.textSize = fitText(
                text, element.label, r * 1.6f, r * (if (element.label.length > 2) 0.52f else 0.8f),
            )
            canvas.drawText(element.label, cx, cy - (text.descent() + text.ascent()) * 0.5f, text)
        }
        // A latched control that is on says so with a ring as well as a fill:
        // no thumb is on it, so the fill alone would read as an ordinary button
        // with a dark face rather than as one that is holding a bit down.
        // Never for a control the player has switched off: the OFF state is
        // 0.30 and no glow, and a halo is a glow. An off control sends nothing
        // either, so the ring would be the only thing still claiming it works.
        if (claims.isToggled(element.id) && !drawingOff) {
            drawLatchRing(canvas, element, cx, cy, r, alpha * fadeFactor)
        }
        if (scaled) canvas.restore()
    }

    /**
     * The glass tint for a spare: the colour of the command it plays, so a C
     * bound to shoot looks like shoot rather than like a grey spare. An unbound
     * spare keeps the pad's neutral violet.
     */
    private fun spareCore(bit: Int): Int = when (bit) {
        NativeBridge.A -> if (playStation) PS_CROSS else COLOR_A
        NativeBridge.B -> if (playStation) PS_CIRCLE else COLOR_B
        NativeBridge.X -> if (playStation) PS_SQUARE else COLOR_X
        NativeBridge.Y -> if (playStation) PS_TRIANGLE else COLOR_Y
        NativeBridge.LB, NativeBridge.RB -> 0xFF334155.toInt()
        else -> COLOR_SPARE
    }

    /** The glow for a spare: the same accent its bound command glows in. */
    private fun spareGlow(bit: Int): Int = when (bit) {
        NativeBridge.A -> if (playStation) PS_CROSS_GLOW else COLOR_A_GLOW
        NativeBridge.B -> if (playStation) PS_CIRCLE_GLOW else COLOR_B_GLOW
        NativeBridge.X -> if (playStation) PS_SQUARE_GLOW else COLOR_X_GLOW
        NativeBridge.Y -> if (playStation) PS_TRIANGLE_GLOW else COLOR_Y_GLOW
        NativeBridge.LB, NativeBridge.RB -> COLOR_BUMPER_GLOW
        else -> COLOR_SPARE_GLOW
    }

    /**
     * The latch halo: a bright ring just outside the control and a dot on its
     * shoulder, drawn for as long as a toggle is engaged.
     *
     * It has to be louder than a press, because nothing is touching it: the
     * ring is what tells the player, from across the pad, that the jockey button
     * is still on. Drawn outside the rim so it never hides the label, and in the
     * same accent for every toggle so "on" always looks the same.
     */
    private fun drawLatchRing(
        canvas: Canvas, element: ControlElement, cx: Float, cy: Float, r: Float, alpha: Float,
    ) {
        val accent = latchAccent(element)
        // The halo first: a wide, soft band just outside the rim, so an
        // engaged toggle glows -- visible from across the pad -- instead of
        // reading as a thin outline that is easy to miss over bright turf. It
        // is a band rather than a filled disc so the control's own label stays
        // legible.
        stroke.color = Color.argb(
            (alpha * 85f).toInt().coerceIn(0, 255),
            Color.red(accent), Color.green(accent), Color.blue(accent),
        )
        stroke.strokeWidth = max(4f, r * 0.22f)
        canvas.drawCircle(cx, cy, r + stroke.strokeWidth * 0.9f, stroke)

        stroke.color = Color.argb(
            (alpha * 235f).toInt().coerceIn(0, 255),
            Color.red(accent), Color.green(accent), Color.blue(accent),
        )
        stroke.strokeWidth = max(2f, r * 0.10f)
        canvas.drawCircle(cx, cy, r + stroke.strokeWidth * 1.1f, stroke)
        fill.color = Color.argb(
            255, Color.red(accent), Color.green(accent), Color.blue(accent),
        )
        canvas.drawCircle(cx + r * 0.70f, cy - r * 0.70f, max(3f * density, r * 0.15f), fill)
    }

    /**
     * The colour a latch's halo glows in. JCK and the close-control toggle
     * speak in R2's red, the colour of an activated trigger; every other toggle
     * keeps the one blue that means "on and holding" elsewhere on the pad.
     */
    private fun latchAccent(element: ControlElement): Int = when {
        element.id == "jockey" || element.id == "controll" -> COLOR_RT_GLOW
        else -> COLOR_LATCH
    }

    /**
     * A five-way tactical button: a ring with four arms and a centre dot, the
     * arms lit where they send something and white under the thumb travelling
     * that way, and the button's name under the dot.
     *
     * It holds nothing itself, so nothing is lit while a thumb merely rests on
     * it; the directions are sent on the lift, exactly as the swipe patch sends
     * them, and the trail that follows is the same one every other swipe draws.
     */
    private fun drawTactical(canvas: Canvas, element: ControlElement, alpha: Float, faded: Boolean) {
        val r = radius(element)
        val cx = centreX(element)
        val cy = centreY(element)
        val fade = if (faded) 0.45f else 1f

        fill.color = Color.argb((alpha * fade * 55).toInt().coerceIn(0, 255), 16, 20, 26)
        canvas.drawCircle(cx, cy, r, fill)
        stroke.color = Color.argb((alpha * fade * 140).toInt().coerceIn(0, 255), 100, 116, 139)
        stroke.strokeWidth = max(1.5f, r * 0.045f)
        canvas.drawCircle(cx, cy, r - stroke.strokeWidth * 0.5f, stroke)

        // The centre dot: where the custom tap goes, so a thumb can find the
        // middle of the control without reading anything.
        fill.color = Color.argb((alpha * fade * 200).toInt().coerceIn(0, 255), 226, 232, 240)
        canvas.drawCircle(cx, cy, max(2.5f, r * 0.12f), fill)

        text.textSize = fitText(text, element.label, r * 1.0f, r * 0.32f)
        text.color = Color.argb((alpha * fade * 225).toInt().coerceIn(0, 255), 241, 245, 249)
        canvas.drawText(element.label, cx, cy + r * 0.60f, text)

        drawSwipeArrows(canvas, element, cx, cy, r, alpha * fade)
    }

    /** Whether any of this control's four directions sends something. */
    private fun hasSwipeBindings(element: ControlElement): Boolean =
        element.swipeUp != 0 || element.swipeDown != 0 ||
            element.swipeLeft != 0 || element.swipeRight != 0

    /** The direction a thumb on this control is heading for right now. */
    private fun liveSwipe(element: ControlElement): Swipe {
        for ((pointerId, direction) in liveSwipes) {
            if (direction != Swipe.NONE && claims.elementFor(pointerId)?.id == element.id) return direction
        }
        return Swipe.NONE
    }

    /**
     * Four chevrons on an action button's rim, one per direction: a faint tick
     * where nothing is bound, a solid one where something is, and white under
     * the thumb that is travelling that way. This is the gesture feedback -- the
     * bit itself is still sent only when the finger lifts (SwipeClassifier).
     */
    private fun drawSwipeArrows(
        canvas: Canvas, element: ControlElement, cx: Float, cy: Float, r: Float, alpha: Float,
    ) {
        if (!hasSwipeBindings(element)) return
        val live = liveSwipe(element)
        val inset = r * 0.74f
        val arm = max(5f * density, r * 0.16f)
        notchPaint.strokeWidth = max(1.5f, r * 0.05f)
        for (swipe in intArrayOf(0, 1, 2, 3)) {
            val direction = when (swipe) {
                0 -> Swipe.UP
                1 -> Swipe.DOWN
                2 -> Swipe.LEFT
                else -> Swipe.RIGHT
            }
            val bound = element.swipeBit(direction) != 0
            val lit = live == direction
            val dx = when (direction) { Swipe.LEFT -> -1f; Swipe.RIGHT -> 1f; else -> 0f }
            val dy = when (direction) { Swipe.UP -> -1f; Swipe.DOWN -> 1f; else -> 0f }
            val level = when { lit -> 255; bound -> 205; else -> 60 }
            notchPaint.color = Color.argb((alpha * level).toInt().coerceIn(0, 255), 255, 255, 255)
            // The live direction is both brighter and heavier, so the feedback
            // is legible under a thumb that is covering the middle of the button.
            notchPaint.strokeWidth = if (lit) max(2.5f, r * 0.09f) else max(1.5f, r * 0.05f)
            val tipX = cx + dx * inset
            val tipY = cy + dy * inset
            val backX = tipX - dx * arm
            val backY = tipY - dy * arm
            canvas.drawLine(backX, backY, tipX, tipY, notchPaint)
            // Barbs, so it reads as an arrow rather than a tick.
            val barbX = -dy * arm * 0.8f
            val barbY = dx * arm * 0.8f
            canvas.drawLine(tipX, tipY, backX + barbX, backY + barbY, notchPaint)
            canvas.drawLine(tipX, tipY, backX - barbX, backY - barbY, notchPaint)
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

    /**
     * The resting light: a faint red ring around a button, toggle or switch that
     * is doing nothing at all. It is the pad saying "here, and idle", and it
     * lifts the moment a thumb lands on it, a latch engages, or the two-stage
     * switch is on mode 2.
     *
     * Deliberately quiet -- two thin strokes at low alpha -- because it is on
     * every idle control at once; the press and latch lights are the ones that
     * are meant to be seen across the pitch.
     */
    private fun drawRestGlow(
        canvas: Canvas, element: ControlElement, alpha: Float, faded: Boolean,
    ) {
        if (!ControlOffState.showsRestGlow(element.kind, isEngaged(element), editing)) return
        val fade = if (faded) 0.45f else 1f
        val r = radius(element)
        val soft = (alpha * fade * 90f).toInt().coerceIn(0, 255)
        val edge = (alpha * fade * 170f).toInt().coerceIn(0, 255)
        val red = Color.red(COLOR_REST_GLOW)
        val green = Color.green(COLOR_REST_GLOW)
        val blue = Color.blue(COLOR_REST_GLOW)

        // The switch is a pill: its light follows its own outline.
        if (element.kind == ControlKind.STICK_MODE) {
            val halfW = r
            val halfH = halfW * 0.5f
            val pad = max(2f, r * 0.10f)
            val cx = centreX(element)
            val cy = centreY(element)
            box.set(cx - halfW - pad, cy - halfH - pad, cx + halfW + pad, cy + halfH + pad)
            val radius = halfH + pad
            stroke.strokeWidth = max(2f, r * 0.14f)
            stroke.color = Color.argb(soft, red, green, blue)
            canvas.drawRoundRect(box, radius, radius, stroke)
            stroke.strokeWidth = max(1f, r * 0.05f)
            stroke.color = Color.argb(edge, red, green, blue)
            canvas.drawRoundRect(box, radius, radius, stroke)
            return
        }

        val cx = centreX(element)
        val cy = centreY(element)
        stroke.strokeWidth = max(2f, r * 0.14f)
        stroke.color = Color.argb(soft, red, green, blue)
        canvas.drawCircle(cx, cy, r * 1.07f, stroke)
        stroke.strokeWidth = max(1f, r * 0.05f)
        stroke.color = Color.argb(edge, red, green, blue)
        canvas.drawCircle(cx, cy, r * 1.04f, stroke)
    }

    /** Whether a control is doing its thing right now: pressed, latched on, or mode 2. */
    private fun isEngaged(element: ControlElement): Boolean = when (element.kind) {
        ControlKind.STICK_MODE -> prefs.stickStage == Prefs.STAGE_TWO
        else -> isOn(element)
    }

    /**
     * The two-stage switch: a small pill above the socket, MATCH on one side and
     * MENU on the other, with the mode in use filled. One tap flips it, and it
     * holds no bit -- what it changes is how the stick under it reads.
     */
    private fun drawStickMode(
        canvas: Canvas, element: ControlElement, alpha: Float, faded: Boolean,
    ) {
        val halfW = radius(element)
        val halfH = halfW * 0.50f
        val cx = centreX(element)
        val cy = centreY(element)
        val fade = if (faded) 0.45f else 1f
        val second = prefs.stickStage == Prefs.STAGE_TWO

        // The housing, then the half that is in use drawn inside it.
        box.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        fill.color = Color.argb((alpha * fade * 190f).toInt().coerceIn(0, 255), 12, 16, 24)
        canvas.drawRoundRect(box, halfH, halfH, fill)
        stroke.strokeWidth = max(1.2f, halfH * 0.12f)
        stroke.color = Color.argb((alpha * fade * 160f).toInt().coerceIn(0, 255), 70, 85, 105)
        canvas.drawRoundRect(box, halfH, halfH, stroke)

        // Blue for the normal throw, the rim's green for the ring.
        val active = if (second) 0xFF22C55E.toInt() else 0xFF3B82F6.toInt()
        val inset = halfH * 0.08f
        if (second) {
            box.set(cx + inset, cy - halfH + inset, cx + halfW - inset, cy + halfH - inset)
        } else {
            box.set(cx - halfW + inset, cy - halfH + inset, cx - inset, cy + halfH - inset)
        }
        fill.color = Color.argb(
            (alpha * fade * 235f).toInt().coerceIn(0, 255),
            Color.red(active), Color.green(active), Color.blue(active),
        )
        canvas.drawRoundRect(box, halfH * 0.92f, halfH * 0.92f, fill)

        text.textSize = halfH * 1.20f
        text.color = Color.argb((alpha * fade * 255f).toInt().coerceIn(0, 255), 248, 250, 252)
        // The words are the modes themselves: MATCH is the standard throw, MENU
        // the two-stage one whose outer circle is the ring. "1" and "2" said
        // nothing about which was which.
        val left = "MATCH"
        val right = "MENU"
        text.textSize = fitText(text, left, halfW * 0.88f, halfH * 1.15f, 4f)
        canvas.drawText(left, cx - halfW * 0.5f, cy - (text.descent() + text.ascent()) * 0.5f, text)
        text.textSize = fitText(text, right, halfW * 0.88f, halfH * 1.15f, 4f)
        canvas.drawText(right, cx + halfW * 0.5f, cy - (text.descent() + text.ascent()) * 0.5f, text)
    }

    /**
     * The pad's stick: [StickArt] draws it, so the Left stick preview page and
     * this control are the same stick at different radii rather than two
     * drawings that have to be kept in step by hand.
     */
    private fun drawStick(canvas: Canvas, element: ControlElement, alpha: Float, faded: Boolean) {
        val twoStaged = isTwoStage(element)
        StickArt.draw(
            canvas = canvas,
            density = density,
            cx = stickBaseX(element),
            cy = stickBaseY(element),
            r = radius(element),
            alpha = alpha,
            faded = faded,
            deflection = stickPositions[element.id],
            knobRaw = if (twoStaged) stickRaw[element.id] else null,
            twoStaged = twoStaged,
            travel = stickTravel,
            sprintHalo = isMatchStick(element) && sprintRunning(),
            label = ControlLayout.labelFor(element, playStation),
        )
    }

    /**
     * A cross of four arms that light while they are held. One socket rather than
     * four buttons: a d-pad is aimed at, not clicked, and the thumb stays on it
     * while a menu scrolls.
     */
    private fun drawDpad(canvas: Canvas, element: ControlElement, alpha: Float, faded: Boolean) {
        val r = radius(element)
        val cx = centreX(element)
        val cy = centreY(element)
        val fadeFactor = if (faded) 0.45f else 1f
        val held = dpadMaskFor(element)

        fill.color = Color.argb((alpha * fadeFactor * 75).toInt().coerceIn(0, 255), 16, 20, 26)
        canvas.drawCircle(cx, cy, r, fill)
        stroke.color = Color.argb((alpha * fadeFactor * 130).toInt().coerceIn(0, 255), 45, 55, 70)
        stroke.strokeWidth = max(1.5f, r * 0.035f)
        canvas.drawCircle(cx, cy, r - stroke.strokeWidth * 0.5f, stroke)

        val length = r * 0.72f
        notchPaint.strokeWidth = max(6f, r * 0.22f)
        for (arm in intArrayOf(NativeBridge.UP, NativeBridge.DOWN, NativeBridge.LEFT, NativeBridge.RIGHT)) {
            val on = (held and arm) != 0
            val level = if (on) 220 else 120
            notchPaint.color = Color.argb((alpha * fadeFactor * level).toInt().coerceIn(0, 255), 235, 238, 242)
            when (arm) {
                NativeBridge.UP -> canvas.drawLine(cx, cy, cx, cy - length, notchPaint)
                NativeBridge.DOWN -> canvas.drawLine(cx, cy, cx, cy + length, notchPaint)
                NativeBridge.LEFT -> canvas.drawLine(cx, cy, cx - length, cy, notchPaint)
                else -> canvas.drawLine(cx, cy, cx + length, cy, notchPaint)
            }
        }

        // The middle is where the thumb rests, not a fifth direction.
        fill.color = Color.argb((alpha * fadeFactor * 150).toInt().coerceIn(0, 255), 40, 48, 60)
        canvas.drawCircle(cx, cy, r * 0.16f, fill)
    }

    /**
     * The swipe patch: a ring of four chevrons. It has no held state to light up,
     * because by the time a direction could be drawn the flick is already over;
     * the ring says what the control is for and nothing more.
     */
    private fun drawSwipe(canvas: Canvas, element: ControlElement, alpha: Float, faded: Boolean) {
        val r = radius(element)
        val cx = centreX(element)
        val cy = centreY(element)
        val fade = if (faded) 0.45f else 1f

        fill.color = Color.argb((alpha * fade * 45).toInt().coerceIn(0, 255), 16, 20, 26)
        canvas.drawCircle(cx, cy, r, fill)
        stroke.color = Color.argb((alpha * fade * 110).toInt().coerceIn(0, 255), 90, 105, 125)
        stroke.strokeWidth = max(1.5f, r * 0.03f)
        canvas.drawCircle(cx, cy, r - stroke.strokeWidth * 0.5f, stroke)

        notchPaint.color = Color.argb((alpha * fade * 170).toInt().coerceIn(0, 255), 220, 226, 236)
        notchPaint.strokeWidth = max(2f, r * 0.06f)
        val arm = r * 0.20f
        val inset = r * 0.55f
        for (direction in intArrayOf(0, 1, 2, 3)) {
            val dx = when (direction) { 0 -> 0f; 1 -> 0f; 2 -> -1f; else -> 1f }
            val dy = when (direction) { 0 -> -1f; 1 -> 1f; else -> 0f }
            val tipX = cx + dx * inset
            val tipY = cy + dy * inset
            val backX = tipX - dx * arm
            val backY = tipY - dy * arm
            canvas.drawLine(backX, backY, tipX, tipY, notchPaint)
            // The barbs, so it reads as an arrow rather than a tick.
            val barbX = -dy * arm * 0.7f
            val barbY = dx * arm * 0.7f
            canvas.drawLine(tipX, tipY, backX + barbX, backY + barbY, notchPaint)
            canvas.drawLine(tipX, tipY, backX - barbX, backY - barbY, notchPaint)
        }
    }

    /**
     * The action a live swipe would send, in a floating pill over the button:
     * "Chip Shot" over a charging shoot button, "Low Cross" over cross. It is
     * the same [SwipeClassifier.peek] the rim arrows read, so it appears as soon
     * as a drag is far enough to count and stays there until the finger lifts --
     * it is a state, not an animation to miss.
     */
    private fun drawSwipeHud(canvas: Canvas, element: ControlElement) {
        if (element.kind != ControlKind.BUTTON && element.kind != ControlKind.CUSTOM &&
            element.kind != ControlKind.TACTICAL
        ) {
            return
        }
        val direction = liveSwipe(element)
        if (direction == Swipe.NONE) return
        val action = element.swipeAction(direction)
        if (!action.bound) return
        val label = SwipeLabels.describe(element, action) { ControlVocabulary.of(context, it) }
        if (label.isEmpty()) return

        val r = radius(element)
        val cy = centreY(element)
        val textSize = max(10f * density, r * 0.26f)
        subText.textSize = textSize
        val halfWidth = subText.measureText(label) * 0.5f + 9f * density
        val height = textSize * 1.8f
        // Above the button when there is room, below it otherwise, so the pill
        // never leaves the screen on a control at the top edge.
        val gap = 7f * density
        val above = cy - r - gap - height >= 2f * density
        val top = if (above) cy - r - gap - height else cy + r + gap
        val cx = centreX(element).coerceIn(
            halfWidth + 2f * density, max(halfWidth + 2f * density, width - halfWidth - 2f * density),
        )
        box.set(cx - halfWidth, top, cx + halfWidth, top + height)
        fill.color = Color.argb(230, 9, 14, 22)
        canvas.drawRoundRect(box, height / 2f, height / 2f, fill)
        stroke.color = Color.argb(235, 56, 189, 248)
        stroke.strokeWidth = max(1f, 1.4f * density)
        canvas.drawRoundRect(box, height / 2f, height / 2f, stroke)
        subText.color = Color.argb(255, 224, 242, 254)
        canvas.drawText(
            label, box.centerX(),
            box.centerY() - (subText.descent() + subText.ascent()) / 2f, subText,
        )
    }

    /**
     * A released swipe, drawn as a tapered trail with an arrowhead that flies
     * out of the control and fades: white over the accent colour, the pad's own
     * way of saying "that flick was read". Nothing is held while it plays, so
     * the next touch is never blocked by an animation.
     */
    private fun pruneSwipeTrails(now: Long) {
        if (swipeTrails.isEmpty()) return
        val iterator = swipeTrails.values.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().startedAt >= SWIPE_TRAIL_MS) iterator.remove()
        }
    }

    private fun drawSwipeTrail(canvas: Canvas, element: ControlElement) {
        val trail = swipeTrails[element.id] ?: return
        if (trail.direction == Swipe.NONE) {
            swipeTrails.remove(element.id)
            return
        }
        val now = SystemClock.uptimeMillis()
        val progress = ((now - trail.startedAt).toFloat() / SWIPE_TRAIL_MS).coerceIn(0f, 1f)
        if (progress >= 1f) {
            swipeTrails.remove(element.id)
            return
        }
        val r = radius(element)
        val cx = centreX(element)
        val cy = centreY(element)
        val dx = when (trail.direction) { Swipe.LEFT -> -1f; Swipe.RIGHT -> 1f; else -> 0f }
        val dy = when (trail.direction) { Swipe.UP -> -1f; Swipe.DOWN -> 1f; else -> 0f }
        // The head leaves the rim as it fades; the tail stays near the control so
        // the trail always reads as coming from the thing that was flicked.
        val head = r * (0.95f + 0.55f * progress)
        val tail = r * 0.55f
        val fade = 1f - progress
        val tipX = cx + dx * head
        val tipY = cy + dy * head
        val backX = cx + dx * tail
        val backY = cy + dy * tail

        // 1. The accent underlay, wide and soft, tapering away with the fade.
        notchPaint.color = Color.argb(
            (200 * fade * 0.45f).toInt().coerceIn(0, 255), 34, 197, 94,
        )
        notchPaint.strokeWidth = max(2f, r * 0.22f * fade)
        canvas.drawLine(backX, backY, tipX, tipY, notchPaint)

        // 2. The white core over it, narrower: the trail reads on bright turf.
        notchPaint.color = Color.argb((235 * fade).toInt().coerceIn(0, 255), 255, 255, 255)
        notchPaint.strokeWidth = max(1.5f, r * 0.085f * fade)
        canvas.drawLine(backX, backY, tipX, tipY, notchPaint)

        // 3. The arrowhead, at the moving tip.
        val arm = max(4f, r * 0.20f) * (0.6f + 0.4f * fade)
        val barbX = -dy * arm * 0.75f
        val barbY = dx * arm * 0.75f
        notchPaint.strokeWidth = max(1.5f, r * 0.075f * fade)
        canvas.drawLine(tipX, tipY, tipX - dx * arm + barbX, tipY - dy * arm + barbY, notchPaint)
        canvas.drawLine(tipX, tipY, tipX - dx * arm - barbX, tipY - dy * arm - barbY, notchPaint)
    }

    /**
     * Shrinks [paint]'s size until [value] fits [maxWidth], never below
     * [minimum]. Glyphs and captions are drawn inside fixed circles, so a long
     * rename used to run out past the rim; this keeps the drawing inside the
     * control it belongs to.
     */
    private fun fitText(
        paint: Paint, value: String, maxWidth: Float, desired: Float, minimum: Float = 6f,
    ): Float {
        paint.textSize = desired
        if (value.isEmpty() || maxWidth <= 0f) return desired
        // Measured once per string at a reference size, then scaled. Android's
        // text widths are linear in textSize, so the ratio is all that is needed
        // -- and a label is drawn every frame, so measuring it every frame was
        // work the pad did for a string that cannot change between two frames.
        var ratio = textRatios[value]
        if (ratio == null) {
            paint.textSize = FIT_SAMPLE
            ratio = paint.measureText(value) / FIT_SAMPLE
            textRatios[value] = ratio
            paint.textSize = desired
        }
        val measured = ratio * desired
        if (measured <= maxWidth) return desired
        return (desired * (maxWidth / measured)).coerceAtLeast(minimum)
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

    /**
     * The OFF badge: a red rounded pill under the control, in the editor only.
     *
     * A dimmed circle alone is ambiguous -- the editor already fades controls
     * that belong to another phase -- so the state gets a word as well as a
     * colour. One pill for every control: its size follows the word, not the
     * control, within a narrow band of sizes, so an L3 and the movement stick
     * carry the same tag instead of one twice the other's. The padding is a
     * share of the text, the gap is the same, and the pill is kept inside the
     * view -- under the control when it fits and over it when the control sits
     * on the bottom edge, which is where the toggles and the thumb clicks live.
     */
    private fun drawOffBadge(canvas: Canvas, element: ControlElement) {
        val r = radius(element)
        val cx = centreX(element)
        val cy = centreY(element)
        // The shape the control actually draws. Three of them are not the
        // circle a radius describes -- the look area is a flattened rectangle
        // and the two pills (mentality, stick mode) are drawn at half the
        // radius' height -- so anchoring at `cy + r` left their badge floating
        // in empty space while a round button's sat snug under it.
        val halfWidthOfControl: Float
        val halfHeightOfControl: Float
        when (element.kind) {
            ControlKind.LOOK -> {
                halfWidthOfControl = element.sizeOn(stance) * density * 0.5f
                halfHeightOfControl = halfWidthOfControl * 0.62f
            }
            ControlKind.MENTALITY -> {
                halfWidthOfControl = r
                halfHeightOfControl = r * 0.48f
            }
            ControlKind.STICK_MODE -> {
                halfWidthOfControl = r
                halfHeightOfControl = r * 0.50f
            }
            else -> {
                halfWidthOfControl = r
                halfHeightOfControl = r
            }
        }

        val textSize = (r * 0.28f).coerceIn(8f * density, 11f * density)
        subText.textSize = textSize
        val padX = textSize * 0.60f
        val pillHeight = textSize * 1.65f
        val gap = textSize * 0.30f
        val halfWidth = subText.measureText(OFF_BADGE) / 2f + padX

        // Below when there is room, above otherwise, so a control on the
        // bottom row cannot push its badge off the screen.
        val belowTop = cy + halfHeightOfControl + gap
        val aboveTop = cy - halfHeightOfControl - gap - pillHeight
        val top = when {
            belowTop + pillHeight + 2f * density <= height.toFloat() -> belowTop
            aboveTop >= 2f * density -> aboveTop
            else -> (height.toFloat() - pillHeight - 2f * density).coerceAtLeast(2f * density)
        }
        // And kept horizontally inside: a control at the right edge (R3, the
        // far toggles) had its pill run under the cutout.
        val centre = cx.coerceIn(
            halfWidth + 2f * density,
            max(halfWidth + 2f * density, width - halfWidth - 2f * density),
        )
        box.set(centre - halfWidth, top, centre + halfWidth, top + pillHeight)
        fill.color = COLOR_OFF
        canvas.drawRoundRect(box, pillHeight / 2f, pillHeight / 2f, fill)
        subText.color = Color.WHITE
        canvas.drawText(
            OFF_BADGE, box.centerX(),
            top + pillHeight / 2f - (subText.descent() + subText.ascent()) / 2f, subText,
        )
    }

    /** Used by the editor's panel to keep the drawing in step with a slider. */
    fun refresh() = invalidate()
}
