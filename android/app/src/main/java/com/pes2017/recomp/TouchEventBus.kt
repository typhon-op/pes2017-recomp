package com.pes2017.recomp

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Decoupled event bus for gesture executions, input state transitions,
 * and HUD toast feedback.
 */
object TouchEventBus {

    enum class GestureType {
        SHOT_FAKE,
        AUTO_SPRINT,
        QUICK_STOP,
        GIVE_AND_GO,
        MICRO_FLICK,
        SUPER_CANCEL,
        TEAMMATE_PRESS,
    }

    data class GestureEvent(
        val type: GestureType,
        val details: String = "",
        val timestamp: Long = System.currentTimeMillis(),
    )

    data class StateChangeEvent(
        val isDefending: Boolean,
        val inMenuMode: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
    )

    private val gestureListeners = CopyOnWriteArrayList<(GestureEvent) -> Unit>()
    private val stateListeners = CopyOnWriteArrayList<(StateChangeEvent) -> Unit>()
    private val toastListeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun registerGestureListener(listener: (GestureEvent) -> Unit) {
        gestureListeners.add(listener)
    }

    fun unregisterGestureListener(listener: (GestureEvent) -> Unit) {
        gestureListeners.remove(listener)
    }

    fun postGesture(type: GestureType, details: String = "") {
        val event = GestureEvent(type, details)
        for (listener in gestureListeners) {
            try {
                listener(event)
            } catch (_: Throwable) {}
        }
    }

    fun registerStateListener(listener: (StateChangeEvent) -> Unit) {
        stateListeners.add(listener)
    }

    fun unregisterStateListener(listener: (StateChangeEvent) -> Unit) {
        stateListeners.remove(listener)
    }

    fun postStateChange(isDefending: Boolean, inMenuMode: Boolean) {
        val event = StateChangeEvent(isDefending, inMenuMode)
        for (listener in stateListeners) {
            try {
                listener(event)
            } catch (_: Throwable) {}
        }
    }

    fun registerToastListener(listener: (String) -> Unit) {
        toastListeners.add(listener)
    }

    fun unregisterToastListener(listener: (String) -> Unit) {
        toastListeners.remove(listener)
    }

    fun postToast(message: String) {
        for (listener in toastListeners) {
            try {
                listener(message)
            } catch (_: Throwable) {}
        }
    }
}
