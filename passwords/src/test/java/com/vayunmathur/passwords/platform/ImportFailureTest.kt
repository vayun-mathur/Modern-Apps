package com.vayunmathur.passwords.platform

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.passwords.FakeAndroidKeyStore
import com.vayunmathur.passwords.data.PasswordRepository
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** A provider whose files can never be opened: every stream request returns null. */
class NullStreamProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = null
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImportFailureTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        Robolectric.buildContentProvider(NullStreamProvider::class.java)
            .create("com.test.nullstream")
    }

    @Test fun importFromUnopenableUriReportsFailure() {
        val viewModel = PasswordsViewModel(app, PasswordRepository.get(app))
        viewModel.importCsv(Uri.parse("content://com.test.nullstream/passwords.csv"))
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (!viewModel.importing.value && viewModel.importMessage.value != null) break
            Thread.sleep(10)
        }
        assertFalse(viewModel.importing.value)
        assertEquals(
            "Import failed: Unable to open selected file",
            viewModel.importMessage.value,
        )
    }
}
