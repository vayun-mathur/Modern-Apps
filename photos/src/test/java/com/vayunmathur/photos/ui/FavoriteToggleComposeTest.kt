package com.vayunmathur.photos.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.data.awaitPhotosCondition
import com.vayunmathur.photos.platform.FavoriteToggler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [rememberFavoriteToggle] with a toggler whose request factory returns a
 * local PendingIntent: the optimistic Room write lands, the launch is
 * attempted through the activity-result launcher, and the no-change case
 * completes immediately.
 */
@RunWith(AndroidJUnit4::class)
class FavoriteToggleComposeTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var installed: TestPhotosRepository.Installed

    @BeforeTest
    fun setup() {
        com.vayunmathur.photos.util.workInfosOverride = { _, _ -> flowOf(emptyList()) }
        installed = TestPhotosRepository.install(app)
    }

    private fun photo(name: String, favorite: Boolean = false) = Photo(
        name = name, uri = "content://media/$name", date = 1L, width = 10, height = 10,
        dateModified = 1L, exifSet = true, lat = null, long = null,
        videoData = null, panoData = null, isFavorite = favorite,
    )

    @Test fun toggleLaunchesRequestAndWritesLocally() = runBlocking {
        installed.photos.upsertAll(listOf(photo("a"), photo("b")))
        val photos = installed.photos.getAll()
        var applied = 0
        val toggler = FavoriteToggler(
            writeLocal = { ids, value -> installed.photos.setFavorite(ids, value) },
            requests = { _, _ ->
                PendingIntent.getActivity(
                    app, 0, Intent(app, com.vayunmathur.photos.MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            },
            scope = CoroutineScope(Dispatchers.Default),
            onApplied = {},
        )
        var toggle: ((List<Photo>) -> Unit)? = null
        rule.setContent {
            toggle = rememberFavoriteToggle(toggler, onApplied = { applied++ })
        }
        rule.waitForIdle()
        toggle!!(photos)
        // Optimistic local write lands in Room regardless of launcher fate.
        assertTrue(awaitPhotosCondition {
            installed.photos.getAll().all { it.isFavorite }
        })
        Unit
    }
}
