package com.vayunmathur.passwords.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.passwords.FakeAndroidKeyStore
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.PasskeyDao
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.data.PasswordDao
import com.vayunmathur.passwords.data.PasswordRepository
import com.vayunmathur.passwords.data.SyncSnapshot
import com.vayunmathur.passwords.data.SyncSnapshotDao
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The repository-backed [KdbxSyncEngine] constructor wraps the repository
 * in anonymous DAO adapters (the same shape [com.vayunmathur.passwords
 * .platform.buildGetCredentialResponse] uses). Each adapter method is a
 * delegation to the repository, so under Robolectric — where the SQLCipher
 * vault cannot open — every one surfaces [UnsatisfiedLinkError] from the
 * repository call it forwards to. Extracting the adapters from the engine
 * and invoking each method pins that none of them stubs, caches or
 * swallows the call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KdbxSyncEngineRepoAdapterTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = FakeAndroidKeyStore.install()

    private val codec = object : KdbxCodec {
        override fun read(password: String, bytes: ByteArray): List<Map<String, String>>? = emptyList()
        override fun write(password: String, entries: List<Map<String, String>>): ByteArray = byteArrayOf(1)
    }

    private fun engine() = KdbxSyncEngine(PasswordRepository.get(ctx), codec)

    @Suppress("UNCHECKED_CAST")
    private fun <T> adapterField(name: String): T {
        val field = KdbxSyncEngine::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(engine()) as T
    }

    @Test fun passwordDaoAdapterDelegatesEveryMethod() {
        val dao = adapterField<PasswordDao>("passwordDao")
        val password = Password(id = 1, name = "A", syncId = "s-1")
        // Flow getters are cold: obtaining one does not open the vault, but
        // the adapter must hand back the repository's flow.
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { dao.getAll() } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { dao.getById(1) } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { dao.getBySyncId("s-1") } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { dao.getUpdatedSince(0) } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { dao.upsertRaw(password) } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { dao.delete(password) } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { dao.deleteBySyncId("s-1") } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { dao.deleteAll() } }
    }

    @Test fun passkeyDaoAdapterDelegatesEveryMethod() {
        val dao = adapterField<PasskeyDao>("passkeyDao")
        val passkey = Passkey(id = 2, rpId = "example.com", syncId = "pk-1")
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { dao.getAll() } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { dao.getByRpId("example.com") } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { dao.getByCredentialId("cred") } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { dao.getBySyncId("pk-1") } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { dao.getUpdatedSince(0) } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { dao.upsertRaw(passkey) } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { dao.delete(passkey) } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { dao.deleteBySyncId("pk-1") } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { dao.deleteAll() } }
    }

    @Test fun snapshotDaoAdapterDelegatesEveryMethod() {
        val dao = adapterField<SyncSnapshotDao>("snapshotDao")
        val snapshot = SyncSnapshot("s-1", SyncSnapshot.KIND_PASSWORD, "h", 1, 2, 3)
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { dao.getAll() } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { dao.upsert(snapshot) } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { dao.deleteBySyncId("s-1") } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { dao.deleteByKind(SyncSnapshot.KIND_PASSWORD) } }
        assertFailsWith<UnsatisfiedLinkError> { runBlocking { dao.deleteAll() } }
    }
}
