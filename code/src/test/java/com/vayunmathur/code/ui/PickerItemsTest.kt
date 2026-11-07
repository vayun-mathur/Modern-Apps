package com.vayunmathur.code.ui

import com.vayunmathur.code.util.CodeUiState
import com.vayunmathur.code.util.ProjectFileEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit tests for the pure quick-open item builders (no composition). */
class PickerItemsTest {

    private fun file(path: String, name: String, rel: String) =
        ProjectFileEntry(path = path, name = name, relativePath = rel)

    @Test
    fun quickOpenItemsMapEveryProjectFile() {
        val state = CodeUiState(
            projectFiles = listOf(
                file("/p/a.kt", "a.kt", "a.kt"),
                file("/p/src/b.kt", "b.kt", "src/b.kt"),
            ),
        )
        val items = quickOpenItems(state) {}
        assertEquals(2, items.size)
        assertEquals("a.kt", items[0].primary)
        assertEquals("a.kt", items[0].secondary)
        assertEquals("a.kt", items[0].matchKey)
        assertEquals("src/b.kt", items[1].secondary)
        assertEquals("src/b.kt", items[1].matchKey)
    }

    @Test
    fun quickOpenItemSelectOpensPath() {
        var opened: String? = null
        val state = CodeUiState(projectFiles = listOf(file("/p/a.kt", "a.kt", "a.kt")))
        quickOpenItems(state) { opened = it }.single().onSelect()
        assertEquals("/p/a.kt", opened)
    }

    @Test
    fun quickOpenItemsEmptyWhenNoFiles() {
        assertTrue(quickOpenItems(CodeUiState()) {}.isEmpty())
    }

    @Test
    fun recentOpenItemsMapRecentFiles() {
        val state = CodeUiState(recentFiles = listOf(file("/p/a.kt", "a.kt", "a.kt")))
        val items = recentOpenItems(state) {}
        assertEquals(1, items.size)
        assertEquals("a.kt", items[0].primary)
        var opened: String? = null
        recentOpenItems(state) { opened = it }.single().onSelect()
        assertEquals("/p/a.kt", opened)
    }

    @Test
    fun pickerItemDefaults() {
        var ran = false
        val item = PickerItem(primary = "Cmd") { ran = true }
        assertEquals("Cmd", item.primary)
        assertEquals(null, item.secondary)
        assertEquals("Cmd", item.matchKey)
        item.onSelect()
        assertTrue(ran)
        assertEquals(item, item.copy())
    }
}
