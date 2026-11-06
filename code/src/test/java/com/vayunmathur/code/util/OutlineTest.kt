package com.vayunmathur.code.util

import com.vayunmathur.code.syntax.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit tests for the pure per-language symbol extraction. */
class OutlineTest {

    @Test
    fun kotlinClassesFunctionsAndProperties() {
        val src = """
            package a.b

            class Foo {
                val bar = 1
                fun baz() {}
            }
        """.trimIndent()
        val symbols = extractSymbols(src, Language.KOTLIN)
        assertTrue(symbols.any { it.name == "Foo" && it.kind == SymbolKind.CLASS })
        assertTrue(symbols.any { it.name == "bar" && it.kind == SymbolKind.PROPERTY })
        assertTrue(symbols.any { it.name == "baz" && it.kind == SymbolKind.FUNCTION })
    }

    @Test
    fun kotlinFunctionLinesAreOneBased() {
        val src = "fun a() {}\nfun b() {}"
        val symbols = extractSymbols(src, Language.KOTLIN)
        assertEquals(1, symbols.first { it.name == "a" }.line)
        assertEquals(2, symbols.first { it.name == "b" }.line)
    }

    @Test
    fun kotlinFunctionSkipsReceiver() {
        val symbols = extractSymbols("fun String.shout() {}", Language.KOTLIN)
        assertTrue(symbols.any { it.name == "shout" && it.kind == SymbolKind.FUNCTION })
    }

    @Test
    fun pythonDefAndClass() {
        val src = "class C:\n    def method(self):\n        pass"
        val symbols = extractSymbols(src, Language.PYTHON)
        assertTrue(symbols.any { it.name == "C" && it.kind == SymbolKind.CLASS })
        assertTrue(symbols.any { it.name == "method" && it.kind == SymbolKind.FUNCTION })
    }

    @Test
    fun javascriptFunctionsClassesAndConsts() {
        val src = "export class A {}\nfunction go() {}\nconst x = 1"
        val symbols = extractSymbols(src, Language.JAVASCRIPT)
        assertTrue(symbols.any { it.name == "A" && it.kind == SymbolKind.CLASS })
        assertTrue(symbols.any { it.name == "go" && it.kind == SymbolKind.FUNCTION })
        assertTrue(symbols.any { it.name == "x" && it.kind == SymbolKind.PROPERTY })
    }

    @Test
    fun markdownHeadingsCarryLevelAsIndent() {
        val src = "# Title\n## Section\n### Sub"
        val symbols = extractSymbols(src, Language.MARKDOWN)
        assertEquals(SymbolKind.HEADING, symbols.first().kind)
        assertEquals("Title", symbols[0].name)
        assertEquals(0, symbols[0].indentDepth)
        assertEquals(1, symbols[1].indentDepth)
        assertEquals(2, symbols[2].indentDepth)
    }

    @Test
    fun jsonTopLevelKeysOnly() {
        val src = """
            {
              "name": "x",
              "nested": { "inner": 1 },
              "list": [1, 2]
            }
        """.trimIndent()
        val symbols = extractSymbols(src, Language.JSON)
        val names = symbols.map { it.name }
        assertTrue("name" in names)
        assertTrue("nested" in names)
        assertTrue("list" in names)
        assertTrue("inner" !in names) // nested key is skipped
    }

    @Test
    fun yamlTopLevelKeysOnly() {
        val src = "name: x\nnested:\n  inner: 1\nother: y"
        val symbols = extractSymbols(src, Language.YAML)
        val names = symbols.map { it.name }
        assertEquals(listOf("name", "nested", "other"), names)
    }

    @Test
    fun plaintextHasNoSymbols() {
        assertTrue(extractSymbols("just some text\nmore text", Language.PLAINTEXT).isEmpty())
    }

    @Test
    fun indentedMembersCarryNestingDepth() {
        val src = "class Foo {\n  fun bar() {}\n\t\tfun deep() {}\n}"
        val symbols = extractSymbols(src, Language.KOTLIN)
        val bar = symbols.single { it.name == "bar" }
        val deep = symbols.single { it.name == "deep" }
        assertEquals(1, bar.indentDepth) // two spaces
        assertEquals(2, deep.indentDepth) // two tabs
    }

    @Test
    fun goRustAndSwiftRulesFire() {
        assertTrue(
            extractSymbols("func main() {}", Language.GO)
                .any { it.name == "main" && it.kind == SymbolKind.FUNCTION },
        )
        assertTrue(
            extractSymbols("fn main() {}", Language.RUST)
                .any { it.name == "main" && it.kind == SymbolKind.FUNCTION },
        )
        assertTrue(
            extractSymbols("func greet() {}", Language.SWIFT)
                .any { it.name == "greet" && it.kind == SymbolKind.FUNCTION },
        )
    }
}
