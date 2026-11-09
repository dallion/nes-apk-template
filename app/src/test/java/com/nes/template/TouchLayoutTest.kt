package com.nes.template
import org.junit.Assert.*
import org.junit.Test

class TouchLayoutTest {
    @Test fun commonPhoneLayoutsNeverOverlapButtons() {
        for(width in listOf(240f,320f,360f,640f)) {
            val buttons = touchLayout(width,154f,1f)
            for(i in buttons.indices) for(j in 0 until i) {
                val a = buttons[i]; val b = buttons[j]
                val overlaps = a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
                assertFalse("width=$width: ${a.label} overlaps ${b.label}",overlaps)
            }
            assertEquals(8,buttons.size)
            assertTrue(buttons.all { it.left >= 0 && it.right <= width && it.top >= 0 && it.bottom <= 154f })
        }
    }
}
