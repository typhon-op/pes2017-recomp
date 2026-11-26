package com.pes2017.recomp

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The right stick with the flick-skill switch on.
 *
 * `Prefs.flickSkillStick` changes how a thumb's travel on RS is read -- the
 * runtime gets the flick's moves -- but not what RS is. The press still deflects
 * the stick, the flick's travel is written back into that deflection, and
 * pushState sums every finger on the stick into the right axes. Before tfix5 the
 * flick path sent only the pulses and pushState skipped the axes, so a flick
 * build had a dead right stick; these tests pin the arithmetic that has to stay.
 *
 * The View itself needs a phone (a Canvas, MotionEvents, the JNI bridge), so
 * what runs here is the two pieces it hands a right-stick sample to: StickVector
 * for the press and RightStick for the axes the state carries.
 */
class RightStickTest {

    private val radius = 150f
    private val baseX = 700f
    private val baseY = 900f

    /**
     * One finger on RS while flick-skill is on: the overlay's press branch calls
     * moveStick for the analogue deflection, and each move calls sendSkill,
     * whose travel is written to the same stickPositions entry.
     *
     * `up` and `right` are in socket radii; up the screen is positive Y.
     */
    private fun flickOn(right: Float, up: Float): RightStick {
        val stick = RightStick()
        // The press: centred, so the deflection it adds is nothing yet.
        val press = StickVector.of(baseX, baseY, baseX, baseY, radius)
        stick.add(press[0], press[1])
        // The thumb slides: this is the sample sendSkill stores and pushState
        // reads back out as RS.
        val travel = RightStick.flickTravel(
            baseX, baseY, baseX + right * radius, baseY - up * radius, radius,
        )
        stick.add(RightStick.clamp(travel[0]), RightStick.clamp(travel[1]))
        return stick
    }

    @Test
    fun aFlickOnPressStillSendsTheRightStickAxes() {
        val stick = flickOn(0.8f, 0f)
        // Not zero: the flick is a gesture on top of an analogue stick.
        assertTrue("RS is sending a throw: ${stick.rightX()}", stick.rightX() > 0.05f)
        assertEquals(0.8f, stick.rightX(), 0.0001f)
        assertEquals("no vertical leak from a horizontal flick", 0f, stick.rightY(), 0.0001f)
    }

    @Test
    fun theFlickedDirectionIsTheRightSticksDirection() {
        // Every arm and a diagonal, because a flick is read as a direction and
        // that direction is also the stick's own deflection.
        for ((right, up) in listOf(
            1f to 0f, 0f to 1f, -1f to 0f, 0f to -1f, 0.6f to -0.6f,
        )) {
            val stick = flickOn(right, up)
            assertEquals("x for $right/$up", right, stick.rightX(), 0.0001f)
            assertEquals("y for $right/$up", up, stick.rightY(), 0.0001f)
            assertEquals("the throw at $right/$up", hypot(right, up), hypot(stick.rightX(), stick.rightY()), 0.0001f)
        }
    }

    @Test
    fun aRestingRightStickSendsNothing() {
        // A press alone is not a direction, and neither is a flick build's RS.
        val stick = flickOn(0f, 0f)
        assertEquals(0f, stick.rightX(), 0f)
        assertEquals(0f, stick.rightY(), 0f)
    }

    @Test
    fun everyFingerOnTheStickAddsAndTheSumStaysOnTheAxis() {
        // Two thumbs on one stick still cannot ask for more than full deflection.
        val stick = RightStick()
        stick.add(0.8f, 0.4f)
        stick.add(0.7f, 0.9f)
        assertEquals(1f, stick.rightX(), 0f)
        assertEquals(1f, stick.rightY(), 0f)
        // And the other way: the axis is -1..1, not 0..1.
        val other = RightStick()
        other.add(-0.6f, -0.6f)
        other.add(-0.6f, -0.6f)
        assertEquals(-1f, other.rightX(), 0f)
        assertEquals(-1f, other.rightY(), 0f)
    }

    @Test
    fun aFlickPastTheRimKeepsTheKnobOnIt() {
        // The runtime is given the whole travel (it quantises the direction);
        // the stick's own state is a full deflection, so the knob cannot leave
        // the socket the hand is drawing.
        val travel = RightStick.flickTravel(baseX, baseY, baseX + 4f * radius, baseY, radius)
        assertEquals(RightStick.MAX_TRAVEL, travel[0], 0.0001f)
        assertEquals(RightStick.MAX_TRAVEL, hypot(travel[0], travel[1]), 0.0001f)
        assertEquals(1f, RightStick.clamp(travel[0]), 0f)
    }

    @Test
    fun aNewPushStartsFromNothing() {
        val stick = flickOn(1f, 0f)
        stick.reset()
        assertEquals(0f, stick.rightX(), 0f)
        assertEquals(0f, stick.rightY(), 0f)
    }
}
