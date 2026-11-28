package com.pes2017.recomp

import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.roundToInt

/**
 * The Left stick preview, on a page of its own.
 *
 * It used to be a 220 dp view inside the settings page's ScrollView, which made
 * it nearly impossible to use: a thumb pushing the stick scrolled the page
 * instead. Here the page does not scroll at all -- there is nothing to scroll:
 * the stick is centred in the window and the Back button is fixed under it --
 * so every direction and the way back to centre can be felt.
 *
 * The settings themselves are read when the page opens and again on every
 * resume -- they are not editable here (the settings page and Edit Controls own
 * that), and nothing on this page reaches the game's input.
 *
 * The stick is drawn at the **layout's own size**: [applyStickSize] reads the
 * `stick` element from the same `controls.json` the match reads, fits it with
 * the same [ScreenFit] rule and the same density, and hands the radius to the
 * view. There is no page-specific scale, so a stick resized in Edit Controls is
 * this size on the pitch and this size here.
 */
class LeftStickPreviewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WrapperTheme.apply(this)
        setContentView(R.layout.activity_left_stick_preview)
        WrapperTheme.dimArtwork(this)
        findViewById<android.view.View>(R.id.back_button)
            .setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        applyStickSize()
        applyProfile()
        findViewById<android.view.View>(R.id.left_stick_preview_back)
            .setOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    /**
     * The pad's own zoom, read again after Edit Controls may have changed the
     * stick's size: the socket is the layout's `stick`, fitted exactly as
     * [TouchOverlayView] fits it.
     */
    override fun onResume() {
        super.onResume()
        applyStickSize()
        applyProfile()
    }

    private fun applyStickSize() {
        val preview = findViewById<LeftStickPreviewView>(R.id.left_stick_preview)
        val sizeDp = StickPreview.sizeDp(ControlLayout.load(this)) ?: return
        val metrics = resources.displayMetrics
        val density = metrics.density
        val fit = ScreenFit(
            metrics.widthPixels / density,
            metrics.heightPixels / density,
            safeArea(),
        )
        preview.stickRadiusPx = StickPreview.radiusPx(sizeDp, fit, density)
    }

    private fun applyProfile() {
        val profile = LeftStickFeel.sanitize(Prefs(this).leftStickFeel())
        findViewById<LeftStickPreviewView>(R.id.left_stick_preview).settings = profile
        val values = getString(
            R.string.left_stick_preview_profile,
            label(profile.preset),
            (profile.deadzone * 100f).roundToInt(),
            (profile.sensitivity * 100f).roundToInt(),
            curve(profile.curve),
            (profile.sprint * 100f).roundToInt(),
        )
        val sizeDp = StickPreview.sizeDp(ControlLayout.load(this)) ?: 0f
        findViewById<TextView>(R.id.left_stick_preview_profile).text =
            values + "\n" + getString(R.string.left_stick_preview_size, sizeDp.roundToInt())
    }

    /** The window's unusable edges in dp, as the pad reads them. */
    private fun safeArea(): SafeAreaDp {
        val density = resources.displayMetrics.density
        val root = window.decorView.rootWindowInsets ?: return SafeAreaDp.NONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = root.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return StickPreview.safeArea(bars.left, bars.top, bars.right, bars.bottom, density)
        }
        @Suppress("DEPRECATION")
        return StickPreview.safeArea(
            root.systemWindowInsetLeft, root.systemWindowInsetTop,
            root.systemWindowInsetRight, root.systemWindowInsetBottom, density,
        )
    }

    private fun label(preset: String): String = getString(
        when (preset) {
            LeftStickFeel.PRESET_BALANCED -> R.string.preset_balanced
            LeftStickFeel.PRESET_TIGHT -> R.string.preset_tight
            LeftStickFeel.PRESET_QUICK -> R.string.preset_quick
            LeftStickFeel.PRESET_CUSTOM -> R.string.preset_custom
            else -> R.string.preset_default
        },
    )

    private fun curve(curve: String): String = getString(
        when (curve) {
            LeftStickFeel.CURVE_SOFT -> R.string.curve_soft
            LeftStickFeel.CURVE_SNAPPY -> R.string.curve_snappy
            else -> R.string.curve_linear
        },
    )
}
