package com.vayunmathur.youpipe.ui

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.data.Playlist
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.data.SubscriptionCategory
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.data.SubscriptionVideo
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows

/**
 * Slice 2/3 pass 3 harness proof: the real [YouPipeViewModel] running on the
 * repository seam (in-memory database) - the thing every VM-bound page needs.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ViewModelHarnessPass3Test {

    private lateinit var db: SubscriptionDatabase
    private lateinit var vm: YouPipeViewModel

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        runCatching {
            WorkManager.initialize(app, Configuration.Builder().build())
        }
        db = Room.inMemoryDatabaseBuilder(app, SubscriptionDatabase::class.java)
            .allowMainThreadQueries().build()
        vm = YouPipeViewModel(app, SubscriptionRepository(app, db))
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    /** Collect a WhileSubscribed flow to a value, pumping the paused main looper. */
    private fun <T> awaitFlow(flow: kotlinx.coroutines.flow.Flow<T>, ready: (T) -> Boolean): T {
        var result: T? = null
        val job = CoroutineScope(Dispatchers.IO).launch {
            flow.collect { value -> if (ready(value)) result = value }
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (result == null && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        job.cancel()
        return result ?: throw AssertionError("flow never became ready")
    }

    private var historyRowId: Long = -1

    private fun seed() = runBlocking {
        val subId = db.subscriptionDao().upsert(
            Subscription(name = "Seeded Chan", channelID = "UCseed", avatarURL = ""),
        )
        db.subscriptionCategoryDao().upsertAll(listOf(SubscriptionCategory(subId, "News")))
        db.subscriptionVideoDao().upsertAll(
            listOf(
                SubscriptionVideo(
                    name = "Seeded Video", duration = 60, views = 3,
                    uploadDate = Instant.fromEpochSeconds(1_700_000_000),
                    thumbnailURL = "", author = "Seeded Chan", channelID = subId,
                ),
            ),
        )
        historyRowId = db.historyVideoDao().upsert(
            HistoryVideo(
                progress = 1_000,
                videoItem = VideoInfo("Watched One", 55, 100, 9, Instant.fromEpochSeconds(1_600_000_000), "", "Seeded Chan"),
                timestamp = Instant.fromEpochSeconds(1_700_100_000),
            ),
        )
        // No explicit Watch later row: the ViewModel seeds the mandatory one.
        subId
    }

    @Test
    fun viewModel_exposesSeededRepositoryState() {
        val subId = seed()
        val subs = awaitFlow(vm.subscriptions) { it.isNotEmpty() }
        assertEquals(listOf("Seeded Chan"), subs.map { it.name })
        assertEquals(listOf("News"), awaitFlow(vm.categoryNames) { it.isNotEmpty() })
        val history = awaitFlow(vm.historyVideosByRecency) { it.isNotEmpty() }
        assertEquals("Watched One", history.single().videoItem.name)
        val playlists = awaitFlow(vm.playlists) { it.isNotEmpty() }
        assertEquals(listOf("Watch later"), playlists.map { it.name })
        assertTrue(playlists.single().mandatory)
        assertEquals("Watched One", awaitFlow(vm.historyById(historyRowId)) { it != null }?.videoItem?.name)
    }

    @Test
    fun viewModel_subscriptionVideosForCategoryFilters() {
        seed()
        val all = awaitFlow(vm.subscriptionVideosFor(null)) { it.isNotEmpty() }
        assertEquals(listOf("Seeded Video"), all.map { it.name })
        val news = awaitFlow(vm.subscriptionVideosFor("News")) { it.isNotEmpty() }
        assertEquals(1, news.size)
        val none = runBlocking {
            withTimeout(5_000) { vm.subscriptionVideosFor("Sports").first() }
        }
        assertEquals(emptyList(), none)
        val inCategory = awaitFlow(vm.subscriptionsInCategory("News")) { it.isNotEmpty() }
        assertEquals("Seeded Chan", inCategory.single().name)
    }

    @Test
    fun viewModel_mutationsReachTheDatabase() {
        seed()
        vm.upsertSubscription(Subscription(name = "Added", channelID = "UCadd", avatarURL = ""))
        val subs = awaitFlow(vm.subscriptions) { it.size == 2 }
        assertEquals(setOf("Seeded Chan", "Added"), subs.map { it.name }.toSet())
        val added = subs.first { it.name == "Added" }
        vm.deleteSubscription(added)
        assertEquals(1, awaitFlow(vm.subscriptions) { it.size == 1 }.size)
        vm.clearHistory()
        assertEquals(emptyList(), awaitFlow(vm.historyVideosByRecency) { it.isEmpty() && vm.historyVideos.value.isEmpty() })
    }
}
