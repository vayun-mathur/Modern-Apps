package com.vayunmathur.office.data.odf

import com.vayunmathur.library.ui.odf.OdfNumberFormat
import com.vayunmathur.library.ui.odf.OdfNumberToken

/**
 * Converts Excel number-format codes (builtin ids 0-49 and custom format strings) to the ODF
 * [OdfNumberFormat] model (Phases C2/X2). Best-effort: covers decimals, thousands grouping,
 * percent, currency, scientific, fraction, and date/time token lists.
 */
internal object ExcelNumFmt {

    private val DATE_BUILTINS = 14..22
    private val TIME_BUILTINS = 45..47
    private val LOCALE_DATE_BUILTINS = 27..36
    private val CJK_BUILTINS = 50..58
    private const val LONG_RUN = 4
    private const val MEDIUM_RUN = 3
    private const val SHORT_RUN = 2
    private const val AMPM_LENGTH = 5
    private const val AP_LENGTH = 3

    /** Builtin numFmtId -> format code (the standard subset; ids without an entry are "General"). */
    private val BUILTINS: Map<Int, String> = mapOf(
        0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00",
        9 to "0%", 10 to "0.00%", 11 to "0.00E+00", 12 to "# ?/?", 13 to "# ??/??",
        14 to "mm-dd-yy", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy",
        18 to "h:mm AM/PM", 19 to "h:mm:ss AM/PM", 20 to "h:mm", 21 to "h:mm:ss",
        22 to "m/d/yy h:mm", 37 to "#,##0;(#,##0)", 38 to "#,##0;[Red](#,##0)",
        39 to "#,##0.00;(#,##0.00)", 40 to "#,##0.00;[Red](#,##0.00)",
        45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mmss.0", 48 to "##0.0E+0", 49 to "@"
    )

    fun forBuiltin(id: Int): OdfNumberFormat? {
        // 27-36 and 50-58 are locale/CJK date-time builtins; use a sensible default date pattern
        // (the literal "date" is not a valid format code and yields garbage tokens).
        val code = BUILTINS[id] ?: if (id in LOCALE_DATE_BUILTINS || id in CJK_BUILTINS) {
            return parse("yyyy-mm-dd")
        } else {
            return null
        }
        if (code == "General") return null
        return parse(code)
    }

    /** True if a builtin id is a date/time format (used to tag value-type without a full parse). */
    fun isDateTimeBuiltin(id: Int): Boolean =
        id in DATE_BUILTINS || id in TIME_BUILTINS || id in LOCALE_DATE_BUILTINS || id in CJK_BUILTINS

    /**
     * Parses a custom format code (uses only the first ';' section for positive numbers). Returns
     * null for "General" / empty.
     */
    fun parse(codeRaw: String): OdfNumberFormat? {
        val section = firstSection(codeRaw) ?: return null
        val parts = splitCodeParts(section)
        if (isDateCode(parts)) return parseDateTime(stripBracketsKeepQuotes(section))
        if (parts.cleaned.contains("@")) return null
        return numericFormat(parts)
    }

    /** First ';' section, or null for "General" / empty. */
    private fun firstSection(codeRaw: String): String? {
        val full = codeRaw.trim()
        if (full.isEmpty() || full.equals("General", true)) return null
        return full.split(';').first().trim()
    }

    /** Cleaned code variants used by kind detection + numeric parsing. */
    private class CodeParts(
        val currency: String?,
        val cleaned: String,
        val dateProbe: String,
    )

    /** Currency + bracket/literal-stripped variants of a section. */
    private fun splitCodeParts(section: String): CodeParts {
        // Strip color / condition brackets like [Red], [$-409], [>100] but keep [$...] currency payloads.
        val currency = extractCurrency(section)
        val cleaned = stripBrackets(section)
        // Date detection must ignore quoted literals: 0" days" is a number, not a date.
        val dateProbe = stripLiterals(section)
        return CodeParts(currency, cleaned, dateProbe)
    }

    /** True when the code is a date/time code (and not scientific/text). */
    private fun isDateCode(parts: CodeParts): Boolean {
        if (isScientificCode(parts.cleaned)) return false
        return containsDateToken(parts.dateProbe)
    }

    /** True for scientific notation codes. */
    private fun isScientificCode(cleaned: String): Boolean =
        cleaned.contains("E+", true) || cleaned.contains("E-", true)

    /** True for fraction codes like "# ?/?". */
    private fun isFractionCode(cleaned: String): Boolean =
        cleaned.contains("/") && Regex("[?#0]\\s*/\\s*[?#0]").containsMatchIn(cleaned)

