package com.vayunmathur.findfamily.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.util.FamilyListActions
import com.vayunmathur.findfamily.util.FamilyListUiState
import com.vayunmathur.findfamily.util.MainPageActions
import com.vayunmathur.findfamily.util.MainPageUiState
import com.vayunmathur.findfamily.util.PersonActions
import com.vayunmathur.findfamily.util.PersonUiState
import com.vayunmathur.findfamily.util.UwbSessionManager
import com.vayunmathur.findfamily.uwb.RangingSample
import com.vayunmathur.library.ui.DynamicTheme
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Expanded desktop window for the wide side-panel layout. */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=420"

/**
 * Store listing images for `:findfamily`. See `common-conventions-preview-metadata`.
 *
 * The app's headline screen is a map with everyone's pins on it, and that is exactly the
 * part Layoutlib cannot draw: the map is a tile renderer fed from the network, and the
 * marker layer only appears once the camera projection resolves — neither happens in a
 * preview. So instead of drawing isolated bottom sheets, these render the *real* page
 * layout ([MainPageContent]) with a static, muted map backdrop injected into its `map`
 * slot. The app and these previews therefore share one layout with zero duplication: the
 * family list, the per-person sharing controls and the full-screen UWB Find Nearby view.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 */
class MetadataPreviews {

    // Sample timestamps are relative to render time so "5 minutes ago" / "in 45 minutes"
    // read naturally. UserCard formats against Clock.System.now() internally anyway, so
    // these images were never going to be byte-identical between runs.
    private val now = Clock.System.now()

    private fun person(
        id: Long,
        name: String,
        place: String,
        arrivedMinutesAgo: Long,
        sendingEnabled: Boolean = true,
    ) = User(
        name = name,
        photo = null,
        locationName = place,
        sendingEnabled = sendingEnabled,
        requestStatus = RequestStatus.MUTUAL_CONNECTION,
        lastLocationChangeTime = now - arrivedMinutesAgo.minutes,
        id = id,
    )

    private fun report(
        userId: Long,
        lat: Double,
        lon: Double,
        speed: Float,
        battery: Float,
        minutesAgo: Long,
    ) = LocationValue(
        userid = userId,
        coord = Coord(lat, lon),
        speed = speed,
        acc = 6f,
        timestamp = now - minutesAgo.minutes,
        battery = battery,
        id = userId,
    )

    private val maya = person(1, "Maya Chen", "Home", arrivedMinutesAgo = 95)
    private val ravi = person(2, "Ravi Patel", "Lincoln High School", arrivedMinutesAgo = 210)
    private val dad = person(3, "Dad", "Market Street", arrivedMinutesAgo = 4)

    private val mayaLocation = report(1, 37.7749, -122.4194, speed = 0f, battery = 78f, minutesAgo = 3)

    private val sampleWaypoints = listOf(
        Waypoint("Home", 150.0, Coord(37.7749, -122.4194), id = 1),
        Waypoint("Lincoln High School", 250.0, Coord(37.7899, -122.4094), id = 2),
        Waypoint("Soccer practice", 120.0, Coord(37.7649, -122.4294), id = 3),
    )

    private fun sampleFamilyList() = FamilyListUiState(
        connectedUsers = listOf(maya, ravi, dad),
        awaitingRequestUsers = listOf(
            // Inbound requests are only known by their id until accepted.
            person(5_432_109L, "Grandma", "Unnamed Location", arrivedMinutesAgo = 0)
                .copy(requestStatus = RequestStatus.AWAITING_REQUEST)
        ),
        temporaryLinks = listOf(
            TemporaryLink(
                name = "Dog walker",
                deleteAt = now + 45.minutes,
                pqcPublicKey = "",
                pqcSeed = "",
                id = 91,
            ),
        ),
        waypoints = sampleWaypoints,
        locationByUser = mapOf(
            1L to mayaLocation,
            2L to report(2, 37.7899, -122.4094, speed = 0f, battery = 41f, minutesAgo = 12),
            3L to report(3, 37.7681, -122.4370, speed = 13.4f, battery = 16f, minutesAgo = 1),
        ),
        userNamesByLocationName = mapOf(
            "Home" to listOf("Maya Chen"),
            "Lincoln High School" to listOf("Ravi Patel"),
        ),
    )

