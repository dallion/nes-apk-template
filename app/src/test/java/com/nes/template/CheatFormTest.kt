package com.nes.template

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CheatFormTest {
    @get:Rule val folder = TemporaryFolder()

    private val config = """
        [固定代码]
        on=SXIOPO
        [可选参数]
        ;@field=select|1|道具|54:赤心丹|6E:地图
        ;@field=number|3|数量|1|99
        on=0600,54,AA;0608,01
    """.trimIndent()

    @Test fun editedPresetReopensAndRestoresWithoutDuplicating() {
        val (presets, forms) = CheatParser.bundledConfiguration(config)
        val storage = GameStorage(folder.root, byteArrayOf(1))
        val book = CheatBook(storage, presets, forms)
        val form = requireNotNull(book.formFor(book.entries[1]))
        assertNull(book.formFor(book.entries[0]))
        assertEquals(listOf(0x54, 1), form.values(book.entries[1]))
        val edited = book.entries[1].copy(code = form.code(listOf(0x6E, 99)), enabled = true)
        book.replace(listOf(book.entries[0], edited))

        val reopened = CheatBook(storage, presets, forms)
        assertEquals(listOf(0x6E, 99), reopened.formFor(edited)?.values(edited))
        assertEquals(listOf("RAM:0600:6E", "RAM:0601:AA", "RAM:0608:63"), reopened.activeCodes())
        reopened.restorePresets()
        assertEquals(book.entries, reopened.entries)
        assertNull(reopened.formFor(edited.copy(code = "0610:6E 0601:AA 0608:63")))
    }

    @Test fun invalidBundledFieldsFailValidation() {
        for (invalid in listOf(
            config.replace("54:赤心丹|", ""),
            config.replace("number|3", "number|1"),
            config.replace("number|3", "number|4"),
        )) {
            assertThrows(IllegalArgumentException::class.java) { CheatParser.parseBundled(invalid) }
        }
    }

    @Test fun selectedOptionControlsTheQuantityRange() {
        val configured = config
            .replace("54:赤心丹|6E:地图", "54@1..255:赤心丹|6E@1..9:地图")
            .replace("数量|1|99", "数量|select|1")
        val form = CheatParser.bundledConfiguration(configured).second.single()
        val code = form.code(listOf(0x54, 255))
        assertEquals("0600:54 0601:AA 0608:FF", code)
        assertEquals(listOf(0x54, 255), form.values(form.preset.copy(code = code)))
        assertEquals(1..9, form.numberRange(1, listOf(0x6E, 1)))
        assertThrows(IllegalArgumentException::class.java) { form.code(listOf(0x6E, 255)) }
        assertNull(form.values(form.preset.copy(code = code.replace("0600:54", "0600:6E"))))
        assertThrows(IllegalArgumentException::class.java) {
            CheatParser.parseBundled(configured.replace("6E@1..9", "6E"))
        }
    }

    @Test fun genieChoiceReplacesAllCodesAndPersistsTheSelectedGroup() {
        val configured = """
            [战斗经验]
            ;@genie=倍率|2×|SXIOPO;EAZTLA
            ;@genie=倍率|3×|TYVXIYEE
            on=SXIOPO;EAZTLA
        """.trimIndent()
        val (presets, forms) = CheatParser.bundledConfiguration(configured)
        val storage = GameStorage(folder.root, byteArrayOf(2))
        val book = CheatBook(storage, presets, forms)
        val preset = book.entries.single()
        val form = requireNotNull(book.formFor(preset))
        assertEquals("2×", form.fields.single().display(form.values(preset)!!.single()))
        assertTrue(book.activeCodes().isEmpty())
        val edited = preset.copy(code = form.code(listOf(1)), enabled = true)
        book.replace(listOf(edited))
        assertEquals(listOf("TYVXIYEE"), book.activeCodes())

        val reopened = CheatBook(storage, presets, forms)
        reopened.restorePresets()
        assertEquals(listOf(edited), reopened.entries)
        assertEquals(listOf(1), reopened.formFor(edited)?.values(edited))
        assertNull(reopened.formFor(edited.copy(code = "TYVXIYEE SXIOPO")))
        for (invalid in listOf(
            configured.replace("3×|TYVXIYEE", "3×|6000,03"),
            configured.replace("3×|TYVXIYEE", "2×|TYVXIYEE"),
            configured.replace("3×|TYVXIYEE", "3×|SXIOPO;EAZTLA"),
            configured.replace("on=SXIOPO;EAZTLA", "on=EAZTLA"),
        )) {
            assertThrows(IllegalArgumentException::class.java) { CheatParser.parseBundled(invalid) }
        }
    }
}
