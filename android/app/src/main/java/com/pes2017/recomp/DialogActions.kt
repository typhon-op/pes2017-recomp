package com.pes2017.recomp

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

/**
 * One action under a dialog's content.
 *
 * [filled] is the dialog's main answer: the navy box with the red edge, as the
 * PES menus draw the chosen entry. The rest are blue text actions under it, in
 * the same column, right-aligned.
 */
class DialogAction(
    val label: CharSequence,
    val filled: Boolean = false,
    /** True for an action that leaves the dialog standing (a field to fill again). */
    val keepOpen: Boolean = false,
    val onClick: () -> Unit = {},
)

/**
 * The dialogs' actions: a stacked column of buttons inside the dialog's own
 * white surface, instead of AppCompat's stock button bar.
 *
 * The launcher's theme gives its own buttons the navy box (`PanelButton`), but
 * a dialog's actions are drawn by the alert's button bar, which the theme does
 * not style: they came out as borderless accent text laid out in a row, and a
 * white label on that bar is the one combination that is unreadable. So the
 * dialogs put their own actions in the content view, where the surfaces they
 * sit on are the app's own colours, and leave the stock bar empty.
 */
object DialogActions {

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** The column itself, right-aligned, filled box first. Wired by [show]. */
    fun column(context: Context, actions: List<DialogAction>): LinearLayout {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        val outlined = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outlined, true)
        for (action in actions) {
            val button = Button(context).apply {
                text = action.label
                isAllCaps = true
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)
                letterSpacing = 0.04f
                minWidth = 0
                stateListAnimator = null
                if (action.filled) {
                    setBackgroundResource(R.drawable.bg_button_action)
                    setTextColor(ContextCompat.getColor(context, R.color.pes_on_dark))
                    textSize = 14f
                    minHeight = dp(context, 40)
                    setPadding(dp(context, 18), dp(context, 8), dp(context, 18), dp(context, 8))
                } else {
                    setBackgroundResource(outlined.resourceId)
                    // The theme's info ink: blue on the light dialog, the pale
                    // blue on the dark one, so a text action never sinks into
                    // the surface behind it.
                    setTextColor(WrapperTheme.color(context, R.attr.pesInfoText, R.color.pes_blue))
                    textSize = 13f
                    minHeight = dp(context, 34)
                    setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4))
                }
            }
            column.addView(
                button,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(context, 6) },
            )
        }
        return column
    }

    /**
     * Builds and shows a dialog over [content], with [actions] stacked under
     * it. An action dismisses the dialog first unless it asked to stay open,
     * which is the same order the stock buttons use: close, then act.
     */
    fun show(
        context: Context,
        title: CharSequence,
        content: View?,
        actions: List<DialogAction>,
    ): AlertDialog {
        val pad = dp(context, 20)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, dp(context, 10))
        }
        if (content != null) {
            root.addView(
                content,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val column = column(context, actions)
        root.addView(
            column,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 12) },
        )
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(root)
            .create()
        for (index in actions.indices) {
            val action = actions[index]
            (column.getChildAt(index) as Button).setOnClickListener {
                if (!action.keepOpen) dialog.dismiss()
                action.onClick()
            }
        }
        dialog.show()
        return dialog
    }
}
