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
class ScaffoldSearchDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = ScaffoldSearchDetector()

    override fun getIssues(): List<Issue> = listOf(ScaffoldSearchDetector.ISSUE)

    @Test
    fun commonSearchBarInContentIsFlagged() {
        lint().files(UI_STUBS, COMPOSABLE_STUB, LAYOUT_STUB, kotlin(
            """
            package com.vayunmathur.example.ui
            import androidx.compose.runtime.Composable
            import com.vayunmathur.library.ui.AppScaffold
            import com.vayunmathur.library.ui.CommonSearchBar
            @Composable fun Screen(q: String) {
                AppScaffold(title = "T") {
                    CommonSearchBar(value = q, onValueChange = {})
                }
            }
            """.trimIndent(),
        )).run().expectErrorCount(1)
    }

    @Test
    fun commonSearchBarInCustomTopBarTitleIsFlagged() {
        lint().files(UI_STUBS, COMPOSABLE_STUB, LAYOUT_STUB, kotlin(
            """
            package com.vayunmathur.example.ui
            import androidx.compose.runtime.Composable
            import com.vayunmathur.library.ui.CommonSearchBar
            import com.vayunmathur.library.ui.LazyListScaffold
            import com.vayunmathur.library.ui.TopAppBar
            @Composable fun Screen(q: String) {
                LazyListScaffold(topBar = {
                    TopAppBar(title = { CommonSearchBar(value = q, onValueChange = {}) })
                }) { }
            }
            """.trimIndent(),
        )).run().expectErrorCount(1)
    }

    @Test
    fun scaffoldSearchParameterIsClean() {
        lint().files(UI_STUBS, COMPOSABLE_STUB, LAYOUT_STUB, kotlin(
            """
            package com.vayunmathur.example.ui
            import androidx.compose.runtime.Composable
            import com.vayunmathur.library.ui.AppScaffold
            import com.vayunmathur.library.ui.ScaffoldSearch
            @Composable fun Screen(q: String) {
                AppScaffold(
                    title = "T",
                    search = ScaffoldSearch(query = q, onQueryChange = {}),
                ) { }
            }
            """.trimIndent(),
        )).run().expectClean()
    }

    @Test
    fun rawSearchBarAndInputFieldAreFlagged() {
        lint().files(UI_STUBS, COMPOSABLE_STUB, LAYOUT_STUB, kotlin(
            """
            package com.vayunmathur.example.ui
            import androidx.compose.runtime.Composable
            import com.vayunmathur.library.ui.SearchBar
            import com.vayunmathur.library.ui.SearchBarInputField
            @Composable fun Screen(q: String) {
                SearchBar(inputField = {}, expanded = false, onExpandedChange = {}) { }
                SearchBarInputField(query = q, onQueryChange = {}, onSearch = {}, expanded = false, onExpandedChange = {})
            }
            """.trimIndent(),
        )).run().expectErrorCount(2)
    }

    @Test
    fun searchTextFieldByLiteralIsFlagged() {
        lint().files(UI_STUBS, COMPOSABLE_STUB, LAYOUT_STUB, kotlin(
            """
            package com.vayunmathur.example.ui
            import androidx.compose.runtime.Composable
            import com.vayunmathur.library.ui.OutlinedTextField
            import com.vayunmathur.library.ui.Text
            @Composable fun Screen(q: String) {
                OutlinedTextField(value = q, onValueChange = {}, label = { Text("Search stars") })
            }
            """.trimIndent(),
        )).run().expectErrorCount(1)
    }

    @Test
    fun searchTextFieldByResourceIsFlagged() {
        lint().files(UI_STUBS, COMPOSABLE_STUB, LAYOUT_STUB, kotlin(
            """
            package com.vayunmathur.example.ui
            import androidx.compose.runtime.Composable
            import com.vayunmathur.library.ui.OutlinedTextField
            import com.vayunmathur.library.ui.Text
            @Composable fun Screen(q: String) {
                OutlinedTextField(
                    value = q,
                    onValueChange = {},
                    label = { Text(com.vayunmathur.example.R.string.search_stars) },
                )
            }
            """.trimIndent(),
        )).run().expectErrorCount(1)
    }

    @Test
    fun ordinaryFormFieldIsClean() {
        lint().files(UI_STUBS, COMPOSABLE_STUB, LAYOUT_STUB, kotlin(
            """
            package com.vayunmathur.example.ui
            import androidx.compose.runtime.Composable
            import com.vayunmathur.library.ui.OutlinedTextField
            import com.vayunmathur.library.ui.Text
            @Composable fun Screen(q: String) {
                OutlinedTextField(value = q, onValueChange = {}, label = { Text("Email address") })
            }
            """.trimIndent(),
        )).run().expectClean()
    }

    @Test
    fun exceptionMarkerOptsOut() {
        lint().files(UI_STUBS, COMPOSABLE_STUB, LAYOUT_STUB, kotlin(
            """
            package com.vayunmathur.example.ui
            // SCAFFOLD SEARCH EXCEPTION: dialog-local filter, no scaffold host
            import androidx.compose.runtime.Composable
            import com.vayunmathur.library.ui.CommonSearchBar
            @Composable fun Picker(q: String) {
                CommonSearchBar(value = q, onValueChange = {})
            }
            """.trimIndent(),
        )).run().expectClean()
    }

    @Test
    fun libraryPackageIsExempt() {
        lint().files(UI_STUBS, COMPOSABLE_STUB, LAYOUT_STUB, kotlin(
            """
            package com.vayunmathur.library.ui
            import androidx.compose.runtime.Composable
            @Composable fun Internal(q: String) {
                CommonSearchBar(value = q, onValueChange = {})
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

        private val LAYOUT_STUB: TestFile = kotlin(
            "src/androidx/compose/foundation/layout/Layout.kt",
            """
            package androidx.compose.foundation.layout
            class PaddingValues
            """.trimIndent(),
        )

        private val R_STUB: TestFile = kotlin(
            "src/com/vayunmathur/example/R.kt",
            """
            package com.vayunmathur.example
            object R { object string { const val search_stars: Int = 1 } }
            """.trimIndent(),
        )

        private val UI_STUBS: TestFile = kotlin(
            "src/com/vayunmathur/library/ui/UiStubs.kt",
            """
            package com.vayunmathur.library.ui
            import androidx.compose.foundation.layout.PaddingValues
            import androidx.compose.runtime.Composable
            class ScaffoldSearch(val query: String, val onQueryChange: (String) -> Unit, val placeholder: String = "Search")
            @Composable fun AppScaffold(
                title: String,
                search: ScaffoldSearch? = null,
                content: @Composable (PaddingValues) -> Unit,
            ) {}
            @Composable fun LazyListScaffold(
                topBar: @Composable () -> Unit = {},
                content: () -> Unit,
            ) {}
            @Composable fun TopAppBar(title: @Composable () -> Unit) {}
            @Composable fun CommonSearchBar(value: String, onValueChange: (String) -> Unit) {}
            @Composable fun SearchBar(inputField: @Composable () -> Unit, expanded: Boolean, onExpandedChange: (Boolean) -> Unit, content: @Composable () -> Unit) {}
            @Composable fun SearchBarInputField(query: String, onQueryChange: (String) -> Unit, onSearch: (String) -> Unit, expanded: Boolean, onExpandedChange: (Boolean) -> Unit) {}
            @Composable fun OutlinedTextField(value: String, onValueChange: (String) -> Unit, label: @Composable () -> Unit) {}
            @Composable fun Text(text: String) {}
            @Composable fun Text(resId: Int) {}
            """.trimIndent(),
        )
    }
}
