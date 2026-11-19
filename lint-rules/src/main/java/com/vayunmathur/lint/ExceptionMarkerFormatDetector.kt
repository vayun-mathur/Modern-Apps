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
 * Validates the shape of the repo's per-file exception-marker comments.
 *
 * The markers are fail-closed: a typo'd marker or a missing colon silently does
 * NOT opt the file out, so enforcement disappears with no signal. This detector
 * catches the near-misses — `EXEPTION` typos, `TOAST EXCEPTION` without the
 * required colon, a `PACKAGE STRUCTURE EXCEPTION` that is not the verbatim JNI
 * form, and markers with no reason — while the owning detectors keep enforcing
 * the opt-out itself.
 *
 * Canonical shapes (kept in sync with the owning detectors):
 *  - `// PACKAGE STRUCTURE EXCEPTION (JNI)` verbatim
 *    (see [PackageStructureDetector]; real files may append `: <reason>`)
 *  - `// RAW SCAFFOLD EXCEPTION: <reason>` with a non-empty reason
 *  - `// TOAST EXCEPTION: <reason>` with colon and non-empty reason
 *    (see [ToastDetector])
 *  - `// RAW ANIMATION EXCEPTION: <reason>` with colon and non-empty reason
 *
 * A reason may continue on immediately following `//` lines, which the shared
 * scaffold/animation markers use for multi-line justifications.
 *
 * Comment-only via [CommentOnlyMatcher]: a marker inside a string literal must
 * neither opt a file out nor trip this check.
 *
 * Blocking: every finding is an error (a bare `// RAW SCAFFOLD EXCEPTION`
 * with no reason is one live shape this surfaces).
 */
class ExceptionMarkerFormatDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes(): List<Class<out UElement>> =
        listOf(UFile::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler =
        object : UElementHandler() {
            override fun visitFile(node: UFile) {
                val packageName = node.packageName
                if (packageName.isEmpty()) return
                if (!packageName.startsWith("com.vayunmathur.")) return
                // Rule-definition sources carry marker shapes in KDoc as examples
                // by necessity; scanning them would self-flag.
                if (isLintRulesPackage(packageName)) return
                val ktFile = node.sourcePsi as? KtFile ?: return
                val source = ktFile.text
                val file = context.file ?: return
                val comments = CommentOnlyMatcher.commentsIn(ktFile)
                comments.forEachIndexed { index, comment ->
                    checkComment(context, node, file, source, comments, index, comment)
                }
            }
        }

    private fun checkComment(
        context: JavaContext,
        node: UFile,
        file: File,
        source: String,
        comments: List<PsiComment>,
        index: Int,
        comment: PsiComment,
    ) {
        val text = comment.text
        val base = comment.textRange.startOffset

        if (text.contains(TYPO_KEYWORD)) {
            report(context, node, file, source, base + text.indexOf(TYPO_KEYWORD), TYPO_KEYWORD.length,
                "Typo'd exception marker (`EXEPTION`). No detector recognises this spelling, " +
                    "so the file is NOT opted out. Use one of the canonical marker comments.")
            return
        }

        if (text.contains(PACKAGE_KEYWORD) && !text.contains(PackageStructureDetector.JNI_MARKER)) {
            report(context, node, file, source, base + text.indexOf(PACKAGE_KEYWORD), PACKAGE_KEYWORD.length,
                "Malformed Package Structure exception marker. The only form that opts out is the " +
                    "verbatim `${PackageStructureDetector.JNI_MARKER}` marker comment.")
            return
        }

        if (text.contains(SCAFFOLD_KEYWORD)) {
            if (!text.contains(SCAFFOLD_PREFIX)) {
                report(context, node, file, source, base + text.indexOf(SCAFFOLD_KEYWORD), SCAFFOLD_KEYWORD.length,
                    "Malformed Raw Scaffold exception marker. Use " +
                        "`// RAW SCAFFOLD EXCEPTION: <reason>` with a non-empty reason.")
                return
            }
            val reason = reasonAfter(text, SCAFFOLD_PREFIX)
            if (!hasReason(source, comments, index, reason)) {
                report(context, node, file, source, base + text.indexOf(SCAFFOLD_PREFIX), SCAFFOLD_PREFIX.length,
                    "Exception marker without a reason. A bare `// RAW SCAFFOLD EXCEPTION` opts " +
                        "the file out but records nothing — append `: <reason>`.")
            }
            return
        }

        if (text.contains(TOAST_KEYWORD)) {
            if (!text.contains(TOAST_PREFIX)) {
                report(context, node, file, source, base + text.indexOf(TOAST_KEYWORD), TOAST_KEYWORD.length,
                    "Malformed Toast exception marker: the colon is required and a marker without " +
                        "it does NOT opt the file out. Use `// TOAST EXCEPTION: <reason>`.")
                return
            }
            val reason = reasonAfter(text, TOAST_PREFIX)
            if (!hasReason(source, comments, index, reason)) {
                report(context, node, file, source, base + text.indexOf(TOAST_PREFIX), TOAST_PREFIX.length,
                    "Exception marker without a reason. Append `: <reason>` to " +
                        "`// TOAST EXCEPTION:` so the opt-out stays reviewable.")
            }
            return
        }

        if (text.contains(ANIMATION_KEYWORD)) {
            if (!text.contains(ANIMATION_PREFIX)) {
                report(context, node, file, source, base + text.indexOf(ANIMATION_KEYWORD), ANIMATION_KEYWORD.length,
                    "Malformed Raw Animation exception marker: the colon is required and a marker " +
                        "without it does NOT opt the file out. Use " +
                        "`// RAW ANIMATION EXCEPTION: <reason>`.")
                return
            }
            val reason = reasonAfter(text, ANIMATION_PREFIX)
            if (!hasReason(source, comments, index, reason)) {
                report(context, node, file, source, base + text.indexOf(ANIMATION_PREFIX), ANIMATION_PREFIX.length,
                    "Exception marker without a reason. Append `: <reason>` to " +
                        "`// RAW ANIMATION EXCEPTION:` so the opt-out stays reviewable.")
            }
        }
    }

    /**
     * Text after [prefix] with a single optional leading colon stripped.
     * Only the first colon is structural; reasons may contain their own.
     */
    private fun reasonAfter(text: String, prefix: String): String {
        var rest = text.substring(text.indexOf(prefix) + prefix.length)
        if (rest.startsWith(":")) rest = rest.substring(1)
        return rest.trim()
    }

    /**
     * True if [reason] is non-blank, or the immediately following `//` comment
     * on the next line carries the reason as a continuation.
     */
    private fun hasReason(
        source: String,
        comments: List<PsiComment>,
        index: Int,
        reason: String,
    ): Boolean {
        if (reason.isNotBlank()) return true
        val next = comments.getOrNull(index + 1) ?: return false
        if (!next.text.startsWith("//")) return false
        val between = source.substring(comments[index].textRange.endOffset, next.textRange.startOffset)
        if (!ADJACENT_LINE.matches(between)) return false
        val body = next.text.removePrefix("//").trim()
        return body.isNotBlank() && !body.contains("EXCEPTION")
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
        /**
         * Rule-definition sources (`lint-rules/` itself) document marker shapes
         * in KDoc as examples by necessity; scanning them would self-flag.
         */
        internal fun isLintRulesPackage(packageName: String): Boolean =
            packageName == "com.vayunmathur.lint" ||
                packageName.startsWith("com.vayunmathur.lint.")

        private const val TYPO_KEYWORD = "EXEPTION"
        private const val PACKAGE_KEYWORD = "PACKAGE STRUCTURE EXCEPTION"
        private const val SCAFFOLD_KEYWORD = "RAW SCAFFOLD EXCEPTION"
        private const val SCAFFOLD_PREFIX = "// RAW SCAFFOLD EXCEPTION"
        private const val TOAST_KEYWORD = "TOAST EXCEPTION"
        private const val TOAST_PREFIX = "// TOAST EXCEPTION:"
        private const val ANIMATION_KEYWORD = "RAW ANIMATION EXCEPTION"
        private const val ANIMATION_PREFIX = "// RAW ANIMATION EXCEPTION:"

        /** Adjacent `//` line: only whitespace and exactly one line break between comments. */
        private val ADJACENT_LINE = Regex("^\\h*\\R\\h*$")

        val ISSUE: Issue = Issue.create(
            id = "ExceptionMarkerFormat",
            briefDescription = "Malformed exception-marker comment",
            explanation = """
                The repo's per-file exception markers are fail-closed: a typo'd marker or a \
                missing colon silently does NOT opt the file out, so enforcement disappears \
                with no signal. Use the canonical shapes — `// PACKAGE STRUCTURE EXCEPTION \
                (JNI)` verbatim, `// RAW SCAFFOLD EXCEPTION: <reason>`, `// TOAST EXCEPTION: \
                <reason>`, `// RAW ANIMATION EXCEPTION: <reason>` — each with a non-empty \
                reason (which may continue on following `//` lines), so every opt-out stays \
                reviewable.
                """.trimIndent(),
            category = Category.CORRECTNESS,
            priority = 6,
            severity = Severity.ERROR,
            implementation = Implementation(
                ExceptionMarkerFormatDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
