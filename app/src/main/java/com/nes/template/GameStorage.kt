package com.nes.template

import java.io.*
import java.security.MessageDigest
import java.util.UUID

class GameStorage(root: File, rom: ByteArray, private val coreId: String = "fceumm-e0f3086") {
    val directory = File(root, sha256(rom).joinToString("") { "%02x".format(it) }).apply {
        check(isDirectory || mkdirs()) { "无法创建存档目录" }
    }
    fun readSram(): ByteArray? = read("sram.bin")
    fun writeSram(bytes: ByteArray) { if (bytes.isNotEmpty()) write("sram.bin", bytes) }
    fun readCheats(): ByteArray? = read("cheats.bin")
    fun writeCheats(bytes: ByteArray) = write("cheats.bin",bytes)
    fun writeState(bytes: ByteArray) = write("state.bin", encodeState(bytes))
    private fun encodeState(bytes: ByteArray): ByteArray {
        require(bytes.isNotEmpty()) { "内核未返回即时存档" }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeUTF("NESSTATE1")
            it.writeUTF(coreId)
            it.writeInt(bytes.size)
            it.write(sha256(bytes))
            it.write(bytes)
        }
        return out.toByteArray()
    }
    fun readState(): ByteArray? = read("state.bin")?.let(::decodeState)
    private fun decodeState(bytes: ByteArray): ByteArray? {
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use {
                if (it.readUTF() != "NESSTATE1" || it.readUTF() != coreId) return null
                val length = it.readInt()
                if (length <= 0 || length > 16 * 1024 * 1024 || it.available() != length + 32) return null
                val hash = ByteArray(32).also(it::readFully)
                val state = ByteArray(length).also(it::readFully)
                if (MessageDigest.isEqual(hash,sha256(state))) state else null
            }
        } catch (_: IOException) { null }
    }
    data class AutoSave(val timestamp: Long, internal val name: String)
    private val autoSaveName = Regex("([0-9]+)-[a-f0-9-]+\\.bin")
    private fun autoSaveFiles(): List<AutoSave> = File(directory,"auto").listFiles().orEmpty()
        .map { it.name.removeSuffix(".bak") }.distinct().mapNotNull { name ->
            val match = autoSaveName.matchEntire(name) ?: return@mapNotNull null
            val timestamp = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            AutoSave(timestamp,name)
        }.sortedByDescending { it.timestamp }

    @Synchronized fun writeAutoSave(bytes: ByteArray, timestamp: Long = System.currentTimeMillis()) {
        require(timestamp >= 0)
        val encoded = encodeState(bytes)
        val folder = File(directory,"auto")
        check(folder.isDirectory || folder.mkdirs()) { "无法创建自动存档目录" }
        val name = "$timestamp-${UUID.randomUUID()}.bin"
        write("auto/$name",encoded)
        // Commit the new recovery point before retiring any old one.
        autoSaveFiles().sortedWith(compareByDescending<AutoSave> { it.name == name }
            .thenByDescending { it.timestamp }).forEachIndexed { index, entry ->
            if(index >= 20 || entry.timestamp < timestamp - 10 * 60_000L || entry.timestamp > timestamp) {
                for(suffix in listOf("", ".bak", ".tmp")) {
                    val file = File(folder,entry.name+suffix)
                    check(!file.exists() || file.delete()) { "无法清理旧自动存档" }
                }
            }
        }
        folder.listFiles().orEmpty().filter { it.name.endsWith(".tmp") &&
            autoSaveName.matches(it.name.removeSuffix(".tmp")) }.forEach {
            check(it.delete()) { "无法清理未完成的自动存档" }
        }
    }
    @Synchronized fun listAutoSaves(): List<AutoSave> = autoSaveFiles().filter {
        readAutoSave(it) != null
    }
    @Synchronized fun readAutoSave(entry: AutoSave): ByteArray? {
        require(autoSaveName.matches(entry.name))
        return read("auto/${entry.name}")?.let(::decodeState)
    }
    private fun read(name: String): ByteArray? {
        val target = File(directory,name)
        val backup = File(directory,"$name.bak")
        // A surviving backup means the previous replacement did not commit.
        if (backup.exists()) { target.delete(); check(backup.renameTo(target)) }
        return if (target.isFile) target.readBytes() else null
    }
    private fun write(name: String, bytes: ByteArray) {
        val target = File(directory,name)
        val temporary = File(directory,"$name.tmp")
        val backup = File(directory,"$name.bak")
        read(name) // Recover any interrupted prior write first.
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        if (target.exists()) check(target.renameTo(backup)) { "无法备份旧存档" }
        if (!temporary.renameTo(target)) {
            if (backup.exists()) backup.renameTo(target)
            error("无法替换存档")
        }
        check(!backup.exists() || backup.delete()) { "无法完成存档写入" }
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}
