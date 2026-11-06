package com.vayunmathur.code.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM coverage for the language table ([Language.fromFileName],
 * labels, comment prefixes, tokenizer specs): every supported extension
 * resolves, unknown names fall back to plain text, and the languages with
 * tokenizers expose one.
 */
class LanguageTest {

    @Test
    fun extensionsResolveToTheirLanguage() {
        assertEquals(Language.KOTLIN, Language.fromFileName("Main.kt"))
        assertEquals(Language.KOTLIN, Language.fromFileName("build.kts"))
        assertEquals(Language.JAVA, Language.fromFileName("A.java"))
        assertEquals(Language.JAVASCRIPT, Language.fromFileName("app.jsx"))
        assertEquals(Language.TYPESCRIPT, Language.fromFileName("app.tsx"))
        assertEquals(Language.PYTHON, Language.fromFileName("s.py"))
        assertEquals(Language.CPP, Language.fromFileName("a.cpp"))
        assertEquals(Language.RUST, Language.fromFileName("lib.rs"))
        assertEquals(Language.GO, Language.fromFileName("main.go"))
        assertEquals(Language.JSON, Language.fromFileName("data.json"))
        assertEquals(Language.MARKDOWN, Language.fromFileName("README.md"))
        assertEquals(Language.DOCKERFILE, Language.fromFileName("Dockerfile"))
        assertEquals(Language.DOCKERFILE, Language.fromFileName("Dockerfile.prod"))
    }

    @Test
    fun unknownExtensionsFallBackToPlainText() {
        assertEquals(Language.PLAINTEXT, Language.fromFileName("notes.txt"))
        assertEquals(Language.PLAINTEXT, Language.fromFileName("Makefile"))
        assertEquals(Language.PLAINTEXT, Language.fromFileName("noext"))
        assertEquals(Language.PLAINTEXT, Language.fromFileName(".hidden"))
    }

    @Test
    fun labelsAndCommentPrefixesAreSet() {
        assertEquals("Kotlin", Language.KOTLIN.label)
        assertEquals("//", Language.KOTLIN.lineCommentPrefix)
        assertEquals("#", Language.PYTHON.lineCommentPrefix)
        assertEquals("--", Language.SQL.lineCommentPrefix)
        assertNull(Language.JSON.lineCommentPrefix)
        assertNull(Language.PLAINTEXT.lineCommentPrefix)
    }

    @Test
    fun braceLanguagesExposeATokenizer() {
        for (lang in listOf(Language.KOTLIN, Language.PYTHON, Language.JSON)) {
            val spec = lang.spec
            assertTrue(spec != null, "$lang")
            val kinds = spec!!.regex.findAll("x = 1 // hi").map { spec.kindFor(it) }.toList()
            assertTrue(kinds.isNotEmpty(), "$lang")
        }
        assertNull(Language.PLAINTEXT.spec)
    }
}
