package com.nes.template

import org.junit.Assert.*
import org.junit.Test

class CheatGroupsTest {
    @Test fun groupedCodesExpandInOrder() {
        assertEquals(listOf("RAM:0590:10", "RAM:0591:1F", "RAM:05D0:01", "RAM:05D1:02"),
            CheatParser.nativeCodes("0590-02-1F10 05D0-02-0201"))
    }
    @Test fun chtBytesAreSequentialAndGroupIsOneSwitch() {
        val entries = CheatParser.parse("[装备]\non=590,10,1F,2B,31;5D0,01,02,01,02\n[敏捷]\non=534,FA")
        assertEquals(2,entries.size)
        assertFalse(entries.first().enabled)
        assertEquals(listOf("RAM:0590:10","RAM:0591:1F","RAM:0592:2B","RAM:0593:31",
            "RAM:05D0:01","RAM:05D1:02","RAM:05D2:01","RAM:05D3:02"),CheatParser.nativeCodes(entries.first().code))
    }
    @Test fun namedOptionsAndLongByteRunsAreSupported() {
        val entries = CheatParser.parse("[血量]\n哪吒=51C,0B,04\n龙女=51E,4F,0A\n[防具]\non=5a0,09,11,17,1b,22,23,24,25")
        assertEquals("血量 / 哪吒",entries[0].name)
        assertEquals(8,CheatParser.nativeCodes(entries[2].code).size)
        assertEquals("RAM:051C:0B",CheatParser.nativeCodes(entries[0].code)[0])
    }
    @Test fun rejectsMalformedSectionsAndOverflowAsAWhole() {
        for(text in listOf("[空]", "on=534,FA", "[坏]\non=7FFF,01,02", "[坏]\non=534,FA;bad", "[坏]\non=534,100"))
            assertThrows(IllegalArgumentException::class.java) { CheatParser.parse(text) }
    }
}