    /** Numeric (non-date) format from cleaned code parts. */
    private fun numericFormat(parts: CodeParts): OdfNumberFormat {
        val cleaned = parts.cleaned
        val isScientific = isScientificCode(cleaned)
        val isFraction = isFractionCode(cleaned)
        val percent = cleaned.contains("%")
        val grouping = cleaned.contains(",") && Regex("#,##0|0,0").containsMatchIn(cleaned)
        return OdfNumberFormat(
            decimals = decimalsOf(cleaned, isScientific, percent),
            percent = percent,
            currencySymbol = parts.currency,
            grouping = grouping,
            isScientific = isScientific,
            isFraction = isFraction,
            fractionDenominatorDigits = fracDigitsOf(cleaned, isFraction)
        )
    }

    /** Decimals after the '.' (only meaningful with '.' / scientific / percent). */
    private fun decimalsOf(cleaned: String, isScientific: Boolean, percent: Boolean): Int {
        if (!cleaned.contains('.') && !isScientific && !percent) return 0
        return cleaned.substringAfter('.', "").takeWhile { it == '0' || it == '#' }
            .count { it == '0' || it == '#' }
    }

    /** Denominator digits for fraction codes (1 otherwise). */
    private fun fracDigitsOf(cleaned: String, isFraction: Boolean): Int {
        if (!isFraction) return 1
        return cleaned.substringAfterLast('/').takeWhile { it == '?' || it == '#' || it == '0' }.length
            .coerceAtLeast(1)
    }

    private fun containsDateToken(code: String): Boolean {
        // A code is a date/time if it has y/d/s tokens, or 'm'/'h' outside of scientific notation.
        val c = code.replace(Regex("\\[[^]]*]"), "")
        return Regex("[yYdDsS]").containsMatchIn(c) || Regex("[hH]").containsMatchIn(c) ||
            (Regex("[mM]").containsMatchIn(c) && !c.contains("E", true))
    }

    /** Date/time tokenize state. */
    private class DateTimeAcc(
        var i: Int = 0,
        var seenHour: Boolean = false,
        var seenTime: Boolean = false,
        val tokens: MutableList<OdfNumberToken> = mutableListOf(),
    )

    private fun parseDateTime(codeIn: String): OdfNumberFormat {
        val code = codeIn
        val acc = DateTimeAcc()
        val ampm = code.contains("AM/PM", true) || code.contains("A/P", true)
        while (acc.i < code.length) {
            stepDateTimeChar(code, acc)
        }
        return buildDateTimeFormat(acc, ampm)
    }

    /** Final format from accumulated tokens. */
    private fun buildDateTimeFormat(acc: DateTimeAcc, ampm: Boolean): OdfNumberFormat {
        val tokens = acc.tokens
        if (ampm && tokens.none { it.kind == "am-pm" }) tokens.add(OdfNumberToken("am-pm"))
        return OdfNumberFormat(
            isDate = !acc.seenTime || tokens.any { it.kind in DATE_KINDS },
            isTime = acc.seenTime && tokens.none { it.kind in DATE_KINDS },
            dateTimeTokens = tokens)
    }

    private val DATE_KINDS = setOf("year", "month", "day", "day-of-week")

    /** Consumes one date/time token or literal at [acc.i]. */
    private fun stepDateTimeChar(code: String, acc: DateTimeAcc) {
        when (code[acc.i].lowercaseChar()) {
            'y' -> addYearToken(code, acc)
            'm' -> addMonthMinuteToken(code, acc)
            'd' -> addDayToken(code, acc)
            'h' -> addHourToken(code, acc)
            's' -> addSecondToken(code, acc)
            '"' -> addQuotedLiteral(code, acc)
            '\\' -> addEscapedChar(code, acc)
            else -> addAmpmOrLiteral(code, acc)
        }
    }

    /** Year run (yyyy = long). */
    private fun addYearToken(code: String, acc: DateTimeAcc) {
        val run = runLen(code, acc.i, 'y')
        acc.tokens.add(OdfNumberToken("year", style = if (run >= LONG_RUN) "long" else "short"))
        acc.i += run
    }

    /** Month vs minutes: 'm' after an hour (or before seconds) is minutes. */
    private fun addMonthMinuteToken(code: String, acc: DateTimeAcc) {
        val run = runLen(code, acc.i, 'm')
        val isMinute = acc.seenHour || nextNonSpaceIsSeconds(code, acc.i + run)
        if (isMinute) {
            acc.tokens.add(OdfNumberToken(
                "minutes",
                style = if (run >= SHORT_RUN) "long" else "short"))
            acc.seenTime = true
        } else {
            acc.tokens.add(OdfNumberToken(
                "month",
                style = if (run >= LONG_RUN) "long" else "short",
                textual = run >= MEDIUM_RUN))
        }
        acc.i += run
    }

