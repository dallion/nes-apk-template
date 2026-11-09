package com.nes.template

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class CheatBookTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun savesNamesCodesAndFlagsSeparatelyForEachRom() {
        val first = GameStorage(folder.root,byteArrayOf(1))
        val entries = listOf(CheatEntry("测试","6800-01-63",true),CheatEntry("Lives","SXIOPO",false))
        CheatBook(first).replace(entries)
        assertEquals(entries,CheatBook(first).entries)
        assertTrue(CheatBook(GameStorage(folder.root,byteArrayOf(2))).entries.isEmpty())
        assertNull(first.readSram())
    }
    @Test fun rejectsOversizedCheatSetWithoutReplacingSavedEntries() {
        val book = CheatBook(GameStorage(folder.root,byteArrayOf(1)))
        book.replace(listOf(CheatEntry("One","SXIOPO",false)))
        val tooMany = List(257) { CheatEntry("$it","SXIOPO",true) }
        assertThrows(IllegalArgumentException::class.java) { book.replace(tooMany) }
        assertEquals("One",book.entries.single().name)
    }
    @Test fun refusesCorruptFileRatherThanSilentlyOverwritingIt() {
        val storage = GameStorage(folder.root,byteArrayOf(1))
        storage.writeCheats(byteArrayOf(1,2,3))
        assertThrows(Exception::class.java) { CheatBook(storage) }
    }
}
