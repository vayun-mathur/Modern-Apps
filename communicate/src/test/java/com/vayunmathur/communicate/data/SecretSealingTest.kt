package com.vayunmathur.communicate.data

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json as KotlinJson
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Token-sealing migration for communicate's three plain secret stores.
 * Uses a deterministic fake [SecretSealer] (AndroidKeyStore is unavailable
 * under Robolectric, mirroring everysync TokenStore's software-key seam):
 * sealed form is `"v1:" + Base64(plain)`, so tests can assert on the raw
 * persisted bytes without real crypto.
 */
@RunWith(RobolectricTestRunner::class)
class SecretSealingTest {

    private class FakeSealer(
        var failSeal: Boolean = false,
        var failUnseal: Boolean = false,
    ) : SecretSealer {
        override fun seal(plain: String): String? =
            if (failSeal) null else "v1:" + Base64.encodeToString(plain.toByteArray(), Base64.NO_WRAP)

        override fun unseal(sealed: String): String? {
            if (failUnseal || !isSealed(sealed)) return null
            return try {
                String(Base64.decode(sealed.removePrefix("v1:"), Base64.NO_WRAP))
            } catch (_: Exception) {
                null
            }
        }

        override fun isSealed(value: String): Boolean = value.startsWith("v1:")
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // ---------- Google Voice ----------

    private fun gvSession(sealer: SecretSealer) =
        GoogleVoiceSession(DataStoreUtils.getInstance(context), sealer)

    @Test
    fun googleVoice_saveSealsAllThreeSecrets() = runTest {
        val sealer = FakeSealer()
        val session = gvSession(sealer)
        session.signOut()
        session.save(cookieHeader = "SAPISID=sid9; OTHER=1", apiKey = "key1", authUser = "2")
        val store = DataStoreUtils.getInstance(context)
        for (key in listOf("gv_cookie", "gv_sapisid", "gv_api_key")) {
            val raw = store.getStringAwait(key)
            assertTrue(raw != null && sealer.isSealed(raw), "$key must be sealed, was $raw")
        }
        assertEquals("SAPISID=sid9; OTHER=1", session.cookieHeader())
        assertEquals("sid9", session.sapisid())
        assertEquals("key1", session.apiKey())
        assertTrue(session.hasUsableCredentials())
    }

    @Test
    fun googleVoice_legacyPlaintextMigratesOnFirstReadIdempotently() = runTest {
        val sealer = FakeSealer()
        val store = DataStoreUtils.getInstance(context)
        gvSession(sealer).signOut()
        store.setString("gv_cookie", "SAPISID=old; OTHER=1")
        store.setString("gv_sapisid", "old")
        store.setString("gv_api_key", "oldkey")
        val session = gvSession(sealer)
        assertEquals("SAPISID=old; OTHER=1", session.cookieHeader())
        assertEquals("old", session.sapisid())
        assertEquals("oldkey", session.apiKey())
        val afterFirst = listOf("gv_cookie", "gv_sapisid", "gv_api_key").map { store.getStringAwait(it) }
        assertTrue(afterFirst.all { it != null && sealer.isSealed(it) })
        // Second read: no rewrite, same values.
        assertEquals("SAPISID=old; OTHER=1", session.cookieHeader())
        assertEquals(afterFirst, listOf("gv_cookie", "gv_sapisid", "gv_api_key").map { store.getStringAwait(it) })
    }

    @Test
    fun googleVoice_unsealFailureIsSignedOutAndLeavesBlobIntact() = runTest {
        val store = DataStoreUtils.getInstance(context)
        gvSession(FakeSealer()).signOut()
        store.setString("gv_cookie", "v1:not-valid-base64!!!")
        store.setString("gv_api_key", "v1:AAAA")
        val session = gvSession(FakeSealer(failUnseal = true))
        assertNull(session.cookieHeader())
        assertNull(session.apiKey())
        assertFalse(session.hasUsableCredentials())
        assertEquals("v1:not-valid-base64!!!", store.getStringAwait("gv_cookie"))
        assertEquals("v1:AAAA", store.getStringAwait("gv_api_key"))
    }

    @Test
    fun googleVoice_sealFailureWritesNoPlaintext() = runTest {
        val session = gvSession(FakeSealer(failSeal = true))
        session.signOut()
        session.save(cookieHeader = "SAPISID=s; O=1", apiKey = "k", authUser = "0")
        val store = DataStoreUtils.getInstance(context)
        assertNull(store.getStringAwait("gv_cookie"))
        assertNull(store.getStringAwait("gv_api_key"))
    }

    // ---------- WhatsApp ----------

    private fun waPrefs() = context.getSharedPreferences("communicate_whatsapp_auth", Context.MODE_PRIVATE)
    private fun waRaw(): String? = waPrefs().getString("auth_data", null)

    private fun sampleWhatsApp() = WhatsAppAuthData(
        phoneNumber = "+15551234567", pushName = "Ada", wid = "15551234567@s.whatsapp.net",
        noisePrivateKey = "np", noisePublicKey = "nP", identityPrivateKey = "ip", identityPublicKey = "iP",
        registrationId = 7, signedPreKeyId = 3, signedPreKeyPublic = "sp", signedPreKeyPrivate = "sP",
        signedPreKeySignature = "sig", registered = true,
    )

    @Test
    fun whatsApp_saveSealsWholeJsonAndLoadsBack() {
        val sealer = FakeSealer()
        WhatsAppAuthData.clear(context)
        WhatsAppAuthData.save(context, sampleWhatsApp(), sealer)
        val raw = waRaw()
        assertTrue(raw != null && sealer.isSealed(raw))
        assertFalse(raw!!.contains("identityPrivateKey"))
        assertEquals(sampleWhatsApp(), WhatsAppAuthData.load(context, sealer))
    }

    @Test
    fun whatsApp_legacyPlaintextMigratesOnlyAfterSuccessfulParse() {
        val sealer = FakeSealer()
        val legacyJson = KotlinJson.encodeToString(sampleWhatsApp())
        waPrefs().edit { putString("auth_data", legacyJson) }
        assertEquals(sampleWhatsApp(), WhatsAppAuthData.load(context, sealer))
        val migrated = waRaw()
        assertTrue(migrated != null && sealer.isSealed(migrated))
        // Idempotent second load.
        assertEquals(sampleWhatsApp(), WhatsAppAuthData.load(context, sealer))
        assertEquals(migrated, waRaw())
    }

    @Test
    fun whatsApp_legacyCorruptJsonIsNotOverwritten() {
        waPrefs().edit { putString("auth_data", "{not json") }
        assertNull(WhatsAppAuthData.load(context, FakeSealer()))
        assertEquals("{not json", waRaw())
    }

    @Test
    fun whatsApp_unsealFailureIsSignedOutAndLeavesBlobIntact() {
        val sealer = FakeSealer()
        WhatsAppAuthData.save(context, sampleWhatsApp(), sealer)
        val sealed = waRaw()
        assertNull(WhatsAppAuthData.load(context, FakeSealer(failUnseal = true)))
        assertEquals(sealed, waRaw())
        // Sealed-but-unparseable plaintext also fails without touching the blob.
        waPrefs().edit { putString("auth_data", FakeSealer().seal("{nope")) }
        val badSealed = waRaw()
        assertNull(WhatsAppAuthData.load(context, FakeSealer()))
        assertEquals(badSealed, waRaw())
    }

    @Test
    fun whatsApp_sealFailureWritesNothing() {
        WhatsAppAuthData.clear(context)
        WhatsAppAuthData.save(context, sampleWhatsApp(), FakeSealer(failSeal = true))
        assertNull(waRaw())
    }

    // ---------- Signal (most careful: identity keys, never destroy blob) ----------

    private fun sigPrefs() = context.getSharedPreferences("communicate_signal_auth", Context.MODE_PRIVATE)
    private fun sigRaw(): String? = sigPrefs().getString("auth_data", null)

    private fun sampleSignal() = SignalAuthData(
        phoneNumber = "+15551234567", aci = "aci-1", identityPrivateKey = "id-priv",
        identityPublicKey = "id-pub", aciIdentityPrivateKey = "aci-priv", aciIdentityPublicKey = "aci-pub",
        registrationId = 9, password = "pw", registered = true,
    )

    @Test
    fun signal_saveSealsWholeJsonAndLoadsBack() {
        val sealer = FakeSealer()
        SignalAuthData.clear(context)
        SignalAuthData.save(context, sampleSignal(), sealer)
        val raw = sigRaw()
        assertTrue(raw != null && sealer.isSealed(raw))
        assertFalse(raw!!.contains("aci-priv"))
        assertEquals(sampleSignal(), SignalAuthData.load(context, sealer))
    }

    @Test
    fun signal_legacyPlaintextMigratesOnlyAfterSuccessfulParse() {
        val sealer = FakeSealer()
        val legacyJson = KotlinJson.encodeToString(sampleSignal())
        sigPrefs().edit { putString("auth_data", legacyJson) }
        assertEquals(sampleSignal(), SignalAuthData.load(context, sealer))
        val migrated = sigRaw()
        assertTrue(migrated != null && sealer.isSealed(migrated))
        assertEquals(sampleSignal(), SignalAuthData.load(context, sealer))
        assertEquals(migrated, sigRaw())
    }

    @Test
    fun signal_legacyCorruptJsonIsNotOverwritten() {
        sigPrefs().edit { putString("auth_data", "{not json") }
        assertNull(SignalAuthData.load(context, FakeSealer()))
        assertEquals("{not json", sigRaw())
    }

    @Test
    fun signal_unsealFailureBehavesNotRegisteredAndNeverDestroysBlob() {
        val sealer = FakeSealer()
        SignalAuthData.save(context, sampleSignal(), sealer)
        val sealed = sigRaw()
        assertNotEquals(null, sealed)
        // Every failure mode returns null and leaves the blob byte-identical,
        // so identity-key material survives for retry on the next launch.
        assertNull(SignalAuthData.load(context, FakeSealer(failUnseal = true)))
        assertEquals(sealed, sigRaw())
        sigPrefs().edit { putString("auth_data", "v1:!!!corrupt") }
        assertNull(SignalAuthData.load(context, FakeSealer()))
        assertEquals("v1:!!!corrupt", sigRaw())
        sigPrefs().edit { putString("auth_data", FakeSealer().seal("{nope")) }
        val badSealed = sigRaw()
        assertNull(SignalAuthData.load(context, FakeSealer()))
        assertEquals(badSealed, sigRaw())
        // And a later load with a working sealer still recovers the original.
        sigPrefs().edit { putString("auth_data", sealed) }
        assertEquals(sampleSignal(), SignalAuthData.load(context, sealer))
    }

    @Test
    fun signal_sealFailureWritesNothing() {
        SignalAuthData.clear(context)
        SignalAuthData.save(context, sampleSignal(), FakeSealer(failSeal = true))
        assertNull(sigRaw())
    }

    // ---------- Real Keystore sealer shape (software fallback under Robolectric) ----------

    @Test
    fun keystoreSealer_roundTripsAndDistinguishesLegacy() {
        val sealer = KeystoreSecretSealer.shared
        val sealed = sealer.seal("secret-value")
        assertTrue(sealed != null && sealer.isSealed(sealed))
        assertFalse(sealed!!.contains("secret-value"))
        assertEquals("secret-value", sealer.unseal(sealed))
        assertFalse(sealer.isSealed("secret-value"))
        assertNull(sealer.unseal("secret-value"))
        assertNull(sealer.unseal("v1:!!!"))
    }
}
