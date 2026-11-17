package com.vayunmathur.madeveloper.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.madeveloper.data.GitHostRepository
import com.vayunmathur.madeveloper.domain.CatalogApp
import com.vayunmathur.madeveloper.domain.InstallPhase
import com.vayunmathur.madeveloper.domain.PendingInstall
import com.vayunmathur.madeveloper.domain.TestRun
import com.vayunmathur.madeveloper.domain.TestsStatus
import com.vayunmathur.madeveloper.platform.FakeGitHost
import com.vayunmathur.madeveloper.platform.MaDevUiState
import com.vayunmathur.madeveloper.platform.MaDevViewModel
import com.vayunmathur.madeveloper.platform.PairingStore
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every MADeveloper screen rendered for real: the state-driven screens
 * over hand-built state, and the ViewModel-driven repo/commits/commit/
 * file screens over the fake git host, exercising buttons, the branch
 * selector, the file filter and the tree navigation.
 */
@RunWith(RobolectricTestRunner::class)
class MaDevScreensTest {

    @get:Rule val rule = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val host = FakeGitHost()

    @BeforeTest fun setUp() {
        PairingStore(app).clear()
    }

    private fun vm(): MaDevViewModel {
        val viewModel = MaDevViewModel(app)
        viewModel.repo = GitHostRepository(host.fetcher)
        return viewModel
    }

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            rule.waitForIdle()
            if (runCatching { cond() }.getOrDefault(false)) return
            Thread.sleep(25)
        }
        assertTrue(cond(), "timed out: $desc")
    }

    private fun awaitText(text: String, substring: Boolean = false) {
        await("text '$text'") {
            rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun clickButton(text: String) {
        rule.onNode(androidx.compose.ui.test.hasScrollAction())
            .performScrollToNode(androidx.compose.ui.test.hasText(text))
        rule.onAllNodesWithText(text, useUnmergedTree = true)[0].performClick()
    }

    /** Clicks the tappable row/button that contains [text]. */
    private fun clickRow(text: String) {
        val matcher = androidx.compose.ui.test.hasClickAction() and
            androidx.compose.ui.test.hasText(text)
        rule.onNode(androidx.compose.ui.test.hasScrollAction())
            .performScrollToNode(androidx.compose.ui.test.hasText(text))
        rule.waitForIdle()
        rule.onAllNodes(matcher)[0].performScrollTo().performClick()
    }

    private fun state() = MaDevUiState(
        pairingCode = "ABC234",
        tests = TestsStatus(
            latest = TestRun("a", "abc123", "fix thing", "passed", 2, 10, null),
            running = TestRun("r", "fff000", "running now", "running", 0, 0, null),
            history = listOf(TestRun("b", "def456", "old run", "failed", 1, 5, null)),
            coveragePct = 50.0,
        ),
        repos = listOf(
            com.vayunmathur.madeveloper.domain.GitRepo("Modern-Apps", "main", "aabbccdd11223344", "top subject", "2026-11-16"),
            com.vayunmathur.madeveloper.domain.GitRepo("MAOS", "main", "1111222233334444", "maos subject", "2026-11-15"),
        ),
        catalog = listOf(
            CatalogApp("com.example.app", "Example", "Demo app", "1.0", 1L, 2048L, "/a_1.apk"),
        ),
    )

    @Test fun homeShowsCiReposAndFiresNavigation() {
        var openedRepo: String? = null
        var testsOpened = 0
        var catalogOpened = 0
        var pushOpened = 0
        var refreshes = 0
        rule.setContent {
            HomeScreen(
                state(),
                onRefresh = { refreshes++ },
                onOpenRepo = { openedRepo = it },
                onOpenTests = { testsOpened++ },
                onOpenCatalog = { catalogOpened++ },
                onOpenPush = { pushOpened++ },
            )
        }
        rule.onNodeWithText("Developer").assertIsDisplayed()
        rule.onNodeWithText("Push socket offline").assertExists()
        rule.onNodeWithText("Pairing code: ABC234").assertExists()
        rule.onNode(androidx.compose.ui.test.hasScrollAction())
            .performScrollToNode(androidx.compose.ui.test.hasText("passed"))
        rule.onNodeWithText("passed").assertIsDisplayed()
        rule.onNodeWithText("Coverage 50.0%").assertIsDisplayed()
        clickRow("Pushed installs")
        clickRow("Test history")
        clickRow("Available builds")
        rule.onNodeWithContentDescription("Refresh").performClick()
        clickRow("Modern-Apps")
        assertEquals("Modern-Apps", openedRepo)
        assertEquals(1, testsOpened)
        assertEquals(1, catalogOpened)
        assertEquals(1, pushOpened)
        assertEquals(1, refreshes)
    }

    @Test fun homeLoadingAndErrorStatesRender() {
        rule.setContent {
            HomeScreen(
                MaDevUiState(loading = true, error = "HTTP 404 for repos"),
                {}, {}, {}, {}, {},
            )
        }
        rule.onNode(androidx.compose.ui.test.hasScrollAction())
            .performScrollToNode(androidx.compose.ui.test.hasText("HTTP 404 for repos"))
        rule.onNodeWithText("HTTP 404 for repos").assertIsDisplayed()
    }

    @Test fun homeEmptyAndLanguageChipRender() {
        rule.setContent {
            HomeScreen(MaDevUiState(pairingCode = "ABC234"), {}, {}, {}, {}, {})
        }
        rule.onNode(androidx.compose.ui.test.hasScrollAction())
            .performScrollToNode(androidx.compose.ui.test.hasText("No repositories"))
        rule.onNodeWithText("No repositories").assertIsDisplayed()
    }

    @Test fun testsAndCatalogScreensListTheirRows() {
        var page by androidx.compose.runtime.mutableStateOf(0)
        rule.setContent {
            when (page) {
                0 -> TestsScreen(state()) {}
                1 -> CatalogScreen(state()) {}
                2 -> TestsScreen(MaDevUiState()) {}
                else -> CatalogScreen(MaDevUiState()) {}
            }
        }
        rule.onNodeWithText("running now").assertIsDisplayed()
        rule.onNodeWithText("old run").assertIsDisplayed()
        rule.runOnIdle { page = 1 }
        rule.onNodeWithText("Example").assertIsDisplayed()
        rule.onNodeWithText("com.example.app · 1.0 (1) · 2 KB", substring = true).assertIsDisplayed()
        rule.runOnIdle { page = 2 }
        rule.onNodeWithText("No CI data loaded yet.").assertIsDisplayed()
        rule.runOnIdle { page = 3 }
        rule.onNodeWithText("No catalog loaded yet.").assertIsDisplayed()
    }

    @Test fun pushScreenShowsPairingPendingAndReadyInstall() {
        var installed: String? = null
        val pending = listOf(
            PendingInstall("com.example.ready", 7L, "https://x/a.apk", "sha", 10L, InstallPhase.READY, 1f),
            PendingInstall("com.example.busy", 7L, "https://x/b.apk", "sha", 10L, InstallPhase.DOWNLOADING, 0.5f),
            PendingInstall("com.example.bad", 7L, "https://x/c.apk", "sha", 10L, InstallPhase.FAILED, 0f, "hash mismatch"),
        )
        rule.setContent {
            PushScreen(MaDevUiState(pairingCode = "XYZ789", pending = pending), onInstall = { installed = it }, onBack = {})
        }
        rule.onNodeWithText("Pairing code: XYZ789").assertIsDisplayed()
        rule.onNodeWithText("Socket offline; reconnecting with backoff").assertIsDisplayed()
        rule.onNodeWithText("com.example.ready").assertIsDisplayed()
        // Only the READY push gets an Install button.
        assertEquals(1, rule.onAllNodesWithText("Install").fetchSemanticsNodes().size)
        clickButton("Install")
        assertEquals("com.example.ready", installed)
    }

    @Test fun pushScreenWaitingForApproval() {
        rule.setContent {
            PushScreen(
                MaDevUiState(pairingCode = "123456", awaitingApproval = true),
                onInstall = {},
            ) {}
        }
        rule.onNodeWithText("Pairing code: 123456").assertIsDisplayed()
        rule.onNodeWithText("Waiting for approval in the server's /dev panel").assertIsDisplayed()
    }

    @Test fun pushScreenPairedAndEmpty() {
        rule.setContent { PushScreen(MaDevUiState(connected = true, paired = true), onInstall = {}) {} }
        rule.onNodeWithText("Paired").assertIsDisplayed()
        rule.onNodeWithText("Socket connected to api.maproj.org").assertIsDisplayed()
        rule.onNodeWithText("No pushed installs yet. Installs always need your tap; nothing installs silently.")
            .assertIsDisplayed()
    }

    @Test fun repoScreenBrowsesTreeFilterAndCommits() {
        val viewModel = vm()
        var openedFile: Pair<String, String>? = null
        var openedCommits: String? = null
        var openedCommit: Pair<String, String>? = null
        rule.setContent {
            RepoScreen(
                "Modern-Apps", viewModel,
                onOpenFile = { branch, path -> openedFile = branch to path },
                onOpenCommits = { openedCommits = it },
                onOpenCommit = { branch, sha -> openedCommit = branch to sha },
            ) {}
        }
        await("repo loaded") { viewModel.browser("Modern-Apps") != null }
        rule.onNodeWithText("https://git.maproj.org/Modern-Apps.git").assertIsDisplayed()
        rule.onNodeWithText("Recent commits").assertIsDisplayed()
        rule.onNodeWithText("recent work").assertIsDisplayed()
        rule.onNode(androidx.compose.ui.test.hasScrollAction())
            .performScrollToNode(androidx.compose.ui.test.hasText("src"))
        rule.onNodeWithText("src").assertIsDisplayed()
        // Descend into the folder, then read the file inside it.
        clickRow("src")
        await("descended") { viewModel.browser("Modern-Apps")!!.path == "src" }
        rule.onNodeWithText("Main.kt").assertIsDisplayed()
        clickButton("Root")
        await("back at root") { viewModel.browser("Modern-Apps")!!.path == "" }
        // The file filter narrows the tree listing.
        rule.onNodeWithText("Filter files").performTextInput("read")
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithText("src").fetchSemanticsNodes().size)
        rule.onNodeWithText("README.md").assertIsDisplayed()
        // Commit + commits navigation callbacks fire.
        clickRow("recent work")
        assertEquals("main", openedCommit!!.first)
        clickButton("All commits")
        assertEquals("main", openedCommits)
        assertEquals(null, openedFile)
    }

    @Test fun commitsCommitAndFileScreensRenderLoadedData() {
        val viewModel = vm()
        var openedCommit: String? = null
        var page by androidx.compose.runtime.mutableStateOf(0)
        rule.setContent {
            when (page) {
                0 -> CommitsScreen("Modern-Apps", "main", viewModel, onOpenCommit = { openedCommit = it }) {}
                1 -> CommitScreen("Modern-Apps", "recent0001aaaa", viewModel) {}
                2 -> FileScreen("Modern-Apps", "src/Main.kt", "main", viewModel) {}
                3 -> FileScreen("Modern-Apps", "src/logo.png", "main", viewModel) {}
                else -> CommitScreen("Modern-Apps", "missing0000", viewModel) {}
            }
        }
        await("commits loaded") { viewModel.browser("Modern-Apps")?.commits?.isNotEmpty() == true }
        rule.onNodeWithText("recent work").assertIsDisplayed()
        clickRow("recent work")
        assertEquals("recent0001aaaa", openedCommit)

        rule.runOnIdle { page = 1 }
        rule.onNodeWithText("recent work").assertIsDisplayed()
        rule.onNodeWithText("vayun").assertIsDisplayed()
        rule.onNodeWithText("recent0001aaaa").assertIsDisplayed()
        rule.onNodeWithText("notes").assertIsDisplayed()

        rule.runOnIdle { page = 2 }
        awaitText("println(1)", substring = true)
        rule.runOnIdle { page = 3 }
        awaitText("Binary file", substring = true)

        // A commit outside the loaded pages says so instead of inventing data.
        rule.runOnIdle { page = 4 }
        rule.onNodeWithText("Commit not in the loaded pages. Open the commits list and load more, or view it on the git host.")
            .assertIsDisplayed()
    }
}
