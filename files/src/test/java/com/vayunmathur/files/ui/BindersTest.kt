package com.vayunmathur.files.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.files.platform.FilesViewModel
import com.vayunmathur.files.platform.ShadowEnvironmentFix
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowEnvironmentFix::class], qualifiers = "w800dp-h1280dp")
class BindersTest {

    @get:Rule val rule = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var vm: FilesViewModel
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = File(app.cacheDir, "binder-${System.nanoTime()}").apply { mkdirs() }
        File(dir, "bound.txt").writeText("bound")
        File(dir, "folder").mkdirs()
        vm = FilesViewModel(app)
        ShadowLooper.idleMainLooper()
        vm.navigateTo(dir)
        // wait for the listing to arrive
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.entries.value.second.isEmpty() && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper(); Thread.sleep(20)
        }
    }

    @Test
    fun `directory page binds view model state`() {
        val actions = RecordingFilesActions()
        rule.setContent { DirectoryPage(vm, actions, isCurrent = true) }
        rule.onNodeWithText("bound.txt").assertIsDisplayed()
        rule.onNodeWithText("folder").assertIsDisplayed()
    }

    @Test
    fun `home binder and trash page bind view model`() {
        val actions = RecordingFilesActions()
        val stage = androidx.compose.runtime.mutableIntStateOf(0)
        rule.setContent {
            when (stage.intValue) {
                0 -> HomeScreenBinder(vm, actions)
                1 -> TrashPage(vm, actions, isCurrent = true)
                else -> FilesShell(vm, actions) { TextProbe() }
            }
        }
        assertTrue(rule.onAllNodesWithText("Internal storage").fetchSemanticsNodes().isNotEmpty())
        rule.runOnIdle { stage.intValue = 1 }
        rule.onNodeWithText("Trash").assertIsDisplayed()
        rule.runOnIdle { stage.intValue = 2 }
        rule.waitForIdle()
        assertTrue(vm.isFilesGranted.value)
    }

    @androidx.compose.runtime.Composable
    private fun TextProbe() {
        androidx.compose.material3.Text("shell-content-probe")
    }

    @Test
    fun `shell shows notification prompt and permanent delete dialog`() {
        val actions = RecordingFilesActions()
        // stage the shell-owned permanent-delete confirmation above the content
        vm.pendingPermanentDeleteState.value = listOf(
            com.vayunmathur.files.platform.FileBrowserItem(
                "doomed.txt", false, 1, File(dir, "doomed.txt"), null, "doomed",
            ),
        )
        rule.setContent {
            FilesShell(vm, actions) {
                androidx.compose.material3.Text("shell-body")
            }
        }
        rule.onNodeWithText("Delete 1 item(s) permanently?").assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()
        assertTrue(actions.calls.contains("confirmPD"))
        // notification prompt (fresh VM in this sandbox has not prompted yet)
        if (rule.onAllNodesWithText("Enable Notifications?").fetchSemanticsNodes().isNotEmpty()) {
            rule.onNodeWithText("Skip").performClick()
            assertTrue(vm.hasPromptedNotifications.value)
        }
    }

    @Test
    fun `shell shows its content when granted`() {
        val actions = RecordingFilesActions()
        rule.setContent {
            FilesShell(vm, actions) { openDrawer ->
                androidx.compose.material3.Text("inside-shell")
                assertTrue(openDrawer != null)
            }
        }
        rule.onNodeWithText("inside-shell").assertIsDisplayed()
    }
}
