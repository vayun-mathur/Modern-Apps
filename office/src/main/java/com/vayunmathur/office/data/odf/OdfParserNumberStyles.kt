package com.vayunmathur.office.data.odf

import com.vayunmathur.library.ui.odf.OdfNumberFormat
import com.vayunmathur.library.ui.odf.OdfNumberToken
import org.xmlpull.v1.XmlPullParser

// --- Number format (data style) parsing (H50) ---

/** Number-style accumulation state. */
private class NumberStyleAcc(
    var curName: String? = null,
    var type: String = "",
    var decimals: Int? = null,
    var currency: String? = null,
    var grouping: Boolean = false,
    var isTime: Boolean = false,
    var isScientific: Boolean = false,
    var isFraction: Boolean = false,
    var fracDenomDigits: Int = 1,
    val dateTokens: MutableList<OdfNumberToken> = mutableListOf(),
) {
    fun reset(name: String?, t: String) {
        curName = name; type = t
        decimals = null; currency = null; grouping = false
        isTime = false; isScientific = false; isFraction = false; fracDenomDigits = 1
        dateTokens.clear()
    }

    fun flushInto(map: MutableMap<String, OdfNumberFormat>) {
        val n = curName ?: return
        map[n] = OdfNumberFormat(
            decimals = decimals,
            percent = type == "percentage",
            currencySymbol = currency,
            grouping = grouping,
            isDate = type == "date",
            isTime = isTime || type == "time",
            isScientific = isScientific,
            isFraction = isFraction,
            fractionDenominatorDigits = fracDenomDigits,
            dateTimeTokens = dateTokens.toList()
        )
    }
}

internal fun OdfParser.parseNumberStyles(xml: String): Map<String, OdfNumberFormat> {
    val map = mutableMapOf<String, OdfNumberFormat>()
    val parser = newParser(xml)
    val acc = NumberStyleAcc()
    var e = parser.eventType
    while (e != XmlPullParser.END_DOCUMENT) {
        when (e) {
            XmlPullParser.START_TAG -> applyNumberStyleStart(parser, acc)
            XmlPullParser.END_TAG -> applyNumberStyleEnd(parser, acc, map)
        }
        e = parser.next()
    }
    return map
}

/** Number style date/time token names. */
private val dateTokenNames = setOf(
    "year", "month", "day", "day-of-week", "hours", "minutes", "seconds",
    "am-pm", "era", "quarter", "week-of-year"
)

/** Apply one number-style start tag. */
private fun OdfParser.applyNumberStyleStart(parser: XmlPullParser, acc: NumberStyleAcc) {
    when (parser.name) {
        "number-style", "percentage-style", "currency-style", "date-style", "time-style" -> {
            acc.reset(getAttr(parser, "name"), parser.name.removeSuffix("-style"))
        }
        "text" -> readNumberStyleText(parser, acc)
        "number" -> {
            getAttr(parser, "decimal-places")?.toIntOrNull()?.let { acc.decimals = it }
            if (getAttr(parser, "grouping") == "true") acc.grouping = true
        }
        "scientific-number" -> {
            acc.isScientific = true
            getAttr(parser, "decimal-places")?.toIntOrNull()?.let { acc.decimals = it }
        }
        "fraction" -> {
            acc.isFraction = true
            getAttr(parser, "min-denominator-digits")?.toIntOrNull()?.let { acc.fracDenomDigits = it }
        }
        "currency-symbol" -> acc.currency = readElementText(parser).trim()
        else -> readNumberStyleDateToken(parser, acc)
    }
}

/** Date/time component token reader. */
private fun OdfParser.readNumberStyleDateToken(parser: XmlPullParser, acc: NumberStyleAcc) {
    if (parser.name !in dateTokenNames) return
    if (acc.type != "date" && acc.type != "time") return
    acc.dateTokens.add(
        OdfNumberToken(
            kind = parser.name,
            style = getAttr(parser, "style"),
            textual = getAttr(parser, "textual") == "true"
        )
    )
}

/** Literal text token reader for date/time styles. */
private fun OdfParser.readNumberStyleText(parser: XmlPullParser, acc: NumberStyleAcc) {
    if (acc.type != "date" && acc.type != "time") return
    acc.dateTokens.add(OdfNumberToken(kind = "text", text = readElementText(parser)))
}

/** Reads all text inside the current element. */
private fun OdfParser.readElementText(parser: XmlPullParser): String {
    val d = parser.depth
    var ev = parser.next()
    val sb = StringBuilder()
    while (!(ev == XmlPullParser.END_TAG && parser.depth == d)) {
        if (ev == XmlPullParser.TEXT) sb.append(parser.text)
        if (ev == XmlPullParser.END_DOCUMENT) break
        ev = parser.next()
    }
    return sb.toString()
}

/** Apply one number-style end tag. */
private fun OdfParser.applyNumberStyleEnd(
    parser: XmlPullParser,
    acc: NumberStyleAcc,
    map: MutableMap<String, OdfNumberFormat>,
) {
    when (parser.name) {
        "number-style", "percentage-style", "currency-style", "date-style", "time-style" -> {
            acc.flushInto(map); acc.curName = null
        }
    }
}
