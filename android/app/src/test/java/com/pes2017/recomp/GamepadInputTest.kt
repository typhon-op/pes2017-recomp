package com.pes2017.recomp

import android.view.InputDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class GamepadInputTest {

    private fun mockDevice(isVirtual: Boolean, isExternal: Boolean, sources: Int): InputDevice {
        val device = mock(InputDevice::class.java)
        `when`(device.isVirtual).thenReturn(isVirtual)
        `when`(device.isExternal).thenReturn(isExternal)
        `when`(device.sources).thenReturn(sources)
        return device
    }

    @Test
    fun testIsPad_acceptsExternalGamepad() {
        val gamepadInput = GamepadInput()
        val device = mockDevice(
            isVirtual = false,
            isExternal = true,
            sources = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK
        )
        assertTrue(gamepadInput.isPad(device))
    }

    @Test
    fun testIsPad_acceptsExternalBluetoothOrUsbJoystick() {
        val gamepadInput = GamepadInput()
        val device = mockDevice(
            isVirtual = false,
            isExternal = true,
            sources = InputDevice.SOURCE_JOYSTICK
        )
        assertTrue(gamepadInput.isPad(device))
    }

    @Test
    fun testIsPad_rejectsVirtualDevice() {
        val gamepadInput = GamepadInput()
        val device = mockDevice(
            isVirtual = true,
            isExternal = true,
            sources = InputDevice.SOURCE_GAMEPAD
        )
        assertFalse(gamepadInput.isPad(device))
    }

    @Test
    fun testIsPad_rejectsInternalBuiltinDevice() {
        val gamepadInput = GamepadInput()
        // Built-in device (isExternal = false) such as phone accelerometer, internal touch, or rotary
        val device = mockDevice(
            isVirtual = false,
            isExternal = false,
            sources = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK
        )
        assertFalse(gamepadInput.isPad(device))
    }

    @Test
    fun testIsPad_rejectsNonGamepadNonJoystick() {
        val gamepadInput = GamepadInput()
        val device = mockDevice(
            isVirtual = false,
            isExternal = true,
            sources = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_TOUCHSCREEN
        )
        assertFalse(gamepadInput.isPad(device))
    }
}
