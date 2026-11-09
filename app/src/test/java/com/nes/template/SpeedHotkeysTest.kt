package com.nes.template

import org.junit.Assert.*
import android.view.KeyEvent
import org.junit.Test

class SpeedHotkeysTest {
    @Test fun repeatedDownChangesSpeedOnlyOnceUntilReleased() {
        var speed = 1
        val keys = SpeedHotkeys({ speed }, { speed = it })
        assertTrue(keys.key(1,KeyEvent.KEYCODE_BUTTON_R1,0,0))
        assertEquals(2,speed)
        keys.key(1,KeyEvent.KEYCODE_BUTTON_R1,0,1)
        keys.key(1,KeyEvent.KEYCODE_BUTTON_R1,0,0) // Also reject duplicate downs from drivers without repeat counts.
        assertEquals(2,speed)
        keys.key(1,KeyEvent.KEYCODE_BUTTON_R1,1,0)
        keys.key(1,KeyEvent.KEYCODE_BUTTON_R1,0,0)
        assertEquals(3,speed)
    }

    @Test fun shoulderButtonsRespectOneAndFourLimits() {
        var speed = 1
        val keys = SpeedHotkeys({ speed }, { speed = it })
        repeat(6) { keys.key(1,KeyEvent.KEYCODE_BUTTON_R1,0,0); keys.key(1,KeyEvent.KEYCODE_BUTTON_R1,1,0) }
        assertEquals(4,speed)
        repeat(6) { keys.key(1,KeyEvent.KEYCODE_BUTTON_L1,0,0); keys.key(1,KeyEvent.KEYCODE_BUTTON_L1,1,0) }
        assertEquals(1,speed)
        assertFalse(keys.key(1,21,0,0)) // D-pad left remains a game input.
    }

    @Test fun clearingHeldKeysAllowsFreshPressButNotOrphanRepeats() {
        var speed = 1
        val keys = SpeedHotkeys({ speed }, { speed = it })
        keys.key(1,KeyEvent.KEYCODE_BUTTON_R1,0,0)
        keys.clear()
        keys.key(1,KeyEvent.KEYCODE_BUTTON_R1,0,2)
        assertEquals(2,speed)
        keys.key(1,KeyEvent.KEYCODE_BUTTON_R1,0,0)
        assertEquals(3,speed)
    }

    @Test fun differentDevicesHaveIndependentPressEdges() {
        var speed = 1
        val keys = SpeedHotkeys({ speed }, { speed = it })
        keys.key(1,KeyEvent.KEYCODE_BUTTON_R1,0,0)
        keys.key(2,KeyEvent.KEYCODE_BUTTON_R1,0,0)
        assertEquals(3,speed)
    }
}

