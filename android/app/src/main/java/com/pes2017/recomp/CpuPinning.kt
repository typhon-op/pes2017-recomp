package com.pes2017.recomp

/**
 * The Performance page's CPU Pinning row, and the only thing that knows how it
 * reaches the engine.
 *
 * It offers no new arrangement of its own: both choices are modes the runtime
 * already has (`runtime/platform.cpp`, `PES17_PINNING`), and the ones its own
 * POCO X7 Pro measurements support.
 *
 *  * **Automatic** (the default) is the pinning the engine does with no setting
 *    at all: the main recompiled loop on the prime core, the title's own threads
 *    on the performance cluster, audio and file I/O on the worker cluster, and
 *    the presenter and ring consumer moved off the prime core on Mali. That is
 *    the arrangement every measurement in `docs/PORTING.md` was taken with -- it
 *    is what took the POCO's match load from a 45 s run-queue stall (1-3 fps for
 *    a minute and a half) to about twenty seconds and 45-55 fps.
 *  * **Wide** (`PES17_PINNING=wide`) is the engine's own answer to the one
 *    measured problem left: on a 1+3+4 part the title's sixty-odd threads share
 *    three cores and the busy ones "waited about as long as they ran". Wide adds
 *    the worker cluster to what they may use.
 *
 * Not offered, because the notes record them as worse: `shared` (the consumer
 * inherits the prime core: 45 s of 58 waiting, 1-3 fps), `big` (the performance
 * cores ~80 % busy while the prime core sat 55 % idle), and `render` on an
 * Adreno phone (43-53 fps against a steady 60). `off` is not offered either: it
 * gives up the placement that was the measured fix.
 */
object CpuPinning {

    /** The engine's own arrangement. The default, and the measured best. */
    const val AUTO = 0

    /** `PES17_PINNING=wide`: the title's threads may use the little cores too. */
    const val WIDE = 1

    /** The order the page shows them in. */
    val VALUES = intArrayOf(AUTO, WIDE)

    /**
     * Anything else -- including the core counts a 0.2.7 test build stored
     * (2, 3, 4, -1) -- reads as Automatic, so a stale preference cannot put the
     * phone into a mode nobody chose.
     */
    fun normalize(value: Int): Int = if (value == WIDE) WIDE else AUTO

    /**
     * What the runtime reads for `PES17_PINNING`, or "" to leave the engine's
     * own role pinning exactly as it is.
     */
    fun option(value: Int): String = when (normalize(value)) {
        WIDE -> "wide"
        else -> ""
    }

    /** The row's own words, one resource per choice. */
    fun labelRes(value: Int): Int =
        if (normalize(value) == WIDE) R.string.cpu_pinning_wide else R.string.cpu_pinning_auto
}
