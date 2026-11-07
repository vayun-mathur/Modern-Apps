package com.vayunmathur.vpn.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.vpn.Route
import com.vayunmathur.vpn.platform.BypassList
import com.vayunmathur.vpn.service.AppResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowApplicationPackageManager

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityAndBypassSwitchTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun bypassSwitchTogglesBypassList() { runBlocking {
        // Give the shadow PM two INTERNET-holding apps for loadApps to offer
        val pm = Shadows.shadowOf(app.packageManager) as ShadowApplicationPackageManager
        for ((i, pkg) in listOf("com.example.bypassone", "com.example.bypasstwo").withIndex()) {
            val pi = PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = "Bypass $i" }
                requestedPermissions = arrayOf(android.Manifest.permission.INTERNET)
                requestedPermissionsFlags = intArrayOf(PackageInfo.REQUESTED_PERMISSION_GRANTED)
            }
            pm.installPackage(pi)
        }
        rule.setContent {
            DynamicTheme {
                val bs = rememberNavBackStack<Route>(Route.Main())
                BypassListPage(bs)
            }
        }
        // Wait for apps to load (toggle appears) - Robolectric PM has the test app itself etc.
        rule.waitUntil(timeoutMillis = 10000) {
            rule.onAllNodes(isToggleable()).fetchSemanticsNodes().isNotEmpty()
        }
        val before = BypassList.load(app)
        rule.onAllNodes(isToggleable())[0].performClick()
        // The switch writes through DataStore asynchronously
        withTimeout(8000) { while (BypassList.load(app) == before) delay(50) }
        val after = BypassList.load(app)
        assertTrue(after != before, "toggling a switch must change the bypass set (before=$before after=$after)")
        // Clean up whatever was toggled on
        (after - before).forEach { BypassList.setBypassed(app, it, false) }
        (before - after).forEach { BypassList.setBypassed(app, it, true) }
        Unit
    } }

    @Test fun describeWithMultiplePackagesSharingUid() {
        val pm = Shadows.shadowOf(app.packageManager) as ShadowApplicationPackageManager
        for ((i, pkg) in listOf("com.example.one", "com.example.two").withIndex()) {
            val pi = PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply { packageName = pkg; uid = 23456; nonLocalizedLabel = "App $i" }
            }
            pm.installPackage(pi)
        }
        val resolver = AppResolver(app)
        val resolved = resolver.resolveUid(23456)
        assertEquals(23456, resolved.uid)
        assertNotNull(resolved.appLabel)
        assertTrue(resolved.appLabel.isNotBlank())
    }
}

