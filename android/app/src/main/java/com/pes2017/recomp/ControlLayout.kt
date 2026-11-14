package com.pes2017.recomp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** What a control does when it is touched. */
enum class ControlKind {
    /** A round button that holds one of the pad's bits while it is down. */
    BUTTON,

    /** A round button that holds a trigger down (0..255). */
    TRIGGER,

    /** The left stick: a base with a knob that follows the finger. */
    STICK,

    /** The look area: a region where dragging turns the view. */
    LOOK,

    /**
     * Four directions from one socket, as a cross: the arm nearest the thumb is
     * held while it stays there. The menus are navigated with it, and the stick
     * stands in for it in a match, so this is off the pitch by default.
     */
    DPAD,

    /**
     * A flick in one of four directions, read when the finger lifts. It holds no
     * bit of its own, so a swipe sends exactly one command -- putting the
     * gesture on an action button would send that button's bit for the whole
     * travel as well.
     */
    SWIPE,

    /**
     * One tap, one skill: pressing it plays the move its `value` names
     * (runtime/skill_moves.h) on the skill stick, so a feint that needs a
     * two-part stick gesture is a thumb on a button. It holds no bit of its own
     * and fires once, on the press; the runtime owns the stick until the move's
     * last frame, so a second thumb cannot interrupt it.
     */
    SKILL,
}

/** When a control belongs on screen. */
enum class PhaseMask {
    ALWAYS,
    /** The title's menus, including while it loads. */
    MENUS,
    /** A match in progress. */
    MATCH,
}

/**
 * What the title is doing, as the runtime reports it. [STARTING] and [UNKNOWN]
 * keep the menus' controls: a build that cannot tell says nothing, and hiding a
 * d-pad because of a guess would be worse than leaving it where the player put
 * it.
 */
enum class GamePhase {
    UNKNOWN,
    MENUS,
    MATCH,
    ;

    /** Whether a control marked [mask] is on screen in this phase. */
    fun shows(mask: PhaseMask): Boolean = when (mask) {
        PhaseMask.ALWAYS -> true
        PhaseMask.MENUS -> this != GamePhase.MATCH
        PhaseMask.MATCH -> this == GamePhase.MATCH
    }
}

/**
 * One thing on screen. Positions are fractions of the window so a layout
 * made on one phone still makes sense on another, and sizes are in
 * density-independent pixels so a button is the same size in millimetres on
 * every screen.
 */
data class ControlElement(
    val id: String,
    val kind: ControlKind,
    /** The pad bit (BUTTON), or which trigger (TRIGGER: 0 left, 1 right). */
    val value: Int,
    val label: String,
    var xFrac: Float,
    var yFrac: Float,
    var sizeDp: Float,
    var alpha: Float = 0.45f,
    var visible: Boolean = true,
    /** Tap to hold, tap again to let go. */
    var toggle: Boolean = false,
    /** What it does on the ball (or, for a stick, a short name), drawn on the control. */
    val sub: String = "",
    /** What it does without the ball -- defending -- drawn smaller beneath. */
    val sub2: String = "",
    /** Which of the title's screens it belongs to. */
    var shownWhen: PhaseMask = PhaseMask.ALWAYS,
    // What a SWIPE sends for each direction. 0 is "nothing", which is how a
    // fresh pad starts: a swipe that fired something unasked would be a
    // surprise, and every bit is one tap away in the editor.
    var swipeUp: Int = 0,
    var swipeDown: Int = 0,
    var swipeLeft: Int = 0,
    var swipeRight: Int = 0,
    /** What a touch on it sends when it was not a swipe at all. */
    var swipeTap: Int = 0,
) {
    /** Whether it is on screen at all: the player's switch and the phase, both. */
    fun shownIn(phase: GamePhase): Boolean = visible && phase.shows(shownWhen)
}

/**
 * The whole on-screen pad: what it is, where it came from, and how it is
 * kept. Saved as json in the app's own folder, so a layout survives an
 * update, and one bad file never stops the game starting -- it falls back to
 * the defaults instead.
 */
