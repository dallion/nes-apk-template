package com.nes.template

import org.junit.Assert.*
import org.junit.Test

class CheatParserTest {
    @Test fun vctExpandsMultiByteValuesInLittleEndianOrder() {
        val entry = CheatParser.parse("; sample\n#1 0390-04-00000002  No timeover").single()
        assertEquals("No timeover",entry.name)
        assertFalse(entry.enabled)
        assertEquals(listOf("RAM:0390:02","RAM:0391:00","RAM:0392:00","RAM:0393:00"),CheatParser.nativeCodes(entry.code))
    }
    @Test fun supportsGameGenieAndDistinguishesRawRamFromRom() {
        assertEquals(listOf("SXIOPO"),CheatParser.nativeCodes("sxiopo"))
        assertEquals(listOf("RAM:6800:63"),CheatParser.nativeCodes("6800:63"))
        assertEquals(listOf("8000:63"),CheatParser.nativeCodes("8000:63"))
        assertEquals(2,CheatParser.parse("SXIOPO  Lives\n\n;ignore\n6800:63  Money").size)
    }

    @Test fun groupsGameGenieCodesAndRamWritesUnderOnePreset() {
        val entry = CheatParser.parseBundled("[组合预设]\non=egvuzkog;XVOLGGYO;602C,7F,96,98").single()
        assertEquals("组合预设",entry.name)
        assertFalse(entry.enabled)
        assertEquals(
            listOf("EGVUZKOG","XVOLGGYO","RAM:602C:7F","RAM:602D:96","RAM:602E:98"),
            CheatParser.nativeCodes(entry.code)
        )
        assertThrows(IllegalArgumentException::class.java) {
            CheatParser.parseBundled("[组合预设]\non=EGVUZKOG;invalid")
        }
    }
    @Test fun rejectsBadCodeOverflowAndNonRamVct() {
        for(code in listOf("BBBBBB","6800-00-01","6800-05-01","6800-01-100","7FFF-02-1234","8000-01-01","6800:6","RAM:6800:63")) {
            assertThrows(code,IllegalArgumentException::class.java) { CheatParser.nativeCodes(code) }
        }
    }
    @Test fun rejectsEntireImportWhenAnyLineIsInvalid() {
        val error = assertThrows(IllegalArgumentException::class.java) { CheatParser.parse("SXIOPO\nbad-code") }
        assertTrue(error.message!!.contains("2"))
    }
    @Test fun decodesUtf8AndLegacyChineseNames() {
        val text = "6800-01-63  金钱"
        assertEquals("金钱",CheatParser.parse(CheatParser.decode(text.toByteArray(Charsets.UTF_8))).single().name)
        assertEquals("金钱",CheatParser.parse(CheatParser.decode(text.toByteArray(charset("GB18030")))).single().name)
    }
}
