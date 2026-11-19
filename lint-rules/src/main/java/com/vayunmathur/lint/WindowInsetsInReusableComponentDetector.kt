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
import org.jetbrains.uast.UCallExpression

/**
 * Flags window-inset modifiers (`imePadding()`, `navigationBarsPadding()`,
 * `systemBarsPadding()`) used in reusable components under the shared UI package
 * `com.vayunmathur.library.ui`.
 *
 * These components are hosted in many different screens and do not know their
 * host's inset ownership. `MainNavigation` is the single owner of the IME inset
 * for every screen it hosts (it applies `imePadding()` once), and standalone
 * roots apply it exactly once at their own Scaffold. A reusable component that
 * also applies an inset makes it count twice - e.g. an editor toolbar floating a
 * keyboard's height above the keyboard.
 *
 * Let the host own the inset. If a genuinely host-agnostic overlay must own its
 * own inset, suppress this on the specific declaration with a justification.
 */
class WindowInsetsInReusableComponentDetector : Detector(), SourceCodeScanner {

    override fun getApplicableMethodNames(): List<String> =
        listOf("imePadding", "navigationBarsPadding", "systemBarsPadding")

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        if (!context.evaluator.isMemberInClass(method, WINDOW_INSETS_CLASS)) return
        val packageName = context.uastFile?.packageName ?: return
        if (!packageName.startsWith(REUSABLE_PACKAGE)) return
        val name = node.methodName ?: return
        context.report(
            ISSUE,
            node,
            context.getLocation(node),
            "`$name()` must not be called in a reusable `$REUSABLE_PACKAGE` component. " +
                "The host (MainNavigation, or a standalone root Scaffold) owns window insets.",
        )
    }

    companion object {
        private const val REUSABLE_PACKAGE = "com.vayunmathur.library.ui"

        // All three are top-level extension functions on Modifier in this class.
        private const val WINDOW_INSETS_CLASS =
            "androidx.compose.foundation.layout.WindowInsetsPaddingKt"

        val ISSUE: Issue = Issue.create(
            id = "WindowInsetsInReusableComponent",
            briefDescription = "Window inset modifier in a reusable component",
            explanation = """
                Reusable components under `com.vayunmathur.library.ui` are hosted in many \
                different screens and do not know their host's inset ownership, so they must \
                not apply window insets (`imePadding()`, `navigationBarsPadding()`, \
                `systemBarsPadding()`) themselves.

                `MainNavigation` is the single owner of the IME inset for every screen it \
                hosts - it applies `imePadding()` once - and standalone Activities apply it \
                exactly once at their own root Scaffold. A reusable component that also applies \
                an inset makes it count twice (for example, an editor toolbar floating a \
                keyboard's height above the keyboard).

                Let the host own the inset. If a genuinely host-agnostic overlay must own its \
                own inset, suppress this issue on the specific declaration with a justification.
                """.trimIndent(),
            category = Category.CORRECTNESS,
            priority = 6,
            severity = Severity.ERROR,
            implementation = Implementation(
                WindowInsetsInReusableComponentDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
