package com.vayunmathur.passwords.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.passwords.FakeAndroidKeyStore
import com.vayunmathur.passwords.data.PasswordRepository
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KdbxBackupFormatExtraTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = FakeAndroidKeyStore.install()

    private fun format() = KdbxBackupFormat(PasswordRepository.get(app))

    @Test fun formatMetadata() {
        val format = format()
        assertEquals("application/octet-stream", format.mimeType)
        assertEquals("passwords.kdbx", format.defaultFileName)
        assertEquals(true, format.needsPassword)
    }

    @Test fun exportAndImportRequireAPassword() {
        val format = format()
        assertFailsWith<IllegalArgumentException> {
            runBlocking { format.export(app, null, ByteArrayOutputStream()) }
        }
        assertFailsWith<IllegalArgumentException> {
            runBlocking { format.import(app, null, ByteArrayInputStream(ByteArray(0))) }
        }
    }

    @Test fun importOfEmptyVaultSucceedsAsNoOp() {
        // A valid KDBX file holding zero entries imports successfully and
        // inserts nothing (the repository is never touched for zero entries).
        val bytes = com.vayunmathur.passwords.sync.NativeKdbxCodec.write("pw", emptyList())!!
        runBlocking { format().import(app, "pw", ByteArrayInputStream(bytes)) }
    }

    @Test fun importRejectsGarbageAndEmptyVaultBytes() {
        val format = format()
        val garbage = assertFailsWith<IllegalStateException> {
            runBlocking { format.import(app, "pw", ByteArrayInputStream(byteArrayOf(1, 2, 3))) }
        }
        assertTrue(garbage.message!!.contains("Failed to open KDBX"))
        assertFailsWith<IllegalStateException> {
            runBlocking { format.import(app, "pw", ByteArrayInputStream(ByteArray(0))) }
        }
    }
}
