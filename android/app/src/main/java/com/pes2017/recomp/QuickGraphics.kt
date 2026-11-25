package com.pes2017.recomp

import android.app.Activity
import android.content.Intent

/**
 * The graphics and performance settings that matter most, reachable from the
 * pause menu. The renderer reads them once as a run starts, so changing them
 * means a new run: "Apply & restart" hands the values to the launcher (the one
 * process that writes the settings) and starts the match again, with no trip
 * through the launcher's screens.
 *
 * Presented on the game's own menu ([PesMenu]) rather than an Android dialog:
 * it is the pause menu's Graphics & performance page and has to look like one.
 */
object QuickGraphics {

    const val EXTRA_RELAUNCH = "pes17.relaunch"
    private const val X_SCENE = "gfx_scene"
    private const val X_SCALE = "gfx_scale"
    private const val X_MSAA = "gfx_msaa"
    private const val X_FSR = "gfx_fsr"
    private const val X_FPS = "gfx_fps"
    private const val X_HEAT = "gfx_heat"
    private const val X_LOADSCALE = "gfx_loadscale"
    private const val X_CROWD = "gfx_crowd"
    private const val X_LOWMEM = "gfx_lowmem"
    private const val X_ANISO = "gfx_aniso"
    private const val X_REFRESH = "gfx_refresh"
    private const val X_PRESET = "gfx_preset"
    private const val X_PACING = "gfx_pacing"
    private const val X_PACING_REFRESH = "gfx_pacing_refresh"

    private class Values(
        var scene: Int, var scale: Int, var msaa: Boolean, var fsr: Boolean, var fps: Int,
        var heat: Boolean, var loadScale: Boolean, var crowd: Boolean, var lowMemory: Boolean,
        var aniso: Int, var refresh: Boolean, var preset: String,
        var pacing: String, var pacingRefresh: String,
    )

