package com.vayunmathur.code.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tokenizer coverage across every language spec: keywords, strings, comments, numbers. */
class SyntaxSpecsTest {

    private fun kinds(lang: Language, text: String): List<TokenKind?> {
        val spec = lang.spec ?: return emptyList()
        return spec.regex.findAll(text).map { spec.kindFor(it) }.toList()
    }

    @Test
    fun everyNonPlaintextLanguageHasASpec() {
        for (lang in Language.entries) {
            if (lang == Language.PLAINTEXT) assertNull(lang.spec)
            else assertNotNull(lang.spec, "$lang")
        }
    }

    @Test
    fun kotlinJvmHighlightsKeywordsStringsCommentsNumbersAnnotations() {
        val k = kinds(Language.KOTLIN, "@Override fun f() = \"s\" // c\nval n = 42")
        assertTrue(TokenKind.ANNOTATION in k)
        assertTrue(TokenKind.KEYWORD in k)
        assertTrue(TokenKind.STRING in k)
        assertTrue(TokenKind.COMMENT in k)
        assertTrue(TokenKind.NUMBER in k)
    }

    @Test
    fun tripleQuotedStringIsOneStringToken() {
        val k = kinds(Language.KOTLIN, "val s = \"\"\"a\nb\"\"\"")
        assertTrue(TokenKind.STRING in k)
    }

    @Test
    fun blockCommentSwallowsKeywords() {
        val matches = Language.KOTLIN.spec!!.regex.findAll("/* fun class */ val x = 1").toList()
        val first = matches.first()
        assertEquals(TokenKind.COMMENT, Language.KOTLIN.spec!!.kindFor(first))
        assertTrue(first.value.contains("fun"))
    }

    @Test
    fun javaScriptHighlightsBacktickAndKeywords() {
        val k = kinds(Language.JAVASCRIPT, "const t = `hi \${x}` // c\nlet n = 0x1F")
        assertTrue(TokenKind.KEYWORD in k)
        assertTrue(TokenKind.STRING in k)
        assertTrue(TokenKind.COMMENT in k)
        assertTrue(TokenKind.NUMBER in k)
        assertTrue(TokenKind.KEYWORD in kinds(Language.TYPESCRIPT, "function f(): void {}"))
    }

    @Test
    fun pythonHighlightsDefHashCommentAndTripleSingle() {
        val k = kinds(Language.PYTHON, "def f():\n    # comment\n    s = '''x'''\n    return 3.14")
        assertTrue(TokenKind.KEYWORD in k)
        assertTrue(TokenKind.COMMENT in k)
        assertTrue(TokenKind.STRING in k)
        assertTrue(TokenKind.NUMBER in k)
    }

    @Test
    fun cHighlightsPreprocessorAndKeywords() {
        val k = kinds(Language.C, "#include <stdio.h>\nint main() { return 0; } // c")
        assertTrue(TokenKind.ANNOTATION in k)
        assertTrue(TokenKind.KEYWORD in k)
        assertTrue(TokenKind.NUMBER in k)
        assertTrue(TokenKind.KEYWORD in kinds(Language.CPP, "class A { public: void f(); };"))
    }

    @Test
    fun rustHighlightsAttributeFnAndLifetimeFreeTokens() {
        val k = kinds(Language.RUST, "#[derive(Debug)] fn main() { let x = 1; } // c")
        assertTrue(TokenKind.ANNOTATION in k)
        assertTrue(TokenKind.KEYWORD in k)
        assertTrue(TokenKind.NUMBER in k)
    }

    @Test
    fun jsonHighlightsStringsKeywordsNumbers() {
        val k = kinds(Language.JSON, "{\"a\": true, \"n\": 12}")
        assertTrue(TokenKind.STRING in k)
        assertTrue(TokenKind.KEYWORD in k)
        assertTrue(TokenKind.NUMBER in k)
    }

    @Test
    fun xmlHighlightsCommentsTagsEntities() {
        val k = kinds(Language.XML, "<!-- c --><a href=\"x\">&amp; text</a>")
        assertTrue(TokenKind.COMMENT in k)
        assertTrue(TokenKind.STRING in k)
        assertTrue(TokenKind.KEYWORD in k)
        assertTrue(TokenKind.NUMBER in k)
    }

