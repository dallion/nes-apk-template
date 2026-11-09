package com.nes.template
import org.junit.Assert.*
import org.junit.Test

class InputRouterTest {
    @Test fun releasingTouchKeepsHeldControllerButton() {
        val events = mutableListOf<Pair<Int,Boolean>>()
        val input = InputRouter { _, b, down -> events.add(b to down) }
        input.setButton("touch", 0, 96, true)
        input.setButton("pad:1", 0, 96, true)
        input.releaseSource("touch")
        assertEquals(listOf(96 to true), events)
        input.releaseSource("pad:1")
        assertEquals(listOf(96 to true, 96 to false), events)
    }
    @Test fun clearReleasesAllPlayersWithoutDuplicateKeydowns() {
        val events = mutableListOf<Triple<Int,Int,Boolean>>()
        val input = InputRouter { p,b,d -> events.add(Triple(p,b,d)) }
        input.setButton("pad",0,19,true)
        input.setButton("pad",0,19,true)
        input.setButton("pad",1,19,true)
        input.releaseAll()
        assertEquals(listOf(Triple(0,19,true),Triple(1,19,true),Triple(0,19,false),Triple(1,19,false)), events)
    }
    @Test fun axisDeadzoneHandlesBoundaryAndNonfiniteValues() {
        assertEquals(0, axisDirection(0.25f))
        assertEquals(1, axisDirection(0.26f))
        assertEquals(-1, axisDirection(-0.26f))
        assertEquals(0, axisDirection(Float.NaN))
    }
}
