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
import org.jetbrains.uast.UElement

/**
 * Flags whole-content loading hand-rolled inside a shared scaffold's content.
 *
 * The loading state belongs to the scaffold object: `AppScaffold` (and the
 * wrappers built on it — `DetailScaffold`, `DetailLazyColumn`,
 * `LazyListScaffold`, `SetupScaffold`) take `isLoading` / `loadingMessage`
 * and render the canonical `LoadingState` instead of the content while it is
 * true. A screen that instead branches inside its own content lambda —
 * `if (loading) LoadingState(...)`, or a centred spinner in a full-size box —
 * re-derives that swap per screen, composes its empty state under the spinner
 * on some of them, and drifts from the one shape.
 *
 * Scope — whole-content only, so legitimate local loading is not flagged:
 * a loading call is reported only when ALL of these hold:
 *  - it resolves to `LoadingState`, `LoadingIndicator`,
 *    `CircularProgressIndicator` or `ContainedLoadingIndicator` in
 *    `com.vayunmathur.library.ui` (or the Material 3 originals behind them);
 *  - it sits lexically inside the content lambda of a shared-scaffold call
 *    (so a standalone spinner composed *before* any scaffold exists — an
 *    early return while a photo/video loads — is not flagged, and neither is
 *    one inside a raw-`Scaffold` exception file, which owns its own chrome);
 *  - it is whole-content: a `LoadingState` (whole-content by definition), or
 *    an indicator whose own modifier or an enclosing container between it
 *    and the scaffold fills the content area (`fillMaxSize()` /
 *    `fillParentMaxSize()`).
 *
 * Exempt by shape (documented in the report message): determinate indicators
 * (`progress = ...`), sized indicators (a `Modifier.size(...)` slot — the
 * button/row-local spinner), indicators inside a button or a pull-to-refresh
 * box, and section-local indicators that do not fill the content area (a
 * `fillMaxWidth()` box above still-visible content). The shared library
 * (`com.vayunmathur.library.*`) is exempt: the scaffolds are built there.
 *
 * Per-file opt-out: `// SCAFFOLD LOADING EXCEPTION: <reason>`.
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

        // Determinate progress is a transfer gauge, not a loading state.
        if (method.parameterList.parameters.any { it.name == "progress" }) return

        val ancestors = ancestorsOf(node)

        // Local slots: a button's own spinner, or pull-to-refresh chrome.
        if (ancestors.any { it is UCallExpression && it.methodName in LOCAL_SLOT_CALLS }) return

        // Must be inside a shared scaffold's content lambda.
        val scaffoldIndex = ancestors.indexOfFirst {
            it is UCallExpression && it.methodName in SCAFFOLD_CALLS
        }
        if (scaffoldIndex == -1) return

        val isWholeContent = when (node.methodName) {
            "LoadingState" -> true
            else -> {
                // A sized indicator is a local slot (button/row spinner).
                if (compact(node.asSourceString()).contains(".size(")) return
                val between = ancestors.subList(0, scaffoldIndex)
                val own = compact(node.asSourceString())
                own.contains(FULL_SIZE) ||
                    own.contains(FULL_PARENT_SIZE) ||
                    // Only container *calls* count: a lambda/if ancestor's source
                    // spans sibling branches that may fill the size for other reasons.
                    between.any {
                        it is UCallExpression &&
                            (compact(it.asSourceString()).contains(FULL_SIZE) ||
                                compact(it.asSourceString()).contains(FULL_PARENT_SIZE))
                    }
            }
        }
        if (!isWholeContent) return

        context.report(
            ISSUE,
            node,
            context.getLocation(node),
            "Whole-content loading must go through the scaffold: pass " +
                "`isLoading = <loading>` (and `loadingMessage` if the wait needs words) to " +
                "AppScaffold / DetailScaffold / DetailLazyColumn / LazyListScaffold / " +
                "SetupScaffold instead of rendering `${node.methodName}` inside its content. " +
                "Local loading is not this and stays inline: a sized indicator, a determinate " +
                "`progress = ...` indicator, an indicator inside a Button/IconButton or a " +
                "pull-to-refresh box, or a section-local indicator that does not fill the " +
                "content area. If this whole-content loading genuinely cannot go through " +
                "the scaffold, add the `$EXCEPTION_MARKER <reason>` marker comment to the file.",
        )
    }

    /** Whitespace-insensitive source match (lint's whitespace test mode re-spaces code). */
    private fun compact(text: String): String = text.replace(WHITESPACE, "")

    private fun ancestorsOf(node: UElement): List<UElement> {
        val result = mutableListOf<UElement>()
        var current = node.uastParent
        while (current != null) {
            result.add(current)
            current = current.uastParent
        }
        return result
    }

    private fun isLoadingCall(method: PsiMethod): Boolean {
        val containing = method.containingClass?.qualifiedName ?: return false
        return containing.startsWith(LIBRARY_UI_PREFIX) ||
            containing.startsWith(MATERIAL3_PREFIX)
    }

    companion object {
        private const val APP_PACKAGE_PREFIX = "com.vayunmathur."
        private const val LIBRARY_PREFIX = "com.vayunmathur.library"
        private const val LIBRARY_UI_PREFIX = "com.vayunmathur.library.ui"
        private const val MATERIAL3_PREFIX = "androidx.compose.material3"

        const val EXCEPTION_MARKER = "// SCAFFOLD LOADING EXCEPTION:"

        private val WHITESPACE = Regex("\\s+")

        private const val FULL_SIZE = "fillMaxSize()"
        private const val FULL_PARENT_SIZE = "fillParentMaxSize()"

        private val LOADING_CALLS = setOf(
            "LoadingState",
            "LoadingIndicator",
            "CircularProgressIndicator",
            "ContainedLoadingIndicator",
        )

        private val SCAFFOLD_CALLS = setOf(
            "AppScaffold",
            "DetailScaffold",
            "DetailLazyColumn",
            "LazyListScaffold",
            "SetupScaffold",
            "ListPage",
            "ListPageContent",
        )

        /** Composables whose own progress affordance is local, never whole-content. */
        private val LOCAL_SLOT_CALLS = setOf(
            "Button",
            "TextButton",
            "OutlinedButton",
            "FilledTonalButton",
            "ElevatedButton",
            "IconButton",
            "AppPullToRefreshBox",
            "PullToRefreshBox",
        )

        private fun isLibraryPackage(packageName: String): Boolean =
            packageName == LIBRARY_PREFIX || packageName.startsWith("$LIBRARY_PREFIX.")

        val ISSUE: Issue = Issue.create(
            id = "ScaffoldLoading",
            briefDescription = "Whole-content loading rendered inside scaffold content",
            explanation = """
                Whole-content loading belongs to the scaffold object. AppScaffold and the \
                shared wrappers built on it (DetailScaffold, DetailLazyColumn, \
                LazyListScaffold, SetupScaffold) accept `isLoading` / `loadingMessage` and \
                render the canonical LoadingState instead of the content while loading is \
                true, with the screen's bars and chrome left in place. Rendering a \
                LoadingState, or a centred LoadingIndicator / CircularProgressIndicator in \
                a container that fills the content area, inside a scaffold's content lambda \
                re-derives that swap per screen and is flagged here.

                Only whole-content loading is flagged. Local loading stays inline and is \
                exempt by shape: a determinate (`progress = ...`) indicator, a sized \
                indicator (the button/row-local spinner slot), an indicator inside a \
                Button/IconButton or a pull-to-refresh box, a section-local indicator that \
                does not fill the content area, and a standalone indicator composed before \
                any scaffold exists (an early return while content loads). The shared \
                library modules (com.vayunmathur.library.*) are exempt because the \
                scaffolds and the canonical indicators are built there.

                If a whole-content loading state genuinely cannot go through the scaffold, \
                add the marker comment `// SCAFFOLD LOADING EXCEPTION: <reason>` to the file.
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
