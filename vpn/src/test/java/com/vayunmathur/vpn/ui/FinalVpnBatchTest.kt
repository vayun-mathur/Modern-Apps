package com.vayunmathur.vpn.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.vpn.Route
import com.vayunmathur.vpn.data.AppUsageSummary
import com.vayunmathur.vpn.platform.VpnViewModel
import com.vayunmathur.vpn.platform.VpnViewModelTest
import com.vayunmathur.vpn.ui.components.AppIconImage
import com.vayunmathur.vpn.ui.components.LockdownWarning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowApplicationPackageManager

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FinalVpnBatchTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun routeSerializationRoundTrip() {
        val json = Json
        val main = json.decodeFromString(Route.serializer(), json.encodeToString(Route.serializer(), Route.Main(2)))
        assertEquals(Route.Main(2), main)
        val detail = json.decodeFromString(Route.serializer(), json.encodeToString(Route.serializer(), Route.Detail(9)))
        assertEquals(Route.Detail(9), detail)
        val bypass = json.decodeFromString(Route.serializer(), json.encodeToString(Route.serializer(), Route.BypassList))
        assertEquals(Route.BypassList, bypass)
    }

    @Test fun loggingDeleteDialogFlow() {
        var deleted = 0
        rule.setContent {
            DynamicTheme {
                LoggingContent(
                    topApps = listOf(AppUsageSummary("com.a", "A", 10, 1)),
                    domainsByCount = emptyList(), domainsByBytes = emptyList(),
                    onDeleteAllLogs = { deleted++ },
                )
            }
        }
        // Trash icon in the scaffold actions (only click-action in the top bar besides tabs)
        rule.onNodeWithText("A").assertIsDisplayed()
        // Open dialog via the delete IconButton: it is the first click node in the top bar area
        val nodes = rule.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes()
        val topBar = nodes.withIndex().filter { it.value.boundsInRoot.top < 80f }
        // Click each top-bar node until the dialog title shows (tabs are lower, actions are here)
        for ((i, _) in topBar) {
            runCatching { rule.onAllNodes(androidx.compose.ui.test.hasClickAction())[i].performClick() }
            rule.waitForIdle()
            if (rule.onAllNodes(androidx.compose.ui.test.hasText("Delete all logs?")).fetchSemanticsNodes().isNotEmpty()) break
        }
        rule.onNodeWithText("Delete all logs?").assertIsDisplayed()
        // Cancel first
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(0, deleted)
    }

    @Test fun appIconAndLockdownWarningDraw() {
        rule.setContent {
            DynamicTheme {
                androidx.compose.foundation.layout.Column {
                    AppIconImage(ColorDrawable(android.graphics.Color.RED))
                    AppIconImage(null)
                    LockdownWarning()
                }
            }
        }
        rule.onNodeWithText("Open system VPN settings").assertIsDisplayed()
        rule.onRoot().captureToImage()
    }

    @Test fun backfillAppNamesNamesResolvableUids() { runBlocking {
        // Install a package for uid 12345 in the shadow PM so AppResolver can name it
        val pm = Shadows.shadowOf(app.packageManager) as ShadowApplicationPackageManager
        val pkg = "com.example.backfill"
        val pi = PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg; uid = 12345; nonLocalizedLabel = "Backfill App" }
        }
        pm.installPackage(pi)
        val logDao = VpnViewModelTest.FakeLogDao().apply { unnamed = listOf(12345, 0) }
        VpnViewModel(app, VpnViewModelTest.FakeConfigDao(), logDao)
        // VM init launches backfill on IO; uid 0 (root) has no package so it is skipped,
        // uid 12345 is named if the shadow PM reports the package for the uid.
        withTimeout(8000) { while (logDao.named.isEmpty()) delay(50); }
        assertTrue(logDao.named.any { it.first == 12345 }, "named=${logDao.named}")
        Unit
    } }

    @Test fun viewModelStartVpnWithActivityAsksPermission() {
        val dao = VpnViewModelTest.FakeConfigDao()
        val vm = VpnViewModel(app, dao, VpnViewModelTest.FakeLogDao())
        vm.startVpn(rule.activity, com.vayunmathur.vpn.data.VpnConfig(id = 4, name = "PermTun"))
        // Robolectric's VpnService.prepare returns a consent intent, so the VM must
        // ask for permission instead of starting the service.
        assertTrue(
            vm.status.value?.contains("Granting") == true || Shadows.shadowOf(rule.activity).nextStartedActivityForResult != null,
            "status=${vm.status.value}",
        )
    }
}