    @Test
    fun markdownHighlightsHeadingCodeBoldQuoteLink() {
        val text = "# Title\n`code`\n**bold**\n> quote\n[t](http://x)"
        val k = kinds(Language.MARKDOWN, text)
        assertTrue(TokenKind.KEYWORD in k)
        assertTrue(TokenKind.STRING in k)
        assertTrue(TokenKind.ANNOTATION in k)
        assertTrue(TokenKind.COMMENT in k)
        assertTrue(TokenKind.NUMBER in k)
    }

    @Test
    fun goSwiftRubyPhpHighlights() {
        assertTrue(TokenKind.KEYWORD in kinds(Language.GO, "package main\nfunc f() {}"))
        assertTrue(TokenKind.STRING in kinds(Language.GO, "var s = `raw`"))
        assertTrue(TokenKind.KEYWORD in kinds(Language.SWIFT, "func greet() -> String { return \"hi\" }"))
        assertTrue(TokenKind.KEYWORD in kinds(Language.RUBY, "def f\n  # c\n  puts \"hi\"\nend"))
        assertTrue(TokenKind.COMMENT in kinds(Language.RUBY, "# comment"))
        val php = kinds(Language.PHP, "<?php \$x = 1; function f() {} // c")
        assertTrue(TokenKind.ANNOTATION in php) // $x variable
        assertTrue(TokenKind.KEYWORD in php)
    }

    @Test
    fun yamlTomlShellHighlights() {
        val yaml = kinds(Language.YAML, "key: \"v\" # c\nn: 42")
        assertTrue(TokenKind.STRING in yaml)
        assertTrue(TokenKind.COMMENT in yaml)
        assertTrue(TokenKind.NUMBER in yaml)
        assertTrue(TokenKind.KEYWORD in kinds(Language.YAML, "flag: true"))
        val toml = kinds(Language.TOML, "# c\ntitle = \"x\"\nn = 7")
        assertTrue(TokenKind.COMMENT in toml)
        assertTrue(TokenKind.STRING in toml)
        assertTrue(TokenKind.NUMBER in toml)
        val sh = kinds(Language.SHELL, "# c\necho \$HOME \${x}\nif true; then\n  n=42\nfi")
        assertTrue(TokenKind.COMMENT in sh)
        assertTrue(TokenKind.ANNOTATION in sh)
        assertTrue(TokenKind.KEYWORD in sh)
    }

    @Test
    fun cssSqlDockerfileGradleHighlights() {
        val css = kinds(Language.CSS, "/* c */\n.a { color: #fff; width: 12px; }")
        assertTrue(TokenKind.COMMENT in css)
        assertTrue(TokenKind.NUMBER in css)
        assertTrue(TokenKind.ANNOTATION in kinds(Language.CSS, "@media x { }"))
        val sql = kinds(Language.SQL, "SELECT * FROM t WHERE x = 1; -- c")
        assertTrue(TokenKind.KEYWORD in sql)
        assertTrue(TokenKind.NUMBER in sql)
        assertTrue(TokenKind.COMMENT in sql)
        assertTrue(TokenKind.KEYWORD in kinds(Language.SQL, "select 1"))
        assertTrue(TokenKind.KEYWORD in kinds(Language.DOCKERFILE, "FROM ubuntu\nRUN echo hi # c"))
        assertTrue(TokenKind.KEYWORD in kinds(Language.DOCKERFILE, "from ubuntu"))
        assertTrue(TokenKind.KEYWORD in kinds(Language.GRADLE, "class Foo implements Bar { def x = 1 } // c"))
    }

    @Test
    fun kindForReturnsNullWhenNoGroupMatches() {
        val spec = LanguageSpec(listOf(TokenKind.KEYWORD to "zzz"))
        val m = spec.regex.find("zzz")!!
        assertEquals(TokenKind.KEYWORD, spec.kindFor(m))
    }

    @Test
    fun languageSpecKindForFirstNonNullGroup() {
        val spec = LanguageSpec(listOf(TokenKind.COMMENT to "//.*", TokenKind.KEYWORD to "fun"))
        val comment = spec.regex.find("// fun")!!
        assertEquals(TokenKind.COMMENT, spec.kindFor(comment))
    }
}
