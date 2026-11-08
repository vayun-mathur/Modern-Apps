package com.vayunmathur.globalesim.platform

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.globalesim.data.api.EsimApi
import com.vayunmathur.globalesim.data.api.ProfileDto
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.robolectric.Shadows

/**
 * EsimViewModel offline behaviour: the API is unreachable from tests, so
 * loads settle into the "No connection" message with the anonymous SIM ID
 * minted, and every user action maps failures to its friendly message.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class EsimViewModelTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private lateinit var vm: EsimViewModel

    @BeforeTest fun setUp() {
        // Hermetic offline: dead loopback, so the API fails on any box
        // (CI has internet and the real API answered these tests before).
        EsimApi.baseUrlOverride = "https://127.0.0.1:9"
        vm = EsimViewModel(app)
    }

    @AfterTest fun tearDown() {
        EsimApi.baseUrlOverride = null
    }

    private fun await(timeoutMs: Long = 15_000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    @Test fun initMintsSimIdAndSettlesOffline() {
        assertTrue(
            await { vm.state.statusLoaded && vm.state.simId != null },
            "state=${vm.state}")
        assertFalse(vm.state.loading)
        assertNull(vm.state.profile)
        assertEquals(
            "No connection. Check your internet and try again.", vm.state.message)
        assertTrue(vm.state.rates.isEmpty())
    }

    @Test fun messageOneShotHelpers() {
        vm.notifyMessage("hello")
        assertEquals("hello", vm.state.message)
        vm.dismissMessage()
        assertNull(vm.state.message)
        vm.notifyMessage("again")
        vm.dismissMessage()
        assertNull(vm.state.message)
    }

    @Test fun finishCardPurchaseStates() {
        vm.finishCardPurchase(paid = false)
        assertNull(vm.state.pendingClientSecret)
        assertFalse(vm.state.loading)
        // Paid starts the credit watch (polling offline status in background);
        // the visible state is just cleared secret + not loading.
        vm.finishCardPurchase(paid = true)
        assertNull(vm.state.pendingClientSecret)
        assertFalse(vm.state.loading)
        vm.finishCardPurchaseWithError("Card declined")
        assertEquals("Card declined", vm.state.message)
        assertNull(vm.state.pendingClientSecret)
    }

    @Test fun purchasesFailOfflineWithFriendlyMessage() {
        assertTrue(await { vm.state.statusLoaded })
        var keyCallback: String? = null
        vm.startCardPurchase(1_000L, publishableKey = "pk_test") { keyCallback = it }
        assertTrue(await { !vm.state.loading && vm.state.message != null }, "state=${vm.state}")
        assertEquals(
            "No connection. Check your internet and try again.", vm.state.message)
        assertNull(vm.state.pendingClientSecret)
        assertNull(keyCallback)

        vm.dismissMessage()
        var invoiceReady: String? = null
        vm.startCryptoPurchase(1_000L) { invoiceReady = it }
        assertTrue(await { !vm.state.loading && vm.state.message != null }, "state=${vm.state}")
        assertEquals(
            "No connection. Check your internet and try again.", vm.state.message)
        assertNull(vm.state.pendingInvoiceUrl)
        assertNull(invoiceReady)
    }

    @Test fun claimAndClearInvoiceFailOffline() {
        assertTrue(await { vm.state.statusLoaded })
        vm.claim("  some-sim-id  ")
        assertTrue(await { !vm.state.loading && vm.state.message != null }, "state=${vm.state}")
        assertEquals(
            "No connection. Check your internet and try again.", vm.state.message)
        // SIM ID is not adopted on failure.
        assertTrue(vm.state.simId != "some-sim-id")

        vm.clearPendingInvoice()
        assertNull(vm.state.pendingInvoiceUrl)
        assertFalse(vm.state.loading)
    }

    @Test fun refreshCyclesSettleOffline() {
        assertTrue(await { vm.state.statusLoaded })
        vm.refresh()
        assertTrue(await { !vm.state.refreshing }, "state=${vm.state}")
        vm.refreshStatus()
        assertTrue(await { !vm.state.loading }, "state=${vm.state}")
        vm.refreshRates()
        assertTrue(await { vm.state.rates.isEmpty() })
    }

    @Test fun creditWatchPollsInBackground() {
        // Paid starts the watcher; it polls the (unreachable) status endpoint
        // on its fast cadence without disturbing the cleared purchase state.
        vm.finishCardPurchase(paid = true)
        assertTrue(await(6_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            vm.state.pendingClientSecret == null && !vm.state.loading
        })
        Thread.sleep(2_500)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNull(vm.state.pendingClientSecret)
        vm.clearPendingInvoice()
    }

    @Test fun routeAndStateValueSemantics() {
        val encoded = kotlinx.serialization.json.Json.encodeToString(
            com.vayunmathur.globalesim.Route.serializer(),
            com.vayunmathur.globalesim.Route.Tabs)
        assertTrue(encoded.contains("Tabs"), encoded)
        val s = EsimUiState(loading = true, simId = "x")
        assertEquals(s, s.copy())
        assertEquals(s.hashCode(), s.copy().hashCode())
        assertTrue(s.toString().contains("simId=x"))
        assertTrue(s != s.copy(loading = false))
    }

    @Test fun uiStateDefaults() {
        val s = EsimUiState()
        assertFalse(s.loading || s.statusLoaded || s.refreshing)
        assertNull(s.simId)
        assertNull(s.profile)
        assertTrue(s.rates.isEmpty())
        assertEquals(0L, s.pendingCreditCents)
        val profile = ProfileDto(iccid = "8901", status = "active", walletUsdCents = 250L)
        assertEquals("", profile.activationCode)
        assertEquals(250L, profile.walletUsdCents)
    }
}
