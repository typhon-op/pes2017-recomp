package com.pes2017.recomp

import android.content.Context
import android.util.Log

/**
 * Everything the app says to the runtime and everything the runtime says
 * back. The other side is runtime/android/jni.cpp; the names here and the
 * names there are the same word for word, so changing one means changing
 * both.
 */
object NativeBridge {

    /** What the runtime calls on its own threads, never on the main one. */
    @Volatile
    var listener: NativeListener? = null

    private var loaded = false
    private var loadError: String? = null

    /**
     * Loads libpes17.so. Called once, late, and allowed to fail: a device whose
     * Vulkan is too old, or an apk built for another ABI, still reaches the
     * launcher, which then says what is wrong instead of disappearing.
     */
    @Synchronized
    fun load(context: Context? = null): Boolean {
        if (loaded) return true
        // The library is built for ARMv8.2-A (inline LSE atomics for the guest's
        // lwarx/stwcx., fp16, dot product). On an older core the first such
        // instruction is SIGILL inside loadLibrary, which no catch below sees, so
        // the CPU is checked first and the launcher says why.
        missingCpuFeature()?.let { feature ->
            loadError = context?.getString(R.string.cpu_missing_feature, feature)
                ?: "This phone's CPU lacks $feature (ARMv8.2-A is required)"
            return false
        }
        return try {
            System.loadLibrary("pes17")
            loaded = true
            true
        } catch (error: Throwable) {
            loadError = error.message ?: error.toString()
            false
        }
    }

    /** The first ARMv8.2 feature /proc/cpuinfo does not list, or null. */
    private fun missingCpuFeature(): String? = try {
        val features = java.io.File("/proc/cpuinfo").readLines()
            .firstOrNull { it.startsWith("Features") }?.substringAfter(':')?.trim()?.split(' ')?.toSet()
        if (features == null) null            // unreadable: let the loader try
        else listOf("atomics" to "LSE atomics", "asimddp" to "dot product", "fphp" to "half-precision FP")
            .firstOrNull { it.first !in features }?.second
    } catch (_: Exception) { null }

    fun isLoaded(): Boolean = loaded
    fun loadError(): String? = loadError

    @Volatile
    private var initialized = false

    fun isInitialized(): Boolean = initialized

    /**
     * Loads the library and starts the runtime once for this process. The
     * launcher used to do this itself, and the setup screen only worked when
     * the launcher happened to have finished first; now whichever screen needs
     * the runtime asks for it, and the first-run setup does it off the main
     * thread behind its own page.
     */
    @Synchronized
    fun ensureInitialized(context: Context): Boolean {
        if (initialized) return true
        if (!load(context)) return false
        return try {
            val ok = nativeInit(
                context.filesDir.absolutePath,
                (context.getExternalFilesDir(null) ?: context.filesDir).absolutePath,
                context.cacheDir.absolutePath,
                context.applicationInfo.nativeLibraryDir,
                null,
            )
            initialized = ok
            if (!ok) noteInitFailure(context.getString(R.string.runtime_did_not_start))
            ok
        } catch (error: Throwable) {
            noteInitFailure(error.message ?: error.toString())
            false
        }
    }

    /**
     * A library that loaded but would not start. An UnsatisfiedLinkError on
     * the first call means the .so and this file have drifted apart, which
     * the launcher should say rather than die of.
     */
    fun noteInitFailure(reason: String) {
        loadError = reason
    }

    /** The runtime started and can install and play. */
    fun noteInitialized() {
        initialized = true
    }

    // ---- start-up ---------------------------------------------------------

    external fun nativeInit(
        filesDir: String,
        externalDir: String,
        cacheDir: String,
        nativeLibDir: String,
        listener: NativeListener?,
    ): Boolean

    /** One of the runtime's PES17_* switches (docs/switches.md). */
    external fun nativeSetOption(key: String, value: String)

    /** The answer to NativeListener.onKeyboard: what was typed, or a cancel. */
    external fun nativeKeyboardResult(ok: Boolean, text: String)

    external fun nativeStart(gameDir: String): Boolean
    external fun nativeRequestStop()
    external fun nativeIsRunning(): Boolean

    /**
     * The title's phase right now, as [GamePhase]'s index: read from the engine
     * rather than waited for. The listener is only told on a change, so a push
     * sent before the app was listening would otherwise leave it guessing.
     */
    external fun nativeGamePhase(): Int
    external fun nativeStatus(): String
    external fun nativeLogTail(): String

    // ---- the surface ------------------------------------------------------

    external fun nativeSurfaceChanged(surface: Any?, width: Int, height: Int, refreshHz: Float)
    external fun nativeSurfaceDestroyed()
    external fun nativeSetPaused(paused: Boolean)
    external fun nativeTrimMemory(level: Int)

    /** Give back what a trim took away, once the run is in front again. */
    external fun nativeRestoreMemory()
    external fun nativeSetRefreshRate(hz: Float)

