package com.vayunmathur.cast.platform

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.cast.R
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.ClientFailure
import com.vayunmathur.cast.domain.ClientPhase
import com.vayunmathur.cast.domain.ClientState
import com.vayunmathur.cast.platform.mirror.MirrorDegradation
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CastViewModelTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val device = CastDevice(id = "tv1", friendlyName = "TV", host = "127.0.0.1", port = 1)

    @Before fun reset() = runBlocking { CastController.teardown() }
    @After fun cleanup() {
        collectors.forEach { it.cancel() }
        collectors.clear()
        runBlocking { CastController.teardown() }
    }

    private val collectors = mutableListOf<kotlinx.coroutines.Job>()

    /** Collect on a background scope and pump the Robolectric main looper until the state lands. */
    private fun awaitState(vm: CastViewModel, condition: (CastUiState) -> Boolean): CastUiState {
        collectors += kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
            .launch { vm.uiState.collect { } }
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val state = vm.uiState.value
            if (condition(state)) return state
            Thread.sleep(20)
        }
        throw AssertionError("uiState never matched; last=${vm.uiState.value}")
    }

    @Test
    fun `factory creates the view model and rejects others`() {
        val factory = CastViewModelFactory(app)
        assertTrue(factory.create(CastViewModel::class.java) is CastViewModel)
        assertFailsWith<IllegalArgumentException> {
            factory.create(OtherViewModel::class.java)
        }
    }

    class OtherViewModel : ViewModel()

    @Test
    fun `ui state maps paired and streaming to connected`() = runBlocking {
        val vm = CastViewModel(app)
        CastController.deviceMutable.value = device
        CastController.sessionStateMutable.value = ClientState(phase = ClientPhase.Paired, receiverName = "TV")
        val paired = awaitState(vm) { it.connection == CastConnection.Connected }
        assertEquals(device, paired.connectedDevice)
        assertTrue(paired.canMirror)

        CastController.sessionStateMutable.value = ClientState(phase = ClientPhase.Streaming)
        awaitState(vm) { it.connection == CastConnection.Connected }

        CastController.deviceMutable.value = null
        val orphaned = awaitState(vm) { it.connection == CastConnection.Disconnected }
        assertEquals(null, orphaned.connectedDevice)
    }

    @Test
    fun `ui state maps connecting awaiting and mirror fields`() = runBlocking {
        val vm = CastViewModel(app)
        CastController.deviceMutable.value = device
        CastController.isConnectingMutable.value = true
        CastController.sessionStateMutable.value = ClientState(phase = ClientPhase.Connecting)
        awaitState(vm) { it.connection == CastConnection.Connecting }

        CastController.sessionStateMutable.value = ClientState(
            phase = ClientPhase.AwaitingCode, attemptsLeft = 2, codeChanged = true,
        )
        val awaiting = awaitState(vm) { it.connection == CastConnection.AwaitingCode }
        assertEquals(2, awaiting.pairAttemptsLeft)
        assertTrue(awaiting.pairCodeChanged)

        CastController.isConnectingMutable.value = false
        CastController.mirrorPhaseMutable.value = MirrorPhase.Mirroring
        CastController.degradationMutable.value = MirrorDegradation(videoUnavailable = true, audioUnavailable = true)
        val mirroring = awaitState(vm) { it.isMirroring && it.videoDegraded && it.audioDegraded }
        assertTrue(mirroring.isMirroring)
    }

    @Test
    fun `failure messages map for every client failure and mirror failure wins`() = runBlocking {
        val vm = CastViewModel(app)
        val expected = mapOf(
            ClientFailure.Unreachable to R.string.cast_connect_lost,
            ClientFailure.VersionMismatch to R.string.cast_version_mismatch,
            ClientFailure.CodeRejected to R.string.cast_pair_rejected,
            ClientFailure.StreamRefused to R.string.cast_mirror_negotiation_failed,
            ClientFailure.Protocol to R.string.cast_protocol_error,
        )
        for ((failure, stringRes) in expected) {
            CastController.sessionStateMutable.value =
                ClientState(phase = ClientPhase.Failed, failure = failure)
            val state = awaitState(vm) { it.connection == CastConnection.Failed && it.failure == app.getString(stringRes) }
            assertEquals(app.getString(stringRes), state.failure)
        }
        CastController.failureMutable.value = "mirror says boom"
        val overridden = awaitState(vm) { it.failure == "mirror says boom" }
        assertEquals("mirror says boom", overridden.failure)

        CastController.failureMutable.value = null
        CastController.sessionStateMutable.value = ClientState(phase = ClientPhase.Idle)
        val idle = awaitState(vm) { it.connection == CastConnection.Disconnected && it.failure == null }
        assertEquals(null, idle.failure)
    }

    @Test
    fun `scan start and stop toggle scanning state`() = runBlocking {
        val vm = CastViewModel(app)
        vm.startScan()
        awaitState(vm) { it.isScanning }
        vm.startScan() // restart is allowed
        vm.stopScan()
        val stopped = awaitState(vm) { !it.isScanning }
        assertTrue(!stopped.isScanning)
    }

    @Test
    fun `actions delegate to the controller`() = runBlocking {
        val vm = CastViewModel(app)
        // A version-mismatched device fails fast in the controller without any dialling.
        vm.connect(device.copy(protocolVersion = 999))
        withTimeout(8_000) {
            while (CastController.sessionState.value.phase != ClientPhase.Failed) delay(15)
        }
        vm.submitPairCode("123456") // no live client after the failure teardown: no-op
        vm.disconnect()
        withTimeout(8_000) {
            while (CastController.sessionState.value.phase != ClientPhase.Idle) delay(15)
        }
        vm.stopMirroring()
        delay(100)
    }

    @Test
    fun `settings and mirroring actions launch their activities`() {
        val vm = CastViewModel(app)
        vm.openLocalNetworkSettings()
        val settings = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings?.action)
        vm.startMirroring()
        val consent = Shadows.shadowOf(app).nextStartedActivity
        assertTrue(consent != null, "mirroring should launch the consent activity")
    }
}
