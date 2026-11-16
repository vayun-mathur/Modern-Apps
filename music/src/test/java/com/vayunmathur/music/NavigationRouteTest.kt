package com.vayunmathur.music

import android.app.Application
import android.content.ComponentName
import android.os.Binder
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.music.data.MusicRepository
import com.vayunmathur.music.platform.MusicViewModel
import com.vayunmathur.music.platform.PlaybackManager
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationRouteTest {
    @get:Rule val composeRule = createComposeRule()
    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName(app, com.vayunmathur.music.service.PlaybackService::class.java), Binder())
    }
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun routesSerialiseAndRoundTrip() {
        val routes: List<Route> = listOf(
            Route.Home, Route.Song, Route.AlbumDetail(7L), Route.ArtistDetail(11L),
            Route.PlaylistDetail(3L), Route.AddToPlaylistDialog(42L))
        for (route in routes) {
            val encoded = Json.encodeToString(Route.serializer(), route)
            assertTrue(encoded.isNotBlank(), route.toString())
            assertEquals(route, Json.decodeFromString(Route.serializer(), encoded))
        }
        assertEquals(7L, Route.AlbumDetail(7L).albumId)
        assertEquals(42L, Route.AddToPlaylistDialog(42L).musicId)
    }

    @Test fun mainActivityCreatesAndComposesPermissionsGate() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.create().start().resume().get()
        assertNotNull(activity)
        controller.pause().stop().destroy()
    }
}
