package com.vayunmathur.games.chess.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.DataStoreUtils
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.first
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

@RunWith(RobolectricTestRunner::class)
class LichessTokenStoreTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @BeforeTest fun installFakeSealer() {
        LichessTokenStore.sealerForTest = FakeTokenSealer()
    }

    @AfterTest fun removeFakeSealer() {
        LichessTokenStore.sealerForTest = null
    }

    @Test fun `token lifecycle save read headers and clear`() = runBlocking {
        val store = LichessTokenStore(ctx)
        store.clear()
        assertNull(store.tokenNow())
        assertNull(store.usernameNow())
        assertNull(store.userIdNow())
        assertTrue(store.authHeaders().isEmpty())
        store.saveToken("secret-token")
        store.saveUsername("ada")
        store.saveUserId("ada-id")
        assertEquals("secret-token", store.tokenNow())
        assertEquals("ada", store.usernameNow())
        assertEquals("ada-id", store.userIdNow())
        assertEquals(mapOf("Authorization" to "Bearer secret-token"), store.authHeaders())
        assertEquals("secret-token", store.token.first { it.isNotEmpty() })
        assertEquals("ada", store.username.first { it.isNotEmpty() })
        // Only sealed form reaches the DataStore file (the fake sealer's marker
        // prefix; the AES sealer's no-plaintext property is covered below).
        val raw = DataStoreUtils.getInstance(ctx).getStringAwait("lichess_access_token")
        assertTrue(raw!!.startsWith("v1:"), "stored token must be sealed, was: $raw")
        store.clear()
        assertNull(store.tokenNow())
        assertNull(store.usernameNow())
        assertTrue(store.authHeaders().isEmpty())
    }

    @Test fun `legacy plaintext token is used once and rewritten sealed`() = runBlocking {
        val ds = DataStoreUtils.getInstance(ctx)
        ds.setString("lichess_access_token", "legacy-token")
        val store = LichessTokenStore(ctx)
        assertEquals("legacy-token", store.tokenNow())
        val raw = ds.getStringAwait("lichess_access_token")
        assertTrue(raw!!.startsWith("v1:"), "legacy token must be rewritten sealed, was: $raw")
        assertEquals("legacy-token", store.tokenNow())
        store.clear()
    }

    @Test fun `unsealable sealed token reads as signed out`() = runBlocking {
        DataStoreUtils.getInstance(ctx).setString("lichess_access_token", "v1:!!!not-a-token!!!")
        val store = LichessTokenStore(ctx)
        assertNull(store.tokenNow())
        assertTrue(store.authHeaders().isEmpty())
        assertEquals("", store.token.first())
        store.clear()
    }

    @Test fun `keystore sealer round trips with a software key and fails closed`() {
        val sealer = KeystoreTokenSealer("test_alias")
        sealer.secretKeyForTest = SecretKeySpec(ByteArray(32) { (it * 7 + 1).toByte() }, "AES")
        val sealed = sealer.seal("hello-token")!!
        assertTrue(sealer.isSealed(sealed))
        assertFalse(sealed.contains("hello-token"))
        assertEquals("hello-token", sealer.unseal(sealed))
        // Legacy plaintext passes through; tampered sealed values fail closed.
        assertEquals("plain-legacy", sealer.unseal("plain-legacy"))
        assertFalse(sealer.isSealed("plain-legacy"))
        assertNull(sealer.unseal(sealed.dropLast(4) + "AAAA"))
    }
}
