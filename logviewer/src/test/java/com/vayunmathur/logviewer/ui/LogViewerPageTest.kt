package com.vayunmathur.logviewer.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.logviewer.domain.LogDocument
import com.vayunmathur.logviewer.domain.LogKind
import com.vayunmathur.logviewer.platform.LogLoadResult
import com.vayunmathur.logviewer.platform.LogViewerViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class LogViewerPageTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val actions = FakeLogViewerActions()

    private fun page(vm: LogViewerViewModel, finishes: AtomicInteger) {
        composeRule.setContent {
            DynamicTheme {
                LogViewerPage(
                    viewModel = vm,
                    actions = actions,
                    onFinish = { finishes.incrementAndGet() },
                )
            }
        }
    }

    @Test
    fun `silently unavailable page finishes itself`() {
        val finishes = AtomicInteger(0)
        val vm = LogViewerViewModel(app)
        vm.start(loader = { LogLoadResult.Unavailable() }, chrome = { LogViewerViewModel.Chrome() })
        page(vm, finishes)
        composeRule.waitUntil(timeoutMillis = 10_000) { finishes.get() > 0 }
        assertEquals(1, finishes.get())
    }

    @Test
    fun `unavailable with a message stays open and explains`() {
        val finishes = AtomicInteger(0)
        val vm = LogViewerViewModel(app)
        vm.start(
            loader = { LogLoadResult.Unavailable(com.vayunmathur.logviewer.R.string.error_system_log_secondary_user) },
            chrome = { LogViewerViewModel.Chrome() },
        )
        page(vm, finishes)
        composeRule.onNodeWithText("The device log is only available to the primary user")
            .assertIsDisplayed()
        assertEquals(0, finishes.get())
    }

    @Test
    fun `loaded page shows the document and stays open`() {
        val finishes = AtomicInteger(0)
        val vm = LogViewerViewModel(app)
        vm.start(
            loader = {
                LogLoadResult.Loaded(
                    LogDocument(
                        kind = LogKind.ErrorReport,
                        sourcePackage = null,
                        title = "Report title",
                        headerLines = emptyList(),
                        bodyLines = listOf("the report body"),
                    )
                )
            },
            chrome = { LogViewerViewModel.Chrome() },
        )
        page(vm, finishes)
        composeRule.onNodeWithText("Report title").assertIsDisplayed()
        composeRule.onNodeWithText("the report body").assertIsDisplayed()
        assertEquals(0, finishes.get())
    }
}
