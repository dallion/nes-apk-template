package com.nes.template
import org.junit.Assert.*
import org.junit.Test

class TouchLayoutTest {
    @Test fun commonPhoneLayoutsNeverOverlapButtons() {
        val layouts = listOf(240f to 0f, 320f to 0f, 360f to 0f, 640f to 0f, 640f to 120f)
        for((width, sideWidth) in layouts) {
            val buttons = touchLayout(width,154f,1f,sideWidth)
            for(i in buttons.indices) for(j in 0 until i) {
                val a = buttons[i]; val b = buttons[j]
                val overlaps = a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
                assertFalse("width=$width: ${a.label} overlaps ${b.label}",overlaps)
            }
            assertEquals(listOf(96,97,99,100),buttons.filter { it.label in listOf("A","B","X","Y") }.map { it.key }.sorted())
            assertEquals(10,buttons.size)
            assertTrue(buttons.all { it.left >= 0 && it.right <= width && it.top >= 0 && it.bottom <= 154f })
            if(sideWidth > 0f) assertTrue(buttons.all { it.right <= sideWidth || it.left >= width-sideWidth })
        }
    }
}
