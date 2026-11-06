package com.vayunmathur.maps.data.google

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1/3 (maps): [GoogleResponse] guard-stripping/parsing and the null-safe
 * positional accessors every Google scrape builds on.
 *
 * The whole point of these helpers is graceful degradation — a Google-side
 * reshape turns fields null instead of crashing the sheet — so the tests pin
 * the null paths as hard as the happy paths.
 */
class GoogleResponseSlice1Test {

    @Test
    fun `strip removes every known XSSI guard form`() {
        assertEquals("[1,2]", GoogleResponse.strip(")]}'\n[1,2]"))
        assertEquals("[1,2]", GoogleResponse.strip(")]}',\n[1,2]"))
        assertEquals("[1,2]", GoogleResponse.strip(")]}'[1,2]"))
        assertEquals("[1,2]", GoogleResponse.strip(")]}[1,2]"))
        assertEquals("[1,2]", GoogleResponse.strip("while(1);[1,2]"))
        assertEquals("[1,2]", GoogleResponse.strip("for(;;);[1,2]"))
    }

    @Test
    fun `strip leaves a guardless body alone`() {
        assertEquals("[1,2]", GoogleResponse.strip("[1,2]"))
        assertEquals("", GoogleResponse.strip(""))
    }

    @Test
    fun `parseOrNull parses a guarded body and rejects garbage`() {
        val el = GoogleResponse.parseOrNull(")]}'\n[[\"a\",1],null]")
        assertTrue(el is JsonArray, "expected an array, got $el")
        assertNull(GoogleResponse.parseOrNull(")]}'\nnot json {{{"))
        assertNull(GoogleResponse.parseOrNull(""))
    }

    @Test
    fun `at walks a positional path and degrades to null`() {
        val root = Json.parseToJsonElement("[[\"a\",[1,2]],{\"k\":1}]")
        assertEquals("a", root.at(0, 0).str())
        assertEquals(2, root.at(0, 1, 1).int())
        // Wrong step kind: object is not an array.
        assertNull(root.at(1, 0))
        // Out of range.
        assertNull(root.at(5))
        assertNull(root.at(0, 9))
        // Null receiver.
        val nil: kotlinx.serialization.json.JsonElement? = null
        assertNull(nil.at(0))
    }

    @Test
    fun `scalar accessors read primitives and refuse the wrong kinds`() {
        assertEquals("hi", JsonPrimitive("hi").str())
        assertEquals(3.5, JsonPrimitive(3.5).dbl())
        assertEquals(7, JsonPrimitive(7).int())
        assertEquals(9L, JsonPrimitive(9).long())
        // Lenient by design: a number where a string was expected coerces
        // rather than dropping the field (contentOrNull is string-friendly).
        assertEquals("3", JsonPrimitive(3).str())
        // A string is not a number.
        assertNull(JsonPrimitive("x").dbl())
        assertNull(JsonPrimitive("x").int())
        assertNull(JsonPrimitive("x").long())
        // Nulls and arrays read as nothing.
        assertNull(JsonNull.str())
        assertNull(JsonArray(emptyList()).str())
        assertNull(JsonArray(emptyList()).dbl())
    }

    @Test
    fun `arr keeps arrays and drops everything else`() {
        val arr = JsonArray(emptyList())
        assertEquals(arr, arr.arr())
        assertNull(JsonPrimitive(1).arr())
        assertNull(JsonNull.arr())
    }
}