class ControlLayout private constructor(
    val elements: MutableList<ControlElement>,
    /** The window the defaults were laid out for, so a reset puts things back there. */
    private val widthDp: Float,
    private val heightDp: Float,
) {

    fun find(id: String): ControlElement? = elements.firstOrNull { it.id == id }

    fun resetAll() {
        val fresh = defaults(widthDp, heightDp)
        elements.clear()
        elements.addAll(fresh.elements)
    }

    fun reset(id: String) {
        val fresh = defaults(widthDp, heightDp).find(id) ?: return
        val index = elements.indexOfFirst { it.id == id }
        if (index >= 0) elements[index] = fresh
    }

    fun toJson(): String {
        val array = JSONArray()
        for (element in elements) {
            val item = JSONObject()
            item.put("id", element.id)
            item.put("x", element.xFrac.toDouble())
            item.put("y", element.yFrac.toDouble())
            item.put("size", element.sizeDp.toDouble())
            item.put("alpha", element.alpha.toDouble())
            item.put("visible", element.visible)
            item.put("toggle", element.toggle)
            item.put("when", element.shownWhen.name)
            if (element.swipeUp != 0 || element.swipeDown != 0 ||
                element.swipeLeft != 0 || element.swipeRight != 0 || element.swipeTap != 0
            ) {
                item.put("swipes", JSONArray(listOf(element.swipeUp, element.swipeDown,
                    element.swipeLeft, element.swipeRight, element.swipeTap)))
            }
            array.put(item)
        }
        val root = JSONObject()
        root.put("version", FORMAT_VERSION)
        root.put("elements", array)
        return root.toString(2)
    }

    fun save(context: Context) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            // Written beside and moved into place: a layout being saved as
            // the process is killed must not leave a half-written file that
            // the next start cannot read.
            val temporary = File(context.filesDir, "$FILE_NAME.part")
            temporary.writeText(toJson())
            if (!temporary.renameTo(file)) {
                file.writeText(toJson())
                temporary.delete()
            }
        } catch (_: Throwable) {
            // A layout that cannot be saved is a small loss; crashing over
            // it would be a large one.
        }
    }

    companion object {
        private const val FILE_NAME = "controls.json"
        private const val FORMAT_VERSION = 6

        fun load(context: Context): ControlLayout {
            // The whole screen (the match is full screen, into the cutout), in
            // landscape whichever way the phone was held when asked.
            val metrics = context.resources.displayMetrics
            var widthPx = metrics.widthPixels
            var heightPx = metrics.heightPixels
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                try {
                    val bounds = context.getSystemService(android.view.WindowManager::class.java)
                        .maximumWindowMetrics.bounds
                    widthPx = bounds.width()
                    heightPx = bounds.height()
                } catch (_: Throwable) {
                }
            }
            val widthDp = max(widthPx, heightPx) / metrics.density
            val heightDp = min(widthPx, heightPx) / metrics.density
            val layout = defaults(widthDp, heightDp)
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return layout
            try {
                val root = JSONObject(file.readText())
                val version = root.optInt("version", 1)
                if (version < FORMAT_VERSION) {
                    // An older layout (the shooter's look area, the d-pad, the old places): start over.
                    try { file.delete() } catch (_: Throwable) {}
                    return layout
                }
                val array = root.optJSONArray("elements") ?: return layout
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    // Only what the app still knows about: an element dropped
                    // in a later version is ignored rather than resurrected,
                    // and a new one keeps its default.
                    val element = layout.find(item.optString("id")) ?: continue
                    element.xFrac = item.optDouble("x", element.xFrac.toDouble())
                        .toFloat().coerceIn(0.02f, 0.98f)
                    element.yFrac = item.optDouble("y", element.yFrac.toDouble())
                        .toFloat().coerceIn(0.02f, 0.98f)
                    element.sizeDp = item.optDouble("size", element.sizeDp.toDouble())
                        .toFloat().coerceIn(32f, 900f)
                    element.alpha = item.optDouble("alpha", element.alpha.toDouble())
                        .toFloat().coerceIn(0.05f, 1f)
                    element.visible = item.optBoolean("visible", element.visible)
                    element.toggle = item.optBoolean("toggle", element.toggle)
                    // Optional: a layout written before phases existed leaves the
                    // element where it always was, and the version stays put so
                    // nobody loses their pad over one new field.
                    element.shownWhen = runCatching {
                        PhaseMask.valueOf(item.optString("when", element.shownWhen.name))
                    }.getOrDefault(element.shownWhen)
                    item.optJSONArray("swipes")?.let { swipes ->
                        element.swipeUp = swipes.optInt(0, element.swipeUp)
                        element.swipeDown = swipes.optInt(1, element.swipeDown)
                        element.swipeLeft = swipes.optInt(2, element.swipeLeft)
                        element.swipeRight = swipes.optInt(3, element.swipeRight)
                        element.swipeTap = swipes.optInt(4, element.swipeTap)
                    }
                }
            } catch (_: Throwable) {
                return defaults(widthDp, heightDp)
            }
            return layout
        }

        /**
         * Where everything starts: a football pad laid out for thumbs, the way
         * PES is played on a phone. The left thumb has only the move stick. The
         * right thumb has everything else in an arc around the four actions:
         *
         *  - Cross / A (pass) at the bottom, where the thumb rests, with
         *    Square / X (lob, cross) beside it: close enough to roll between,
         *    with a gap a thumb does not bridge by accident.
         *  - R1 / RB (sprint, "run") bigger and left of the actions, so the
         *    thumb can hold it and slide onto pass or shoot.
         *  - L1 / LB (change player) above the actions, the next thing the thumb
         *    reaches for when defending.
         *  - The skill stick above the sprint button; L2 / R2 small, under the
         *    HUD and the MENU button at the top; start and select top centre.
         *
         * Positions are in dp from the nearest edge, so the thumb distances are
         * the same on every phone and a wider screen only opens up the middle.
         * Nothing touches (with the touch slack) from a 592 x 336 dp window up.
         *
         * The game's default binding, for reference: Cross pass, Circle shoot,
         * Square lob / cross, Triangle through pass, R1 sprint, L1 change player.
         */
        fun defaults(widthDp: Float = 800f, heightDp: Float = 360f): ControlLayout {
            val w = max(widthDp, 320f)
            val h = max(heightDp, 240f)

            /** A control at x, y dp: positive from the left / top, negative from the right / bottom. */
            fun place(
                id: String, kind: ControlKind, value: Int, label: String,
                x: Float, y: Float, size: Float, alpha: Float = 0.45f, visible: Boolean = true,
                shownWhen: PhaseMask = PhaseMask.ALWAYS,
            ) = ControlElement(
                id, kind, value, label,
                (if (x >= 0f) x else w + x) / w, (if (y >= 0f) y else h + y) / h,
                size, alpha, visible, shownWhen = shownWhen,
            )

            val centre = w / 2f
            return ControlLayout(
                mutableListOf(
                    // Left thumb: movement.
                    place("stick", ControlKind.STICK, 0, "LS", 95f, -95f, 150f, 0.40f),

                    // Right thumb: the four actions as the pad's diamond.
                    place("a", ControlKind.BUTTON, NativeBridge.A, "A", -110f, -52f, 68f),
                    place("b", ControlKind.BUTTON, NativeBridge.B, "B", -52f, -110f, 68f),
                    place("x", ControlKind.BUTTON, NativeBridge.X, "X", -168f, -110f, 68f),
                    place("y", ControlKind.BUTTON, NativeBridge.Y, "Y", -110f, -168f, 68f),

                    // The shoulders where a pad has them: L2 / L1 along the top left,
                    // R1 / R2 along the top right, triggers outermost.
                    place("lt", ControlKind.TRIGGER, 0, "LT", 44f, 76f, 56f),
                    place("lb", ControlKind.BUTTON, NativeBridge.LB, "LB", 110f, 76f, 56f),
                    place("rb", ControlKind.BUTTON, NativeBridge.RB, "RB", -110f, 76f, 56f),
                    place("rt", ControlKind.TRIGGER, 1, "RT", -44f, 76f, 56f),

                    // The skill stick, left of the actions.
                    place("right_stick", ControlKind.STICK, 1, "RS", -250f, -170f, 100f, 0.40f),

                    // The skill palette: one button per move, played on the skill
                    // stick by the runtime. Hidden until the player turns it on,
                    // as the swipe pad is, and shown only over the pitch, where a
                    // move means anything. `value` is an index into
                    // runtime/skill_moves.h, so the order here is the table's and
                    // must not be shuffled.
                    place("skill_double_touch", ControlKind.SKILL, 0, "D.TOUCH", -330f, -250f, 48f, 0.40f, visible = false, shownWhen = PhaseMask.MATCH),
                    place("skill_roulette", ControlKind.SKILL, 1, "ROULETTE", -330f, -196f, 48f, 0.40f, visible = false, shownWhen = PhaseMask.MATCH),
                    place("skill_rainbow", ControlKind.SKILL, 2, "RAINBOW", -330f, -142f, 48f, 0.40f, visible = false, shownWhen = PhaseMask.MATCH),
                    place("skill_drag_back", ControlKind.SKILL, 3, "DRAG BK", -330f, -88f, 48f, 0.40f, visible = false, shownWhen = PhaseMask.MATCH),
                    place("skill_body_feint", ControlKind.SKILL, 4, "BODY FNT", -330f, -34f, 48f, 0.40f, visible = false, shownWhen = PhaseMask.MATCH),

                    // Four-way swipes, hidden until the player binds a direction:
                    // an unbound swipe stays out of the way rather than
                    // duplicating an action button.
                    place("swipe", ControlKind.SWIPE, 0, "SWIPE", -215f, -52f, 96f, 0.40f, visible = false),

                    // The menus' d-pad, above where the left thumb rests. It is
                    // on screen only while the title is in its menus (the app's
                    // phase listener), because over the pitch the stick already
                    // stands in for it.
                    place("dpad", ControlKind.DPAD, 0, "D-PAD", 90f, -215f, 110f, 0.40f,
                        shownWhen = PhaseMask.MENUS),

                    // Stick clicks: kept for the editor, hidden until wanted. R3
                    // sits out of the skill palette's column, which it used to
                    // share: two controls on one spot is a thumb that means one
                    // and gets the other.
                    place("l3", ControlKind.BUTTON, NativeBridge.L3, "L3", 200f, -40f, 52f, visible = false),
                    place("r3", ControlKind.BUTTON, NativeBridge.R3, "R3", -430f, -45f, 52f, visible = false),

                    // Menus have to be found at a glance in a football game.
                    place("start", ControlKind.BUTTON, NativeBridge.START, "START", centre + 60f, 37f, 54f, 0.75f),
                    place("back", ControlKind.BUTTON, NativeBridge.BACK, "SELECT", centre - 40f, 37f, 54f, 0.75f),
                ),
                widthDp, heightDp,
            )
        }

        /** The name printed on a control: its Xbox name, or the PlayStation one. */
        fun labelFor(element: ControlElement, playStation: Boolean): String {
            if (!playStation) return element.label
            return when (element.id) {
                "a" -> "CROSS"
                "b" -> "CIRCLE"
                "x" -> "SQUARE"
                "y" -> "TRIANGLE"
                "lb" -> "L1"
                "rb" -> "R1"
                "lt" -> "L2"
                "rt" -> "R2"
                "stick" -> "L"
                "right_stick" -> "R"
                "start" -> "START"
                "back" -> "SELECT"
                else -> element.label
            }
        }
    }
}
