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
    var sub: String = "",
    /** What it does without the ball -- defending -- drawn smaller beneath. */
    var sub2: String = "",
)

/**
 * The whole on-screen pad: what it is, where it came from, and how it is
 * kept. Saved as json in the app's own folder, so a layout survives an
 * update, and one bad file never stops the game starting -- it falls back to
 * the defaults instead.
 */
class ControlLayout private constructor(
    val elements: MutableList<ControlElement>,
    /** The window the defaults were laid out for, so a reset puts things back there. */
    val widthDp: Float,
    val heightDp: Float,
    var sprintThreshold: Float = 0.85f,
    var autoSprintEnabled: Boolean = true,
    var flickGesturesEnabled: Boolean = true,
    var deadzoneThreshold: Float = 0.15f,
    var pressedAlpha: Float = 0.85f,
    var currentProfile: String = PROFILE_DEFAULT,
) {

    fun find(id: String): ControlElement? = elements.firstOrNull { it.id == id }

    fun resetAll() {
        val fresh = defaults(widthDp, heightDp, currentProfile)
        elements.clear()
        elements.addAll(fresh.elements)
        sprintThreshold = fresh.sprintThreshold
        autoSprintEnabled = fresh.autoSprintEnabled
        flickGesturesEnabled = fresh.flickGesturesEnabled
        deadzoneThreshold = fresh.deadzoneThreshold
        pressedAlpha = fresh.pressedAlpha
    }

    fun applyProfile(profileName: String) {
        currentProfile = profileName
        val fresh = defaults(widthDp, heightDp, profileName)
        elements.clear()
        elements.addAll(fresh.elements)
        sprintThreshold = fresh.sprintThreshold
        autoSprintEnabled = fresh.autoSprintEnabled
        flickGesturesEnabled = fresh.flickGesturesEnabled
        deadzoneThreshold = fresh.deadzoneThreshold
        pressedAlpha = fresh.pressedAlpha
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
            if (element.sub.isNotEmpty()) item.put("sub", element.sub)
            if (element.sub2.isNotEmpty()) item.put("sub2", element.sub2)
            array.put(item)
        }
        val root = JSONObject()
        root.put("version", FORMAT_VERSION)
        root.put("currentProfile", currentProfile)
        root.put("sprintThreshold", sprintThreshold.toDouble())
        root.put("autoSprintEnabled", autoSprintEnabled)
        root.put("flickGesturesEnabled", flickGesturesEnabled)
        root.put("deadzoneThreshold", deadzoneThreshold.toDouble())
        root.put("pressedAlpha", pressedAlpha.toDouble())
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
        private const val FORMAT_VERSION = 9
        const val PROFILE_DEFAULT = "Default"
        const val PROFILE_TIKI_TAKA = "Tiki-Taka"
        const val PROFILE_DEFENSIVE = "Defensive"

        fun load(context: Context): ControlLayout {
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
                    try { file.delete() } catch (_: Throwable) {}
                    return layout
                }
                layout.sprintThreshold = root.optDouble("sprintThreshold", 0.85).toFloat().coerceIn(0.70f, 0.95f)
                layout.autoSprintEnabled = root.optBoolean("autoSprintEnabled", true)
                layout.flickGesturesEnabled = root.optBoolean("flickGesturesEnabled", true)
                layout.currentProfile = root.optString("currentProfile", PROFILE_DEFAULT)
                layout.deadzoneThreshold = root.optDouble("deadzoneThreshold", 0.15).toFloat().coerceIn(0.05f, 0.35f)
                layout.pressedAlpha = root.optDouble("pressedAlpha", 0.85).toFloat().coerceIn(0.10f, 1.0f)

                val array = root.optJSONArray("elements") ?: return layout
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
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
                    val s1 = item.optString("sub", "")
                    if (s1.isNotEmpty()) element.sub = s1
                    val s2 = item.optString("sub2", "")
                    if (s2.isNotEmpty()) element.sub2 = s2
                }
            } catch (_: Throwable) {
                return defaults(widthDp, heightDp)
            }
            return layout
        }

        fun defaults(widthDp: Float = 800f, heightDp: Float = 360f, profile: String = PROFILE_DEFAULT): ControlLayout {
            val w = max(widthDp, 320f)
            val h = max(heightDp, 240f)

            fun place(
                id: String, kind: ControlKind, value: Int, label: String,
                x: Float, y: Float, size: Float, alpha: Float = 0.50f, visible: Boolean = true,
                sub: String = "", sub2: String = "",
            ) = ControlElement(
                id, kind, value, label,
                (if (x >= 0f) x else w + x) / w, (if (y >= 0f) y else h + y) / h,
                size, alpha, visible,
                sub = sub, sub2 = sub2,
            )

            val centre = w / 2f
            val baseList = mutableListOf(
                // Left thumb: eFootball floating/fixed analogue joystick with auto-sprint
                place("stick", ControlKind.STICK, 0, "LS", 100f, -100f, 150f, 0.40f),

                // Right thumb: eFootball Classic 4-button action cluster
                place("y", ControlKind.BUTTON, NativeBridge.Y, "Y", -105f, -170f, 74f, sub = "THROUGH", sub2 = "GK RUSH"),
                place("x", ControlKind.BUTTON, NativeBridge.X, "X", -170f, -105f, 74f, sub = "SHOOT", sub2 = "TACKLE"),
                place("b", ControlKind.BUTTON, NativeBridge.B, "B", -40f, -105f, 74f, sub = "CROSS", sub2 = "CLEAR"),
                place("a", ControlKind.BUTTON, NativeBridge.A, "A", -105f, -40f, 74f, sub = "PASS", sub2 = "PRESSURE"),

                // Multi-Action Merged Button: Super-Cancel (RB + RT)
                place("super_cancel", ControlKind.BUTTON, NativeBridge.RB or 0x00010000, "CANCEL", -175f, -175f, 54f, alpha = 0.40f, visible = false, sub = "CANCEL", sub2 = "CANCEL"),

                // Tactical / Shoulder controls along top
                place("lb", ControlKind.BUTTON, NativeBridge.LB, "LB", -110f, 70f, 56f, sub = "SWITCH", sub2 = "SWITCH"),
                place("rt", ControlKind.TRIGGER, 1, "RT", -44f, 70f, 56f, sub = "MATCH UP", sub2 = "MATCH UP"),
                place("lt", ControlKind.TRIGGER, 0, "LT", 44f, 70f, 56f),

                // Standalone DASH (RB) fallback
                place("rb", ControlKind.BUTTON, NativeBridge.RB, "RB", -240f, -40f, 60f, visible = false, sub = "DASH", sub2 = "DASH"),

                // Skill stick, hidden by default
                place("right_stick", ControlKind.STICK, 1, "RS", -255f, -170f, 100f, 0.40f, visible = false),

                // Stick clicks
                place("l3", ControlKind.BUTTON, NativeBridge.L3, "L3", 200f, -40f, 52f, visible = false),
                place("r3", ControlKind.BUTTON, NativeBridge.R3, "R3", -320f, -45f, 52f, visible = false),

                // Menus
                place("start", ControlKind.BUTTON, NativeBridge.START, "START", centre + 60f, 37f, 54f, 0.75f),
                place("back", ControlKind.BUTTON, NativeBridge.BACK, "SELECT", centre - 40f, 37f, 54f, 0.75f),
            )

            var sprintThresh = 0.85f
            if (profile == PROFILE_TIKI_TAKA) {
                sprintThresh = 0.90f
            } else if (profile == PROFILE_DEFENSIVE) {
                sprintThresh = 0.80f
            }

            return ControlLayout(
                baseList,
                widthDp, heightDp,
                sprintThreshold = sprintThresh,
                autoSprintEnabled = true,
                flickGesturesEnabled = true,
                deadzoneThreshold = 0.15f,
                pressedAlpha = 0.85f,
                currentProfile = profile,
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