    /** PowerManager.THERMAL_STATUS_* (0 none .. 6 shutdown), for the adaptive scene scale. */
    external fun nativeSetThermalStatus(status: Int)

    // ---- input ------------------------------------------------------------

    external fun nativeTouchState(
        buttons: Int,
        leftTrigger: Int,
        rightTrigger: Int,
        leftX: Float,
        leftY: Float,
        rightX: Float,
        rightY: Float,
    )

    /**
     * Buttons that went down since the last state. The title samples the pad
     * once a frame, so a tap that begins and ends between two samples would
     * otherwise not exist; the runtime holds these bits until the next poll can
     * carry them, then forgets them.
     */
    external fun nativeTouchPulse(buttons: Int)

    /**
     * Whether the runtime may let the left stick stand in for the d-pad. The app
     * turns it off while its own d-pad is on screen, so the guest is not given the
     * same four bits from two places at once.
     */
    external fun nativeDpadSynthesis(enabled: Boolean)

    /** A flick of the look area, in pixels; the runtime turns it into a stick. */
    external fun nativeLookDelta(dx: Float, dy: Float)
    external fun nativeLookEnd()

    /**
     * How far a flick has to travel before the look stick reaches its edge.
     * [saturationScale] is a feel multiplier (0.3..3.0): 1.0 is the default,
     * below it turns slower and above it faster. The runtime converts it to
     * pixels a second (runtime/look_scale.h), so no caller passes a pixel
     * figure here.
     */
    external fun nativeLookSettings(
        sensitivityX: Float,
        sensitivityY: Float,
        smoothing: Float,
        invertY: Boolean,
        saturationScale: Float,
    )

    /**
     * The touch sticks' throw: the share of it that sends nothing, and the curve
     * on what is left (below 1 reaches the edge sooner, above 1 is finer around
     * the middle). Radial, so a diagonal stays a diagonal.
     */
    external fun nativeStickSettings(
        leftDeadzone: Float,
        leftCurve: Float,
        rightDeadzone: Float,
        rightCurve: Float,
    )

    /**
     * The skill stick read as a flick: the direction is the thumb's travel from
     * where it landed, quantised to eight, and written to the pad one guest
     * frame at a time (runtime/skill_stick.h, runtime/stick_pulses.h). Off is
     * the older stick that follows the finger's position exactly.
     */
    external fun nativeSkillStick(flick: Boolean)

    /** The travel since the touch-down point, in throws; Y points up. */
    external fun nativeSkillTouch(dx: Float, dy: Float)

    /** The thumb lifted: the direction is let go after its dwell. */
    external fun nativeSkillEnd()

    /**
     * One tap of the skill palette: play the move at [index] on the skill
     * stick, to its last frame (runtime/skill_moves.h). The move owns the stick
     * until then, so nothing the thumb does interrupts it. False when there is
     * no such move.
     */
    external fun nativeSkillPlay(index: Int): Boolean

    /**
     * Whether the runtime this app is packaged with answers an entry point.
     *
     * The Android build ships a libpes17.so built elsewhere, and one built
     * before a call existed does not export it. Calling it is not a feature
     * that quietly does nothing: it is an UnsatisfiedLinkError, and an
     * uncaught one is the app closing on the first screen. So an entry point is
     * asked once, the answer is kept, and whatever it drives is offered only
     * when the answer is yes.
     *
     * Throwable rather than UnsatisfiedLinkError alone, because the same
     * missing symbol surfaces as other linkage errors on other ART versions,
     * and this must never reach the crash screen.
     */
    private inline fun answered(name: String, call: () -> Unit): Boolean =
        try {
            call()
            true
        } catch (missing: Throwable) {
            Log.i(
                "PES17",
                "runtime has no $name (${missing.javaClass.simpleName}): that control stays off",
            )
            false
        }

    /**
     * The skill-stick entry points (runtime/skill_stick.h, runtime/skill_moves.h).
     *
     * The stream is asked with a no-op set, end and an impossible move index, so
     * a runtime that has them is left exactly as it was found.
     */
    val skillStick: Boolean by lazy {
        answered("nativeSkill*") {
            nativeSkillStick(false)
            nativeSkillEnd()
            nativeSkillPlay(-1)
        }
    }

    /**
     * The left stick standing in for the d-pad (runtime/kernel/input.cpp).
     *
     * Absent from a runtime built before the d-pad existed, where a pad of the
     * player's own was the only way to work a menu: nothing is lost, the
     * setting simply has no effect.
     */
    @Volatile
    private var dpadSynthesisState: Boolean? = null

    val dpadSynthesis: Boolean
        get() = dpadSynthesisState ?: probeDpadSynthesis()

