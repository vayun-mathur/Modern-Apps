package com.vayunmathur.backup.platform

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.backup.FakeAndroidKeyStore
import com.vayunmathur.backup.data.BackendType
import com.vayunmathur.backup.data.BackupConfig
import com.vayunmathur.backup.data.BackupSettings
import com.vayunmathur.backup.domain.crypto.Bip39
import com.vayunmathur.backup.platform.crypto.KeyManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.Shadows.shadowOf
import android.os.Looper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class BackupViewModelTest {
    // VMs must be cleared after each test: a live BackupViewModel collects
    // BackupConfig.settings forever on the main looper, and a later test's
    // runBlocking DataStore edit on that same thread would deadlock against it.
    private val store = androidx.lifecycle.ViewModelStore()
    private fun vm(): BackupViewModel =
        androidx.lifecycle.ViewModelProvider(
            store,
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    BackupViewModel(app) as T
            },
        )[BackupViewModel::class.java]

    @After fun tearDown() { store.clear() }

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app

    @Before fun setUp() {
        FakeAndroidKeyStore.install(); FakeAndroidKeyStore.clear()
        ctx.getSharedPreferences("backup_secure_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        // Pumped off-main edit, never runBlocking on this thread: parking
        // the Robolectric main thread here wedged under a 5-CPU cap —
        // completing the BackupConfig clear waits on work queued in the
        // paused main looper (live BackupViewModels collect the shared
        // store from viewModelScope), and a parked thread never pumps it
        // (hung 9+ minutes in setUp, while passing locally). Launch the
        // edit on IO and idle the looper until it has landed.
        val done = CompletableDeferred<Unit>()
        CoroutineScope(Dispatchers.IO).launch {
            try { BackupConfig(ctx).clear(); done.complete(Unit) }
            catch (e: Throwable) { done.completeExceptionally(e) }
        }
        pumpUntil("backup config clear") { done.isCompleted }
        done.getCompletionExceptionOrNull()?.let { throw it }
        runCatching { KeyManager(ctx).clear() }
    }

    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }

    private fun awaitState(cond: () -> Boolean) {
        pumpUntil("backup state", cond)
    }
    /**
     * Step-bounded pumped wait on a terminal [condition]: every step
     * advances the paused main looper (running the ViewModel/DataStore
     * work queued there, including delayed tasks), then parks briefly
     * so Dispatchers.IO/Default producers get a CPU slice on a
     * contended box. The bound is pumping steps, never wall-clock
     * seconds that CI contention can burn before any work runs, and
     * the failure is raised only after [MAX_PUMP_STEPS] pumps.
     */
    private fun pumpUntil(description: String, condition: () -> Boolean) {
        var steps = 0
        while (true) {
            // Pump first, then check: the loops this replaces always
            // idled at least once before judging the condition, and a
            // condition that is already true at entry can still need
            // that one pump for the work behind it to settle.
            Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(PUMP_STEP_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (condition()) break
            java.util.concurrent.locks.LockSupport.parkNanos(
                java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(PARK_MS),
            )
            if (++steps >= MAX_PUMP_STEPS) {
                throw AssertionError("timed out (step-bounded): $description after $MAX_PUMP_STEPS pumps")
            }
        }
    }

    private companion object {
        const val PUMP_STEP_MS = 100L
        const val PARK_MS = 10L
        const val MAX_PUMP_STEPS = 6_000
    }


    @Test fun initialStateAndOnboardedLogic() {
        val vm = vm(); idle()
        assertFalse(vm.state.hasKey); assertFalse(vm.state.onboarded); assertFalse(vm.state.busy); assertFalse(vm.state.loading)
        assertEquals(BackupSettings(), vm.state.settings.copy(lastRun = vm.state.settings.lastRun))
        // Pure state logic
        assertFalse(BackupUiState(hasKey = true, settings = BackupSettings()).onboarded)
        assertTrue(BackupUiState(hasKey = true, settings = BackupSettings(BackendType.SAF, safTreeUri = "content://x")).onboarded)
        assertFalse(BackupUiState(hasKey = false, settings = BackupSettings(BackendType.SAF, safTreeUri = "content://x")).onboarded)
        assertTrue(BackupUiState(hasKey = true, settings = BackupSettings(BackendType.WEBDAV, webdavUrl = "https://h")).onboarded)
        val s = BackupUiState(loading = true, busy = true, message = "m", error = "e", generatedCode = listOf("a"))
        assertEquals("m", s.message); assertEquals("e", s.error); assertEquals(listOf("a"), s.generatedCode)
    }

    @Test fun generateAndInvalidCode() {
        val vm = vm(); idle()
        vm.generateRecoveryCode(); idle()
        assertEquals(12, vm.state.generatedCode.size)
        assertTrue(Bip39.isValid(vm.state.generatedCode))
        assertNull(vm.state.error)
        vm.confirmNewCode(listOf("not", "valid")); idle()
        assertNotNull(vm.state.error)
        vm.restoreWithCode(listOf("abandon") + List(11) { "abandon" }); idle()
        assertNotNull(vm.state.error) // bad checksum
        vm.dismissMessages(); assertNull(vm.state.error); assertNull(vm.state.message)
    }

    @Test fun confirmValidCodeStoresKey() {
        val vm = vm(); idle()
        val code = Bip39.generate()
        vm.confirmNewCode(code.map { "  ${it.uppercase()} " }); idle()
        // storeSeed runs on IO dispatcher; wait for main state update via looper + sleep-free polling on state
        awaitState { vm.state.hasKey }
        assertTrue(vm.state.hasKey)
        assertTrue(
            KeyManager(ctx).hasMasterKey(),
            "key not persisted: aliases=${com.vayunmathur.backup.FakeAndroidKeyStore.aliases()} " +
                "prefs=${ctx.getSharedPreferences("backup_secure_prefs", android.content.Context.MODE_PRIVATE).all.keys} msg=${vm.state.message}",
        )
        assertTrue(vm.state.generatedCode.isEmpty())
        assertNotNull(vm.state.message)
    }

    @Test fun restoreValidCodeStoresKey() {
        val vm = vm(); idle()
        vm.restoreWithCode(Bip39.generate()); idle()
        awaitState { vm.state.hasKey }
        assertTrue(vm.state.hasKey)
        assertTrue(vm.state.message!!.contains("accepted"))
    }

    @Test fun backendSelectionAndToggles() = runBlocking<Unit> {
        val vm = vm(); idle()
        vm.setWebDavBackend("", "u", "p"); assertNotNull(vm.state.error)
        vm.setWebDavBackend(" https://dav.example.com ", " alice ", "pw"); idle()
        awaitState { vm.state.settings.backendType == BackendType.WEBDAV }
        assertEquals(BackendType.WEBDAV, vm.state.settings.backendType)
        assertEquals("https://dav.example.com", vm.state.settings.webdavUrl)
        assertEquals("alice", vm.state.settings.webdavUser)
        vm.setSafBackend("content://tree/xyz"); idle()
        awaitState { vm.state.settings.backendType == BackendType.SAF }
        assertEquals("content://tree/xyz", vm.state.settings.safTreeUri)
        vm.setAppBackupEnabled(true); idle()
        awaitState { vm.state.settings.appBackupEnabled }
        assertTrue(vm.state.settings.appBackupEnabled)
        vm.setFileBackupEnabled(true); idle()
        awaitState { vm.state.settings.fileBackupEnabled }
        assertTrue(vm.state.settings.fileBackupEnabled)
        // Await the final toggle too: leaving it as a floating
        // viewModelScope DataStore edit means tearDown's store.clear()
        // can cancel it mid-flight and strand the shared store's write
        // actor — the next test's setUp clear then never lands (the
        // restoreValidCodeStoresKey wedge this ordering produced).
        vm.setFileBackupEnabled(false); idle()
        awaitState { !vm.state.settings.fileBackupEnabled }
        assertFalse(vm.state.settings.fileBackupEnabled)
    }

    @Test fun backupNowWithoutDestinationReportsError() {
        val vm = vm(); idle()
        vm.backupFilesNow()
        assertTrue(vm.state.busy)
        awaitState { !vm.state.busy }
        assertNotNull(vm.state.error)
        vm.restoreFilesNow()
        awaitState { !vm.state.busy }
        assertNotNull(vm.state.error)
        vm.dismissMessages(); assertNull(vm.state.error)
    }
}