    /**
     * Stands in for the live [MapView] in the `map` slot. Layoutlib cannot reach the network,
     * and the real [MapView] now draws through Vulkan besides, so instead of a synthetic grid
     * we bundle a real basemap mosaic (`preview_map_sf.png`, a screenshotTest-only classpath
     * resource centered on [mayaLocation]) and overlay the sample person marker exactly like
     * the app does. The mosaic is a JVM resource rather than an Android drawable so it never
     * ships in the app APK. Image + static overlays only — no network or effects — so it
     * renders under Layoutlib.
     *
     * **The mosaic is stale.** It is a CARTO Voyager capture, and the app no longer renders
     * CARTO tiles — it renders the self-hosted Protomaps archive. The attribution below still
     * credits CARTO because that is what this *image* is, and crediting Protomaps for a CARTO
     * capture would be a worse error than the staleness. Regenerating it means capturing the
     * Vulkan renderer on a device, which has to wait until that is verified on hardware; the
     * attribution moves to "© OpenStreetMap contributors · Protomaps" in the same change.
     */
    @Composable
    private fun StaticMapBackdrop() {
        val mapImage = remember {
            val stream = MetadataPreviews::class.java.getResourceAsStream("/preview_map_sf.png")
                ?: error("preview_map_sf.png missing from screenshotTest resources")
            stream.use { BitmapFactory.decodeStream(it).asImageBitmap() }
        }
        Box(Modifier.fillMaxSize()) {
            Image(
                bitmap = mapImage,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            // Sample person marker over the map point the mosaic is centered on.
            Box(Modifier.align(Alignment.Center)) {
                UserPicture(maya, 64.dp)
            }
            Text(
                text = "© CARTO © OpenStreetMap",
                color = Color.Black.copy(alpha = 0.7f),
                fontSize = 9.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    // Clear the 200.dp collapsed sheet peek so the required attribution stays visible.
                    .padding(start = 8.dp, bottom = 208.dp)
                    .background(Color.White.copy(alpha = 0.6f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }

    @PreviewTest
    @Preview(name = "1-family", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Family() {
        DynamicTheme(darkTheme = true) {
            MainPageContent(
                state = MainPageUiState(familyList = sampleFamilyList()),
                familyActions = FamilyListActions.Noop,
                actions = MainPageActions.Noop,
                map = { StaticMapBackdrop() },
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-person", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Person() {
        DynamicTheme(darkTheme = true) {
            // Person UI is now the Route.PersonSheet destination; preview
            // its sheet content directly over the static map backdrop.
            Box(Modifier.fillMaxSize()) {
                StaticMapBackdrop()
                PersonDetailSheet(
                    PersonUiState(user = maya, location = mayaLocation, waypoints = sampleWaypoints),
                    PersonActions.Noop,
                )
            }
        }
    }

    @PreviewTest
    @Preview(name = "3-find-nearby", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3FindNearby() {
        DynamicTheme(darkTheme = true) {
            UwbRangingContent(
                peerName = maya.name,
                session = UwbSessionManager.UwbSessionState.Ranging(
                    RangingSample(
                        distanceMeters = 4.2f,
                        azimuthDeg = -22.5f,
                        elevationDeg = 3.0f,
                        timestampNanos = 0L,
                    )
                ),
                onBack = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview4Expanded() {
        DynamicTheme(darkTheme = true) {
            FindFamilyWideLayout(
                map = { StaticMapBackdrop() },
                panel = {
                    FamilyListSheet(sampleFamilyList(), FamilyListActions.Noop)
                },
            )
        }
    }
}
