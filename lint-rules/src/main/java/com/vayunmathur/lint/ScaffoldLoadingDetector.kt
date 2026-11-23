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
 * Flags standalone loading/refresh icons in app UI.
 *
 * Refresh indication is owned by the scaffold: `AppScaffold` (and the
 * wrappers built on it — `DetailScaffold`, `DetailLazyColumn`,
 * `LazyListScaffold`, `SetupScaffold`, `ListPage`) take
 * `isRefreshing` / `onRefresh` / `refreshEnabled` and render a Material 3
 * pull-to-refresh indicator at the top while any refresh runs, with the
 * content composed underneath. TV screens use the same pattern without a
 * gesture via `TvAppFrame(isRefreshing = ...)`; `TvLoading` in
 * `library:tv` is that family's sanctioned top indicator and is not
 * flagged. No other loading icon belongs in a screen.
 *
 * Scope: a call is reported when it resolves to `LoadingState`,
 * `LoadingIndicator`, `CircularProgressIndicator`,
 * `ContainedLoadingIndicator`, `PullToRefreshBox` or `AppPullToRefreshBox` in `com.vayunmathur.library.*` (or the Material 3
 * originals behind them) and the file is in an app package
 * (`com.vayunmathur.*` outside `com.vayunmathur.library.*`). There is no
 * sized/button/section/determinate exemption any more and no requirement
 * that the call sit inside a scaffold: whole/section loading reroutes to
 * the scaffold (or a parent scaffold), truly local progress becomes a
 * non-icon affordance (a disabled control, progress text), and real
 * determinate progress becomes a determinate bar
 * (`LinearProgressIndicator(progress = ...)`), which is not flagged.
 * Hand-rolled pull-to-refresh containers in screens are likewise flagged:
 * the scaffold's box replaces them.
 *
 * The shared library (`com.vayunmathur.library.*`) is exempt: the
 * scaffolds and their indicators are built there.
 *
 * Per-file opt-out, reserved for library-internal helpers (already exempt
 * by package) and genuinely non-screen contexts only:
 * `// SCAFFOLD LOADING EXCEPTION: <reason>`.
 */
class ScaffoldLoadingDetector : Detector(), SourceCodeScanner {

    override fun getApplicableMethodNames(): List<String> = LOADING_CALLS.toList()

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        if (!isLoadingCall(method)) return

        val packageName = context.uastFile?.packageName ?: return
        if (!packageName.startsWith(APP_PACKAGE_PREFIX)) return
        if (isLibraryPackage(packageName)) return

        val ktFile = context.uastFile?.sourcePsi as? KtFile
        if (ktFile != null && CommentOnlyMatcher.containsMarkerInComments(ktFile, EXCEPTION_MARKER)) return

        context.report(
            ISSUE,
            node,
            context.getLocation(node),
            "Loading/refresh indication is owned by the scaffold: pass " +
                "`isRefreshing = <refreshing>` (and `onRefresh`/`refreshEnabled` as needed) to " +
                "AppScaffold / DetailScaffold / DetailLazyColumn / LazyListScaffold / " +
                "SetupScaffold / ListPage (TV: TvAppFrame `isRefreshing`, or `TvLoading` " +
                "as the TV scaffold family's top indicator) instead of rendering " +
                "`${node.methodName}` in app UI. Whole/section loading reroutes to the scaffold " +
                "or a parent scaffold; truly local progress uses a non-icon affordance " +
                "(disabled state, progress text); real determinate progress uses a " +
                "determinate `LinearProgressIndicator(progress = ...)` bar, not a spinner. " +
                "The exception marker `$EXCEPTION_MARKER <reason>` is reserved for " +
                "library-internal helpers and genuinely non-screen contexts.",
        )
    }

    private fun isLoadingCall(method: PsiMethod): Boolean {
        val containing = method.containingClass?.qualifiedName ?: return false
        return containing.startsWith(LIBRARY_PREFIX) ||
            containing.startsWith(MATERIAL3_PREFIX)
    }

    companion object {
        private const val APP_PACKAGE_PREFIX = "com.vayunmathur."
        private const val LIBRARY_PREFIX = "com.vayunmathur.library"
        private const val MATERIAL3_PREFIX = "androidx.compose.material3"

        const val EXCEPTION_MARKER = "// SCAFFOLD LOADING EXCEPTION:"

        private val LOADING_CALLS = setOf(
            "LoadingState",
            "LoadingIndicator",
            "CircularProgressIndicator",
            "ContainedLoadingIndicator",
            "PullToRefreshBox",
            "AppPullToRefreshBox",
        )

        private fun isLibraryPackage(packageName: String): Boolean =
            packageName == LIBRARY_PREFIX || packageName.startsWith("$LIBRARY_PREFIX.")

        val ISSUE: Issue = Issue.create(
            id = "ScaffoldLoading",
            briefDescription = "Standalone loading icon in app UI; scaffold owns refresh indication",
            explanation = """
                Refresh indication belongs to the scaffold object. AppScaffold and the \
                shared wrappers built on it (DetailScaffold, DetailLazyColumn, \
                LazyListScaffold, SetupScaffold, ListPage) accept \
                `isRefreshing` / `onRefresh` / `refreshEnabled` and show a Material 3 \
                pull-to-refresh indicator at the top while any refresh runs, with the \
                content composed underneath; TV screens use TvAppFrame's \
                `isRefreshing` top indicator (no pull gesture). Rendering a \
                LoadingState, LoadingIndicator, CircularProgressIndicator, \
                ContainedLoadingIndicator, or a hand-rolled \
                PullToRefreshBox/AppPullToRefreshBox anywhere in app UI is flagged here, \
                whatever its size, slot, or determinacy.

                Whole/section loading reroutes to the scaffold or a parent scaffold. \
                Truly local progress uses a non-icon affordance (a disabled control, \
                progress text). Real determinate progress (uploads/downloads with a \
                percentage) is a determinate LinearProgressIndicator bar, which is not \
                flagged. The shared library modules (com.vayunmathur.library.*) are \
                exempt because the scaffolds and their indicators are built there.

                The marker comment `// SCAFFOLD LOADING EXCEPTION: <reason>` is reserved \
                for library-internal helpers and genuinely non-screen contexts only.
                """.trimIndent(),
            category = Category.CORRECTNESS,
            priority = 6,
            severity = Severity.ERROR,
            implementation = Implementation(
                ScaffoldLoadingDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
