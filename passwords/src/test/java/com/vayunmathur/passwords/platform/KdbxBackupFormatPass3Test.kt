package com.vayunmathur.passwords.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.passwords.FakeAndroidKeyStore
import com.vayunmathur.passwords.data.PasswordRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Pass-3: KdbxBackupFormat password guards and corrupt-file handling (no vault access needed). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KdbxBackupFormatPass3Test {

    @Before fun setUp() = FakeAndroidKeyStore.install()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun format() = KdbxBackupFormat(PasswordRepository.get(app))

    @Test
    fun propertiesMatchKdbxContract() {
        val format = format()
        assertEquals("passwords.kdbx", format.defaultFileName)
        assertEquals("application/octet-stream", format.mimeType)
        assertTrue(format.needsPassword)
    }

    @Test
    fun exportRequiresPassword() = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            format().export(app, null, ByteArrayOutputStream())
        }
        Unit
    }

    @Test
    fun importRequiresPassword() = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            format().import(app, null, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        }
        Unit
    }

    @Test
    fun importRejectsCorruptKdbx() = runBlocking {
        // Native import of garbage bytes must fail cleanly with the format's error,
        // before any repository write happens.
        val error = assertFailsWith<IllegalStateException> {
            format().import(app, "hunter2", ByteArrayInputStream("not a kdbx file".toByteArray()))
        }
        assertTrue(error.message!!.contains("Failed to open KDBX"))
        Unit
    }
}
