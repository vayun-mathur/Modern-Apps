package com.vayunmathur.communicate.rcs

import android.content.Context
import android.net.Uri
import android.telephony.ims.RcsClientConfiguration
import android.telephony.ims.`ProvisioningManager$RcsProvisioningCallback`
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.rcs.RcsHiddenApi
import com.vayunmathur.communicate.data.rcs.RcsMsrp
import com.vayunmathur.communicate.data.rcs.RcsSession
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pass 3: the hidden-API reflection bridge against hand-written fake
 * framework objects (UCE capability callbacks via dynamic proxy,
 * provisioning callback via the concrete stub subclass), plus MSRP
 * connection failure edges over loopback sockets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HiddenApiRcsPass3Test {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val executor = Executors.newSingleThreadExecutor()

    @Test fun uceBridgeFailsClosedWithoutAdapterMethods() {
        val callback = object : RcsHiddenApi.UceCallback {
            override fun onCapabilitiesReceived(caps: List<*>) {}
            override fun onComplete() {}
            override fun onError(errorCode: Int, retryAfterMillis: Long) {}
        }
        // No reflective requestCapabilities/requestAvailability methods here.
        assertFalse(RcsHiddenApi.requestUceCapabilities(Any(), emptyList(), executor, callback))
        assertFalse(
            RcsHiddenApi.requestUceAvailability(Any(), Uri.parse("tel:+15550007002"), executor, callback),
        )
    }

    /** Stands in for ProvisioningManager (matched reflectively). */
    class FakeProvisioningManager {
        var config: RcsClientConfiguration? = null
        var callback: `ProvisioningManager$RcsProvisioningCallback`? = null
        var unregisteredWith: Any? = null
        fun setRcsClientConfiguration(config: RcsClientConfiguration) { this.config = config }
        fun registerRcsProvisioningCallback(
            executor: java.util.concurrent.Executor,
            callback: `ProvisioningManager$RcsProvisioningCallback`,
        ) { this.callback = callback }
        fun unregisterRcsProvisioningCallback(callback: `ProvisioningManager$RcsProvisioningCallback`) {
            unregisteredWith = callback
        }
        fun isRcsVolteSingleRegistrationCapable(): Boolean = true
    }

    class ThrowingProvisioningManager {
        fun isRcsVolteSingleRegistrationCapable(): Boolean = throw RuntimeException("dead")
    }

    @Test fun provisioningCallbackSubclassForwardsEvents() {
        val pm = FakeProvisioningManager()
        var changed: ByteArray? = null
        var resets = 0
        var removed = 0
        val callback = object : RcsHiddenApi.ProvisioningCallback {
            override fun onConfigurationChanged(configXml: ByteArray) { changed = configXml }
            override fun onConfigurationReset() { resets++ }
            override fun onRemoved() { removed++ }
        }
        assertTrue(RcsHiddenApi.registerProvisioningCallback(pm, executor, "6.0", "UP_2.0", callback))
        assertNotNull(pm.config)
        val subclass = pm.callback
        assertNotNull(subclass)
        subclass.onConfigurationChanged("<rcs/>".toByteArray())
        assertEquals("<rcs/>", changed?.toString(Charsets.UTF_8))
        subclass.onConfigurationReset()
        subclass.onRemoved()
        assertEquals(1, resets)
        assertEquals(1, removed)
        // Unregister without a proxy is a no-op; with one it dispatches.
        RcsHiddenApi.unregisterProvisioningCallback(pm)
        RcsHiddenApi.unregisterProvisioningCallback(pm, subclass)
        // Single-reg tri-state.
        assertEquals(true, RcsHiddenApi.isSingleRegCapable(pm))
        assertNull(RcsHiddenApi.isSingleRegCapable(ThrowingProvisioningManager()))
        assertNull(RcsHiddenApi.isSingleRegCapable(Any()))
        // The reflective delegate-manager lookup resolves under Robolectric.
        assertNotNull(RcsHiddenApi.sipDelegateManager(context, 1))
    }

    @Test fun msrpConnectRefusedAndSendEdges() = runBlocking {
        // No listener: connect fails closed.
        val dead = RcsSession(
            dialogId = "d-ms", callId = "ms-1", localTag = "lt", remoteTag = "rt",
            remoteUri = "sip:+15550007009@rcs", conversationId = "ms-conv",
            msrpRemotePath = "msrp://127.0.0.1:1/dead;tcp",
            msrpLocalPath = "msrp://127.0.0.1/local;tcp",
        )
        assertNull(RcsMsrp.connect(dead, context) { _, _ -> })
        // No remote path at all.
        assertNull(RcsMsrp.connect(dead.copy(msrpRemotePath = null), context) { _, _ -> })

        // Loopback pair: SEND round trip in both directions.
        val server = ServerSocket(0)
        try {
            val port = server.localPort
            val clientSession = dead.copy(
                dialogId = "d-ms2", callId = "ms-2",
                msrpRemotePath = "msrp://127.0.0.1:$port/peer;tcp",
            )
            val receivedByServer = CopyOnWriteArrayList<ByteArray>()
            val acceptedRef = java.util.concurrent.atomic.AtomicReference<RcsMsrp.MsrpConnection>()
            val acceptThread = Thread {
                runCatching {
                    val socket: Socket = server.accept()
                    val conn = RcsMsrp.wrapAccepted(
                        socket,
                        com.vayunmathur.communicate.data.rcs.RcsMsrpListen.AcceptedHead(emptyList()),
                        "msrp://127.0.0.1:$port/peer;tcp",
                        "msrp://127.0.0.1/local;tcp",
                    ) { _, body -> receivedByServer += body }
                    acceptedRef.set(conn)
                }
            }.apply { isDaemon = true; start() }

            val conn = RcsMsrp.connect(clientSession, context) { _, _ -> }
            assertNotNull(conn)
            assertTrue(conn.sendCpim("hello msrp".toByteArray()))
            val deadline = System.currentTimeMillis() + 10_000
            while (receivedByServer.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals("hello msrp", receivedByServer.firstOrNull()?.toString(Charsets.UTF_8))
            // Empty payload and closed connection refuse to send.
            assertFalse(conn.sendCpim(ByteArray(0)))
            conn.close()
            assertTrue(conn.isClosed())
            assertFalse(conn.sendCpim("late".toByteArray()))
            acceptedRef.get()?.close()
            acceptThread.join(TimeUnit.SECONDS.toMillis(5))
        } finally {
            server.close()
        }
    }
}
