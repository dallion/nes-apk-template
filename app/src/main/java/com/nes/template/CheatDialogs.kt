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
        menu.show(entry.name,onBack=::show) {
            text(entry.code)
            text("停用会停止注入，不会回滚已经改变的游戏数值。")
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
