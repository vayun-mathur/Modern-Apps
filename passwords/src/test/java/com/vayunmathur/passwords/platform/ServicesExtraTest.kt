package com.vayunmathur.passwords.platform

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.provider.BeginCreateCustomCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.passwords.FakeAndroidKeyStore
import com.vayunmathur.passwords.platform.cable.CableActivity
import com.vayunmathur.passwords.platform.cable.CableService
import com.vayunmathur.passwords.platform.cable.CableTunnel
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServicesExtraTest {

    @Before fun setUp() = FakeAndroidKeyStore.install()

    private val app: android.app.Application get() =
        androidx.test.core.app.ApplicationProvider.getApplicationContext()

    // ---- PasskeyCredentialService ----

    @Test fun credentialServiceCreatePublicKeyReturnsEntry() {
        val service = Robolectric.buildService(PasskeyCredentialService::class.java).create().get()
        var response: BeginCreateCredentialResponse? = null
        var error: CreateCredentialException? = null
        service.onBeginCreateCredentialRequest(
            BeginCreatePublicKeyCredentialRequest(
                requestJson = """{"rp":{"id":"example.com"},"user":{"id":"dQ","name":"a"},"challenge":"Yw"}""",
                candidateQueryData = Bundle(),
                callingAppInfo = null,
            ),
            CancellationSignal(),
            object : OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException> {
                override fun onResult(r: BeginCreateCredentialResponse) { response = r }
                override fun onError(e: CreateCredentialException) { error = e }
            },
        )
        assertEquals(null, error)
        assertEquals(1, response!!.createEntries.size)
    }

    @Test fun credentialServiceGetWithUnlockedVaultAndNoOptionsReturnsEmpty() {
        DatabaseHelper(app).storePassphrase("db-passphrase")
        val service = Robolectric.buildService(PasskeyCredentialService::class.java).create().get()
        var response: BeginGetCredentialResponse? = null
        service.onBeginGetCredentialRequest(
            BeginGetCredentialRequest(listOf()),
            CancellationSignal(),
            object : OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException> {
                override fun onResult(r: BeginGetCredentialResponse) { response = r }
                override fun onError(e: GetCredentialException) = Unit
            },
        )
        assertNotNull(response)
        assertEquals(0, response!!.credentialEntries.size)
        assertEquals(0, response!!.authenticationActions.size)
    }

    // ---- CableActivity / CableService ----

    @Test fun cableActivityWithValidFidoDataUriStillFinishesWithoutBiometric() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("fido:/12345"))
        val controller = Robolectric.buildActivity(CableActivity::class.java, intent)
        controller.create()
        // The URI passed the gate, so the activity is waiting on the biometric
        // prompt rather than finishing immediately like a rejected URI does.
        assertEquals(false, controller.get().isFinishing)
    }

    @Test fun cableActivityWithUppercaseFidoExtraPassesUriGate() {
        val intent = Intent().putExtra(CableService.EXTRA_FIDO_URI, "FIDO:/12345")
        val controller = Robolectric.buildActivity(CableActivity::class.java, intent)
        controller.create()
        assertEquals(false, controller.get().isFinishing)
    }

    @Test fun cableServiceWithMalformedUriStopsItself() {
        val controller = Robolectric.buildService(CableService::class.java).create()
        val service = controller.get()
        val intent = Intent().putExtra(CableService.EXTRA_FIDO_URI, "FIDO:/not-digits")
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 7))
        // The session coroutine runs on IO: wait for it to fail parse and stop the service.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !shadowOf(service).isStoppedBySelf) {
            Thread.sleep(10)
        }
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller.destroy()
    }

    @Test fun cableServiceBindReturnsNull() {
        val service = Robolectric.buildService(CableService::class.java).create().get()
        assertEquals(null, service.onBind(Intent()))
    }

    @Test fun cableTunnelHexEncoding() {
        assertEquals("", CableTunnel.hex(ByteArray(0)))
        assertEquals("00ff10ab", CableTunnel.hex(byteArrayOf(0, 0xFF.toByte(), 0x10, 0xAB.toByte())))
        assertEquals("fido.cable", CableTunnel.SUBPROTOCOL)
        assertEquals("X-caBLE-Routing-ID", CableTunnel.ROUTING_ID_HEADER)
    }

    // ---- AppBackupAgent ----

    @Test fun backupAgentConfig() {
        // dbConfigs decrypts the stored vault passphrase; without one it is
        // expected to fail, so store one first like an unlocked vault would.
        DatabaseHelper(app).storePassphrase("backup-pw")
        val agent = Robolectric.buildBackupAgent(
            com.vayunmathur.passwords.platform.AppBackupAgent::class.java,
        ).create().get()
        assertEquals(listOf("passwords-db" to "backup-pw"), agent.dbConfigs)
        assertTrue(agent.extraFiles.isEmpty())
        assertNotNull(agent.dbCodec)
    }

    @Test fun credentialServiceCreateCustomCredentialReturnsEmptyResponse() {
        val service = Robolectric.buildService(PasskeyCredentialService::class.java).create().get()
        var response: BeginCreateCredentialResponse? = null
        service.onBeginCreateCredentialRequest(
            BeginCreateCustomCredentialRequest(
                "com.example.Custom",
                Bundle(),
                CallingAppInfoFactory.callingAppInfo("com.example.app"),
            ),
            CancellationSignal(),
            object : OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException> {
                override fun onResult(r: BeginCreateCredentialResponse) { response = r }
                override fun onError(e: CreateCredentialException) = Unit
            },
        )
        assertNotNull(response)
        assertEquals(0, response!!.createEntries.size)
    }

    @Test fun credentialServiceClearStateCompletesWithNull() {
        val service = Robolectric.buildService(PasskeyCredentialService::class.java).create().get()
        var completed = false
        service.onClearCredentialStateRequest(
            ProviderClearCredentialStateRequest(
                CallingAppInfoFactory.callingAppInfo("com.example.app"),
            ),
            CancellationSignal(),
            object : OutcomeReceiver<Void?, ClearCredentialException> {
                override fun onResult(r: Void?) { completed = true }
                override fun onError(e: ClearCredentialException) = Unit
            },
        )
        assertTrue(completed)
    }

    @Test fun credentialServiceGetWithBlankRpOptionReturnsEmpty() {
        DatabaseHelper(app).storePassphrase("db-passphrase")
        val service = Robolectric.buildService(PasskeyCredentialService::class.java).create().get()
        var response: BeginGetCredentialResponse? = null
        service.onBeginGetCredentialRequest(
            BeginGetCredentialRequest(
                listOf(
                    BeginGetPublicKeyCredentialOption(
                        Bundle(), "req-blank", """{"challenge":"Y2hhbGxlbmdl"}""",
                    ),
                ),
            ),
            CancellationSignal(),
            object : OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException> {
                override fun onResult(r: BeginGetCredentialResponse) { response = r }
                override fun onError(e: GetCredentialException) = Unit
            },
        )
        assertNotNull(response)
        assertEquals(0, response!!.credentialEntries.size)
    }
}
