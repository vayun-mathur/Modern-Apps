package com.vayunmathur.passwords.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.passwords.FakeAndroidKeyStore
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.data.PasswordRepository
import com.vayunmathur.passwords.sync.EntryMapper
import com.vayunmathur.passwords.sync.NativeKdbxCodec
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * [KdbxBackupFormat] paths that reach the vault. The KDBX codec is native
 * and runs for real in these tests (it is the same Rust library the unit
 * tests for the sync codec use), so an import file carrying entries is
 * genuinely decoded and mapped through [EntryMapper] before the first
 * repository upsert — which is where the SQLCipher boundary (unavailable
 * under Robolectric) surfaces. Export likewise reads the vault first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KdbxBackupFormatVaultPathTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = FakeAndroidKeyStore.install()

    private fun format() = KdbxBackupFormat(PasswordRepository.get(app))

    @Test fun exportWithPasswordReadsVaultFirst() {
        assertFailsWith<UnsatisfiedLinkError> {
            runBlocking { format().export(app, "pw", ByteArrayOutputStream()) }
        }
    }

    @Test fun importWithPasswordEntryReachesRepositoryUpsert() {
        val entry = EntryMapper.toFields(
            Password(name = "Mail", username = "alice", password = "secret", syncId = "pw-1"),
        )
        val bytes = NativeKdbxCodec.write("pw", listOf(entry))!!
        assertFailsWith<UnsatisfiedLinkError> {
            runBlocking { format().import(app, "pw", ByteArrayInputStream(bytes)) }
        }
    }

    @Test fun importWithPasskeyEntryReachesRepositoryUpsert() {
        val entry = EntryMapper.toFields(
            Passkey(
                rpId = "example.com", rpName = "Example", credentialId = "cred-1",
                userName = "alice", privateKeyBytes = byteArrayOf(1, 2, 3), syncId = "pk-1",
            ),
        )
        val bytes = NativeKdbxCodec.write("pw", listOf(entry))!!
        assertFailsWith<UnsatisfiedLinkError> {
            runBlocking { format().import(app, "pw", ByteArrayInputStream(bytes)) }
        }
    }
}
