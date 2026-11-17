package com.vayunmathur.code.domain
import com.vayunmathur.code.data.GitRepo
/** The role of a diff row in a side-by-side view. */
enum class DiffRowType { CONTEXT, ADD, REMOVE, HUNK }

/**
 * One aligned row of a side-by-side diff. [leftNumber]/[rightNumber] are 1-based line numbers on the
 * old/new sides (null when the row does not exist on that side). [HUNK] rows carry the `@@` header.
 */
data class DiffRow(
    val type: DiffRowType,
    val leftNumber: Int?,
    val rightNumber: Int?,
    val text: String,
)

/**
 * Parses a unified diff (as produced by [GitRepo.diff]) into aligned rows for a two-column view.
 * Pure and unit-tested: metadata before the first hunk is skipped, and inside a hunk each line is
 * classified strictly by its leading character so removed lines beginning with `-` are not mistaken
 * for file headers.
 */
fun parseUnifiedDiff(diff: String): List<DiffRow> {
    val rows = ArrayList<DiffRow>()
    val header = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@")
    var oldLine = 0
    var newLine = 0
    var inHunk = false

    for (raw in diff.split("\n")) {
        when {
            raw.startsWith("@@") -> {
                header.find(raw)?.let {
                    oldLine = it.groupValues[1].toInt()
                    newLine = it.groupValues[2].toInt()
                }
                inHunk = true
                rows.add(DiffRow(DiffRowType.HUNK, null, null, raw))
            }
            raw.startsWith("diff ") -> inHunk = false // start of the next file section
            !inHunk -> Unit // skip index/---/+++/new file/etc. before the first hunk
            raw.startsWith("+") -> {
                rows.add(DiffRow(DiffRowType.ADD, null, newLine, raw.substring(1)))
                newLine++
            }
            raw.startsWith("-") -> {
                rows.add(DiffRow(DiffRowType.REMOVE, oldLine, null, raw.substring(1)))
                oldLine++
            }
            raw.startsWith(" ") -> {
                rows.add(DiffRow(DiffRowType.CONTEXT, oldLine, newLine, raw.substring(1)))
                oldLine++
                newLine++
            }
            // "\ No newline at end of file" and blank trailing lines fall through and are ignored.
        }
    }
    return rows
}
