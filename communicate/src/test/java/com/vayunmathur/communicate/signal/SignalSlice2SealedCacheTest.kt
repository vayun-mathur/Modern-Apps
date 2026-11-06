package com.vayunmathur.communicate.signal

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.SignalProfileKey
import com.vayunmathur.communicate.data.signal.SignalSealedSender
import com.vayunmathur.communicate.data.signal.SignalSenderCertificate
import com.vayunmathur.communicate.data.signal.transport.SignalTrust
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wave 5 (w6 slice 2/6): sealed-sender certificate cache + profile-key/access-key store paths in
 * [SignalSealedSender], plus the [SignalTrust] union factory. All local behavior — no network:
 * cached certificates are built with a self-signed chain (as in SignalSlice2E2ETest), and the
 * trust factory is exercised against the bundled CA assets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignalSlice2SealedCacheTest {

    private lateinit var db: SignalDatabase

    @BeforeTest
    fun open() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        SignalTrust.clearCache()
    }

    @AfterTest
    fun close() {
        SignalTrust.clearCache()
        db.close()
    }

    private fun selfSignedCertBytes(senderAci: String = "3f0e5a2c-1111-2222-3333-444455556666"): ByteArray {
        val trustKey = org.signal.libsignal.protocol.ecc.ECKeyPair.generate()
        val serverKey = org.signal.libsignal.protocol.ecc.ECKeyPair.generate()
        val serverCert = org.signal.libsignal.metadata.certificate.ServerCertificate(
            trustKey.privateKey, 1, serverKey.publicKey,
        )
        val senderKey = org.signal.libsignal.protocol.ecc.ECPublicKey(
            org.signal.libsignal.protocol.IdentityKeyPair.generate().publicKey.publicKey.serialize(),
        )
        return serverCert.issue(
            serverKey.privateKey, senderAci, java.util.Optional.empty(), 1, senderKey,
            System.currentTimeMillis() + 86_400_000L,
        ).serialized
    }

    @Test
    fun cachedCertificate_isReturnedWithoutNetwork() = runTest {
        val bytes = selfSignedCertBytes()
        val parsed = org.signal.libsignal.metadata.certificate.SenderCertificate(bytes)
        // Self-signed test certs expire a day out, inside the production 24h refresh
        // buffer — so extend the stored expiration the way a real server-issued cert
        // (a week out) would carry it; the record bytes stay the self-signed ones.
        val storedExp = System.currentTimeMillis() + 7 * 86_400_000L
        db.senderCertificateDao().upsert(
            SignalSenderCertificate(
                record = bytes, expiration = storedExp, includesE164 = false,
            ),
        )
        // No network is available in tests — a cache hit must return before any fetch.
        val got = SignalSealedSender.senderCertificate(db, "auth", null, includeE164 = false)
        assertNotNull(got)
        assertEquals(parsed.expiration, got.expiration)
    }

    @Test
    fun expiredCertificate_isNotUsed() = runTest {
        val bytes = selfSignedCertBytes()
        db.senderCertificateDao().upsert(
            SignalSenderCertificate(
                record = bytes, expiration = System.currentTimeMillis() - 1000L,
                includesE164 = false,
            ),
        )
        // Expired: the fetch fails (no network) so null means "send identified instead".
        assertNull(SignalSealedSender.senderCertificate(db, "auth", null, includeE164 = false))
    }

    @Test
    fun e164Mismatch_isNotAHit() = runTest {
        val bytes = selfSignedCertBytes()
        val parsed = org.signal.libsignal.metadata.certificate.SenderCertificate(bytes)
        db.senderCertificateDao().upsert(
            SignalSenderCertificate(
                record = bytes, expiration = parsed.expiration, includesE164 = true,
            ),
        )
        // Cached cert carries E164 but we asked without: must refetch (fails offline -> null).
        assertNull(SignalSealedSender.senderCertificate(db, "auth", null, includeE164 = false))
    }

    @Test
    fun corruptCachedRecord_fallsThroughToNull() = runTest {
        db.senderCertificateDao().upsert(
            SignalSenderCertificate(
                record = byteArrayOf(1, 2, 3),
                expiration = System.currentTimeMillis() + 86_400_000L,
                includesE164 = false,
            ),
        )
        assertNull(SignalSealedSender.senderCertificate(db, "auth", null, includeE164 = false))
    }

    @Test
    fun profileKeyRoundTrip_accessKey() = runTest {
        val aci = "7c1d9b4e-aaaa-bbbb-cccc-ddddeeeeffff"
        assertNull(SignalSealedSender.accessKeyFor(db, aci))
        val key = ByteArray(32) { (it + 1).toByte() }
        SignalSealedSender.rememberProfileKey(db, aci, key)
        val access = SignalSealedSender.accessKeyFor(db, aci)
        assertNotNull(access)
        assertEquals(16, access!!.size)
        assertTrue(access.contentEquals(SignalSealedSender.deriveAccessKey(key)))
        // Wrong-size keys are ignored, never stored.
        SignalSealedSender.rememberProfileKey(db, "other", ByteArray(5))
        assertNull(SignalSealedSender.accessKeyFor(db, "other"))
    }

    @Test
    fun trustFactory_buildsFromBundledRoots() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val factory = SignalTrust.sslSocketFactory(ctx)
        assertNotNull(factory)
        // Cached instance is returned on repeat calls.
        assertTrue(factory === SignalTrust.sslSocketFactory(ctx))
    }
}
