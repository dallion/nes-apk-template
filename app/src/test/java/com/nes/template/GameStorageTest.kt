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
    @Test fun autoSavesRollOverWithoutChangingManualStateOrSram() {
        val storage = GameStorage(folder.root,byteArrayOf(1))
        storage.writeState(byteArrayOf(99))
        storage.writeSram(byteArrayOf(98))
        repeat(25) { storage.writeAutoSave(byteArrayOf(it.toByte()),it * 30_000L) }
        val reopened = GameStorage(folder.root,byteArrayOf(1))
        val entries = reopened.listAutoSaves()
        assertEquals(20,entries.size)
        assertArrayEquals(byteArrayOf(24),reopened.readAutoSave(entries.first()))
        assertArrayEquals(byteArrayOf(5),reopened.readAutoSave(entries.last()))
        assertArrayEquals(byteArrayOf(99),reopened.readState())
        assertArrayEquals(byteArrayOf(98),reopened.readSram())
        assertTrue(GameStorage(folder.root,byteArrayOf(2)).listAutoSaves().isEmpty())
    }
    @Test fun failedCaptureKeepsHistoryAndNextSavePrunesExpiredHistory() {
        val storage = GameStorage(folder.root,byteArrayOf(1))
        storage.writeAutoSave(byteArrayOf(1),0)
        assertThrows(IllegalArgumentException::class.java) { storage.writeAutoSave(byteArrayOf(),900_000) }
        assertEquals(1,storage.listAutoSaves().size)
        storage.writeAutoSave(byteArrayOf(2),900_000)
        assertEquals(1,storage.listAutoSaves().size)
        assertArrayEquals(byteArrayOf(2),storage.readAutoSave(storage.listAutoSaves().single()))
    }
    @Test fun autoSaveChecksIntegrityAndCoreAndRecoversInterruptedReplacement() {
        val storage = GameStorage(folder.root,byteArrayOf(1))
        storage.writeAutoSave(byteArrayOf(3,4),1000)
        val entry = storage.listAutoSaves().single()
        val file = java.io.File(storage.directory,"auto/${entry.name}")
        val backup = java.io.File(file.path+".bak")
        assertTrue(file.renameTo(backup))
        file.writeBytes(byteArrayOf(0))
        assertArrayEquals(byteArrayOf(3,4),storage.readAutoSave(entry))
        assertFalse(backup.exists())
        assertTrue(GameStorage(folder.root,byteArrayOf(1),"other-core").listAutoSaves().isEmpty())
        file.writeBytes(file.readBytes().also { it[it.lastIndex] = 99 })
        assertTrue(storage.listAutoSaves().isEmpty())
    }
    @Test fun snapshotsWithIdenticalTimestampsHaveSeparateFiles() {
        val storage = GameStorage(folder.root,byteArrayOf(1))
        storage.writeAutoSave(byteArrayOf(1),1000)
        storage.writeAutoSave(byteArrayOf(2),1000)
        assertEquals(2,storage.listAutoSaves().size)
    }
    @Test fun clockRollbackKeepsNewRecoveryPointAndRemovesAbandonedTemporaryFile() {
        val storage = GameStorage(folder.root,byteArrayOf(1))
        repeat(20) { storage.writeAutoSave(byteArrayOf(1),1_000_000L+it) }
        val abandoned = java.io.File(storage.directory,"auto/123-abcdef.bin.tmp")
        abandoned.writeBytes(byteArrayOf(99))
        storage.writeAutoSave(byteArrayOf(2),1000)
        assertEquals(1,storage.listAutoSaves().size)
        assertArrayEquals(byteArrayOf(2),storage.readAutoSave(storage.listAutoSaves().single()))
        assertFalse(abandoned.exists())
    }
    @Test fun diskWriteFailureDoesNotDeleteExistingSnapshots() {
        val storage = GameStorage(folder.root,byteArrayOf(1))
        storage.writeAutoSave(byteArrayOf(1),1000)
        val directory = java.io.File(storage.directory,"auto")
        val retained = java.io.File(storage.directory,"retained-auto")
        assertTrue(directory.renameTo(retained))
        directory.writeBytes(byteArrayOf(99)) // A file blocks the save directory.
        assertThrows(IllegalStateException::class.java) { storage.writeAutoSave(byteArrayOf(2),900_000) }
        assertTrue(directory.delete())
        assertTrue(retained.renameTo(directory))
        assertArrayEquals(byteArrayOf(1),storage.readAutoSave(storage.listAutoSaves().single()))
    }
}
