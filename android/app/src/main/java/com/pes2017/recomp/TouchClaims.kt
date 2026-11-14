package com.pes2017.recomp

/**
 * Who is holding what on the on-screen pad.
 *
 * A finger is a pointer id, and a control can be under more than one finger:
 * two thumbs resting on the same button is not rare, and neither is a thumb
 * landing on a button the other thumb is already holding. So what a control is
 * doing is counted here rather than assumed, and a control is only let go when
 * the last finger on it lifts. Toggles are latched instead: a tap flips them
 * and no finger has to stay down.
 *
 * Kept out of [TouchOverlayView] and free of Android types, so the state
 * machine can be read and tested on its own (TouchClaimsTest) rather than only
 * by playing with two thumbs.
 */
internal class TouchClaims {

    private class Claim(val element: ControlElement, val holds: Boolean)

    /** What a lift let go of; [lastClaim] is false while another finger still has it. */
    class Released(val element: ControlElement, val lastClaim: Boolean)

    private val byPointer = HashMap<Int, Claim>()
    private val holds = HashMap<String, Int>()        // momentary buttons: element id -> fingers
    private val stickOwners = HashMap<String, Int>()  // sticks: element id -> fingers
    private val toggled = HashSet<String>()

    fun elementFor(pointerId: Int): ControlElement? = byPointer[pointerId]?.element

    /** Whether the guest should see this control as down. */
    fun isOn(id: String): Boolean = (holds[id] ?: 0) > 0 || toggled.contains(id)

    fun isToggled(id: String): Boolean = toggled.contains(id)

    fun holdCount(id: String): Int = holds[id] ?: 0

    fun stickHoldCount(id: String): Int = stickOwners[id] ?: 0

    /**
     * A finger went down on [element]. Returns whether the guest's buttons or
     * triggers changed; a stick's deflection is the caller's to compute.
     */
    fun press(pointerId: Int, element: ControlElement): Boolean {
        // A pointer cannot press twice without lifting; drop whatever it had,
        // so a lost ACTION_UP cannot leave a count that never comes back down.
        release(pointerId)
        if (element.kind == ControlKind.STICK) {
            byPointer[pointerId] = Claim(element, holds = false)
            stickOwners[element.id] = (stickOwners[element.id] ?: 0) + 1
            return false
        }
        byPointer[pointerId] = Claim(element, holds = !element.toggle)
        if (element.toggle) {
            if (!toggled.remove(element.id)) toggled.add(element.id)
        } else {
            holds[element.id] = (holds[element.id] ?: 0) + 1
        }
        return true
    }

    /** A finger lifted. Null when no finger was on that pointer id. */
    fun release(pointerId: Int): Released? {
        val claim = byPointer.remove(pointerId) ?: return null
        val id = claim.element.id
        val last = when {
            claim.element.kind == ControlKind.STICK -> {
                val left = (stickOwners[id] ?: 1) - 1
                if (left <= 0) stickOwners.remove(id) else stickOwners[id] = left
                left <= 0
            }
            claim.holds -> {
                val left = (holds[id] ?: 1) - 1
                if (left <= 0) holds.remove(id) else holds[id] = left
                left <= 0
            }
            else -> true   // a toggle: nothing was counted, so nothing is left over
        }
        return Released(claim.element, last)
    }

    /** Every finger up. Toggles stay: a latched run is still latched after a pause. */
    fun clearMomentary() {
        byPointer.clear()
        holds.clear()
        stickOwners.clear()
    }

    /** Every finger up and every toggle let go: the editor uses this. */
    fun clearAll() {
        clearMomentary()
        toggled.clear()
    }
}
