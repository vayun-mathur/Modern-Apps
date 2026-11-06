package com.vayunmathur.communicate.whatsapp

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.registration.RegistrationHttpClient
import com.vayunmathur.communicate.data.whatsapp.registration.WhatsAppDeviceFingerprint
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1 (w5): RegistrationHttpClient offline paths (no network).
 * Robolectric (SharedPreferences + Android Log + TelephonyManager shadows).
 *
 * - Offline failure: with no persisted scaffold, register()/submitTwoFactor()
 *   return `no_keys` without touching the network.
 * - Result `ok` flags: pure status mapping on every result type.
 * - Device fingerprint: getOrCreate persists lengths-correct material.
 */
@RunWith(AndroidJUnit4::class)
class W5Slice1RegOfflineTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @AfterTest
    fun clearState() {
        WhatsAppAuthData.clear(context)
        WhatsAppDeviceFingerprint.clear(context)
        // Robolectric shadows may throw for getSystemService overrides; ignore.
        runCatching {
            org.robolectric.RuntimeEnvironment.getApplication()
                .getSharedPreferences("communicate_whatsapp_auth", android.content.Context.MODE_PRIVATE)
                .edit().clear().apply()
        }
    }

    @Test
    fun register_withoutScaffold_returnsNoKeys() = runTest {
        WhatsAppAuthData.clear(context)
        val client = RegistrationHttpClient(context)
        val r = client.register("1", "2024561111", "123-456")
        assertEquals("error", r.status)
        assertEquals("no_keys", r.reason)
        assertNull(r.auth)
        assertFalse(r.ok)
    }

    @Test
    fun submitTwoFactor_withoutScaffold_returnsNoKeys() = runTest {
        WhatsAppAuthData.clear(context)
        val client = RegistrationHttpClient(context)
        val r = client.submitTwoFactor("1", "2024561111", "000000")
        assertEquals("no_keys", r.reason)
        assertFalse(r.ok)
    }

    @Test
    fun resultOkFlags_mapStatuses() {
        assertTrue(RegistrationHttpClient.CodeResult("sent", null, null, null, null, null, "").ok)
        assertTrue(RegistrationHttpClient.CodeResult("ok", null, null, null, null, null, "").ok)
        assertFalse(RegistrationHttpClient.CodeResult("fail", null, null, null, "x", null, "").ok)

        assertTrue(RegistrationHttpClient.RegisterResult("ok", null, null, null, null, null, null, "").ok)
        assertFalse(RegistrationHttpClient.RegisterResult("fail", null, null, null, "x", null, null, "").ok)

        assertTrue(RegistrationHttpClient.ConsentResult("ok", null, null, null, "").ok)
        assertTrue(RegistrationHttpClient.AutoconfResult("sent", null, null, "").ok)
        assertTrue(RegistrationHttpClient.AutoconfVerifierResult("ok", null, "").ok)
        assertTrue(RegistrationHttpClient.CaptchaVerifyResult("verified", null, null, null, "").ok)
        assertFalse(RegistrationHttpClient.CaptchaVerifyResult("pending", null, null, null, "").ok)
        assertTrue(RegistrationHttpClient.ChallengeResult("ok", null, "").ok)
        assertTrue(RegistrationHttpClient.ClientLogResult("ok", "").ok)
        assertFalse(RegistrationHttpClient.ClientLogResult("fail", "").ok)
    }

    @Test
    fun fingerprint_getOrCreate_stableAndSized() {
        WhatsAppDeviceFingerprint.clear(context)
        val a = WhatsAppDeviceFingerprint.getOrCreate(context)
        val b = WhatsAppDeviceFingerprint.getOrCreate(context)
        assertEquals(a.fdid, b.fdid)
        assertEquals(a.expid, b.expid)
        assertTrue(a.recoveryToken.contentEquals(b.recoveryToken))
        assertEquals(16, a.recoveryToken.size)
        assertEquals(20, a.backupToken.size)
        assertEquals(32, a.attestationKey.size)
        // valid UUID strings
        java.util.UUID.fromString(a.fdid)
        java.util.UUID.fromString(a.expid)
    }

    @Test
    fun authData_saveLoadClear_roundTrips() {
        val auth = WhatsAppAuthData(
            phoneNumber = "+15550001", pushName = "T", wid = "1@s.whatsapp.net",
            noisePrivateKey = "a", noisePublicKey = "b", identityPrivateKey = "c",
            identityPublicKey = "d", registrationId = 5, signedPreKeyId = 1,
            signedPreKeyPublic = "e", signedPreKeyPrivate = "f", signedPreKeySignature = "g",
            registered = true,
        )
        WhatsAppAuthData.save(context, auth)
        assertEquals(auth, WhatsAppAuthData.load(context))
        WhatsAppAuthData.clear(context)
        assertNull(WhatsAppAuthData.load(context))
    }
}
