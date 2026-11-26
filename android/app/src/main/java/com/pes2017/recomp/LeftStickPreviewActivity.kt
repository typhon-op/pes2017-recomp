package com.pes2017.recomp

import android.os.Bundle
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
 * The settings themselves are read once when the page opens; they are not
 * editable here (the settings page owns that), and nothing on this page reaches
 * the game's input.
 */
class LeftStickPreviewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WrapperTheme.apply(this)
        setContentView(R.layout.activity_left_stick_preview)
        WrapperTheme.dimArtwork(this)
        findViewById<android.view.View>(R.id.back_button)
            .setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val prefs = Prefs(this)
        val profile = LeftStickFeel.sanitize(prefs.leftStickFeel())
        val preview = findViewById<LeftStickPreviewView>(R.id.left_stick_preview)
        preview.settings = profile

        val values = getString(
            R.string.left_stick_preview_profile,
            label(profile.preset),
            (profile.deadzone * 100f).roundToInt(),
            (profile.sensitivity * 100f).roundToInt(),
            curve(profile.curve),
            (profile.sprint * 100f).roundToInt(),
        )
        findViewById<TextView>(R.id.left_stick_preview_profile).text = values
        findViewById<android.view.View>(R.id.left_stick_preview_back)
            .setOnClickListener { onBackPressedDispatcher.onBackPressed() }
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
