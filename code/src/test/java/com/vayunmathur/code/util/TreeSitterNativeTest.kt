package com.vayunmathur.code.util

import com.vayunmathur.code.syntax.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for the tree-sitter bridge mapping (pure JVM; native call guarded). */
class TreeSitterNativeTest {

    @Test
    fun languageIdsMapForSupportedLanguages() {
        assertEquals("kotlin", TreeSitterNative.languageIdFor(Language.KOTLIN))
        assertEquals("java", TreeSitterNative.languageIdFor(Language.JAVA))
        assertEquals("javascript", TreeSitterNative.languageIdFor(Language.JAVASCRIPT))
        assertEquals("typescript", TreeSitterNative.languageIdFor(Language.TYPESCRIPT))
        assertEquals("python", TreeSitterNative.languageIdFor(Language.PYTHON))
        assertEquals("rust", TreeSitterNative.languageIdFor(Language.RUST))
        assertEquals("go", TreeSitterNative.languageIdFor(Language.GO))
        assertEquals("json", TreeSitterNative.languageIdFor(Language.JSON))
        assertEquals("c", TreeSitterNative.languageIdFor(Language.C))
        assertEquals("cpp", TreeSitterNative.languageIdFor(Language.CPP))
    }

    @Test
    fun unsupportedLanguagesMapToNull() {
        assertNull(TreeSitterNative.languageIdFor(Language.PLAINTEXT))
        assertNull(TreeSitterNative.languageIdFor(Language.MARKDOWN))
        assertNull(TreeSitterNative.languageIdFor(Language.YAML))
        assertNull(TreeSitterNative.languageIdFor(Language.SWIFT))
        assertNull(TreeSitterNative.languageIdFor(Language.XML))
    }

    @Test
    fun spansReturnsNullWhenUnsupportedOrUnavailable() {
        // Unsupported language: null without touching JNI.
        assertNull(TreeSitterNative.spans("x", Language.PLAINTEXT))
        // Supported language but (on JVM test) no native lib: null, not a crash.
        val supported = TreeSitterNative.spans("fun f() {}", Language.KOTLIN)
        if (!TreeSitterNative.isAvailable) assertNull(supported)
    }

    @Test
    fun tsKindHasExpectedEntries() {
        val names = TsKind.entries.map { it.name }
        assertTrue("KEYWORD" in names)
        assertTrue("STRING" in names)
        assertTrue("COMMENT" in names)
        assertTrue("FUNCTION" in names)
        assertTrue("TYPE" in names)
        assertEquals(TsKind.KEYWORD, TsKind.valueOf("KEYWORD"))
    }

    @Test
    fun tsSpanHoldsItsFields() {
        val s = TsSpan(1, 5, TsKind.STRING)
        assertEquals(1, s.start)
        assertEquals(5, s.end)
        assertEquals(TsKind.STRING, s.kind)
        assertEquals(s, s.copy())
    }
}
