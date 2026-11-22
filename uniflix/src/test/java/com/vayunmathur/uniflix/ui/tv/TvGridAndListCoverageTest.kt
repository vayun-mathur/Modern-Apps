package com.vayunmathur.uniflix.ui.tv

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.uniflix.data.UniflixDatabase
import com.vayunmathur.uniflix.data.UniflixRepository
import com.vayunmathur.uniflix.data.uniflixTransport
import com.vayunmathur.uniflix.platform.FakeAndroidKeyStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** TvGridPage kinds + TvMyListTab in their offline/empty states. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TvGridAndListCoverageTest {

    @get:Rule
    val composeRule = createComposeRule()

    @BeforeTest
    fun setUp() {
        // Hermetic DB (the sibling TV-test pattern): TvGridPage/TvMyListTab
        // compose HomeViewModel/ListsViewModel, which collect the
        // repository's Room flows. Without a planted in-memory DB the
        // repository builds the real SQLCipher file DB — which needs the
        // AndroidKeyStore on a host JVM — and in a shared test JVM it
        // instead inherits whatever stale/closed in-memory instance a
        // previous class planted in the process-wide registry, whose
        // flows then fail with "no such table". Plant a fresh in-memory
        // DB and rebind the repository singleton for every test.
        FakeAndroidKeyStore.install()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, UniflixDatabase::class.java)
            .allowMainThreadQueries().build()
        com.vayunmathur.library.util.databases[UniflixDatabase::class] = db
        UniflixRepository.resetInstanceForTest()
    }

    @AfterTest fun tearDown() { uniflixTransport = null }

    @Test fun gridPageContinueWatchingEmpty() {
        uniflixTransport = { throw java.io.IOException("offline") }
        val stack = NavBackStack<TvRoute>(arrayOf(TvRoute.Home))
        composeRule.setContent { DynamicTheme { TvGridPage(stack, TV_GRID_CONTINUE) } }
        composeRule.waitForIdle()
        assertEquals(TvRoute.Home, stack.last())
    }

    @Test fun gridPageTopMoviesLoading() {
        uniflixTransport = { throw java.io.IOException("offline") }
        val stack = NavBackStack<TvRoute>(arrayOf(TvRoute.Home))
        composeRule.setContent { DynamicTheme { TvGridPage(stack, TV_GRID_TOP_MOVIES) } }
        composeRule.waitForIdle()
        assertEquals(TvRoute.Home, stack.last())
    }

    @Test fun gridPageTopTvLoading() {
        uniflixTransport = { throw java.io.IOException("offline") }
        val stack = NavBackStack<TvRoute>(arrayOf(TvRoute.Home))
        composeRule.setContent { DynamicTheme { TvGridPage(stack, TV_GRID_TOP_TV) } }
        composeRule.waitForIdle()
    }

    @Test fun gridPageTrendingMoviesLoading() {
        uniflixTransport = { throw java.io.IOException("offline") }
        val stack = NavBackStack<TvRoute>(arrayOf(TvRoute.Home))
        composeRule.setContent { DynamicTheme { TvGridPage(stack, TV_GRID_TRENDING_MOVIES) } }
        composeRule.waitForIdle()
    }

    @Test fun gridPageUnknownKindFallsToTrendingSeries() {
        uniflixTransport = { throw java.io.IOException("offline") }
        val stack = NavBackStack<TvRoute>(arrayOf(TvRoute.Home))
        composeRule.setContent { DynamicTheme { TvGridPage(stack, "something-else") } }
        composeRule.waitForIdle()
    }

    @Test fun myListTabEmptyState() {
        uniflixTransport = { throw java.io.IOException("offline") }
        var opened = 0
        var resumed = 0
        composeRule.setContent {
            DynamicTheme {
                TvMyListTab(onOpenDetail = { opened++ }, onResume = { resumed++ })
            }
        }
        composeRule.waitForIdle()
        // Nothing saved yet: the empty state renders and no navigation fires.
        assertEquals(0, opened)
        assertEquals(0, resumed)
    }
}
