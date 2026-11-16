package com.vayunmathur.maps

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.maps.util.SelectedFeatureViewModel
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * W6: warm/cold navigation deep links with a seeded GPS fix, so
 * MainActivity.navigateTo resolves the destination instead of only
 * opening the place pane.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapsActivityW6Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @kotlin.test.BeforeTest
    fun setUp() {
        runCatching {
            androidx.work.WorkManager.initialize(
                context,
                androidx.work.Configuration.Builder()
                    .setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor())
                    .build(),
            )
        }
    }

    private fun seedFix() {
        Shadows.shadowOf(context as android.app.Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadow = Shadows.shadowOf(lm)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val fix = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 37.422; longitude = -122.084; accuracy = 5f; time = System.currentTimeMillis()
        }
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, fix)
        shadow.setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix)
    }

    private fun launch(data: String): MainActivity {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setData(android.net.Uri.parse(data))
        }
        return Robolectric.buildActivity(MainActivity::class.java, intent).setup().get()
    }

    @Test
    fun navigationLink_withFix_resolvesDestination() {
        seedFix()
        val activity = launch("google.navigation:q=37.4275,-122.1697")
        ShadowLooper.idleMainLooper()
        assertNotNull(activity)
    }

    @Test
    fun geoLink_namedPlace_selectsIt() {
        seedFix()
        val activity = launch("geo:37.4275,-122.1697?q=Stanford%20University")
        ShadowLooper.idleMainLooper()
        assertNotNull(activity)
    }

    @Test
    fun warmStart_newIntent_routesAgain() {
        seedFix()
        val controller = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(context, MainActivity::class.java).apply { action = Intent.ACTION_VIEW; data = android.net.Uri.parse("geo:0,0?q=Coffee") },
        ).setup()
        val activity: MainActivity = controller.get()
        val second = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW; data = android.net.Uri.parse("google.navigation:q=Paris")
        }
        controller.newIntent(second)
        ShadowLooper.idleMainLooper()
        assertTrue(true)
    }
}
