package com.vayunmathur.passwords.platform

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.passwords.FakeAndroidKeyStore
import com.vayunmathur.passwords.data.PasswordRepository
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PasswordsViewModelImportTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = FakeAndroidKeyStore.install()

    private fun vm() = PasswordsViewModel(app, PasswordRepository.get(app))

    private fun serve(uri: Uri, text: String) {
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(text.toByteArray()))
    }

    private fun awaitImportDone(viewModel: PasswordsViewModel) {
        // The import runs on viewModelScope + Dispatchers.IO; wait until the
        // VM reports completion instead of sleeping a fixed duration.
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (!viewModel.importing.value && viewModel.importMessage.value != null) return
            Thread.sleep(10)
        }
        error("import did not finish: importing=${viewModel.importing.value} message=${viewModel.importMessage.value}")
    }

    @Test fun importEmptyFileReportsZeroRows() {
        val viewModel = vm()
        val uri = Uri.parse("content://test/empty.csv")
        serve(uri, "")
        viewModel.importCsv(uri)
        awaitImportDone(viewModel)
        assertEquals("Imported 0 rows, skipped 0 rows", viewModel.importMessage.value)
        assertFalse(viewModel.importing.value)
    }

    @Test fun importHeaderOnlyFileReportsZeroRows() {
        val viewModel = vm()
        val uri = Uri.parse("content://test/header.csv")
        serve(uri, "name,login_username,login_password,login_uri,login_totp\n")
        viewModel.importCsv(uri)
        awaitImportDone(viewModel)
        assertEquals("Imported 0 rows, skipped 0 rows", viewModel.importMessage.value)
    }

    @Test fun importBlankRowsOnlyReportsZeroRows() {
        val viewModel = vm()
        val uri = Uri.parse("content://test/blank.csv")
        serve(uri, "name,login_username,login_password\n,,\n,,\n")
        viewModel.importCsv(uri)
        awaitImportDone(viewModel)
        assertEquals("Imported 0 rows, skipped 0 rows", viewModel.importMessage.value)
    }
}
