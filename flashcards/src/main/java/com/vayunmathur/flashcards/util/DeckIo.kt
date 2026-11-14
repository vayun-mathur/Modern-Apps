package com.vayunmathur.flashcards.util

/** CSV import for bulk note creation. Per-deck sharing now uses `.apkg` (see [ApkgExport]). */
object DeckIo {

    /**
     * Serializes notes to `front,back` CSV. [rows] are (front, back) pairs already
     * stripped of HTML/markers by the caller. A header row is written first.
     */
    fun writeCsv(rows: List<Pair<String, String>>): String {
        val sb = StringBuilder("front,back\n")
        rows.forEach { (front, back) ->
            sb.append(escapeCsv(front)).append(',').append(escapeCsv(back)).append('\n')
        }
        return sb.toString()
    }

    private fun escapeCsv(field: String): String {
        val needsQuoting = field.contains(',') || field.contains('"') || field.contains('\n')
        if (!needsQuoting) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }

    /**
     * Parses CSV text into (front, back) pairs. Accepts two columns per row; a
     * standard-quoted first line of `front,back` is skipped as a header. Handles
     * double-quoted fields with embedded commas and escaped quotes.
     */
    fun parseCsv(text: String): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        var firstRecord = true
        for (fields in splitCsvRecords(text)) {
            if (fields.size >= 2) {
                val front = fields[0].trim()
                val back = fields[1].trim()
                if (front.isNotEmpty() || back.isNotEmpty()) {
                    val isHeader = firstRecord && front.equals("front", true) &&
                        back.equals("back", true)
                    firstRecord = false
                    if (!isHeader) rows.add(front to back)
                }
            }
        }
        return rows
    }

    /**
     * Splits CSV [text] into records. Quoted fields may span multiple lines
     * (as [writeCsv] emits for fields containing newlines), so this must scan
     * the whole text rather than one physical line at a time.
     */
    private fun splitCsvRecords(text: String): List<List<String>> = CsvParser(text).parse()

    /** Character scanner for [splitCsvRecords]; quoted fields may span lines. */
    private class CsvParser(private val text: String) {
        private val records = mutableListOf<List<String>>()
        private val record = mutableListOf<String>()
        private val sb = StringBuilder()
        private var inQuotes = false
        private var recordHasContent = false
        private var i = 0

        fun parse(): List<List<String>> {
            while (i < text.length) {
                consume(text[i])
                i++
            }
            if (recordHasContent || record.isNotEmpty() || sb.isNotEmpty()) endRecord()
            return records
        }

        private fun consume(c: Char) {
            when {
                inQuotes && c == '"' && text.getOrNull(i + 1) == '"' -> {
                    sb.append('"'); i++
                }
                c == '"' -> { inQuotes = !inQuotes; recordHasContent = true }
                c == ',' && !inQuotes -> endField()
                (c == '\n' || c == '\r') && !inQuotes -> consumeNewline(c)
                else -> { sb.append(c); recordHasContent = true }
            }
        }

        private fun endField() {
            record.add(sb.toString()); sb.clear(); recordHasContent = true
        }

        private fun consumeNewline(c: Char) {
            if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
            if (recordHasContent || record.isNotEmpty() || sb.isNotEmpty()) endRecord()
        }

        private fun endRecord() {
            record.add(sb.toString()); sb.clear()
            records.add(record.toList()); record.clear()
            recordHasContent = false
        }
    }
}
