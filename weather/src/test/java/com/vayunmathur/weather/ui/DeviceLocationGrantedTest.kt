package com.vayunmathur.weather.ui

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.weather.platform.FakeRepository
import com.vayunmathur.weather.platform.WeatherViewModel
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Shadows
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class DeviceLocationGrantedTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun clickWithPermissionRunsFetchAndClearsLoading() {
        Shadows.shadowOf(rule.activity.application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        // No enabled providers: currentLocation must return null at once
        // instead of parking on a one-shot listener that never fires.
        val lm = rule.activity.getSystemService(android.location.LocationManager::class.java)
        Shadows.shadowOf(lm).setProviderEnabled(android.location.LocationManager.GPS_PROVIDER, false)
        Shadows.shadowOf(lm).setProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER, false)
        runCatching {
            androidx.work.WorkManager.initialize(
                rule.activity.application, androidx.work.Configuration.Builder().build(),
            )
        }
        val vm = WeatherViewModel(rule.activity.application, FakeRepository(rule.activity.application))
        var pair: Pair<() -> Unit, Boolean>? = null
        rule.setContent { pair = rememberRequestDeviceLocation(vm) }
        pair!!.first()
        rule.waitForIdle()
        // No providers under Robolectric: the fetch completes with no fix and
        // the loading flag must clear again.
        var guard = 0
        while (pair!!.second && guard++ < 50) {
            Thread.sleep(20)
            rule.waitForIdle()
        }
        assertEquals(false, pair!!.second)
    }
}
