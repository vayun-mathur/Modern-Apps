package com.vayunmathur.globalesim.platform

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.globalesim.data.AnonymousIdStore
import com.vayunmathur.globalesim.network.EsimApi
import com.vayunmathur.globalesim.network.ProfileDto
import com.vayunmathur.testsupport.awaitWithMainIdle
import com.vayunmathur.testsupport.idleMainLooper
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * EsimViewModel offline behaviour: the API is unreachable from tests, so
 * loads settle into the "No connection" message with the anonymous SIM ID
 * minted, and every user action maps failures to its friendly message.
 *
 * Isolation (why the whole class used to fail while single methods passed):
 * this class builds a fresh ViewModel per method, but production gets its
 * one instance from `by viewModels()`, whose ViewModelStore cancels
 * viewModelScope when the activity finishes. Bare construction here
 * bypassed that, so every previous method's coroutines — the init load and,
 * worst, the 45-minute credit-watch poll started by the paid-purchase
 * tests — kept running against the process-wide state for the rest of the
 * class: the `preferencesDataStore` delegate behind [AnonymousIdStore]
 * (one DataStore per file for the whole test process) and
 * [EsimApi.baseUrlOverride]. Later methods then raced those leaked polls
 * for the same account id and never settled inside the old wall-clock
 * await. The store below restores the production lifecycle (clearing it in
 * tearDown cancels the scope, credit watch included), each method seeds a
 * unique account id before its ViewModel reads one, and all waits go
 * through the shared [awaitWithMainIdle] instead of sleeping.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class EsimViewModelTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private lateinit var viewModelStore: ViewModelStore
    private lateinit var vm: EsimViewModel

    @BeforeTest fun setUp() {
        // Hermetic offline: dead loopback, so the API fails on any box
        // (CI has internet and the real API answered these tests before).
        EsimApi.baseUrlOverride = "https://127.0.0.1:9"
        // Unique account id per method, seeded before the ViewModel's init
        // reads it: the DataStore file itself is process-wide (see class
        // doc), so value-level isolation is what a method can own.
        runBlocking { AnonymousIdStore(app).replace(UUID.randomUUID().toString()) }
        viewModelStore = ViewModelStore()
        vm = EsimViewModel(app)
        viewModelStore.put(VM_KEY, vm)
    }

    @AfterTest fun tearDown() {
        // onCleared() closes viewModelScope: cancels the init load, any
        // in-flight action, and the credit-watch poll, so nothing from this
        // method can touch the shared DataStore or API override afterwards.
        viewModelStore.clear()
        EsimApi.baseUrlOverride = null
    }

    /**
     * Deterministic form of the old sleep-and-deadline loop: pump the main
     * looper until [cond] holds. The generous step bound is the cast
     * precedent for producers on IO (the dead-loopback calls land there);
     * each step yields so those threads get wall time, and the bound is a
     * failure limit only, never the mechanism.
     */
    private fun await(description: String, cond: () -> Boolean): Boolean =
        try {
            awaitWithMainIdle(description, maxSteps = AWAIT_MAX_STEPS, condition = cond)
            true
        } catch (_: IllegalStateException) {
            false
        }

    private companion object {
        const val VM_KEY = "esim"
        const val AWAIT_MAX_STEPS = 200_000
    }

    @Test fun initMintsSimIdAndSettlesOffline() {
        assertTrue(
            await("init settles offline") { vm.state.statusLoaded && vm.state.simId != null },
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
        assertTrue(await("status loaded") { vm.state.statusLoaded })
        var keyCallback: String? = null
        vm.startCardPurchase(1_000L, publishableKey = "pk_test") { keyCallback = it }
        assertTrue(await("action fails offline") { !vm.state.loading && vm.state.message != null }, "state=${vm.state}")
        assertEquals(
            "No connection. Check your internet and try again.", vm.state.message)
        assertNull(vm.state.pendingClientSecret)
        assertNull(keyCallback)

        vm.dismissMessage()
        var invoiceReady: String? = null
        vm.startCryptoPurchase(1_000L) { invoiceReady = it }
        assertTrue(await("action fails offline") { !vm.state.loading && vm.state.message != null }, "state=${vm.state}")
        assertEquals(
            "No connection. Check your internet and try again.", vm.state.message)
        assertNull(vm.state.pendingInvoiceUrl)
        assertNull(invoiceReady)
    }

    @Test fun claimAndClearInvoiceFailOffline() {
        assertTrue(await("status loaded") { vm.state.statusLoaded })
        vm.claim("  some-sim-id  ")
        assertTrue(await("action fails offline") { !vm.state.loading && vm.state.message != null }, "state=${vm.state}")
        assertEquals(
            "No connection. Check your internet and try again.", vm.state.message)
        // SIM ID is not adopted on failure.
        assertTrue(vm.state.simId != "some-sim-id")

        vm.clearPendingInvoice()
        assertNull(vm.state.pendingInvoiceUrl)
        assertFalse(vm.state.loading)
    }

    @Test fun refreshCyclesSettleOffline() {
        assertTrue(await("status loaded") { vm.state.statusLoaded })
        vm.refresh()
        assertTrue(await("refresh settles") { !vm.state.refreshing }, "state=${vm.state}")
        vm.refreshStatus()
        assertTrue(await("refreshStatus settles") { !vm.state.loading }, "state=${vm.state}")
        vm.refreshRates()
        assertTrue(await("rates settle empty") { vm.state.rates.isEmpty() })
    }

    @Test fun creditWatchPollsInBackground() {
        // Paid starts the watcher; it polls the (unreachable) status endpoint
        // on its fast cadence without disturbing the cleared purchase state.
        // No wall-clock wait for a poll tick: a failed poll changes no visible
        // state, so pumping the looper and re-checking the cleared state is
        // the deterministic form of the old sleep-and-recheck.
        vm.finishCardPurchase(paid = true)
        assertTrue(
            await("credit watch leaves purchase state cleared") {
                vm.state.pendingClientSecret == null && !vm.state.loading
            })
        idleMainLooper()
        assertNull(vm.state.pendingClientSecret)
        assertFalse(vm.state.loading)
        vm.clearPendingInvoice()
    }

    @Test fun routeAndStateValueSemantics() {
        val encoded = kotlinx.serialization.json.Json.encodeToString(
            com.vayunmathur.globalesim.Route.serializer(),
            com.vayunmathur.globalesim.Route.Purchase)
        assertTrue(encoded.contains("Purchase"), encoded)
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
