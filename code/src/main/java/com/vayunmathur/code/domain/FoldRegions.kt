package com.vayunmathur.code.domain
/** A foldable region: the header is [startLine]; folding hides `startLine+1..endLine` (0-based). */
data class FoldRegion(val startLine: Int, val endLine: Int)

/**
 * Pure, indentation-based fold computation for the editor's code folding. A line is a fold header
 * when the following non-blank lines are more deeply indented; the region runs to the last such
 * line. Nested blocks produce nested regions. Blank lines never start or end a region. No Android
 * dependencies, so it is unit-tested directly.
 */
fun computeFoldRegions(text: String): List<FoldRegion> {
    val lines = text.split("\n")
    val regions = ArrayList<FoldRegion>()
    val stack = ArrayDeque<IntArray>() // each entry: [headerLine, indent]
    var lastNonBlank = -1

    for (i in lines.indices) {
        val line = lines[i]
        if (line.isBlank()) continue
        val indent = indentOf(line)
        while (stack.isNotEmpty() && indent <= stack.last()[1]) {
            val header = stack.removeLast()
            if (lastNonBlank > header[0]) regions.add(FoldRegion(header[0], lastNonBlank))
        }
        stack.addLast(intArrayOf(i, indent))
        lastNonBlank = i
    }
    while (stack.isNotEmpty()) {
        val header = stack.removeLast()
        if (lastNonBlank > header[0]) regions.add(FoldRegion(header[0], lastNonBlank))
    }
    return regions.sortedBy { it.startLine }
}

/** Leading-whitespace width, counting each space or tab as one unit (enough to compare depth). */
private fun indentOf(line: String): Int {
    var n = 0
    for (c in line) {
        if (c == ' ' || c == '\t') n++ else break
    }
    return n
}
