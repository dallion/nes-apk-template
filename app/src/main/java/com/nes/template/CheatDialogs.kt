package com.nes.template

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast

class CheatDialogs(
    private val activity: Activity,
    private val book: CheatBook,
    private val display: (AlertDialog) -> Unit,
    private val changed: () -> Unit,
    private val importFile: () -> Unit,
) {
    private fun later(action: () -> Unit) { activity.window.decorView.post(action) }
    private fun notify(text: String) = Toast.makeText(activity,text,Toast.LENGTH_LONG).show()

    fun show() {
        val entries = book.entries
        val builder = AlertDialog.Builder(activity).setTitle("金手指（${entries.count { it.enabled }}/${entries.size}）")
            .setPositiveButton("添加") { _,_ -> later(::add) }
            .setNeutralButton("导入文件") { _,_ -> later(importFile) }
            .setNegativeButton("返回",null)
        val restore = if(book.presets.isEmpty()) emptyList() else listOf("恢复内置金手指（${book.presets.size}条）")
        if(entries.isEmpty() && restore.isEmpty()) builder.setMessage("暂无金手指。支持 VCT、CHT、地址:数值和 Game Genie。新增及导入默认停用。")
        else builder.setItems((restore+entries.map { "${if(it.enabled) "[开]" else "[关]"} ${it.name}" }).toTypedArray()) { _,index ->
            later {
                if(restore.isNotEmpty() && index == 0) {
                    runCatching { book.restorePresets(); changed() }
                        .onFailure { notify("恢复失败：${it.message}") }
                    show()
                } else detail(index-restore.size)
            }
        }
        display(builder.create())
    }

    private fun detail(index: Int) {
        val entry = book.entries.getOrNull(index) ?: return
        display(AlertDialog.Builder(activity).setTitle(entry.name)
            .setMessage("${entry.code}\n\n停用会停止注入，不会回滚已经改变的游戏数值。")
            .setPositiveButton(if(entry.enabled) "停用" else "启用") { _,_ ->
                update(book.entries.toMutableList().apply { this[index] = entry.copy(enabled=!entry.enabled) })
                later(::show)
            }
            .setNeutralButton("删除") { _,_ ->
                update(book.entries.toMutableList().apply { removeAt(index) })
                later(::show)
            }
            .setNegativeButton("返回") { _,_ -> later(::show) }.create())
    }

    private fun update(entries: List<CheatEntry>): Boolean = runCatching {
        book.replace(entries)
        changed()
    }.fold({ true },{ notify("保存或应用失败：${it.message}"); false })

    private fun add() {
        val padding = (16*activity.resources.displayMetrics.density).toInt()
        val name = EditText(activity).apply { hint = "名称（单条代码可选）"; setSingleLine() }
        val code = EditText(activity).apply {
            hint = "6800-01-63 / 6800:63 / SXIOPO\n可粘贴多行，每行一条，代码后可写名称"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 3
            maxLines = 8
        }
        val fields = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding,0,padding,0)
            addView(name)
            addView(code)
        }
        val dialog = AlertDialog.Builder(activity).setTitle("添加金手指（默认停用）")
            .setView(fields).setPositiveButton("添加",null)
            .setNegativeButton("取消") { _,_ -> later(::show) }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    val parsed = CheatParser.parse(code.text.toString())
                    val entries = if(parsed.size == 1 && name.text.isNotBlank())
                        listOf(parsed.single().copy(name=name.text.toString().trim())) else parsed
                    if(update(book.entries+entries)) {
                        dialog.dismiss()
                        later(::show)
                    }
                }.onFailure { notify(it.message ?: "代码格式错误") }
            }
        }
        display(dialog)
    }
}
