package com.vayunmathur.appstore.data.play

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Play with no reachable anonymous-auth dispenser: dispensing fails,
 * the auth state records the error, and every read that needs a fresh
 * session answers empty instead of throwing. Reads that can use a
 * cached session (there is none) answer immediately.
 */
@RunWith(RobolectricTestRunner::class)
class PlayRepositoryAuthFailureTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `ensureAuth fails and records an error state when the dispenser is unreachable`() = runBlocking {
        val repo = PlayRepository(context)
        val result = repo.ensureAuth()
        assertTrue(result.isFailure, "auth must fail with no network: $result")
        assertTrue(
            repo.authState.value is PlayAuthState.Error,
            "authState was ${repo.authState.value}",
        )
    }

    @Test
    fun `fresh-session reads answer empty when auth cannot be dispensed`() = runBlocking {
        val repo = PlayRepository(context)
        assertTrue(repo.details(listOf("com.example.a", "com.example.b")).isEmpty())
        assertTrue(repo.topChart().isEmpty())
        assertTrue(repo.homeClusters().isEmpty())
    }

    @Test
    fun `purchase throws when no session can be dispensed`() = runBlocking {
        val repo = PlayRepository(context)
        assertFailsWith<Exception> {
            repo.purchase("com.example.a", versionCode = 1L, offerType = 0, certHash = null)
        }
        Unit
    }
}
