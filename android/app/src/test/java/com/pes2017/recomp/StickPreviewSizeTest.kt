package com.pes2017.recomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Left stick preview's size, without a view: it is the match pad's socket,
 * from the layout's own `stick` element and the same fitting rule, so a stick
 * resized in Edit Controls is the same size on the preview page and on the
 * pitch -- and there is no page-specific scale to drift from it.
 */
class StickPreviewSizeTest {

    private val phone = ScreenFit(800f, 360f)

    @Test
    fun thePreviewDrawsTheLayoutsStick() {
        val layout = ControlLayout.defaults(800f, 360f)
        val sizeDp = StickPreview.sizeDp(layout) ?: error("the default pad has a movement stick")

        assertEquals("the factory socket", 150f, sizeDp, 1e-3f)
        // A 150 dp socket is 75 dp of radius; at density 3 that is 225 px. The
        // old preview filled the page instead, which is why it read as a
        // different control.
        assertEquals(225f, StickPreview.radiusPx(sizeDp, phone, 3f), 1e-3f)
    }

    @Test
    fun resizingTheStickResizesThePreview() {
        val layout = ControlLayout.defaults(800f, 360f)
        val stick = layout.find(StickPreview.STICK_ID) ?: error("the default pad has a movement stick")
        val before = StickPreview.radiusPx(StickPreview.sizeDp(layout)!!, phone, 2f)

        // What Edit Controls' size slider writes.
        stick.sizeDp = 96f
        val after = StickPreview.radiusPx(StickPreview.sizeDp(layout)!!, phone, 2f)

        assertEquals(96f, StickPreview.sizeDp(layout)!!, 1e-3f)
        assertEquals(96f, after, 1e-3f)
        assertNotEquals("the preview follows the player's size", before, after, 1e-3f)
    }

    @Test
    fun thePreviewUsesThePadsArithmetic() {
        // The overlay's geometryFor: fitted size in dp, times density, halved.
        val sizeDp = 132f
        val fit = ScreenFit(900f, 420f, SafeAreaDp(12f, 30f, 12f, 40f))
        val density = 2.75f
        val pad = fit.sizeDp(sizeDp) * density * 0.5f

        assertEquals(pad, StickPreview.radiusPx(sizeDp, fit, density), 1e-4f)
    }

    @Test
    fun aSmallSafeAreaShrinksBothTheSameWay() {
        // A control is never shrunk below the fit's floor, and never grown: the
        // preview must not become the one place a stick is a different size.
        val tight = ScreenFit(220f, 160f, SafeAreaDp(0f, 45f, 0f, 45f))
        val fitted = tight.sizeDp(150f)
        val radius = StickPreview.radiusPx(150f, tight, 1f)

        assertEquals(fitted, radius * 2f, 1e-4f)
        assertTrue("fitted, not grown", fitted <= 150f)
    }

    @Test
    fun aLayoutWithoutAStickHasNoPreviewSize() {
        // A pad restored without its stick: the page has nothing to size, and
        // draws nothing rather than inventing a socket of its own.
        val layout = ControlLayout.defaults(800f, 360f)
        layout.elements.removeAll { it.id == StickPreview.STICK_ID }

        assertTrue(StickPreview.sizeDp(layout) == null)
    }

    @Test
    fun insetsAreConvertedToDpLikeThePad() {
        val area = StickPreview.safeArea(30, 90, 30, 60, 3f)

        assertEquals(SafeAreaDp(10f, 30f, 10f, 20f), area)
        // A density of zero would divide by zero on a broken display: it is
        // treated as one rather than crashing the page.
        assertEquals(SafeAreaDp(30f, 90f, 30f, 60f), StickPreview.safeArea(30, 90, 30, 60, 0f))
    }
}
