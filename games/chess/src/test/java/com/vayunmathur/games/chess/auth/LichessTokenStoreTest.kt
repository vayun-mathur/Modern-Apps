package com.vayunmathur.games.chess.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LichessTokenStoreTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

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
        store.clear()
        assertNull(store.tokenNow())
        assertNull(store.usernameNow())
        assertTrue(store.authHeaders().isEmpty())
    }
}
