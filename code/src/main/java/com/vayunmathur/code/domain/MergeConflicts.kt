package com.vayunmathur.code.domain
/** How a single merge conflict should be resolved. */
enum class Resolution { OURS, THEIRS, BOTH }

/**
 * One Git merge conflict block. [startLine]/[endLine] are 0-based, inclusive line indices spanning
 * the `<<<<<<<` … `>>>>>>>` markers; [ours] is the current-branch side, [theirs] the incoming side.
 */
data class Conflict(
    val startLine: Int,
    val endLine: Int,
    val ours: List<String>,
    val theirs: List<String>,
)

private const val OURS_MARKER = "<<<<<<<"
private const val SEPARATOR_MARKER = "======="
private const val THEIRS_MARKER = ">>>>>>>"

/**
 * Pure detection + resolution of Git merge-conflict markers. Kept out of the UI so both are
 * unit-tested directly.
 */

/** Finds every well-formed conflict block in [text]. Malformed/unterminated blocks are ignored. */
fun parseConflicts(text: String): List<Conflict> {
    val lines = text.split("\n")
    val out = ArrayList<Conflict>()
    var i = 0
    while (i < lines.size) {
        if (!lines[i].startsWith(OURS_MARKER)) {
            i++
            continue
        }
        i = consumeConflict(lines, i, out)
    }
    return out
}

private class ConflictBounds(val separator: Int, val end: Int)

private fun consumeConflict(lines: List<String>, start: Int, out: MutableList<Conflict>): Int {
    val bounds = findConflictBounds(lines, start) ?: return start + 1
    out.add(
        Conflict(
            startLine = start,
            endLine = bounds.end,
            ours = lines.subList(start + 1, bounds.separator).toList(),
            theirs = lines.subList(bounds.separator + 1, bounds.end).toList(),
        ),
    )
    return bounds.end + 1
}

private fun findConflictBounds(lines: List<String>, start: Int): ConflictBounds? {
    var sep = -1
    var j = start + 1
    while (j < lines.size) {
        if (sep == -1 && lines[j].startsWith(SEPARATOR_MARKER)) sep = j
        if (lines[j].startsWith(THEIRS_MARKER)) {
            return if (sep == -1) null else ConflictBounds(sep, j)
        }
        // A new conflict started; this one is malformed.
        if (lines[j].startsWith(OURS_MARKER)) return null
        j++
    }
    return null
}

/**
 * Rewrites [text], replacing each conflict block with the chosen side. [resolutions] is applied in
 * document order; if it is shorter than the number of conflicts, the remaining blocks are left
 * untouched. [Resolution.BOTH] keeps ours followed by theirs.
 */
fun applyResolutions(text: String, resolutions: List<Resolution>): String {
    val conflicts = parseConflicts(text)
    if (conflicts.isEmpty()) return text
    val lines = text.split("\n")
    val out = ArrayList<String>(lines.size)
    var i = 0
    var ci = 0
    while (i < lines.size) {
        val conflict = conflicts.getOrNull(ci)
        if (conflict != null && i == conflict.startLine) {
            val resolution = resolutions.getOrNull(ci)
            if (resolution == null) {
                // No choice supplied: keep the original block verbatim.
                out.addAll(lines.subList(conflict.startLine, conflict.endLine + 1))
            } else {
                out.addAll(
                    when (resolution) {
                        Resolution.OURS -> conflict.ours
                        Resolution.THEIRS -> conflict.theirs
                        Resolution.BOTH -> conflict.ours + conflict.theirs
                    },
                )
            }
            i = conflict.endLine + 1
            ci++
        } else {
            out.add(lines[i])
            i++
        }
    }
    return out.joinToString("\n")
}
