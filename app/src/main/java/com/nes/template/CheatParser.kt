package com.nes.template

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

data class CheatEntry(val name: String, val code: String, val enabled: Boolean = false)

object CheatParser {
    const val MAX_CODES = 256
    const val MAX_FILE_BYTES = 128*1024
    private val hex = "[0-9A-F]"
    private val vct = Regex("^($hex{4})-($hex{1,2})-($hex{1,8})$")
    private val raw = Regex("^($hex{4}):($hex{2})$")
    private val genie = Regex("^[APZLGITYEOXUKSVN]{6}([APZLGITYEOXUKSVN]{2})?$")

    internal fun genieGroup(text: String): String {
        val tokens = text.split(';').map { it.trim().uppercase(Locale.ROOT) }
        require(tokens.size <= MAX_CODES && tokens.all(genie::matches)) { "无效的 Game Genie 代码组" }
        return tokens.joinToString(" ")
    }

    fun nativeCodes(code: String): List<String> {
        require(code.length <= MAX_FILE_BYTES) { "代码文本过长" }
        val tokens = code.trim().split(Regex("\\s+"))
        require(tokens.size <= MAX_CODES) { "分组代码过多" }
        return tokens.flatMap(::nativeToken)
    }

    private fun nativeToken(code: String): List<String> {
        val value = code.trim().uppercase(Locale.ROOT)
        vct.matchEntire(value)?.let {
            val address = it.groupValues[1].toInt(16)
            val length = it.groupValues[2].toInt(16)
            val number = it.groupValues[3].toLong(16)
            require(length in 1..4) { "VCT 字节数必须为 1～4" }
            require(address+length <= 0x8000) { "VCT 持续写入仅支持 RAM 地址，不能越过 7FFF" }
            require(number < (1L shl (8*length))) { "数值超过指定字节数" }
            return List(length) { offset ->
                String.format(Locale.ROOT,"RAM:%04X:%02X",address+offset,(number shr (offset*8)) and 255)
            }
        }
        raw.matchEntire(value)?.let {
            return listOf(if(it.groupValues[1].toInt(16) < 0x8000) "RAM:$value" else value)
        }
        require(genie.matches(value)) { "支持 AAAA-L-VV、AAAA:VV 或六/八位 Game Genie 代码" }
        return listOf(value)
    }

    fun parse(text: String): List<CheatEntry> {
        require(text.length <= MAX_FILE_BYTES) { "代码文本过长" }
        val clean = text.removePrefix("\uFEFF")
        val lines = clean.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith(';') && !it.startsWith("//") }.toList()
        if(lines.firstOrNull()?.startsWith('[') == true) return parseCht(lines)
        val result = mutableListOf<CheatEntry>()
        text.removePrefix("\uFEFF").lineSequence().forEachIndexed { index,line ->
            val trimmed = line.trim()
            if(trimmed.isEmpty() || trimmed.startsWith(';') || trimmed.startsWith("//")) return@forEachIndexed
            val content = trimmed.replaceFirst(Regex("^#\\d+\\s+"),"")
            val parts = content.split(Regex("\\s+"),limit=2)
            val code = parts[0].uppercase(Locale.ROOT)
            try { nativeCodes(code) } catch(error: IllegalArgumentException) {
                throw IllegalArgumentException("第 ${index+1} 行：${error.message}")
            }
            val name = parts.getOrNull(1)?.trim()?.removePrefix(";")?.trim().orEmpty().ifEmpty { code }
            result.add(CheatEntry(name,code))
        }
        require(result.isNotEmpty()) { "没有找到有效代码" }
        validate(result)
        return result
    }

    private fun parseCht(lines: List<String>): List<CheatEntry> {
        val result = mutableListOf<CheatEntry>()
        var section: String? = null
        var hasValue = false
        lines.forEach { line ->
            if(line.startsWith('[')) {
                require(section == null || hasValue) { "空的金手指分组：$section" }
                require(line.endsWith(']') && line.length > 2) { "金手指分组标题错误" }
                section = line.substring(1,line.length-1).trim()
                hasValue = false
            } else {
                val title = requireNotNull(section) { "代码前需要 [名称]" }
                val pair = line.split('=',limit=2)
                require(pair.size == 2 && pair[0].isNotBlank()) { "需要 on=地址,字节 或 on=Game Genie 格式" }
                val codes = pair[1].split(';').flatMap { segment ->
                    val token = segment.trim().uppercase(Locale.ROOT)
                    if(genie.matches(token)) return@flatMap listOf(token)
                    val parts = segment.split(',').map { it.trim() }
                    require(parts.size >= 2 && parts[0].matches(Regex("[0-9A-Fa-f]{1,4}"))) { "地址格式错误" }
                    val address = parts[0].toInt(16)
                    require(address+parts.size-1 <= 0x8000) { "分组写入越过 RAM 边界" }
                    parts.drop(1).mapIndexed { offset,byte ->
                        require(byte.matches(Regex("[0-9A-Fa-f]{2}"))) { "字节必须为两位十六进制" }
                        String.format(Locale.ROOT,"%04X:%s",address+offset,byte.uppercase(Locale.ROOT))
                    }
                }
                val name = if(pair[0].trim().equals("on",true)) title else "$title / ${pair[0].trim()}"
                result.add(CheatEntry(name,codes.joinToString(" ")))
                hasValue = true
            }
        }
        require(hasValue) { "空的金手指分组：$section" }
        validate(result)
        return result
    }

    fun parseBundled(text: String): List<CheatEntry> = bundledConfiguration(text).first

    internal fun bundledConfiguration(text: String): Pair<List<CheatEntry>, List<CheatForm>> {
        require(text.length <= MAX_FILE_BYTES) { "代码文本过长" }
        val hasCode = text.removePrefix("\uFEFF").lineSequence().any {
            val line = it.trim()
            line.isNotEmpty() && !line.startsWith(';') && !line.startsWith("//")
        }
        val entries = if(hasCode) parse(text) else emptyList()
        return entries to CheatForm.parse(text, entries)
    }

    fun validate(entries: List<CheatEntry>) {
        require(entries.size <= MAX_CODES) { "金手指条目过多" }
        var count = 0
        entries.forEach {
            require(it.name.isNotBlank() && it.name.length <= 80) { "名称需为 1～80 个字符" }
            count += nativeCodes(it.code).size
        }
        require(count <= MAX_CODES) { "展开后代码总数不能超过 $MAX_CODES 条" }
    }

    fun decode(bytes: ByteArray): String {
        require(bytes.size <= MAX_FILE_BYTES) { "文件不能超过 128 KiB" }
        fun decodeWith(name: String) = charset(name).newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        return try { decodeWith("UTF-8") } catch(_: java.nio.charset.CharacterCodingException) { decodeWith("GB18030") }
    }
}
