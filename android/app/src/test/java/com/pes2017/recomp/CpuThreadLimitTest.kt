package com.pes2017.recomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CPU Thread Limit as the page stores it and as the engine reads it. The
 * arithmetic is pure, so no device is needed; the engine side of the same rule
 * is `runtime/thread_limit.h`.
 */
class CpuThreadLimitTest {

    @Test
    fun thePageOffersAutoThenTwoThreeFourAndAll() {
        assertEquals(listOf(0, 2, 3, 4, -1), CpuThreadLimit.VALUES.toList())
    }

    @Test
    fun aStoredValueThePageNoLongerOffersReadsAsAuto() {
        // A preference written by an older build, or a corrupted one, must not
        // leave the setting in a state the page cannot show.
        for (value in listOf(1, 5, 6, 7, 64, -3, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals(0, CpuThreadLimit.normalize(value))
        }
        for (value in CpuThreadLimit.VALUES) {
            assertEquals(value, CpuThreadLimit.normalize(value))
        }
    }

    @Test
    fun autoSendsNothingSoTheEngineKeepsItsOwnPinning() {
        assertEquals("", CpuThreadLimit.option(0))
        // ...and so does anything unrecognised.
        assertEquals("", CpuThreadLimit.option(9))
    }

    @Test
    fun everyChoiceReachesTheEngineAsSomethingItParses() {
        assertEquals("2", CpuThreadLimit.option(2))
        assertEquals("3", CpuThreadLimit.option(3))
        assertEquals("4", CpuThreadLimit.option(4))
        assertEquals("all", CpuThreadLimit.option(-1))
        // The parser in runtime/thread_limit.h knows exactly these.
        val known = setOf("", "2", "3", "4", "all")
        for (value in CpuThreadLimit.VALUES) {
            assertTrue(
                "option($value) is not one the engine reads",
                CpuThreadLimit.option(value) in known,
            )
        }
    }

    @Test
    fun everyChoiceHasItsOwnWords() {
        val labels = CpuThreadLimit.VALUES.map { CpuThreadLimit.labelRes(it) }
        assertEquals(CpuThreadLimit.VALUES.size, labels.toSet().size)
    }

    @Test
    fun aRoundTripThroughStorageKeepsTheChoice() {
        // What Prefs stores is normalised on both sides, so a value the page
        // offered is what the page shows again.
        for (value in CpuThreadLimit.VALUES) {
            assertEquals(value, CpuThreadLimit.normalize(CpuThreadLimit.normalize(value)))
            assertEquals(CpuThreadLimit.option(value), CpuThreadLimit.option(CpuThreadLimit.normalize(value)))
        }
    }
}
