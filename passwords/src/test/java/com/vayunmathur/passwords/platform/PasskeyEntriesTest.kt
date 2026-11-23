package com.vayunmathur.passwords.platform

import android.content.Context
import android.os.Bundle
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.PasskeyDao
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.data.PasswordDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PasskeyEntriesTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private val passkey = Passkey(id = 1, rpId = "example.com", userName = "alice", credentialId = "cred-1", syncId = "pk-1")
    private val password = Password(id = 2, name = "Mail", username = "alice", password = "pw", syncId = "pw-1")

    private inner class FakePasskeyDao : PasskeyDao() {
        override fun getAllFlow(): Flow<List<Passkey>> = flowOf(listOf(passkey))
        override suspend fun getAll(): List<Passkey> = listOf(passkey)
        override suspend fun getByRpId(rpId: String): List<Passkey> =
            if (rpId == "example.com") listOf(passkey) else emptyList()
        override suspend fun getByCredentialId(credentialId: String): Passkey? = null
        override suspend fun getBySyncId(syncId: String): Passkey? = null
        override suspend fun getUpdatedSince(since: Long): List<Passkey> = emptyList()
        override suspend fun upsertRaw(passkey: Passkey): Long = passkey.id
        override suspend fun delete(passkey: Passkey): Int = 0
        override suspend fun deleteBySyncId(syncId: String): Int = 0
        override suspend fun deleteAll() = Unit
    }

    private inner class FakePasswordDao : PasswordDao() {
        override fun getAllFlow(): Flow<List<Password>> = flowOf(listOf(password))
        override suspend fun getAll(): List<Password> = listOf(password)
        override fun getByIdFlow(id: Long): Flow<Password?> = flowOf(password)
        override suspend fun getById(id: Long): Password? = password
        override suspend fun getBySyncId(syncId: String): Password? = null
        override suspend fun getUpdatedSince(since: Long): List<Password> = emptyList()
        override suspend fun upsertRaw(value: Password): Long = value.id
        override suspend fun delete(value: Password): Int = 0
        override suspend fun deleteBySyncId(syncId: String): Int = 0
        override suspend fun deleteAll() = Unit
    }

    @Test fun passwordOptionYieldsOneEntryPerPassword() = runBlocking {
        val response = buildGetCredentialResponse(
            ctx,
            listOf(BeginGetPasswordOption(emptySet(), Bundle(), "req-1")),
            FakePasskeyDao(),
            FakePasswordDao(),
        )
        assertEquals(1, response.credentialEntries.size)
    }

    @Test fun publicKeyOptionYieldsMatchingPasskeysOnly() = runBlocking {
        val matching = buildGetCredentialResponse(
            ctx,
            listOf(BeginGetPublicKeyCredentialOption(Bundle(), "req-2", """{"rpId":"example.com","challenge":"Y2hhbGxlbmdl"}""")),
            FakePasskeyDao(),
            FakePasswordDao(),
        )
        assertEquals(1, matching.credentialEntries.size)

        val noRp = buildGetCredentialResponse(
            ctx,
            listOf(BeginGetPublicKeyCredentialOption(Bundle(), "req-3", """{"rpId":"","challenge":"Y2hhbGxlbmdl"}""")),
            FakePasskeyDao(),
            FakePasswordDao(),
        )
        assertEquals(0, noRp.credentialEntries.size)

        val otherRp = buildGetCredentialResponse(
            ctx,
            listOf(BeginGetPublicKeyCredentialOption(Bundle(), "req-4", """{"rpId":"other.com","challenge":"Y2hhbGxlbmdl"}""")),
            FakePasskeyDao(),
            FakePasswordDao(),
        )
        assertEquals(0, otherRp.credentialEntries.size)
    }

    @Test fun publicKeyOptionHonoursAllowCredentials() = runBlocking {
        val matching = buildGetCredentialResponse(
            ctx,
            listOf(BeginGetPublicKeyCredentialOption(Bundle(), "req-allow", """{"rpId":"example.com","challenge":"Y2hhbGxlbmdl","allowCredentials":[{"type":"public-key","id":"cred-1"}]}""")),
            FakePasskeyDao(),
            FakePasswordDao(),
        )
        assertEquals(1, matching.credentialEntries.size)

        val nonMatching = buildGetCredentialResponse(
            ctx,
            listOf(BeginGetPublicKeyCredentialOption(Bundle(), "req-allow-other", """{"rpId":"example.com","challenge":"Y2hhbGxlbmdl","allowCredentials":[{"type":"public-key","id":"b3RoZXItY3JlZA"}]}""")),
            FakePasskeyDao(),
            FakePasswordDao(),
        )
        assertEquals(0, nonMatching.credentialEntries.size)
    }

    @Test fun noOptionsYieldEmptyResponse() = runBlocking {
        val response = buildGetCredentialResponse(ctx, emptyList(), FakePasskeyDao(), FakePasswordDao())
        assertEquals(0, response.credentialEntries.size)
    }

    @Test fun malformedRequestJsonYieldsEmptyResponseNotException() = runBlocking {
        // Non-JSON cannot even construct the framework option (its init
        // validates), so the malformed shapes that reach enumeration are
        // valid JSON missing the fields we need, or naming no RP.
        for (requestJson in listOf("""{}""", """{"challenge":"Y2g"}""", """{"rpId":123}""")) {
            val response = buildGetCredentialResponse(
                ctx,
                listOf(BeginGetPublicKeyCredentialOption(Bundle(), "req-bad", requestJson)),
                FakePasskeyDao(),
                FakePasswordDao(),
            )
            assertEquals(0, response.credentialEntries.size)
        }
    }

    @Test fun blankUsernamePasskeyStillYieldsEntry() = runBlocking {
        // Imported rows can have no username; the entry builder must not
        // throw the whole enumeration over one such row.
        val blankNamed = Passkey(id = 3, rpId = "example.com", userName = "", credentialId = "cred-3", syncId = "pk-3")
        val dao = object : PasskeyDao() {
            override fun getAllFlow(): Flow<List<Passkey>> = flowOf(listOf(blankNamed))
            override suspend fun getAll(): List<Passkey> = listOf(blankNamed)
            override suspend fun getByRpId(rpId: String): List<Passkey> = listOf(blankNamed)
            override suspend fun getByCredentialId(credentialId: String): Passkey? = null
            override suspend fun getBySyncId(syncId: String): Passkey? = null
            override suspend fun getUpdatedSince(since: Long): List<Passkey> = emptyList()
            override suspend fun upsertRaw(passkey: Passkey): Long = passkey.id
            override suspend fun delete(passkey: Passkey): Int = 0
            override suspend fun deleteBySyncId(syncId: String): Int = 0
            override suspend fun deleteAll() = Unit
        }
        val response = buildGetCredentialResponse(
            ctx,
            listOf(BeginGetPublicKeyCredentialOption(Bundle(), "req-blank-name", """{"rpId":"example.com","challenge":"Y2g"}""")),
            dao,
            FakePasswordDao(),
        )
        assertEquals(1, response.credentialEntries.size)
    }

    @Test fun throwingDaoYieldsEmptyResponseNotException() = runBlocking {
        // A vault read failure during enumeration must complete the provider
        // callback with an (empty) response, never throw across the binder.
        val dao = object : PasskeyDao() {
            override fun getAllFlow(): Flow<List<Passkey>> = flowOf(emptyList())
            override suspend fun getAll(): List<Passkey> = emptyList()
            override suspend fun getByRpId(rpId: String): List<Passkey> =
                throw IllegalStateException("vault locked mid-enumeration")
            override suspend fun getByCredentialId(credentialId: String): Passkey? = null
            override suspend fun getBySyncId(syncId: String): Passkey? = null
            override suspend fun getUpdatedSince(since: Long): List<Passkey> = emptyList()
            override suspend fun upsertRaw(passkey: Passkey): Long = passkey.id
            override suspend fun delete(passkey: Passkey): Int = 0
            override suspend fun deleteBySyncId(syncId: String): Int = 0
            override suspend fun deleteAll() = Unit
        }
        val response = buildGetCredentialResponse(
            ctx,
            listOf(BeginGetPublicKeyCredentialOption(Bundle(), "req-throw", """{"rpId":"example.com","challenge":"Y2g"}""")),
            dao,
            FakePasswordDao(),
        )
        assertEquals(0, response.credentialEntries.size)
    }
}
