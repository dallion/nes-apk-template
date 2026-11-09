package com.nes.template

import org.junit.Assert.*
import org.junit.Test

class FpsCounterTest {
    @Test fun measuresFramesOverElapsedTime() {
        val counter = FpsCounter()
        assertNull(counter.onFrame(0))
        for(i in 1 until 30) assertNull(counter.onFrame(i*500_000_000L/30))
        assertEquals(60.0,counter.onFrame(500_000_000L)!!,0.001)
    }

    @Test fun irregularFramesUseActualElapsedTime() {
        val counter = FpsCounter()
        counter.onFrame(0)
        counter.onFrame(100_000_000L)
        counter.onFrame(300_000_000L)
        assertEquals(3.0/0.7,counter.onFrame(700_000_000L)!!,0.001)
    }

    @Test fun resetExcludesPausedTimeAndPendingFrames() {
        val counter = FpsCounter()
        counter.onFrame(0)
        counter.onFrame(250_000_000L)
        counter.reset()
        assertNull(counter.onFrame(10_000_000_000L))
        counter.onFrame(10_250_000_000L)
        assertEquals(4.0,counter.onFrame(10_500_000_000L)!!,0.001)
    }
}
