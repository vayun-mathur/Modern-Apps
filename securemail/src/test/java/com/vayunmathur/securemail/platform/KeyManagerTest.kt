package com.vayunmathur.securemail.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.securemail.platform.crypto.KeyManager
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [KeyManager] plain-preference custody (relay token + own address) and the
 * empty-Keystore branches. Sealed-secret round trips need a real
 * AndroidKeyStore, which the host JVM does not provide — noted as skipped.
 */
@RunWith(RobolectricTestRunner::class)
class KeyManagerTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private fun km() = KeyManager(ctx)

    @Test fun `fresh manager has no identity passphrase token or address`() {
        val manager = km()
        manager.clear()
        assertNull(manager.getKeyPassphrase())
        assertNull(manager.getRelayToken())
        assertNull(manager.getOwnAddress())
    }

    @Test fun `relay token and own address round trip and clear removes them`() {
        val manager = km()
        manager.storeRelayToken("token-123")
        manager.storeOwnAddress("me@maemail.org")
        assertEquals("token-123", manager.getRelayToken())
        assertEquals("me@maemail.org", manager.getOwnAddress())
        manager.storeRelayToken("token-456")
        assertEquals("token-456", manager.getRelayToken())
        manager.clear()
        assertNull(manager.getRelayToken())
        assertNull(manager.getOwnAddress())
        assertNull(manager.getKeyPassphrase())
    }
}
