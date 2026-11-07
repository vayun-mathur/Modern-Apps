package com.vayunmathur.updater.platform

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.DataStoreUtils
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class UpdaterViewModelTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val store get() = DataStoreUtils.getInstance(app)
    private val fetchCount = AtomicInteger(0)

    @BeforeTest
    fun setUp() = runBlocking {
        TestSysProps.setRunningBuild(dateUtcSeconds = 1000L)
        store.setBoolean(UpdaterPreferences.AUTO_INSTALL, true)
        store.setBoolean(UpdaterPreferences.METERED_ALLOWED, false)
        store.setLong(UpdaterPreferences.LAST_CHECKED, 0L)
        Unit
    }

    @AfterTest
    fun tearDown() {
        UpdateChecker.connectionOverride = null
    }

    private fun awaitState(vm: UpdaterViewModel, condition: (UpdaterUiState) -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition(vm.state)) return
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun fakeBody(body: String) {
        UpdateChecker.connectionOverride = { url ->
            fetchCount.incrementAndGet()
            FakeHttpsConnection(URL(url), 200, body.toByteArray())
        }
    }

    @Test
    fun `init loads the running build and the stored settings`() = runBlocking {
        store.setBoolean(UpdaterPreferences.AUTO_INSTALL, false)
        store.setBoolean(UpdaterPreferences.METERED_ALLOWED, true)
        store.setLong(UpdaterPreferences.LAST_CHECKED, 777L)
        val vm = UpdaterViewModel(app)
        awaitState(vm) { it.currentBuild == "2026090500" && it.lastCheckedMillis == 777L }
        assertEquals("2026090500", vm.state.currentBuild)
        assertFalse(vm.state.autoInstall)
        assertTrue(vm.state.meteredAllowed)
        assertEquals(777L, vm.state.lastCheckedMillis)
        assertFalse(vm.state.checking)
        assertNull(vm.state.available)
    }

    @Test
    fun `check now surfaces an available update and stamps the check time`() {
        fakeBody("2026090700 2000 shiba stable")
        val vm = UpdaterViewModel(app)
        awaitState(vm) { it.currentBuild == "2026090500" }
        val before = System.currentTimeMillis()
        vm.checkNow()
        assertTrue(vm.state.checking)
        // A second tap while a check is in flight is a no-op.
        vm.checkNow()
        awaitState(vm) { !it.checking && it.available != null }
        assertEquals("2026090700", vm.state.available?.build)
        assertTrue(vm.state.lastCheckedMillis >= before)
        assertNull(vm.state.lastFailure)
        assertEquals(1, fetchCount.get())
        runBlocking { assertTrue((store.getLongAwait(UpdaterPreferences.LAST_CHECKED) ?: 0L) >= before) }
    }

    @Test
    fun `check now clears the update when the device is up to date`() {
        fakeBody("2026090500 1000 shiba stable")
        val vm = UpdaterViewModel(app)
        awaitState(vm) { it.currentBuild == "2026090500" }
        vm.checkNow()
        awaitState(vm) { !it.checking && it.lastCheckedMillis > 0L }
        assertNull(vm.state.available)
        assertNull(vm.state.lastFailure)
    }

    @Test
    fun `check now records the failure reason verbatim`() {
        fakeBody("<html>portal</html>")
        val vm = UpdaterViewModel(app)
        awaitState(vm) { it.currentBuild == "2026090500" }
        vm.checkNow()
        awaitState(vm) { !it.checking && it.lastFailure != null }
        assertTrue(vm.state.lastFailure!!.contains("metadata line"), vm.state.lastFailure)
        assertNull(vm.state.available)
    }

    @Test
    fun `toggles update state immediately and persist`() = runBlocking {
        val vm = UpdaterViewModel(app)
        awaitState(vm) { it.currentBuild == "2026090500" }
        vm.setAutoInstall(false)
        assertFalse(vm.state.autoInstall)
        vm.setMeteredAllowed(true)
        assertTrue(vm.state.meteredAllowed)
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (store.getBooleanAwait(UpdaterPreferences.AUTO_INSTALL, true) == false &&
                store.getBooleanAwait(UpdaterPreferences.METERED_ALLOWED, false)
            ) return@runBlocking
            Thread.sleep(20)
        }
        assertFalse(store.getBooleanAwait(UpdaterPreferences.AUTO_INSTALL, true))
        assertTrue(store.getBooleanAwait(UpdaterPreferences.METERED_ALLOWED, false))
        assertNotNull(vm.state)
    }
}
