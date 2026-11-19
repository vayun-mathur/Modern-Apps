package com.vayunmathur.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiComment
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UFile
import java.io.File

/**
 * Flags commented-out Kotlin code.
 *
 * The tree is clean today but nothing enforces it, and version control makes
 * commented-out code pure noise: stale copies rot, readers cannot tell whether
 * the block is load-bearing documentation or a revert waiting to happen, and
 * the history already keeps the old shape. Delete it; the commit that removed
 * it is the backup.
 *
 * Heuristic: a `//` comment line whose body parses as the start of a Kotlin
 * declaration or statement (val/var/fun/class/import/return/assignment/call).
 * Two or more consecutive matching lines report once on the run. Single
 * illustrative lines (e.g. `// e.g. foo.bar()`) stay silent, as do KDoc,
 * block comments, section banners, and the repo's own exception markers.
 *
 * Comment-only via [CommentOnlyMatcher]: string literals are never scanned.
 *
 * Blocking: every finding is an error.
 */
class CommentedOutCodeDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes(): List<Class<out UElement>> =
        listOf(UFile::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler =
        object : UElementHandler() {
            override fun visitFile(node: UFile) {
                val packageName = node.packageName
                if (packageName.isEmpty()) return
                if (!packageName.startsWith("com.vayunmathur.")) return
                if (LintPackageExclusions.isExcluded(packageName)) return
                val ktFile = node.sourcePsi as? KtFile ?: return
                val source = ktFile.text
                val file = context.file ?: return
                val comments = CommentOnlyMatcher.commentsIn(ktFile)
                var index = 0
                while (index < comments.size) {
                    val run = collectRun(source, comments, index)
                    if (run != null) {
                        val (start, length, count) = run
                        report(
                            context, node, file, source, start, length,
                            "Commented-out code ($count consecutive lines). Delete it — " +
                                "version control keeps the old shape, and stale copies rot. " +
                                "If the block documents intent, rewrite it as prose instead.",
                        )
                        index = runEnd(source, comments, index)
                    } else {
                        index++
                    }
                }
            }
        }

    /** Start/length/line-count of a commented-code run at [index], or null. */
    private fun collectRun(
        source: String,
        comments: List<PsiComment>,
        index: Int,
    ): Triple<Int, Int, Int>? {
        val first = comments[index]
        if (!isLineComment(first)) return null
        if (!looksLikeCode(stripSlashes(first.text))) return null
        var end = index
        while (end + 1 < comments.size) {
            val next = comments[end + 1]
            if (!isLineComment(next)) break
            if (!adjacentLine(source, comments[end], next)) break
            if (!looksLikeCode(stripSlashes(next.text))) break
            end++
        }
        if (end - index + 1 < MIN_RUN_LINES) return null
        val start = comments[index].textRange.startOffset
        val stop = comments[end].textRange.endOffset
        return Triple(start, stop - start, end - index + 1)
    }

    /** Index just past the run starting at [index]. */
    private fun runEnd(
        source: String,
        comments: List<PsiComment>,
        index: Int,
    ): Int {
        var end = index
        while (end + 1 < comments.size) {
            val next = comments[end + 1]
            if (!isLineComment(next)) break
            if (!adjacentLine(source, comments[end], next)) break
            if (!looksLikeCode(stripSlashes(next.text))) break
            end++
        }
        return end + 1
    }

    private fun isLineComment(comment: PsiComment): Boolean =
        comment.text.startsWith("//") && !comment.text.startsWith("///") && !comment.text.startsWith("//!")

    private fun stripSlashes(text: String): String =
        text.removePrefix("//").trimStart()

    private fun adjacentLine(
        source: String,
        prev: PsiComment,
        next: PsiComment,
    ): Boolean {
        val between = source.substring(prev.textRange.endOffset, next.textRange.startOffset)
        return ADJACENT_LINE.matches(between)
    }

    /** Heuristic: body reads as the start of a Kotlin declaration/statement. */
    private fun looksLikeCode(body: String): Boolean {
        if (body.isEmpty()) return false
        if (body.startsWith("----") || body.startsWith("====") || body.startsWith("****")) return false
        if (body.contains("EXCEPTION")) return false
        if (body.startsWith("e.g.") || body.startsWith("i.e.") || body.startsWith("see ")) return false
        return CODE_LIKE.any { it.containsMatchIn(body) }
    }

    private fun report(
        context: JavaContext,
        node: UFile,
        file: File,
        source: String,
        start: Int,
        length: Int,
        message: String,
    ) {
        context.report(
            ISSUE,
            node,
            Location.create(file, source, start, start + length),
            message,
        )
    }

    companion object {
        private const val MIN_RUN_LINES = 2

        /** Only whitespace and exactly one line break between adjacent `//` lines. */
        private val ADJACENT_LINE = Regex("^\\h*\\R\\h*$")

        private val CODE_LIKE = listOf(
            Regex("""^(val|var|fun|class|object|interface|enum|data|sealed|abstract|override|private|public|internal|protected|suspend|lateinit|const)\b"""),
            Regex("""^import\s+[\w.]+"""),
            Regex("""^return\b"""),
            Regex("""^[A-Za-z_][\w<>?]*\s*[:=]\s*\S"""),
            Regex("""^[A-Za-z_][\w.]*\s*\("""),
            Regex("""^\}\s*(else\b|catch\b|finally\b)?"""),
        )

        val ISSUE: Issue = Issue.create(
            id = "CommentedOutCode",
            briefDescription = "Commented-out code",
            explanation = """
                Commented-out code rots: readers cannot tell whether the block is \
                load-bearing documentation or a revert waiting to happen, and version \
                control already keeps the old shape. Delete the block — the commit that \
                removed it is the backup. If it documents intent, rewrite it as prose.
                """.trimIndent(),
            category = Category.PRODUCTIVITY,
            priority = 4,
            severity = Severity.ERROR,
            implementation = Implementation(
                CommentedOutCodeDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
