package com.nes.template
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class GameStorageTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun romContentSeparatesSramAndStates() {
        val a = GameStorage(folder.root, byteArrayOf(1))
        val b = GameStorage(folder.root, byteArrayOf(2))
        assertNull(a.readSram())
        assertNull(a.readState())
        a.writeSram(byteArrayOf(4,5))
        a.writeState(byteArrayOf(7,8))
        assertArrayEquals(byteArrayOf(4,5), GameStorage(folder.root,byteArrayOf(1)).readSram())
        assertArrayEquals(byteArrayOf(7,8), a.readState())
        assertNull(b.readSram())
        assertNull(b.readState())
    }
    @Test fun corruptedStateAndDifferentCoreAreRejected() {
        val a = GameStorage(folder.root,byteArrayOf(1))
        a.writeState(byteArrayOf(3,4))
        val f = java.io.File(a.directory,"state.bin")
        val damaged = f.readBytes().also { it[it.lastIndex] = 99 }
        f.writeBytes(damaged)
        assertNull(a.readState())
        a.writeState(byteArrayOf(3,4))
        assertNull(GameStorage(folder.root,byteArrayOf(1),"another-core").readState())
    }
}
