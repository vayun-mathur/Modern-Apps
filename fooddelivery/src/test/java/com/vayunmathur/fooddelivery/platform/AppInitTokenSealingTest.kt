package com.vayunmathur.fooddelivery.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.fooddelivery.data.AuthToken
import com.vayunmathur.fooddelivery.network.BitesAuth
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

/**
 * Sealing contract for the persisted auth token, exercised through [AppInit]'s
 * restore/persist callbacks (the warm-up path cold-process services rely on).
 * AppInit.start is once-per-process, so the restore assertions share one test.
 */
@RunWith(RobolectricTestRunner::class)
class AppInitTokenSealingTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = ctx.getSharedPreferences("fooddelivery_prefs", Context.MODE_PRIVATE)

    private val legacyJson =
        """{"access_token":"legacy-access","refresh_token":"legacy-refresh",""" +
            """"token_type":"Bearer","expires_in":3600,"expires_at_ms":9999999999999}"""

    @BeforeTest fun installFakeSealer() {
        AppInit.sealerForTest = FakeTokenSealer()
        BitesAuth.clearToken()
        prefs.edit().clear().commit()
    }

    @AfterTest fun removeFakeSealer() {
        BitesAuth.clearToken()
        prefs.edit().clear().commit()
        AppInit.sealerForTest = null
    }

    @Test fun `legacy token restores and migrates sealed, updates persist sealed`() = runBlocking {
        prefs.edit().putString("token_json", legacyJson).commit()
        AppInit.start(ctx)
        AppInit.awaitReady()
        // Legacy plaintext was restored (signed in) and rewritten sealed in place.
        assertTrue(BitesAuth.isLoggedIn())
        val migrated = prefs.getString("token_json", null)!!
        assertTrue(migrated.startsWith("v1:"), "legacy token must be rewritten sealed, was: $migrated")
        // Persist callback seals: a fresh sign-in never writes plaintext.
        BitesAuth.setToken(AuthToken("fresh-access", "fresh-refresh", "Bearer", 3600))
        val persisted = prefs.getString("token_json", null)!!
        assertTrue(persisted.startsWith("v1:"), "persisted token must be sealed, was: $persisted")
        // Sign-out removal still clears the key.
        BitesAuth.clearToken()
        assertNull(prefs.getString("token_json", null))
        assertFalse(BitesAuth.isLoggedIn())
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
