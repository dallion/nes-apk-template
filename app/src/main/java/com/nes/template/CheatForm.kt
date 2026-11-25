package com.nes.template

import java.util.Locale

internal data class CheatChoice(val label: String, val numberRange: IntRange? = null)

internal data class CheatField(
    val index: Int,
    val label: String,
    val choices: Map<Int, CheatChoice>,
    val range: IntRange,
    val rangeSource: Int? = null,
) {
    fun display(value: Int) = choices[value]?.label ?: value.toString()
}

internal class CheatForm(
    val preset: CheatEntry,
    val fields: List<CheatField>,
    private val codeChoices: List<String> = emptyList(),
) {
    private val template = preset.code.split(' ')

    fun values(entry: CheatEntry): List<Int>? {
        if (entry.name != preset.name) return null
        if (codeChoices.isNotEmpty()) {
            return codeChoices.indexOf(entry.code).takeIf { it >= 0 }?.let(::listOf)
        }
        val tokens = entry.code.split(' ')
        if (tokens.size != template.size) return null
        for (index in tokens.indices) {
            if (fields.none { it.index == index } && tokens[index] != template[index]) return null
        }
        val values = fields.map { field ->
            val token = tokens[field.index]
            val prefix = template[field.index].substringBefore(':') + ":"
            if (!token.startsWith(prefix) || token.length != prefix.length + 2) return null
            val value = token.substring(prefix.length).toIntOrNull(16) ?: return null
            value
        }
        return values.takeIf { fields.indices.all { accepts(it, values) } }
    }

    fun numberRange(fieldIndex: Int, values: List<Int?>): IntRange {
        val field = fields[fieldIndex]
        val source = field.rangeSource ?: return field.range
        val sourceIndex = fields.indexOfFirst { it.index == source }
        return requireNotNull(fields[sourceIndex].choices[values[sourceIndex]]?.numberRange)
    }

    private fun accepts(fieldIndex: Int, values: List<Int>): Boolean {
        val field = fields[fieldIndex]
        val value = values[fieldIndex]
        if (field.choices.isNotEmpty()) return value in field.choices
        val source = field.rangeSource
        if (source != null) {
            val sourceIndex = fields.indexOfFirst { it.index == source }
            if (values[sourceIndex] !in fields[sourceIndex].choices) return false
        }
        return value in numberRange(fieldIndex, values)
    }

    fun code(values: List<Int>): String {
        require(values.size == fields.size)
        if (codeChoices.isNotEmpty()) {
            return requireNotNull(codeChoices.getOrNull(values.single())) { "无效的代码组选项" }
        }
        val tokens = template.toMutableList()
        fields.forEachIndexed { fieldIndex, field ->
            require(accepts(fieldIndex, values)) { "${field.label}的值无效" }
            tokens[field.index] = tokens[field.index].substringBefore(':') +
                String.format(Locale.ROOT, ":%02X", values[fieldIndex])
        }
        return tokens.joinToString(" ")
    }

    companion object {
        private const val PREFIX = ";@field="
        private const val GENIE_PREFIX = ";@genie="
        private val ramByte = Regex("[0-7][0-9A-F]{3}:[0-9A-F]{2}")

        fun parse(text: String, presets: List<CheatEntry>): List<CheatForm> {
            var section = ""
            val groups = mutableMapOf<String, MutableList<CheatField>>()
            val genieGroups = mutableMapOf<String, MutableList<List<String>>>()
            text.removePrefix("\uFEFF").lineSequence().map(String::trim).forEach { line ->
                if (line.startsWith('[') && line.endsWith(']')) {
                    section = line.substring(1, line.lastIndex).trim()
                }
                if (line.startsWith(PREFIX)) {
                    require(section.isNotBlank()) { "表单字段需要 CHT 分组" }
                    groups.getOrPut(section, ::mutableListOf).add(parseField(line.removePrefix(PREFIX)))
                }
                if (line.startsWith(GENIE_PREFIX)) {
                    require(section.isNotBlank()) { "Game Genie 选项需要 CHT 分组" }
                    val parts = line.removePrefix(GENIE_PREFIX).split('|', limit = 3).map(String::trim)
                    require(parts.size == 3 && parts.none(String::isEmpty)) { "Game Genie 选项格式错误" }
                    genieGroups.getOrPut(section, ::mutableListOf).add(parts)
                }
            }
            require(groups.keys.intersect(genieGroups.keys).isEmpty()) { "同一分组只能使用一种表单格式" }
            return groups.map { (name, fields) ->
                val preset = requireNotNull(presets.singleOrNull { it.name == name }) {
                    "表单分组需要唯一的 on 条目：$name"
                }
                val tokens = preset.code.split(' ')
                require(fields.map { it.index }.distinct().size == fields.size &&
                    fields.all { tokens.getOrNull(it.index)?.matches(ramByte) == true }) {
                    "表单字段需要不同的 RAM 字节位置：$name"
                }
                for (field in fields) {
                    val source = field.rangeSource ?: continue
                    val choices = fields.firstOrNull { it.index == source }?.choices.orEmpty()
                    require(choices.isNotEmpty() && choices.values.all { it.numberRange != null }) {
                        "数值字段需要引用已定义数值范围的下拉选项：${field.label}"
                    }
                }
                CheatForm(preset, fields).also {
                    require(it.values(preset) != null) { "表单默认值无效：$name" }
                }
            } + genieGroups.map { (name, options) ->
                val preset = requireNotNull(presets.singleOrNull { it.name == name }) {
                    "Game Genie 选项需要唯一的 on 条目：$name"
                }
                val label = options.first()[0]
                require(options.all { it[0] == label }) { "同一组 Game Genie 选项需要相同的字段名" }
                val choices = options.map { it[1] }
                require(choices.distinct().size == choices.size) { "Game Genie 选项名称重复" }
                val codes = options.map { CheatParser.genieGroup(it[2]) }
                require(codes.distinct().size == codes.size && preset.code in codes) {
                    "Game Genie 代码组需要各不相同，且包含 on 的默认代码组"
                }
                val field = CheatField(0, label, choices.withIndex().associate { (index, choice) ->
                    index to CheatChoice(choice)
                }, IntRange.EMPTY)
                CheatForm(preset, listOf(field), codes)
            }
        }

        private fun parseField(spec: String): CheatField {
            val parts = spec.split('|').map(String::trim)
            require(parts.size >= 4 && parts[2].isNotEmpty()) { "表单字段格式错误" }
            val position = parts[1].toIntOrNull()
            require(position != null && position > 0) { "字段位置必须是正整数" }
            return when (parts[0]) {
                "select" -> {
                    val choices = linkedMapOf<Int, CheatChoice>()
                    for (option in parts.drop(3)) {
                        val pair = option.split(':', limit = 2)
                        val spec = pair[0].split('@', limit = 2)
                        val value = spec[0].toIntOrNull(16)
                        require(value != null && value in 0..255 && pair.size == 2 && pair[1].isNotBlank())
                        val range = spec.getOrNull(1)?.let {
                            val limits = it.split("..", limit = 2)
                            require(limits.size == 2) { "选项范围需要最小值和最大值" }
                            byteRange(limits[0], limits[1])
                        }
                        require(choices.put(value, CheatChoice(pair[1].trim(), range)) == null) { "下拉选项值重复" }
                    }
                    CheatField(position - 1, parts[2], choices, IntRange.EMPTY)
                }
                "number" -> {
                    require(parts.size == 5) { "数值字段格式错误" }
                    if (parts[3] == "select") {
                        val source = parts[4].toIntOrNull()
                        require(source != null && source > 0) { "下拉字段位置必须是正整数" }
                        CheatField(position - 1, parts[2], emptyMap(), IntRange.EMPTY, source - 1)
                    } else {
                        CheatField(position - 1, parts[2], emptyMap(), byteRange(parts[3], parts[4]))
                    }
                }
                else -> throw IllegalArgumentException("未知表单字段类型：${parts[0]}")
            }
        }

        private fun byteRange(first: String, last: String): IntRange {
            val min = first.toIntOrNull()
            val max = last.toIntOrNull()
            require(min != null && max != null && min in 0..255 && max in min..255) { "字节范围无效" }
            return min..max
        }
    }
}
