package com.vayunmathur.euicc

import android.app.Application
import android.content.Context
import android.service.euicc.EuiccService
import android.telephony.TelephonyManager
import android.telephony.euicc.DownloadableSubscription
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.euicc.platform.DownloadState
import com.vayunmathur.euicc.platform.EuiccViewModel
import com.vayunmathur.euicc.service.EuiccManagerService
import com.vayunmathur.euicc.telephony.FakeEuiccTelephonyShadow
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.UiccCardInfoBuilder
import org.robolectric.shadows.UiccPortInfoBuilder
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Full SGP.22 flows against [FakeEuicc] (APDU layer) and [FakeSmdp] (local
 * HTTP ES9+), so the split-phase download, the ViewModel state machine around
 * it, and the manager service's profile operations run end to end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [FakeEuiccTelephonyShadow::class])
class FakeSmdpIntegrationTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var smdp: FakeSmdp
    private lateinit var euicc: FakeEuicc

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
        euicc = FakeEuicc()
        euicc.install()
        smdp = FakeSmdp()
        smdp.start()
        NetworkClient.init(ctx, TrustBundle.ESIM)
    }

    @AfterTest fun tearDown() {
        smdp.stop()
        Dispatchers.resetMain()
    }

    private fun channelManager() =
        com.vayunmathur.euicc.telephony.EuiccChannelManager(ctx)

    private fun vm() = EuiccViewModel(ApplicationProvider.getApplicationContext<Application>())

    private fun awaitVm(vm: EuiccViewModel, timeoutMs: Long = 20_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (vm.state.loading && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    private fun awaitDownload(vm: EuiccViewModel, pred: (DownloadState) -> Boolean, timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!pred(vm.download) && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    @Test fun `viewmodel reload reads eid info profiles and notifications from fake euicc`() {
        val vm = vm()
        awaitVm(vm)
        assertFalse(vm.state.loading)
        assertNull(vm.state.error, "state=${vm.state}")
        assertEquals("2.2.0", vm.state.info?.svn)
        assertEquals(listOf("1122"), vm.state.info?.ciPkIdListForVerification)
        assertNotNull(vm.state.eid)
        assertEquals(4, vm.state.profiles.size)
        assertEquals("Work", vm.state.profiles[0].displayName)
        assertTrue(vm.state.profiles[0].isEnabled)
        assertEquals("Global Data", vm.state.profiles[1].displayName)
        assertEquals(1, vm.state.notifications.size)
        assertEquals(3, vm.state.notifications[0].seqNumber)
        assertEquals("install", vm.state.notifications[0].operation)
    }

    @Test fun `viewmodel profile actions succeed against fake euicc`() {
        val vm = vm()
        awaitVm(vm)
        val disabled = vm.state.profiles.first { !it.isEnabled }
        vm.enable(disabled.iccid)
        awaitVm(vm)
        assertNull(vm.state.error, "state=${vm.state}")
        val enabled = vm.state.profiles.first { it.isEnabled }
        vm.disable(enabled.iccid)
        awaitVm(vm)
        assertNull(vm.state.error)
        vm.rename(disabled.iccid, "Renamed")
        awaitVm(vm)
        assertNull(vm.state.error)
        vm.removeNotification(3)
        awaitVm(vm)
        assertNull(vm.state.error)
        vm.delete(disabled.iccid)
        awaitVm(vm)
        assertNull(vm.state.error)
        assertEquals(4, vm.state.profiles.size)
    }

    @Test fun `native split phase authenticate finish and cancel on one thread`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Pair<String, String>> {
                channelManager().withIsdrChannel {
                    val authRaw = EuiccNative.nativeAuthenticate(smdp.activationCode(), "", "", "")
                    val auth = Json.parseToJsonElement(authRaw).jsonObject
                    val tx = auth["transactionId"]?.jsonPrimitive?.content
                    assertNotNull(tx, "auth=$authRaw")
                    assertEquals("Fake Carrier", auth["carrier"]?.jsonPrimitive?.content)
                    val finishRaw = EuiccNative.nativeFinishDownload(
                        tx, "", "Fake Plan",
                        object : EuiccNative.DownloadProgressCallback {
                            override fun onProgress(done: Int, total: Int): Boolean = true
                        },
                    )
                    // Second session for cancel.
                    val auth2Raw = EuiccNative.nativeAuthenticate(smdp.activationCode(), "", "", "")
                    val tx2 = Json.parseToJsonElement(auth2Raw).jsonObject["transactionId"]?.jsonPrimitive?.content
                    assertNotNull(tx2)
                    assertTrue(EuiccNative.nativeCancelDownload(tx2, 0))
                    authRaw to finishRaw
                }
            }.get()
            val finish = Json.parseToJsonElement(result.second).jsonObject
            assertEquals("true", finish["success"]?.jsonPrimitive?.content, "finish=${result.second}")
            assertEquals(1, smdp.count("getBoundProfilePackage"))
            assertEquals(1, smdp.count("cancelSession"))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun `viewmodel download confirms and completes`() {
        val vm = vm()
        awaitVm(vm)
        vm.startDownload(smdp.activationCode())
        awaitDownload(vm, { it is DownloadState.Confirm || it is DownloadState.Failed })
        assertTrue(vm.download is DownloadState.Confirm, "download=${vm.download}")
        val confirm = vm.download as DownloadState.Confirm
        assertEquals("Fake Carrier", confirm.carrier)
        assertEquals("Fake Plan", confirm.profileName)
        vm.confirmDownload()
        awaitDownload(vm, { it is DownloadState.Complete || it is DownloadState.Failed })
        assertTrue(vm.download is DownloadState.Complete, "download=${vm.download}")
        assertEquals("Fake Carrier", (vm.download as DownloadState.Complete).carrier)
        awaitVm(vm)
        assertNull(vm.state.error)
    }

    @Test fun `viewmodel download cancel from confirm returns to idle`() {
        val vm = vm()
        awaitVm(vm)
        vm.startDownload(smdp.activationCode())
        awaitDownload(vm, { it is DownloadState.Confirm || it is DownloadState.Failed })
        assertTrue(vm.download is DownloadState.Confirm, "download=${vm.download}")
        vm.cancelDownload()
        assertEquals(DownloadState.Idle, vm.download)
        vm.clearDownload()
        assertEquals(DownloadState.Idle, vm.download)
    }

    @Test fun `viewmodel confirmation code refusal relaunches to code screen with error`() {
        smdp.ccRequired = true
        smdp.refuseBoundProfilePackage = true
        val vm = vm()
        awaitVm(vm)
        vm.startDownload(smdp.activationCode())
        awaitDownload(vm, { it is DownloadState.AwaitingConfirmationCode || it is DownloadState.Failed })
        assertTrue(vm.download is DownloadState.AwaitingConfirmationCode, "download=${vm.download}")
        vm.submitConfirmationCode("badcode")
        awaitDownload(vm, {
            (it is DownloadState.AwaitingConfirmationCode && it.error) || it is DownloadState.Failed
        })
        assertTrue(
            vm.download is DownloadState.AwaitingConfirmationCode,
            "download=${vm.download}",
        )
        assertTrue((vm.download as DownloadState.AwaitingConfirmationCode).error)
        assertTrue(smdp.count("initiateAuthentication") >= 2, "counts=${smdp.counts}")
    }

    @Test fun `manager service maps profiles and runs operations`() {
        val controller = Robolectric.buildService(EuiccManagerService::class.java)
        val service = controller.create().get()
        assertEquals("2.2.0", service.onGetEuiccInfo(0).osVersion)
        assertNotNull(service.onGetEid(0))
        assertNotNull(service.onGetEuiccProfileInfoList(0))
        // Switch to a disabled profile by its display ICCID: enable succeeds.
        assertEquals(
            EuiccService.RESULT_OK,
            service.onSwitchToSubscription(0, "8901234567890000002", false),
        )
        // Null ICCID disables the currently enabled profile.
        assertEquals(EuiccService.RESULT_OK, service.onSwitchToSubscription(0, null, false))
        assertEquals(
            EuiccService.RESULT_OK,
            service.onSwitchToSubscriptionWithPort(0, 0, "8901234567890000002", false),
        )
        assertEquals(
            EuiccService.RESULT_OK,
            service.onUpdateSubscriptionNickname(0, "8901234567890000001", "Work"),
        )
        assertEquals(
            EuiccService.RESULT_OK,
            service.onDeleteSubscription(0, "8901234567890000003"),
        )
        // Unknown ICCID resolves to no profile.
        assertEquals(
            EuiccService.RESULT_FIRST_USER,
            service.onDeleteSubscription(0, "999999"),
        )
        controller.destroy()
    }

    @Test fun `manager service download subscription succeeds and honors switch flag`() {
        val controller = Robolectric.buildService(EuiccManagerService::class.java)
        val service = controller.create().get()
        val sub = DownloadableSubscription.forActivationCode(smdp.activationCode())
        assertEquals(
            EuiccService.RESULT_OK,
            service.onDownloadSubscription(0, sub, switchAfterDownload = false, forceDeactivateSim = false),
        )
        // An enabled profile exists, so switch-after-download without force
        // must ask the platform to deactivate first.
        assertEquals(
            EuiccService.RESULT_MUST_DEACTIVATE_SIM,
            service.onDownloadSubscription(0, sub, switchAfterDownload = true, forceDeactivateSim = false),
        )
        assertEquals(
            EuiccService.RESULT_OK,
            service.onDownloadSubscription(0, sub, switchAfterDownload = true, forceDeactivateSim = true),
        )
        controller.destroy()
    }
}
