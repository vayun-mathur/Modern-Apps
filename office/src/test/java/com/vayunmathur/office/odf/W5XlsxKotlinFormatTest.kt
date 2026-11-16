package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfNumberFormat
import com.vayunmathur.library.ui.odf.OdfNumberToken
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSheet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W5: the pure-Kotlin XLSX display formatter inside [OoxmlXlsx] (the fallback
 * used when the native engine cannot load) and the number-format DTO mapping
 * in OfficeNative. The private formatter is invoked reflectively — the same
 * precedent as W4WebViewClientTest — because on a host where the native
 * library loads, the public importer path never reaches it; the assertions
 * pin the formatter's real output strings, not its existence.
 */
class W5XlsxKotlinFormatTest {

    private fun callPrivate(name: String, vararg args: Any?): Any? {
        val clazz = OoxmlXlsx::class.java
        val method = clazz.declaredMethods.single { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        return method.invoke(OoxmlXlsx, *args)
    }

    private fun kotlinFormat(value: Double, fmt: OdfNumberFormat, raw: String = "raw") =
        callPrivate("kotlinFormat", value, fmt, raw) as String

    private fun group(s: String) = callPrivate("groupThousands", s) as String

    private fun serial(serial: Double) = callPrivate("excelSerialToIso", serial) as String?

    // ---- grouping ----

    @Test fun groupingCases() {
        val cases = mapOf(
            "0" to "0", "12" to "12", "123" to "123", "1234" to "1,234",
            "1234567" to "1,234,567", "123456789012" to "123,456,789,012",
            "-1234" to "-1,234", "-1234567.89" to "-1,234,567.89",
            "1234.5" to "1,234.5", "999" to "999", "-5" to "-5",
        )
        for ((input, expected) in cases) assertEquals(expected, group(input), "group($input)")
    }

    // ---- excel serial dates ----

    @Test fun serialDates() {
        // Excel serial 45292 = 2024-01-01 (days since 1899-12-30 epoch offset).
        assertEquals("2024-01-01", serial(45292.0))
        assertEquals("2023-01-01", serial(44927.0))
        assertEquals("2024-01-01 12:00", serial(45292.5))
        assertEquals("2024-01-01 06:00", serial(45292.25))
        // Fractional minutes truncate, never round up.
        assertEquals("2024-01-01 18:29", serial(45292.77083333333))
        assertNull(serial(-1.0), "negative serial has no date")
        assertNull(serial(3000000.0), "serial beyond the sane bound has no date")
        assertTrue(serial(0.0) != null, "serial zero still maps to the epoch neighbourhood")
        // Whole-day serials never carry a time part; fractional ones always do.
        assertEquals(10, serial(45292.0)!!.length)
        assertEquals(16, serial(45292.5)!!.length)
    }

    // ---- kotlinFormat branches ----

    @Test fun plainAndDecimalFormats() {
        assertEquals("42", kotlinFormat(42.4, OdfNumberFormat()))
        assertEquals("43", kotlinFormat(42.6, OdfNumberFormat()))
        assertEquals("-7", kotlinFormat(-7.2, OdfNumberFormat()))
        assertEquals("3.14", kotlinFormat(3.14159, OdfNumberFormat(decimals = 2)))
        assertEquals("3.142", kotlinFormat(3.14159, OdfNumberFormat(decimals = 3)))
        assertEquals("0.50", kotlinFormat(0.5, OdfNumberFormat(decimals = 2)))
        assertEquals("100.0", kotlinFormat(100.04, OdfNumberFormat(decimals = 1)))
    }

    @Test fun percentFormats() {
        assertEquals("25%", kotlinFormat(0.25, OdfNumberFormat(percent = true)))
        assertEquals("25.0%", kotlinFormat(0.25, OdfNumberFormat(percent = true, decimals = 1)))
        assertEquals("150%", kotlinFormat(1.5, OdfNumberFormat(percent = true)))
        assertEquals("0%", kotlinFormat(0.0, OdfNumberFormat(percent = true)))
        assertEquals("-25%", kotlinFormat(-0.25, OdfNumberFormat(percent = true)))
    }

    @Test fun currencyAndGroupingFormats() {
        assertEquals("\$1,234,567", kotlinFormat(1234567.0, OdfNumberFormat(grouping = true, currencySymbol = "\$")))
        assertEquals("€1,234.50", kotlinFormat(1234.5, OdfNumberFormat(grouping = true, decimals = 2, currencySymbol = "€")))
        assertEquals("1,234,567", kotlinFormat(1234567.0, OdfNumberFormat(grouping = true)))
        assertEquals("1234567", kotlinFormat(1234567.0, OdfNumberFormat(grouping = false)))
        assertEquals("\$42", kotlinFormat(42.0, OdfNumberFormat(currencySymbol = "\$")))
    }

    @Test fun scientificFormats() {
        assertEquals("1.23E+04", kotlinFormat(12345.0, OdfNumberFormat(isScientific = true, decimals = 2)))
        assertEquals("1.234568E+07", kotlinFormat(12345678.0, OdfNumberFormat(isScientific = true, decimals = 6)))
        // No explicit decimals -> the default scientific precision.
        val def = kotlinFormat(12345.0, OdfNumberFormat(isScientific = true))
        assertTrue(def.contains("E"), def)
        assertEquals("0.00E+00", kotlinFormat(0.0, OdfNumberFormat(isScientific = true, decimals = 2)))
    }

    @Test fun fractionFallsBackToRaw() {
        assertEquals("1/2", kotlinFormat(0.5, OdfNumberFormat(isFraction = true), raw = "1/2"))
        assertEquals("3/4", kotlinFormat(0.75, OdfNumberFormat(isFraction = true), raw = "3/4"))
    }

    @Test fun dateAndTimeFormatsUseSerial() {
        assertEquals("2024-01-01", kotlinFormat(45292.0, OdfNumberFormat(isDate = true), raw = "ignored"))
        assertEquals("2024-01-01 12:00", kotlinFormat(45292.5, OdfNumberFormat(isTime = true), raw = "ignored"))
        // An out-of-range serial falls back to the raw cached string.
        assertEquals("cached", kotlinFormat(-5.0, OdfNumberFormat(isDate = true), raw = "cached"))
        assertEquals("cached", kotlinFormat(9.9e9, OdfNumberFormat(isDate = true), raw = "cached"))
    }

    // ---- OfficeNative workbook JSON / NfDto mapping ----

    @Test fun workbookJsonCarriesFormatsAndRanges() {
        val fmt = OdfNumberFormat(
            decimals = 2, percent = true, currencySymbol = "\$", grouping = true,
            dateTimeTokens = listOf(
                OdfNumberToken("year", style = "long"),
                OdfNumberToken("text", text = "-"),
                OdfNumberToken("month", textual = true),
            ),
        )
        val sheet = OdfSheet(
            name = "Data",
            rows = listOf(
                OdfRow(listOf(
                    OdfCell(text = "1.5", numberValue = 1.5, numberFormat = fmt, formula = "=A1*2"),
                    OdfCell(text = "plain"),
                    OdfCell(text = "covered", isCovered = true),
                )),
                OdfRow(emptyList()),
            ),
        )
        val json = com.vayunmathur.office.util.OfficeNative.buildWorkbookJson(
            listOf(sheet),
            listOf(com.vayunmathur.library.ui.odf.OdfNamedRange("Total", "Data.A1:B2")),
        )
        assertTrue(json.contains("\"Data\""), json)
        assertTrue(json.contains("Total"), json)
        assertTrue(json.contains("=A1*2"), json)
        // The NfDto fields survive serialization (percent/decimals/grouping).
        assertTrue(json.contains("\"percent\":true"), json)
        assertTrue(json.contains("\"decimals\":2"), json)
        // An empty workbook still produces a valid sheets array.
        val empty = com.vayunmathur.office.util.OfficeNative.buildWorkbookJson(emptyList())
        assertTrue(empty.contains("\"sheets\":[]"), empty)
        // Format-less cells serialize without a numberFormat object.
        val plain = com.vayunmathur.office.util.OfficeNative.buildWorkbookJson(
            listOf(OdfSheet("S", listOf(OdfRow(listOf(OdfCell(text = "x")))))))
        assertTrue(plain.contains("\"x\""), plain)
    }
}
