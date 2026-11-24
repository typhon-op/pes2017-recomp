package com.pes2017.recomp

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialog
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The in-game menu, drawn the way PES 2017 draws one on the 360: a panel down
 * the left of the pitch, a dark blue gradient under condensed italic capitals,
 * and one bright band with a cyan edge on the row the player is on.
 *
 * One dialog holds a stack of [Screen]s, so a submenu is the same window with
 * new rows -- there is never a second dialog to flicker over the first, and the
 * back key walks the stack the way the original game does. Rows are built by a
 * lambda rather than kept: a toggle can be re-rendered with its new word without
 * rebuilding the screen by hand.
 *
 * Controller-first, touch-friendly: the D-pad and stick move the band, A
 * (Cross) chooses, B (Circle) goes back, Start closes; a tap chooses the row it
 * lands on, which is the same one-key grammar on glass.
 */
class PesMenu(
    private val activity: Activity,
    private val onClosed: () -> Unit = {},
) : AppCompatDialog(activity, R.style.Theme_PES17_Menu) {

    /** One line of a screen. [checked] turns the row into a choice with a dot. */
    class Row(
        val label: String,
        val detail: String = "",
        val enabled: Boolean = true,
        val checked: Boolean? = null,
        /**
         * A row that switches something. Pressing it plays the toggle sound
         * instead of the press beep; a row with [checked] is one too, since
         * the dot is this menu's way of showing a switch's state.
         */
        val toggle: Boolean = false,
        val onAdjust: ((PesMenu, Int) -> Unit)? = null,
        val activate: ((PesMenu) -> Unit)? = null,
    )

    /** One text box on a form screen (rename, a macro's name, the console). */
    class Field(
        val label: String,
        val initial: String = "",
        val hint: String = "",
        val multiline: Boolean = false,
    )

    /** A whole screen: a heading, a sentence, its rows, and its form boxes. */
    class Screen(
        val title: String,
        val message: String = "",
        val rows: List<Row> = emptyList(),
        val fields: List<Field> = emptyList(),
        val submit: String = "",
        val onSubmit: (List<String>) -> Unit = {},
        val hint: String = "",
        /**
         * Whether the page wants the menu's own Back row. A page that already
         * prints a way back of its own -- a prompt's Cancel, an empty list's
         * Close -- sets this to false, so the way up is never offered twice.
         */
        val backRow: Boolean = true,
    )

    private val density = activity.resources.displayMetrics.density

    /**
     * Arabic is written joined: a letter-spaced run pulls the strokes apart
     * and reads as disconnected letters, so the panel drops its tracking in
     * right-to-left languages. The brand line keeps its own spacing because it
     * is "PES 2017 | Typhon Lhoucine" in either language.
     */
    private val rtl = activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    /**
     * The launcher's Dark Mode, read once as the menu opens. The panel, header
     * and foot are navy in both modes, but the rows were a fixed light grey --
     * the one surface that still read as Light Mode in the pause menu that
     * carries Edit Controls. The rows follow the choice now.
     */
    private val dark = Prefs(activity).darkMode

    private fun dp(value: Int): Int = (value * density).roundToInt()

    /**
     * PES's condensed italic capitals are a Latin design. Arabic has no italic
     * of its own and the system synthesises one by shearing the whole line,
     * which pushes the outer letters of a run past its advance and gets them
     * clipped; so a right-to-left menu keeps the family and the weight and
     * drops the slope.
     */
    private val headingType = Typeface.create(
        "sans-serif-condensed", if (rtl) Typeface.BOLD else Typeface.BOLD_ITALIC,
    )
    private val bodyType = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    private val root = FrameLayout(activity)
    private val backdrop = Backdrop(activity)
    private val panel = LinearLayout(activity)
    private val brand = TextView(activity)
    private val heading = TextView(activity)
    private val message = TextView(activity)
    private val scroll = CappedScrollView(activity)
    private val body = LinearLayout(activity)
    private val promptStrip = TextView(activity)
    private val hintLine = TextView(activity)

    private val stack = ArrayList<(PesMenu) -> Screen>()

    /**
     * The heading of every page on [stack], kept as the pages are rendered so a
     * submenu's Back row can name the page it returns to without building that
     * page a second time.
     */
    private val titles = ArrayList<String>()
    private var screen: Screen? = null
    private var index = 0
    private val rows = ArrayList<Row>()
    private val rowViews = ArrayList<RowView>()
    private val fieldViews = ArrayList<EditText>()

    /** How many pages deep the menu is; a row that pushed one leaves it open. */
    val depth: Int get() = stack.size

    private var closing = false
    private var closedOnce = false
    private var lastStickStep = 0L

    init {
        setCanceledOnTouchOutside(false)
        // The two menu samples are loaded once per process, on the first menu
        // that opens. A device that cannot play them says nothing and the menu
        // works the same (MenuSounds).
        MenuSounds.prepare(activity)
        setOnDismissListener {
            if (!closedOnce) {
                closedOnce = true
                onClosed()
            }
        }

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(DIM)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        val metrics = activity.resources.displayMetrics
        val widthDp = metrics.widthPixels / metrics.density
        val panelWidth = (min(widthDp * PANEL_FRACTION, PANEL_MAX_DP) * density).roundToInt()

        // The panel is opaque now. It used to be a 95%-opaque blue gradient,
        // and over a paused match -- whose own bright menu is still drawn on
        // the surface behind this window -- the game's white text showed
        // through the rows and between the letters. That is what "the Arabic
        // is covered" was: not the translation, and not the font.
        panel.orientation = LinearLayout.VERTICAL
        // The panel takes its own taps: only the pitch beside it goes back, so
        // tapping a heading or the space between rows cannot close the menu.
        panel.setOnClickListener { }
        panel.background = GradientDrawable().apply {
            setColor(PANEL)
            setStroke(maxOf(1, dp(1)), PANEL_EDGE)
        }
        // Centred in the window, in every language: pinned to a start edge the
        // same menu sits on a different side depending on the language.
        panel.layoutParams = FrameLayout.LayoutParams(
            panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        )
        panel.setPadding(0, 0, 0, 0)

        // The header is the PES 2017 blue bar with the page's title on it, as
        // the Edit Menu in the reference is: edge to edge on the panel, white
        // bold lettering, the build line above it in the quieter cyan.
        brand.text = SpannableStringBuilder("PES 2017").apply {
            val barAt = length
            append(" | ")
            setSpan(
                ForegroundColorSpan(COLOR_HEADER_BAR), barAt, length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            val nameAt = length
            append(OWNER)
            setSpan(
                ForegroundColorSpan(COLOR_HEADER_NAME), nameAt, length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        brand.setTextColor(BRAND)
        brand.textSize = 9.5f
        brand.letterSpacing = 0.28f
        brand.typeface = bodyType

        heading.setTextColor(Color.WHITE)
        heading.textSize = 18f
        heading.typeface = headingType
        heading.letterSpacing = if (rtl) 0f else 0.02f
        // No all-caps transformation in Arabic: it rewrites the string before
        // shaping and can drop the last glyph of a run.
        heading.isAllCaps = false
        heading.maxLines = 2
        heading.ellipsize = android.text.TextUtils.TruncateAt.END

        message.setTextColor(HEADER_TEXT)
        message.textSize = 11f
        message.typeface = bodyType
        message.setPadding(0, dp(3), 0, 0)
        message.visibility = View.GONE

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(HEADER)
            setPadding(dp(14), dp(9), dp(14), dp(10))
            addView(brand, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(
                heading,
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            addView(message, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        panel.addView(header, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        body.orientation = LinearLayout.VERTICAL
        body.setPadding(0, dp(2), 0, dp(2))
        scroll.isFillViewport = false
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(body, FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        // The page never grows past the screen: past that the rows scroll, and
        // the panel ends on a whole row rather than half of one.
        scroll.maxHeight = (metrics.heightPixels - dp(190)).coerceAtLeast(dp(120))
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        promptStrip.setTextColor(PROMPT)
        promptStrip.textSize = 10f
        promptStrip.typeface = bodyType
        promptStrip.letterSpacing = if (rtl) 0f else 0.06f

        hintLine.setTextColor(HINT)
        hintLine.textSize = 9.5f
        hintLine.typeface = bodyType
        hintLine.setPadding(0, dp(2), 0, 0)
        hintLine.visibility = View.GONE

        val foot = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(FOOT)
            setPadding(dp(14), dp(7), dp(14), dp(9))
            addView(promptStrip, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(hintLine, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        panel.addView(foot, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(backdrop, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(panel)
        // A tap on the pitch beside the panel is swallowed rather than closing
        // the menu: a thumb resting on the glass must not resume the match.
        // Carry on, or B, is the way out.
        root.setOnClickListener { }
        setContentView(root)
    }

    // ---- opening and closing -------------------------------------------------

    /** Opens the dialog on [build], replacing whatever stack was there. */
    fun open(build: (PesMenu) -> Screen) {
        stack.clear()
        titles.clear()
        stack.add(build)
        // A fresh visit: whatever ended the previous one (dismissNow, a close)
        // must not leave this page unable to push or to go back. An instance is
        // normally used once, but a menu that silently refused to navigate
        // because of a flag from an earlier life is exactly the stuck submenu
        // this class is supposed to make impossible.
        closing = false
        closedOnce = false
        if (!isShowing) {
            panel.alpha = 0f
            panel.translationX = -dp(28).toFloat()
            show()
            panel.animate().alpha(1f).translationX(0f).setDuration(OPEN_MS).start()
        }
        showScreen(build, keepSelection = false)
    }

    /** Opens [build] on top of the current screen, as a submenu. */
    fun push(build: (PesMenu) -> Screen) {
        if (closing) return
        // A push on a menu that is not on screen would be a page nobody can
        // see or leave: open it as the root instead.
        if (!isShowing) {
            open(build)
            return
        }
        stack.add(build)
        showScreen(build, keepSelection = false)
        rebrand()
    }

    /** Back one level, closing the dialog when this is the root. */
    fun back() {
        if (closing) return
        MenuSounds.press()
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
            showScreen(stack.last(), keepSelection = false)
            rebrand()
        } else {
            closeMenu()
        }
    }

    /** Re-runs the current screen's builder, keeping the lit row where it was. */
    fun refresh() {
        val builder = stack.lastOrNull() ?: return
        showScreen(builder, keepSelection = true)
    }

    /** Dismisses at once, for a caller that is opening another screen behind it. */
    fun dismissNow() {
        if (!isShowing) return
        closing = true
        dismiss()
    }

    /** Animates out and dismisses. Safe to call twice. */
    fun closeMenu() {
        if (closing || !isShowing) {
            if (isShowing) dismiss()
            return
        }
        closing = true
        panel.animate()
            .alpha(0f)
            .translationX(-dp(24).toFloat())
            .setDuration(CLOSE_MS)
            .withEndAction { dismiss() }
            .start()
    }

    override fun onBackPressed() {
        back()
    }

    // ---- rendering -----------------------------------------------------------

    private fun showScreen(build: (PesMenu) -> Screen, keepSelection: Boolean) {
        val next = build(this)
        val previous = index
        screen = next
        while (titles.size > stack.size) titles.removeAt(titles.size - 1)
        if (titles.size == stack.size) titles[stack.size - 1] = next.title
        else titles.add(next.title)
        rows.clear()
        rows.addAll(next.rows)
        val selection = MenuSelection(rows.map { it.enabled }, if (keepSelection) previous else 0)
        index = selection.index
        render(next)
        if (!keepSelection) {
            scroll.post { scroll.scrollTo(0, 0) }
        }
    }

    /** The row that leaves this page for the one under it, named for its parent. */
    private fun backRow(): Row = Row(
        activity.getString(R.string.back),
        detail = titles.getOrNull(stack.size - 2).orEmpty(),
    ) { it.back() }

    private fun rebrand() {
        // A submenu is a new page: the panel eases in from the left, the same
        // way the root did, so the eye reads it as a page turn rather than a
        // list that changed under the thumb.
        panel.translationX = -dp(14).toFloat()
        panel.alpha = 0.35f
        panel.animate().translationX(0f).alpha(1f).setDuration(PUSH_MS).start()
    }

    private fun render(current: Screen) {
        heading.text = current.title
        message.text = current.message
        message.visibility = if (current.message.isEmpty()) View.GONE else View.VISIBLE

        body.removeAllViews()
        rowViews.clear()
        fieldViews.clear()

        // Every page above the root prints its way back to the page under it,
        // first in the panel, where a long list cannot push it out of sight.
        // B and the system's back gesture do the same thing, so a player on a
        // pad loses nothing and a player on glass never has to know a key to
        // leave a submenu. It names the parent, so it can only lead one level
        // up, and it is not one of the page's rows: the band and the pickers'
        // indices are exactly what they were.
        if (stack.size > 1 && current.backRow) {
            val back = RowView(backRow(), BACK_ROW)
            back.setRowGap()
            body.addView(
                back,
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        for (field in current.fields) {
            body.addView(fieldLabel(field.label))
            val editor = EditText(activity).apply {
                setText(field.initial)
                if (field.hint.isNotEmpty()) hint = field.hint
                setSelection(text.length)
                setSingleLine(!field.multiline)
                minLines = if (field.multiline) 2 else 1
                setTextColor(0xFFEAF4FF.toInt())
                setHintTextColor(0xFF7F97B8.toInt())
                textSize = 14f
                typeface = bodyType
                background = GradientDrawable().apply {
                    setColor(0x66102038)
                    cornerRadius = dp(2).toFloat()
                    setStroke(maxOf(1, dp(1)), 0x6635D3FF)
                }
                setPadding(dp(8), dp(6), dp(8), dp(6))
                inputType = InputType.TYPE_CLASS_TEXT or
                    if (field.multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
                imeOptions = EditorInfo.IME_ACTION_DONE
            }
            fieldViews.add(editor)
            body.addView(editor, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        for ((i, row) in rows.withIndex()) {
            val view = RowView(row, i)
            view.setRowGap()
            rowViews.add(view)
            body.addView(
                view, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        paintSelection(scrollTo = false)

        val hasAdjust = rows.any { it.onAdjust != null }
        promptStrip.text = when {
            current.fields.isNotEmpty() -> activity.getString(
                R.string.menu_prompt_confirm,
                current.submit.ifEmpty { activity.getString(R.string.menu_confirm) },
            )
            hasAdjust -> activity.getString(R.string.menu_prompt_adjust)
            else -> activity.getString(R.string.menu_prompt_select)
        }
        hintLine.text = current.hint
        hintLine.visibility = if (current.hint.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun fieldLabel(text: String): TextView = TextView(activity).apply {
        this.text = text
        setTextColor(0xFF9FC4EA.toInt())
        textSize = 11f
        letterSpacing = if (rtl) 0f else 0.08f
        typeface = bodyType
        isAllCaps = true
        setPadding(0, dp(8), 0, dp(3))
    }

    private fun paintSelection(scrollTo: Boolean) {
        for (view in rowViews) view.chosen = view.rowIndex == index
        if (scrollTo && index in rowViews.indices) {
            rowViews[index].requestRectangleOnScreen(
                android.graphics.Rect(0, 0, rowViews[index].width, rowViews[index].height), false,
            )
        }
    }

    // ---- moving and choosing -------------------------------------------------

    private fun move(delta: Int) {
        val selection = MenuSelection(rows.map { it.enabled }, index)
        if (selection.move(delta)) {
            index = selection.index
            paintSelection(scrollTo = true)
            MenuSounds.press()
        }
    }

    private fun choose() {
        val row = rows.getOrNull(index) ?: return
        if (!row.enabled) return
        if (row.onAdjust != null) {
            MenuSounds.press()
            row.onAdjust.invoke(this, 1)
        } else {
            // A switch is a toggle action, everything else is a press.
            if (row.toggle || row.checked != null) MenuSounds.toggle() else MenuSounds.press()
            row.activate?.invoke(this) ?: closeMenu()
        }
    }

    private fun adjust(row: Row, delta: Int) {
        val handler = row.onAdjust ?: return
        MenuSounds.press()
        handler.invoke(this, delta)
    }

    /** Confirms a form page from a row, the same way A does from the box. */
    fun submit() {
        val current = screen ?: return
        if (current.fields.isEmpty()) return
        MenuSounds.press()
        current.onSubmit.invoke(fieldViews.map { it.text.toString() })
    }

    // ---- controller ----------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val repeat = event.repeatCount > 0
        // Typing in a form box keeps the arrow keys: they move the caret there,
        // not the band. A, B and Start still work, so the pad still leaves.
        val typing = currentFocus is EditText && screen?.fields?.isNotEmpty() == true
        if (typing && event.keyCode !in FRAME_KEYS) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (down) move(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) move(1)
            KeyEvent.KEYCODE_DPAD_LEFT ->
                if (down) adjust(rows.getOrNull(index) ?: return true, -1)
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (down) adjust(rows.getOrNull(index) ?: return true, 1)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE ->
                if (down && !repeat) {
                    if (screen?.fields?.isNotEmpty() == true) submit() else choose()
                }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK ->
                if (down) back()
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU ->
                if (down) closeMenu()
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    /**
     * The analogue stick, for a pad that only reports it as an axis. It moves
     * the band at a walking pace rather than once per frame, so holding the
     * stick over does not run to the end of the list.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) {
            return super.onGenericMotionEvent(event)
        }
        val x = event.getAxisValue(MotionEvent.AXIS_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y)
        if (kotlin.math.abs(x) < STICK_DEADZONE && kotlin.math.abs(y) < STICK_DEADZONE) return false
        val now = SystemClock.uptimeMillis()
        if (now - lastStickStep < STICK_REPEAT_MS) return true
        lastStickStep = now
        when {
            y < -STICK_DEADZONE -> move(-1)
            y > STICK_DEADZONE -> move(1)
            x < -STICK_DEADZONE -> adjust(rows.getOrNull(index) ?: return true, -1)
            x > STICK_DEADZONE -> adjust(rows.getOrNull(index) ?: return true, 1)
        }
        return true
    }

    // ---- one row -------------------------------------------------------------

    /**
     * A row: the band and its cyan edge are drawn, not composed from images, so
     * the panel stays one cheap gradient per selection change over a running
     * match. The label and the value are ordinary text views on top.
     */
    private inner class RowView(val row: Row, val rowIndex: Int) : LinearLayout(activity) {

        // Both scroll when they do not fit, and sit still when they do: a row
        // that cuts a saved pad's name or a bound command into "LB + LS U..."
        // is the menu failing to say what it means.
        private val label = MarqueeText(activity)
        private val detail = MarqueeText(activity)
        private val tick = TextView(activity)
        private val chevron = TextView(activity)
        // Flat fills, as the reference's rows are: light grey for a row, the
        // bright PES blue for the one the player is on. Two drawables reused by
        // every row rather than one gradient repainted per selection change.
        private val resting = GradientDrawable().apply { setColor(if (dark) DARK_ROW else ROW) }
        private val lit = GradientDrawable().apply { setColor(SELECTED) }

        var chosen: Boolean
            get() = isSelected
            set(value) {
                if (isSelected == value) return
                isSelected = value
                restyle()
                invalidate()
            }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // A row is one line of text plus the breathing room the reference
            // gives it; the text decides the floor so a taller Arabic face can
            // never be cut at the row's edge.
            minimumHeight = dp(42)
            isClickable = true
            isFocusable = true
            // Relative, so the text starts from the side the language starts
            // from, and the tick and the value stay on their own sides.
            setPaddingRelative(dp(14), dp(4), dp(10), dp(4))
            background = resting

            label.text = row.label
            label.typeface = headingType
            label.textSize = 15f
            label.letterSpacing = if (rtl) 0f else 0.03f
            label.isAllCaps = false
            label.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

            detail.typeface = bodyType
            detail.textSize = 11.5f
            detail.gravity = Gravity.END
            detail.setPaddingRelative(dp(8), 0, dp(4), 0)

            tick.typeface = bodyType
            tick.textSize = 11f
            tick.setPaddingRelative(0, 0, dp(8), 0)
            tick.text = "\u25CF"

            chevron.typeface = headingType
            chevron.textSize = 16f
            chevron.text = "\u203A"

            addView(tick)
            addView(label)
            addView(detail)
            addView(chevron)

            tick.visibility = if (row.checked == null) View.GONE else View.VISIBLE
            detail.visibility = if (row.detail.isEmpty()) View.GONE else View.VISIBLE
            chevron.visibility = if (row.onAdjust == null && row.enabled) View.VISIBLE else View.GONE

            restyle()

            setOnClickListener {
                if (!row.enabled) return@setOnClickListener
                // The Back row is not a page row: a tap leaves the page rather
                // than moving the band onto something that is not a choice.
                if (rowIndex == BACK_ROW) {
                    MenuSounds.press()
                    row.activate?.invoke(this@PesMenu)
                    return@setOnClickListener
                }
                if (index != rowIndex) {
                    index = rowIndex
                    paintSelection(scrollTo = true)
                }
                if (row.onAdjust != null) {
                    adjust(row, 1)
                } else {
                    if (row.toggle || row.checked != null) MenuSounds.toggle() else MenuSounds.press()
                    row.activate?.invoke(this@PesMenu) ?: closeMenu()
                }
            }
            setOnLongClickListener {
                if (!row.enabled || row.onAdjust == null) return@setOnLongClickListener false
                adjust(row, -1)
                true
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // The value column is capped, never the name: a long value would
            // otherwise claim the row's whole width and squeeze the name out of
            // sight, which is the same cut-off in another form. It is set from
            // the measured row, so it follows a rotation or a different screen.
            detail.maxWidth = (w * DETAIL_SHARE).toInt()
        }

        /** The hairline gap between two rows, which is the panel showing. */
        fun setRowGap() {
            val params = layoutParams as? LinearLayout.LayoutParams ?: return
            params.bottomMargin = dp(2)
            layoutParams = params
        }

        private fun restyle() {
            background = if (isSelected) lit else resting
            label.setTextColor(
                when {
                    !row.enabled -> if (dark) 0xFF6B7788.toInt() else 0xFF77828F.toInt()
                    isSelected -> Color.WHITE
                    else -> if (dark) DARK_INK else INK
                },
            )
            detail.setTextColor(
                if (isSelected) 0xFFD9ECFF.toInt()
                else if (dark) 0xFFA9B8D4.toInt() else 0xFF3E4C5C.toInt(),
            )
            detail.text = row.detail
            tick.setTextColor(
                if (row.checked == true) 0xFF1B8DFF.toInt()
                else if (dark) 0x55EAF2FF.toInt() else 0x33202C3A,
            )
            chevron.setTextColor(
                if (isSelected) 0xFFEAF6FF.toInt()
                else if (dark) 0xFF9FB2D0.toInt() else 0xFF5C6B7C.toInt(),
            )
            alpha = if (row.enabled) 1f else 0.6f
        }
    }

    companion object {
        private val FRAME_KEYS = setOf(
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MENU,
        )

        /** How much of the window the panel takes, and its ceiling on a tablet. */
        private const val PANEL_FRACTION = 0.58f
        private const val PANEL_MAX_DP = 520f

        /** The Back row's place in a page: not one of its own rows, so never lit. */
        private const val BACK_ROW = -1

        /** The most of a row's width its value may take; the name keeps the rest. */
        private const val DETAIL_SHARE = 0.45f

        private const val STICK_DEADZONE = 0.6f
        private const val STICK_REPEAT_MS = 170L

        private const val OPEN_MS = 200L
        private const val PUSH_MS = 150L
        private const val CLOSE_MS = 160L

        private const val DIM = 0.45f

        /** Whose build this is, printed beside the title in the panel's header. */
        private const val OWNER = "Typhon Lhoucine"
        private const val COLOR_HEADER_NAME = 0xFFEAF4FF.toInt()
        private const val COLOR_HEADER_BAR = 0x9957C7FF.toInt()

        /** The panel: opaque navy, so nothing behind it reads through a row. */
        private const val PANEL = 0xFF0B1420.toInt()
        private const val PANEL_EDGE = 0xFF1B2A3D.toInt()

        /** The title bar, and the strip along the foot that names the keys. */
        private const val HEADER = 0xFF1D5B93.toInt()
        private const val HEADER_TEXT = 0xFFD6E9FF.toInt()
        private const val FOOT = 0xFF0E1A27.toInt()
        private const val PROMPT = 0xFF7FD2F5.toInt()
        private const val HINT = 0xFF8FA9CC.toInt()
        private const val BRAND = 0xFFBFE4FF.toInt()

        /** A row at rest, and the row the player is on. */
        private const val ROW = 0xFFD2D6DC.toInt()
        private const val SELECTED = 0xFF1B8DFF.toInt()
        private const val INK = 0xFF172433.toInt()

        /** The same row in Dark Mode: the launcher's dark card over the panel. */
        private const val DARK_ROW = 0xFF1A253C.toInt()
        private const val DARK_INK = 0xFFEAF1FF.toInt()
    }
}

/**
 * The screen behind the panel: the reference menu's low-poly field, dark navy
 * shards with a thin pale wire over them, instead of the paused match showing
 * through. Deterministic, so the same triangles are in the same places on
 * every opening, and drawn from a handful of flat fills so it costs one pass
 * over the screen behind a paused game.
 */
private class Backdrop(context: Context) : View(context) {

    private class Facet(val points: FloatArray, val color: Int)

    private val density = resources.displayMetrics.density
    private val base = Paint().apply { color = 0xF2070B12.toInt() }
    private val fill = Paint().apply { isAntiAlias = true }
    private val wire = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = maxOf(1f, density)
        color = 0x1F8FC4F0
    }
    private val path = Path()
    private val facets: List<Facet> = buildFacets()

    private fun buildFacets(): List<Facet> {
        // A fixed seed: the backdrop is part of the design, not a surprise.
        var seed = 2017L
        fun next(): Float {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 33) % 1000L) / 1000f
        }
        val shades = intArrayOf(
            0xFF0C1420.toInt(), 0xFF101B29.toInt(),
            0xFF13202F.toInt(), 0xFF0A1019.toInt(),
        )
        val facets = ArrayList<Facet>(18)
        repeat(18) {
            val centerX = next() * 1.3f - 0.15f
            val centerY = next() * 1.3f - 0.15f
            val radius = 0.2f + next() * 0.35f
            val start = next() * 6.2832f
            val points = FloatArray(6)
            for (corner in 0 until 3) {
                val angle = start + corner * 2.0944f + (next() - 0.5f) * 0.8f
                points[corner * 2] = centerX + cos(angle) * radius
                points[corner * 2 + 1] = centerY + sin(angle) * radius
            }
            facets += Facet(points, shades[(next() * shades.size).toInt().coerceIn(0, shades.size - 1)])
        }
        return facets
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), base)
        for (facet in facets) {
            path.reset()
            path.moveTo(facet.points[0] * width, facet.points[1] * height)
            path.lineTo(facet.points[2] * width, facet.points[3] * height)
            path.lineTo(facet.points[4] * width, facet.points[5] * height)
            path.close()
            fill.color = facet.color
            canvas.drawPath(path, fill)
            canvas.drawPath(path, wire)
        }
    }
}

/**
 * A ScrollView that stops growing at [maxHeight] and scrolls instead.
 *
 * The page is as tall as its rows, so a short page stays a short panel; but a
 * page longer than the screen must not run off it, and the rows must end on a
 * whole row. Without a ceiling the panel was given the whole screen and the
 * last row was cut in half at the bottom edge, which reads as clipped text.
 */
private class CappedScrollView(context: Context) : ScrollView(context) {

    var maxHeight: Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (maxHeight <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val capped = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, capped)
    }
}
