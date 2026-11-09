package com.nes.template

import java.io.*

class CheatBook(private val storage: GameStorage, presets: List<CheatEntry> = emptyList()) {
    val presets = presets.map { it.copy(enabled=false) }.also(CheatParser::validate)
    var entries: List<CheatEntry> = storage.readCheats()?.let(::decode) ?: this.presets
        private set

    fun restorePresets() {
        val missing = presets.filter { preset -> entries.none { it.code == preset.code && it.name == preset.name } }
        replace(entries+missing)
    }

    fun replace(next: List<CheatEntry>) {
        CheatParser.validate(next)
        storage.writeCheats(encode(next))
        entries = next.toList()
    }

    fun activeCodes(): List<String> = entries.filter { it.enabled }.flatMap { CheatParser.nativeCodes(it.code) }

    companion object {
        fun clear(storage: GameStorage, presets: List<CheatEntry> = emptyList()): CheatBook {
            storage.writeCheats(encode(emptyList()))
            return CheatBook(storage,presets)
        }
        private fun encode(entries: List<CheatEntry>): ByteArray {
            val buffer = ByteArrayOutputStream()
            DataOutputStream(buffer).use { out ->
                out.writeInt(0x4E434831) // NCH1
                out.writeInt(entries.size)
                entries.forEach { out.writeUTF(it.name); out.writeUTF(it.code); out.writeBoolean(it.enabled) }
            }
            return buffer.toByteArray()
        }
        private fun decode(bytes: ByteArray): List<CheatEntry> {
            require(bytes.size <= CheatParser.MAX_FILE_BYTES) { "金手指记录过大" }
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == 0x4E434831) { "金手指记录格式错误" }
                val count = input.readInt()
                require(count in 0..CheatParser.MAX_CODES) { "金手指条目数错误" }
                val result = List(count) { CheatEntry(input.readUTF(),input.readUTF(),input.readBoolean()) }
                require(input.available() == 0) { "金手指记录包含多余数据" }
                CheatParser.validate(result)
                result
            }
        }
    }
}
