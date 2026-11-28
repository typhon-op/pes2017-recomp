package com.pes2017.recomp

/**
 * The Performance page's CPU Thread Limit, and the only thing that knows how it
 * reaches the engine.
 *
 * It does not switch cores off and it does not touch the threads that have to
 * keep running: the main recompiled game loop, the renderer's presenter and
 * command recorder, audio and input keep their own cores whatever this says.
 * What it limits is the pool of cores the title's own worker threads are pinned
 * to, and the size of the engine's own shader-preparing pool
 * (`runtime/thread_limit.h` in the engine).
 *
 * Stored as one int so the layout's radio ids map table-to-table, and given to
 * the runtime as `PES17_THREAD_LIMIT` before it starts: "" (Auto, the pinning the
 * engine would have done anyway), "2", "3", "4", or "all".
 */
object CpuThreadLimit {

    /** Auto, 2, 3, 4, every available core -- the order the page shows them in. */
    val VALUES = intArrayOf(0, 2, 3, 4, -1)

    /** Anything a stale preference may hold reads as Auto. */
    fun normalize(value: Int): Int = if (VALUES.contains(value)) value else 0

    /** What the runtime reads, or "" to leave the pinning exactly as it was. */
    fun option(value: Int): String = when (normalize(value)) {
        2 -> "2"
        3 -> "3"
        4 -> "4"
        -1 -> "all"
        else -> ""
    }

    /** The row's own words, one resource per choice. */
    fun labelRes(value: Int): Int = when (normalize(value)) {
        2 -> R.string.cpu_threads_2
        3 -> R.string.cpu_threads_3
        4 -> R.string.cpu_threads_4
        -1 -> R.string.cpu_threads_all
        else -> R.string.cpu_threads_auto
    }
}
