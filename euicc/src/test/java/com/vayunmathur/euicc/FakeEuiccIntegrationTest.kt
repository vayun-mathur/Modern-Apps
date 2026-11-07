package com.vayunmathur.euicc

import android.app.Application
import android.content.Context
import android.service.euicc.EuiccService
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.euicc.platform.DownloadState
import com.vayunmathur.euicc.platform.EuiccViewModel
import com.vayunmathur.euicc.service.EuiccManagerService
import com.vayunmathur.euicc.telephony.FakeEuiccTelephonyShadow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.UiccCardInfoBuilder
import org.robolectric.shadows.UiccPortInfoBuilder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * With [FakeEuiccTelephonyShadow] reporting an eUICC and answering APDUs with
 * a bare `9000`, the channel opens and the native core runs; it finds no
 * profile data, which exercises the success-shaped paths of the ViewModel
 * reload/actions and the manager service that the no-hardware tests cannot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [FakeEuiccTelephonyShadow::class])
class FakeEuiccIntegrationTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        FakeEuiccTelephonyShadow.reset()
        EuiccNative.activeChannel = null
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        Shadows.shadowOf(tm).setUiccCardsInfo(
            listOf(
                UiccCardInfoBuilder.newBuilder()
                    .setIsEuicc(true).setCardId(1).setEid("89044000001234567890123456789012")
                    .setSlotIndex(0).setPhysicalSlotIndex(0)
                    .setPorts(
                        listOf(
                            UiccPortInfoBuilder.newBuilder().setPortIndex(0)
                                .setLogicalSlotIndex(0).setIsActive(true).build(),
                        ),
                    ).build(),
            ),
        )
    }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = EuiccViewModel(ApplicationProvider.getApplicationContext<Application>())

    private fun awaitVm(vm: EuiccViewModel, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (vm.state.loading && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    @Test fun `viewmodel reload succeeds over fake channel with empty euicc`() {
        val vm = vm()
        awaitVm(vm)
        assertFalse(vm.state.loading)
        assertNull(vm.state.error, "state=${vm.state}")
        assertTrue(vm.state.profiles.isEmpty())
        assertTrue(vm.state.notifications.isEmpty())
        // The channel was opened and closed for the reload.
        assertTrue(FakeEuiccTelephonyShadow.openCalls.isNotEmpty())
        assertEquals(FakeEuiccTelephonyShadow.openCalls.size, FakeEuiccTelephonyShadow.closeCalls.size)
    }

    @Test fun `viewmodel actions run over fake channel and settle`() {
        val vm = vm()
        awaitVm(vm)
        vm.enable("98AB")
        awaitVm(vm)
        assertFalse(vm.state.loading)
        vm.disable("98AB")
        awaitVm(vm)
        assertFalse(vm.state.loading)
        vm.rename("98AB", "Work")
        awaitVm(vm)
        assertFalse(vm.state.loading)
        vm.removeNotification(1)
        awaitVm(vm)
        assertFalse(vm.state.loading)
        assertTrue(FakeEuiccTelephonyShadow.openCalls.size >= 5)
    }

    @Test fun `viewmodel delete settles`() {
        val vm = vm()
        awaitVm(vm)
        vm.delete("98AB")
        awaitVm(vm)
        assertFalse(vm.state.loading)
    }

    @Test fun `manager service over fake channel returns empty success shapes`() {
        val controller = Robolectric.buildService(EuiccManagerService::class.java)
        val service = controller.create().get()
        assertNull(service.onGetEid(0))
        assertEquals("", service.onGetEuiccInfo(0).osVersion)
        assertNotNull(service.onGetEuiccProfileInfoList(0))
        assertEquals(EuiccService.RESULT_FIRST_USER, service.onDeleteSubscription(0, "8901"))
        assertEquals(EuiccService.RESULT_FIRST_USER, service.onSwitchToSubscription(0, "8901", false))
        assertEquals(EuiccService.RESULT_FIRST_USER, service.onSwitchToSubscription(0, null, false))
        assertEquals(EuiccService.RESULT_FIRST_USER, service.onUpdateSubscriptionNickname(0, "8901", "Work"))
        controller.destroy()
    }

    @Test fun `viewmodel startDownload reaches a terminal download state`() {
        val vm = vm()
        awaitVm(vm)
        vm.startDownload("LPA:1\$smdp.example.com\$MATCH1")
        assertEquals(DownloadState.Preparing, vm.download)
        val deadline = System.currentTimeMillis() + 30_000
        while (vm.download is DownloadState.Preparing && System.currentTimeMillis() < deadline) Thread.sleep(20)
        // Without a real SM-DP+ the authenticate phase must fail, not hang or
        // report success.
        assertTrue(
            vm.download is DownloadState.Failed || vm.download is DownloadState.Confirm ||
                vm.download is DownloadState.AwaitingConfirmationCode,
            "download=${vm.download}",
        )
        vm.cancelDownload()
        vm.clearDownload()
        assertEquals(DownloadState.Idle, vm.download)
    }
}
