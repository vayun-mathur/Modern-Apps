package com.vayunmathur.youpipe

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.util.decodeVideoID
import com.vayunmathur.youpipe.util.encodeVideoID
import com.vayunmathur.youpipe.util.parseSharedVideoId
import com.vayunmathur.youpipe.util.videoIDtoURL
import com.vayunmathur.youpipe.util.videoURLtoID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice 3/3 second pass: [Route] serialization (the persisted back stack),
 * video-id codecs behind share/deep-link routing, [findActivity], PiP state,
 * and [MainActivity] launch routing for every intent shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RootSlice3Pass2Test {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val app get() = context.applicationContext as Application

    @BeforeTest
    fun seed() {
        try {
            WorkManager.initialize(context, Configuration.Builder().build())
        } catch (_: IllegalStateException) {
        }
        if (!com.vayunmathur.library.util.databases.containsKey(SubscriptionDatabase::class)) {
            val db = Room.inMemoryDatabaseBuilder(context, SubscriptionDatabase::class.java)
                .allowMainThreadQueries().build()
            com.vayunmathur.library.util.databases[SubscriptionDatabase::class] = db
        }
    }

    @Test
    fun routeSerializationRoundTripsEveryVariant() {
        val json = Json { ignoreUnknownKeys = true }
        val routes: List<Route> = listOf(
            Route.Main(2), Route.VideoPage(9L), Route.ChannelPage("UC1"),
            Route.SubscriptionVideosPage("music"), Route.SubscriptionVideosPage(null),
            Route.CreateSubscriptionCategory("c1"), Route.Downloads, Route.PlaylistDetail(3L),
            Route.CreatePlaylist, Route.AddToPlaylist(9L, false), Route.AddToWatchLater(11L),
            Route.SettingsGeneral, Route.SettingsSponsorBlock, Route.SettingsRecommendations,
            Route.SettingsData, Route.ManageInterests,
        )
        for (route in routes) {
            val text = json.encodeToString(Route.serializer(), route)
            assertEquals(route, json.decodeFromString(Route.serializer(), text), "round-trip: $text")
        }
        // Polymorphic discriminator is present so restores can tell variants apart.
        assertTrue(json.encodeToString(Route.serializer(), Route.Downloads).contains("Downloads"))
    }

    @Test
    fun videoIdCodecsRoundTripAndShareParsing() {
        // 11-char YouTube ids encode to the app's Long ids and back.
        val id = encodeVideoID("dQw4w9WgXcQ")
        assertEquals("dQw4w9WgXcQ", decodeVideoID(id))
        assertEquals(id, videoURLtoID("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", videoIDtoURL(id))
        assertEquals(id, parseSharedVideoId("check this https://youtu.be/dQw4w9WgXcQ lol"))
        assertEquals(id, parseSharedVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals(id, parseSharedVideoId("watch?v=dQw4w9WgXcQ"))
        assertNull(parseSharedVideoId("no video here"))
        assertNull(parseSharedVideoId(""))
    }

    @Test
    fun findActivityUnwrapsAndThrowsOutsideActivity() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        assertEquals(activity, activity.findActivity())
        assertEquals(activity, ContextWrapper(activity).findActivity())
        assertEquals(activity, ContextWrapper(ContextWrapper(activity)).findActivity())
        assertFailsWith<IllegalStateException> { app.findActivity() }
    }

    @Test
    fun pipModeDefaultsFalseOutsidePip() {
        var pip: Boolean? = null
        composeRule.setContent { pip = rememberIsInPipMode() }
        composeRule.waitForIdle()
        assertEquals(false, pip)
    }

    private fun launch(intent: Intent): MainActivity {
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
        val activity = controller.setup().get()
        assertTrue(!activity.isFinishing)
        controller.pause().stop().destroy()
        return activity
    }

    @Test
    fun launchDefaultIntentResolves() {
        launch(Intent(Intent.ACTION_MAIN))
    }

    @Test
    fun launchApplicationPreferencesResolves() {
        launch(Intent(Intent.ACTION_APPLICATION_PREFERENCES))
    }

    @Test
    fun launchSharedVideoResolves() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://youtu.be/dQw4w9WgXcQ")
        }
        launch(intent)
    }

    @Test
    fun launchSharedNonVideoFallsBackToDefault() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "just some text")
        }
        launch(intent)
    }

    @Test
    fun launchWatchDeepLinkResolves() {
        val id = encodeVideoID("dQw4w9WgXcQ")
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(videoIDtoURL(id)))
        launch(intent)
    }
}
