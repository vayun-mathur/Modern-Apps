package com.vayunmathur.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class ScaffoldLoadingDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = ScaffoldLoadingDetector()

    override fun getIssues(): List<Issue> = listOf(ScaffoldLoadingDetector.ISSUE)

    @Test
    fun loadingStateInsideAppScaffoldIsFlagged() {
        lint().files(UI_STUBS, MODIFIER_STUB, LAYOUT_STUBS, COMPOSABLE_STUB, kotlin(
            """
            package com.vayunmathur.example.ui
            import androidx.compose.runtime.Composable
            import com.vayunmathur.library.ui.AppScaffold
            import com.vayunmathur.library.ui.LoadingState
            @Composable fun Screen(loading: Boolean) {
                AppScaffold(title = "T") {
                    if (loading) LoadingState()
                }
            }
            """.trimIndent(),
        )).run().expectErrorCount(1)
    }

    @Test
    fun fullSizeIndicatorInsideAppScaffoldIsFlagged() {
        lint().files(UI_STUBS, MODIFIER_STUB, LAYOUT_STUBS, COMPOSABLE_STUB, kotlin(
            """
            package com.vayunmathur.example.ui
            import androidx.compose.foundation.layout.Box
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import com.vayunmathur.library.ui.AppScaffold
            import com.vayunmathur.library.ui.LoadingIndicator
            @Composable fun Screen(loading: Boolean) {
                AppScaffold(title = "T") {
                    if (loading) Box(Modifier.fillMaxSize()) { LoadingIndicator() }
                }
            }
            """.trimIndent(),
        )).run().expectErrorCount(1)
    }

    @Test
    fun scaffoldLoadingParameterIsClean() {
        lint().files(UI_STUBS, MODIFIER_STUB, LAYOUT_STUBS, COMPOSABLE_STUB, kotlin(
            """
            package com.vayunmathur.example.ui
            import androidx.compose.foundation.layout.Box
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import com.vayunmathur.library.ui.AppScaffold
            @Composable fun Screen(loading: Boolean) {
                AppScaffold(title = "T", isLoading = loading) {
                    Box(Modifier.fillMaxSize()) { }
                }
            }
            """.trimIndent(),
        )).run().expectClean()
    }

    @Test
    fun sectionLocalIndicatorIsClean() {
        lint().files(UI_STUBS, MODIFIER_STUB, LAYOUT_STUBS, COMPOSABLE_STUB, kotlin(
            """
            package com.vayunmathur.example.ui
            import androidx.compose.foundation.layout.Box
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import com.vayunmathur.library.ui.AppScaffold
            import com.vayunmathur.library.ui.LoadingIndicator
            @Composable fun Screen(loading: Boolean) {
                AppScaffold(title = "T") {
                    if (loading) Box(Modifier.fillMaxWidth()) { LoadingIndicator() }
                }
            }
            """.trimIndent(),
        )).run().expectClean()
    }

    @Test
    fun sizedDeterminateAndButtonIndicatorsAreClean() {
        lint().files(UI_STUBS, MODIFIER_STUB, LAYOUT_STUBS, COMPOSABLE_STUB, kotlin(
            """
            package com.vayunmathur.example.ui
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import com.vayunmathur.library.ui.AppScaffold
            import com.vayunmathur.library.ui.Button
            import com.vayunmathur.library.ui.CircularProgressIndicator
            import com.vayunmathur.library.ui.LoadingIndicator
            @Composable fun Screen(saving: Boolean) {
                AppScaffold(title = "T") {
                    if (saving) CircularProgressIndicator(Modifier.size(16))
                    LoadingIndicator(progress = { 0.5f })
                    Button(onClick = {}) { CircularProgressIndicator() }
                }
            }
            """.trimIndent(),
        )).run().expectClean()
    }

    @Test
    fun standaloneIndicatorBeforeScaffoldIsClean() {
        lint().files(UI_STUBS, MODIFIER_STUB, LAYOUT_STUBS, COMPOSABLE_STUB, kotlin(
            """
            package com.vayunmathur.example.ui
            import androidx.compose.foundation.layout.Box
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            import com.vayunmathur.library.ui.AppScaffold
            import com.vayunmathur.library.ui.LoadingIndicator
            @Composable fun Screen(photo: String?) {
                if (photo == null) {
                    Box(Modifier.fillMaxSize()) { LoadingIndicator() }
                    return
                }
                AppScaffold(title = "T") { Box(Modifier.fillMaxSize()) { } }
            }
            """.trimIndent(),
        )).run().expectClean()
    }

    @Test
    fun exceptionMarkerOptsOut() {
        lint().files(UI_STUBS, MODIFIER_STUB, LAYOUT_STUBS, COMPOSABLE_STUB, kotlin(
            """
            package com.vayunmathur.example.ui
            // SCAFFOLD LOADING EXCEPTION: pager pages own their loading chrome
            import androidx.compose.runtime.Composable
            import com.vayunmathur.library.ui.AppScaffold
            import com.vayunmathur.library.ui.LoadingState
            @Composable fun Screen(loading: Boolean) {
                AppScaffold(title = "T") {
                    if (loading) LoadingState()
                }
            }
            """.trimIndent(),
        )).run().expectClean()
    }

    companion object {
        private val COMPOSABLE_STUB: TestFile = kotlin(
            "src/androidx/compose/runtime/Composable.kt",
            """
            package androidx.compose.runtime
            annotation class Composable
            """.trimIndent(),
        )

        private val MODIFIER_STUB: TestFile = kotlin(
            "src/androidx/compose/ui/Modifier.kt",
            """
            package androidx.compose.ui
            open class Modifier {
                fun fillMaxSize(): Modifier = this
                fun fillMaxWidth(): Modifier = this
                fun size(dp: Int): Modifier = this
                fun padding(values: Any): Modifier = this
                companion object : Modifier()
            }
            """.trimIndent(),
        )

        private val LAYOUT_STUBS: TestFile = kotlin(
            "src/androidx/compose/foundation/layout/Layout.kt",
            """
            package androidx.compose.foundation.layout
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            class PaddingValues
            @Composable fun Box(modifier: Modifier = Modifier, content: @Composable () -> Unit) {}
            """.trimIndent(),
        )

        private val UI_STUBS: TestFile = kotlin(
            "src/com/vayunmathur/library/ui/UiStubs.kt",
            """
            package com.vayunmathur.library.ui
            import androidx.compose.foundation.layout.PaddingValues
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Modifier
            @Composable fun AppScaffold(
                title: String,
                isLoading: Boolean = false,
                loadingMessage: String? = null,
                content: @Composable (PaddingValues) -> Unit,
            ) {}
            @Composable fun LoadingState(modifier: Modifier = Modifier, message: String? = null) {}
            @Composable fun LoadingIndicator(modifier: Modifier = Modifier) {}
            @Composable fun LoadingIndicator(progress: () -> Float, modifier: Modifier = Modifier) {}
            @Composable fun CircularProgressIndicator(modifier: Modifier = Modifier) {}
            @Composable fun CircularProgressIndicator(progress: () -> Float, modifier: Modifier = Modifier) {}
            @Composable fun Button(onClick: () -> Unit, content: @Composable () -> Unit) {}
            """.trimIndent(),
        )
    }
}
