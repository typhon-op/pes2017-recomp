package com.pes2017.recomp

/**
 * How big the Left stick preview draws the stick: exactly the socket the match
 * pad draws, from the layout's own numbers.
 *
 * The preview used to fill whatever page it was on, so the same stick that is
 * 150 dp under a thumb in a match arrived on the preview page at three times
 * that and read as a different control. The size a player picks in Edit Controls
 * is `controls.json`'s `stick` element, fitted by [ScreenFit] against the
 * window exactly as [TouchOverlayView.geometryFor] fits it, and scaled by the
 * same density: [radiusPx] is that one arithmetic, here rather than copied into
 * a second drawing path.
 *
 * Free of Android types, so [StickPreviewSizeTest] checks it against the pad's
 * own geometry without a device.
 */
object StickPreview {

    /** The layout id of the movement socket, the one every pad has. */
    const val STICK_ID = "stick"

    /** The socket's centre-to-rim radius in pixels, as the match pad draws it. */
    fun radiusPx(sizeDp: Float, fit: ScreenFit, density: Float): Float =
        fit.sizeDp(sizeDp) * (if (density > 0f) density else 1f) * 0.5f

    /** The size in dp the layout asks for, before fitting. */
    fun sizeDp(layout: ControlLayout): Float? =
        layout.find(STICK_ID)?.sizeOn(PadStance.ATTACK)

    /**
     * A window's unusable edges in dp, from the pixel insets the system reports
     * -- the same conversion [TouchOverlayView.onApplyWindowInsets] makes, so a
     * notched phone fits the preview's socket the way it fits the pad's.
     */
    fun safeArea(left: Int, top: Int, right: Int, bottom: Int, density: Float): SafeAreaDp {
        val scale = if (density > 0f) density else 1f
        return SafeAreaDp(left / scale, top / scale, right / scale, bottom / scale)
    }
}