    /** Day / day-of-week run. */
    private fun addDayToken(code: String, acc: DateTimeAcc) {
        val run = runLen(code, acc.i, 'd')
        val kind = if (run >= MEDIUM_RUN) "day-of-week" else "day"
        val style = if (run == LONG_RUN || run == SHORT_RUN) "long" else "short"
        acc.tokens.add(OdfNumberToken(kind, style = style, textual = run >= MEDIUM_RUN))
        acc.i += run
    }

    /** Hour run. */
    private fun addHourToken(code: String, acc: DateTimeAcc) {
        val run = runLen(code, acc.i, 'h')
        val style = if (run >= SHORT_RUN) "long" else "short"
        acc.tokens.add(OdfNumberToken("hours", style = style))
        acc.seenHour = true
        acc.seenTime = true
        acc.i += run
    }

    /** Second run. */
    private fun addSecondToken(code: String, acc: DateTimeAcc) {
        val run = runLen(code, acc.i, 's')
        val style = if (run >= SHORT_RUN) "long" else "short"
        acc.tokens.add(OdfNumberToken("seconds", style = style))
        acc.seenTime = true
        acc.i += run
    }

    /** Quoted literal segment. */
    private fun addQuotedLiteral(code: String, acc: DateTimeAcc) {
        val end = code.indexOf('"', acc.i + 1)
        val lit = if (end >= 0) code.substring(acc.i + 1, end) else code.substring(acc.i + 1)
        if (lit.isNotEmpty()) acc.tokens.add(OdfNumberToken("text", text = lit))
        acc.i = if (end >= 0) end + 1 else code.length
    }

    /** Backslash-escaped single literal char. */
    private fun addEscapedChar(code: String, acc: DateTimeAcc) {
        if (acc.i + 1 < code.length) {
            acc.tokens.add(OdfNumberToken("text", text = code[acc.i + 1].toString()))
            acc.i += 2
        } else {
            acc.i++
        }
    }

    /** AM/PM marker or a literal text run. */
    private fun addAmpmOrLiteral(code: String, acc: DateTimeAcc) {
        if (code.startsWith("AM/PM", acc.i, true)) {
            acc.tokens.add(OdfNumberToken("am-pm"))
            acc.i += AMPM_LENGTH
            return
        }
        if (code.startsWith("A/P", acc.i, true)) {
            acc.tokens.add(OdfNumberToken("am-pm"))
            acc.i += AP_LENGTH
            return
        }
        // literal text run until next token char / quote / escape
        val start = acc.i
        while (acc.i < code.length && isLiteralChar(code, acc.i)) acc.i++
        val lit = code.substring(start, acc.i)
        if (lit.isNotEmpty()) acc.tokens.add(OdfNumberToken("text", text = lit))
    }

    private fun nextNonSpaceIsSeconds(code: String, from: Int): Boolean {
        var i = from
        while (i < code.length && (code[i] == ':' || code[i] == ' ')) i++
        return i < code.length && code[i].lowercaseChar() == 's'
    }

    private fun runLen(s: String, start: Int, ch: Char): Int {
        var i = start
        while (i < s.length && s[i].lowercaseChar() == ch.lowercaseChar()) i++
        return i - start
    }

    /** True while [code] at [i] is literal text (not a token char/quote/escape). */
    private fun isLiteralChar(code: String, i: Int): Boolean =
        code[i].lowercaseChar() !in "ymdhs" &&
            code[i] != '"' &&
            code[i] != '\\' &&
            !code.startsWith("AM/PM", i, true)

    private fun extractCurrency(code: String): String? {
        Regex("\\[\\$([^\\]-]*)").find(code)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { return it }
        for (sym in listOf("$", "€", "£", "¥", "₹")) if (code.contains(sym)) return sym
        return null
    }

    private fun stripBrackets(code: String): String =
        code.replace(Regex("\\[[^]]*]"), "").replace("\"", "").replace("\\", "")

    /** Removes bracket sections but keeps quoted literals intact (for the date token parser). */
    private fun stripBracketsKeepQuotes(code: String): String =
        code.replace(Regex("\\[[^]]*]"), "")

    /** Removes bracket sections, quoted literals, and backslash escapes (for date detection). */
    private fun stripLiterals(code: String): String =
        code.replace(Regex("\\[[^]]*]"), "").replace(Regex("\"[^\"]*\""), "").replace(Regex("\\\\."), "")
}
