package com.vayunmathur.passwords.platform

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.data.PasswordRepository
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PasswordsViewModelTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun vm() = PasswordsViewModel(app, PasswordRepository.get(app))

    @Test fun draftLifecycle() {
        val viewModel = vm()
        assertNull(viewModel.draft.value)
        viewModel.updateDraft { it.copy(name = "X") } // no draft yet: no-op
        assertNull(viewModel.draft.value)

        val seed = Password(id = 3, name = "GitHub")
        viewModel.initDraft(seed)
        assertEquals("GitHub", viewModel.draft.value?.name)

        // Same id does not clobber edits; a different id reseeds.
        viewModel.updateDraft { it.copy(name = "Edited") }
        viewModel.initDraft(Password(id = 3, name = "Other"))
        assertEquals("Edited", viewModel.draft.value?.name)
        viewModel.initDraft(Password(id = 4, name = "New"))
        assertEquals("New", viewModel.draft.value?.name)

        viewModel.clearDraft()
        assertNull(viewModel.draft.value)
    }

    @Test fun saveDraftWithoutDraftIsNoOp() {
        val viewModel = vm()
        viewModel.saveDraft()
        assertNull(viewModel.draft.value)
    }

    @Test fun copyToClipboardWritesSensitiveClip() {
        val viewModel = vm()
        viewModel.copyToClipboard("password", "s3cret")
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("s3cret", clipboard.primaryClip?.getItemAt(0)?.text)
        assertEquals("password", clipboard.primaryClip?.description?.label)
    }

    @Test fun initialStateIsEmptyAndNotLoaded() {
        val viewModel = vm()
        assertEquals(emptyList(), viewModel.passwords.value)
        assertEquals(emptyList(), viewModel.passkeys.value)
        assertEquals(false, viewModel.loaded.value)
        assertEquals(false, viewModel.importing.value)
        assertNull(viewModel.importMessage.value)
    }

    @Test fun factoryCreatesAndRejectsOtherClasses() {
        val factory = PasswordsViewModelFactory(app, PasswordRepository.get(app))
        val created = factory.create(PasswordsViewModel::class.java)
        assertTrue(created is PasswordsViewModel)
        assertFailsWith<IllegalArgumentException> {
            factory.create(OtherViewModel::class.java)
        }
    }

    @Test fun backupFormatProperties() {
        val format = vm().buildBackupFormat()
        assertEquals("passwords.kdbx", format.defaultFileName)
        assertEquals(true, format.needsPassword)
        assertEquals("application/octet-stream", format.mimeType)
    }
}

private class OtherViewModel : androidx.lifecycle.ViewModel()
