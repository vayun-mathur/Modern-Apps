package com.vayunmathur.securemail.domain.mail

import com.vayunmathur.securemail.domain.mail.PlainTextMarkup.Run
import kotlin.test.Test
import kotlin.test.assertEquals

class PlainTextMarkupTest {

    @Test
    fun protonStyleLink() {
        assertEquals(
            listOf(Run("See "), Run("the docs", "https://proton.me/support"), Run(" please")),
            PlainTextMarkup.parse("See [the docs](https://proton.me/support) please"),
        )
    }

    @Test
    fun unsafeSchemeStaysLiteral() {
        assertEquals(
            listOf(Run("[click](javascript:alert(1))")),
            PlainTextMarkup.parse("[click](javascript:alert(1))"),
        )
    }

    @Test
    fun boldItalicAndHeading() {
        assertEquals(
            listOf(Run("Title", bold = true), Run("\nis "), Run("big", bold = true), Run(" and "), Run("slanted", italic = true)),
            PlainTextMarkup.parse("# Title\nis **big** and *slanted*"),
        )
    }

    @Test
    fun bulletsAndLineBreaksPreserved() {
        assertEquals(
            listOf(Run("Items:\n\u2022 one\n  \u2022 two\n\nbye")),
            PlainTextMarkup.parse("Items:\n* one\n  - two\n\nbye"),
        )
    }

    @Test
    fun bareUrlTrailingPunctuationAndEmail() {
        assertEquals(
            listOf(
                Run("Go to "),
                Run("https://maemail.org/x", "https://maemail.org/x"),
                Run(". Mail "),
                Run("bob@proton.me", "mailto:bob@proton.me"),
            ),
            PlainTextMarkup.parse("Go to https://maemail.org/x. Mail bob@proton.me"),
        )
    }

    @Test
    fun snakeCaseAndMathAreNotItalic() {
        assertEquals(listOf(Run("my_var_name and 2*3*4")), PlainTextMarkup.parse("my_var_name and 2*3*4"))
    }
}
