package com.vayunmathur.email.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.email.data.EmailAccount
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Error paths of [resolveAuth]: missing credentials must fail loudly, never send empty auth. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResolveAuthTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `password account without encrypted password fails`() {
        val account = EmailAccount(email = "me@x.com", authType = "password")
        val error = assertFailsWith<IllegalStateException> {
            runBlocking { account.resolveAuth(context) }
        }
        assertTrue(error.message!!.contains("missing passwordEncrypted"))
    }

    @Test
    fun `password account with cipher but no iv fails`() {
        val account = EmailAccount(
            email = "me@x.com", authType = "password",
            passwordEncrypted = byteArrayOf(1, 2, 3), passwordIv = null,
        )
        val error = assertFailsWith<IllegalStateException> {
            runBlocking { account.resolveAuth(context) }
        }
        assertTrue(error.message!!.contains("missing passwordIv"))
    }

    @Test
    fun `legacy oauth account without password fails`() {
        val account = EmailAccount(email = "me@x.com", authType = "oauth2", provider = "gmail")
        val error = assertFailsWith<IllegalStateException> {
            runBlocking { account.resolveAuth(context) }
        }
        assertTrue(error.message!!.contains("re-added"))
    }
}