    /**
     * Asks once whether the loaded engine exports the entry point. The probe
     * itself is a call because JNI has no cheaper way to ask: a missing symbol
     * throws [UnsatisfiedLinkError], which [answered] catches and remembers as
     * "no". That is the supported state for an engine built before the d-pad.
     */
    @Synchronized
    private fun probeDpadSynthesis(): Boolean {
        dpadSynthesisState?.let { return it }
        val present = answered("nativeDpadSynthesis") { nativeDpadSynthesis(true) }
        dpadSynthesisState = present
        return present
    }

    /**
     * The only way the app asks the engine to stand its stick in for a d-pad.
     *
     * Safe wrapper rather than a bare native call: an engine whose symbol table
     * answers the probe but whose loaded copy cannot link the method (a mixed or
     * repackaged library) would otherwise throw out of a touch event. On a
     * failure the capability is turned off for the rest of the process, so the
     * app keeps its own stick-to-d-pad synthesis and nothing else changes.
     *
     * @return true when the engine took the call; false when the app must do it.
     */
    fun setDpadSynthesis(enabled: Boolean): Boolean {
        if (!dpadSynthesis) return false
        return try {
            nativeDpadSynthesis(enabled)
            true
        } catch (_: UnsatisfiedLinkError) {
            dpadSynthesisState = false
            Log.i(
                "PES17",
                "nativeDpadSynthesis could not link: the app keeps its own d-pad synthesis",
            )
            false
        }
    }

    /**
     * A tap carried to the next poll instead of lost between two
     * (runtime/stick_pulses.h). A runtime without it still reads the held
     * state, so a press shorter than the frame gap is dropped, not a crash.
     */
    val touchPulse: Boolean by lazy {
        answered("nativeTouchPulse") { nativeTouchPulse(0) }
    }

    /**
     * The stick's deadzone and curve (runtime/stick_curve.h). A runtime without
     * them keeps the deadzone and shape it was built with.
     */
    val stickSettings: Boolean by lazy {
        answered("nativeStickSettings") { nativeStickSettings(0.25f, 1f, 0.25f, 1f) }
    }

    external fun nativePadState(
        user: Int,
        buttons: Int,
        leftTrigger: Int,
        rightTrigger: Int,
        leftX: Float,
        leftY: Float,
        rightX: Float,
        rightY: Float,
    )

    external fun nativePadConnected(user: Int, connected: Boolean)
    external fun nativePadEnabled(enabled: Boolean)

    // ---- the graphics driver ----------------------------------------------

    /** An unpacked driver folder and the library inside it, or "" for the system's. */
    external fun nativeSelectDriver(directory: String, library: String)
    external fun nativeDriverDescription(): String

    // ---- installing --------------------------------------------------------

    external fun nativeGameInstalled(gameDir: String): Boolean

    /** Blocking. Returns null when it worked, or a sentence to show when it did not. */
    external fun nativeInstall(source: String, gameDir: String): String?

    /** Fills [progress] with { bytes done, bytes total } and returns the file being copied. */
    external fun nativeInstallProgress(progress: LongArray): String

    external fun nativeCancelInstall()

    // ---- what the overlay shows --------------------------------------------

    /** Fills [out] with { frames per second, texture MB, width, height, frame time ms, presented frames }. */
    external fun nativeStats(out: FloatArray)

    /** Returns formatted "Device|RendererMode|TextureCompression" */
    external fun nativeRendererInfo(): String

    /** Engine options in force, switch files, swapchain/present counts and Vulkan device facts, as text. */
    external fun nativeEngineReport(): String

    /** Feeds a console command into IW4 Cbuf_AddText queue (god, give all, map, etc.) */
    external fun nativeRunConsoleCommand(command: String)

    // ---- the button bits the guest expects ----------------------------------
    // XINPUT_GAMEPAD_*, as runtime/kernel/input.cpp reads them.
    const val UP = 0x0001
    const val DOWN = 0x0002
    const val LEFT = 0x0004
    const val RIGHT = 0x0008
    const val START = 0x0010
    const val BACK = 0x0020
    const val L3 = 0x0040
    const val R3 = 0x0080
    const val LB = 0x0100
    const val RB = 0x0200
    const val A = 0x1000
    const val B = 0x2000
    const val X = 0x4000
    const val Y = 0x8000
}

/**
 * Implemented by the game activity. Both methods arrive on runtime threads.
 * The names and signatures are looked up from C++ by reflection, so the
 * shrinker is told to leave them alone (proguard-rules.pro).
 */
interface NativeListener {
    /** Motor strengths, 0..65535, only when they change. */
    fun onRumble(user: Int, lowFrequency: Int, highFrequency: Int)

    /** A sentence for the player: what the run is doing, or why it stopped. */
    fun onStatus(text: String)

    /** The title wants text (a name): show a dialog with the keyboard, answer with nativeKeyboardResult. */
    fun onKeyboard(title: String, description: String, initial: String, maxLength: Int)

    /**
     * What the title is doing, only when it changes: one of [GamePhase]. The
     * controls marked for a phase come and go with it.
     */
    fun onGamePhase(phase: Int)
}
