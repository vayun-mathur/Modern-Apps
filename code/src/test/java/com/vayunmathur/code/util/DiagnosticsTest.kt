package com.vayunmathur.code.util

import com.vayunmathur.code.syntax.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for the pure diagnostics validators. JSON is validated via `org.json`, which runs
 * for real under Robolectric (android-all) and is stubbed in plain JVM unit tests — hence the
 * runner: JSON assertions below execute against the real implementation.
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticsTest {

    @Test
    fun mergeConflictMarkersAreErrors() {
        val text = "a\n<<<<<<< HEAD\nx\n=======\ny\n>>>>>>> other\nb"
        val diags = computeDiagnostics(text, Language.PLAINTEXT)
        assertEquals(1, diags.count { it.severity == DiagnosticSeverity.ERROR })
        assertEquals(1, diags.first().line)
        assertTrue(diags.first().message.contains("merge"))
    }

    @Test
    fun todoAndFixmeAreInfo() {
        val text = "line one\n// TODO clean up\n// FIXME later"
        val diags = computeDiagnostics(text, Language.PLAINTEXT)
        val infos = diags.filter { it.severity == DiagnosticSeverity.INFO }
        assertEquals(listOf("TODO", "FIXME"), infos.map { it.message })
        assertEquals(listOf(1, 2), infos.map { it.line })
    }

    @Test
    fun yamlTabIndentationIsError() {
        val text = "root:\n\tchild: 1"
        val diags = computeDiagnostics(text, Language.YAML)
        val err = diags.single { it.severity == DiagnosticSeverity.ERROR }
        assertEquals(1, err.line)
        assertEquals(0, err.startCol)
    }

    @Test
    fun balancedBracketsProduceNoDiagnostics() {
        val diags = computeDiagnostics("fun f() { val a = x[0] }", Language.KOTLIN)
        assertTrue(diags.isEmpty(), "expected no diagnostics, got $diags")
    }

    @Test
    fun unmatchedCloserIsError() {
        val diags = computeDiagnostics("fun f() { )", Language.KOTLIN)
        assertTrue(diags.any { it.severity == DiagnosticSeverity.ERROR && it.message.contains(")") })
    }

    @Test
    fun unclosedOpenerIsWarning() {
        val diags = computeDiagnostics("fun f( {", Language.KOTLIN)
        assertTrue(diags.any { it.severity == DiagnosticSeverity.WARNING })
    }

    @Test
    fun bracketsInsideStringsAreIgnored() {
        val diags = computeDiagnostics("val s = \"a ( b [ c\"", Language.KOTLIN)
        assertTrue(diags.none { it.severity != DiagnosticSeverity.INFO }, "got $diags")
    }

    @Test
    fun multilineTripleQuotedStringsTrackLines() {
        // Triple-quoted string spanning lines: the bracket inside is ignored
        // and the line tracking advances past the string.
        val text = "val s = \"\"\"\na ( b\n\"\"\"\nval y = 2"
        val diags = computeDiagnostics(text, Language.KOTLIN)
        assertTrue(diags.none { it.severity == DiagnosticSeverity.ERROR }, "got $diags")
    }

    @Test
    fun charAndBacktickStringsAreSkipped() {
        val diags = computeDiagnostics("val c = '(' \nval t = `( [ `", Language.KOTLIN)
        assertTrue(diags.none { it.severity == DiagnosticSeverity.ERROR }, "got $diags")
    }

    @Test
    fun bracketsInsideLineCommentsAreIgnored() {
        val diags = computeDiagnostics("val x = 1 // )]}\n", Language.KOTLIN)
        assertTrue(diags.isEmpty(), "got $diags")
    }

    @Test
    fun bracketsInsideBlockCommentsAreIgnored() {
        val diags = computeDiagnostics("/* ( [ { */\nval y = 2", Language.KOTLIN)
        assertTrue(diags.isEmpty(), "got $diags")
    }

    @Test
    fun wellFormedXmlHasNoDiagnostics() {
        val diags = computeDiagnostics("<root><child/></root>", Language.XML)
        assertTrue(diags.isEmpty(), "got $diags")
    }

    @Test
    fun malformedXmlIsReported() {
        val diags = computeDiagnostics("<root><child></root>", Language.XML)
        assertTrue(diags.any { it.severity == DiagnosticSeverity.ERROR })
    }

    @Test
    fun htmlIsNotFlaggedAsMalformedXml() {
        val diags = computeDiagnostics("<!DOCTYPE html>\n<html><br></html>", Language.XML)
        assertTrue(diags.none { it.severity == DiagnosticSeverity.ERROR })
    }

    @Test
    fun malformedJsonPinpointsLineAndColumn() {
        // org.json runs for real under Robolectric (android-all), so the
        // offset in its message resolves through offsetToLineCol.
        val diags = computeDiagnostics("{\n  \"a\": 1,\n  \"b\": }", Language.JSON)
        val err = diags.single { it.severity == DiagnosticSeverity.ERROR }
        assertEquals(2, err.line)
        assertTrue(err.startCol >= 0)
        assertTrue(err.message.isNotBlank())
    }

    @Test
    fun malformedJsonPinpointsFirstLine() {
        // org.json is lenient about trailing commas, but truncation and
        // unterminated values fail with a character offset for offsetToLineCol.
        val truncated = computeDiagnostics("[", Language.JSON).single { it.severity == DiagnosticSeverity.ERROR }
        assertEquals(0, truncated.line)
        val unterminated = computeDiagnostics("\"", Language.JSON).single { it.severity == DiagnosticSeverity.ERROR }
        assertTrue(unterminated.message.isNotBlank())
    }

    @Test
    fun wellFormedJsonHasNoDiagnostics() {
        assertTrue(computeDiagnostics("{\"a\": [1, 2]}", Language.JSON).isEmpty())
        assertTrue(computeDiagnostics("", Language.JSON).isEmpty())
        assertTrue(computeDiagnostics("   ", Language.JSON).isEmpty())
    }

    @Test
    fun diagnosticHoldsItsRange() {
        val d = Diagnostic(line = 3, startCol = 1, endCol = 5, severity = DiagnosticSeverity.WARNING, message = "m")
        assertEquals(3, d.line)
        assertEquals(1, d.startCol)
        assertEquals(5, d.endCol)
        assertEquals(d, d.copy())
    }
}
