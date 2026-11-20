package com.vayunmathur.taxi.data.lyft

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.taxi.network.lyft.LyftToken
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sealing contract for Lyft tokens: sealed at persist, unseal-or-migrate at read. */
@RunWith(RobolectricTestRunner::class)
class LyftTokenStoreSealingTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Writes values verbatim, so tests can plant legacy plaintext / foreign sealed values. */
    private object PassThroughSealer : TokenSealer {
        override fun seal(plain: String): String = plain
        override fun unseal(stored: String): String = stored
        override fun isSealed(stored: String): Boolean = false
    }

    /** Unseals only [FakeTokenSealer] output; plain values fail — proves a rewrite happened. */
    private object StrictSealer : TokenSealer {
        override fun seal(plain: String): String = "v1:fake:$plain"
        override fun unseal(stored: String): String? =
            if (stored.startsWith("v1:fake:")) stored.removePrefix("v1:fake:") else null
        override fun isSealed(stored: String): Boolean = stored.startsWith("v1:")
    }

    @BeforeTest fun installFakeSealer() {
        LyftTokenStore.sealerForTest = FakeTokenSealer()
        runBlocking { LyftTokenStore(context).clear() }
    }

    @AfterTest fun removeFakeSealer() {
        runBlocking { LyftTokenStore(context).clear() }
        LyftTokenStore.sealerForTest = null
    }

    @Test fun `tokens round trip and sign-in state follows`() = runBlocking {
        val store = LyftTokenStore(context)
        assertFalse(store.isSignedIn())
        store.save(LyftToken(accessToken = "access-tok", refreshToken = "refresh-tok", expiresIn = 3600, userId = "42"))
        assertEquals("access-tok", store.accessToken())
        assertEquals("refresh-tok", store.refreshToken())
        assertEquals("42", store.userId())
        assertTrue(store.isSignedIn())
        store.clear()
        assertNull(store.accessToken())
        assertFalse(store.isSignedIn())
    }

    @Test fun `legacy plaintext tokens migrate to sealed on read`() = runBlocking {
        LyftTokenStore.sealerForTest = PassThroughSealer
        LyftTokenStore(context).save(
            LyftToken(accessToken = "legacy-access", refreshToken = "legacy-refresh", expiresIn = 3600, userId = "7"),
        )
        LyftTokenStore.sealerForTest = FakeTokenSealer()
        val store = LyftTokenStore(context)
        assertEquals("legacy-access", store.accessToken())
        assertEquals("legacy-refresh", store.refreshToken())
        // Both reads rewrote sealed: a sealer rejecting plaintext now still reads them.
        LyftTokenStore.sealerForTest = StrictSealer
        assertEquals("legacy-access", store.accessToken())
        assertEquals("legacy-refresh", store.refreshToken())
    }

    @Test fun `unsealable sealed tokens read as signed out`() = runBlocking {
        LyftTokenStore.sealerForTest = PassThroughSealer
        LyftTokenStore(context).save(
            LyftToken(accessToken = "v1:foreign-sealed", refreshToken = "v1:foreign-refresh", expiresIn = 3600, userId = "7"),
        )
        LyftTokenStore.sealerForTest = FakeTokenSealer()
        val store = LyftTokenStore(context)
        assertNull(store.accessToken())
        assertNull(store.refreshToken())
        assertFalse(store.isSignedIn())
    }

    @Test fun `keystore sealer round trips with a software key and fails closed`() {
        val sealer = KeystoreTokenSealer("test_alias")
        sealer.secretKeyForTest = SecretKeySpec(ByteArray(32) { (it * 7 + 1).toByte() }, "AES")
        val sealed = sealer.seal("hello-token")!!
        assertTrue(sealer.isSealed(sealed))
        assertFalse(sealed.contains("hello-token"))
        assertEquals("hello-token", sealer.unseal(sealed))
        assertEquals("plain-legacy", sealer.unseal("plain-legacy"))
        assertFalse(sealer.isSealed("plain-legacy"))
        assertNull(sealer.unseal(sealed.dropLast(4) + "AAAA"))
    }
}
