package com.vayunmathur.library.util

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** NavBackStack behaviour and DataStoreUtils typed accessors. */
class LibraryStateTest {

    @Test
    fun navBackStackAddPopSetReset() {
        val stack = NavBackStack(arrayOf(TestKey("home")))
        assertEquals("home", stack.last().route)
        stack.add(TestKey("detail"))
        assertEquals(2, stack.backStack.size)
        assertEquals("detail", stack.last().route)
        stack.setLast(TestKey("detail2"))
        assertEquals("detail2", stack.last().route)
        stack.set(0, TestKey("root"))
        assertEquals("root", stack.backStack[0].route)
        stack.pop()
        assertEquals("root", stack.last().route)
        stack.pop()
        // Popping an empty stack is a no-op, not a crash.
        stack.pop()
        assertTrue(stack.backStack.isEmpty())
        stack.reset(TestKey("a"), TestKey("b"))
        assertEquals(listOf("a", "b"), stack.backStack.map { it.route })
        stack.clear()
        assertTrue(stack.backStack.isEmpty())
    }

    private fun newStore(): DataStoreUtils {
        val dir = Files.createTempDirectory("ds-test").toFile()
        val file = File(dir, "test.preferences_pb")
        return DataStoreUtils(PreferenceDataStoreFactory.create { file })
    }

    @Test
    fun dataStoreTypedRoundTrips() = runBlocking {
        val store = newStore()
        assertNull(store.getString("missing"))
        assertNull(store.getLong("missing"))
        assertFalse(store.getBoolean("missing", false))
        store.setString("name", "ada")
        assertEquals("ada", store.getStringAwait("name"))
        store.setString("name", "grace", onlyIfAbsent = true)
        assertEquals("ada", store.getStringAwait("name"))
        store.updateString("name") { (it ?: "") + "!" }
        assertEquals("ada!", store.getStringAwait("name"))
        store.setLong("count", 41)
        assertEquals(41L, store.getLong("count"))
        assertTrue(store.setLongIfGreater("count", 42))
        assertFalse(store.setLongIfGreater("count", 40))
        assertEquals(42L, store.getLong("count"))
        store.setDouble("ratio", 2.5)
        assertEquals(2.5, store.doubleFlow("ratio").first())
        store.setBoolean("flag", true)
        assertTrue(store.getBooleanAwait("flag"))
        assertTrue(store.booleanFlow("flag").first())
        assertEquals("ada!", store.stringFlow("name").first())
        store.setByteArray("blob", byteArrayOf(1, 2, 3))
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(store.getByteArrayAwait("blob")!!))
    }

    @Test
    fun dataStoreStringSetsAndRemove() = runBlocking {
        val store = newStore()
        assertEquals(emptySet(), store.getStringSetAwait("tags"))
        assertTrue(store.addStringToSetIfAbsent("tags", "a"))
        assertTrue(store.addStringToSetIfAbsent("tags", "b"))
        assertFalse(store.addStringToSetIfAbsent("tags", "a"))
        assertEquals(setOf("a", "b"), store.getStringSetAwait("tags"))
        assertEquals(setOf("a", "b"), store.stringSetFlow("tags").first())
        store.removeKeys(listOf("tags"))
        assertEquals(emptySet(), store.getStringSetAwait("tags"))
    }
}

@Serializable
private data class TestKey(val route: String) : NavKey
