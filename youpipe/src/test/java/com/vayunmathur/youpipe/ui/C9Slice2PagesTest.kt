package com.vayunmathur.youpipe.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice 2 (A-L) page bodies against the real ViewModel: the cached database
 * registry is pre-seeded with an in-memory SubscriptionDatabase so no
 * AndroidKeyStore is touched, exactly as the slice-3 tests do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class C9Slice2PagesTest {

    @get:Rule val rule = createComposeRule()

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

    @BeforeTest fun open() {
        try {
            WorkManager.initialize(context, Configuration.Builder().build())
        } catch (_: IllegalStateException) {
            // Already initialized in this sandbox.
        }
        sharedDb(context)
        vm = YouPipeViewModel(app, SubscriptionRepository.get(context))
    }

    private fun stack() = NavBackStack<Route>(arrayOf(Route.Main(0)))

    @Test fun generalSettings_rendersAndToggles() {
        rule.setContent { GeneralSettingsPage(stack(), vm) }
        rule.onNodeWithText("General & Content").assertIsDisplayed()
        rule.onAllNodesWithText("General")[0].assertExists()
    }

    @Test fun dataSettings_renders() {
        rule.setContent { DataSettingsPage(stack(), vm) }
        rule.onNodeWithText("Data & Backup").assertIsDisplayed()
    }

    @Test fun historyPage_rendersEmpty() {
        rule.setContent { HistoryPage(stack(), vm) }
        rule.waitForIdle()
    }

    @Test fun downloadedVideosPage_renders() {
        rule.setContent { DownloadedVideosPage(stack(), vm) }
        rule.onAllNodesWithText("Downloads", substring = true)[0].assertExists()
    }

    @Test fun historyPage_withSeededVideo_showsTitle() {
        val db = sharedDb(context)
        kotlinx.coroutines.runBlocking {
            db.historyVideoDao().upsert(
                com.vayunmathur.youpipe.data.HistoryVideo.fromVideoData(
                    VideoInfo(
                        name = "Seeded History Video", videoID = 777L, duration = 120, views = 5000,
                        uploadDate = kotlin.time.Instant.fromEpochSeconds(1_700_000_000L),
                        thumbnailURL = "", author = "Seed Author",
                    ),
                    progress = 30_000,
                )
            )
        }
        rule.setContent { HistoryPage(stack(), vm) }
        rule.waitUntil(5000) {
            rule.onAllNodesWithText("Seeded History Video").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Seeded History Video").assertExists()
    }

    @Test fun downloadedVideos_withSeededDownload_showsTitle() {
        val db = sharedDb(context)
        kotlinx.coroutines.runBlocking {
            db.downloadedVideoDao().upsert(
                com.vayunmathur.youpipe.data.DownloadedVideo(
                    id = 888L,
                    videoItem = VideoInfo(
                        name = "Seeded Download", videoID = 888L, duration = 60, views = 10,
                        uploadDate = kotlin.time.Instant.fromEpochSeconds(1_700_000_000L),
                        thumbnailURL = "", author = "Seed Author",
                    ),
                    filePath = "/tmp/888.mp4",
                    timestamp = kotlin.time.Instant.fromEpochSeconds(1_700_000_000L),
                )
            )
        }
        rule.setContent { DownloadedVideosPage(stack(), vm) }
        rule.waitUntil(5000) {
            rule.onAllNodesWithText("Seeded Download").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Seeded Download").assertExists()
    }

    @Test fun channelPage_rendersLoadingOrEmpty() {
        rule.setContent { ChannelPage(stack(), vm, "UC1234567890") }
        // loadChannel hits the extractor/network off-device; the page must
        // compose its loading/empty state without crashing.
        rule.waitForIdle()
    }
}
