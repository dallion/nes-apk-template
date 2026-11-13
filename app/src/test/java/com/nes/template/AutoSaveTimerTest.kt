package com.nes.template

import org.junit.Assert.*
import org.junit.Test

class AutoSaveTimerTest {
    @Test fun savesEveryThirtySecondsOfGameplayAcrossPauses() {
        val timer = AutoSaveTimer()
        assertFalse(timer.onFrame(0))
        assertFalse(timer.onFrame(20_000))
        timer.pause()
        assertFalse(timer.onFrame(500_000))
        assertFalse(timer.onFrame(509_999))
        assertTrue(timer.onFrame(510_000))
        assertFalse(timer.onFrame(539_999))
        assertTrue(timer.onFrame(540_000))
    }
}
