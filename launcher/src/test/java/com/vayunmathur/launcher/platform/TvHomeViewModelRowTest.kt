package com.vayunmathur.launcher.platform

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.Shadows.shadowOf

/**
 * Row-level tests for [TvHomeViewModel]: seeded tiles exercise move/cancel/
 * remove/finish paths that the state-machine tests cannot reach with an
 * empty grid, plus the open-* intent paths and a refresh over shadowed
 * installed leanback apps (the `refreshApps` tile-building lambda).
 */
@RunWith(RobolectricTestRunner::class)
class TvHomeViewModelRowTest {

    private fun vm(): TvHomeViewModel {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val created = TvHomeViewModel(app)
        // The init refresh runs on IO threads against real dispatchers; let
        // it settle before any test seeds row state, or it would land later
        // and overwrite the seeded rows with an empty Robolectric grid.
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!created.home.value.loading) return created
            Thread.sleep(10)
        }
        return created
    }

    private fun tile(pkg: String) = TvTile(pkg, pkg.substringAfterLast('.'), null, null, true)

    @Suppress("UNCHECKED_CAST")
    private fun seed(vm: TvHomeViewModel, pkgs: List<String>, persisted: List<String> = pkgs) {
        val homeField = TvHomeViewModel::class.java.getDeclaredField("_home").apply { isAccessible = true }
        val flow = homeField.get(vm) as MutableStateFlow<TvHomeState>
        flow.value = flow.value.copy(rowApps = pkgs.map(::tile), loading = false)
        val persistedField = TvHomeViewModel::class.java
            .getDeclaredField("lastPersistedOrder").apply { isAccessible = true }
        persistedField.set(vm, persisted)
    }

    private fun order(vm: TvHomeViewModel): List<String> = vm.home.value.rowApps.map { it.packageName }

    /** Reads the persisted TV order on a worker thread: collecting the DataStore flow via runBlocking on Robolectric's main thread can deadlock. */
    private fun persistedOrder(): List<String>? {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        var result: List<String>? = null
        val thread = Thread {
            result = runBlocking {
                com.vayunmathur.launcher.data.TvPrefs(app).settings.first().appOrder
            }
        }
        thread.start()
        thread.join(2000)
        return result
    }

    private fun await(vm: TvHomeViewModel, description: String, cond: () -> Boolean) {
        repeat(1000) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(10)
        }
        fail("timed out waiting for: $description (state=${vm.home.value})")
    }

    @Test fun moveKeyMovesHeldTileBothWaysAndClampsAtEnds() {
        val v = vm(); seed(v, listOf("a.one", "b.two", "c.three"))
        v.startMove("b.two")
        v.moveKey("b.two", 1)
        assertEquals(listOf("a.one", "c.three", "b.two"), order(v))
        v.moveKey("b.two", 1) // already last: no-op
        assertEquals(listOf("a.one", "c.three", "b.two"), order(v))
        v.moveKey("b.two", -5) // clamps to front via coerce + single-step move semantics
        assertEquals("b.two", order(v).first())
        assertEquals(3, order(v).size)
    }

    @Test fun moveKeyIgnoresUnknownAndUnheldPackages() {
        val v = vm(); seed(v, listOf("a.one", "b.two"))
        v.moveKey("a.one", 1) // not held
        assertEquals(listOf("a.one", "b.two"), order(v))
        v.startMove("a.one")
        v.moveKey("z.missing", 1)
        assertEquals(listOf("a.one", "b.two"), order(v))
    }

    @Test fun cancelMoveRestoresPersistedOrderAndKeepsArmed() {
        val v = vm(); seed(v, listOf("a.one", "b.two", "c.three"))
        v.startMove("a.one")
        v.moveKey("a.one", 1)
        assertEquals(listOf("b.two", "a.one", "c.three"), order(v))
        v.cancelMove()
        assertNull(v.home.value.movingKey)
        assertEquals(listOf("a.one", "b.two", "c.three"), order(v))
        assertTrue(v.home.value.reorderArmed)
    }

    @Test fun toggleOffWhileHoldingRestoresPersistedOrder() {
        val v = vm(); seed(v, listOf("a.one", "b.two", "c.three"))
        v.startMove("b.two")
        v.moveKey("b.two", -1)
        assertEquals(listOf("b.two", "a.one", "c.three"), order(v))
        v.toggleReorder()
        assertFalse(v.home.value.reorderArmed)
        assertNull(v.home.value.movingKey)
        assertEquals(listOf("a.one", "b.two", "c.three"), order(v))
    }

    @Test fun pressTileWhileAnotherIsHeldDoesNothing() {
        val v = vm(); seed(v, listOf("a.one", "b.two"))
        v.startMove("a.one")
        v.pressTile("b.two")
        assertEquals("a.one", v.home.value.movingKey)
        assertEquals(listOf("a.one", "b.two"), order(v))
    }

    @Test fun finishMovePersistsOnScreenOrderAndClearsHold() {
        val v = vm(); seed(v, listOf("a.one", "b.two", "c.three"))
        v.startMove("c.three")
        v.moveKey("c.three", -1)
        v.finishMove()
        await(v, "hold cleared after finishMove") { v.home.value.movingKey == null }
        assertEquals(listOf("a.one", "c.three", "b.two"), order(v))
        assertTrue(v.home.value.reorderArmed) // stays armed for the next move
        assertEquals(listOf("a.one", "c.three", "b.two"), persistedOrder())
    }

    @Test fun removeTileDropsTileClosesMenuAndPersistsKeptOrder() {
        val v = vm(); seed(v, listOf("a.one", "b.two", "c.three"))
        v.openEditMenu("b.two")
        v.removeTile("b.two")
        assertEquals(listOf("a.one", "c.three"), order(v))
        assertNull(v.home.value.editingKey)
        await(v, "removal persisted") { persistedOrder() == listOf("a.one", "c.three") }
    }

    @Test fun openTileForMissingPackageLeavesStateUntouched() {
        val v = vm(); seed(v, listOf("a.one"))
        v.openTile("no.such.package")
        v.openAppStore()
        assertEquals(listOf("a.one"), order(v))
        assertNull(v.home.value.movingKey)
    }

    @Test fun openSettingsAndNetworkSettingsStartActivities() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val v = vm()
        v.openSettings()
        val started = shadowOf(app).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_SETTINGS, started?.action)
        v.openNetworkSettings()
        val network = shadowOf(app).nextStartedActivity
        assertTrue(
            network?.action in listOf(
                android.provider.Settings.ACTION_WIFI_SETTINGS,
                android.provider.Settings.ACTION_WIRELESS_SETTINGS,
                android.provider.Settings.ACTION_SETTINGS,
            ),
        )
    }

    @Test fun uninstallOrInfoForUnknownPackageOpensAppDetailsAndClosesMenu() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val v = vm(); seed(v, listOf("a.one"))
        v.openEditMenu("a.one")
        v.uninstallOrInfo("a.one") // not installed per shadow PM: not user-installed
        val started = shadowOf(app).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started?.action)
        assertEquals("package:a.one", started?.dataString)
        assertNull(v.home.value.editingKey)
    }

    @Test fun refreshAppsBuildsTilesFromShadowedLeanbackApps() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val pm = app.packageManager
        val shadowPm = shadowOf(pm)
        for (pkg in listOf("com.netflix.ninja", "com.example.alpha", "com.example.beta")) {
            val component = android.content.ComponentName(pkg, "$pkg.Main")
            val resolve = ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = pkg
                    name = "$pkg.Main"
                }
            }
            shadowPm.addResolveInfoForIntent(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
                resolve,
            )
            shadowPm.addActivityIfNotPresent(component)
            shadowPm.addIntentFilterForActivity(
                component,
                android.content.IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    addCategory(Intent.CATEGORY_DEFAULT)
                },
            )
            val packageInfo = PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply {
                    packageName = pkg
                    flags = 0
                }
            }
            shadowPm.addPackage(packageInfo)
        }
        // A sibling test may have persisted a row order; a saved order is
        // honoured verbatim (no top-up), so persist one naming these apps.
        val writer = Thread {
            runBlocking {
                com.vayunmathur.launcher.data.TvPrefs(app)
                    .saveOrder(listOf("com.netflix.ninja", "com.example.alpha", "com.example.beta"))
            }
        }
        writer.start(); writer.join(5000)
        // Registration preceded construction, so the init refresh already
        // rebuilds with these apps; no second refreshApps() is needed.
        val v = vm()
        await(v, "tiles built") { v.home.value.rowApps.isNotEmpty() }
        val pkgs = order(v)
        assertTrue("com.netflix.ninja" in pkgs, "expected default-row netflix in $pkgs")
        assertFalse(v.home.value.loading)
        assertEquals(pkgs.size, pkgs.distinct().size)
        // The App Store (reported installed by Robolectric's shadow PM) owns
        // a bottom-row slot, so it must not also appear on the main row.
        assertFalse(TvHomeViewModel.APP_STORE in pkgs)
    }

    @Test fun refreshAppsDoesNotClobberInProgressMove() {
        val v = vm(); seed(v, listOf("a.one", "b.two"))
        v.startMove("a.one")
        v.refreshApps()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("a.one", v.home.value.movingKey)
        assertEquals(listOf("a.one", "b.two"), order(v))
    }

    @Test fun openAddMenuShowsMenuEvenWithNoCandidates() {
        val v = vm(); seed(v, listOf("a.one"))
        v.openAddMenu(replaceIndex = 0)
        await(v, "add menu shown") { v.home.value.showAddMenu }
        assertEquals(0, v.home.value.addReplacesIndex)
        assertTrue(v.home.value.addCandidates.none { it.packageName == "a.one" })
    }

    @Test fun pickAddCandidateDismissesAddMenu() {
        val v = vm(); seed(v, listOf("a.one", "b.two"))
        // Simulate an open add menu replacing the first slot.
        val homeField = TvHomeViewModel::class.java.getDeclaredField("_home").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val flow = homeField.get(v) as MutableStateFlow<TvHomeState>
        flow.value = flow.value.copy(showAddMenu = true, addReplacesIndex = 0)
        v.pickAddCandidate("z.added")
        await(v, "add menu dismissed") { !v.home.value.showAddMenu }
        assertEquals(-1, v.home.value.addReplacesIndex)
        assertTrue(v.home.value.addCandidates.isEmpty())
    }

    @Test fun refreshDefaultHomeMatchesRoleHelper() {
        val v = vm()
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).create().get()
        v.refreshDefaultHome(activity)
        // Robolectric resolves HOME to this app's own manifest activity.
        assertEquals(TvHomeRole.isDefaultHome(activity), v.home.value.isDefaultHome)
        v.requestHomeRole(activity) // must not throw when role cannot be taken
    }
}
