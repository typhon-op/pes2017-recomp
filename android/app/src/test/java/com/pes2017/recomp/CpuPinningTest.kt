package com.pes2017.recomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CPU Pinning row: the two modes the engine has and its own measurements
 * support, what reaches `PES17_PINNING`, and what a stale preference reads as.
 */
class CpuPinningTest {

    @Test
    fun onlyTheEnginesMeasuredUsableModesAreOffered() {
        assertEquals(listOf(0, 1), CpuPinning.VALUES.toList())
        assertTrue(CpuPinning.AUTO in CpuPinning.VALUES)
        assertTrue(CpuPinning.WIDE in CpuPinning.VALUES)
    }

    @Test
    fun theDefaultIsTheEngineOnArrangement() {
        // No setting at all: the role pinning every measurement was taken with.
        assertEquals(CpuPinning.AUTO, CpuPinning.normalize(0))
        assertEquals("", CpuPinning.option(0))
        assertEquals(0, CpuPinning.VALUES[0])
    }

    @Test
    fun everyChoiceIsAModeTheEngineItselfHas() {
        // runtime/platform.cpp reads PES17_PINNING and knows "", "off", "shared",
        // "wide"/"split", "big" and "render". The row may only reach the ones its
        // measurements support, and "" is the engine's default.
        assertEquals("wide", CpuPinning.option(CpuPinning.WIDE))
        val offered = CpuPinning.VALUES.map { CpuPinning.option(it) }.toSet()
        assertEquals(setOf("", "wide"), offered)
    }

    @Test
    fun aCoreCountStoredByTheOldSettingReadsAsAutomatic() {
        // The 0.2.7 test build stored a core count (0/2/3/4/-1) under its own
        // key. If any of those ever reach this model they must mean Automatic,
        // never a half-chosen arrangement: 2 and 3 cores were the ones the POCO
        // notes show starving the title's threads.
        // (1 is not in this list: it is Wide's own stored value now. The old
        // key is a different key and is deleted, so its 0/1 never reach here.)
        for (stale in listOf(2, 3, 4, -1, 5, 64, -7, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals(CpuPinning.AUTO, CpuPinning.normalize(stale))
            assertEquals("", CpuPinning.option(stale))
            assertEquals(R.string.cpu_pinning_auto, CpuPinning.labelRes(stale))
        }
    }

    @Test
    fun theTwoChoicesHaveDifferentWords() {
        assertNotEquals(CpuPinning.labelRes(CpuPinning.AUTO), CpuPinning.labelRes(CpuPinning.WIDE))
    }

    @Test
    fun aRoundTripThroughStorageKeepsTheChoice() {
        for (value in CpuPinning.VALUES) {
            assertEquals(value, CpuPinning.normalize(CpuPinning.normalize(value)))
            assertEquals(CpuPinning.option(value), CpuPinning.option(CpuPinning.normalize(value)))
            assertEquals(CpuPinning.labelRes(value), CpuPinning.labelRes(CpuPinning.normalize(value)))
        }
    }
}
