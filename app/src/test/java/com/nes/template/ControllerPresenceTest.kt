package com.nes.template

import org.junit.Assert.*
import org.junit.Test

class ControllerPresenceTest {
    @Test fun keyboardWithDpadDoesNotHideTouchControls() {
        // Android SOURCE_KEYBOARD | SOURCE_DPAD, common on tablet keyboards/remotes.
        assertFalse(ControllerInput.isPhysicalController(0x00000301, false))
    }

    @Test fun physicalGamepadOrJoystickCanReplaceTouchControls() {
        assertTrue(ControllerInput.isPhysicalController(0x00000401, false))
        assertTrue(ControllerInput.isPhysicalController(0x01000010, false))
    }

    @Test fun virtualGamepadEventsDoNotHideTouchControls() {
        assertFalse(ControllerInput.isPhysicalController(0x00000401, true))
    }
}