    /**
     * One visit to the screen. The values live here, not in the screen it
     * builds, so re-rendering a row after an adjustment keeps every other
     * change the player already made.
     */
    class Session internal constructor(
        private val activity: Activity,
        private val prefs: Prefs,
        /** Runs after the relaunch has been requested: the old run ends here. */
        private val onApply: () -> Unit,
    ) {

        private val v = Values(
            prefs.sceneScalePercent, prefs.renderScale, prefs.multisampling, prefs.fsr, prefs.fpsLimit,
            prefs.heatScale, prefs.loadScale, prefs.crowd, prefs.lowMemory,
            prefs.maxAniso, prefs.matchRefresh, prefs.preset,
            prefs.framePacing, prefs.framePacingRefresh,
        )
        private val tiers = DeviceProfile.Tier.values()

        /** Chosen tier when the values still match it, for the checked dot. */
        private fun tierName(tier: DeviceProfile.Tier): String = activity.getString(
            when (tier) {
                DeviceProfile.Tier.LOW -> R.string.preset_low
                DeviceProfile.Tier.MEDIUM -> R.string.preset_medium
                DeviceProfile.Tier.HIGH -> R.string.preset_high
            },
        )

        private fun touched() {
            v.preset = DeviceProfile.PRESET_CUSTOM
        }

        private fun setTier(tier: DeviceProfile.Tier) {
            val p = DeviceProfile.preset(tier)
            // The preset sets what DeviceProfile.preset defines. Internal resolution
            // is not one of them, so a preset never resets a multiplier the player
            // chose for this phone.
            v.scene = p.sceneScalePercent; v.msaa = p.multisampling; v.fsr = p.fsr
            v.fps = p.fpsLimit; v.heat = p.heatScale; v.loadScale = p.loadScale
            v.lowMemory = p.lowMemory; v.aniso = p.maxAniso; v.preset = tier.name
        }

        fun screen(menu: PesMenu): PesMenu.Screen {
            val rows = ArrayList<PesMenu.Row>()

            for (tier in tiers) {
                rows += PesMenu.Row(tierName(tier), checked = v.preset == tier.name) { m ->
                    setTier(tier)
                    m.refresh()
                }
            }

            // The label names the setting and the right-hand column carries its
            // value. The label is not a format string: a row handed the raw
            // resource prints "%1$d" at the player, and the row's capitals turn
            // that into "%1$D"; a value inside a long label is also the first
            // thing a narrow panel truncates. The value is formatted here, so the
            // three placeholders read "3x", "75%" and "45 FPS".
            rows += PesMenu.Row(
                activity.getString(R.string.qg_scale),
                detail = activity.getString(R.string.qg_scale_value, v.scale),
                onAdjust = { m, d ->
                    v.scale = GraphicsOptions.step(GraphicsOptions.INTERNAL_SCALE, v.scale, d)
                    touched()
                    m.refresh()
                },
            )
            rows += PesMenu.Row(
                activity.getString(R.string.qg_scene),
                detail = activity.getString(R.string.qg_scene_value, v.scene),
                onAdjust = { m, d ->
                    v.scene = GraphicsOptions.step(GraphicsOptions.SCENE_PERCENT, v.scene, d)
                    touched()
                    m.refresh()
                },
            )
            rows += PesMenu.Row(
                activity.getString(R.string.qg_fps),
                detail = activity.getString(R.string.qg_fps_value, v.fps),
                onAdjust = { m, d ->
                    v.fps = GraphicsOptions.step(GraphicsOptions.FPS_LIMIT, v.fps, d)
                    touched()
                    m.refresh()
                },
            )

            fun toggle(label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
                rows += PesMenu.Row(
                    label,
                    detail = activity.getString(if (get()) R.string.on else R.string.off),
                    onAdjust = { m, _ -> set(!get()); touched(); m.refresh() },
                )
            }
            rows += PesMenu.Row(
                activity.getString(R.string.aniso_label_short),
                detail = activity.getString(R.string.aniso_value, v.aniso),
                onAdjust = { m, d ->
                    v.aniso = GraphicsOptions.step(GraphicsOptions.MAX_ANISO, v.aniso, d)
                    touched()
                    m.refresh()
                },
            )
            toggle(activity.getString(R.string.qg_msaa), { v.msaa }) { v.msaa = it }
            toggle(activity.getString(R.string.qg_fsr), { v.fsr }) { v.fsr = it }
            toggle(activity.getString(R.string.qg_heat), { v.heat }) { v.heat = it }
            // The load rule is what keeps a GPU with no headroom steady; Low turns it on.
            toggle(activity.getString(R.string.load_scale), { v.loadScale }) { v.loadScale = it }
            toggle(activity.getString(R.string.qg_crowd), { v.crowd }) { v.crowd = it }
            toggle(activity.getString(R.string.qg_lowmem), { v.lowMemory }) { v.lowMemory = it }
            toggle(activity.getString(R.string.match_refresh), { v.refresh }) { v.refresh = it }

            // Frame pacing lives on its own keys, shared with SettingsActivity.
            // It is not a preset setting, so these rows do not call touched():
            // moving one must not change the selected preset or the other
            // values. The page's note says they apply as the next match starts.
            rows += PesMenu.Row(
                activity.getString(R.string.frame_pacing_mode),
                detail = activity.getString(FramePacingOptions.modeLabel(v.pacing)),
                onAdjust = { m, d ->
                    v.pacing = FramePacingOptions.stepMode(v.pacing, d)
                    prefs.framePacing = v.pacing
                    m.refresh()
                },
            )
            rows += PesMenu.Row(
                activity.getString(R.string.frame_pacing_refresh),
                detail = activity.getString(FramePacingOptions.refreshLabel(v.pacingRefresh)),
                onAdjust = { m, d ->
                    v.pacingRefresh = FramePacingOptions.stepRefresh(v.pacingRefresh, d)
                    prefs.framePacingRefresh = v.pacingRefresh
                    m.refresh()
                },
            )

            rows += PesMenu.Row(activity.getString(R.string.qg_apply)) { m ->
                m.dismissNow()
                launch()
            }

            return PesMenu.Screen(
                activity.getString(R.string.qg_title),
                message = activity.getString(R.string.qg_note),
                rows = rows,
                hint = activity.getString(R.string.qg_hint),
            )
        }

        private fun launch() {
            val intent = Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_RELAUNCH, true)
                .putExtra(X_SCENE, v.scene).putExtra(X_SCALE, v.scale).putExtra(X_MSAA, v.msaa).putExtra(X_FSR, v.fsr)
                .putExtra(X_FPS, v.fps).putExtra(X_HEAT, v.heat).putExtra(X_LOADSCALE, v.loadScale)
                .putExtra(X_CROWD, v.crowd)
                .putExtra(X_LOWMEM, v.lowMemory).putExtra(X_ANISO, v.aniso)
                .putExtra(X_REFRESH, v.refresh)
                .putExtra(X_PRESET, v.preset)
                .putExtra(X_PACING, v.pacing)
                .putExtra(X_PACING_REFRESH, v.pacingRefresh)
            activity.startActivity(intent)
            onApply()
        }
    }

    /** A fresh visit to the graphics page; the caller opens it as a submenu. */
    fun session(activity: Activity, prefs: Prefs, onApply: () -> Unit): Session =
        Session(activity, prefs, onApply)

    /** In the launcher: store what the match sent. True when this intent asked for a relaunch. */
    fun takeRelaunch(intent: Intent?, prefs: Prefs): Boolean {
        if (intent == null || !intent.getBooleanExtra(EXTRA_RELAUNCH, false)) return false
        intent.removeExtra(EXTRA_RELAUNCH)
        if (intent.hasExtra(X_SCENE)) {
            prefs.sceneScalePercent = intent.getIntExtra(X_SCENE, prefs.sceneScalePercent)
            prefs.renderScale = intent.getIntExtra(X_SCALE, prefs.renderScale)
            prefs.multisampling = intent.getBooleanExtra(X_MSAA, prefs.multisampling)
            prefs.fsr = intent.getBooleanExtra(X_FSR, prefs.fsr)
            prefs.fpsLimit = intent.getIntExtra(X_FPS, prefs.fpsLimit)
            prefs.heatScale = intent.getBooleanExtra(X_HEAT, prefs.heatScale)
            prefs.loadScale = intent.getBooleanExtra(X_LOADSCALE, prefs.loadScale)
            prefs.crowd = intent.getBooleanExtra(X_CROWD, prefs.crowd)
            prefs.lowMemory = intent.getBooleanExtra(X_LOWMEM, prefs.lowMemory)
            prefs.maxAniso = intent.getIntExtra(X_ANISO, prefs.maxAniso)
            prefs.matchRefresh = intent.getBooleanExtra(X_REFRESH, prefs.matchRefresh)
            prefs.preset = intent.getStringExtra(X_PRESET) ?: prefs.preset
            prefs.framePacing = intent.getStringExtra(X_PACING) ?: prefs.framePacing
            prefs.framePacingRefresh = intent.getStringExtra(X_PACING_REFRESH) ?: prefs.framePacingRefresh
        }
        return true
    }
}
