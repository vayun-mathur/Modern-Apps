package com.vayunmathur.passwords.platform.cable

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.passwords.data.Passkey
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebAuthnAndBluetoothExtraTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun bluetoothHelpersWithShadowAdapter() {
        // Robolectric provides a shadow Bluetooth adapter.
        assertTrue(CableBluetooth.adapter(ctx) != null)
        assertTrue(CableBluetooth.isSupported(ctx))
    }

    @Test fun advertiserStartsWithShadowBluetooth() {
        val advertiser = CableAdvertiser(ctx)
        assertTrue(advertiser.isAvailable)
        // Robolectric's shadow LE advertiser accepts the advert without
        // invoking the callback synchronously; starting must not throw.
        advertiser.start(byteArrayOf(1, 2, 3)) { }
        advertiser.stop()
        // uuid16 masks to 16 bits and expands into the Bluetooth base UUID.
        assertEquals(
            "0000fde2-0000-1000-8000-00805f9b34fb",
            CableAdvertiser.uuid16(0x1FDE2).toString(),
        )
        assertEquals(
            "00000000-0000-1000-8000-00805f9b34fb",
            CableAdvertiser.uuid16(0).toString(),
        )
    }

    @Test fun createCredentialWithPresenceAndVerificationOff() = runBlocking {
        val store = FakePasskeyStore()
        val created = WebAuthnAuthenticator.createCredential(
            rpId = "example.com",
            rpName = "Example",
            userId = byteArrayOf(1, 2),
            userName = "alice",
            userDisplayName = "Alice",
            aaguid = ByteArray(16) { 0x0A },
            store = store,
            userPresent = false,
            userVerified = false,
        )
        // AT plus the default backup flags, with UP/UV cleared (offset 32).
        assertEquals(0x40 or 0x08 or 0x10, created.authenticatorData[32].toInt() and 0xFF)
        assertEquals(listOf(0x0A.toByte()), created.authenticatorData.copyOfRange(37, 38).toList())
        assertEquals(1, store.store.size)

        // Signing with userPresent = false clears UP but still bumps the counter.
        val assertion = WebAuthnAuthenticator.signAssertion(
            passkey = store.store.single(),
            clientDataHash = ByteArray(32) { 0x11 },
            store = store,
            userPresent = false,
            userVerified = true,
        )
        assertEquals(0x04 or 0x08 or 0x10, assertion.authenticatorData[32].toInt() and 0xFF)
        assertEquals(1, assertion.newSignCount)
        assertEquals(1, store.store.single().signCount)
    }

    @Test fun assertionForPasskeyWithNonBase64CredentialIdStillSigns() = runBlocking {
        // Through the CTAP processor: a stored credential id that is not valid
        // base64url is echoed back as an empty credential descriptor, not a crash.
        val kp = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val pk = Passkey(
            id = 1, rpId = "example.com", credentialId = "!!!not-b64!!!",
            userId = "dXNlcg", userName = "alice", privateKeyBytes = kp.private.encoded,
            signCount = 0, syncId = "pk-bad",
        )
        val store = FakePasskeyStore(listOf(pk))
        val request = com.vayunmathur.passwords.domain.Cbor.encode(
            linkedMapOf<Any, Any>(1L to "example.com", 2L to ByteArray(32) { 0x22 }),
        )
        val response = CtapProcessor(store, userVerified = true)
            .process(byteArrayOf(Ctap.CMD_GET_ASSERTION.toByte()) + request)
        assertEquals(Ctap.OK.toByte(), response[0])
        val map = CborReader.decode(response.copyOfRange(1, response.size)) as Map<*, *>
        val cred = map[1L] as Map<*, *>
        assertEquals(0, (cred["id"] as ByteArray).size)
        assertEquals(1, store.store.single().signCount)
        assertTrue(response.size > 40)
    }
}
