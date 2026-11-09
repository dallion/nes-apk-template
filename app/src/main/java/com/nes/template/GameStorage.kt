package com.nes.template

import java.io.*
import java.security.MessageDigest

class GameStorage(root: File, rom: ByteArray, private val coreId: String = "fceumm-e0f3086") {
    val directory = File(root, sha256(rom).joinToString("") { "%02x".format(it) }).apply {
        check(isDirectory || mkdirs()) { "无法创建存档目录" }
    }
    fun readSram(): ByteArray? = read("sram.bin")
    fun writeSram(bytes: ByteArray) { if (bytes.isNotEmpty()) write("sram.bin", bytes) }
    fun readCheats(): ByteArray? = read("cheats.bin")
    fun writeCheats(bytes: ByteArray) = write("cheats.bin",bytes)
    fun writeState(bytes: ByteArray) {
        require(bytes.isNotEmpty()) { "内核未返回即时存档" }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeUTF("NESSTATE1")
            it.writeUTF(coreId)
            it.writeInt(bytes.size)
            it.write(sha256(bytes))
            it.write(bytes)
        }
        write("state.bin",out.toByteArray())
    }
    fun readState(): ByteArray? {
        val bytes = read("state.bin") ?: return null
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
