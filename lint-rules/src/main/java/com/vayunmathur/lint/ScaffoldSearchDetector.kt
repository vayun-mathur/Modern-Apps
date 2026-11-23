package com.vayunmathur.lint

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.uast.UCallExpression

/**
 * Flags standalone search bars in app UI.
 *
 * Screen-level search is owned by the scaffold: `AppScaffold` (and the
 * wrappers built on it — `DetailScaffold`, `DetailLazyColumn`,
 * `LazyListScaffold`) take a `search: ScaffoldSearch?` parameter and render
 * the shared inline filter field AS the top app bar, in its title slot.
 * Rendering `CommonSearchBar`, `SearchBar`, or `SearchBarInputField`
 * anywhere in app UI — as a content row, a first lazy item, or hand-built
 * into a custom top bar's title slot — is flagged here, as is a raw
 * Material 3 `SearchBar` behind those wrappers.
 *
 * TextField-as-search: a `TextField` / `OutlinedTextField` call is flagged
 * only when it is reliably identifiable as search from its own source —
 * a string literal containing "search", a `R.string.*search*` /
 * `UiR.string.*search*` resource reference, or an `IconSearch` leading
 * icon in the call. Ordinary form fields never match, which is deliberate:
 * an unreliable guess must not break form screens.
 *
 * Scope: app packages only (`com.vayunmathur.*` outside
 * `com.vayunmathur.library.*`). The shared library is exempt: the scaffolds
 * and their field are built there (`ListPage`'s internal search included).
 * TV text fields (`TvTextField`) are a different chrome family and are not
 * flagged.
 *
 * Legitimate non-screen search — a bottom-sheet/drawer-local picker
 * (launcher), a dialog-local filter (a timezone/ingredient picker), an
 * expandable SearchBar whose overlay IS the screen's results surface
 * (photos, youpipe), a map/document/browser overlay with no scaffold —
 * opts out per file with:
 * `// SCAFFOLD SEARCH EXCEPTION: <reason>`.
 */
class ScaffoldSearchDetector : Detector(), SourceCodeScanner {

    override fun getApplicableMethodNames(): List<String> = SEARCH_CALLS.toList()

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        if (!isSearchCall(context, node, method)) return

        val packageName = context.uastFile?.packageName ?: return
        if (!packageName.startsWith(APP_PACKAGE_PREFIX)) return
        if (isLibraryPackage(packageName)) return

        val ktFile = context.uastFile?.sourcePsi as? KtFile
        if (ktFile != null && CommentOnlyMatcher.containsMarkerInComments(ktFile, EXCEPTION_MARKER)) return

        context.report(
            ISSUE,
            node,
            context.getLocation(node),
            "Screen-level search is owned by the scaffold: pass " +
                "`search = ScaffoldSearch(query, onQueryChange, placeholder)` to " +
                "AppScaffold / DetailScaffold / DetailLazyColumn / LazyListScaffold " +
                "instead of rendering `${node.methodName}` in app UI. " +
                "Dialog/sheet/drawer-local filters and expandable overlay search " +
                "that genuinely have no scaffold host use the exception marker " +
                "`$EXCEPTION_MARKER <reason>`.",
        )
    }

    private fun isSearchCall(context: JavaContext, node: UCallExpression, method: PsiMethod): Boolean {
        val name = method.name
        val containing = method.containingClass?.qualifiedName ?: return false
        val inScope = containing.startsWith(LIBRARY_PREFIX) || containing.startsWith(MATERIAL3_PREFIX)
        if (!inScope) return false
        if (name in DIRECT_SEARCH_CALLS) return true
        if (name in TEXT_FIELD_CALLS) return looksLikeSearch(node)
        return false
    }

    /**
     * Reliable search identification for a raw text field, from the call's
     * own source only: a "search" string literal, a `*.string.*search*`
     * resource reference, or a search leading icon.
     */
    private fun looksLikeSearch(node: UCallExpression): Boolean {
        val source = node.asSourceString()
        if (source.contains(ICON_SEARCH)) return true
        if (SEARCH_RESOURCE.containsMatchIn(source)) return true
        return STRING_LITERAL.findAll(source).any { it.groupValues[1].lowercase().contains(SEARCH_WORD) }
    }

    companion object {
        private const val APP_PACKAGE_PREFIX = "com.vayunmathur."
        private const val LIBRARY_PREFIX = "com.vayunmathur.library"
        private const val MATERIAL3_PREFIX = "androidx.compose.material3"
        private const val ICON_SEARCH = "IconSearch"
        private const val SEARCH_WORD = "search"

        const val EXCEPTION_MARKER = "// SCAFFOLD SEARCH EXCEPTION:"

        private val DIRECT_SEARCH_CALLS = setOf(
            "CommonSearchBar",
            "SearchBar",
            "SearchBarInputField",
        )

        private val TEXT_FIELD_CALLS = setOf(
            "TextField",
            "OutlinedTextField",
        )

        private val SEARCH_CALLS = DIRECT_SEARCH_CALLS + TEXT_FIELD_CALLS

        private val SEARCH_RESOURCE = Regex("""\.\s*string\s*\.\s*\w*[Ss]earch\w*""")
        private val STRING_LITERAL = Regex("\"([^\"]*)\"")

        private fun isLibraryPackage(packageName: String): Boolean =
            packageName == LIBRARY_PREFIX || packageName.startsWith("$LIBRARY_PREFIX.")

        val ISSUE: Issue = Issue.create(
            id = "ScaffoldSearch",
            briefDescription = "Standalone search bar in app UI; scaffold owns screen-level search",
            explanation = """
                Screen-level search belongs to the scaffold object. AppScaffold and \
                the shared wrappers built on it (DetailScaffold, DetailLazyColumn, \
                LazyListScaffold) accept a `search: ScaffoldSearch?` parameter and \
                render the shared inline filter field AS the top app bar, in \
                its title slot (title replaced), above the refresh box. Rendering a CommonSearchBar, \
                SearchBar, or SearchBarInputField in app UI is flagged here, \
                wherever it sits — content row, first lazy item, or a custom top \
                bar's title slot. A raw TextField/OutlinedTextField is flagged only \
                when its own source reliably identifies it as search (a "search" \
                string literal, a `*.string.*search*` resource, or an IconSearch \
                leading icon); ordinary form fields are never flagged.

                The shared library modules (com.vayunmathur.library.*) are exempt \
                because the scaffolds and their field are built there. TV text \
                fields are a different chrome family and are not flagged.

                Dialog/sheet/drawer-local filters, expandable overlay search whose \
                overlay is the results surface, and other genuinely non-screen \
                search opt out per file with the marker comment \
                `// SCAFFOLD SEARCH EXCEPTION: <reason>`.
                """.trimIndent(),
            category = Category.CORRECTNESS,
            priority = 6,
            severity = Severity.ERROR,
            implementation = Implementation(
                ScaffoldSearchDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
