package com.pes2017.recomp

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The wrapper's look, in one place.
 *
 * Every screen the app itself draws -- the launcher, Settings and the pages
 * opened from them -- asks for its theme here before it inflates a layout, so
 * Light Mode and Dark Mode differ by the values of one theme rather than by two
 * copies of every layout. The layouts and styles name the attributes in
 * `attrs.xml` (`?attr/pesPage`, `?attr/pesText`, ...), and [R.style.Theme_PES17]
 * or [R.style.Theme_PES17_Dark] answers with the palette the player chose.
 *
 * The choice lives in [Prefs.darkMode], so it survives a restart and is applied
 * to the next screen opened as well as to the launcher as soon as it is toggled
 * (MainActivity saves it and recreates itself).
 *
 * Deliberately not used by GameActivity, PesMenu or the pad editor: those are
 * the match's own dark surfaces and are the same in both modes.
 */
object WrapperTheme {

    /** The colour a screen's own pages use, which the system bars match. */
    fun page(activity: Activity): Int = ContextCompat.getColor(
        activity,
        if (Prefs(activity).darkMode) R.color.pes_page_dark else R.color.pes_page,
    )

    /**
     * Put [activity] into the chosen mode. Call it after `super.onCreate` and
     * before `setContentView`, so every view, drawable and dialog expanded by
     * this activity resolves the right palette.
     */
    fun apply(activity: AppCompatActivity) {
        val dark = Prefs(activity).darkMode
        activity.setTheme(if (dark) R.style.Theme_PES17_Dark else R.style.Theme_PES17)

        val window = activity.window ?: return
        val page = page(activity)
        // The bars around the page follow it, and their icons flip with it:
        // dark icons on the white page, light icons on the navy one.
        window.statusBarColor = page
        window.navigationBarColor = page
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    /**
     * The launcher artwork is drawn on a white sheet. On the dark page that
     * sheet would be a bright rectangle, so in Dark Mode it is faded to a
     * ghost of itself; Light Mode keeps the alpha the layout shipped with.
     * A screen without the artwork is left alone.
     */
    fun dimArtwork(activity: Activity) {
        if (!Prefs(activity).darkMode) return
        activity.findViewById<View>(R.id.hero_art)?.alpha = 0.16f
    }

    /**
     * A colour from the current theme, for the few views built in code
     * (DialogActions' text actions, the patch screens' summary lines). Falls
     * back to the light palette's plain colour if an attribute is missing, so
     * a screen can never end up with an invisible label.
     */
    fun color(context: Context, attr: Int, fallback: Int): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attr, value, true)) {
            return ContextCompat.getColor(context, fallback)
        }
        return when {
            value.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
                value.type <= TypedValue.TYPE_LAST_COLOR_INT -> value.data
            value.resourceId != 0 -> ContextCompat.getColor(context, value.resourceId)
            else -> ContextCompat.getColor(context, fallback)
        }
    }
}
