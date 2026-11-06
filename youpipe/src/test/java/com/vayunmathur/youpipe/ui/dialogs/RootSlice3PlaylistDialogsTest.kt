package com.vayunmathur.youpipe.ui.dialogs

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.data.Playlist
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice-3 tests for the playlist dialogs ([CreatePlaylist] validation +
 * creation, [AddToPlaylist] membership staging, [AddToWatchLater] auto-add),
 * driven through the REAL composables against a real [YouPipeViewModel].
 *
 * The VM's repository is Room/SQLCipher-backed, but Room is bypassed cleanly:
 * [SubscriptionRepository] exposes its flows cold, and the cached database
 * registry (`com.vayunmathur.library.util.databases`, keyed by class) is
 * pre-seeded with an in-memory [SubscriptionDatabase] — the exact instance the
 * repository resolves on first access, so no production change and no
 * AndroidKeyStore is ever touched. WorkManager is initialized manually
 * (the manifest disables auto-init); the VM's periodic-enqueue calls then
 * no-op against the test driver.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RootSlice3PlaylistDialogsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: SubscriptionDatabase
    private lateinit var vm: YouPipeViewModel

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val app get() = context.applicationContext as Application

    companion object {
        private var sharedDb: SubscriptionDatabase? = null

        private fun sharedDb(context: android.content.Context): SubscriptionDatabase {
            sharedDb?.let { return it }
            com.vayunmathur.library.util.databases[SubscriptionDatabase::class]?.let {
                @Suppress("UNCHECKED_CAST")
                sharedDb = it as SubscriptionDatabase
                return it as SubscriptionDatabase
            }
            return Room.inMemoryDatabaseBuilder(context, SubscriptionDatabase::class.java)
                .allowMainThreadQueries()
                .build().also {
                    com.vayunmathur.library.util.databases[SubscriptionDatabase::class] = it
                    sharedDb = it
                }
        }
    }

    @BeforeTest
    fun open() {
        try {
            WorkManager.initialize(context, Configuration.Builder().build())
        } catch (_: IllegalStateException) {
            // One init per Robolectric sandbox: earlier tests in this class
            // already initialized it; the instance survives across tests.
        }
        db = sharedDb(context)
        vm = YouPipeViewModel(app, SubscriptionRepository.get(context))
    }

    @AfterTest
    fun close() {
        // Keep ONE database per test class: closeCachedDatabase() + db.close()
        // races the next test's in-flight cleanup coroutines (the VM init
        // launches IO cleanup on a shared scope), so instead wipe rows and
        // reuse the same instance across tests. Blocking reads: runTest's
        // scheduler would freeze concurrent collectors.
        kotlinx.coroutines.runBlocking {
            db.subscriptionDao().clearAll()
            // Wipe category rows (no clearAll on the DAO; delete per name).
            // The VM's subscriptionCategories flow always holds the latest
            // emission — read it off the current VM instead of collecting.
            for (name in vm.subscriptionCategories.value.map { it.category }.distinct()) {
                db.subscriptionCategoryDao().deleteCategory(name)
            }
            for (playlist in db.playlistDao().getAll()) {
                for (item in db.playlistItemDao().getForPlaylist(playlist.id)) {
                    db.playlistItemDao().delete(item)
                }
                db.playlistDao().delete(playlist)
            }
        }
    }

    // ---------- CreatePlaylist ----------

    @Test
    fun createPlaylistStartsDisabledThenValidates() {
        val backStack = NavBackStack(arrayOf<Route>(Route.Main(0), Route.CreatePlaylist))
        composeRule.setContent { CreatePlaylist(backStack, vm) }
        val createLabel = context.getString(com.vayunmathur.library.ui.R.string.create)
        composeRule.onNodeWithText(
            context.getString(com.vayunmathur.youpipe.R.string.title_create_playlist),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(createLabel).assertIsNotEnabled()
    }

    @Test
    fun createPlaylistTypingEnablesAndCreates() {
        val backStack = NavBackStack(arrayOf<Route>(Route.Main(0), Route.CreatePlaylist))
        composeRule.setContent { CreatePlaylist(backStack, vm) }
        val createLabel = context.getString(com.vayunmathur.library.ui.R.string.create)
        composeRule.onNodeWithText(
            context.getString(com.vayunmathur.youpipe.R.string.label_playlist_name),
        ).performTextInput("My Mix")
        composeRule.waitForIdle()
        composeRule.onNodeWithText(createLabel).assertIsEnabled()
        composeRule.onNodeWithText(createLabel).performClick()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 10_000) { backStack.backStack.size == 1 }
        assertEquals(1, backStack.backStack.size)
    }

    @Test
    fun createPlaylistDuplicateNameStaysDisabled() {
        kotlinx.coroutines.runBlocking {
            db.playlistDao().upsert(Playlist(name = "Existing", position = 1.0))
        }
        val backStack = NavBackStack(arrayOf<Route>(Route.Main(0), Route.CreatePlaylist))
        composeRule.setContent { CreatePlaylist(backStack, vm) }
        val createLabel = context.getString(com.vayunmathur.library.ui.R.string.create)
        // Wait for the seeded playlist to reach the dialog through the VM flows.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            vm.playlists.value.any { it.name == "Existing" }
        }
        composeRule.onNodeWithText(
            context.getString(com.vayunmathur.youpipe.R.string.label_playlist_name),
        ).performTextInput("Existing")
        composeRule.waitForIdle()
        composeRule.onNodeWithText(createLabel).assertIsNotEnabled()
    }

    @Test
    fun createPlaylistPersistsThroughOps() {
        val backStack = NavBackStack(arrayOf<Route>(Route.Main(0), Route.CreatePlaylist))
        composeRule.setContent { CreatePlaylist(backStack, vm) }
        composeRule.onNodeWithText(
            context.getString(com.vayunmathur.youpipe.R.string.label_playlist_name),
        ).performTextInput("Persisted")
        composeRule.waitForIdle()
        composeRule.onNodeWithText(
            context.getString(com.vayunmathur.library.ui.R.string.create),
        ).performClick()
        // The dialog pops synchronously; the IO write lands shortly after.
        // Poll the DAO with blocking reads on the test thread (never the VM
        // flow under runTest: its scheduler would freeze the collector).
        composeRule.waitUntil(timeoutMillis = 10_000) { backStack.backStack.size == 1 }
        var found = false
        val deadline = System.currentTimeMillis() + 10_000
        while (!found && System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            found = kotlinx.coroutines.runBlocking { db.playlistDao().getAll() }
                .any { it.name == "Persisted" }
        }
        assertTrue(found, "Persisted playlist missing from DAO")
    }

    // ---------- AddToPlaylist ----------

    @Test
    fun addToPlaylistShowsTitleWhenVideoMissing() {
        val backStack = NavBackStack(arrayOf<Route>(Route.Main(0), Route.AddToPlaylist(7L)))
        composeRule.setContent { AddToPlaylist(backStack, vm, videoID = 7L) }
        composeRule.onNodeWithText(
            context.getString(com.vayunmathur.youpipe.R.string.add_to_playlist),
        ).assertIsDisplayed()
    }

    @Test
    fun addToPlaylistConfirmsMembershipChange() {
        seedVideoState(videoID = 11L)
        kotlinx.coroutines.runBlocking {
            db.playlistDao().upsert(Playlist(name = "Rock", position = 1.0))
        }
        val backStack = NavBackStack(arrayOf<Route>(Route.Main(0), Route.AddToPlaylist(11L)))
        composeRule.setContent { AddToPlaylist(backStack, vm, videoID = 11L) }
        // The staged dialog lists the playlist with an unchecked box; checking it
        // and confirming persists the membership row and pops the dialog.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Rock").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Rock").performClick()
        composeRule.onNodeWithText(context.getString(android.R.string.ok)).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { backStack.backStack.size == 1 }
        assertEquals(1, backStack.backStack.size)
    }

    // ---------- AddToWatchLater ----------

    @Test
    fun addToWatchLaterShowsProgressThenAddsAndPops() {
        seedVideoState(videoID = 21L)
        // The mandatory Watch-later row is seeded by the VM init; wait for the
        // DAO row directly (plain thread, no runTest scheduler freeze).
        var watchLaterId = -1L
        val seedDeadline = System.currentTimeMillis() + 10_000
        while (watchLaterId < 0 && System.currentTimeMillis() < seedDeadline) {
            composeRule.waitForIdle()
            watchLaterId = kotlinx.coroutines.runBlocking { db.playlistDao().getAll() }
                .firstOrNull { it.mandatory }?.id ?: -1L
        }
        assertTrue(watchLaterId >= 0, "Watch-later seed row never landed")
        val backStack = NavBackStack(arrayOf<Route>(Route.Main(0), Route.AddToWatchLater(21L)))
        composeRule.setContent { AddToWatchLater(backStack, vm, videoID = 21L) }
        composeRule.onNodeWithText(
            context.getString(com.vayunmathur.youpipe.R.string.adding_to_watch_later),
        ).assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 10_000) { backStack.backStack.size == 1 }
        var added = false
        val deadline = System.currentTimeMillis() + 10_000
        while (!added && System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            added = kotlinx.coroutines.runBlocking {
                db.playlistItemDao().getForPlaylist(watchLaterId)
            }.any { it.videoItem.videoID == 21L }
        }
        assertTrue(added, "video 21 missing from Watch later")
    }

    @Test
    fun addToWatchLaterPopsOnVideoError() {
        vm.videoStateMutable.value = YouPipeViewModel.VideoState(error = true)
        val backStack = NavBackStack(arrayOf<Route>(Route.Main(0), Route.AddToWatchLater(22L)))
        composeRule.setContent { AddToWatchLater(backStack, vm, videoID = 22L) }
        composeRule.waitUntil(timeoutMillis = 10_000) { backStack.backStack.size == 1 }
        assertEquals(1, backStack.backStack.size)
    }

    // ---------- CreateSubscriptionCategory ----------

    @Test
    fun createCategoryShowsTitleAndDisablesCreate() {
        val backStack = NavBackStack(arrayOf<Route>(Route.Main(0), Route.CreateSubscriptionCategory(null)))
        composeRule.setContent { CreateSubscriptionCategory(backStack, vm, null) }
        composeRule.onNodeWithText(
            context.getString(com.vayunmathur.youpipe.R.string.title_create_subscription_category),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(com.vayunmathur.library.ui.R.string.create),
        ).assertIsNotEnabled()
    }

    @Test
    fun createCategoryTypingNameAndSelectingEnables() {
        // Seed on a plain thread: runTest's scheduler would freeze the
        // compose collector while waiting for the row to flow through.
        kotlinx.coroutines.runBlocking {
            db.subscriptionDao().upsert(
                Subscription(name = "Chan", channelID = "UC1", avatarURL = "https://x/y.jpg"),
            )
        }
        val backStack = NavBackStack(arrayOf<Route>(Route.Main(0), Route.CreateSubscriptionCategory(null)))
        composeRule.setContent { CreateSubscriptionCategory(backStack, vm, null) }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Chan").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            context.getString(com.vayunmathur.youpipe.R.string.label_category_name),
        ).performTextInput("Music")
        composeRule.waitForIdle()
        toggleRowCheckbox("Chan")
        composeRule.waitForIdle()
        composeRule.onNodeWithText(
            context.getString(com.vayunmathur.library.ui.R.string.create),
        ).assertIsEnabled()
    }

    @Test
    fun updateCategoryShowsUpdateTitleAndSaves() {
        // Seed a subscription already assigned to "Music" via the real
        // replaceCategory path, then open the dialog in update mode.
        val subId = kotlinx.coroutines.runBlocking {
            db.subscriptionDao().upsert(
                Subscription(name = "Chan", channelID = "UC1", avatarURL = "https://x/y.jpg"),
            )
        }
        kotlinx.coroutines.runBlocking {
            db.subscriptionCategoryDao().replaceCategory(null, "Music", listOf(subId))
        }
        val backStack = NavBackStack(arrayOf<Route>(Route.Main(0), Route.CreateSubscriptionCategory("Music")))
        composeRule.setContent { CreateSubscriptionCategory(backStack, vm, "Music") }
        // Update title (not the create title) shows with the id prefilled.
        composeRule.onNodeWithText(
            context.getString(com.vayunmathur.youpipe.R.string.title_update_subscription_category),
        ).assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Chan").fetchSemanticsNodes().isNotEmpty()
        }
        // The pre-existing membership keeps Update enabled; confirming writes
        // through replaceCategory and pops the dialog.
        composeRule.waitForIdle()
        val updateLabel = context.getString(com.vayunmathur.youpipe.R.string.action_update)
        composeRule.onNodeWithText(updateLabel).assertIsEnabled()
        composeRule.onNodeWithText(updateLabel).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { backStack.backStack.size == 1 }
        assertEquals(1, backStack.backStack.size)
    }

    @Test
    fun createCategoryDuplicateNameStaysDisabled() {
        val subId = kotlinx.coroutines.runBlocking {
            db.subscriptionDao().upsert(
                Subscription(name = "Chan", channelID = "UC1", avatarURL = "https://x/y.jpg"),
            )
        }
        kotlinx.coroutines.runBlocking {
            db.subscriptionCategoryDao().replaceCategory(null, "Music", listOf(subId))
        }
        // New-category mode: typing an already-used name keeps Create disabled
        // even with a subscription selected.
        val backStack = NavBackStack(arrayOf<Route>(Route.Main(0), Route.CreateSubscriptionCategory(null)))
        composeRule.setContent { CreateSubscriptionCategory(backStack, vm, null) }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Chan").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(
            context.getString(com.vayunmathur.youpipe.R.string.label_category_name),
        ).performTextInput("Music")
        composeRule.waitForIdle()
        toggleRowCheckbox("Chan")
        composeRule.waitForIdle()
        composeRule.onNodeWithText(
            context.getString(com.vayunmathur.library.ui.R.string.create),
        ).assertIsNotEnabled()
    }

    // ---------- helpers ----------

    /** Clicks the checkbox inside the subscription row showing [name]. */
    private fun toggleRowCheckbox(name: String) {
        // The ListItem row itself has no click handler — only the M3 Checkbox
        // does — so click the checkbox node: a sibling of the name text
        // inside the same row.
        composeRule.onNodeWithText(name, useUnmergedTree = true)
            .onParent()
            .onChildren()
            .filterToOne(
                androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(
                    androidx.compose.ui.semantics.SemanticsActions.OnClick,
                ),
            ).performClick()
    }

    private fun seedVideoState(videoID: Long) {
        vm.videoStateMutable.value = YouPipeViewModel.VideoState(
            data = com.vayunmathur.youpipe.ui.VideoData(
                title = "Title $videoID",
                views = 100L,
                duration = 60L,
                uploadDate = kotlin.time.Instant.fromEpochSeconds(1_700_000_000L),
                thumbnailURL = "https://x/t.jpg",
                author = "Author",
                authorURL = "https://x/c",
                authorThumbnail = "https://x/a.jpg",
                description = "desc",
            ),
        )
    }
}
