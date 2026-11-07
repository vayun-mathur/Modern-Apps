package com.vayunmathur.euicc.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EuiccViewModelTest {
    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = EuiccViewModel(ApplicationProvider.getApplicationContext<Application>())

    /** Awaits the IO-backed reload/action settling (condition poll, not a timing assertion). */
    private fun awaitSettled(vm: EuiccViewModel, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (vm.state.loading && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    @Test fun `initial reload reports eUICC unavailable without hardware`() {
        val vm = vm()
        awaitSettled(vm)
        assertFalse(vm.state.loading)
        assertNotNull(vm.state.error, "expected eUICC error, state=${vm.state}")
        assertTrue(vm.state.profiles.isEmpty())
        assertTrue(vm.state.notifications.isEmpty())
        assertNull(vm.state.eid)
        assertEquals(DownloadState.Idle, vm.download)
    }

    @Test fun `profile actions surface failure and clear loading`() {
        val vm = vm()
        awaitSettled(vm)
        for (action in listOf<() -> Unit>(
            { vm.enable("98AB") }, { vm.disable("98AB") }, { vm.delete("98AB") },
            { vm.rename("98AB", "Work") }, { vm.removeNotification(3) },
        )) {
            action()
            awaitSettled(vm)
            assertFalse(vm.state.loading)
            assertNotNull(vm.state.error)
        }
    }

    @Test fun `startDownload fails cleanly without channel and can be retried from Failed`() {
        val vm = vm()
        awaitSettled(vm)
        vm.startDownload("LPA:1\$smdp.example.com\$MATCH1", imei = "123", confirmationCode = null)
        assertEquals(DownloadState.Preparing, vm.download)
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.download !is DownloadState.Failed && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(vm.download is DownloadState.Failed, "download=${vm.download}")
        assertNull((vm.download as DownloadState.Failed).message)
        // Failed is a re-entry point.
        vm.startDownload("LPA:1\$smdp.example.com\$MATCH1")
        assertEquals(DownloadState.Preparing, vm.download)
        val deadline2 = System.currentTimeMillis() + 10_000
        while (vm.download !is DownloadState.Failed && System.currentTimeMillis() < deadline2) Thread.sleep(10)
        assertTrue(vm.download is DownloadState.Failed)
    }

    @Test fun `confirm submit cancel and clear are no-ops without pending session`() {
        val vm = vm()
        awaitSettled(vm)
        vm.confirmDownload()
        assertEquals(DownloadState.Idle, vm.download)
        vm.confirmDownload("123456")
        assertEquals(DownloadState.Idle, vm.download)
        vm.submitConfirmationCode("123456")
        assertEquals(DownloadState.Idle, vm.download)
        vm.cancelDownload()
        assertEquals(DownloadState.Idle, vm.download)
        vm.cancelDownload(reason = 2)
        assertEquals(DownloadState.Idle, vm.download)
        vm.clearDownload()
        assertEquals(DownloadState.Idle, vm.download)
    }

    @Test fun `install nickname prefers profile name then carrier`() {
        // Regression: the nickname used to be read after the state had moved
        // to Installing, so the Confirm branch (and the profile name) was dead.
        assertEquals(
            "Plan 5G",
            installNickname(DownloadState.Confirm(carrier = "Carrier", profileName = "Plan 5G"), pendingCarrier = "Carrier"),
        )
        assertEquals(
            "Carrier",
            installNickname(DownloadState.Confirm(carrier = "Carrier", profileName = " "), pendingCarrier = "Other"),
        )
        assertEquals(
            "Carrier",
            installNickname(DownloadState.AwaitingConfirmationCode(carrier = "Carrier"), pendingCarrier = "Other"),
        )
        assertEquals(
            "Pending",
            installNickname(DownloadState.Installing(0.5f), pendingCarrier = "Pending"),
        )
        assertEquals("", installNickname(DownloadState.Idle, pendingCarrier = null))
    }

    @Test fun `reload keeps profiles empty and sets error repeatedly`() {
        val vm = vm()
        awaitSettled(vm)
        vm.reload()
        assertTrue(vm.state.loading)
        awaitSettled(vm)
        assertFalse(vm.state.loading)
        assertNotNull(vm.state.error)
    }
}
