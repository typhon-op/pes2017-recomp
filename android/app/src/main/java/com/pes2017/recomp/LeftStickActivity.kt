package com.pes2017.recomp

import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.roundToInt

/**
 * Left stick customizations: the four presets, a live preview and the five
 * numbers behind them.
 *
 * Every change is written as it is made, like Settings: the match reads the
 * profile on its next frame, so there is nothing to apply and nothing to lose
 * if the screen is closed. "Default" is the stick this build shipped with --
 * the shipped deadzone, curve and sprint latch -- and Reset always returns to
 * it, never to Balanced.
 *
 * A wrapper screen: the theme is [WrapperTheme], so Light and Dark Mode are the
 * same layout with different values. It does not touch the pad's coordinates or
 * its touch handling; it only writes the numbers the pad reads.
 */
class LeftStickActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var preview: LeftStickPreviewView
    private lateinit var profile: TextView
    private lateinit var presets: RadioGroup
    private lateinit var deadzone: SeekBar
    private lateinit var sensitivity: SeekBar
    private lateinit var sprint: SeekBar
    private lateinit var curveGroup: RadioGroup
    private lateinit var smoothingGroup: RadioGroup

    /** True while [show] is writing the widgets, so their listeners stay quiet. */
    private var applying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WrapperTheme.apply(this)
        setContentView(R.layout.activity_left_stick)
        WrapperTheme.dimArtwork(this)
        findViewById<android.view.View>(R.id.back_button)
            .setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        prefs = Prefs(this)
        title = getString(R.string.left_stick_menu)

        preview = findViewById(R.id.left_stick_preview)
        profile = findViewById(R.id.left_stick_profile)
        presets = findViewById(R.id.left_stick_presets)
        deadzone = findViewById(R.id.left_stick_deadzone)
        sensitivity = findViewById(R.id.left_stick_sensitivity)
        sprint = findViewById(R.id.left_stick_sprint)
        curveGroup = findViewById(R.id.left_stick_curve_group)
        smoothingGroup = findViewById(R.id.left_stick_smoothing_group)

        // The bars keep no state of their own across a recreate: the prefs are
        // the only source of truth, as on the Settings page.
        listOf(deadzone, sensitivity, sprint).forEach { it.isSaveEnabled = false }
        deadzone.max = 25                                  // 0-25%
        sensitivity.max = 14                               // 70-140% in 5s
        sprint.max = 25                                    // 70-95%

        labelPresets()

        presets.setOnCheckedChangeListener { _, id ->
            if (applying || id == -1) return@setOnCheckedChangeListener
            pick(LeftStickFeel.preset(presetName(id)))
        }
        deadzone.setOnSeekBarChangeListener(object : Simple() {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!applying && fromUser) pick(edited())
            }
        })
        sensitivity.setOnSeekBarChangeListener(object : Simple() {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!applying && fromUser) pick(edited())
            }
        })
        sprint.setOnSeekBarChangeListener(object : Simple() {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!applying && fromUser) pick(edited())
            }
        })
        curveGroup.setOnCheckedChangeListener { _, id ->
            if (!applying && id != -1) pick(edited())
        }
        smoothingGroup.setOnCheckedChangeListener { _, id ->
            if (!applying && id != -1) pick(edited())
        }
        findViewById<android.view.View>(R.id.left_stick_reset)
            .setOnClickListener { pick(LeftStickFeel.preset(LeftStickFeel.PRESET_DEFAULT)) }

        show(prefs.leftStickFeel())
    }

    /** Writes a profile and shows it, without a listener treating it as a hand edit. */
    private fun pick(settings: LeftStickFeel.Settings) {
        val clean = LeftStickFeel.sanitize(settings)
        prefs.setLeftStickFeel(clean)
        show(clean)
    }

    /** What the widgets currently say. A hand edit is always a Custom profile. */
    private fun edited(): LeftStickFeel.Settings = LeftStickFeel.Settings(
        preset = LeftStickFeel.PRESET_CUSTOM,
        deadzone = deadzone.progress / 100f,
        sensitivity = (70 + sensitivity.progress * 5) / 100f,
        curve = curveName(curveGroup.checkedRadioButtonId),
        smoothing = smoothingName(smoothingGroup.checkedRadioButtonId),
        sprint = (70 + sprint.progress) / 100f,
    )

    private fun show(settings: LeftStickFeel.Settings) {
        val s = LeftStickFeel.sanitize(settings)
        applying = true
        profile.text = getString(R.string.left_stick_profile, presetLabel(s.preset))
        val presetId = presetId(s.preset)
        if (presetId == 0) presets.clearCheck() else presets.check(presetId)
        deadzone.progress = (s.deadzone * 100f).roundToInt()
        sensitivity.progress = (((s.sensitivity * 100f).roundToInt() - 70) / 5).coerceIn(0, 14)
        sprint.progress = ((s.sprint * 100f).roundToInt() - 70).coerceIn(0, 25)
        curveGroup.check(curveId(s.curve))
        smoothingGroup.check(smoothingId(s.smoothing))
        applying = false
        labels(s)
        preview.settings = s
    }

    private fun labels(s: LeftStickFeel.Settings) {
        (findViewById<TextView>(R.id.left_stick_deadzone_label)).text =
            getString(R.string.left_stick_deadzone, (s.deadzone * 100f).roundToInt())
        (findViewById<TextView>(R.id.left_stick_sensitivity_label)).text =
            getString(R.string.left_stick_sensitivity, (s.sensitivity * 100f).roundToInt())
        (findViewById<TextView>(R.id.left_stick_sprint_label)).text =
            getString(R.string.left_stick_sprint, (s.sprint * 100f).roundToInt())
    }

    private fun labelPresets() {
        for ((id, name) in listOf(
            R.id.preset_default to LeftStickFeel.PRESET_DEFAULT,
            R.id.preset_balanced to LeftStickFeel.PRESET_BALANCED,
            R.id.preset_tight to LeftStickFeel.PRESET_TIGHT,
            R.id.preset_quick to LeftStickFeel.PRESET_QUICK,
        )) {
            val s = LeftStickFeel.preset(name)
            val values = getString(
                R.string.left_stick_values,
                (s.deadzone * 100f).roundToInt(),
                (s.sensitivity * 100f).roundToInt(),
                curveLabel(s.curve),
                smoothingLabel(s.smoothing),
                (s.sprint * 100f).roundToInt(),
            )
            findViewById<RadioButton>(id).text = getString(
                R.string.left_stick_preset_row, presetLabel(name), values,
            )
        }
    }

    private fun presetLabel(name: String): String = when (name) {
        LeftStickFeel.PRESET_BALANCED -> getString(R.string.preset_balanced)
        LeftStickFeel.PRESET_TIGHT -> getString(R.string.preset_tight)
        LeftStickFeel.PRESET_QUICK -> getString(R.string.preset_quick)
        LeftStickFeel.PRESET_CUSTOM -> getString(R.string.preset_custom)
        else -> getString(R.string.preset_default)
    }

    private fun curveLabel(curve: String): String = when (curve) {
        LeftStickFeel.CURVE_SOFT -> getString(R.string.curve_soft)
        LeftStickFeel.CURVE_SNAPPY -> getString(R.string.curve_snappy)
        else -> getString(R.string.curve_linear)
    }

    private fun smoothingLabel(smoothing: String): String = when (smoothing) {
        LeftStickFeel.SMOOTHING_LOW -> getString(R.string.smoothing_low)
        LeftStickFeel.SMOOTHING_MEDIUM -> getString(R.string.smoothing_medium)
        else -> getString(R.string.smoothing_off)
    }

    private fun presetName(id: Int): String = when (id) {
        R.id.preset_balanced -> LeftStickFeel.PRESET_BALANCED
        R.id.preset_tight -> LeftStickFeel.PRESET_TIGHT
        R.id.preset_quick -> LeftStickFeel.PRESET_QUICK
        else -> LeftStickFeel.PRESET_DEFAULT
    }

    private fun presetId(name: String): Int = when (name) {
        LeftStickFeel.PRESET_BALANCED -> R.id.preset_balanced
        LeftStickFeel.PRESET_TIGHT -> R.id.preset_tight
        LeftStickFeel.PRESET_QUICK -> R.id.preset_quick
        LeftStickFeel.PRESET_DEFAULT -> R.id.preset_default
        else -> 0
    }

    private fun curveName(id: Int): String = when (id) {
        R.id.curve_soft -> LeftStickFeel.CURVE_SOFT
        R.id.curve_snappy -> LeftStickFeel.CURVE_SNAPPY
        else -> LeftStickFeel.CURVE_LINEAR
    }

    private fun curveId(curve: String): Int = when (curve) {
        LeftStickFeel.CURVE_SOFT -> R.id.curve_soft
        LeftStickFeel.CURVE_SNAPPY -> R.id.curve_snappy
        else -> R.id.curve_linear
    }

    private fun smoothingName(id: Int): String = when (id) {
        R.id.smoothing_low -> LeftStickFeel.SMOOTHING_LOW
        R.id.smoothing_medium -> LeftStickFeel.SMOOTHING_MEDIUM
        else -> LeftStickFeel.SMOOTHING_OFF
    }

    private fun smoothingId(smoothing: String): Int = when (smoothing) {
        LeftStickFeel.SMOOTHING_LOW -> R.id.smoothing_low
        LeftStickFeel.SMOOTHING_MEDIUM -> R.id.smoothing_medium
        else -> R.id.smoothing_off
    }

    /** The two methods nobody needs, as on the Settings page. */
    private abstract class Simple : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(bar: SeekBar) = Unit
        override fun onStopTrackingTouch(bar: SeekBar) = Unit
    }
}
