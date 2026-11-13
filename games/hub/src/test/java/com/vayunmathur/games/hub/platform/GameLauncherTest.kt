package com.vayunmathur.games.hub.platform

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.hub.ui.screens.rememberInstalledGameIds
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Play on a missing game must lead somewhere: the game, its store listing, or a message. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameLauncherTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: Application
    private val shadowPm get() = shadowOf(app.packageManager)

    @BeforeTest
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // Drain anything the compose host's own launch recorded.
        while (shadowOf(app).nextStartedActivity != null) Unit
    }

    private fun install(pkg: String) {
        shadowPm.installPackage(
            PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply { packageName = pkg }
            },
        )
    }

    private fun installGame(pkg: String) {
        install(pkg)
        val main = ComponentName(pkg, "$pkg.MainActivity")
        shadowPm.addActivityIfNotPresent(main)
        shadowPm.addIntentFilterForActivity(
            main,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    private fun installStore(pkg: String) {
        install(pkg)
        val store = ComponentName(pkg, "$pkg.StoreActivity")
        shadowPm.addActivityIfNotPresent(store)
        shadowPm.addIntentFilterForActivity(
            store,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("market")
            },
        )
    }

    @Test
    fun installedGameIsLaunched() {
        installGame(CHESS)
        assertEquals(LaunchOutcome.LAUNCHED, GameLauncher.launchOrInstall(app, CHESS))
        assertEquals(CHESS, shadowOf(app).nextStartedActivity?.component?.packageName)
    }

    @Test
    fun missingGameOpensItsModernAppsStoreListing() {
        installStore(GameLauncher.MODERN_APPS_STORE)
        installStore("com.other.store")
        assertEquals(LaunchOutcome.OPENED_STORE, GameLauncher.launchOrInstall(app, CHESS))
        val started = shadowOf(app).nextStartedActivity
        assertEquals("market://details?id=$CHESS", started?.dataString)
        assertEquals(GameLauncher.MODERN_APPS_STORE, started?.`package`)
    }

    @Test
    fun missingGameFallsBackToAnyStore() {
        installStore("com.other.store")
        assertEquals(LaunchOutcome.OPENED_STORE, GameLauncher.launchOrInstall(app, CHESS))
        val started = shadowOf(app).nextStartedActivity
        assertEquals("market://details?id=$CHESS", started?.dataString)
        assertNull(started?.`package`)
    }

    @Test
    fun missingGameWithNoStoreIsReportedNotSwallowed() {
        assertEquals(LaunchOutcome.UNAVAILABLE, GameLauncher.launchOrInstall(app, CHESS))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun installedGameIdsChecksEachPackage() {
        install(CHESS)
        assertEquals(setOf("chess"), GameLauncher.installedGameIds(app, mapOf("chess" to CHESS, "sudoku" to SUDOKU)))
    }

    @Test
    fun installedSetRefreshesOnResumeAfterAnUninstall() {
        install(CHESS)
        var seen: Set<String>? = null
        rule.setContent { seen = rememberInstalledGameIds(mapOf("chess" to CHESS, "sudoku" to SUDOKU)) }
        rule.waitUntil(TIMEOUT_MS) { seen == setOf("chess") }

        // Uninstalled while the hub sits in the background; coming back re-checks.
        shadowPm.deletePackage(CHESS)
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        rule.waitUntil(TIMEOUT_MS) { seen == emptySet<String>() }
    }

    private companion object {
        const val CHESS = "com.vayunmathur.games.chess"
        const val SUDOKU = "com.vayunmathur.games.sudoku"
        const val TIMEOUT_MS = 5_000L
    }
}
