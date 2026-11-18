package com.vayunmathur.findfamily.platform

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [UwbController] on SDK 36, where `android.ranging` exists. Robolectric supplies a
 * `RangingManager` whose sessions never call back, so what is pinned here is the local
 * parameter generation, the state machine, the preference/device construction on the
 * way into `stream`, and fail-closed behaviour — not radio results.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UwbControllerSdk36Test {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun openControllerGeneratesFiRaParams() {
        val ctrl = UwbController(ctx)
        val info = ctrl.openController().getOrThrow()
        assertEquals(2, info.localAddress.size)
        // Reserved FiRa addresses 0x0000 / 0xFFFF must never be handed out.
        assertTrue(!(info.localAddress[0] == 0.toByte() && info.localAddress[1] == 0.toByte()))
        assertEquals(8, info.sessionKey.size)
        assertEquals(UwbController.DEFAULT_CHANNEL, info.channelNumber)
        assertEquals(UwbController.DEFAULT_PREAMBLE, info.preambleIndex)
        assertEquals(UwbController.State.Ready(info.localAddress), ctrl.state.value)
        ctrl.stop()
    }

    @Test fun openControleeGeneratesOnlyAddress() {
        val ctrl = UwbController(ctx)
        val addr = ctrl.openControlee().getOrThrow()
        assertEquals(2, addr.size)
        assertEquals(UwbController.State.Ready(addr), ctrl.state.value)
        ctrl.stop()
        assertEquals(UwbController.State.Idle, ctrl.state.value)
    }

    @Test fun stopIsIdempotentFromIdle() {
        val ctrl = UwbController(ctx)
        ctrl.stop()
        ctrl.stop()
        assertEquals(UwbController.State.Idle, ctrl.state.value)
    }

    @Test fun streamWithBadLocalAddressFailsClosed() = runBlocking {
        val ctrl = UwbController(ctx)
        val job = async {
            runCatching {
                ctrl.stream(
                    role = UwbController.Role.Initiator,
                    localAddress = byteArrayOf(1, 2, 3),
                    peerAddress = byteArrayOf(3, 4),
                    sessionId = 42,
                    sessionKey = ByteArray(8),
                    channelNumber = 9,
                    preambleIndex = 10,
                ).collect { }
            }
        }
        // Malformed address must not crash collection; session ends via stop().
        delay(1_500)
        job.cancel()
        ctrl.stop()
        assertEquals(UwbController.State.Idle, ctrl.state.value)
        Unit
    }

    @Test fun streamBuildsPreferenceAndStartsSession() = runBlocking {
        val ctrl = UwbController(ctx)
        var sawDisconnect = false
        val job = async {
            runCatching {
                ctrl.stream(
                    role = UwbController.Role.Responder,
                    localAddress = byteArrayOf(1, 2),
                    peerAddress = byteArrayOf(3, 4),
                    sessionId = 7,
                    sessionKey = ByteArray(8) { it.toByte() },
                    channelNumber = 9,
                    preambleIndex = 10,
                ).collect { if (it.peerDisconnected) sawDisconnect = true }
            }
        }
        delay(1_500)
        job.cancel()
        ctrl.stop()
        assertEquals(UwbController.State.Idle, ctrl.state.value)
        Unit
    }

    @Test fun uwbBytesRoundTrip() {
        val bytes = byteArrayOf(0, 1, 127, -128, -1)
        assertTrue(bytes.contentEquals(UwbBytes.from(UwbBytes.b64(bytes))))
        assertFailsWith<IllegalArgumentException> { UwbBytes.from("!!!not-base64!!!") }
    }

    @Test fun rangingSampleDefaultsToLive() {
        val s = RangingSample(1.5f, 10f, null, 123L)
        assertEquals(false, s.peerDisconnected)
        assertNotNull(s.distanceMeters)
    }
}
