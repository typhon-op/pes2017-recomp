package com.pes2017.recomp

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import kotlin.math.roundToInt

/**
 * The pad editor's own strip. Everything on it acts on whichever control is
 * selected; dragging and pinching happen on the control itself. Shared by the
 * match (pause > Edit controls) and Settings > Controls > Arrange, so both edit
 * the same controls.json the same way.
 *
 * The strip sits at the bottom, and moves to the top while a control in the
 * lower half is selected, so it never covers the control being edited.
 */
class ControlEditPanel(
    private val activity: Activity,
    private val root: FrameLayout,
    private val overlay: TouchOverlayView,
    private val onDone: () -> Unit,
) {
    private val view: View = activity.layoutInflater.inflate(R.layout.edit_panel, root, false)
    private val title: TextView = view.findViewById(R.id.edit_title)
    private val size: SeekBar = view.findViewById(R.id.edit_size)
    private val opacity: SeekBar = view.findViewById(R.id.edit_opacity)
    private val visible: SwitchCompat = view.findViewById(R.id.edit_visible)
    private val toggle: SwitchCompat = view.findViewById(R.id.edit_toggle)
    private val snap: SwitchCompat = view.findViewById(R.id.edit_snap)
    /** What is left of the editor while nothing is selected: a hint and the way out. */
    private val idle: View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(0xB3101418.toInt())
        val dp = activity.resources.displayMetrics.density
        setPadding((12 * dp).roundToInt(), (4 * dp).roundToInt(), (4 * dp).roundToInt(), (4 * dp).roundToInt())
        addView(TextView(activity).apply {
            setText(R.string.edit_pick_one)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
        })
    }
    private val resetOne: Button = view.findViewById(R.id.edit_reset_one)

    /** Reset this / Reset all / Done: top right, on screen for the whole edit. */
    private val actions: LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        val dp = activity.resources.displayMetrics.density
        setPadding((4 * dp).roundToInt(), (4 * dp).roundToInt(), (4 * dp).roundToInt(), (4 * dp).roundToInt())
        for (id in intArrayOf(R.id.edit_reset_one, R.id.edit_reset_all, R.id.edit_done)) {
            val button = view.findViewById<Button>(id)
            (button.parent as ViewGroup).removeView(button)
            button.textSize = 12f
            button.minWidth = 0
            button.minimumWidth = 0
            addView(button, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = (6 * dp).roundToInt() })
        }
    }
    /** Shown only while a swipe control is selected: what each direction sends. */
    private val swipes: Button = Button(activity).apply {
        setText(R.string.swipe_bindings)
        textSize = 12f
        minWidth = 0
        minimumWidth = 0
        visibility = View.GONE
        setOnClickListener { showSwipeBindings() }
    }

    private var current: ControlElement? = null
    private var attached = false
    private var atTop = false

    init {
        val dp = activity.resources.displayMetrics.density
        actions.addView(swipes, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = (6 * dp).roundToInt() })
        size.max = 100
        opacity.max = 100
        size.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                val element = current ?: return
                val smallest = if (element.kind == ControlKind.LOOK) 120f else 36f
                val largest = if (element.kind == ControlKind.LOOK) 900f else 200f
                element.sizeDp = smallest + (largest - smallest) * (value / 100f)
                overlay.refresh()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) {
                // Grown into a neighbour: step it aside.
                current?.let { overlay.keepApart(it) }
                save()
            }
        })
        opacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                val element = current ?: return
                element.alpha = (value / 100f).coerceIn(0.05f, 1f)
                overlay.refresh()
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = save()
        })
        visible.setOnCheckedChangeListener { _, checked ->
            val element = current ?: return@setOnCheckedChangeListener
            if (element.visible == checked) return@setOnCheckedChangeListener
            element.visible = checked
            if (checked) overlay.keepApart(element)
            overlay.refresh()
            save()
        }
        toggle.setOnCheckedChangeListener { _, checked ->
            val element = current ?: return@setOnCheckedChangeListener
            element.toggle = checked
            save()
        }
        snap.setOnCheckedChangeListener { _, checked -> overlay.snapToGrid = checked }
        actions.findViewById<Button>(R.id.edit_reset_one).setOnClickListener {
            val element = current ?: return@setOnClickListener
            overlay.layout.reset(element.id)
            val fresh = overlay.layout.find(element.id)
            if (fresh != null) overlay.keepApart(fresh)
            overlay.select(fresh)
            show(fresh)
            overlay.refresh()
            save()
        }
        actions.findViewById<Button>(R.id.edit_reset_all).setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle(R.string.reset_all)
                .setMessage(R.string.reset_all_explained)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.reset) { _, _ ->
                    overlay.layout.resetAll()
                    overlay.select(null)
                    overlay.refresh()
                    save()
                }
                .show()
        }
        actions.findViewById<Button>(R.id.edit_done).setOnClickListener { onDone() }
    }

    private fun save() = overlay.layout.save(activity)

    // ---- what a swipe sends -------------------------------------------------

    private fun showSwipeBindings() {
        val element = current ?: return
        val directions = intArrayOf(R.string.swipe_up, R.string.swipe_down,
            R.string.swipe_left, R.string.swipe_right)
        val labels = Array(directions.size) { index ->
            activity.getString(directions[index]) + ": " + bitName(swipeBinding(element, index))
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.swipe_bindings)
            .setItems(labels) { _, which -> pickSwipeBit(element, which) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickSwipeBit(element: ControlElement, direction: Int) {
        val options = intArrayOf(0, NativeBridge.A, NativeBridge.B, NativeBridge.X, NativeBridge.Y,
            NativeBridge.LB, NativeBridge.RB, NativeBridge.START, NativeBridge.BACK,
            NativeBridge.L3, NativeBridge.R3, NativeBridge.UP, NativeBridge.DOWN,
            NativeBridge.LEFT, NativeBridge.RIGHT)
        val labels = Array(options.size) { bitName(options[it]) }
        AlertDialog.Builder(activity)
            .setTitle(R.string.swipe_direction)
            .setItems(labels) { _, which ->
                setSwipeBinding(element, direction, options[which])
                // Shown straight away: an unbound swipe stays hidden, and a
                // player who has just bound one expects to see it.
                if (options[which] != 0 && !element.visible) element.visible = true
                overlay.refresh()
                show(element)
                save()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 0 up, 1 down, 2 left, 3 right: the order the dialog lists them in. */
    private fun swipeBinding(element: ControlElement, direction: Int): Int = when (direction) {
        0 -> element.swipeUp
        1 -> element.swipeDown
        2 -> element.swipeLeft
        else -> element.swipeRight
    }

    private fun setSwipeBinding(element: ControlElement, direction: Int, bit: Int) {
        when (direction) {
            0 -> element.swipeUp = bit
            1 -> element.swipeDown = bit
            2 -> element.swipeLeft = bit
            else -> element.swipeRight = bit
        }
    }

    /** The name printed for a pad bit, as the console's own face names mark them. */
    private fun bitName(bit: Int): String = when (bit) {
        0 -> activity.getString(R.string.button_none)
        NativeBridge.A -> "A"
        NativeBridge.B -> "B"
        NativeBridge.X -> "X"
        NativeBridge.Y -> "Y"
        NativeBridge.LB -> "LB"
        NativeBridge.RB -> "RB"
        NativeBridge.START -> "START"
        NativeBridge.BACK -> "BACK"
        NativeBridge.L3 -> "L3"
        NativeBridge.R3 -> "R3"
        NativeBridge.UP -> "D-PAD UP"
        NativeBridge.DOWN -> "D-PAD DOWN"
        NativeBridge.LEFT -> "D-PAD LEFT"
        else -> "D-PAD RIGHT"
    }

    fun attach() {
        if (attached) return
        root.addView(view, params(top = false))
        root.addView(
            actions,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ),
        )
        root.addView(
            idle,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ),
        )
        atTop = false
        attached = true
        show(null)
    }

    fun detach() {
        if (!attached) return
        root.removeView(view)
        root.removeView(idle)
        root.removeView(actions)
        attached = false
    }

    private fun params(top: Boolean) = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        if (top) Gravity.TOP else Gravity.BOTTOM,
    )

    fun show(element: ControlElement?) {
        current = element
        // Only a selected control has anything to adjust; until then the strip stays out of the way.
        view.visibility = if (element == null) View.GONE else View.VISIBLE
        idle.visibility = if (element == null) View.VISIBLE else View.GONE
        resetOne.isEnabled = element != null
        if (element == null) swipes.visibility = View.GONE
        val wantTop = element != null && element.yFrac > 0.55f
        if (attached && wantTop != atTop) {
            view.layoutParams = params(wantTop)
            atTop = wantTop
        }
        if (element == null) {
            title.setText(R.string.edit_pick_one)
            size.isEnabled = false
            opacity.isEnabled = false
            visible.isEnabled = false
            toggle.isEnabled = false
            return
        }
        title.text = activity.getString(R.string.edit_selected, ControlLayout.labelFor(element, overlay.playStation))
        swipes.visibility = if (element.kind == ControlKind.SWIPE) View.VISIBLE else View.GONE
        size.isEnabled = true
        opacity.isEnabled = true
        visible.isEnabled = true
        toggle.isEnabled = element.kind == ControlKind.BUTTON || element.kind == ControlKind.TRIGGER
        val smallest = if (element.kind == ControlKind.LOOK) 120f else 36f
        val largest = if (element.kind == ControlKind.LOOK) 900f else 200f
        size.progress = (((element.sizeDp - smallest) / (largest - smallest)) * 100f)
            .roundToInt().coerceIn(0, 100)
        opacity.progress = (element.alpha * 100f).roundToInt().coerceIn(0, 100)
        visible.isChecked = element.visible
        toggle.isChecked = element.toggle
    }
}
