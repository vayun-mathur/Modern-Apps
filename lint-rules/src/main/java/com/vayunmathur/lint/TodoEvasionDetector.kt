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
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UFile
import java.io.File

/**
 * Catches TODO-marker shapes that evade detekt's `ForbiddenComment` ban.
 *
 * detekt matches exact comment substrings, so each of these live shapes slips
 * past `ForbiddenComment` (`FIXME:`/`STOPSHIP:`/`TODO:`) untouched:
 *  - scoped markers with a parenthesised owner — `TODO(scope): ...`,
 *    `TODO(b/12345): ...`
 *  - bare `TODO` / `FIXME` words in section banners with no colon
 *    (`// ---- TODO / FIXME ----`)
 *
 * The base ban stays blocking in detekt; these evasion shapes land here as a
 * an error like the rest of the ban. Comment-only via
 * [CommentOnlyMatcher]: an identifier or string literal that happens to
 * contain `TODO` must not trip this check.
 */
class TodoEvasionDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes(): List<Class<out UElement>> =
        listOf(UFile::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler =
        object : UElementHandler() {
            override fun visitFile(node: UFile) {
                val packageName = node.packageName
                if (packageName.isEmpty()) return
                if (!packageName.startsWith("com.vayunmathur.")) return
                if (LintPackageExclusions.isExcluded(packageName)) return
                // Rule-definition sources carry marker shapes in KDoc as examples
                // by necessity; scanning them would self-flag.
                if (ExceptionMarkerFormatDetector.isLintRulesPackage(packageName)) return
                val ktFile = node.sourcePsi as? KtFile ?: return
                val source = ktFile.text
                val file = context.file ?: return
                for (comment in CommentOnlyMatcher.commentsIn(ktFile)) {
                    val text = comment.text
                    if (BASE_BANNED.any { text.contains(it) }) continue
                    val base = comment.textRange.startOffset
                    val scoped = SCOPED_MARKER.find(text)
                    if (scoped != null) {
                        report(
                            context, node, file, source,
                            base + scoped.range.first, scoped.value.length,
                            "TODO marker with a parenthesised scope (`${scoped.value}`) evades " +
                                "the detekt `ForbiddenComment` ban, which matches `TODO:` exactly. " +
                                "Resolve it or file a tracked task — bare markers are banned " +
                                "repo-wide with no scope carve-out.",
                        )
                        continue
                    }
                    val bare = BARE_MARKER.find(text)
                    if (bare != null) {
                        report(
                            context, node, file, source,
                            base + bare.range.first, bare.value.length,
                            "Bare `${bare.value}` marker with no colon evades the detekt " +
                                "`ForbiddenComment` ban. Resolve it or file a tracked task — " +
                                "bare markers are banned repo-wide.",
                        )
                    }
                }
            }
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
        /** Already blocked by detekt `ForbiddenComment`; never double-report. */
        private val BASE_BANNED = listOf("FIXME:", "STOPSHIP:", "TODO:")

        /** `TODO(owner):` / `TODO(b/123):` — scoped markers the ban misses. */
        private val SCOPED_MARKER = Regex("""\bTODO\([^()\n]*\):?""")

        /** Bare `TODO` / `FIXME` banner words with no colon (and no scope paren). */
        private val BARE_MARKER = Regex("""\b(?:TODO|FIXME)\b(?!:)(?!\()""")

        val ISSUE: Issue = Issue.create(
            id = "TodoEvasion",
            briefDescription = "TODO marker shape evades the marker ban",
            explanation = """
                detekt's `ForbiddenComment` ban matches `TODO:`, `FIXME:` and `STOPSHIP:` \
                exactly, so scoped markers (`TODO(owner):`, `TODO(b/123):`) and bare banner \
                words (`TODO`, `FIXME` with no colon) slip past it untouched. Those shapes \
                are banned here too: resolve the item or file a tracked task rather than \
                leaving an untracked marker in the tree.
                """.trimIndent(),
            category = Category.PRODUCTIVITY,
            priority = 5,
            severity = Severity.ERROR,
            implementation = Implementation(
                TodoEvasionDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
