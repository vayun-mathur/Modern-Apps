package com.vayunmathur.uniflix.ui.tv

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.uniflix.data.UniflixDatabase
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.platform.FakeAndroidKeyStore
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** TV pages that need the database/ViewModels: navigation, detail, grids, watch, browse, search. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class C9TvPagesTest {

    @get:Rule val rule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @BeforeTest fun open() {
        FakeAndroidKeyStore.install()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Force ours in: another test class may have already let
        // buildDatabase cache the real SQLCipher instance in the registry.
        val db = Room.inMemoryDatabaseBuilder(context, UniflixDatabase::class.java)
            .allowMainThreadQueries().build()
        com.vayunmathur.library.util.databases[UniflixDatabase::class] = db
    }

    private fun stack() = NavBackStack<TvRoute>(arrayOf(TvRoute.Main()))

    @Test fun tvNavigation_renders() {
        rule.setContent { TvNavigation() }
        rule.waitForIdle()
    }

    @Test fun tvDetail_renders() {
        rule.setContent { TvDetailPage(stack(), "movie", 1) }
        rule.waitForIdle()
    }

    @Test fun tvGrid_continue() {
        rule.setContent { TvGridPage(stack(), TV_GRID_CONTINUE) }
        rule.waitForIdle()
    }

    @Test fun tvGrid_topMovies() {
        rule.setContent { TvGridPage(stack(), TV_GRID_TOP_MOVIES) }
        rule.waitForIdle()
    }

    @Test fun tvWatch_renders() {
        rule.setContent { TvWatchPage(stack(), "movie", 1, 0, 0) }
        rule.waitForIdle()
    }

    @Test fun tvBrowse_renders() {
        rule.setContent { TvBrowseTab(MediaType.MOVIE, onOpenDetail = {}) }
        rule.waitForIdle()
    }

    @Test fun tvSearch_renders() {
        rule.setContent { TvSearchTab(onOpenDetail = {}) }
        rule.waitForIdle()
    }
}
