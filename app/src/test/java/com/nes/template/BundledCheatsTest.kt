package com.nes.template

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class BundledCheatsTest {
    @get:Rule val folder = TemporaryFolder()
    private val presets = listOf(CheatEntry("装备", "0590:10 05D0:01"))
    @Test fun freshRomHasDisabledPresetsAndPersistsTheWholeGroup() {
        val storage = GameStorage(folder.root, byteArrayOf(1))
        val book = CheatBook(storage,presets)
        assertEquals(presets,book.entries)
        book.replace(book.entries.map { it.copy(enabled=true) })
        assertEquals(listOf("RAM:0590:10","RAM:05D0:01"),CheatBook(storage,presets).activeCodes())
        assertTrue(CheatBook(GameStorage(folder.root,byteArrayOf(2))).entries.isEmpty())
    }
    @Test fun upgradeDoesNotOverwriteSavedEntriesAndRestoreDoesNotDuplicateThem() {
        val storage = GameStorage(folder.root, byteArrayOf(1))
        CheatBook(storage).replace(listOf(CheatEntry("自己加的", "SXIOPO",true)))
        val book = CheatBook(storage,presets)
        assertEquals(1,book.entries.size)
        book.restorePresets()
        assertEquals(2,book.entries.size)
        assertTrue(book.entries[0].enabled)
        assertFalse(book.entries[1].enabled)
        book.restorePresets()
        assertEquals(2,book.entries.size)
        book.replace(emptyList())
        assertTrue(CheatBook(storage,presets).entries.isEmpty())
    }
}
