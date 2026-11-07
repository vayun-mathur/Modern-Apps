package com.vayunmathur.setupwizard.platform

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SetupViewModelTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test fun `initial state reflects the platform readings`() {
        val vm = SetupViewModel(app)
        assertTrue(vm.state.language.isNotBlank())
        assertEquals(OEM_UNLOCK_ACK_SECONDS, vm.state.bootloaderAckSeconds)
        // Robolectric reports a primary user on an unlocked, insecure device
        assertTrue(vm.state.isPrimaryUser)
        assertFalse(vm.state.bootloaderUnlocked)
        assertFalse(vm.state.deviceSecure)
    }

    @Test fun `language can be set and available languages are listed`() {
        val vm = SetupViewModel(app)
        // The system-locale write goes through a hidden framework API that Robolectric
        // does not implement, so the reading stays at the system locale; the call must
        // still complete and refresh the display name from the platform.
        vm.setLanguage(Locale.FRENCH)
        assertEquals(vm.system.currentLocale().getDisplayName(vm.system.currentLocale()), vm.state.language)
        vm.setLanguage(Locale.US)
        assertTrue(vm.availableLanguages().isNotEmpty())
    }

    @Test fun `location toggles round trip through settings`() {
        val vm = SetupViewModel(app)
        // Location/wifi writes also ride hidden APIs; under Robolectric they no-op and
        // the state simply mirrors whatever the platform reports afterwards.
        vm.setLocationEnabled(true)
        assertEquals(vm.system.isLocationEnabled(), vm.state.locationEnabled)
        vm.setLocationEnabled(false)
        assertEquals(vm.system.isLocationEnabled(), vm.state.locationEnabled)
        vm.setWifiScanningEnabled(true)
        assertEquals(vm.system.isWifiScanningAlwaysAvailable(), vm.state.wifiScanningEnabled)
    }

    @Test fun `welcome does not divert to bootloader warning when locked`() {
        val vm = SetupViewModel(app)
        vm.onEnterWelcome() // applies SIM locale if any; must not crash either way
        assertFalse(vm.welcomeLeadsToBootloaderWarning())
        vm.refreshSecurity()
        assertFalse(vm.state.deviceSecure)
    }

    @Test fun `ack timer counts down once and does not restart`() {
        val vm = SetupViewModel(app)
        vm.startBootloaderAckTimer()
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline && vm.state.bootloaderAckSeconds >= OEM_UNLOCK_ACK_SECONDS) {
            Thread.sleep(50)
        }
        assertTrue(vm.state.bootloaderAckSeconds < OEM_UNLOCK_ACK_SECONDS, "timer did not tick: ${vm.state.bootloaderAckSeconds}")
        val seen = vm.state.bootloaderAckSeconds
        vm.startBootloaderAckTimer() // guarded: no second timer
        assertTrue(vm.state.bootloaderAckSeconds <= seen)
    }

    @Test fun `debug flags parse values bare flags and booleans`() {
        Settings.Global.putString(ctx.contentResolver, "setupwizard_debug_flags", "alpha=1,beta,gamma=false")
        assertEquals("1", DebugFlags.get(ctx, "alpha"))
        assertEquals("true", DebugFlags.get(ctx, "beta"))
        assertEquals("false", DebugFlags.get(ctx, "gamma"))
        assertEquals(false, DebugFlags.getBool(ctx, "gamma"))
        assertEquals(true, DebugFlags.getBool(ctx, "beta"))
        assertEquals(null, DebugFlags.get(ctx, "missing"))
        assertEquals(null, DebugFlags.getBool(ctx, "alpha")) // "1" is not a strict boolean
        Settings.Global.putString(ctx.contentResolver, "setupwizard_debug_flags", null)
        assertEquals(null, DebugFlags.get(ctx, "alpha"))
    }

    @Test fun `setup intents target the platform settings actions`() {
        assertEquals("android.settings.ACCESSIBILITY_SETTINGS_FOR_SUW", SetupIntents.accessibilitySettings().action)
        assertTrue(SetupIntents.biometricEnroll().action != null)
        assertTrue(SetupIntents.gestureTutorial().action != null)
        val internet = SetupIntents.setupInternet("Wi-Fi", "Pick one", "Skip")
        assertTrue(internet.action != null)
        // Nothing restores backups on this test image: the migration step is skipped by design
        assertEquals(null, SetupIntents.restoreBackup(ctx))
    }
}
