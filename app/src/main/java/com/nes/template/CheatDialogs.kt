package com.nes.template

import android.app.Activity
import android.widget.Toast

class CheatDialogs(
    private val activity: Activity,
    private val book: CheatBook,
    private val menu: GameMenu,
    private val changed: () -> Unit,
    private val importFile: () -> Unit,
    private val back: () -> Unit,
) {
    private fun notify(text: String) = Toast.makeText(activity,text,Toast.LENGTH_LONG).show()

    fun show() {
        val entries = book.entries
        menu.show("金手指","已启用 ${entries.count { it.enabled }} / ${entries.size} 条",onBack=back) {
            pair("添加金手指" to { add() },"导入文件" to importFile)
            if(entries.isEmpty()) text("暂无金手指。支持 VCT、CHT、地址:数值和 Game Genie，新增及导入默认停用。")
            entries.forEachIndexed { index,entry ->
                action("${if(entry.enabled) "已启用" else "未启用"}  ${entry.name}") { detail(index) }
            }
            if(book.presets.isNotEmpty()) {
                section("内置预设")
                action("恢复内置金手指（${book.presets.size} 条）") {
                    runCatching { book.restorePresets(); changed() }
                        .onFailure { notify("恢复失败：${it.message}") }
                    show()
                }
            }
            action("返回") { back() }
        }
    }

    private fun detail(index: Int) {
        val entry = book.entries.getOrNull(index) ?: return
        val form = book.formFor(entry)
        menu.show(entry.name,onBack=::show) {
            if(form == null) {
                text(entry.code)
                text("停用会停止注入，不会回滚已经改变的游戏数值。")
            } else {
                text(form.fields.zip(requireNotNull(form.values(entry))).joinToString(" · ") { (field,value) ->
                    "${field.label} ${field.display(value)}"
                })
            }
            action(if(entry.enabled) "停用" else "启用",primary=true) {
                if(update(book.entries.toMutableList().apply { this[index] = entry.copy(enabled=!entry.enabled) })) show()
            }
            action("编辑") { edit(index) }
            action("删除") {
                if(update(book.entries.toMutableList().apply { removeAt(index) })) show()
            }
            action("返回") { show() }
        }
    }

    private fun update(entries: List<CheatEntry>): Boolean = runCatching {
        book.replace(entries)
        changed()
    }.fold({ true },{ notify("保存或应用失败：${it.message}"); false })

    private fun edit(index: Int) {
        val entry = book.entries.getOrNull(index) ?: return
        book.formFor(entry)?.let { form ->
            editForm(index,entry,form,requireNotNull(form.values(entry)).map(Int::toString))
            return
        }
        menu.show("编辑金手指","地址和值均为十六进制，多段代码共同保存。",onBack={ detail(index) }) {
            val name = field("名称").apply { setText(entry.name) }
            val code = field("代码",multiline=true).apply { setText(entry.code.replace(' ','\n')) }
            action("保存修改",primary=true) {
                val next = entry.copy(name=name.text.toString().trim(),
                    code=code.text.toString().trim().uppercase(java.util.Locale.ROOT)
                        .split(Regex("\\s+")).joinToString(" "))
                if(update(book.entries.toMutableList().apply { this[index] = next })) detail(index)
            }
            action("取消") { detail(index) }
        }
    }

    private fun editForm(index: Int, entry: CheatEntry, form: CheatForm, draft: List<String>) {
        menu.show("编辑金手指",entry.name,onBack={ detail(index) }) {
            val readValues = mutableListOf<() -> String>()
            form.fields.forEachIndexed { fieldIndex, field ->
                section(field.label)
                if(field.choices.isNotEmpty()) {
                    val selected = draft[fieldIndex].toInt()
                    action("${field.display(selected)} ▾") {
                        val current = readValues.map { it() }
                        menu.show(field.label,onBack={ editForm(index,entry,form,current) }) {
                            field.choices.forEach { (value,choice) ->
                                action(choice.label,primary=value==selected) {
                                    val next = current.toMutableList().apply { this[fieldIndex] = value.toString() }
                                    form.fields.forEachIndexed { dependentIndex, dependent ->
                                        if(dependent.rangeSource == field.index) {
                                            val range = form.numberRange(dependentIndex,next.map(String::toIntOrNull))
                                            val amount = next[dependentIndex].toIntOrNull()
                                            next[dependentIndex] = (amount?.coerceIn(range) ?: range.first).toString()
                                        }
                                    }
                                    editForm(index,entry,form,next)
                                }
                            }
                            action("返回") { editForm(index,entry,form,current) }
                        }
                    }
                    readValues.add { selected.toString() }
                } else {
                    val range = form.numberRange(fieldIndex,draft.map(String::toIntOrNull))
                    val input = field("${range.first}～${range.last}").apply {
                        inputType = android.text.InputType.TYPE_CLASS_NUMBER
                        setText(draft[fieldIndex])
                    }
                    readValues.add { input.text.toString() }
                }
            }
            action("保存修改",primary=true) {
                val draftValues = readValues.map { it().toIntOrNull() }
                val values = form.fields.mapIndexed { fieldIndex, field ->
                    val value = draftValues[fieldIndex]
                    val range = form.numberRange(fieldIndex,draftValues)
                    if(field.choices.isEmpty() && (value == null || value !in range)) {
                        notify("${field.label}需为 ${range.first}～${range.last}")
                        return@action
                    }
                    requireNotNull(value)
                }
                val next = entry.copy(code=form.code(values))
                if(update(book.entries.toMutableList().apply { this[index] = next })) detail(index)
            }
            action("取消") { detail(index) }
        }
    }

    private fun add() {
        menu.show("添加金手指","新增默认停用；批量输入每行一条，代码后可写名称。",onBack=::show) {
            val name = field("名称（单条代码可选）")
            val code = field("6800-01-63 / 6800:63 / SXIOPO",multiline=true)
            action("添加",primary=true) {
                runCatching {
                    val parsed = CheatParser.parse(code.text.toString())
                    val entries = if(parsed.size == 1 && name.text.isNotBlank())
                        listOf(parsed.single().copy(name=name.text.toString().trim())) else parsed
                    if(update(book.entries+entries)) show()
                }.onFailure { notify(it.message ?: "代码格式错误") }
            }
            action("取消") { show() }
        }
    }
}
