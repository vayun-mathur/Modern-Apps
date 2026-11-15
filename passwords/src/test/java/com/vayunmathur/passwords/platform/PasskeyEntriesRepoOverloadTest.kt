package com.vayunmathur.passwords.platform

import android.content.Context
import android.os.Bundle
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.passwords.FakeAndroidKeyStore
import com.vayunmathur.passwords.data.PasswordRepository
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The repository-backed overload of [buildGetCredentialResponse] builds its
 * DAO adapters up front; requests that need no vault rows (no options, or a
 * public-key request without an rpId) complete without opening the vault.
 * Requests that do need rows open SQLCipher, which is on-device only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PasskeyEntriesRepoOverloadTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = FakeAndroidKeyStore.install()

    @Test fun noOptionsYieldEmptyResponseThroughRepository() = runBlocking {
        val response = buildGetCredentialResponse(ctx, emptyList(), PasswordRepository.get(ctx))
        assertEquals(0, response.credentialEntries.size)
    }

    @Test fun blankRpIdIsSkippedThroughRepository() = runBlocking {
        val option = BeginGetPublicKeyCredentialOption(
            Bundle(), "req-blank", """{"challenge":"Y2hhbGxlbmdl"}""",
        )
        val response = buildGetCredentialResponse(ctx, listOf(option), PasswordRepository.get(ctx))
        assertEquals(0, response.credentialEntries.size)
    }
}
