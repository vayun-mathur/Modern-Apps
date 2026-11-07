package com.vayunmathur.uniflix.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.uniflix.data.AddonEntity
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain

/**
 * AddonsViewModel with a fake AndroidKeyStore provider: the ViewModel can be
 * constructed (its init only builds the Room database object; the SQLCipher
 * connection itself is never opened on these paths).
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AddonsViewModelKeyStoreTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest fun setUp() {
        FakeAndroidKeyStore.install()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    private fun await(timeoutMs: Long = 15_000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    private fun entity(id: Long, name: String, kind: String = "http", priority: Int = 0) = AddonEntity(
        dbId = id, manifestUrl = "https://$name.invalid/manifest.json",
        baseUrl = "https://$name.invalid", addonId = name, name = name,
        kind = kind, priority = priority)

    @Test fun blankAndOfflineAddsResetAddingFlag() {
        val vm = AddonsViewModel(app)
        vm.addByUrl("  ")
        assertFalse(vm.adding)
        vm.addByUrl("https://addons.invalid/base")
        assertTrue(await { !vm.adding }, "adding=${vm.adding}")
        vm.addJsPlugin("")
        assertFalse(vm.adding)
        vm.addJsPlugin("not a plugin at all")
        assertTrue(await { !vm.adding }, "adding=${vm.adding}")
        assertFalse(vm.refreshing)
    }

    @Test fun moveGuardsAndJsNotices() {
        val vm = AddonsViewModel(app)
        val a = AddonRow(entity(1, "a", priority = 0), isDefault = false)
        val b = AddonRow(entity(2, "b", priority = 1), isDefault = false)
        val rows = listOf(a, b)
        vm.moveUp(rows, a)
        vm.moveDown(rows, b)
        vm.moveUp(rows, AddonRow(entity(99, "ghost"), isDefault = false))
        vm.moveDown(emptyList(), a)
        // JS rows take the notice path (no network, no database).
        val js = AddonRow(entity(3, "js", kind = "js"), isDefault = false)
        vm.refreshManifest(js)
        assertFalse(vm.refreshing)
    }

    @Test fun defaultPrefsViaViewModel() {
        val vm = AddonsViewModel(app)
        val a = AddonRow(entity(5, "a"), isDefault = false)
        vm.setDefault(a)
        vm.clearDefault()
        assertFalse(vm.adding)
        assertEquals(emptyList(), vm.addons.value)
    }
}
