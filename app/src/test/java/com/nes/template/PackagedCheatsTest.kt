package com.nes.template

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PackagedCheatsTest {
    @Test fun emptyBundledCheatsAreAllowedButEmptyImportsStillFail() {
        for(text in listOf("", "\uFEFF ; 说明\n// 注释\n")) {
            assertTrue(CheatParser.parseBundled(text).isEmpty())
            assertThrows(IllegalArgumentException::class.java) { CheatParser.parse(text) }
        }
        assertThrows(IllegalArgumentException::class.java) { CheatParser.parseBundled("[坏代码]") }
    }
    @Test fun selectedBundleParsesWithTheActualApplicationParser() {
        val path = System.getProperty("nes.cheatsFile").orEmpty()
        if(path.isEmpty()) return
        val bytes = File(path).readBytes()
        val entries = CheatParser.parseBundled(CheatParser.decode(bytes))
        assertTrue(entries.none { it.enabled })
    }
}
